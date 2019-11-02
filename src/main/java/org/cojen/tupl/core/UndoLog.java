/*
 *  Copyright (C) 2011-2017 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.core;

import java.io.IOException;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static java.lang.System.arraycopy;

import org.cojen.tupl.ClosedIndexException;
import org.cojen.tupl.CorruptDatabaseException;
import org.cojen.tupl.Cursor;
import org.cojen.tupl.DatabaseException;
import org.cojen.tupl.EventListener;
import org.cojen.tupl.EventType;
import org.cojen.tupl.Index;
import org.cojen.tupl.LockFailureException;
import org.cojen.tupl.Transaction;

import static org.cojen.tupl.core.PageOps.*;
import static org.cojen.tupl.core.Utils.*;

/**
 * Specialized stack used to record compensating actions for rolling back transactions. UndoLog
 * instances are created on a per-transaction basis -- they're not shared.
 *
 * @author Brian S O'Neill
 */
final class UndoLog implements DatabaseAccess {
    // Linked list of UndoLogs registered with Database.
    UndoLog mPrev;
    UndoLog mNext;

    /*
      UndoLog is persisted in Nodes. All multibyte types are little endian encoded.

      +----------------------------------------+
      | byte:   node type                      |  header
      | byte:   reserved (must be 0)           |
      | ushort: pointer to top entry           |
      | ulong:  lower node id                  |
      +----------------------------------------+
      | free space                             |
      -                                        -
      |                                        |
      +----------------------------------------+
      | log stack entries                      |
      -                                        -
      |                                        |
      +----------------------------------------+

      Stack entries are encoded from the tail end of the node towards the
      header. Entries without payloads are encoded with an opcode less than 16.
      All other types of entries are composed of three sections:

      +----------------------------------------+
      | byte:   opcode                         |
      | varint: payload length                 |
      | n:      payload                        |
      +----------------------------------------+

      Popping entries off the stack involves reading the opcode and moving
      forwards. Payloads which don't fit into the node spill over into the
      lower node(s).
    */

    static final int I_LOWER_NODE_ID = 4;
    private static final int HEADER_SIZE = 12;

    // Must be power of two.
    private static final int INITIAL_BUFFER_SIZE = 128;

    private static final byte OP_SCOPE_ENTER = (byte) 1;
    private static final byte OP_SCOPE_COMMIT = (byte) 2;

    @Deprecated // replaced with OP_LOG_COPY_C and OP_LOG_REF_C
    static final byte OP_COMMIT = (byte) 4;

    // Indicates that transaction has been committed and log is partially truncated.
    @Deprecated // replaced with OP_LOG_COPY_C and OP_LOG_REF_C
    static final byte OP_COMMIT_TRUNCATE = (byte) 5;

    // Indicates that transaction has been prepared for two-phase commit.
    static final byte OP_PREPARE = (byte) 6;

    // Same as OP_UNINSERT, except uses OP_ACTIVE_KEY. (ValueAccessor op)
    static final byte OP_UNCREATE = (byte) 12;

    // All ops less than 16 have no payload.
    private static final byte PAYLOAD_OP = (byte) 16;

    // Copy to another log from master log. Payload is transaction id, active
    // index id, buffer size (short type), and serialized buffer.
    private static final byte OP_LOG_COPY = (byte) 16;

    // Reference to another log from master log. Payload is transaction id,
    // active index id, length, node id, and top entry offset.
    private static final byte OP_LOG_REF = (byte) 17;

    // Payload is active index id.
    private static final byte OP_INDEX = (byte) 18;

    // Payload is key to delete to undo an insert.
    static final byte OP_UNINSERT = (byte) 19;

    // Payload is Node-encoded key/value entry to store, to undo an update.
    static final byte OP_UNUPDATE = (byte) 20;

    // Payload is Node-encoded key/value entry to store, to undo a delete.
    static final byte OP_UNDELETE = (byte) 21;

    // Payload is Node-encoded key and trash id, to undo a fragmented value delete.
    static final byte OP_UNDELETE_FRAGMENTED = (byte) 22;

    // Payload is a key for ValueAccessor operations.
    static final byte OP_ACTIVE_KEY = (byte) 23;

    // Payload is custom handler id and message.
    static final byte OP_CUSTOM = (byte) 24;

    private static final int LK_ADJUST = 5;

    // Payload is a (large) key and value to store, to undo an update.
    static final byte OP_UNUPDATE_LK = (byte) (OP_UNUPDATE + LK_ADJUST); //25

    // Payload is a (large) key and value to store, to undo a delete.
    static final byte OP_UNDELETE_LK = (byte) (OP_UNDELETE + LK_ADJUST); //26

    // Payload is a (large) key and trash id, to undo a fragmented value delete.
    static final byte OP_UNDELETE_LK_FRAGMENTED = (byte) (OP_UNDELETE_FRAGMENTED + LK_ADJUST); //27

    // Payload is the value length to undo a value extension. (ValueAccessor op)
    static final byte OP_UNEXTEND = (byte) 29;

    // Payload is the value length and position to undo value hole fill. (ValueAccessor op)
    static final byte OP_UNALLOC = (byte) 30;

    // Payload is the value position and bytes to undo a value write. (ValueAccessor op)
    static final byte OP_UNWRITE = (byte) 31;

    // Copy to a committed log from master log. Payload is transaction id, active index id,
    // buffer size (short type), and serialized buffer.
    private static final byte OP_LOG_COPY_C = (byte) 32;

    // Reference to a committed log from master log. Payload is transaction id, active index
    // id, length, node id, and top entry offset.
    private static final byte OP_LOG_REF_C = (byte) 33;

    // Payload is key to delete to recover an exclusive lock.
    static final byte OP_LOCK_EXCLUSIVE = 34;

    // Payload is key to delete to recover an upgradable lock.
    static final byte OP_LOCK_UPGRADABLE = 35;

    private final LocalDatabase mDatabase;
    final long mTxnId;

    // Number of bytes currently pushed into log.
    private long mLength;

    // Except for mLength, all field modifications during normal usage must be
    // performed while holding shared db commit lock. See writeToMaster method.

    private byte[] mBuffer;
    private int mBufferPos;

    // Top node, if required. Nodes are not used for logs which fit into local buffer.
    private Node mNode;
    private int mNodeTopPos;

    private long mActiveIndexId;

    // Active key is used for ValueAccessor operations.
    private byte[] mActiveKey;

    private byte mCommitted;

    UndoLog(LocalDatabase db, long txnId) {
        mDatabase = db;
        mTxnId = txnId;
    }

    @Override
    public LocalDatabase getDatabase() {
        return mDatabase;
    }

    /**
     * Ensures all entries are stored in persistable nodes, unless the log is empty. Caller
     * must hold db commit lock.
     *
     * @return top node id or 0 if log is empty
     */
    long persistReady() throws IOException {
        Node node = mNode;

        if (node != null) {
            node.acquireExclusive();
            try {
                mDatabase.markUnmappedDirty(node);
            } catch (Throwable e) {
                node.releaseExclusive();
                throw e;
            }
        } else {
            if (mLength == 0) {
                return 0;
            }
            // Note: Buffer cannot be null if length is non-zero.
            byte[] buffer = mBuffer;
            int pos = mBufferPos;
            int size = buffer.length - pos;
            mNode = node = allocUnevictableNode(0);
            var page = node.mPage;
            int newPos = pageSize(page) - size;
            p_copyFromArray(buffer, pos, page, newPos, size);
            // Set pointer to top entry.
            mNodeTopPos = newPos;
            mBuffer = null;
            mBufferPos = 0;
        }

        node.undoTop(mNodeTopPos);
        node.releaseExclusive();

        return mNode.id();
    }

    private int pageSize(/*P*/ byte[] page) {
        /*P*/ // [
        return page.length;
        /*P*/ // |
        /*P*/ // return mDatabase.pageSize();
        /*P*/ // ]
    }

    /**
     * Deletes just the top node, as part of database close sequence. Caller must hold
     * exclusive db commit lock.
     */
    void delete() {
        mLength = 0;
        mBufferPos = 0;
        mBuffer = null;
        Node node = mNode;
        if (node != null) {
            mNode = null;
            node.delete(mDatabase);
        }
    }

    /**
     * Called by LocalTransaction with db commit lock held.
     */
    void commit() {
        mCommitted = OP_LOG_COPY_C - OP_LOG_COPY;
    }

    /**
     * Called by LocalTransaction, which does not need to hold db commit lock.
     */
    void uncommit() {
        CommitLock.Shared shared = mDatabase.commitLock().acquireSharedUnchecked();
        mCommitted = 0;
        shared.release();
    }

    /**
     * If the transaction was committed, deletes any ghosts and truncates the log.
     *
     * @return true if transaction was committed
     */
    boolean recoveryCleanup() throws IOException {
        if (mCommitted != 0) {
            // Transaction was actually committed, but redo log is gone. This can happen when a
            // checkpoint completes in the middle of the transaction commit operation.
            if (mNode != null && mNodeTopPos == 0) {
                // This signals that the checkpoint captured the undo log in the middle of a
                // truncation, and any ghosts were already deleted.
                truncate();
            } else {
                // Deleting ghosts truncates the undo log as a side-effect.
                deleteGhosts();
            }
            return true;
        }

        // Look for deprecated commit ops.

        switch (peek(true)) {
        default:
            return false;
        case OP_COMMIT:
            deleteGhosts();
            return true;
        case OP_COMMIT_TRUNCATE:
            truncate();
            return true;
        }
    }

    /**
     * Caller must hold db commit lock.
     */
    final void pushUninsert(final long indexId, byte[] key) throws IOException {
        setActiveIndexId(indexId);
        doPush(OP_UNINSERT, key);
    }

    /**
     * Push an operation with a Node-encoded key and value, which might be fragmented. Caller
     * must hold db commit lock.
     *
     * @param op OP_UNUPDATE, OP_UNDELETE or OP_UNDELETE_FRAGMENTED
     */
    final void pushNodeEncoded(final long indexId, byte op, byte[] payload, int off, int len)
        throws IOException
    {
        setActiveIndexId(indexId);

        if ((payload[off] & 0xc0) == 0xc0) {
            // Key is fragmented and cannot be stored as-is, so expand it fully and switch to
            // using the "LK" op variant.
            var copy = p_transfer(payload);
            try {
                payload = Node.expandKeyAtLoc(this, copy, off, len, op != OP_UNDELETE_FRAGMENTED);
            } finally {
                p_delete(copy);
            }
            off = 0;
            len = payload.length;
            op += LK_ADJUST;
        }

        doPush(op, payload, off, len);
    }

    /**
     * Push an operation with a Node-encoded key and value, which might be fragmented. Caller
     * must hold db commit lock.
     *
     * @param op OP_UNUPDATE, OP_UNDELETE or OP_UNDELETE_FRAGMENTED
     */
    /*P*/ // [|
    /*P*/ // final void pushNodeEncoded(long indexId, byte op, long payloadPtr, int off, int len)
    /*P*/ //     throws IOException
    /*P*/ // {
    /*P*/ //     setActiveIndexId(indexId);
    /*P*/ // 
    /*P*/ //     byte[] payload;
    /*P*/ //     if ((DirectPageOps.p_byteGet(payloadPtr, off) & 0xc0) == 0xc0) {
    /*P*/ //         // Key is fragmented and cannot be stored as-is, so expand it fully and
    /*P*/ //         // switch to using the "LK" op variant.
    /*P*/ //         payload = _Node.expandKeyAtLoc
    /*P*/ //             (this, payloadPtr, off, len, op != OP_UNDELETE_FRAGMENTED);
    /*P*/ //         op += LK_ADJUST;
    /*P*/ //     } else {
    /*P*/ //         payload = new byte[len];
    /*P*/ //         DirectPageOps.p_copyToArray(payloadPtr, off, payload, 0, len);
    /*P*/ //     }
    /*P*/ // 
    /*P*/ //     doPush(op, payload);
    /*P*/ // }
    /*P*/ // ]

    private void setActiveIndexId(long indexId) throws IOException {
        long activeIndexId = mActiveIndexId;
        if (indexId != activeIndexId) {
            if (activeIndexId != 0) {
                byte[] payload = new byte[8];
                encodeLongLE(payload, 0, activeIndexId);
                doPush(OP_INDEX, payload, 0, 8, 1);
            }
            mActiveIndexId = indexId;
        }
    }

    /**
     * Caller must hold db commit lock.
     *
     * @return savepoint
     */
    long pushPrepare() throws IOException {
        doPush(OP_PREPARE);
        return mLength;
    }

    /**
     * Caller must hold db commit lock.
     */
    void pushCustom(int handlerId, byte[] message) throws IOException {
        byte[] payload = new byte[calcUnsignedVarIntLength(handlerId) + message.length];
        int pos = encodeUnsignedVarInt(payload, 0, handlerId);
        arraycopy(message, 0, payload, pos, message.length);
        doPush(OP_CUSTOM, payload);
    }

    /**
     * Caller must hold db commit lock.
     */
    void pushUncreate(long indexId, byte[] key) throws IOException {
        setActiveIndexIdAndKey(indexId, key);
        doPush(OP_UNCREATE);
    }

    /**
     * Caller must hold db commit lock.
     *
     * @param savepoint used to check if op isn't necessary
     */
    void pushUnextend(long savepoint, long indexId, byte[] key, long length) throws IOException {
        if (setActiveIndexIdAndKey(indexId, key) && savepoint < mLength) discardCheck: {
            // Check if op isn't necessary because it's action will be superceded by another.

            long unlen;

            Node node = mNode;
            if (node == null) {
                byte op = mBuffer[mBufferPos];
                if (op == OP_UNCREATE) {
                    return;
                }
                if (op != OP_UNEXTEND) {
                    break discardCheck;
                }
                int pos = mBufferPos + 1;
                int payloadLen = decodeUnsignedVarInt(mBuffer, pos);
                pos += calcUnsignedVarIntLength(payloadLen);
                IntegerRef.Value offsetRef = new IntegerRef.Value();
                offsetRef.value = pos;
                unlen = decodeUnsignedVarLong(mBuffer, offsetRef);
            } else {
                byte op = p_byteGet(mNode.mPage, mNodeTopPos);
                if (op == OP_UNCREATE) {
                    return;
                }
                if (op != OP_UNEXTEND) {
                    break discardCheck;
                }
                int pos = mNodeTopPos + 1;
                int payloadLen = p_uintGetVar(mNode.mPage, pos);
                pos += calcUnsignedVarIntLength(payloadLen);
                if (pos + payloadLen > pageSize(mNode.mPage)) {
                    // Don't bother decoding payload which spills into the next node.
                    break discardCheck;
                }
                IntegerRef.Value offsetRef = new IntegerRef.Value();
                offsetRef.value = pos;
                unlen = p_ulongGetVar(mNode.mPage, offsetRef);
            }

            if (unlen <= length) {
                // Existing unextend length will truncate at least as much.
                return;
            }
        }

        byte[] payload = new byte[9];
        int off = encodeUnsignedVarLong(payload, 0, length);
        doPush(OP_UNEXTEND, payload, 0, off);
    }

    /**
     * Caller must hold db commit lock.
     */
    void pushUnalloc(long indexId, byte[] key, long pos, long length) throws IOException {
        setActiveIndexIdAndKey(indexId, key);
        byte[] payload = new byte[9 + 9];
        int off = encodeUnsignedVarLong(payload, 0, length);
        off = encodeUnsignedVarLong(payload, off, pos);
        doPush(OP_UNALLOC, payload, 0, off);
    }

    /**
     * Caller must hold db commit lock.
     */
    void pushUnwrite(long indexId, byte[] key, long pos, byte[] b, int off, int len)
        throws IOException
    {
        setActiveIndexIdAndKey(indexId, key);
        int pLen = calcUnsignedVarLongLength(pos);
        int varIntLen = calcUnsignedVarIntLength(pLen + len);
        doPush(OP_UNWRITE, b, off, len, varIntLen, pLen);

        // Now encode the pos in the reserved region before the payload.
        Node node = mNode;
        int posOff = 1 + varIntLen;
        if (node != null) {
            p_ulongPutVar(node.mPage, mNodeTopPos + posOff, pos);
        } else {
            encodeUnsignedVarLong(mBuffer, mBufferPos + posOff, pos);
        }
    }

    /**
     * Caller must hold db commit lock.
     */
    /*P*/ // [|
    /*P*/ // void pushUnwrite(long indexId, byte[] key, long pos, long ptr, int off, int len)
    /*P*/ //     throws IOException
    /*P*/ // {
    /*P*/ //     byte[] b = new byte[len];
    /*P*/ //     DirectPageOps.p_copyToArray(ptr, off, b, 0, len);
    /*P*/ //     pushUnwrite(indexId, key, pos, b, 0, len);
    /*P*/ // }
    /*P*/ // ]

    /**
     * Caller must hold db commit lock.
     */
    final void pushLock(byte op, long indexId, byte[] key) throws IOException {
        setActiveIndexId(indexId);
        doPush(op, key);
    }

    /**
     * @return true if active index and key already match
     */
    private boolean setActiveIndexIdAndKey(long indexId, byte[] key) throws IOException {
        boolean result = true;

        long activeIndexId = mActiveIndexId;
        if (indexId != activeIndexId) {
            if (activeIndexId != 0) {
                byte[] payload = new byte[8];
                encodeLongLE(payload, 0, activeIndexId);
                doPush(OP_INDEX, payload, 0, 8, 1);
            }
            mActiveIndexId = indexId;
            result = false;
        }

        byte[] activeKey = mActiveKey;
        if (!Arrays.equals(key, activeKey)) {
            if (activeKey != null) {
                doPush(OP_ACTIVE_KEY, mActiveKey);
            }
            mActiveKey = key;
            result = false;
        }

        return result;
    }

    /**
     * Caller must hold db commit lock.
     */
    private void doPush(final byte op) throws IOException {
        doPush(op, EMPTY_BYTES, 0, 0, 0);
    }

    /**
     * Caller must hold db commit lock.
     */
    private void doPush(final byte op, final byte[] payload) throws IOException {
        doPush(op, payload, 0, payload.length);
    }

    /**
     * Caller must hold db commit lock.
     */
    private void doPush(final byte op, final byte[] payload, final int off, final int len)
        throws IOException
    {
        doPush(op, payload, off, len, calcUnsignedVarIntLength(len), 0);
    }

    /**
     * Caller must hold db commit lock.
     */
    private void doPush(final byte op, final byte[] payload, final int off, final int len,
                        final int varIntLen)
        throws IOException
    {
        doPush(op, payload, off, len, varIntLen, 0);
    }

    /**
     * Caller must hold db commit lock.
     *
     * @param pLen space to reserve before the payload; must be accounted for in varIntLen
     */
    private void doPush(final byte op, final byte[] payload, final int off, final int len,
                        final int varIntLen, final int pLen)
        throws IOException
    {
        final int encodedLen = 1 + varIntLen + pLen + len;

        Node node = mNode;
        if (node != null) {
            // Push into allocated node, which must be marked dirty.
            node.acquireExclusive();
            try {
                mDatabase.markUnmappedDirty(node);
            } catch (Throwable e) {
                node.releaseExclusive();
                throw e;
            }
        } else quick: {
            // Try to push into a local buffer before allocating a node.
            byte[] buffer = mBuffer;
            int pos;
            if (buffer == null) {
                int newCap = Math.max(INITIAL_BUFFER_SIZE, roundUpPower2(encodedLen));
                int pageSize = mDatabase.pageSize();
                if (newCap <= (pageSize >> 1)) {
                    mBuffer = buffer = new byte[newCap];
                    mBufferPos = pos = newCap;
                } else {
                    // Required capacity is large, so just use a node.
                    mNode = node = allocUnevictableNode(0);
                    // Set pointer to top entry (none at the moment).
                    mNodeTopPos = pageSize;
                    break quick;
                }
            } else {
                pos = mBufferPos;
                if (pos < encodedLen) {
                    final int size = buffer.length - pos;
                    int newCap = roundUpPower2(encodedLen + size);
                    if (newCap < 0) {
                        newCap = Integer.MAX_VALUE;
                    } else {
                        newCap = Math.max(buffer.length << 1, newCap);
                    }
                    if (newCap <= (mDatabase.pageSize() >> 1)) {
                        byte[] newBuf = new byte[newCap];
                        int newPos = newCap - size;
                        arraycopy(buffer, pos, newBuf, newPos, size);
                        mBuffer = buffer = newBuf;
                        mBufferPos = pos = newPos;
                    } else {
                        // Required capacity is large, so just use a node.
                        mNode = node = allocUnevictableNode(0);
                        var page = node.mPage;
                        int newPos = pageSize(page) - size;
                        p_copyFromArray(buffer, pos, page, newPos, size);
                        // Set pointer to top entry.
                        mNodeTopPos = newPos;
                        mBuffer = null;
                        mBufferPos = 0;
                        break quick;
                    }
                }
            }

            pos -= encodedLen;
            buffer[pos] = op;
            if (op >= PAYLOAD_OP) {
                int payloadPos = encodeUnsignedVarInt(buffer, pos + 1, pLen + len) + pLen;
                arraycopy(payload, off, buffer, payloadPos, len);
            }
            mBufferPos = pos;
            mLength += encodedLen;
            return;
        }

        int pos = mNodeTopPos;
        int available = pos - HEADER_SIZE;
        if (available >= encodedLen) {
            pos -= encodedLen;
            var page = node.mPage;
            p_bytePut(page, pos, op);
            if (op >= PAYLOAD_OP) {
                int payloadPos = p_uintPutVar(page, pos + 1, pLen + len) + pLen;
                p_copyFromArray(payload, off, page, payloadPos, len);
            }
            node.releaseExclusive();
            mNodeTopPos = pos;
            mLength += encodedLen;
            return;
        }

        // Payload doesn't fit into node, so break it up.
        int remaining = len;

        while (true) {
            int amt = Math.min(available, remaining);
            pos -= amt;
            available -= amt;
            remaining -= amt;
            var page = node.mPage;
            p_copyFromArray(payload, off + remaining, page, pos, amt);

            if (remaining <= 0 && available >= (encodedLen - len)) {
                if (varIntLen > 0) {
                    p_uintPutVar(page, pos -= varIntLen + pLen, pLen + len);
                }
                p_bytePut(page, --pos, op);
                node.releaseExclusive();
                break;
            }

            Node newNode;
            try {
                newNode = allocUnevictableNode(node.id());
            } catch (Throwable e) {
                // Undo the damage.
                while (node != mNode) {
                    node = popNode(node, true);
                }
                node.releaseExclusive();
                throw e;
            }

            node.undoTop(pos);
            mDatabase.nodeMapPut(node);
            node.releaseExclusive();
            node.makeEvictable();

            node = newNode;
            pos = pageSize(page);
            available = pos - HEADER_SIZE;
        }

        mNode = node;
        mNodeTopPos = pos;
        mLength += encodedLen;
    }

    /**
     * Caller does not need to hold db commit lock.
     *
     * @return savepoint
     */
    final long scopeEnter() throws IOException {
        final CommitLock.Shared shared = mDatabase.commitLock().acquireShared();
        try {
            long savepoint = mLength;
            doScopeEnter();
            return savepoint;
        } finally {
            shared.release();
        }
    }

    /**
     * Caller must hold db commit lock.
     */
    final void doScopeEnter() throws IOException {
        doPush(OP_SCOPE_ENTER);
    }

    /**
     * Caller does not need to hold db commit lock.
     *
     * @return savepoint
     */
    final long scopeCommit() throws IOException {
        final CommitLock.Shared shared = mDatabase.commitLock().acquireShared();
        try {
            doPush(OP_SCOPE_COMMIT);
            return mLength;
        } finally {
            shared.release();
        }
    }

    /**
     * Rollback all log entries to the given savepoint. Pass zero to rollback
     * everything. Caller does not need to hold db commit lock.
     */
    final void scopeRollback(long savepoint) throws IOException {
        final CommitLock.Shared shared = mDatabase.commitLock().acquireShared();
        try {
            if (savepoint < mLength) {
                // Rollback the entire scope, including the enter op.
                doRollback(savepoint);
            }
        } finally {
            shared.release();
        }
    }

    /**
     * Truncate all log entries. Caller does not need to hold db commit lock.
     */
    final void truncate() throws IOException {
        final CommitLock commitLock = mDatabase.commitLock();
        CommitLock.Shared shared = commitLock.acquireShared();
        try {
            shared = doTruncate(commitLock, shared);
        } finally {
            shared.release();
        }
    }

    /**
     * Truncate all log entries. Caller must hold db commit lock.
     */
    final CommitLock.Shared doTruncate(CommitLock commitLock, final CommitLock.Shared shared)
        throws IOException
    {
        if (mLength > 0) {
            Node node = mNode;
            if (node == null) {
                mBufferPos = mBuffer.length;
            } else {
                node.acquireExclusive();
                while (true) {
                    try {
                        if ((node = popNode(node, true)) == null) {
                            break;
                        }
                    } catch (Throwable e) {
                        // Caller will release the commit lock, and so these fields must be
                        // cleared. See comments below.
                        mNodeTopPos = 0;
                        mActiveKey = null;
                        throw e;
                    }

                    if (commitLock.hasQueuedThreads()) {
                        // Release and re-acquire, to unblock any threads waiting for
                        // checkpoint to begin. In case the checkpoint writes out the node(s)
                        // before truncation finishes, use a top position of zero to indicate
                        // that recovery should simply complete the truncation. A position of
                        // zero within the node is otherwise illegal, since it refers to the
                        // header, which doesn't contain undo operations.
                        mNodeTopPos = 0;
                        // Clear this early, to prevent writeToMaster from attempting to push
                        // anything to the log. The key isn't needed to complete truncation.
                        mActiveKey = null;
                        shared.release();
                        commitLock.acquireShared(shared);
                    }
                }
            }
            mLength = 0;
            mActiveIndexId = 0;
            mActiveKey = null;
        }

        return shared;
    }

    /**
     * Rollback all log entries, and then discard this UndoLog object. Caller does not need to
     * hold db commit lock.
     */
    final void rollback() throws IOException {
        if (mLength == 0) {
            // Nothing to rollback, so return quickly.
            return;
        }

        final CommitLock.Shared shared = mDatabase.commitLock().acquireShared();
        try {
            mCommitted = 0;
            doRollback(0);
        } finally {
            shared.release();
        }
    }

    /**
     * @param savepoint must be less than mLength
     */
    private void doRollback(long savepoint) throws IOException {
        new PopAll() {
            Index activeIndex;

            @Override
            public boolean accept(byte op, byte[] entry) throws IOException {
                activeIndex = undo(activeIndex, op, entry);
                return true;
            }
        }.go(true, savepoint);
    }

    final void rollbackToPrepare() throws IOException {
        // RTP: "Rollback To Prepare" helper class.
        class RTP extends IOException implements Popper {
            Index activeIndex;

            @Override
            public boolean accept(byte op, byte[] entry) throws IOException {
                if (op == OP_PREPARE) {
                    // Found the prepare operation, but don't pop it.
                    throw this;
                }
                activeIndex = undo(activeIndex, op, entry);
                return true;
            }

            // Disable stack trace capture, since it's not required.
            @Override
            public Throwable fillInStackTrace() {
                return this;
            }
        };

        RTP rtp = new RTP();

        try {
            while (pop(true, rtp));
            throw new IllegalStateException("Prepare operation not found");
        } catch (RTP r) {
            // Expected.
        }
    }

    /**
     * Truncate all log entries, and delete any ghosts that were created. Only
     * to be called during recovery.
     */
    final void deleteGhosts() throws IOException {
        new PopAll() {
            Index activeIndex;

            @Override
            public boolean accept(byte op, byte[] entry) throws IOException {
                switch (op) {
                default:
                    throw new DatabaseException("Unknown undo log entry type: " + op);

                case OP_SCOPE_ENTER:
                case OP_SCOPE_COMMIT:
                case OP_COMMIT:
                case OP_COMMIT_TRUNCATE:
                case OP_PREPARE:
                case OP_UNCREATE:
                case OP_UNINSERT:
                case OP_UNUPDATE:
                case OP_ACTIVE_KEY:
                case OP_CUSTOM:
                case OP_UNUPDATE_LK:
                case OP_UNEXTEND:
                case OP_UNALLOC:
                case OP_UNWRITE:
                case OP_LOCK_EXCLUSIVE:
                case OP_LOCK_UPGRADABLE:
                    // Ignore.
                    break;

                case OP_INDEX:
                    mActiveIndexId = decodeLongLE(entry, 0);
                    activeIndex = null;
                    break;

                case OP_UNDELETE:
                case OP_UNDELETE_FRAGMENTED:
                    // Since transaction was committed, don't insert an entry
                    // to undo a delete, but instead delete the ghost.
                    byte[] key = decodeNodeKey(entry);
                    activeIndex = doUndo(activeIndex, ix -> {
                        BTreeCursor cursor = new BTreeCursor((BTree) ix, null);
                        try {
                            cursor.deleteGhost(key);
                        } catch (Throwable e) {
                            throw closeOnFailure(cursor, e);
                        }
                    });
                    break;

                case OP_UNDELETE_LK:
                case OP_UNDELETE_LK_FRAGMENTED:
                    // Since transaction was committed, don't insert an entry
                    // to undo a delete, but instead delete the ghost.
                    key = new byte[decodeUnsignedVarInt(entry, 0)];
                    arraycopy(entry, calcUnsignedVarIntLength(key.length), key, 0, key.length);
                    activeIndex = doUndo(activeIndex, ix -> {
                        BTreeCursor cursor = new BTreeCursor((BTree) ix, null);
                        try {
                            cursor.deleteGhost(key);
                        } catch (Throwable e) {
                            throw closeOnFailure(cursor, e);
                        }
                    });
                    break;
                }

                return true;
            }
        }.go(true, 0);
    }

    /**
     * @param activeIndex active index, possibly null
     * @param op undo op
     * @return new active index, possibly null
     */
    private Index undo(Index activeIndex, byte op, byte[] entry) throws IOException {
        switch (op) {
        default:
            throw new DatabaseException("Unknown undo log entry type: " + op);

        case OP_SCOPE_ENTER:
        case OP_SCOPE_COMMIT:
        case OP_COMMIT:
        case OP_COMMIT_TRUNCATE:
        case OP_PREPARE:
        case OP_LOCK_EXCLUSIVE:
        case OP_LOCK_UPGRADABLE:
            // Only needed by recovery.
            break;

        case OP_INDEX:
            mActiveIndexId = decodeLongLE(entry, 0);
            activeIndex = null;
            break;

        case OP_UNCREATE:
            activeIndex = doUndo(activeIndex, ix -> ix.delete(Transaction.BOGUS, mActiveKey));
            break;

        case OP_UNINSERT:
            activeIndex = doUndo(activeIndex, ix -> ix.delete(Transaction.BOGUS, entry));
            break;

        case OP_UNUPDATE:
        case OP_UNDELETE: {
            byte[][] pair = decodeNodeKeyValuePair(entry);
            activeIndex = doUndo(activeIndex, ix -> ix.store(Transaction.BOGUS, pair[0], pair[1]));
            break;
        }

        case OP_UNUPDATE_LK:
        case OP_UNDELETE_LK: {
            byte[] key = new byte[decodeUnsignedVarInt(entry, 0)];
            int keyLoc = calcUnsignedVarIntLength(key.length);
            arraycopy(entry, keyLoc, key, 0, key.length);

            int valueLoc = keyLoc + key.length;
            byte[] value = new byte[entry.length - valueLoc];
            arraycopy(entry, valueLoc, value, 0, value.length);

            activeIndex = doUndo(activeIndex, ix -> ix.store(Transaction.BOGUS, key, value));
            break;
        }

        case OP_UNDELETE_FRAGMENTED:
            activeIndex = doUndo(activeIndex, ix -> {
                FragmentedTrash.remove(mDatabase.fragmentedTrash(), mTxnId, (BTree) ix, entry);
            });
            break;

        case OP_UNDELETE_LK_FRAGMENTED: {
            byte[] key = new byte[decodeUnsignedVarInt(entry, 0)];
            int keyLoc = calcUnsignedVarIntLength(key.length);
            arraycopy(entry, keyLoc, key, 0, key.length);

            int tidLoc = keyLoc + key.length;
            int tidLen = entry.length - tidLoc;
            byte[] trashKey = new byte[8 + tidLen];
            encodeLongBE(trashKey, 0, mTxnId);
            arraycopy(entry, tidLoc, trashKey, 8, tidLen);

            activeIndex = doUndo(activeIndex, ix -> {
                FragmentedTrash.remove(mDatabase.fragmentedTrash(), (BTree) ix, key, trashKey);
            });
            break;
        }

        case OP_CUSTOM:
            int handlerId = decodeUnsignedVarInt(entry, 0);
            int messageLoc = calcUnsignedVarIntLength(handlerId);
            byte[] message = new byte[entry.length - messageLoc];
            arraycopy(entry, messageLoc, message, 0, message.length);
            mDatabase.findCustomHandler(handlerId).undo(null, message);
            break;

        case OP_ACTIVE_KEY:
            mActiveKey = entry;
            break;

        case OP_UNEXTEND:
            long length = decodeUnsignedVarLong(entry, new IntegerRef.Value());
            activeIndex = doUndo(activeIndex, ix -> {
                try (Cursor c = ix.newAccessor(Transaction.BOGUS, mActiveKey)) {
                    c.valueLength(length);
                }
            });
            break;

        case OP_UNALLOC:
            IntegerRef offsetRef = new IntegerRef.Value();
            length = decodeUnsignedVarLong(entry, offsetRef);
            long pos = decodeUnsignedVarLong(entry, offsetRef);
            activeIndex = doUndo(activeIndex, ix -> {
                try (Cursor c = ix.newAccessor(Transaction.BOGUS, mActiveKey)) {
                    c.valueClear(pos, length);
                }
            });
            break;

        case OP_UNWRITE:
            offsetRef = new IntegerRef.Value();
            pos = decodeUnsignedVarLong(entry, offsetRef);
            int off = offsetRef.get();
            activeIndex = doUndo(activeIndex, ix -> {
                try (Cursor c = ix.newAccessor(Transaction.BOGUS, mActiveKey)) {
                    c.valueWrite(pos, entry, off, entry.length - off);
                }
            });
            break;
        }

        return activeIndex;
    }

    private byte[] decodeNodeKey(byte[] entry) throws IOException {
        byte[] key;
        var pentry = p_transfer(entry);
        try {
            key = Node.retrieveKeyAtLoc(this, pentry, 0);
        } finally {
            p_delete(pentry);
        }
        return key;
    }

    private byte[][] decodeNodeKeyValuePair(byte[] entry) throws IOException {
        byte[][] pair;
        var pentry = p_transfer(entry);
        try {
            pair = Node.retrieveKeyValueAtLoc(this, pentry, 0);
        } finally {
            p_delete(pentry);
        }
        return pair;
    }

    @FunctionalInterface
    private static interface UndoTask {
        void run(Index activeIndex) throws IOException;
    }

    /**
     * @return null if index was deleted
     */
    private Index doUndo(Index activeIndex, UndoTask task) throws IOException {
        while ((activeIndex = findIndex(activeIndex)) != null) {
            try {
                task.run(activeIndex);
                break;
            } catch (ClosedIndexException e) {
                // User closed the shared index reference, so re-open it.
                activeIndex = null;
            }
        }
        return activeIndex;
    }

    /**
     * @return null if index was deleted
     */
    private Index findIndex(Index activeIndex) throws IOException {
        if (activeIndex == null || activeIndex.isClosed()) {
            activeIndex = mDatabase.anyIndexById(mActiveIndexId);
        }
        return activeIndex;
    }

    /**
     * @param delete true to delete empty nodes
     * @return last pushed op, or 0 if empty
     */
    @Deprecated
    private byte peek(boolean delete) throws IOException {
        Node node = mNode;
        if (node == null) {
            return (mBuffer == null || mBufferPos >= mBuffer.length) ? 0 : mBuffer[mBufferPos];
        }

        node.acquireExclusive();
        while (true) {
            var page = node.mPage;
            if (mNodeTopPos < pageSize(page)) {
                byte op = p_byteGet(page, mNodeTopPos);
                node.releaseExclusive();
                return op;
            }
            if ((node = popNode(node, delete)) == null) {
                return 0;
            }
        }
    }

    private static interface Popper {
        /**
         * @return false if popping should stop
         */
        boolean accept(byte op, byte[] entry) throws IOException;
    }

    /**
     * Implementation of Popper which processes all undo operations up to a savepoint. Any
     * exception thrown by the accept method preserves the state of the log. That is, the
     * failed operation isn't discarded, and it remains as the top item.
     */
    private abstract class PopAll implements Popper {
        /**
         * @param delete true to delete nodes
         * @param savepoint must be less than mLength; pass 0 to pop the entire stack
         */
        void go(boolean delete, long savepoint) throws IOException {
            while (pop(delete, this) && savepoint < mLength);
        }
    }

    /**
     * Implementation of Popper which copies the undo entry before processing it. Any exception
     * thrown during processing (by the user of the class) won't preserve the state of the log.
     * As a result, this variant should only be used when checkpoints will never run, like
     * during recovery.
     */
    private class PopOne implements Popper {
        byte mOp;
        byte[] mEntry;

        @Override
        public boolean accept(byte op, byte[] entry) throws IOException {
            mOp = op;
            mEntry = entry;
            return true;
        }
    }

    /**
     * Caller must hold db commit lock. The given popper is called such that if it throws any
     * exception, the operation is effectively un-popped (no state changes).
     *
     * @param delete true to delete nodes
     * @param popper called at most once per call to this method
     * @return false if nothing left
     */
    private final boolean pop(boolean delete, Popper popper) throws IOException {
        final byte op;

        Node node = mNode;
        if (node == null) {
            byte[] buffer = mBuffer;
            int pos;
            if (buffer == null || (pos = mBufferPos) >= buffer.length) {
                mLength = 0;
                return false;
            }
            boolean result;
            op = buffer[pos++];
            if (op < PAYLOAD_OP) {
                result = popper.accept(op, EMPTY_BYTES);
                mBufferPos = pos;
                mLength -= 1;
            } else {
                int payloadLen = decodeUnsignedVarInt(buffer, pos);
                int varIntLen = calcUnsignedVarIntLength(payloadLen);
                pos += varIntLen;
                byte[] entry = new byte[payloadLen];
                arraycopy(buffer, pos, entry, 0, payloadLen);
                result = popper.accept(op, entry);
                mBufferPos = pos + payloadLen;
                mLength -= 1 + varIntLen + payloadLen;
            }
            return result;
        }

        node.acquireExclusive();
        /*P*/ byte[] page;
        while (true) {
            page = node.mPage;
            if (mNodeTopPos < pageSize(page)) {
                break;
            }
            if ((node = popNode(node, delete)) == null) {
                mLength = 0;
                return false;
            }
        }

        int nodeTopPos = mNodeTopPos;
        op = p_byteGet(page, nodeTopPos++);

        if (op < PAYLOAD_OP) {
            boolean result = popper.accept(op, EMPTY_BYTES);
            mNodeTopPos = nodeTopPos;
            mLength -= 1;
            if (nodeTopPos >= pageSize(page)) {
                node = popNode(node, delete);
            }
            if (node != null) {
                node.releaseExclusive();
            }
            return result;
        }

        long length = mLength;

        int payloadLen = p_uintGetVar(page, nodeTopPos);
        int varIntLen = p_uintVarSize(payloadLen);
        nodeTopPos += varIntLen;
        length -= 1 + varIntLen + payloadLen;

        final byte[] entry = new byte[payloadLen];
        int entryPos = 0;

        while (true) {
            int avail = Math.min(payloadLen, pageSize(page) - nodeTopPos);
            p_copyToArray(page, nodeTopPos, entry, entryPos, avail);
            payloadLen -= avail;
            nodeTopPos += avail;
            entryPos += avail;

            if (nodeTopPos >= pageSize(page)) {
                long lowerNodeId = p_longGetLE(node.mPage, I_LOWER_NODE_ID);
                node.releaseExclusive();
                if (lowerNodeId == 0) {
                    node = null;
                    nodeTopPos = 0;
                } else {
                    LocalDatabase db = mDatabase;
                    node = db.nodeMapGetExclusive(lowerNodeId);
                    if (node == null) {
                        // Node was evicted, so reload it.
                        node = readUndoLogNode(db, lowerNodeId, 0);
                        db.nodeMapPut(node);
                    }
                    nodeTopPos = node.undoTop();
                }
            }

            if (payloadLen <= 0) {
                break;
            }

            if (node == null) {
                throw new CorruptDatabaseException("Remainder of undo log is missing");
            }

            page = node.mPage;

            // Payloads which spill over should always continue into a node which is full. If
            // the top position is actually at the end, then it likely references a
            // OP_COMMIT_TRUNCATE operation, in which case the transaction has actully
            // committed, and full decoding of the undo log is unnecessary or impossible.
            if (mNodeTopPos == pageSize(page) - 1 &&
                p_byteGet(page, mNodeTopPos) == OP_COMMIT_TRUNCATE)
            {
                break;
            }
        }

        // At this point, the node variable refers to the top node which must remain after the
        // consumed nodes are popped. It can be null if all nodes must be popped. Capture the
        // id to stop at before releasing the latch, after which it can be evicted and change.

        long nodeId = 0;
        if (node != null) {
            nodeId = node.id();
            node.releaseExclusive();
        }

        boolean result = popper.accept(op, entry);

        Node n = mNode;
        if (node != n) {
            // Now pop as many nodes as necessary.
            try {
                n.acquireExclusive();
                while (true) {
                    n = popNode(n, delete);
                    if (n == null) {
                        if (nodeId != 0) {
                            throw new AssertionError();
                        }
                        break;
                    }
                    if (n.id() == nodeId) {
                        n.releaseExclusive();
                        break;
                    }
                }
            } catch (Throwable e) {
                // Panic.
                mDatabase.close(e);
                throw e;
            }
        }

        mNodeTopPos = nodeTopPos;
        mLength = length;

        return result;
    }

    /**
     * @param parent latched parent node
     * @param delete true to delete the parent node too
     * @return current (latched) mNode; null if none left
     */
    private Node popNode(Node parent, boolean delete) throws IOException {
        Node lowerNode = null;
        long lowerNodeId = p_longGetLE(parent.mPage, I_LOWER_NODE_ID);
        if (lowerNodeId != 0) {
            lowerNode = mDatabase.nodeMapGetAndRemove(lowerNodeId);
            if (lowerNode != null) {
                lowerNode.makeUnevictable();
            } else {
                // Node was evicted, so reload it.
                try {
                    lowerNode = readUndoLogNode(mDatabase, lowerNodeId);
                } catch (Throwable e) {
                    parent.releaseExclusive();
                    throw e;
                }
            }
        }

        parent.makeEvictable();

        if (delete) {
            LocalDatabase db = mDatabase;
            // Safer to never recycle undo log nodes. Keep them until the next checkpoint, when
            // there's a guarantee that the master undo log will not reference them anymore.
            // Of course it's fine to recycle pages from master undo log itself, which is the
            // only one with a transaction id of zero.
            db.deleteNode(parent, mTxnId == 0);
        } else {
            parent.releaseExclusive();
        }

        mNode = lowerNode;
        mNodeTopPos = lowerNode == null ? 0 : lowerNode.undoTop();

        return lowerNode;
    }

    /**
     * Caller must hold db commit lock.
     */
    private Node allocUnevictableNode(long lowerNodeId) throws IOException {
        Node node = mDatabase.allocDirtyNode(NodeGroup.MODE_UNEVICTABLE);
        node.type(Node.TYPE_UNDO_LOG);
        p_longPutLE(node.mPage, I_LOWER_NODE_ID, lowerNodeId);
        return node;
    }

    /**
     * Caller must hold exclusive db commit lock.
     *
     * @param workspace temporary buffer, allocated on demand
     * @return new or original workspace instance
     */
    final byte[] writeToMaster(UndoLog master, byte[] workspace) throws IOException {
        if (mActiveKey != null) {
            doPush(OP_ACTIVE_KEY, mActiveKey);
            // Set to null to reduce redundant pushes if transaction is long lived and is
            // written to the master multiple times.
            mActiveKey = null;
        }

        Node node = mNode;
        if (node == null) {
            byte[] buffer = mBuffer;
            if (buffer == null) {
                return workspace;
            }
            int pos = mBufferPos;
            int bsize = buffer.length - pos;
            if (bsize == 0) {
                return workspace;
            }
            // TODO: Consider calling persistReady if UndoLog is still in a buffer next time.
            final int psize = (8 + 8 + 2) + bsize;
            if (workspace == null || workspace.length < psize) {
                workspace = new byte[Math.max(INITIAL_BUFFER_SIZE, roundUpPower2(psize))];
            }
            writeHeaderToMaster(workspace);
            encodeShortLE(workspace, (8 + 8), bsize);
            arraycopy(buffer, pos, workspace, (8 + 8 + 2), bsize);
            master.doPush((byte) (OP_LOG_COPY + mCommitted), workspace, 0, psize);
        } else {
            if (workspace == null) {
                workspace = new byte[INITIAL_BUFFER_SIZE];
            }
            writeHeaderToMaster(workspace);
            encodeLongLE(workspace, (8 + 8), mLength);
            encodeLongLE(workspace, (8 + 8 + 8), node.id());
            encodeShortLE(workspace, (8 + 8 + 8 + 8), mNodeTopPos);
            master.doPush((byte) (OP_LOG_REF + mCommitted), workspace, 0, (8 + 8 + 8 + 8 + 2), 1);
        }

        return workspace;
    }

    private void writeHeaderToMaster(byte[] workspace) {
        encodeLongLE(workspace, 0, mTxnId);
        encodeLongLE(workspace, 8, mActiveIndexId);
    }

    static UndoLog recoverMasterUndoLog(LocalDatabase db, long nodeId) throws IOException {
        UndoLog log = new UndoLog(db, 0);
        // Length is not recoverable.
        log.mLength = Long.MAX_VALUE;
        log.mNode = readUndoLogNode(db, nodeId);
        log.mNodeTopPos = log.mNode.undoTop();
        log.mNode.releaseExclusive();
        return log;
    }

    /**
     * Recover transactions which were recorded by this master log, keyed by
     * transaction id. Recovered transactions have a NO_REDO durability mode.
     * All transactions are registered, and so they must be reset after
     * recovery is complete. Master log is truncated as a side effect of
     * calling this method.
     *
     * @param debugListener optional
     * @param trace when true, log all recovered undo operations to debugListener
     */
    void recoverTransactions(EventListener debugListener, boolean trace,
                             LHashTable.Obj<LocalTransaction> txns)
        throws IOException
    {
        new PopAll() {
            @Override
            public boolean accept(byte op, byte[] entry) throws IOException {
                UndoLog log = recoverUndoLog(op, entry);

                if (debugListener != null) {
                    debugListener.notify
                        (EventType.DEBUG,
                         "Recovered transaction undo log: " +
                         "txnId=%1$d, length=%2$d, bufferPos=%3$d, " +
                         "nodeId=%4$d, nodeTopPos=%5$d, activeIndexId=%6$d, committed=%7$s",
                         log.mTxnId, log.mLength, log.mBufferPos,
                         log.mNode == null ? 0 : log.mNode.id(), log.mNodeTopPos,
                         log.mActiveIndexId, log.mCommitted != 0);
                }

                LocalTransaction txn = log.recoverTransaction(debugListener, trace);

                // Reload the UndoLog, since recoverTransaction consumes it all.
                txn.recoveredUndoLog(recoverUndoLog(op, entry));
                txn.attach("recovery");

                txns.insert(log.mTxnId).value = txn;

                return true;
            }
        }.go(true, 0);
    }

    /**
     * Method consumes entire log as a side-effect.
     */
    private final LocalTransaction recoverTransaction(EventListener debugListener, boolean trace)
        throws IOException
    {
        if (mNode != null && mNodeTopPos == 0) {
            // The checkpoint captured a committed log in the middle of truncation. The
            // recoveryCleanup method will finish the truncation.
            return new LocalTransaction(mDatabase, mTxnId, 0);
        }

        PopOne popper = new PopOne();
        Scope scope = new Scope();

        // Scopes are recovered in the opposite order in which they were
        // created. Gather them in a stack to reverse the order.
        Deque<Scope> scopes = new ArrayDeque<>();
        scopes.addFirst(scope);

        boolean acquireLocks = true;
        int depth = 1;

        // Blindly assume trash must be deleted. No harm if none exists.
        int hasState = LocalTransaction.HAS_TRASH;

        loop: while (mLength > 0) {
            if (!pop(false, popper)) {
                // Undo log would have to be corrupt for this case to occur.
                break;
            }

            byte op = popper.mOp;
            byte[] entry = popper.mEntry;

            if (trace) {
                traceOp(debugListener, op, entry);
            }

            switch (op) {
            default:
                throw new DatabaseException("Unknown undo log entry type: " + op);

            case OP_COMMIT:
                // Handled by Transaction.recoveryCleanup, but don't acquire
                // locks. This avoids deadlocks with later transactions.
                acquireLocks = false;
                break;

            case OP_COMMIT_TRUNCATE:
                // Skip examining the rest of the log. It will likely appear to be corrupt
                // anyhow due to the OP_COMMIT_TRUNCATE having overwritten existing data.
                if (mNode != null) {
                    mNode.makeEvictable();
                    mNode = null;
                    mNodeTopPos = 0;
                }
                break loop;

            case OP_PREPARE:
                if ((hasState & LocalTransaction.HAS_PREPARE) == 0) {
                    // Only need to recover the last prepare key.
                    byte[] key = Locker.createPrepareKey(mTxnId);
                    scope.addExclusiveLock(Tree.PREPARE_LOCK_ID, key);
                    hasState |= LocalTransaction.HAS_PREPARE;
                }
                break;

            case OP_SCOPE_ENTER:
                depth++;
                if (depth > scopes.size()) {
                    scope.mSavepoint = mLength;
                    scope = new Scope();
                    scopes.addFirst(scope);
                }
                break;

            case OP_SCOPE_COMMIT:
                depth--;
                break;

            case OP_INDEX:
                mActiveIndexId = decodeLongLE(entry, 0);
                break;

            case OP_UNINSERT:
            case OP_LOCK_EXCLUSIVE:
                scope.addExclusiveLock(mActiveIndexId, entry);
                break;

            case OP_LOCK_UPGRADABLE:
                scope.addUpgradableLock(mActiveIndexId, entry);
                break;

            case OP_UNUPDATE:
            case OP_UNDELETE:
            case OP_UNDELETE_FRAGMENTED: {
                byte[] key = decodeNodeKey(entry);
                Lock lock = scope.addExclusiveLock(mActiveIndexId, key);
                if (op != OP_UNUPDATE) {
                    // Indicate that a ghost must be deleted when the transaction is
                    // committed. When the frame is uninitialized, the Node.deleteGhost
                    // method uses the slow path and searches for the entry.
                    lock.setGhostFrame(new GhostFrame());
                }
                break;
            }

            case OP_UNUPDATE_LK:
            case OP_UNDELETE_LK:
            case OP_UNDELETE_LK_FRAGMENTED: {
                byte[] key = new byte[decodeUnsignedVarInt(entry, 0)];
                arraycopy(entry, calcUnsignedVarIntLength(key.length), key, 0, key.length);
                Lock lock = scope.addExclusiveLock(mActiveIndexId, key);
                if (op != OP_UNUPDATE_LK) {
                    // Indicate that a ghost must be deleted when the transaction is
                    // committed. When the frame is uninitialized, the Node.deleteGhost
                    // method uses the slow path and searches for the entry.
                    lock.setGhostFrame(new GhostFrame());
                }
                break;
            }

            case OP_CUSTOM:
                break;

            case OP_ACTIVE_KEY:
                mActiveKey = entry;
                break;

            case OP_UNCREATE:
            case OP_UNEXTEND:
            case OP_UNALLOC:
            case OP_UNWRITE:
                if (mActiveKey != null) {
                    scope.addExclusiveLock(mActiveIndexId, mActiveKey);
                    // Avoid creating a huge list of redundant Lock objects.
                    mActiveKey = null;
                }
                break;
            }
        }

        LocalTransaction txn = new LocalTransaction(mDatabase, mTxnId, hasState);

        scope = scopes.pollFirst();
        if (acquireLocks) {
            scope.acquireLocks(txn);
        }

        while ((scope = scopes.pollFirst()) != null) {
            txn.recoveredScope(scope.mSavepoint, LocalTransaction.HAS_TRASH);
            if (acquireLocks) {
                scope.acquireLocks(txn);
            }
        }

        return txn;
    }

    private void traceOp(EventListener debugListener, byte op, byte[] entry) throws IOException {
        String opStr;
        String payloadStr = null;

        switch (op) {
        default:
            opStr = "UNKNOWN";
            payloadStr = "op=" + (op & 0xff) + ", entry=0x" + toHex(entry);
            break;

        case OP_SCOPE_ENTER:
            opStr = "SCOPE_ENTER";
            break;

        case OP_SCOPE_COMMIT:
            opStr = "SCOPE_COMMIT";
            break;

        case OP_COMMIT:
            opStr = "COMMIT";
            break;

        case OP_COMMIT_TRUNCATE:
            opStr = "COMMIT_TRUNCATE";
            break;

        case OP_PREPARE:
            opStr = "PREPARE";
            break;

        case OP_UNCREATE:
            opStr = "UNCREATE";
            break;

        case OP_LOG_COPY:
            opStr = "LOG_COPY";
            break;

        case OP_LOG_REF:
            opStr = "LOG_REF";
            break;

        case OP_LOG_COPY_C:
            opStr = "LOG_COPY_C";
            break;

        case OP_LOG_REF_C:
            opStr = "LOG_REF_C";
            break;

        case OP_INDEX:
            opStr = "INDEX";
            payloadStr = "indexId=" + decodeLongLE(entry, 0);
            break;

        case OP_UNINSERT:
            opStr = "UNINSERT";
            payloadStr = "key=0x" + toHex(entry) + " (" + utf8(entry) + ')';
            break;

        case OP_UNUPDATE: case OP_UNDELETE:
            opStr = op == OP_UNUPDATE ? "UNUPDATE" : "UNDELETE";
            byte[][] pair = decodeNodeKeyValuePair(entry);
            payloadStr = "key=0x" + toHex(pair[0]) + " (" +
                utf8(pair[0]) + ") value=0x" + toHex(pair[1]);
            break;

        case OP_UNDELETE_FRAGMENTED:
            opStr = "UNDELETE_FRAGMENTED";
            byte[] key = decodeNodeKey(entry);
            payloadStr = "key=0x" + toHex(key) + " (" + utf8(key) + ')';
            break;

        case OP_ACTIVE_KEY:
            opStr = "ACTIVE_KEY";
            payloadStr = "key=0x" + toHex(entry) + " (" + utf8(entry) + ')';
            break;

        case OP_CUSTOM:
            opStr = "CUSTOM";
            int handlerId = decodeUnsignedVarInt(entry, 0);
            int messageLoc = calcUnsignedVarIntLength(handlerId);
            String handlerName = mDatabase.findCustomHandlerName(handlerId);
            payloadStr = "handlerId=" + handlerId + ", handlerName=" + handlerName +
                ", message=0x" + toHex(entry, messageLoc, entry.length - messageLoc);
            break;

        case OP_UNUPDATE_LK: case OP_UNDELETE_LK:
            opStr = op == OP_UNUPDATE ? "UNUPDATE_LK" : "UNDELETE_LK";

            int keyLen = decodeUnsignedVarInt(entry, 0);
            int keyLoc = calcUnsignedVarIntLength(keyLen);
            int valueLoc = keyLoc + keyLen;
            int valueLen = entry.length - valueLoc;

            payloadStr = "key=0x" + toHex(entry, keyLoc, keyLen) + " (" +
                utf8(entry, keyLoc, keyLen) + ") value=0x" +
                toHex(entry, valueLoc, valueLen);

            break;

        case OP_UNDELETE_LK_FRAGMENTED:
            opStr = "UNDELETE_LK_FRAGMENTED";

            keyLen = decodeUnsignedVarInt(entry, 0);
            keyLoc = calcUnsignedVarIntLength(keyLen);

            payloadStr = "key=0x" + toHex(entry, keyLoc, keyLen) + " (" +
                utf8(entry, keyLoc, keyLen) + ')';

            break;

        case OP_UNEXTEND:
            opStr = "UNEXTEND";
            payloadStr = "length=" + decodeUnsignedVarLong(entry, new IntegerRef.Value());
            break;

        case OP_UNALLOC:
            opStr = "UNALLOC";
            IntegerRef offsetRef = new IntegerRef.Value();
            long length = decodeUnsignedVarLong(entry, offsetRef);
            long pos = decodeUnsignedVarLong(entry, offsetRef);
            payloadStr = "pos=" + pos + ", length=" + length;
            break;

        case OP_UNWRITE:
            opStr = "UNWRITE";
            offsetRef = new IntegerRef.Value();
            pos = decodeUnsignedVarLong(entry, offsetRef);
            int off = offsetRef.get();
            payloadStr = "pos=" + pos + ", value=0x" + toHex(entry, off, entry.length - off);
            break;

        case OP_LOCK_UPGRADABLE:
            opStr = "LOCK_UPGRADABLE";
            payloadStr = "key=0x" + toHex(entry) + " (" + utf8(entry) + ')';
            break;

        case OP_LOCK_EXCLUSIVE:
            opStr = "LOCK_EXCLUSIVE";
            payloadStr = "key=0x" + toHex(entry) + " (" + utf8(entry) + ')';
            break;
        }

        if (payloadStr == null) {
            debugListener.notify(EventType.DEBUG, "Undo recover %1$s", opStr);
        } else {
            debugListener.notify(EventType.DEBUG, "Undo recover %1$s %2$s", opStr, payloadStr);
        }
    }

    /**
     * Recovered undo scope.
     */
    static class Scope {
        long mSavepoint;

        // Locks are recovered in the opposite order in which they were acquired. Gather them
        // in a stack to reverse the order. Re-use the LockManager collision chain field and
        // form a linked list.
        Lock mTopLock;

        Scope() {
        }

        Lock addExclusiveLock(long indexId, byte[] key) {
            return addLock(indexId, key, ~0);
        }

        Lock addUpgradableLock(long indexId, byte[] key) {
            return addLock(indexId, key, 1 << 31);
        }

        Lock addLock(long indexId, byte[] key, int lockCount) {
            Lock lock = new Lock();
            lock.mIndexId = indexId;
            lock.mKey = key;
            lock.mHashCode = LockManager.hash(indexId, key);
            lock.mLockManagerNext = mTopLock;
            lock.mLockCount = lockCount;
            mTopLock = lock;
            return lock;
        }

        void acquireLocks(LocalTransaction txn) throws LockFailureException {
            Lock lock = mTopLock;
            if (lock != null) while (true) {
                // Copy next before the field is overwritten.
                Lock next = lock.mLockManagerNext;
                txn.recoverLock(lock);
                if (next == null) {
                    break;
                }
                mTopLock = lock = next;
            }
        }
    }

    /**
     * @param masterLogOp OP_LOG_*
     */
    private UndoLog recoverUndoLog(byte masterLogOp, byte[] masterLogEntry)
        throws IOException
    {
        if (masterLogOp != OP_LOG_COPY && masterLogOp != OP_LOG_REF &&
            masterLogOp != OP_LOG_COPY_C && masterLogOp != OP_LOG_REF_C)
        {
            throw new DatabaseException("Unknown undo log entry type: " + masterLogOp);
        }

        long txnId = decodeLongLE(masterLogEntry, 0);
        UndoLog log = new UndoLog(mDatabase, txnId);
        log.mActiveIndexId = decodeLongLE(masterLogEntry, 8);

        if ((masterLogOp & 1) == 0) { // OP_LOG_COPY or OP_LOG_COPY_C
            int bsize = decodeUnsignedShortLE(masterLogEntry, (8 + 8));
            log.mLength = bsize;
            byte[] buffer = new byte[bsize];
            arraycopy(masterLogEntry, (8 + 8 + 2), buffer, 0, bsize);
            log.mBuffer = buffer;
            log.mBufferPos = 0;
        } else { // OP_LOG_REF or OP_LOG_REF_C
            log.mLength = decodeLongLE(masterLogEntry, (8 + 8));
            long nodeId = decodeLongLE(masterLogEntry, (8 + 8 + 8));
            int topEntry = decodeUnsignedShortLE(masterLogEntry, (8 + 8 + 8 + 8));
            log.mNode = readUndoLogNode(mDatabase, nodeId);
            log.mNodeTopPos = topEntry;

            // If node contains OP_COMMIT_TRUNCATE at the end, then the corresponding transaction
            // was committed and the undo log nodes don't need to be fully examined.
            if (log.mNode.undoTop() == pageSize(log.mNode.mPage) - 1 &&
                p_byteGet(log.mNode.mPage, log.mNode.undoTop()) == OP_COMMIT_TRUNCATE)
            {
                log.mNodeTopPos = log.mNode.undoTop();
            }

            log.mNode.releaseExclusive();
        }

        log.mCommitted = (byte) ((masterLogOp >> 1) & OP_LOG_COPY);

        return log;
    }

    /**
     * @return latched, unevictable node
     */
    private static Node readUndoLogNode(LocalDatabase db, long nodeId) throws IOException {
        return readUndoLogNode(db, nodeId, NodeGroup.MODE_UNEVICTABLE);
    }

    /**
     * @return latched node with given eviction mode (pass 0 for normal mode)
     */
    private static Node readUndoLogNode(LocalDatabase db, long nodeId, int mode)
        throws IOException
    {
        Node node = db.allocLatchedNode(nodeId, mode);
        try {
            node.read(db, nodeId);
            if (node.type() != Node.TYPE_UNDO_LOG) {
                throw new CorruptDatabaseException
                    ("Not an undo log node type: " + node.type() + ", id: " + nodeId);
            }
            return node;
        } catch (Throwable e) {
            node.makeEvictableNow();
            node.releaseExclusive();
            throw e;
        }
    }
}
