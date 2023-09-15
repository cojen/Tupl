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

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;

import org.cojen.tupl.ClosedIndexException;
import org.cojen.tupl.CorruptDatabaseException;
import org.cojen.tupl.Cursor;
import org.cojen.tupl.DatabaseException;
import org.cojen.tupl.DeadlockException;
import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.LockFailureException;
import org.cojen.tupl.LockMode;
import org.cojen.tupl.LockResult;
import org.cojen.tupl.Ordering;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.UnmodifiableReplicaException;

import org.cojen.tupl.diag.CompactionObserver;
import org.cojen.tupl.diag.IndexStats;
import org.cojen.tupl.diag.VerificationObserver;

import org.cojen.tupl.views.ViewUtils;

import static org.cojen.tupl.core.PageOps.*;
import static org.cojen.tupl.core.Utils.*;

import static java.util.Arrays.compareUnsigned;

/**
 * Internal cursor implementation, which can be used by one thread at a time.
 *
 * @author Brian S O'Neill
 */
public class BTreeCursor extends CoreValueAccessor implements Cursor {
    // Sign is important because values are passed to Node.retrieveKeyCmp
    // method. Bit 0 is set for inclusive variants and clear for exclusive.
    private static final int LIMIT_LE = 1, LIMIT_LT = 2, LIMIT_GE = -1, LIMIT_GT = -2;

    final BTree mTree;
    LocalTransaction mTxn;

    // Top stack frame for cursor, usually a leaf except during cleanup.
    CursorFrame mFrame;

    byte[] mKey;
    byte[] mValue;

    boolean mKeyOnly;
    
    // Hashcode is defined by LockManager.
    private int mKeyHash;

    // Assigned by register method, for direct redo operations. When id isn't zero, and the
    // high bit is clear, the key must be written into the redo log.
    long mCursorId;

    BTreeCursor(BTree tree, Transaction txn) {
        mTxn = tree.check(txn);
        mTree = tree;
    }

    BTreeCursor(BTree tree) {
        mTree = tree;
    }

    @Override
    public final Ordering ordering() {
        return Ordering.ASCENDING;
    }

    @Override
    public final Comparator<byte[]> comparator() {
        return KEY_COMPARATOR;
    }

    @Override
    public final Transaction link(Transaction txn) {
        LocalTransaction old = mTxn;
        mTxn = mTree.check(txn);
        return old;
    }

    @Override
    public final Transaction link() {
        return mTxn;
    }

    @Override
    public final byte[] key() {
        return mKey;
    }

    @Override
    public final byte[] value() {
        return mValue;
    }

    /**
     * Retrieves stats for the value at current cursor position.
     */
    void valueStats(long[] stats) throws IOException {
        stats[0] = -1;
        stats[1] = 0;
        if (mValue != null && mValue != Cursor.NOT_LOADED) {
            stats[0] = mValue.length;
            return;
        }
        CursorFrame frame = frameSharedNotSplit();
        Node node = frame.mNode;
        try {
            int pos = frame.mNodePos;
            if (pos >= 0) {
                node.retrieveLeafValueStats(pos, stats);
            }
        } finally {
            node.releaseShared();
        }
    }

    
    @Override
    public final boolean autoload(boolean mode) {
        boolean old = mKeyOnly;
        mKeyOnly = !mode;
        return !old;
    }

    @Override
    public final boolean autoload() {
        return !mKeyOnly;
    }

    @Override
    public final int compareKeyTo(byte[] rkey) {
        byte[] lkey = mKey;
        return compareUnsigned(lkey, rkey);
    }

    @Override
    public final int compareKeyTo(byte[] rkey, int offset, int length) {
        byte[] lkey = mKey;
        return compareUnsigned(lkey, 0, lkey.length, rkey, offset, offset + length);
    }

    @Override
    public final boolean register() throws IOException {
        if (mCursorId == 0) {
            if (!allowRedo()) {
                return false;
            }

            LocalTransaction txn = mTxn;
            if (txn == null) {
                LocalDatabase db = mTree.mDatabase;
                RedoWriter redo = db.txnRedoWriter();

                if (redo == null || redo.adjustTransactionId(1) <= 0) {
                    // Non-stored and replica databases don't redo.
                    return false;
                }

                BTree cursorRegistry = db.cursorRegistry();

                CommitLock.Shared shared = db.commitLock().acquireShared();
                try {
                    TransactionContext context = db.anyTransactionContext();
                    long cursorId = context.nextTransactionId();
                    context.redoCursorRegister(redo, cursorId, mTree.mId);
                    mCursorId = cursorId;
                    db.registerCursor(cursorRegistry, this);
                } catch (UnmodifiableReplicaException e) {
                    return false;
                } finally {
                    shared.release();
                }
            } else {
                if (txn.durabilityMode() == DurabilityMode.NO_REDO) {
                    return false;
                }

                CommitLock.Shared shared = mTree.mDatabase.commitLock().acquireShared();
                try {
                    return txn.tryRedoCursorRegister(this);
                } catch (UnmodifiableReplicaException e) {
                    return false;
                } finally {
                    shared.release();
                }
            }
        }

        return true;
    }

    @Override
    public final void unregister() {
        long cursorId = mCursorId;
        if (cursorId != 0) {
            doUnregister(mTxn, cursorId);
        }
    }

    private int keyHash() {
        int hash = mKeyHash;
        if (hash == 0) {
            mKeyHash = hash = LockManager.hash(mTree.mId, mKey);
        }
        return hash;
    }

    @Override
    public final LockResult first() throws IOException {
        reset();

        if (!toFirst(new CursorFrame(), latchRootNode())) {
            return LockResult.UNOWNED;
        }

        LocalTransaction txn = mTxn;
        LockResult result = tryCopyCurrent(txn);

        if (result != null) {
            // Extra check for filtering ghosts.
            if (mValue != null) {
                return result;
            }
        } else if ((result = lockAndCopyIfExists(txn)) != null) {
            return result;
        }

        // If this point is reached, then entry was deleted after latch was
        // released. Move to next entry, which is consistent with findGe.
        // First means, "find greater than or equal to lowest possible key".
        return next();
    }

    /**
     * Moves the cursor to the first subtree entry. Leaf frame remains latched
     * when method returns normally.
     *
     * @param frame frame to bind node to
     * @param node latched node; can have no keys
     * @return false if nothing left
     */
    private boolean toFirst(CursorFrame frame, Node node) throws IOException {
        return toFirstLeaf(frame, node).hasKeys() || toNext(mFrame);
    }

    /**
     * Non-transactionally moves the cursor to the first key, which might refer to a ghost. The
     * value isn't loaded.
     *
     * @return null if nothing left
     */
    byte[] firstKey() throws IOException {
        reset();
        if (toFirst(new CursorFrame(), latchRootNode())) {
            tryCopyCurrent(LocalTransaction.BOGUS);
        }
        return mKey;
    }

    /**
     * Non-transactionally moves the cursor to the first leaf node, which might be empty or
     * full of ghosts. Key and value are not loaded.
     */
    final void firstLeaf() throws IOException {
        reset();
        toFirstLeaf(new CursorFrame(), latchRootNode());
        mFrame.mNode.releaseShared();
    }

    /**
     * Moves the cursor to the first subtree leaf node, which might be empty or full of
     * ghosts. Leaf frame remains latched when method returns normally.
     *
     * @param frame frame to bind node to
     * @param node latched node; can have no keys
     * @return latched first node, possibly empty, bound by mFrame
     */
    private Node toFirstLeaf(CursorFrame frame, Node node) throws IOException {
        try {
            while (true) {
                frame.bind(node, 0);
                if (node.mSplit != null) {
                    node = mTree.finishSplitShared(frame, node);
                    if (frame.mNodePos != 0) {
                        // Rebind if position changed (possibly negative).
                        frame.bindOrReposition(node, 0);
                    }
                }
                if (node.isLeaf()) {
                    mFrame = frame;
                    return node;
                }
                node = mTree.mDatabase.latchToChild(node, 0);
                frame = new CursorFrame(frame);
            }
        } catch (Throwable e) {
            throw cleanup(e, frame);
        }
    }

    /**
     * Moves the cursor to the first subtree internal node, which might be empty. Node remains
     * latched when method returns normally.
     *
     * @param frame frame to bind node to
     * @param node latched internal node; can have no keys
     * @return latched first internal node, possibly empty, bound by mFrame
     */
    private Node toFirstInternal(CursorFrame frame, Node node) throws IOException {
        try {
            while (true) {
                frame.bind(node, 0);
                if (node.mSplit != null) {
                    node = mTree.finishSplitShared(frame, node);
                    if (frame.mNodePos != 0) {
                        // Rebind if position changed (possibly negative).
                        frame.bindOrReposition(node, 0);
                    }
                }
                if (node.isBottomInternal()) {
                    mFrame = frame;
                    return node;
                }
                node = mTree.mDatabase.latchToChild(node, 0);
                frame = new CursorFrame(frame);
            }
        } catch (Throwable e) {
            throw cleanup(e, frame);
        }
    }

    @Override
    public final LockResult last() throws IOException {
        reset();

        if (!toLast(new CursorFrame(), latchRootNode())) {
            return LockResult.UNOWNED;
        }

        LocalTransaction txn = mTxn;
        LockResult result = tryCopyCurrent(txn);

        if (result != null) {
            // Extra check for filtering ghosts.
            if (mValue != null) {
                return result;
            }
        } else if ((result = lockAndCopyIfExists(txn)) != null) {
            return result;
        }

        // If this point is reached, then entry was deleted after latch was
        // released. Move to previous entry, which is consistent with findLe.
        // Last means, "find less than or equal to highest possible key".
        return previous();
    }

    /**
     * Moves the cursor to the last subtree entry. Leaf frame remains latched
     * when method returns normally.
     *
     * @param frame frame to bind node to
     * @param node latched node; can have no keys
     * @return false if nothing left
     */
    private boolean toLast(CursorFrame frame, Node node) throws IOException {
        return toLastLeaf(frame, node).hasKeys() || toPrevious(mFrame);
    }

    /**
     * Moves the cursor to the last subtree leaf node, which might be empty or full of
     * ghosts. Leaf frame remains latched when method returns normally.
     *
     * @param frame frame to bind node to
     * @param node latched node; can have no keys
     * @return latched last node, possibly empty, bound by mFrame
     */
    private Node toLastLeaf(CursorFrame frame, Node node) throws IOException {
        try {
            while (true) {
                Split split = node.mSplit;
                if (split != null) {
                    // Bind to the highest position and finish the split.
                    frame.bind(node, split.highestPos(node));
                    node = mTree.finishSplitShared(frame, node);
                }

                if (node.isLeaf()) {
                    // Note: Highest pos is -2 if leaf node has no keys. Use 0 instead.
                    frame.bindOrReposition(node, Math.max(0, node.highestLeafPos()));
                    mFrame = frame;
                    return node;
                }

                // Note: Highest pos is 0 if internal node has no keys.
                int childPos = node.highestInternalPos();
                frame.bindOrReposition(node, childPos);
                node = mTree.mDatabase.latchToChild(node, childPos);

                frame = new CursorFrame(frame);
            }
        } catch (Throwable e) {
            throw cleanup(e, frame);
        }
    }

    /**
     * Moves the cursor to the last subtree internal node, which might be empty. Node remains
     * latched when method returns normally.
     *
     * @param frame frame to bind node to
     * @param node latched internal node; can have no keys
     * @return latched last internal node, possibly empty, bound by mFrame
     */
    private Node toLastInternal(CursorFrame frame, Node node) throws IOException {
        try {
            while (true) {
                Split split = node.mSplit;
                if (split != null) {
                    // Bind to the highest position and finish the split.
                    frame.bind(node, split.highestPos(node));
                    node = mTree.finishSplitShared(frame, node);
                }
                // Note: Highest pos is 0 if internal node has no keys.
                int childPos = node.highestInternalPos();
                frame.bindOrReposition(node, childPos);
                if (node.isBottomInternal()) {
                    mFrame = frame;
                    return node;
                }
                node = mTree.mDatabase.latchToChild(node, childPos);
                frame = new CursorFrame(frame);
            }
        } catch (Throwable e) {
            throw cleanup(e, frame);
        }
    }

    @Override
    public final LockResult skip(long amount) throws IOException {
        if (amount == 0) {
            LocalTransaction txn = mTxn;
            if (txn != null && !txn.isBogus()) {
                byte[] key = mKey;
                if (key != null) {
                    return txn.mManager.check(txn, mTree.mId, key, keyHash());
                }
            }
            return LockResult.UNOWNED;
        }

        mCursorId &= ~(1L << 63); // key will change, but cursor isn't reset

        try {
            CursorFrame frame = frameSharedNotSplit();
            if (amount > 0) {
                if (amount > 1 && (frame = skipNextGap(frame, amount - 1, null)) == null) {
                    return LockResult.UNOWNED;
                }
                return next(mTxn, frame);
            } else {
                if (amount < -1 && (frame = skipPreviousGap(frame, -1 - amount, null)) == null) {
                    return LockResult.UNOWNED;
                }
                return previous(mTxn, frame);
            }
        } catch (Throwable e) {
            throw handleException(e, false);
        }
    }

    @Override
    public final LockResult skip(long amount, byte[] limitKey, boolean inclusive)
        throws IOException
    {
        if (amount == 0 || limitKey == null) {
            return skip(amount);
        }

        mCursorId &= ~(1L << 63); // key will change, but cursor isn't reset

        try {
            CursorFrame frame = frameSharedNotSplit();
            if (amount > 0) {
                if (amount > 1 && (frame = skipNextGap(frame, amount - 1, limitKey)) == null) {
                    return LockResult.UNOWNED;
                }
                return nextCmp(limitKey, inclusive ? LIMIT_LE : LIMIT_LT, frame);
            } else {
                if (amount < -1
                    && (frame = skipPreviousGap(frame, -1 - amount, limitKey)) == null)
                {
                    return LockResult.UNOWNED;
                }
                return previousCmp(limitKey, inclusive ? LIMIT_GE : LIMIT_GT, frame);
            }
        } catch (Throwable e) {
            throw handleException(e, false);
        }
    }

    @Override
    public final LockResult next() throws IOException {
        return next(mTxn, frameSharedNotSplit());
    }

    @Override
    public final LockResult nextLe(byte[] limitKey) throws IOException {
        return nextCmp(limitKey, LIMIT_LE);
    }

    @Override
    public final LockResult nextLt(byte[] limitKey) throws IOException {
        return nextCmp(limitKey, LIMIT_LT);
    }

    private LockResult nextCmp(byte[] limitKey, int limitMode) throws IOException {
        keyCheck(limitKey);
        return nextCmp(limitKey, limitMode, frameSharedNotSplit());
    }

    private LockResult nextCmp(byte[] limitKey, int limitMode, CursorFrame frame)
        throws IOException
    {
        mCursorId &= ~(1L << 63); // key will change, but cursor isn't reset
        LocalTransaction txn = mTxn;

        while (true) {
            if (!toNext(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrentCmp(txn, limitKey, limitMode);
            if (result != null) {
                // Extra check if limit reached, and for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn)) != null) {
                return result;
            }
            frame = frameSharedNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid.
     *
     * @param frame leaf frame, not split, with shared latch
     */
    private LockResult next(LocalTransaction txn, CursorFrame frame) throws IOException {
        mCursorId &= ~(1L << 63); // key will change, but cursor isn't reset

        while (true) {
            if (!toNext(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrent(txn);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn)) != null) {
                return result;
            }
            frame = frameSharedNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be
     * valid. Leaf frame remains latched when method returns true.
     *
     * @param frame leaf frame, not split, with shared latch
     * @return false if nothing left
     */
    private boolean toNext(CursorFrame frame) throws IOException {
        while (true) {
            Node node = toNextLeaf(frame);
            if (node == null) {
                return false;
            }
            if (node.hasKeys()) {
                return true;
            }
            frame = mFrame;
        }
    }

    /**
     * Non-transactionally moves the cursor to the next key, which might refer to a ghost. The
     * value isn't loaded.
     *
     * @return null if nothing left
     */
    byte[] nextKey() throws IOException {
        mCursorId &= ~(1L << 63); // key will change, but cursor isn't reset
        mValue = NOT_LOADED;
        if (toNext(frameSharedNotSplit())) {
            tryCopyCurrent(LocalTransaction.BOGUS);
        }
        return mKey;
    }

    /**
     * Non-transactionally moves the cursor to the next entry, which might refer to a node
     * which is empty or full of ghosts. Key and value are not loaded.
     */
    private void nextLeaf() throws IOException {
        Node node = toNextLeaf(frameSharedNotSplit());
        if (node != null) {
            node.releaseShared();
        }
    }

    /**
     * Non-transactionally move to the next tree leaf node, loading it if necessary. Node might
     * be empty or full of ghosts. Key and value are not loaded.
     */
    private void skipToNextLeaf() throws IOException {
        // Move to next node by first setting current node position higher than possible.
        mFrame.mNodePos = Integer.MAX_VALUE - 1;
        nextLeaf();
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid. Leaf frame
     * remains latched when method returns a non-null node.
     *
     * @param frame leaf frame, not split, with shared latch
     * @return latched node, never split, possibly empty, bound by mFrame, or null
     * if nothing left
     */
    private Node toNextLeaf(CursorFrame frame) throws IOException {
        // Note: This method is nearly the same as toNextInternal.

        start: while (true) {
            Node node = frame.mNode;

            quick: {
                int pos = frame.mNodePos;
                if (pos < 0) {
                    pos = ~2 - pos; // eq: (~pos) - 2;
                    if (pos >= node.highestLeafPos()) {
                        break quick;
                    }
                    frame.mNotFoundKey = null;
                } else if (pos >= node.highestLeafPos()) {
                    break quick;
                }
                frame.mNodePos = pos + 2;
                return node;
            }

            while (true) {
                CursorFrame parentFrame = frame.mParentFrame;

                if (parentFrame == null) {
                    reachedEnd(node);
                    return null;
                }

                Node parentNode;

                latchParent: {
                    splitCheck: {
                        // Latch coupling up the tree usually works, so give it a try. If it
                        // works, then there's no need to worry about a node merge.
                        parentNode = parentFrame.tryAcquireShared();
                        node.releaseShared();

                        if (parentNode == null) {
                            // Latch coupling failed, and so acquire the parent latch without
                            // holding the child latch. The child might have changed, and so it
                            // must be checked again.
                            parentNode = parentFrame.acquireShared();
                            if (parentNode.mSplit == null) {
                                break splitCheck;
                            }
                        } else if (parentNode.mSplit == null) {
                            break latchParent;
                        }

                        // When this point is reached, only the parent latch is held, and the
                        // parent node must be split.

                        parentNode = mTree.finishSplitShared(parentFrame, parentNode);
                    }

                    // When this point is reached, only the parent latch is held.

                    // Quick check again, in case node got bigger due to merging. Quick check
                    // doesn't work when the child node is internal, since too many structural
                    // tree changes might have taken place as the latches were released.

                    if (frame != mFrame) {
                        parentNode.releaseShared();
                        frame = frameSharedNotSplit();
                        continue start;
                    }

                    node = frame.acquireShared();

                    if (node.mSplit != null) {
                        parentNode.releaseShared();
                        mTree.finishSplitShared(frame, node);
                        continue start;
                    }

                    quick: {
                        int pos = frame.mNodePos;
                        if (pos < 0) {
                            pos = ~2 - pos; // eq: (~pos) - 2;
                            if (pos >= node.highestLeafPos()) {
                                break quick;
                            }
                            frame.mNotFoundKey = null;
                        } else if (pos >= node.highestLeafPos()) {
                            break quick;
                        }
                        parentNode.releaseShared();
                        frame.mNodePos = pos + 2;
                        return node;
                    }

                    node.releaseShared();
                }

                // When this point is reached, only the shared parent latch is held, and the
                // child node has reached the edge. Advance the position of the parent node, or
                // keep going up the tree if the parent has reached the edge too.

                int parentPos = parentFrame.mNodePos;

                if (parentPos < parentNode.highestInternalPos()) {
                    // Note: Frames are popped as late as possible, in order for cursor
                    // bindings to be properly fixed as structural tree changes are made.
                    parentFrame.popChildren(mFrame);
                    parentFrame.mNodePos = (parentPos += 2);
                    // Always create a new cursor frame. See CursorFrame.unbind.
                    frame = new CursorFrame(parentFrame);
                    return toFirstLeaf(frame, mTree.mDatabase.latchToChild(parentNode, parentPos));
                }

                frame = parentFrame;
                node = parentNode;
            }
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid. Internal node
     * frame remains latched when method returns a non-null node.
     *
     * @param frame internal node frame, not split, with shared latch
     * @return latched internal node, never split, possibly empty, bound by mFrame, or null
     * if nothing left
     */
    private Node toNextInternal(CursorFrame frame) throws IOException {
        // Note: This method is nearly the same as toNextLeaf.

        start: while (true) {
            Node node = frame.mNode;

            quick: {
                int pos = frame.mNodePos;
                if (pos >= node.highestInternalPos()) {
                    break quick;
                }
                frame.mNodePos = pos + 2;
                return node;
            }

            while (true) {
                CursorFrame parentFrame = frame.mParentFrame;

                if (parentFrame == null) {
                    reachedEnd(node);
                    return null;
                }

                Node parentNode;

                latchParent: {
                    splitCheck: {
                        // Latch coupling up the tree usually works, so give it a try. If it
                        // works, then there's no need to worry about a node merge.
                        parentNode = parentFrame.tryAcquireShared();
                        node.releaseShared();

                        if (parentNode == null) {
                            // Latch coupling failed, and so acquire the parent latch without
                            // holding the child latch. The child might have changed, and so it
                            // must be checked again.
                            parentNode = parentFrame.acquireShared();
                            if (parentNode.mSplit == null) {
                                break splitCheck;
                            }
                        } else if (parentNode.mSplit == null) {
                            break latchParent;
                        }

                        // When this point is reached, only the parent latch is held, and the
                        // parent node must be split.

                        parentNode = mTree.finishSplitShared(parentFrame, parentNode);
                    }

                    // When this point is reached, only the parent latch is held.

                    // Quick check again, in case node got bigger due to merging. Quick check
                    // doesn't work when the child node isn't the bound internal node, since
                    // too many structural tree changes might have taken place as the latches
                    // were released.

                    if (frame != mFrame) {
                        parentNode.releaseShared();
                        frame = frameSharedNotSplit();
                        continue start;
                    }

                    node = frame.acquireShared();

                    if (node.mSplit != null) {
                        parentNode.releaseShared();
                        mTree.finishSplitShared(frame, node);
                        continue start;
                    }

                    quick: {
                        int pos = frame.mNodePos;
                        if (pos >= node.highestInternalPos()) {
                            break quick;
                        }
                        parentNode.releaseShared();
                        frame.mNodePos = pos + 2;
                        return node;
                    }

                    node.releaseShared();
                }

                // When this point is reached, only the shared parent latch is held, and the
                // child node has reached the edge. Advance the position of the parent node, or
                // keep going up the tree if the parent has reached the edge too.

                int parentPos = parentFrame.mNodePos;

                if (parentPos < parentNode.highestInternalPos()) {
                    // Note: Frames are popped as late as possible, in order for cursor
                    // bindings to be properly fixed as structural tree changes are made.
                    parentFrame.popChildren(mFrame);
                    parentFrame.mNodePos = (parentPos += 2);
                    // Always create a new cursor frame. See CursorFrame.unbind.
                    frame = new CursorFrame(parentFrame);
                    Node child = mTree.mDatabase.latchToChild(parentNode, parentPos);
                    return toFirstInternal(frame, child);
                }

                frame = parentFrame;
                node = parentNode;
            }
        }
    }

    /**
     * @param frame leaf frame, not split, with shared latch
     * @param inLimit inclusive highest allowed internal key; null for no limit
     * @return latched leaf frame or null if reached end
     */
    private CursorFrame skipNextGap(CursorFrame frame, long amount, byte[] inLimit)
        throws IOException
    {
        start: while (true) {
            int pos = frame.mNodePos;
            if (pos < 0) {
                pos = ~pos;
            }

            Node node = frame.mNode;
            int avail = (node.highestLeafPos() + 2 - pos) >> 1;

            if (amount < avail) {
                frame.mNodePos = pos + (((int) amount) << 1);
                return frame;
            }

            amount -= avail;
            node.releaseShared();

            // Pop up a level and scan over the bottom internal nodes.

            mFrame = frame = frame.pop();

            if (frame == null) {
                // Nothing left.
                return null;
            }

            node = frame.acquireShared();
            if (node.mSplit != null) {
                node = mTree.finishSplitShared(frame, node);
            }

            while (true) {
                if (!node.isBottomInternal()) {
                    try {
                        checkClosedIndexException(node.mPage);
                        throw new CorruptDatabaseException(node.toString());
                    } finally {
                        node.releaseShared();
                    }
                }

                if (inLimit != null && frame.mNodePos <= node.highestKeyPos()) {
                    int cmp;
                    try {
                        cmp = node.compareKey(frame.mNodePos, inLimit);
                    } catch (Throwable e) {
                        resetLatched(node);
                        throw e;
                    }

                    if (cmp > 0) {
                        resetLatched(node);
                        return null;
                    }
                }

                node = toNextInternal(frame);

                if (node == null) {
                    return null;
                }

                frame = mFrame;

                Node child;
                int childCount;

                obtainCount: {
                    childCount = node.childEntryCount(frame.mNodePos);

                    if (childCount >= 0) {
                        if (amount >= childCount) {
                            amount -= childCount;
                            continue;
                        }
                        // Note: Since count is valid, child cannot be split.
                        child = mTree.mDatabase.latchToChild(node, frame.mNodePos);
                        break obtainCount;
                    }

                    child = mTree.mDatabase.latchChildRetainParent(node, frame.mNodePos);

                    if (child.mSplit != null) {
                        // Must bind to the leaf to finish the split.
                        node.releaseShared();
                        frame = new CursorFrame(frame);
                        toFirstLeaf(frame, child);
                        // Now bound to the leaf, go back to the code which skips over leaves.
                        continue start;
                    }

                    childCount = child.countNonGhostKeys();

                    if (child.mCachedState == Node.CACHED_CLEAN && node.tryUpgrade()) {
                        try {
                            CommitLock.Shared shared =
                                mTree.mDatabase.commitLock().tryAcquireShared();
                            if (shared != null) {
                                try {
                                    if (tryNotSplitDirty(frame)) {
                                        node.storeChildEntryCount(frame.mNodePos, childCount);
                                    }
                                } catch (Throwable e) {
                                    child.releaseShared();
                                    throw e;
                                } finally {
                                    shared.release();
                                }
                            }
                        } catch (Throwable e) {
                            node.releaseExclusive();
                            throw e;
                        }
                        node.downgrade();
                    }

                    if (amount >= childCount) {
                        child.releaseShared();
                        amount -= childCount;
                        continue;
                    }

                    node.releaseShared();
                }

                try {
                    frame = new CursorFrame(frame);
                    frame.bind(child, ((int) amount) << 1);
                    mFrame = frame;
                    return frame;
                } catch (Throwable e) {
                    child.releaseShared();
                    throw e;
                }
            }
        }
    }

    /**
     * Called by BTree.count. Caller must always reset the cursor after calling this method.
     */
    long countTo(BTreeCursor high) throws IOException {
        if (mKey == null || (high != null && compareUnsigned(mKey, high.mKey) >= 0)) {
            // Found nothing.
            return 0;
        }

        CursorFrame frame = frameSharedNotSplit();

        // Directly count the entries in the lowest leaf.
        int pos = frame.mNodePos;
        if (pos < 0) {
            pos = ~pos;
        }

        Node node = frame.mNode;
        int lowPos = node.searchVecStart() + pos;

        if (high != null && node == high.mFrame.mNode) {
            long count = countNonGhostKeys(node, lowPos, high);
            node.releaseShared();
            return count;
        }

        long count = node.countNonGhostKeys(lowPos, node.searchVecEnd());
        node.releaseShared();

        // Pop up a level and scan over the bottom internal nodes.

        mFrame = frame = frame.pop();

        if (frame == null) {
            // Nothing left.
            return count;
        }

        node = frame.acquireShared();
        if (node.mSplit != null) {
            node = mTree.finishSplitShared(frame, node);
        }

        while (true) {
            if (!node.isBottomInternal()) {
                try {
                    checkClosedIndexException(node.mPage);
                    throw new CorruptDatabaseException(node.toString());
                } finally {
                    node.releaseShared();
                }
            }

            node = toNextInternal(frame);

            if (node == null) {
                return count;
            }

            frame = mFrame;

            if (high != null) {
                CursorFrame highFrame;

                while (node == (highFrame = high.mFrame.mParentFrame).mNode &&
                       frame.mNodePos >= highFrame.mNodePos)
                {
                    // Access the child for obtaining a partial count.
                    Node child = high.mFrame.acquireShared();
                    node.releaseShared();

                    if (child.mSplit != null) {
                        // Finishing the split causes all latches to be released, so loop back
                        // and check again afterwards.
                        mTree.finishSplitShared(high.mFrame, child).releaseShared();
                        node = frame.acquireShared();
                        continue;
                    }

                    count += countNonGhostKeys(child, child.searchVecStart(), high);
                    child.releaseShared();
                    return count;
                }
            }

            int childCount = node.childEntryCount(frame.mNodePos);

            if (childCount >= 0) {
                count += childCount;
                continue;
            }

            Node child = mTree.mDatabase.latchChildRetainParent(node, frame.mNodePos);
            childCount = child.countNonGhostKeys();
            count += childCount;

            if (child.mCachedState == Node.CACHED_CLEAN && node.tryUpgrade()) {
                // Note: If child node is clean, it's also not split.
                try {
                    CommitLock.Shared shared = mTree.mDatabase.commitLock().tryAcquireShared();
                    if (shared != null) {
                        try {
                            if (tryNotSplitDirty(frame)) {
                                node.storeChildEntryCount(frame.mNodePos, childCount);
                                child.releaseShared();
                                node.downgrade();
                                continue;
                            }
                        } catch (Throwable e) {
                            child.releaseShared();
                            throw e;
                        } finally {
                            shared.release();
                        }
                    }
                } catch (Throwable e) {
                    node.releaseExclusive();
                    throw e;
                }
                node.downgrade();
            }

            if (child.mSplit != null) {
                Node sibling = child.mSplit.latchSibling();
                count += sibling.countNonGhostKeys();
                sibling.releaseShared();
            }

            child.releaseShared();
        }
    }

    /**
     * Count from a low position to a high position in the same node.
     *
     * @param node must be a leaf, not split
     * @param lowPos absolute position in search vector
     * @param high not null and must be bound to the same node
     */
    private static long countNonGhostKeys(Node node, int lowPos, BTreeCursor high) {
        int highPos = high.mFrame.mNodePos;
        if (highPos < 0) {
            highPos = ~highPos;
        }
        return node.countNonGhostKeys(lowPos, node.searchVecStart() + highPos - 2);
    }

    @Override
    public final LockResult previous() throws IOException {
        return previous(mTxn, frameSharedNotSplit());
    }

    @Override
    public final LockResult previousGe(byte[] limitKey) throws IOException {
        return previousCmp(limitKey, LIMIT_GE);
    }

    @Override
    public final LockResult previousGt(byte[] limitKey) throws IOException {
        return previousCmp(limitKey, LIMIT_GT);
    }

    private LockResult previousCmp(byte[] limitKey, int limitMode) throws IOException {
        keyCheck(limitKey);
        return previousCmp(limitKey, limitMode, frameSharedNotSplit());
    }

    private LockResult previousCmp(byte[] limitKey, int limitMode, CursorFrame frame)
        throws IOException
    {
        mCursorId &= ~(1L << 63); // key will change, but cursor isn't reset
        LocalTransaction txn = mTxn;

        while (true) {
            if (!toPrevious(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrentCmp(txn, limitKey, limitMode);
            if (result != null) {
                // Extra check if limit reached, and for filtering ghosts.
                if (mKey == null || mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn)) != null) {
                return result;
            }
            frame = frameSharedNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid.
     *
     * @param frame leaf frame, not split, with shared latch
     */
    private LockResult previous(LocalTransaction txn, CursorFrame frame)
        throws IOException
    {
        mCursorId &= ~(1L << 63); // key will change, but cursor isn't reset

        while (true) {
            if (!toPrevious(frame)) {
                return LockResult.UNOWNED;
            }
            LockResult result = tryCopyCurrent(txn);
            if (result != null) {
                // Extra check for filtering ghosts.
                if (mValue != null) {
                    return result;
                }
            } else if ((result = lockAndCopyIfExists(txn)) != null) {
                return result;
            }
            frame = frameSharedNotSplit();
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be
     * valid. Leaf frame remains latched when method returns true.
     *
     * @param frame leaf frame, not split, with shared latch
     * @return false if nothing left
     */
    private boolean toPrevious(CursorFrame frame) throws IOException {
        while (true) {
            Node node = toPreviousLeaf(frame);
            if (node == null) {
                return false;
            }
            if (node.hasKeys()) {
                return true;
            }
            frame = mFrame;
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid. Leaf frame
     * remains latched when method returns a non-null node.
     *
     * @param frame leaf frame, not split, with shared latch
     * @return latched node, never split, possibly empty, bound by mFrame, or null if
     * nothing left
     */
    private Node toPreviousLeaf(CursorFrame frame) throws IOException {
        // Note: This method is nearly the same as toPreviousInternal.

        start: while (true) {
            Node node = frame.mNode;

            quick: {
                int pos = frame.mNodePos;
                if (pos < 0) {
                    pos = ~pos;
                    if (pos == 0) {
                        break quick;
                    }
                    frame.mNotFoundKey = null;
                } else if (pos == 0) {
                    break quick;
                }
                frame.mNodePos = pos - 2;
                return node;
            }

            while (true) {
                CursorFrame parentFrame = frame.mParentFrame;

                if (parentFrame == null) {
                    reachedEnd(node);
                    return null;
                }

                Node parentNode;

                latchParent: {
                    splitCheck: {
                        // Latch coupling up the tree usually works, so give it a try. If it
                        // works, then there's no need to worry about a node merge.
                        parentNode = parentFrame.tryAcquireShared();
                        node.releaseShared();

                        if (parentNode == null) {
                            // Latch coupling failed, and so acquire the parent latch without
                            // holding the child latch. The child might have changed, and so it
                            // must be checked again.
                            parentNode = parentFrame.acquireShared();
                            if (parentNode.mSplit == null) {
                                break splitCheck;
                            }
                        } else if (parentNode.mSplit == null) {
                            break latchParent;
                        }

                        // When this point is reached, only the parent latch is held, and the
                        // parent node must be split.

                        parentNode = mTree.finishSplitShared(parentFrame, parentNode);
                    }

                    // When this point is reached, only the parent latch is held.

                    // Quick check again, in case node got bigger due to merging. Quick check
                    // doesn't work when the child node is internal, since too many structural
                    // tree changes might have taken place as the latches were released.

                    if (frame != mFrame) {
                        parentNode.releaseShared();
                        frame = frameSharedNotSplit();
                        continue start;
                    }

                    node = frame.acquireShared();

                    if (node.mSplit != null) {
                        parentNode.releaseShared();
                        mTree.finishSplitShared(frame, node);
                        continue start;
                    }

                    quick: {
                        int pos = frame.mNodePos;
                        if (pos < 0) {
                            pos = ~pos;
                            if (pos == 0) {
                                break quick;
                            }
                            frame.mNotFoundKey = null;
                        } else if (pos == 0) {
                            break quick;
                        }
                        parentNode.releaseShared();
                        frame.mNodePos = pos - 2;
                        return node;
                    }

                    node.releaseShared();
                }

                // When this point is reached, only the shared parent latch is held, and the
                // child node has reached the edge. Advance the position of the parent node, or
                // keep going up the tree if the parent has reached the edge too.

                int parentPos = parentFrame.mNodePos;

                if (parentPos > 0) {
                    // Note: Frames are popped as late as possible, in order for cursor
                    // bindings to be properly fixed as structural tree changes are made.
                    parentFrame.popChildren(mFrame);
                    parentFrame.mNodePos = (parentPos -= 2);
                    // Always create a new cursor frame. See CursorFrame.unbind.
                    frame = new CursorFrame(parentFrame);
                    return toLastLeaf(frame, mTree.mDatabase.latchToChild(parentNode, parentPos));
                }

                frame = parentFrame;
                node = parentNode;
            }
        }
    }

    /**
     * Note: When method returns, frame is unlatched and may no longer be valid. Internal node
     * frame remains latched when method returns a non-null node.
     *
     * @param frame internal node frame, not split, with shared latch
     * @return latched internal node, never split, possibly empty, bound by mFrame, or null if
     * nothing left
     */
    private Node toPreviousInternal(CursorFrame frame) throws IOException {
        // Note: This method is nearly the same as toPreviousLeaf.

        start: while (true) {
            Node node = frame.mNode;

            quick: {
                int pos = frame.mNodePos;
                if (pos == 0) {
                    break quick;
                }
                frame.mNodePos = pos - 2;
                return node;
            }

            while (true) {
                CursorFrame parentFrame = frame.mParentFrame;

                if (parentFrame == null) {
                    reachedEnd(node);
                    return null;
                }

                Node parentNode;

                latchParent: {
                    splitCheck: {
                        // Latch coupling up the tree usually works, so give it a try. If it
                        // works, then there's no need to worry about a node merge.
                        parentNode = parentFrame.tryAcquireShared();
                        node.releaseShared();

                        if (parentNode == null) {
                            // Latch coupling failed, and so acquire the parent latch without
                            // holding the child latch. The child might have changed, and so it
                            // must be checked again.
                            parentNode = parentFrame.acquireShared();
                            if (parentNode.mSplit == null) {
                                break splitCheck;
                            }
                        } else if (parentNode.mSplit == null) {
                            break latchParent;
                        }

                        // When this point is reached, only the parent latch is held, and the
                        // parent node must be split.

                        parentNode = mTree.finishSplitShared(parentFrame, parentNode);
                    }

                    // When this point is reached, only the parent latch is held.

                    // Quick check again, in case node got bigger due to merging. Quick check
                    // doesn't work when the child node isn't the bound internal node, since
                    // too many structural tree changes might have taken place as the latches
                    // were released.

                    if (frame != mFrame) {
                        parentNode.releaseShared();
                        frame = frameSharedNotSplit();
                        continue start;
                    }

                    node = frame.acquireShared();

                    if (node.mSplit != null) {
                        parentNode.releaseShared();
                        mTree.finishSplitShared(frame, node);
                        continue start;
                    }

                    quick: {
                        int pos = frame.mNodePos;
                        if (pos == 0) {
                            break quick;
                        }
                        parentNode.releaseShared();
                        frame.mNodePos = pos - 2;
                        return node;
                    }

                    node.releaseShared();
                }

                // When this point is reached, only the shared parent latch is held, and the
                // child node has reached the edge. Advance the position of the parent node, or
                // keep going up the tree if the parent has reached the edge too.

                int parentPos = parentFrame.mNodePos;

                if (parentPos > 0) {
                    // Note: Frames are popped as late as possible, in order for cursor
                    // bindings to be properly fixed as structural tree changes are made.
                    parentFrame.popChildren(mFrame);
                    parentFrame.mNodePos = (parentPos -= 2);
                    // Always create a new cursor frame. See CursorFrame.unbind.
                    frame = new CursorFrame(parentFrame);
                    Node child = mTree.mDatabase.latchToChild(parentNode, parentPos);
                    return toLastInternal(frame, child);
                }

                frame = parentFrame;
                node = parentNode;
            }
        }
    }

    /**
     * @param frame leaf frame, not split, with shared latch
     * @param inLimit inclusive lowest allowed internal key; null for no limit
     * @return latched leaf frame or null if reached end
     */
    private CursorFrame skipPreviousGap(CursorFrame frame, long amount, byte[] inLimit)
        throws IOException
    {
        start: while (true) {
            int pos = frame.mNodePos;
            if (pos < 0) {
                pos = ~pos;
            }

            Node node = frame.mNode;
            int avail = (pos + 2) >> 1;

            if (amount < avail) {
                frame.mNodePos = pos - (((int) amount) << 1);
                return frame;
            }

            amount -= avail;
            node.releaseShared();

            // Pop up a level and scan over the bottom internal nodes.

            mFrame = frame = frame.pop();

            if (frame == null) {
                // Nothing left.
                return null;
            }

            node = frame.acquireShared();
            if (node.mSplit != null) {
                node = mTree.finishSplitShared(frame, node);
            }

            while (true) {
                if (!node.isBottomInternal()) {
                    try {
                        checkClosedIndexException(node.mPage);
                        throw new CorruptDatabaseException(node.toString());
                    } finally {
                        node.releaseShared();
                    }
                }

                if (inLimit != null && frame.mNodePos > 0) {
                    int cmp;
                    try {
                        cmp = node.compareKey(frame.mNodePos - 2, inLimit);
                    } catch (Throwable e) {
                        resetLatched(node);
                        throw e;
                    }

                    if (cmp < 0) {
                        resetLatched(node);
                        return null;
                    }
                }

                node = toPreviousInternal(frame);

                if (node == null) {
                    return null;
                }

                frame = mFrame;

                Node child;
                int childCount;

                obtainCount: {
                    childCount = node.childEntryCount(frame.mNodePos);

                    if (childCount >= 0) {
                        if (amount >= childCount) {
                            amount -= childCount;
                            continue;
                        }
                        // Note: Since count is valid, child cannot be split.
                        child = mTree.mDatabase.latchToChild(node, frame.mNodePos);
                        break obtainCount;
                    }

                    child = mTree.mDatabase.latchChildRetainParent(node, frame.mNodePos);

                    if (child.mSplit != null) {
                        // Must bind to the leaf to finish the split.
                        node.releaseShared();
                        frame = new CursorFrame(frame);
                        toLastLeaf(frame, child);
                        // Now bound to the leaf, go back to the code which skips over leaves.
                        continue start;
                    }

                    childCount = child.countNonGhostKeys();

                    if (child.mCachedState == Node.CACHED_CLEAN && node.tryUpgrade()) {
                        try {
                            CommitLock.Shared shared =
                                mTree.mDatabase.commitLock().tryAcquireShared();
                            if (shared != null) {
                                try {
                                    if (tryNotSplitDirty(frame)) {
                                        node.storeChildEntryCount(frame.mNodePos, childCount);
                                    }
                                } catch (Throwable e) {
                                    child.releaseShared();
                                    throw e;
                                } finally {
                                    shared.release();
                                }
                            }
                        } catch (Throwable e) {
                            node.releaseExclusive();
                            throw e;
                        }
                        node.downgrade();
                    }

                    if (amount >= childCount) {
                        child.releaseShared();
                        amount -= childCount;
                        continue;
                    }

                    node.releaseShared();
                }

                try {
                    frame = new CursorFrame(frame);
                    frame.bind(child, child.highestKeyPos() - (((int) amount) << 1));
                    mFrame = frame;
                    return frame;
                } catch (Throwable e) {
                    child.releaseShared();
                    throw e;
                }
            }
        }
    }

    /**
     * Try to copy the current entry, locking it if required. Null is returned if lock is not
     * immediately available and only the key was copied. Node latch is always released by this
     * method, even if an exception is thrown.
     *
     * @return null, UNOWNED, INTERRUPTED, TIMED_OUT_LOCK, ACQUIRED,
     * OWNED_SHARED, OWNED_UPGRADABLE, or OWNED_EXCLUSIVE
     * @param txn optional
     */
    private LockResult tryCopyCurrent(LocalTransaction txn) throws IOException {
        final Node node;
        final int pos;
        {
            CursorFrame leaf = mFrame;
            node = leaf.mNode;
            pos = leaf.mNodePos;
        }

        try {
            mKeyHash = 0;

            final int lockType;
            if (txn == null) {
                lockType = 0;
            } else {
                LockMode mode = txn.lockMode();
                if (mode.noReadLock) {
                    node.retrieveLeafEntry(pos, this);
                    return LockResult.UNOWNED;
                } else {
                    lockType = mode.repeatable;
                }
            }

            // Copy key for now, because lock might not be available. Value
            // might change after latch is released. Assign NOT_LOADED, in case
            // lock cannot be granted at all. This prevents uncommitted value
            // from being exposed.
            mKey = node.retrieveKey(pos);
            mValue = NOT_LOADED;

            try {
                int keyHash = keyHash();

                if (lockType == 0) {
                    if (mTree.isLockAvailable(txn, mKey, keyHash)) {
                        // No need to acquire full lock.
                        mValue = mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
                        return LockResult.UNOWNED;
                    } else {
                        return null;
                    }
                }

                LockResult result = txn.doTryLock(lockType, mTree.mId, mKey, keyHash, 0L);

                if (result.isHeld()) {
                    mValue = mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
                    return result;
                } else {
                    return null;
                }
            } catch (DeadlockException e) {
                // Not expected with timeout of zero anyhow.
                return null;
            }
        } finally {
            node.releaseShared();
        }
    }

    /**
     * Variant of tryCopyCurrent used by iteration methods which have a
     * limit. If limit is reached, cursor is reset and UNOWNED is returned.
     */
    private LockResult tryCopyCurrentCmp(LocalTransaction txn, byte[] limitKey, int limitMode)
        throws IOException
    {
        try {
            return doTryCopyCurrentCmp(txn, limitKey, limitMode);
        } catch (Throwable e) {
            mFrame.mNode.releaseShared();
            throw e;
        }
    }

    /**
     * Variant of tryCopyCurrent used by iteration methods which have a
     * limit. If limit is reached, cursor is reset and UNOWNED is returned.
     */
    private LockResult doTryCopyCurrentCmp(LocalTransaction txn, byte[] limitKey, int limitMode)
        throws IOException
    {
        final Node node;
        final int pos;
        {
            CursorFrame leaf = mFrame;
            node = leaf.mNode;
            pos = leaf.mNodePos;
        }

        byte[] key = node.retrieveKeyCmp(pos, limitKey, limitMode);

        check: {
            if (key != null) {
                if (key != limitKey) {
                    mKey = key;
                    break check;
                } else if ((limitMode & 1) != 0) {
                    // Cursor contract does not claim ownership of limitKey instance.
                    mKey = key.clone();
                    break check;
                }
            }

            // Limit has been reached.
            node.releaseShared();
            reset();
            return LockResult.UNOWNED;
        }

        mKeyHash = 0;

        LockResult result;
        obtainResult: {
            final int lockType;
            if (txn == null) {
                lockType = 0;
            } else {
                LockMode mode = txn.lockMode();
                if (mode.noReadLock) {
                    mValue = mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
                    result = LockResult.UNOWNED;
                    break obtainResult;
                } else {
                    lockType = mode.repeatable;
                }
            }

            mValue = NOT_LOADED;
            int keyHash = keyHash();

            if (lockType == 0) {
                if (mTree.isLockAvailable(txn, mKey, keyHash)) {
                    // No need to acquire full lock.
                    mValue = mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
                    result = LockResult.UNOWNED;
                } else {
                    result = null;
                }
                break obtainResult;
            } else {
                result = txn.doTryLock(lockType, mTree.mId, mKey, keyHash, 0L);
            }

            if (result.isHeld()) {
                mValue = mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
            } else {
                result = null;
            }
        }

        node.releaseShared();
        return result;
    }

    /**
     * With node latch not held, lock the current key. Returns the lock result if entry exists,
     * null otherwise. Method is intended to be called for operations which move the position,
     * and so it should not retain locks for entries which were concurrently deleted. The find
     * operation is required to lock entries which don't exist.
     *
     * @param txn optional
     * @return null if current entry has been deleted
     */
    private LockResult lockAndCopyIfExists(LocalTransaction txn) throws IOException {
        int keyHash = keyHash();

        if (txn == null) {
            Locker locker = mTree.lockSharedLocal(mKey, keyHash);
            try {
                if (copyIfExists() != null) {
                    return LockResult.UNOWNED;
                }
            } finally {
                locker.doUnlock();
            }
        } else {
            LockResult result;

            int lockType = txn.lockMode().repeatable;

            if (lockType == 0) {
                if ((result = txn.doLockShared(mTree.mId, mKey, keyHash)) == LockResult.ACQUIRED) {
                    result = LockResult.UNOWNED;
                }
            } else {
                result = txn.doLock(lockType, mTree.mId, mKey, keyHash, txn.mLockTimeoutNanos);
            }

            if (copyIfExists() != null) {
                if (result == LockResult.UNOWNED) {
                    txn.doUnlock();
                }
                return result;
            }

            if (result == LockResult.UNOWNED || result == LockResult.ACQUIRED) {
                txn.doUnlock();
            }
        }

        // Entry does not exist, and lock has been released if was just acquired.
        return null;
    }

    private byte[] copyIfExists() throws IOException {
        byte[] value;

        CursorFrame frame = frameSharedNotSplit();
        Node node = frame.mNode;
        try {
            int pos = frame.mNodePos;
            if (pos < 0) {
                value = null;
            } else {
                value = mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
            }
        } finally {
            node.releaseShared();
        }

        mValue = value;
        return value;
    }

    /**
     * Checks validity of key, assigns key and hash code to cursor, and returns the linked
     * transaction.
     */
    private LocalTransaction prepareFind(byte[] key) {
        keyCheck(key);
        LocalTransaction txn = mTxn;
        int hash;
        selectHash: {
            if (txn != null) {
                LockMode mode = txn.lockMode();
                if (mode == LockMode.READ_UNCOMMITTED || mode == LockMode.UNSAFE) {
                    hash = 0;
                    break selectHash;
                }
            }
            hash = LockManager.hash(mTree.mId, key);
        }
        mKey = key;
        mKeyHash = hash;
        return txn;
    }

    private static final int
        VARIANT_REGULAR = 0,
        VARIANT_RETAIN  = 1, // retain node latch
        VARIANT_CHECK   = 2; // retain node latch, don't lock entry, don't load entry

    @Override
    public final LockResult find(byte[] key) throws IOException {
        reset();
        return doFind(key);
    }

    final LockResult doFind(byte[] key) throws IOException {
        return find(prepareFind(key), key, VARIANT_REGULAR, new CursorFrame(), latchRootNode());
    }

    @Override
    public final LockResult findGe(byte[] key) throws IOException {
        // If isolation level is read committed, then key must be
        // locked. Otherwise, an uncommitted delete could be observed.
        reset();
        LocalTransaction txn = prepareFind(key);
        LockResult result = find(txn, key, VARIANT_RETAIN, new CursorFrame(), latchRootNode());
        if (mValue != null) {
            mFrame.mNode.releaseShared();
            return result;
        } else {
            if (result == LockResult.ACQUIRED) {
                txn.doUnlock();
            }
            return next(txn, mFrame);
        }
    }

    @Override
    public final LockResult findLe(byte[] key) throws IOException {
        // If isolation level is read committed, then key must be
        // locked. Otherwise, an uncommitted delete could be observed.
        reset();
        LocalTransaction txn = prepareFind(key);
        LockResult result = find(txn, key, VARIANT_RETAIN, new CursorFrame(), latchRootNode());
        if (mValue != null) {
            mFrame.mNode.releaseShared();
            return result;
        } else {
            if (result == LockResult.ACQUIRED) {
                txn.doUnlock();
            }
            return previous(txn, mFrame);
        }
    }

    @Override
    public final LockResult findGt(byte[] key) throws IOException {
        findNoLock(key);
        return next(mTxn, mFrame);
    }

    @Override
    public final LockResult findLt(byte[] key) throws IOException {
        findNoLock(key);
        return previous(mTxn, mFrame);
    }

    private void findNoLock(byte[] key) throws IOException {
        reset();
        keyCheck(key);
        // Never lock the requested key.
        find(null, key, VARIANT_CHECK, new CursorFrame(), latchRootNode());
    }

    @Override
    public final LockResult findNearby(byte[] key) throws IOException {
        mCursorId &= ~(1L << 63); // key will change, but cursor isn't reset
        LocalTransaction txn = prepareFind(key);

        Node node;
        CursorFrame frame = mFrame;
        if (frame == null) {
            // Allocate new frame before latching root -- allocation can block.
            frame = new CursorFrame();
            node = latchRootNode();
        } else {
            node = frame.acquireShared();
            if (node.mSplit != null) {
                node = mTree.finishSplitShared(frame, node);
            }

            int startPos = frame.mNodePos;
            if (startPos < 0) {
                startPos = ~startPos;
            }

            int pos;
            try {
                pos = node.binarySearch(key, startPos);
            } catch (Throwable e) {
                node.releaseShared();
                throw e;
            }

            if (pos >= 0) {
                frame.mNotFoundKey = null;
                frame.mNodePos = pos;
                try {
                    LockResult result = tryLockKey(txn);
                    if (result == null) {
                        mValue = NOT_LOADED;
                    } else {
                        try {
                            mValue = mKeyOnly ? node.hasLeafValue(pos)
                                : node.retrieveLeafValue(pos);
                            return result;
                        } catch (Throwable e) {
                            mValue = NOT_LOADED;
                            throw e;
                        }
                    }
                } finally {
                    node.releaseShared();
                }
                return doLoad(txn, key, frame, VARIANT_REGULAR);
            } else if ((pos != ~0 || (node.type() & Node.LOW_EXTREMITY) != 0) &&
                       (~pos <= node.highestLeafPos() || (node.type() & Node.HIGH_EXTREMITY) != 0))
            {
                // Not found, but insertion pos is in bounds.
                frame.mNotFoundKey = key;
                frame.mNodePos = pos;
                LockResult result = tryLockKey(txn);
                if (result == null) {
                    mValue = NOT_LOADED;
                    node.releaseShared();
                } else {
                    mValue = null;
                    node.releaseShared();
                    return result;
                }
                return doLoad(txn, key, frame, VARIANT_REGULAR);
            }

            // Cannot be certain if position is in leaf node, so pop up.

            mFrame = null;

            while (true) {
                CursorFrame parent = frame.pop();

                if (parent == null) {
                    // Usually the root frame refers to the root node, but it
                    // can be wrong if the tree height is changing.
                    Node root = mTree.mRoot;
                    if (node != root) {
                        node.releaseShared();
                        root.acquireShared();
                        node = root;
                    }
                    frame = null;
                    break;
                }

                node.releaseShared();
                frame = parent;
                node = frame.acquireShared();

                if (node.mSplit != null) {
                    node = mTree.finishSplitShared(frame, node);
                }

                try {
                    pos = Node.internalPos(node.binarySearch(key, frame.mNodePos));
                } catch (Throwable e) {
                    node.releaseShared();
                    throw cleanup(e, frame);
                }

                if ((pos == 0 && (node.type() & Node.LOW_EXTREMITY) == 0) ||
                    (pos >= node.highestInternalPos() && (node.type() & Node.HIGH_EXTREMITY) == 0))
                {
                    // Cannot be certain if position is in this node, so pop up.
                    continue;
                }

                frame.mNodePos = pos;
                try {
                    node = mTree.mDatabase.latchToChild(node, pos);
                } catch (Throwable e) {
                    throw cleanup(e, frame);
                }
                break;
            }

            // Always create a new cursor frame. See CursorFrame.unbind.
            frame = new CursorFrame(frame);
        }

        return find(txn, key, VARIANT_REGULAR, frame, node);
    }

    /**
     * @param frame new frame for node
     * @param node search node to start from
     */
    private LockResult find(LocalTransaction txn, byte[] key, int variant,
                            CursorFrame frame, Node node)
        throws IOException
    {
        while (true) {
            if (node.isLeaf()) {
                int pos;
                if (node.mSplit == null) {
                    try {
                        pos = node.binarySearch(key);
                    } catch (Throwable e) {
                        node.releaseShared();
                        throw cleanup(e, frame);
                    }
                    frame.bind(node, pos);
                } else {
                    try {
                        pos = node.mSplit.binarySearchLeaf(node, key);
                    } catch (Throwable e) {
                        node.releaseShared();
                        throw cleanup(e, frame);
                    }
                    frame.bind(node, pos);
                    if (pos < 0) {
                        // The finishSplit method will release the latch, and so the frame must
                        // be completely defined first.
                        frame.mNotFoundKey = key;
                    }
                    node = mTree.finishSplitShared(frame, node);
                    pos = frame.mNodePos;
                }

                mFrame = frame;

                if (variant == VARIANT_CHECK) {
                    if (pos < 0) {
                        frame.mNotFoundKey = key;
                        mValue = null;
                    } else {
                        mValue = NOT_LOADED;
                    }
                    return LockResult.UNOWNED;
                }

                LockResult result = tryLockKey(txn);

                if (result == null) {
                    // Unable to immediately acquire the lock.
                    if (pos < 0) {
                        frame.mNotFoundKey = key;
                    }
                    mValue = NOT_LOADED;
                    node.releaseShared();
                    // This might fail to acquire the lock too, but the cursor is at the proper
                    // position, and with the proper state.
                    return doLoad(txn, key, frame, variant);
                }

                if (pos < 0) {
                    frame.mNotFoundKey = key;
                    mValue = null;
                } else {
                    try {
                        mValue = mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
                    } catch (Throwable e) {
                        mValue = NOT_LOADED;
                        node.releaseShared();
                        throw e;
                    }
                }

                if (variant == VARIANT_REGULAR) {
                    node.releaseShared();
                }

                return result;
            }

            Split split = node.mSplit;
            if (split == null) {
                int childPos;
                try {
                    childPos = Node.internalPos(node.binarySearch(key));
                } catch (Throwable e) {
                    node.releaseShared();
                    throw cleanup(e, frame);
                }
                frame.bind(node, childPos);
                try {
                    node = mTree.mDatabase.latchToChild(node, childPos);
                } catch (Throwable e) {
                    throw cleanup(e, frame);
                }
            } else {
                // Follow search into split, binding this frame to the unsplit node as if it
                // had not split. The binding will be corrected when the split is finished.

                final Node sibling = split.latchSibling();

                final Node left, right;
                if (split.mSplitRight) {
                    left = node;
                    right = sibling;
                } else {
                    left = sibling;
                    right = node;
                }

                final Node selected;
                final int selectedPos;

                try {
                    if (split.compare(key) < 0) {
                        selected = left;
                        selectedPos = Node.internalPos(left.binarySearch(key));
                        frame.bind(node, selectedPos);
                        right.releaseShared();
                    } else {
                        selected = right;
                        selectedPos = Node.internalPos(right.binarySearch(key));
                        frame.bind(node, left.highestInternalPos() + 2 + selectedPos);
                        left.releaseShared();
                    }
                } catch (Throwable e) {
                    node.releaseShared();
                    sibling.releaseShared();
                    throw cleanup(e, frame);
                }

                try {
                    node = mTree.mDatabase.latchToChild(selected, selectedPos);
                } catch (Throwable e) {
                    throw cleanup(e, frame);
                }
            }

            frame = new CursorFrame(frame);
        }
    }

    /**
     * With node latched, try to lock the current key. Method expects mKeyHash
     * to be valid. Returns null if lock is required but not immediately available.
     *
     * @param txn can be null
     */
    private LockResult tryLockKey(LocalTransaction txn) {
        LockMode mode;

        if (txn == null || (mode = txn.lockMode()) == LockMode.READ_COMMITTED) {
            // If lock is available, no need to acquire full lock and
            // immediately release it because node is latched.
            return mTree.isLockAvailable(txn, mKey, mKeyHash) ? LockResult.UNOWNED : null;
        }

        try {
            if (mode.noReadLock) {
                return LockResult.UNOWNED;
            }

            LockResult result = txn.doTryLock(mode.repeatable, mTree.mId, mKey, mKeyHash, 0L);

            return result.isHeld() ? result : null;
        } catch (DeadlockException e) {
            // Not expected with timeout of zero anyhow.
            return null;
        }
    }

    @Override
    public final LockResult random(byte[] lowKey, boolean lowInclusive,
                                   byte[] highKey, boolean highInclusive)
        throws IOException
    {
        if (!lowInclusive && lowKey != null) {
            // Switch to exclusive start behavior.
            lowKey = ViewUtils.appendZero(lowKey);
        }
        if (highInclusive && highKey != null) {
            // Switch to inclusive end behavior.
            highKey = ViewUtils.appendZero(highKey);
        }

        if (lowKey != null && highKey != null && compareUnsigned(lowKey, highKey) >= 0) {
            // Cannot find anything if range is empty.
            reset();
            return LockResult.UNOWNED;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        start: while (true) {
            reset();
            var frame = new CursorFrame();
            Node node = latchRootNode();

            while (true) {
                if (node.mSplit != null) {
                    // Bind to anything to finish the split.
                    frame.bind(node, 0);
                    node = mTree.finishSplitShared(frame, node);
                }

                int pos;
                try {
                    pos = randomPosition(rnd, node, lowKey, highKey);
                } catch (Throwable e) {
                    node.releaseShared();
                    throw cleanup(e, frame);
                }
                if (pos < 0) {   // Node is empty or out of bounds, so start over.
                    mFrame = frame;
                    resetLatched(node);
                    // Before continuing, check if range has anything in it at all. This must
                    // be performed each time, to account for concurrent updates.
                    if (isRangeEmpty(lowKey, highKey)) {
                        return LockResult.UNOWNED;
                    }
                    continue start;
                }

                // Need to bind specially in case split handling above already bound the frame.
                frame.bindOrReposition(node, pos);

                if (node.isLeaf()) {
                    mFrame = frame;
                    LocalTransaction txn;
                    try {
                        txn = prepareFind(node.retrieveKey(pos));
                    } catch (Throwable e) {
                        resetLatched(node);
                        throw e;
                    }

                    LockResult result;
                    if ((result = tryLockKey(txn)) == null) {
                        // Unable to immediately acquire the lock.
                        mValue = NOT_LOADED;
                        node.releaseShared();
                        // This might fail to acquire the lock too, but the cursor
                        // is at the proper position, and with the proper state.
                        result = doLoad(txn, mKey, frame, VARIANT_REGULAR);
                    } else {
                        try {
                            mValue = mKeyOnly ? node.hasLeafValue(pos)
                                : node.retrieveLeafValue(pos);
                        } catch (Throwable e) {
                            mValue = NOT_LOADED;
                            node.releaseShared();
                            throw e;
                        }
                        node.releaseShared();
                    }

                    if (mValue == null) {
                        // Skip over ghosts. Attempting to lock ghosts in the
                        // first place is correct behavior, avoiding bias.
                        if (result == LockResult.ACQUIRED) {
                            txn.doUnlock();
                        }

                        frame = frameSharedNotSplit();

                        if (rnd.nextBoolean()) {
                            result = highKey == null ? next(txn, frame)
                                : nextCmp(highKey, LIMIT_LT, frame);
                            if (mValue == null) {
                                // Wrap around.
                                return first();
                            }
                        } else {
                            result = lowKey == null ? previous(txn, frame)
                                : previousCmp(lowKey, LIMIT_GE, frame);
                            if (mValue == null) {
                                // Wrap around.
                                return last();
                            }
                        }
                    }

                    return result;
                }

                try {
                    node = mTree.mDatabase.latchToChild(node, pos);
                } catch (Throwable e) {
                    throw cleanup(e, frame);
                }

                frame = new CursorFrame(frame);
            }
        }
    }
    
    /**
     * Select a random node, steering towards a node that is not cached.  
     *
     * @param lowKey inclusive lowest key from which to pick the random node; pass null for
     * open range
     * @param highKey exclusive highest key from which to pick the random node; pass null for
     * open range
     * @return returns the value of the highest key on the node on success; returns null on failure
     * @throws IOException
     */
    byte[] randomNode(byte[] lowKey, byte[] highKey) throws IOException {
        if (lowKey != null && highKey != null && compareUnsigned(lowKey, highKey) >= 0) {
            // Cannot find anything if range is empty.
            reset();
            return null;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        start: while (true) {
            reset();
            var frame = new CursorFrame();
            Node node = latchRootNode();

            // Until the cursor hits a node that is 3 deep (parent of a bottom internal node), the 
            // algorithm proceeds by picking random nodes. At that point, the algorithm tries to 
            // pick a 2 deep (bottom internal node) node that is not in cache. It tries to do this
            // twice. On the third attempt, it picks a random bottom internal node. 
            int remainingAttemptsBIN = 2; 

            // Once the cursor is at a bottom internal node, the algorithm tries twice to pick
            // a leaf node that is not in the cache. On the third attempt, it picks a random
            // leaf node.
            int remainingAttemptsLN = 2;

            search: while (true) {
                if (node.mSplit != null) {
                    // Bind to anything to finish the split.
                    frame.bindOrReposition(node, 0);
                    node = mTree.finishSplitShared(frame, node);
                }

                int pos;
                if (node.isLeaf()) {
                    // Bind to the first key, if it exists.
                    pos = node.highestLeafPos() >> 31; // -1 or 0
                } else {
                    try {
                        pos = randomPosition(rnd, node, lowKey, highKey);
                    } catch (Throwable e) {
                        node.releaseShared();
                        throw cleanup(e, frame);
                    }
                }

                if (pos < 0) {   // Node is empty or out of bounds, so start over.
                    mFrame = frame;
                    resetLatched(node);
                    // Before continuing, check if range has anything in it at all. This must
                    // be performed each time, to account for concurrent updates.
                    if (isRangeEmpty(lowKey, highKey)) {
                        return null;
                    }
                    // Go to start. This will reset all counts. If we went back to search,
                    // this would be an infinite loop
                    continue start;
                }

                // Need to bind specially in case split handling above already bound the frame.
                frame.bindOrReposition(node, pos);

                if (node.isLeaf()) {
                    byte[] startKey = node.retrieveKey(pos);

                    byte[] endKey;
                    {
                        int highPos = node.highestLeafPos();
                        endKey = highPos == pos ? startKey : node.retrieveKey(highPos);
                    }

                    mFrame = frame;
                    LocalTransaction txn;
                    try {
                        txn = prepareFind(startKey);
                    } catch (Throwable e) {
                        resetLatched(node);
                        throw e;
                    }

                    if (tryLockKey(txn) == null) {
                        // Unable to immediately acquire the lock.
                        mValue = NOT_LOADED;
                        node.releaseShared();
                        // This might fail to acquire the lock too, but the cursor
                        // is at the proper position, and with the proper state.
                        doLoad(txn, mKey, frame, VARIANT_REGULAR);
                    } else {
                        try {
                            mValue = mKeyOnly ? node.hasLeafValue(pos)
                                : node.retrieveLeafValue(pos);
                        } catch (Throwable e) {
                            mValue = NOT_LOADED;
                            node.releaseShared();
                            throw e;
                        }
                        node.releaseShared();
                    }

                    return endKey;
                }

                long childId = node.childId(pos);
                Node child = mTree.mDatabase.nodeMapGet(childId);

                if (child != null) { 
                    // Node is in cache. If its not cache, we found a good path and so we go
                    // down directly to the latchToChild code below.

                    if (node.isBottomInternal()) {
                        // Node is not a leaf, but of its child nodes are all leaves.

                        if (--remainingAttemptsLN >= 0) {
                            // Retry another child of the same node.
                            continue search;
                        }

                        // Used up max random selection attempts for non-cached leaf node.
                        // Scan sequentially for a non-cached leaf node.
                        try {
                            int spos = 0;
                            if (lowKey != null) {
                                spos = Node.internalPos(node.binarySearch(lowKey));
                            }

                            int highestInternalPos = node.highestInternalPos();
                            int highestKeyPos = node.highestKeyPos();
                            for (; spos <= highestInternalPos; spos += 2) {
                                childId = node.childId(spos);
                                child = mTree.mDatabase.nodeMapGet(childId);
                                if (child == null) { // node is not cached
                                    pos = spos;
                                    frame.bindOrReposition(node, pos);
                                    break; // go down to latching the node and then on to search
                                }
                                if (highKey != null && spos <= highestKeyPos
                                    && node.compareKey(spos, highKey) >= 0)
                                {
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            // Continue with the randomly selected node.
                        }
                    } else {
                        // Non-bottom internal node.

                        child.acquireShared();
                        try {
                            // - Always check the id first. By checking the id first (with the
                            // latch held), then the isBottomInternal check won't see a "lock"
                            // Node instance, which never refers to a valid page. The lock
                            // instance is held exclusive for the duration of a child load
                            // operation, and the id is set to zero (and latch released) when
                            // the load completes. The id check in the changed code will fail
                            // first because it sees the zero id.
                            //
                            // - If loaded child is not in cache (i.e., childId != child.mId),
                            // this is a good path. Go on down to latchChild.
                            //
                            // - If loaded child is in cache and is not a bottom internal node,
                            // continue using the node.
                            //
                            // - If loaded child is in cache, is a bottom internal node, and
                            // there are more than 0 attemptsRemaining on BIN, then retry with
                            // another child of the node.
                            //
                            // - If loaded child is in cache, is a bottom internal node, and
                            // there are no more remaining on BIN, then use the node.
                            if (childId == child.id() && child.isBottomInternal()
                                && --remainingAttemptsBIN >= 0)
                            {
                                // Retry another child of the same node.
                                continue search;
                            }
                        } finally {
                            child.releaseShared();
                        }
                    }
                }

                try {
                    node = mTree.mDatabase.latchToChild(node, pos);
                } catch (Throwable e) {
                    throw cleanup(e, frame);
                }

                frame = new CursorFrame(frame);
            } // search
        } // start
    }

    /**
     * Must be called with node latch not held.
     *
     * @param variant VARIANT_REGULAR or VARIANT_RETAIN
     */
    private LockResult doLoad(LocalTransaction txn, byte[] key, CursorFrame leaf, int variant)
        throws IOException
    {
        LockResult result;
        Locker locker;

        if (txn == null) {
            result = LockResult.UNOWNED;
            locker = mTree.lockSharedLocal(key, keyHash());
        } else {
            LockMode mode = txn.lockMode();
            if (mode.noReadLock) {
                // Not expected. Caller typically calls tryLockKey first, which would have
                // returned UNOWNED instead of null, and doLoad won't be called.
                result = LockResult.UNOWNED;
                locker = null;
            } else {
                int keyHash = keyHash();
                if (mode == LockMode.READ_COMMITTED) {
                    result = txn.doLockShared(mTree.mId, key, keyHash);
                    if (result == LockResult.ACQUIRED) {
                        result = LockResult.UNOWNED;
                        locker = txn;
                    } else {
                        locker = null;
                    }
                } else {
                    result = txn.doLock
                        (mode.repeatable, mTree.mId, key, keyHash, txn.mLockTimeoutNanos);
                    locker = null;
                }
            }
        }

        try {
            Node node = leaf.acquireShared();
            if (node.mSplit != null) {
                node = mTree.finishSplitShared(leaf, node);
            }
            try {
                int pos = leaf.mNodePos;
                mValue = pos < 0 ? null
                    : mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
            } catch (Throwable e) {
                node.releaseShared();
                throw e;
            }
            if (variant == VARIANT_REGULAR) {
                node.releaseShared();
            }
            return result;
        } finally {
            if (locker != null) {
                locker.doUnlock();
            }
        }
    }

    /**
     * Analyze at the current position. Cursor is reset as a side-effect.
     */
    IndexStats analyze() throws IOException {
        double entryCount, keyBytes, valueBytes, freeBytes, totalBytes;

        CursorFrame frame = frameSharedNotSplit();
        Node node = frame.mNode;
        try {
            entryCount = node.numKeys();

            int pos = frame.mNodePos;
            int numKeys;

            freeBytes = node.availableBytes();
            totalBytes = pageSize(node.mPage);

            if (pos < 0 || (numKeys = node.numKeys()) <= 0) {
                keyBytes = 0;
                valueBytes = 0;
            } else {
                var stats = new long[2];

                node.retrieveKeyStats(pos, stats);
                keyBytes = ((double) stats[0]) * numKeys;
                totalBytes += ((double) stats[1]) * pageSize(node.mPage);

                node.retrieveLeafValueStats(pos, stats);
                valueBytes = ((double) stats[0]) * numKeys;
                totalBytes += ((double) stats[1]) * pageSize(node.mPage);
            }

            frame = frame.pop();
        } catch (Throwable e) {
            resetLatched(node);
            throw e;
        }

        node.releaseShared();

        while (frame != null) {
            double scalar;
            int availBytes;
            int pageSize;

            node = frame.acquireShared();
            try {
                scalar = node.numKeys() + 1; // internal nodes have +1 children
                availBytes = node.availableInternalBytes();
                pageSize = pageSize(node.mPage);
                frame = frame.pop();
            } finally {
                node.releaseShared();
            }

            entryCount *= scalar;
            keyBytes *= scalar;
            valueBytes *= scalar;
            freeBytes *= scalar;
            totalBytes *= scalar;

            freeBytes += availBytes;
            totalBytes += pageSize;
        }

        return new IndexStats(entryCount, keyBytes, valueBytes, freeBytes, totalBytes);
    }

    @Override
    public final boolean exists() throws IOException {
        CursorFrame leaf = frameSharedNotSplit();
        Node node = leaf.mNode;
        try {
            int pos = leaf.mNodePos;
            if (pos < 0) {
                // Definitely doesn't exist.
                return false;
            }
            // Perform additional checks in case the value is a ghost.
            LockManager manager;
            if (mTxn == null || (manager = mTxn.mManager) == null
                || node.hasLeafValue(pos) != null)
            {
                // Value isn't a ghost, or without a transaction holding a lock, can't check.
                return true;
            }
            // Value is a ghost, but only treat it as a visible delete if the transaction owns
            // the lock, implying that it performed the delete.
            return !manager.check(mTxn, mTree.mId, mKey, keyHash()).isHeld();
        } finally {
            node.releaseShared();
        }
    }

    @Override
    public final LockResult lock() throws IOException {
        final byte[] key = mKey;
        ViewUtils.positionCheck(key);

        final CursorFrame leaf = frame();
        final LocalTransaction txn = mTxn;

        LockResult result;
        final Locker locker;

        try {
            if (txn == null) {
                int keyHash = keyHash();
                if (tryLockLoad(txn, key, keyHash, mKeyOnly, leaf)) {
                    return LockResult.UNOWNED;
                }
                locker = mTree.lockSharedLocal(key, keyHash);
                result = LockResult.UNOWNED;
            } else {
                LockMode mode = txn.lockMode();
                if (mode.noReadLock) {
                    return LockResult.UNOWNED;
                }
                int keyHash = keyHash();
                if (mode == LockMode.READ_COMMITTED) {
                    if (tryLockLoad(txn, key, keyHash, mKeyOnly, leaf)) {
                        return LockResult.UNOWNED;
                    }
                    result = txn.doLockShared(mTree.mId, key, keyHash);
                    if (result != LockResult.ACQUIRED) {
                        // Not expected. If the transaction already owned the lock, then
                        // the tryLockLoad call earlier would have returned true.
                        return result;
                    }
                    result = LockResult.UNOWNED;
                    locker = txn;
                } else {
                    result = txn.doLock
                        (mode.repeatable, mTree.mId, key, keyHash, txn.mLockTimeoutNanos);
                    if (result != LockResult.ACQUIRED) {
                        return result;
                    }
                    locker = null;
                }
            }
        } catch (LockFailureException e) {
            mValue = NOT_LOADED;
            throw e;
        }

        try {
            Node node = leaf.acquireShared();
            if (node.mSplit != null) {
                node = mTree.finishSplitShared(leaf, node);
            }
            try {
                int pos = leaf.mNodePos;
                mValue = pos < 0 ? null
                    : mKeyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
            } catch (Throwable e) {
                node.releaseShared();
                throw e;
            }
            node.releaseShared();
            return result;
        } finally {
            if (locker != null) {
                locker.doUnlock();
            }
        }
    }

    @Override
    public final LockResult load() throws IOException {
        final byte[] key = mKey;
        ViewUtils.positionCheck(key);

        final CursorFrame leaf = frame();
        final LocalTransaction txn = mTxn;

        LockResult result;
        final Locker locker;

        try {
            if (txn == null) {
                int keyHash = keyHash();
                if (tryLockLoad(txn, key, keyHash, false, leaf)) {
                    return LockResult.UNOWNED;
                }
                locker = mTree.lockSharedLocal(key, keyHash);
                result = LockResult.UNOWNED;
            } else {
                LockMode mode = txn.lockMode();
                if (mode.noReadLock) {
                    result = LockResult.UNOWNED;
                    locker = null;
                } else {
                    int keyHash = keyHash();
                    if (mode == LockMode.READ_COMMITTED) {
                        if (tryLockLoad(txn, key, keyHash, false, leaf)) {
                            return LockResult.UNOWNED;
                        }
                        result = txn.doLockShared(mTree.mId, key, keyHash);
                        if (result == LockResult.ACQUIRED) {
                            result = LockResult.UNOWNED;
                            locker = txn;
                        } else {
                            // Not expected. If the transaction already owned the lock, then
                            // the tryLockLoad call earlier would have returned true.
                            locker = null;
                        }
                    } else {
                        result = txn.doLock
                            (mode.repeatable, mTree.mId, key, keyHash, txn.mLockTimeoutNanos);
                        locker = null;
                    }
                }
            }
        } catch (LockFailureException e) {
            mValue = NOT_LOADED;
            throw e;
        }

        try {
            Node node = leaf.acquireShared();
            if (node.mSplit != null) {
                node = mTree.finishSplitShared(leaf, node);
            }
            try {
                int pos = leaf.mNodePos;
                mValue = pos < 0 ? null : node.retrieveLeafValue(pos);
            } catch (Throwable e) {
                node.releaseShared();
                throw e;
            }
            node.releaseShared();
            return result;
        } finally {
            if (locker != null) {
                locker.doUnlock();
            }
        }
    }

    private boolean tryLockLoad(LocalTransaction txn, byte[] key, int keyHash, boolean keyOnly,
                                CursorFrame leaf)
        throws IOException
    {
        Node node = leaf.tryAcquireShared();
        if (node != null) {
            if (node.mSplit != null) {
                node = mTree.finishSplitShared(leaf, node);
            }
            try {
                if (mTree.isLockAvailable(txn, key, keyHash)) {
                    // No need to acquire full lock.
                    int pos = leaf.mNodePos;
                    if (pos >= 0) {
                        mValue = keyOnly ? node.hasLeafValue(pos) : node.retrieveLeafValue(pos);
                    } else {
                        checkClosedIndexException(node.mPage);
                        mValue = null;
                    }
                    return true;
                }
            } finally {
                node.releaseShared();
            }
        }
        return false;
    }

    /**
     * Returns false if modifications to the tree cannot write to the redo log.
     */
    private boolean allowRedo() {
        return !(mTree instanceof BTree.Temp);
    }

    /**
     * Returns true if auto-commit transactions (null) require a transaction which always
     * writes to the redo log.
     */
    private boolean requireTransaction() {
        return mTree instanceof BTree.Repl;
    }

    @Override
    public final void store(byte[] value) throws IOException {
        if (mTxn == null) {
            storeAutoCommit(value);
        } else {
            txnStore(value);
        }
    }

    /**
     * Can only be called when cursor is linked to a transaction.
     */
    private void txnStore(byte[] value) throws IOException {
        byte[] key = mKey;
        ViewUtils.positionCheck(key);
        try {
            if (mTxn.lockMode() != LockMode.UNSAFE) {
                mTxn.doLockExclusive(mTree.mId, key, keyHash());
            }
            if (allowRedo()) {
                storeAndMaybeRedo(mTxn, value);
            } else {
                storeNoRedo(mTxn, value);
            }
        } catch (Throwable e) {
            throw handleException(e, false);
        }
    }
    
    private void storeAutoCommit(byte[] value) throws IOException {
        byte[] key = mKey;
        ViewUtils.positionCheck(key);

        try {
            final LocalTransaction txn;
            if (!allowRedo()) {
                // Never redo, but still acquire the lock.
                txn = LocalTransaction.BOGUS;
            } else if (!requireTransaction()) {
                txn = null;
            } else {
                // Always undo (and redo).
                LocalDatabase db = mTree.mDatabase;
                txn = db.threadLocalTransaction(alwaysRedo(db.mDurabilityMode));
                try {
                    txn.doLockExclusive(mTree.mId, key, keyHash());
                    txn.storeCommit(txn, this, value);
                    return;
                } catch (Throwable e) {
                    db.removeThreadLocalTransaction();
                    txn.reset();
                    throw e;
                }
            }

            final Locker locker = mTree.lockExclusiveLocal(key, keyHash());
            try {
                storeAndMaybeRedo(txn, value);
            } finally {
                locker.doUnlock();
            }
        } catch (Throwable e) {
            throw handleException(e, false);
        }
    }

    @Override
    public final void commit(byte[] value) throws IOException {
        if (mTxn == null) {
            storeAutoCommit(value);
        } else {
            txnCommit(value);
        }
    }

    /**
     * Can only be called when cursor is linked to a transaction.
     */
    private void txnCommit(byte[] value) throws IOException {
        byte[] key = mKey;
        ViewUtils.positionCheck(key);

        try {
            store: {
                if (mTxn.lockMode() != LockMode.UNSAFE) {
                    mTxn.doLockExclusive(mTree.mId, key, keyHash());
                    if (allowRedo() && mTxn.mDurabilityMode != DurabilityMode.NO_REDO) {
                        mTxn.storeCommit(requireTransaction() ? mTxn : LocalTransaction.BOGUS,
                                         this, value);
                        return;
                    }
                } else if (allowRedo()) {
                    storeAndMaybeRedo(mTxn, value);
                    break store;
                }

                storeNoRedo(mTxn, value);
            }

            mTxn.commit();
        } catch (Throwable e) {
            throw handleException(e, false);
        }
    }

    /**
     * Atomic find and store operation. Cursor must be in a reset state when this method is
     * called, and the caller must reset the cursor afterwards.
     *
     * <p>If mKeyOnly is true (autoload is off), the returned original value is always null.
     *
     * @param key must not be null
     * @return original value
     */
    final byte[] findAndStore(byte[] key, byte[] value) throws IOException {
        if (mTxn == null) {
            return findAndStoreAutoCommit(key, value);
        } else {
            return txnFindAndStore(key, value);
        }
    }

    /**
     * Can only be called when cursor is linked to a transaction.
     *
     * @return original value
     */
    private byte[] txnFindAndStore(byte[] key, byte[] value) throws IOException {
        mKey = key;
        try {
            if (mTxn.lockMode() == LockMode.UNSAFE) {
                mKeyHash = 0;
            } else {
                final int hash = LockManager.hash(mTree.mId, key);
                mKeyHash = hash;
                mTxn.doLockExclusive(mTree.mId, key, hash);
            }
            return doFindAndStore(mTxn, key, value);
        } catch (Throwable e) {
            throw handleException(e, false); // no reset on safe exception
        }
    }

    /**
     * @return original value
     */
    private byte[] findAndStoreAutoCommit(byte[] key, byte[] value) throws IOException {
        mKey = key;

        try {
            final int hash = LockManager.hash(mTree.mId, key);
            mKeyHash = hash;

            final LocalTransaction txn;
            if (!allowRedo()) {
                // Never redo, but still acquire the lock.
                txn = LocalTransaction.BOGUS;
            } else if (!requireTransaction()) {
                txn = null;
            } else {
                // Always undo (and redo).
                LocalDatabase db = mTree.mDatabase;
                txn = db.threadLocalTransaction(alwaysRedo(db.mDurabilityMode));
                try {
                    txn.doLockExclusive(mTree.mId, key, hash);
                    byte[] result = doFindAndStore(txn, key, value);
                    txn.commit();
                    return result;
                } catch (Throwable e) {
                    db.removeThreadLocalTransaction();
                    txn.reset();
                    throw e;
                }
            }

            final Locker locker = mTree.lockExclusiveLocal(key, hash);
            try {
                return doFindAndStore(txn, key, value);
            } finally {
                locker.doUnlock();
            }
        } catch (Throwable e) {
            throw handleException(e, false); // no reset on safe exception
        }
    }

    /**
     * If mKeyOnly is true (autoload is off), the returned original value is always null.
     *
     * @return original value
     */
    private byte[] doFindAndStore(LocalTransaction txn, byte[] key, byte[] value)
        throws IOException
    {
        // Find with no lock because it has already been acquired. Leaf latch is retained too.
        find(null, key, VARIANT_CHECK, new CursorFrame(), latchRootNode());

        CursorFrame leaf = mFrame;
        CommitLock.Shared shared = prepareStoreUpgrade(leaf, value);
        byte[] originalValue = null;
        int pos = leaf.mNodePos;

        if (pos >= 0 && !mKeyOnly) {
            Node node = leaf.mNode;
            try {
                originalValue = node.retrieveLeafValue(pos);
            } catch (Throwable e) {
                node.releaseExclusive();
                shared.release();
                throw e;
            }
        }

        if (value == null) {
            deleteNoRedo(txn, leaf);
        } else {
            storeNoRedo(txn, leaf, value);
        }

        if (allowRedo()) {
            maybeRedoStore(txn, shared, value);
        } else {
            shared.release();
        }

        return originalValue;
    }

    static final byte[]
        MODIFY_INSERT = new byte[0], MODIFY_REPLACE = new byte[0], MODIFY_UPDATE = new byte[0];

    /**
     * Atomic find and modify operation. Cursor must be in a reset state when this method is
     * called, and the caller must reset the cursor afterwards.
     *
     * @param key must not be null
     * @param oldValue MODIFY_INSERT, MODIFY_REPLACE, MODIFY_UPDATE, else actual old value
     */
    final boolean findAndModify(byte[] key, byte[] oldValue, byte[] newValue) throws IOException {
        mKey = key;
        LocalTransaction txn = mTxn;

        try {
            // Note: Acquire exclusive lock instead of performing upgrade sequence. The upgrade
            // would need to be performed with the node latch held, which is deadlock prone.

            if (txn == null) {
                final int hash = LockManager.hash(mTree.mId, key);
                mKeyHash = hash;
                if (!allowRedo()) {
                    // Never redo, but still acquire the lock.
                    txn = LocalTransaction.BOGUS;
                } else if (requireTransaction()) {
                    // Always undo (and redo).
                    LocalDatabase db = mTree.mDatabase;
                    txn = db.threadLocalTransaction(alwaysRedo(db.mDurabilityMode));
                    try {
                        txn.doLockExclusive(mTree.mId, key, hash);
                        boolean result = doFindAndModify(txn, key, oldValue, newValue);
                        txn.commit();
                        return result;
                    } catch (Throwable e) {
                        db.removeThreadLocalTransaction();
                        txn.reset();
                        throw e;
                    }
                }

                final Locker locker = mTree.lockExclusiveLocal(key, hash);
                try {
                    return doFindAndModify(txn, key, oldValue, newValue);
                } finally {
                    locker.doUnlock();
                }
            }

            LockResult result;

            LockMode mode = txn.lockMode();
            if (mode == LockMode.UNSAFE) {
                mKeyHash = 0;
                // Indicate that no unlock should be performed.
                result = LockResult.OWNED_EXCLUSIVE;
            } else {
                final int hash;
                mKeyHash = hash = LockManager.hash(mTree.mId, key);
                result = txn.doLockExclusive(mTree.mId, key, hash);
                if (result == LockResult.ACQUIRED && mode.repeatable != 0) {
                    // Downgrade to upgradable when no modification is made, to
                    // preserve repeatable semantics and allow upgrade later.
                    result = LockResult.UPGRADED;
                }
            }

            try {
                if (doFindAndModify(txn, key, oldValue, newValue)) {
                    return true;
                }
            } catch (Throwable e) {
                try {
                    if (result == LockResult.ACQUIRED) {
                        txn.doUnlock();
                    } else if (result == LockResult.UPGRADED) {
                        txn.doUnlockToUpgradable();
                    }
                } catch (Throwable e2) {
                    // Assume transaction is invalid now.
                }

                throw e;
            }

            if (result == LockResult.ACQUIRED) {
                txn.doUnlock();
            } else if (result == LockResult.UPGRADED) {
                txn.doUnlockToUpgradable();
            }

            return false;
        } catch (Throwable e) {
            throw handleException(e, false); // no reset on safe exception
        }
    }

    /**
     * Caller must have acquired the leaf node shared latch, which is always released by this
     * method.
     *
     * @param key non-null key, which should already be locked exclusively
     * @param oldValue MODIFY_INSERT, MODIFY_REPLACE, MODIFY_UPDATE, else actual old value
     */
    private boolean doFindAndModify(LocalTransaction txn,
                                    byte[] key, byte[] oldValue, byte[] newValue)
        throws IOException
    {
        // Find with no lock because caller must already acquire exclusive lock.
        find(null, key, VARIANT_CHECK, new CursorFrame(), latchRootNode());

        final CursorFrame leaf = mFrame;
        Node node = leaf.mNode;
        boolean exclusive = false;

        final CommitLock commitLock = mTree.mDatabase.commitLock();
        CommitLock.Shared shared = commitLock.tryAcquireShared();

        if (shared == null) {
            node.releaseShared();
            shared = commitLock.acquireShared();
            node = leaf.acquireShared();
            splitCheck: if (node.mSplit != null) {
                exclusive = true;
                if (!node.tryUpgrade()) {
                    node.releaseShared();
                    node = leaf.acquireExclusive();
                    if (node.mSplit == null) {
                        break splitCheck;
                    }
                }
                node = mTree.finishSplit(leaf, node);
            }
        }

        while (true) {
            int pos = leaf.mNodePos;

            check: {
                if (oldValue == MODIFY_INSERT) {
                    if (pos < 0 || node.hasLeafValue(pos) == null) {
                        // Insert allowed.
                        break check;
                    }
                } else if (oldValue == MODIFY_REPLACE) {
                    if (pos >= 0 && node.hasLeafValue(pos) != null) {
                        // Replace allowed.
                        break check;
                    }
                } else {
                    byte[] originalValue;
                    if (pos < 0) {
                        originalValue = null;
                    } else {
                        try {
                            // TODO: If originalValue is fragmented, compare without loading.
                            originalValue = node.retrieveLeafValue(pos);
                        } catch (Throwable e) {
                            node.releaseExclusive();
                            shared.release();
                            throw e;
                        }
                    }
                    if (oldValue == MODIFY_UPDATE) {
                        if (!Arrays.equals(originalValue, newValue)) {
                            // Update allowed.
                            break check;
                        }
                    } else {
                        // Provided an ordinary oldValue.
                        if (Arrays.equals(oldValue, originalValue)) {
                            // Update allowed.
                            break check;
                        }
                    }
                }

                node.release(exclusive);
                shared.release();
                return false;
            }

            try {
                if (notSplitDirtyUpgrade(leaf, exclusive)) {
                    break;
                }
            } catch (Throwable e) {
                shared.release();
                throw e;
            }

            exclusive = true;
            node = leaf.mNode;
        }

        if (newValue == null) {
            deleteNoRedo(txn, leaf);
        } else {
            storeNoRedo(txn, leaf, newValue);
        }

        if (allowRedo()) {
            maybeRedoStore(txn, shared, newValue);
        } else {
            shared.release();
        }

        return true;
    }

    /**
     * Non-transactional ghost delete. Caller is expected to hold exclusive key lock. Method
     * does nothing if a value exists. Cursor must be in a reset state when called, and cursor
     * is also reset as a side-effect.
     *
     * @return false if BTree is closed
     */
    final boolean deleteGhost(byte[] key) throws IOException {
        try {
            // Find with no lock because it has already been acquired.
            find(null, key, VARIANT_CHECK, new CursorFrame(), latchRootNode());

            try {
                CursorFrame leaf = mFrame;
                CommitLock.Shared shared = prepareStoreUpgrade(leaf, null);

                Node node = leaf.mNode;
                if (isClosedOrDeleted(node.mPage)) {
                    node.releaseExclusive();
                    shared.release();
                    return false;
                }

                int pos = leaf.mNodePos;
                if (pos >= 0 && node.hasLeafValue(pos) == null) {
                    deleteNoRedo(LocalTransaction.BOGUS, leaf);
                } else {
                    // Non-ghost value exists.
                    node.releaseExclusive();
                }

                shared.release();
                return true;
            } finally {
                reset();
            }
        } catch (Throwable e) {
            throw handleException(e, true);
        }
    }

    /**
     * Non-transactionally store a ghost value. Also see Node.txnDeleteLeafEntry.
     *
     * @param ghost frame to bind; can be null to not bind anything
     */
    final void storeGhost(GhostFrame ghost) throws IOException {
        CommitLock.Shared shared = prepareStore();

        // Keeping the exclusive latch is ideal, but I'd rather not have to create special
        // modifications for an infrequent operation. Releasing the latch permits a concurrent
        // modification, which is checked by ensuring that a value exists and is empty.
        storeNoRedo(null, mFrame, EMPTY_BYTES);

        CursorFrame leaf;
        try {
            leaf = frameExclusive();

            // Releases leaf latch if an exception is thrown.
            notSplitDirty(leaf);

            Node node = leaf.mNode;
            int pos = leaf.mNodePos;

            try {
                if (pos >= 0) { // value must still exist
                    var page = node.mPage;
                    int loc = p_ushortGetLE(page, node.searchVecStart() + pos);

                    // Skip the key.
                    loc += Node.keyLengthAtLoc(page, loc);

                    if (p_byteGet(page, loc) == 0) { // value must still be empty
                        if (ghost != null) {
                            ghost.bind(node, pos);
                            mTree.mLockManager.ghosted(mTree.mId, mKey, keyHash(), ghost);
                        }
                        p_bytePut(page, loc, -1); // ghost value
                        mValue = null;
                    }
                }
            } finally {
                node.releaseExclusive();
            }
        } finally {
            shared.release();
        }
    }

    /**
     * Convenience method, which prepares and finishes the store, which assumes that a key
     * exists and that any necessary locking has been performed. No redo logs are written.
     *
     * @param txn can be null
     * @param value pass null to delete
     */
    final void storeNoRedo(LocalTransaction txn, byte[] value) throws IOException {
        doStoreNoRedo(txn, value).release();
    }

    /**
     * Convenience method, which prepares and finishes the store, which assumes that a key
     * exists and that any necessary locking has been performed. Redo logs aren't actually
     * written if the durability mode is NO_REDO.
     *
     * @param txn can be null
     * @param value pass null to delete
     */
    final void storeAndMaybeRedo(LocalTransaction txn, byte[] value) throws IOException {
        maybeRedoStore(txn, doStoreNoRedo(txn, value), value);
    }

    private CommitLock.Shared doStoreNoRedo(LocalTransaction txn, byte[] value) throws IOException {
        CommitLock.Shared shared;

        if (value == null) {
            // Note: Similar to prepareStore, except it might skip the node dirty step.
            {
                shared = mTree.mDatabase.commitLock().acquireShared();
                CursorFrame leaf = frameExclusive();

                // Only dirty the node if delete will actually do something.
                if (leaf.mNodePos >= 0) {
                    try {
                        // Releases leaf latch if an exception is thrown.
                        notSplitDirty(leaf);
                    } catch (Throwable e) {
                        shared.release();
                        throw e;
                    }
                }
            }

            deleteNoRedo(txn, mFrame);
        } else {
            shared = prepareStore();
            storeNoRedo(txn, mFrame, value);
        }

        mValue = value;
        return shared;
    }

    /**
     * Called after delete or store, to write to redo log and possibly wait for a commit. If
     * transaction is NO_REDO, then nothing is written to the redo log.
     *
     * @param txn can be null
     * @param shared held commit lock, which is always released by this method
     * @param value pass null to delete
     */
    private void maybeRedoStore(LocalTransaction txn, CommitLock.Shared shared, byte[] value)
        throws IOException
    {
        long commitPos;

        if (txn == null) {
            try {
                commitPos = mTree.redoStoreNullTxn(mKey, value);
            } finally {
                shared.release();
            }

            if (commitPos != 0) {
                // Wait for commit sync without holding commit lock and node latch.
                mTree.txnCommitSync(commitPos);
            }
        } else {
            try {
                if (txn.mDurabilityMode == DurabilityMode.NO_REDO) {
                    return;
                } else if (txn.lockMode() != LockMode.UNSAFE) {
                    long cursorId = mCursorId;
                    if (cursorId == 0) {
                        txn.redoStore(mTree.mId, mKey, value);
                    } else {
                        // Always write the key, for simplicity. There's no good reason for an
                        // application to update the same entry multiple times.
                        txn.redoCursorStore(cursorId & ~(1L << 63), mKey, value);
                        mCursorId = cursorId | (1L << 63);
                    }
                    return;
                } else {
                    commitPos = txn.redoStoreNoLock(mTree.mId, mKey, value);
                }
            } finally {
                shared.release();
            }

            if (commitPos != 0) {
                // If the transaction supports redo, and the lock mode is unsafe, then the
                // store is effectively auto-commit. The transaction isn't truly committed at
                // this point, and the unsafe store committed is out-of-band. Combining safe
                // and unsafe stores within a transaction breaks atomicity. If strong redo
                // durability is requested, then wait for it without holding the commit lock.
                txn.mRedo.txnCommitSync(commitPos);
            }
        }
    }

    /**
     * Must call prepareStore before calling this method, which ensures that the node is
     * dirtied and not split. If the delete won't actually do anything, then the node doesn't
     * need to be dirtied.
     *
     * Caller must have acquired the shared commit lock, which is released by this method if an
     * exception is thrown.
     *
     * @param leaf leaf frame, latched exclusively, which is always released by this method
     */
    private void deleteNoRedo(LocalTransaction txn, CursorFrame leaf) throws IOException {
        try {
            Node node = leaf.mNode;
            int pos = leaf.mNodePos;

            if (pos >= 0) {
                byte[] key = mKey;
                try {
                    if (txn != null && txn.lockMode() != LockMode.UNSAFE) {
                        node.txnDeleteLeafEntry(txn, mTree, key, keyHash(), pos);
                    } else {
                        node.deleteLeafEntry(pos);
                        // Fix all bound cursors, including this one.
                        node.postDelete(pos, key);
                    }
                } catch (Throwable e) {
                    node.releaseExclusive();
                    throw e;
                }

                if (node.shouldLeafMerge()) {
                    // Releases node as a side-effect.
                    mergeLeaf(leaf, node);
                    return;
                }
            }

            node.releaseExclusive();
        } catch (Throwable e) {
            // Release the shared lock.
            mTree.mDatabase.commitLock().unlock();

            rethrowIfRecoverable(e);
            if (txn != null) {
                txn.reset(e);
            }
            throw e;
        }
    }

    /**
     * Acquires the shared commit lock, acquires exclusive frame latch, and prepares the frame
     * for a store operation (not split, dirty).
     *
     * @return held commitLock
     */
    private CommitLock.Shared prepareStore() throws IOException {
        CommitLock.Shared shared = mTree.mDatabase.commitLock().acquireShared();
        CursorFrame leaf = frameExclusive();

        try {
            // Releases leaf latch if an exception is thrown.
            notSplitDirty(leaf);
        } catch (Throwable e) {
            shared.release();
            throw e;
        }

        return shared;
    }

    /**
     * With leaf frame held shared, acquires the shared commit lock, upgrades the frame to
     * exclusive, and prepares the frame for a store operation (not split, dirty). If value is
     * null and nothing needs to actually be deleted, node isn't dirtied. Leaf latch might be
     * released and re-acquired by this method.
     *
     * @param leaf non-null leaf frame, held shared, released if an exception is thrown
     * @return held commitLock
     */
    private CommitLock.Shared prepareStoreUpgrade(CursorFrame leaf, byte[] value)
        throws IOException
    {
        CommitLock commitLock = mTree.mDatabase.commitLock();
        CommitLock.Shared shared = commitLock.tryAcquireShared();

        lockIt: {
            if (shared != null) {
                if (leaf.mNode.tryUpgrade()) {
                    break lockIt;
                }
                leaf.mNode.releaseShared();
            } else {
                leaf.mNode.releaseShared();
                shared = commitLock.acquireShared();
            }

            leaf.acquireExclusive();
        }

        // Dirty the node if a value is provided, or if delete will actually do something.
        if (value != null || leaf.mNodePos >= 0) {
            try {
                // Releases leaf latch if an exception is thrown.
                notSplitDirty(leaf);
            } catch (Throwable e) {
                shared.release();
                throw e;
            }
        }

        return shared;
    }

    /**
     * Must call prepareStore before calling this method, which ensures that the node is
     * dirtied and not split.
     *
     * Caller must have acquired the shared commit lock, which is released by this method if an
     * exception is thrown.
     *
     * @param leaf leaf frame, latched exclusively, which is always released by this method
     */
    private void storeNoRedo(LocalTransaction txn, CursorFrame leaf, byte[] value)
        throws IOException
    {
        try {
            Node node = leaf.mNode;
            int pos = leaf.mNodePos;
            byte[] key = mKey;

            if (pos >= 0) {
                // Update entry...

                try {
                    if (txn != null && txn.lockMode() != LockMode.UNSAFE) {
                        node.txnPreUpdateLeafEntry(txn, mTree, pos);
                    }
                    node.updateLeafValue(mTree, pos, 0, value);
                } catch (Throwable e) {
                    node.releaseExclusive();
                    throw e;
                }

                if (node.shouldLeafMerge()) {
                    // Releases node as a side-effect.
                    mergeLeaf(leaf, node);
                } else {
                    if (node.mSplit != null) {
                        // Releases latch if an exception is thrown.
                        node = mTree.finishSplitCritical(leaf, node);
                    }
                    node.releaseExclusive();
                }
            } else {
                // Insert entry...

                try {
                    if (txn != null && txn.lockMode() != LockMode.UNSAFE) {
                        txn.pushUninsert(mTree.mId, key);
                    }
                    node.insertLeafEntry(leaf, mTree, ~pos, key, value);
                } catch (Throwable e) {
                    node.releaseExclusive();
                    throw e;
                }

                // Releases latch if an exception is thrown.
                node = postInsert(leaf, node, key);

                node.releaseExclusive();
            }
        } catch (Throwable e) {
            // Release the shared lock.
            mTree.mDatabase.commitLock().unlock();

            rethrowIfRecoverable(e);
            if (txn != null) {
                txn.reset(e);
            }
            throw e;
        }
    }

    /**
     * Fixes this and all bound cursors after an insert.
     *
     * @param leaf latched leaf frame; released if an exception is thrown
     * @return replacement node
     */
    Node postInsert(CursorFrame leaf, Node node, byte[] key) throws IOException {
        int pos = leaf.mNodePos;
        int newPos = ~pos;

        leaf.mNodePos = newPos;
        leaf.mNotFoundKey = null;

        // Fix all cursors bound to the node.
        CursorFrame frame = node.mLastCursorFrame;
        do {
            if (frame == leaf) {
                // Don't need to fix self.
                continue;
            }

            int framePos = frame.mNodePos;

            if (framePos == pos) {
                // Other cursor is at same not-found position as this one was. If keys are the
                // same, then other cursor switches to a found state as well. If key is
                // greater, then position needs to be updated.

                byte[] frameKey = frame.mNotFoundKey;
                if (frameKey != null) {
                    int compare = compareUnsigned(frameKey, key);
                    if (compare > 0) {
                        // Position is a complement, so subtract instead of add.
                        frame.mNodePos = framePos - 2;
                    } else if (compare == 0) {
                        frame.mNodePos = newPos;
                        frame.mNotFoundKey = null;
                    }
                }
            } else if (framePos >= newPos) {
                frame.mNodePos = framePos + 2;
            } else if (framePos < pos) {
                // Position is a complement, so subtract instead of add.
                frame.mNodePos = framePos - 2;
            }
        } while ((frame = frame.mPrevCousin) != null);

        if (node.mSplit != null) {
            node = mTree.finishSplitCritical(leaf, node);
        }

        return node;
    }

    /**
     * Non-transactional store of a fragmented value as an undo action. Cursor value is
     * NOT_LOADED as a side-effect.
     */
    final void storeFragmented(byte[] value) throws IOException {
        ViewUtils.positionCheck(mKey);
        if (value == null) {
            throw new IllegalArgumentException("Value is null");
        }

        final CommitLock.Shared shared = prepareStore();
        final CursorFrame leaf = mFrame;
        Node node = leaf.mNode;

        try {
            final int pos = leaf.mNodePos;
            if (pos >= 0) {
                try {
                    node.updateLeafValue(mTree, pos, Node.ENTRY_FRAGMENTED, value);
                } catch (Throwable e) {
                    node.releaseExclusive();
                    throw e;
                }
                if (node.mSplit != null) {
                    // Releases latch if an exception is thrown.
                    node = mTree.finishSplitCritical(leaf, node);
                }
            } else {
                // This case is possible when entry was deleted concurrently without a lock.
                byte[] key = mKey;
                try {
                    node.insertFragmentedLeafEntry(leaf, mTree, ~pos, key, value);
                } catch (Throwable e) {
                    node.releaseExclusive();
                    throw e;
                }
                // Releases latch if an exception is thrown.
                node = postInsert(leaf, node, key);
            }

            mValue = NOT_LOADED;

            node.releaseExclusive();
        } catch (Throwable e) {
            throw handleException(e, false);
        } finally {
            shared.release();
        }
    }

    /**
     * Non-transactional insert of a blank value. Caller must hold shared commit lock and
     * have verified that insert is a valid operation.
     *
     * @param leaf leaf frame, latched exclusively, which is released by this
     * method if an exception is thrown
     * @param node frame node, not split, dirtied
     * @param vlength length of blank value
     * @return replacement node, latched
     */
    final Node insertBlank(CursorFrame leaf, Node node, long vlength) throws IOException {
        byte[] key = mKey;
        try {
            node.insertBlankLeafEntry(leaf, mTree, ~leaf.mNodePos, key, vlength);
        } catch (Throwable e) {
            node.releaseExclusive();
            throw e;
        }
        // Releases latch if an exception is thrown.
        return postInsert(leaf, node, key);
    }

    /**
     * Non-transactionally deletes all entries in the tree. No other cursors or threads can be
     * active in the tree.
     *
     * @return false if stopped because database is closed
     */
    final boolean deleteAll() throws IOException {
        autoload(false);

        final LocalDatabase db = mTree.mDatabase;
        final CommitLock commitLock = db.commitLock();

        final CommitLock.Shared shared = commitLock.acquireShared();

        // Close check is required because this method is called by the trashed tree deletion
        // task. The tree isn't registered as an open tree, and so closing the database doesn't
        // close the tree before deleting the node instances.
        if (db.isClosed()) {
            shared.release();
            return false;
        }

        try {
            firstLeaf();
        } catch (Throwable e) {
            shared.release();
            throw e;
        }

        final boolean result;

        while (true) {
            if (commitLock.hasQueuedThreads()) {
                shared.release();
                commitLock.acquireShared(shared);
            }

            try {
                if (db.isClosed()) {
                    result = false;
                    break;
                }

                mFrame.acquireExclusive();

                // Releases latch if an exception is thrown.
                Node node = notSplitDirty(mFrame);

                if (node.hasKeys()) {
                    try {
                        node.deleteLeafEntry(0);
                    } catch (Throwable e) {
                        node.releaseExclusive();
                        throw e;
                    }

                    if (node.hasKeys()) {
                        node.releaseExclusive();
                        continue;
                    }
                }

                if (!deleteLowestNode(mFrame, node)) {
                    mFrame = null;
                    reset();
                    result = true;
                    break;
                }
            } catch (Throwable e) {
                shared.release();
                throw e;
            }
        }

        shared.release();

        return result;
    }

    /**
     * Non-transactionally deletes the current entry, and then moves to the next one. No other
     * cursors or threads can be active in the tree.
     */
    final void deleteNext() throws IOException {
        final LocalDatabase db = mTree.mDatabase;
        final CommitLock commitLock = db.commitLock();

        final CommitLock.Shared shared = commitLock.acquireShared();

        Node node;

        while (true) {
            try {
                if (db.isClosed()) {
                    throw new ClosedIndexException();
                }

                mFrame.acquireExclusive();

                // Releases latch if an exception is thrown.
                node = notSplitDirty(mFrame);

                if (node.hasKeys()) {
                    try {
                        node.deleteLeafEntry(0);
                    } catch (Throwable e) {
                        node.releaseExclusive();
                        throw e;
                    }

                    if (node.hasKeys()) {
                        break;
                    }
                }

                if (!deleteLowestNode(mFrame, node)) {
                    mFrame = null;
                    reset();
                    shared.release();
                    return;
                }

                mFrame.acquireExclusive();

                // Releases latch if an exception is thrown.
                node = notSplitDirty(mFrame);

                if (node.hasKeys()) {
                    break;
                }

                node.releaseExclusive();
            } catch (Throwable e) {
                shared.release();
                throw e;
            }

            if (commitLock.hasQueuedThreads()) {
                shared.release();
                commitLock.acquireShared(shared);
            }
        }

        shared.release();

        try {
            mKey = node.retrieveKey(0);
            if (!mKeyOnly) {
                mValue = node.retrieveLeafValue(0);
            }
        } finally {
            node.releaseExclusive();
        }
    }

    /**
     * Deletes the lowest latched node and assigns the next node to the frame. All latches are
     * released by this method. No other cursors or threads can be active in the tree.
     *
     * @param frame node frame
     * @param node latched node, with no keys, and dirty
     * @return false if tree is empty
     */
    private boolean deleteLowestNode(final CursorFrame frame, final Node node) throws IOException {
        node.mLastCursorFrame = null;

        LocalDatabase db = mTree.mDatabase;

        if (node == mTree.mRoot) {
            try {
                node.asTrimmedRoot();
            } finally {
                node.releaseExclusive();
            }
            return false;
        }

        db.prepareToDelete(node);

        CursorFrame parentFrame = frame.mParentFrame;
        Node parentNode = parentFrame.acquireExclusive();

        if (parentNode.hasKeys()) {
            parentNode.deleteLowestChildRef();
        } else {
            if (!deleteLowestNode(parentFrame, parentNode)) {
                db.finishDeleteNode(node);
                return false;
            }
            parentNode = parentFrame.acquireExclusive();
        }

        Node next = db.latchChildRetainParentEx(parentNode, 0, true);

        try {
            if (db.markDirty(mTree, next)) {
                parentNode.updateChildRefId(0, next.id());
            }
        } finally {
            parentNode.releaseExclusive();
        }

        frame.mNode = next;
        frame.mNodePos = 0;
        next.mLastCursorFrame = frame;
        next.type((byte) (next.type() | Node.LOW_EXTREMITY));
        next.releaseExclusive();

        db.finishDeleteNode(node);

        return true;
    }
 
    /**
     * Non-transactionally deletes the current entry, and then moves to the previous one. No
     * other cursors or threads can be active in the tree.
     */
    final void deletePrevious() throws IOException {
        final LocalDatabase db = mTree.mDatabase;
        final CommitLock commitLock = db.commitLock();

        final CommitLock.Shared shared = commitLock.acquireShared();

        Node node;

        while (true) {
            try {
                if (db.isClosed()) {
                    throw new ClosedIndexException();
                }

                mFrame.acquireExclusive();

                // Releases latch if an exception is thrown.
                node = notSplitDirty(mFrame);

                if (node.hasKeys()) {
                    try {
                        node.deleteLeafEntry(node.highestLeafPos());
                    } catch (Throwable e) {
                        node.releaseExclusive();
                        throw e;
                    }

                    if (node.hasKeys()) {
                        break;
                    }
                }

                if (!deleteHighestNode(mFrame, node)) {
                    mFrame = null;
                    reset();
                    shared.release();
                    return;
                }

                mFrame.acquireExclusive();

                // Releases latch if an exception is thrown.
                node = notSplitDirty(mFrame);

                if (node.hasKeys()) {
                    break;
                }
            } catch (Throwable e) {
                shared.release();
                throw e;
            }

            if (commitLock.hasQueuedThreads()) {
                shared.release();
                commitLock.acquireShared(shared);
            }
        }

        shared.release();

        try {
            int pos = node.highestLeafPos();
            mKey = node.retrieveKey(pos);
            if (!mKeyOnly) {
                mValue = node.retrieveLeafValue(pos);
            }
        } finally {
            node.releaseExclusive();
        }
    }

    /**
     * Deletes the highest latched node and assigns the previous node to the frame. All latches
     * are released by this method. No other cursors or threads can be active in the tree.
     *
     * @param frame node frame
     * @param node latched node, with no keys, and dirty
     * @return false if tree is empty
     */
    private boolean deleteHighestNode(final CursorFrame frame, final Node node) throws IOException {
        node.mLastCursorFrame = null;

        LocalDatabase db = mTree.mDatabase;

        if (node == mTree.mRoot) {
            try {
                node.asTrimmedRoot();
            } finally {
                node.releaseExclusive();
            }
            return false;
        }

        db.prepareToDelete(node);

        CursorFrame parentFrame = frame.mParentFrame;
        Node parentNode = parentFrame.acquireExclusive();

        if (parentNode.hasKeys()) {
            parentNode.deleteRightChildRef(parentNode.highestInternalPos());
        } else {
            if (!deleteHighestNode(parentFrame, parentNode)) {
                db.finishDeleteNode(node);
                return false;
            }
            parentNode = parentFrame.acquireExclusive();
        }

        int pos = parentNode.highestInternalPos();
        Node previous = mTree.mDatabase.latchChildRetainParentEx(parentNode, pos, true);

        try {
            if (db.markDirty(mTree, previous)) {
                parentNode.updateChildRefId(pos, previous.id());
            }
        } finally {
            parentNode.releaseExclusive();
        }

        frame.mNode = previous;
        frame.mNodePos = previous.highestPos();
        previous.mLastCursorFrame = frame;
        previous.type((byte) (previous.type() | Node.HIGH_EXTREMITY));
        previous.releaseExclusive();

        db.finishDeleteNode(node);

        return true;
    }

    /**
     * Find and return a random position in the node. The node should be latched.
     * @param rnd random number generator
     * @param node non-null latched node
     * @param lowKey start of range, inclusive. pass null for open range
     * @param highKey end of range, exclusive. pass null for open range
     * @return {@literal <0 if node is empty or out of bounds}
     */
    private int randomPosition(ThreadLocalRandom rnd, Node node, byte[] lowKey, byte[] highKey)
        throws IOException
    {
       int pos;
       if (highKey == null) {
           pos = node.highestPos() + 2;
       } else {
           pos = node.binarySearch(highKey);
           if (pos < 0) {    // highKey is not found
               pos = ~pos;
           }
           if (!node.isLeaf()) {
               pos += 2;
           }
       }

       if (lowKey == null) {
           if (pos > 0) {
               // search vector has 2 byte long entries
               pos = (pos == 2) ? 0 : (rnd.nextInt(pos >> 1) << 1);
               return pos;
           }
       } else {
           int lowPos = node.binarySearch(lowKey);
           if (!node.isLeaf()) {
               lowPos = Node.internalPos(lowPos);
           } else if (lowPos < 0) {  // lowKey not found
               lowPos = ~lowPos;
           }
           int range = pos - lowPos;
           if (range > 0) {
               // search vector has 2 byte long entries
               pos = (range == 2) ? lowPos : lowPos + (rnd.nextInt(range >> 1) << 1);
               return pos;
           }
       }
       // node is empty or out of bounds
       return -1;
    }

    /**
     * Check if there are any keys in the range. Cursor is reset as a side effect.
     * @param lowKey start of range, inclusive
     * @param highKey end of range, exclusive
     * @return
     */
    private boolean isRangeEmpty(byte[] lowKey, byte[] highKey) throws IOException {
        boolean oldKeyOnly = mKeyOnly;
        LocalTransaction oldTxn = mTxn;
        try {
            mTxn = LocalTransaction.BOGUS;
            mKeyOnly = true;
            if (lowKey == null) {
                first();
            } else {
                findGe(lowKey);
            }
            return mKey == null || (highKey != null && compareUnsigned(mKey, highKey) >= 0);
        } finally {
            reset();
            mKeyOnly = oldKeyOnly;
            mTxn = oldTxn;
        }
    }

    private IOException handleException(Throwable e, boolean reset) throws IOException {
        // Checks if cause of exception is likely due to the database being closed. If so, the
        // given exception is discarded and a new DatabaseException is thrown.
        mTree.mDatabase.checkClosed(e);

        if (mFrame == null && e instanceof IllegalStateException ise) {
            // Exception is caused by cursor state; store is safe.
            if (reset) {
                reset();
            }
            throw ise;
        }

        if (e instanceof DatabaseException de && de.isRecoverable()) {
            if (reset) {
                reset();
            }
            throw de;
        }

        // Any unexpected exception can corrupt the internal store state. Closing down
        // protects the persisted state.

        try {
            throw closeOnFailure(mTree.mDatabase, e);
        } finally {
            reset();
        }
    }

    @Override
    public final long valueLength() throws IOException {
        CursorFrame frame;
        try {
            frame = frameSharedNotSplit();
        } catch (IllegalStateException e) {
            valueCheckOpen();
            throw e;
        }

        long result = BTreeValue.action(null, this, frame, BTreeValue.OP_LENGTH, 0, null, 0, 0);
        frame.mNode.releaseShared();
        return result;
    }

    @Override
    public final void valueLength(long length) throws IOException {
        try {
            if (length <= 0) {
                store(length == 0 ? EMPTY_BYTES : null);
            } else {
                doValueModify(BTreeValue.OP_SET_LENGTH, length, EMPTY_BYTES, 0, 0);
            }
        } catch (IllegalStateException e) {
            valueCheckOpen();
            throw e;
        }
    }

    @Override
    protected final int doValueRead(long pos, byte[] buf, int off, int len) throws IOException {
        CursorFrame frame;
        try {
            frame = frameSharedNotSplit();
        } catch (IllegalStateException e) {
            valueCheckOpen();
            throw e;
        }

        long result = BTreeValue.action(null, this, frame, BTreeValue.OP_READ, pos, buf, off, len);
        frame.mNode.releaseShared();
        return (int) result;
    }

    @Override
    protected final void doValueWrite(long pos, byte[] buf, int off, int len) throws IOException {
        try {
            doValueModify(BTreeValue.OP_WRITE, pos, buf, off, len);
        } catch (IllegalStateException e) {
            valueCheckOpen();
            throw e;
        }
    }

    @Override
    protected final void doValueClear(long pos, long length) throws IOException {
        try {
            doValueModify(BTreeValue.OP_CLEAR, pos, EMPTY_BYTES, 0, length);
        } catch (IllegalStateException e) {
            valueCheckOpen();
            throw e;
        }
    }

    /**
     * @param op OP_SET_LENGTH, OP_WRITE, or OP_CLEAR
     * @param buf pass EMPTY_BYTES for OP_SET_LENGTH or OP_CLEAR
     */
    protected final void doValueModify(int op, long pos, byte[] buf, int off, long len)
        throws IOException
    {
        if (mTxn == null) {
            doValueModifyAutoCommit(op, pos, buf, off, len);
        } else {
            doTxnValueModify(op, pos, buf, off, len);
        }
    }

    /**
     * Can only be called when cursor is linked to a transaction.
     */
    private void doTxnValueModify(int op, long pos, byte[] buf, int off, long len)
        throws IOException
    {
        byte[] key = mKey;
        ViewUtils.positionCheck(key);

        LocalTransaction undoTxn = null;

        if (mTxn.lockMode() != LockMode.UNSAFE) {
            mTxn.doLockExclusive(mTree.mId, key, keyHash());
            undoTxn = mTxn;
        }

        final CommitLock.Shared shared = prepareStore();
        final CursorFrame leaf = mFrame;

        try {
            BTreeValue.action(undoTxn, this, leaf, op, pos, buf, off, len);
            Node node = leaf.mNode;

            if (op == BTreeValue.OP_SET_LENGTH && node.shouldLeafMerge()) {
                // Method always releases the node latch, even if an exception is thrown.
                mergeLeaf(leaf, node);
            } else {
                node.releaseExclusive();
            }

            if (allowRedo() && mTxn.durabilityMode() != DurabilityMode.NO_REDO) {
                mTxn.redoCursorValueModify(this, op, pos, buf, off, len);
            }
        } finally {
            shared.release();
        }
    }

    private void doValueModifyAutoCommit(int op, long pos, byte[] buf, int off, long len)
        throws IOException
    {
        LocalDatabase db = mTree.mDatabase;

        final LocalTransaction txn;
        if (!allowRedo()) {
            txn = db.threadLocalTransaction(DurabilityMode.NO_REDO);
        } else {
            DurabilityMode durabilityMode = db.mDurabilityMode;
            if (requireTransaction()) {
                txn = db.threadLocalTransaction(alwaysRedo(durabilityMode));
            } else {
                byte[] key = mKey;
                ViewUtils.positionCheck(key);
                txn = db.threadLocalTransaction(durabilityMode);
                txn.mLockMode = LockMode.UNSAFE; // no undo
                // Manually lock the key.
                txn.doLockExclusive(mTree.mId, key, keyHash());
            }
        }

        try {
            mTxn = txn;
            doTxnValueModify(op, pos, buf, off, len);
            txn.commit();
        } catch (Throwable e) {
            db.removeThreadLocalTransaction();
            txn.reset();
            throw e;
        } finally {
            mTxn = null;
        }
    }

    @Override
    protected final int valueStreamBufferSize(int bufferSize) {
        if (bufferSize <= 1) {
            if (bufferSize < 0) {
                bufferSize = mTree.mDatabase.mPageSize;
            } else {
                bufferSize = 1;
            }
        }
        return bufferSize;
    }

    @Override
    protected final void valueCheckOpen() {
        if (mKey == null) {
            throw new IllegalStateException("Accessor closed");
        }
    }

    @Override
    public final BTreeCursor copy() {
        BTreeCursor copy = copyNoValue();
        copy.mKeyOnly = mKeyOnly;
        copy.mValue = ViewUtils.copyValue(mValue);
        return copy;
    }

    private BTreeCursor copyNoValue() {
        var copy = new BTreeCursor(mTree, mTxn);
        CursorFrame frame = mFrame;
        if (frame != null) {
            var frameCopy = new CursorFrame();
            frame.copyInto(frameCopy);
            copy.mFrame = frameCopy;
        }
        copy.mKey = mKey;
        copy.mKeyHash = mKeyHash;
        return copy;
    }

    /**
     * Return root node latched shared.
     */
    private Node latchRootNode() {
        Node root = mTree.mRoot;
        root.acquireShared();
        return root;
    }

    @Override
    public final void reset() {
        mKey = null;
        mKeyHash = 0;
        mValue = null;

        CursorFrame frame = mFrame;
        mFrame = null;

        if (frame != null) {
            CursorFrame.popAll(frame);
        }

        unregister();
    }

    @Override
    public final void close() {
        reset();
    }

    /**
     * Reset with leaf already latched shared.
     */
    private void resetLatched(Node node) {
        node.releaseShared();
        reset();
    }

    /**
     * Called when next/previous reached the end. Must be called with the node latch held
     * shared, which is always released. The cursor is also always reset.
     */
    private void reachedEnd(Node node) throws ClosedIndexException {
        boolean closed = isClosedOrDeleted(node.mPage);
        resetLatched(node);
        if (closed) {
            throw newClosedIndexException(node.mPage);
        }
    }

    /**
     * Called if an exception is thrown while frames are being constructed.
     * Given frame does not need to be bound, but it must not be latched.
     */
    private RuntimeException cleanup(Throwable e, CursorFrame frame) {
        mFrame = frame;
        reset();
        return rethrow(e);
    }

    /**
     * Non-transactionally moves the first entry from the source into the tree, as the highest
     * overall. No other cursors can be active in the target subtree, and no check is performed
     * to verify that the entry is the highest and unique. The garbage field of the source node
     * is untouched.
     *
     * Caller must hold shared commit lock and exclusive node latch.
     */
    final void appendTransfer(Node source) throws IOException {
        try {
            final CursorFrame tleaf = mFrame;
            tleaf.acquireExclusive();
            Node tnode = notSplitDirty(tleaf);

            try {
                final var spage = source.mPage;
                final int sloc = p_ushortGetLE(spage, source.searchVecStart());
                final int encodedLen = Node.leafEntryLengthAtLoc(spage, sloc);

                final int tpos = tleaf.mNodePos;
                // Pass a null frame to disable rebalancing. It's not useful here, and it
                // interferes with the neighboring subtrees.
                final int tloc = tnode.createLeafEntry(null, mTree, tpos, encodedLen);

                if (tloc < 0) {
                    tnode.splitLeafAscendingAndCopyEntry(mTree, source, 0, encodedLen);
                    tnode = mTree.finishSplitCritical(tleaf, tnode);
                } else {
                    p_copy(spage, sloc, tnode.mPage, tloc, encodedLen);
                }

                // Prepare for next append.
                tleaf.mNodePos += 2;
            } finally {
                tnode.releaseExclusive();
            }

            int searchVecStart = source.searchVecStart();
            int searchVecEnd = source.searchVecEnd();

            if (searchVecStart == searchVecEnd) {
                // After removing the last entry, adjust the end pointer instead of the start
                // pointer. If the start pointer was incremented, it could go out of bounds.
                source.searchVecEnd(searchVecEnd - 2);
            } else {
                source.searchVecStart(searchVecStart + 2);
            }
        } catch (Throwable e) {
            throw handleException(e, false);
        }
    }

    /**
     * Non-transactionally moves the current entry from the source into the tree, as the highest
     * overall. No other cursors can be active in the target subtree, and no check is performed
     * to verify that the entry is the highest and unique. This source is positioned at the
     * next entry as a side effect, and nodes are deleted only when empty.
     */
    final void appendTransfer(BTreeCursor source) throws IOException {
        final CommitLock.Shared shared = mTree.mDatabase.commitLock().acquireShared();
        CursorFrame sleaf;
        try {
            final CursorFrame tleaf = mFrame;
            tleaf.acquireExclusive();
            Node tnode = notSplitDirty(tleaf);

            sleaf = source.mFrame;
            Node snode = sleaf.acquireExclusive();

            try {
                snode = source.notSplitDirty(sleaf);
                final int spos = sleaf.mNodePos;

                try {
                    final var spage = snode.mPage;
                    final int sloc = p_ushortGetLE(spage, snode.searchVecStart() + spos);
                    final int encodedLen = Node.leafEntryLengthAtLoc(spage, sloc);

                    final int tpos = tleaf.mNodePos;
                    // Pass a null frame to disable rebalancing. It's not useful here, and it
                    // interferes with the neighboring subtrees.
                    final int tloc = tnode.createLeafEntry(null, mTree, tpos, encodedLen);

                    if (tloc < 0) {
                        tnode.splitLeafAscendingAndCopyEntry(mTree, snode, spos, encodedLen);
                        tnode = mTree.finishSplitCritical(tleaf, tnode);
                    } else {
                        p_copy(spage, sloc, tnode.mPage, tloc, encodedLen);
                    }

                    // Prepare for next append.
                    tleaf.mNodePos += 2;

                    snode.finishDeleteLeafEntry(spos, encodedLen);
                    snode.postDelete(spos, null);
                } catch (Throwable e) {
                    snode.releaseExclusive();
                    throw e;
                }
            } finally {
                tnode.releaseExclusive();
            }

            if (snode.hasKeys()) {
                snode.downgrade();
            } else {
                source.mergeLeaf(sleaf, snode);
                sleaf = source.frameSharedNotSplit();
            }
        } catch (Throwable e) {
            throw handleException(e, false);
        } finally {
            shared.release();
        }

        source.next(LocalTransaction.BOGUS, sleaf);
    }

    /**
     * @param txn non-null
     */
    private void doUnregister(LocalTransaction txn, long cursorId) {
        cursorId &= ~(1L << 63);

        try {
            LocalDatabase db = mTree.mDatabase;

            doRedo: {
                TransactionContext context;
                RedoWriter redo;
                if (txn == null) {
                    context = db.anyTransactionContext();
                    redo = db.txnRedoWriter();
                } else {
                    context = txn.mContext;
                    redo = txn.mRedo;
                    if (redo == null) {
                        // RedoWriter can be null if the transaction is used for redo recovery
                        // or for replica-side operation processing.
                        break doRedo;
                    }
                }

                context.redoCursorUnregister(redo, cursorId);
            }

            db.unregisterCursor(cursorId);
            mCursorId = 0;
        } catch (UnmodifiableReplicaException e) {
            // Ignore.
        } catch (IOException e) {
            // Original definition of link and reset methods doesn't declare throwing
            // an IOException, so throw it as unchecked for compatibility.
            throw rethrow(e);
        }
    }

    final int height() {
        int height = 0;
        CursorFrame frame = mFrame;
        while (frame != null) {
            height++;
            frame = frame.mParentFrame;
        }
        return height;
    }

    /**
     * Used by file compaction mode. Compacts from the current node to the last, unless stopped
     * by observer or aborted. Caller must issue a checkpoint after entering compaction mode
     * and before calling this method. This ensures completion of any splits in progress before
     * compaction began. Any new splits will be created during compaction and so they will
     * allocate pages outside of the compaction zone.
     *
     * @param highestNodeId defines the highest node before the compaction zone; anything
     * higher is in the compaction zone
     * @return false if compaction should stop
     */
    final boolean compact(long highestNodeId, CompactionObserver observer) throws IOException {
        int height = height();

        // Reference to frame nodes, to detect when cursor has moved past a node. Level 0
        // represents the leaf node. Detection may also be triggered by concurrent
        // modifications to the tree, but this is not harmful.
        var frameNodes = new Node[height];
        var nodePositions = new int[height];

        CursorFrame frame = mFrame;

        outer: while (true) {
            for (int level = 0; level < height; level++) {
                Node node = frame.acquireShared();

                if (frameNodes[level] == node &&
                    (level == 0 || nodePositions[level] == frame.mNodePos))
                {
                    // No point in checking upwards if this level is unchanged.
                    node.releaseShared();
                    break;
                }

                frameNodes[level] = node;
                nodePositions[level] = frame.mNodePos;
                long id = compactFrame(highestNodeId, frame, node);
                if (id > highestNodeId) {
                    // Abort compaction.
                    return false;
                }

                try {
                    if (!observer.indexNodeVisited(id)) {
                        return false;
                    }
                } catch (Throwable e) {
                    uncaught(e);
                    return false;
                }

                if (level != 0 && !compactInternalNode(highestNodeId, frame)) {
                    return false;
                }

                frame = frame.mParentFrame;
            }

            // Search leaf for fragmented values.

            frame = frameSharedNotSplit();
            Node node = frame.mNode;

            // Quick check avoids excessive node re-latching.
            quick: {
                final int end = node.highestLeafPos();
                int pos = frame.mNodePos;
                if (pos < 0) {
                    pos = ~pos;
                }
                for (; pos <= end; pos += 2) {
                    if (node.isFragmentedKey(pos) || node.isFragmentedLeafValue(pos)) {
                        // Found one, so abort the quick check.
                        break quick;
                    }
                }
                // No fragmented values found.
                node.releaseShared();
                skipToNextLeaf();
                if ((frame = mFrame) == null) {
                    // No more entries to examine.
                    return true;
                }
                continue outer;
            }

            while (true) {
                int nodePos = frame.mNodePos;
                if (nodePos >= 0) {
                    if (node.isFragmentedKey(nodePos)) {
                        node = compactFragmentedEntry(frame, node, true, highestNodeId);
                        if (node == null) {
                            return false;
                        }
                    }
                    if (node.isFragmentedLeafValue(nodePos)) {
                        node = compactFragmentedEntry(frame, node, false, highestNodeId);
                        if (node == null) {
                            return false;
                        }
                    }
                }

                node.releaseShared();

                nextLeaf();

                if (mFrame == null) {
                    // No more entries to examine.
                    return true;
                }

                frame = frameSharedNotSplit();
                Node next = frame.mNode;

                if (next != node) {
                    next.releaseShared();
                    break;
                }
            }
        }
    }

    /**
     * Moves the frame's node out of the compaction zone if necessary.
     *
     * @param frame frame with shared latch held; always released as a side-effect
     * @return new node id; caller must check if it is outside compaction zone and abort
     */
    private long compactFrame(long highestNodeId, CursorFrame frame, Node node)
        throws IOException
    {
        long id = node.id();
        node.releaseShared();

        if (id > highestNodeId) {
            LocalDatabase db = mTree.mDatabase;
            CommitLock.Shared shared = db.commitLock().acquireShared();
            try {
                node = frame.acquireExclusive();
                id = node.id();
                if (id > highestNodeId) {
                    // Marking as dirty forces an allocation, which should be outside the
                    // compaction zone.
                    node = notSplitDirty(frame);
                    id = node.id();
                }
                node.releaseExclusive();
            } finally {
                shared.release();
            }
        }

        return id;
    }

    /**
     * Examines the current internal node position, and compacts the key if necessary.
     *
     * @return false if compaction should stop
     */
    private boolean compactInternalNode(long highestNodeId, CursorFrame frame) throws IOException {
        Node node = frame.acquireShared();
        if (node.mSplit != null) {
            node = mTree.finishSplitShared(frame, node);
        }
        boolean result;
        if (!node.isInternal() || frame.mNodePos >= node.highestInternalPos()) {
            result = true;
        } else {
            result = compactFragmentedEntry(frame, node, true, highestNodeId) != null;
        }
        node.releaseShared();
        return result;
    }

    /**
     * Scans through a fragmented key or value and moves content out of the compaction zone.
     * The frame's node position must not be negative.
     *
     * @param frame leaf held shared, not split, released if an exception is thrown
     * @param isKey true to examine the key instead of the value
     * @return null if compaction should stop (node latch will have been released too)
     */
    private Node compactFragmentedEntry(final CursorFrame frame, Node node, boolean isKey,
                                        long highestNodeId)
        throws IOException
    {
        int pLen = pageSize(node.mPage);
        long pos = 0;
        while (true) {
            int result;
            try {
                result = BTreeValue.compactCheck(frame, isKey, pos, highestNodeId);
            } catch (Throwable e) {
                node.releaseShared();
                throw e;
            }

            if (result < -1) {
                return node;
            }

            if (result > -1) {
                if (result > 0) {
                    // Skip over inline content.
                    pos = result;
                    continue;
                }

                // Can pass null and still force the node to be dirtied because
                // the node position isn't negative.
                CommitLock.Shared shared = prepareStoreUpgrade(frame, null);

                try {
                    BTreeValue.touch(frame, isKey, pos);
                } finally {
                    shared.release();
                }

                node = frame.mNode;
                node.downgrade();

                if (node.id() > highestNodeId) {
                    // Abort compaction.
                    node.releaseShared();
                    return null;
                }
            }

            pos += pLen;
        }
    }

    /**
     * Test method which confirms that the given cursor is positioned exactly the same as this
     * one.
     */
    public final boolean equalPositions(BTreeCursor other) {
        if (this == other) {
            return true;
        }

        CursorFrame thisFrame = mFrame;
        CursorFrame otherFrame = other.mFrame;
        while (true) {
            if (thisFrame == null) {
                return otherFrame == null;
            } else if (otherFrame == null) {
                return false;
            }
            if (thisFrame.mNode != otherFrame.mNode) {
                return false;
            }
            if (thisFrame.mNodePos != otherFrame.mNodePos) {
                return false;
            }
            thisFrame = thisFrame.mParentFrame;
            otherFrame = otherFrame.mParentFrame;
        }
    }

    /**
     * Test method which confirms that cursor frame nodes are at an extremity. Method is not
     * tolerant of split nodes.
     *
     * @param extremity LOW_EXTREMITY or HIGH_EXTREMITY
     */
    public final boolean verifyExtremities(byte extremity) throws IOException {
        Node node = latchRootNode();
        try {
            while (true) {
                if ((node.type() & extremity) == 0) {
                    return false;
                }
                if (node.isLeaf()) {
                    return true;
                }
                int pos = 0;
                if (extremity == Node.HIGH_EXTREMITY) {
                    pos = node.highestInternalPos();
                }
                node = mTree.mDatabase.latchToChild(node, pos);
            }
        } finally {
            node.releaseShared();
        }
    }

    /**
     * Verifies from the current node to the last, unless stopped by observer.
     *
     * @return false if should stop
     */
    final boolean verify(final int height, VerificationObserver observer) throws IOException {
        if (height > 0) {
            final var stack = new Node[height];
            while (key() != null) {
                if (!verifyFrames(height, stack, mFrame, observer)) {
                    return false;
                }
                skipToNextLeaf();
            }
        }
        return true;
    }

    private boolean verifyFrames(int level, Node[] stack, CursorFrame frame,
                                 VerificationObserver observer)
        throws IOException
    {
        CursorFrame parentFrame = frame.mParentFrame;
        Node childNode;

        if (parentFrame == null) {
            childNode = frame.acquireShared();
        } else {
            Node parentNode = parentFrame.mNode;
            int parentLevel = level - 1;
            if (parentLevel > 0 && stack[parentLevel] != parentNode) {
                parentNode = parentFrame.acquireShared();
                parentNode.releaseShared();
                if (stack[parentLevel] != parentNode) {
                    stack[parentLevel] = parentNode;
                    if (!verifyFrames(parentLevel, stack, parentFrame, observer)) {
                        return false;
                    }
                }
            }

            parentNode = parentFrame.acquireShared();
            try {
                childNode = frame.acquireShared();

                boolean result;
                try {
                    result = verifyParentChildFrames
                        (level, parentFrame, parentNode, frame, childNode, observer);
                } catch (Throwable e) {
                    childNode.releaseShared();
                    throw e;
                }

                if (!result) {
                    childNode.releaseShared();
                    return false;
                }
            } finally {
                parentNode.releaseShared();
            }
        }

        return childNode.verifyTreeNode(level, observer);
    }

    @SuppressWarnings("fallthrough")
    private boolean verifyParentChildFrames(int level,
                                            CursorFrame parentFrame, Node parentNode,
                                            CursorFrame childFrame, Node childNode,
                                            VerificationObserver observer)
        throws IOException
    {
        final long childId = childNode.id();

        // Verify child node keys are lower/higher than parent node. Nodes can be empty before
        // they're deleted. Also, skip nodes which are splitting.

        if (childNode.hasKeys() && parentNode.hasKeys()
            && childNode.mSplit == null && parentNode.mSplit == null)
        {
            int parentPos = parentFrame.mNodePos;

            int childPos;
            boolean left;
            if (parentPos >= parentNode.highestInternalPos()) {
                // Verify lowest child key is greater than or equal to parent key.
                parentPos = parentNode.highestKeyPos();
                childPos = 0;
                left = false;
            } else {
                // Verify highest child key is lower than parent key.
                childPos = childNode.highestKeyPos();
                left = true;
            }

            byte[] parentKey = parentNode.retrieveKey(parentPos);
            byte[] childKey = childNode.retrieveKey(childPos);

            int compare = compareUnsigned(childKey, parentKey);

            if (left) {
                if (compare >= 0) {
                    observer.failed = true;
                    if (!observer.indexNodeFailed
                        (childId, level, "Child keys are not less than parent key: " + parentNode))
                    {
                        return false;
                    }
                }
            } else if (childNode.isInternal()) {
                if (compare <= 0) {
                    observer.failed = true;
                    if (!observer.indexNodeFailed
                        (childId, level,
                         "Internal child keys are not greater than parent key: " + parentNode))
                    {
                        return false;
                    }
                }
            } else if (compare < 0) {
                observer.failed = true;
                if (!observer.indexNodeFailed
                    (childId, level,
                     "Child keys are not greater than or equal to parent key: " + parentNode))
                {
                    return false;
                }
            }

            // Verify extremities.

            if ((childNode.type() & Node.LOW_EXTREMITY) != 0
                && (parentNode.type() & Node.LOW_EXTREMITY) == 0)
            {
                observer.failed = true;
                if (!observer.indexNodeFailed
                    (childId, level, "Child is low extremity but parent is not: " + parentNode))
                {
                    return false;
                }
            }

            if ((childNode.type() & Node.HIGH_EXTREMITY) != 0
                && (parentNode.type() & Node.HIGH_EXTREMITY) == 0)
            {
                observer.failed = true;
                if (!observer.indexNodeFailed
                    (childId, level, "Child is high extremity but parent is not: " + parentNode))
                {
                    return false;
                }
            }
        }

        // Verify node level types.

        switch (parentNode.type()) {
        case Node.TYPE_TN_IN:
            if (childNode.isLeaf() && parentNode.id() > 1) { // stubs are never bins
                observer.failed = true;
                if (!observer.indexNodeFailed
                    (childId, level,
                     "Child is a leaf, but parent is a regular internal node: " + parentNode))
                {
                    return false;
                }
            }
            break;
        case Node.TYPE_TN_BIN:
            if (!childNode.isLeaf()) {
                observer.failed = true;
                if (!observer.indexNodeFailed
                    (childId, level,
                     "Child is not a leaf, but parent is a bottom internal node: " + parentNode))
                {
                    return false;
                }
            }
            break;
        default:
            if (!parentNode.isLeaf()) {
                break;
            }
            // Fallthrough...
        case Node.TYPE_TN_LEAF:
            observer.failed = true;
            if (!observer.indexNodeFailed
                (childId, level, "Child parent is a leaf node: " + parentNode))
            {
                return false;
            }
            break;
        }

        return true;
    }

    /**
     * Checks that mFrame is defined and returns it.
     *
     * @throws UnpositionedCursorException if unpositioned
     */
    private CursorFrame frame() {
        CursorFrame frame = mFrame;
        ViewUtils.positionCheck(frame);
        return frame;
    }

    /**
     * Latches and returns mFrame, which might be split.
     *
     * @throws UnpositionedCursorException if unpositioned
     */
    protected final CursorFrame frameExclusive() {
        CursorFrame frame = frame();
        frame.acquireExclusive();
        return frame;
    }

    /**
     * Latches and returns mFrame, not split.
     *
     * @throws UnpositionedCursorException if unpositioned
     */
    final CursorFrame frameSharedNotSplit() throws IOException {
        CursorFrame frame = frame();
        Node node = frame.acquireShared();
        if (node.mSplit != null) {
            mTree.finishSplitShared(frame, node);
        }
        return frame;
    }

    /**
     * Called with exclusive frame latch held, which is retained. Leaf frame is dirtied, any
     * split is finished, and the same applies to all parent nodes. Caller must hold shared
     * commit lock, to prevent deadlock. Node latch is released if an exception is thrown.
     *
     * @return replacement node, still latched
     */
    final Node notSplitDirty(final CursorFrame frame) throws IOException {
        Node node = frame.mNode;

        if (node.mSplit != null) {
            // Already dirty, but finish the split.
            return mTree.finishSplit(frame, node);
        }

        LocalDatabase db = mTree.mDatabase;
        if (!db.shouldMarkDirty(node)) {
            return node;
        }

        CursorFrame parentFrame = frame.mParentFrame;
        if (parentFrame == null) {
            try {
                db.doMarkDirty(mTree, node);
                return node;
            } catch (Throwable e) {
                node.releaseExclusive();
                throw e;
            }
        }

        // Make sure the parent is not split and dirty too.
        Node parentNode = parentFrame.tryAcquireExclusive();

        dirtyParent: {
            if (parentNode != null) {
                // Parent latch was acquired without releasing the current node latch.
                if (parentNode.mSplit == null && !db.shouldMarkDirty(parentNode)) {
                    // Parent is already dirty.
                    break dirtyParent;
                }
                node.releaseExclusive();
            } else {
                // Release and acquire parent without risk of deadlock.
                node.releaseExclusive();
                parentFrame.acquireExclusive();
            }

            // Parent must be dirtied.
            parentNode = notSplitDirty(parentFrame);

            // Re-acquire child latch which was released.
            node = frame.acquireExclusive();

            // Must repeat some of the same steps as above since the latch was released.

            if (node.mSplit != null) {
                // Already dirty, but finish the split.
                parentNode.releaseExclusive();
                return mTree.finishSplit(frame, node);
            }

            if (!db.shouldMarkDirty(node)) {
                parentNode.releaseExclusive();
                return node;
            }
        }

        // Now that parent is ready to be updated, can safely dirty the child node and update
        // the reference to it.

        try {
            db.doMarkDirty(mTree, node);
            parentNode.updateChildRefId(parentFrame.mNodePos, node.id());
            return node;
        } catch (Throwable e) {
            node.releaseExclusive();
            throw e;
        } finally {
            parentNode.releaseExclusive();
        }
    }

    /**
     * Called with latch held for an unsplit node, which is upgraded to an exclusive latch.
     * Leaf frame is dirtied, and the same applies to all parent nodes. Caller must hold shared
     * commit lock, to prevent deadlock. Node latch is released if an exception is thrown.
     *
     * @param exclusive true if upgrade was already performed
     * @return true if latch was never released for the upgrade
     */
    final boolean notSplitDirtyUpgrade(final CursorFrame frame, boolean exclusive)
        throws IOException
    {
        final Node node = frame.mNode;

        if (!exclusive && !node.tryUpgrade()) {
            node.releaseShared();
        } else {
            LocalDatabase db = mTree.mDatabase;
            if (!db.shouldMarkDirty(node)) {
                return true;
            }

            CursorFrame parentFrame = frame.mParentFrame;
            if (parentFrame == null) {
                try {
                    db.doMarkDirty(mTree, node);
                    return true;
                } catch (Throwable e) {
                    node.releaseExclusive();
                    throw e;
                }
            }

            // Make sure the parent is not split and dirty too.
            Node parentNode = parentFrame.tryAcquireExclusive();

            if (parentNode != null) {
                // Parent latch was acquired without releasing the current node latch.

                if (parentNode.mSplit == null && !db.shouldMarkDirty(parentNode)) {
                    // Parent is ready to be updated.
                    try {
                        db.doMarkDirty(mTree, node);
                        parentNode.updateChildRefId(parentFrame.mNodePos, node.id());
                        return true;
                    } catch (Throwable e) {
                        node.releaseExclusive();
                        throw e;
                    } finally {
                        parentNode.releaseExclusive();
                    }
                }

                node.releaseExclusive();
            } else {
                node.releaseExclusive();
                parentFrame.acquireExclusive();
            }

            // Parent must be dirtied.
            notSplitDirty(parentFrame).releaseExclusive();
        }

        // Since node latch was released, start over and check everything again properly.
        frame.acquireExclusive();
        notSplitDirty(frame);
        return false;
    }

    /**
     * Variant of notSplitDirty which fails if it would need to re-acquire any latches. Should
     * be used when caller is maintaining a child node latch. If this parent node was
     * re-acquired with the child latch held, that's the wrong order and cause a deadlock.
     *
     * Note: Latch isn't released, even if an exception is thrown.
     *
     * @return false if latches would need to be re-acquired
     */
    final boolean tryNotSplitDirty(final CursorFrame frame) throws IOException {
        Node node = frame.mNode;

        if (node.mSplit != null) {
            // Node is dirty, but finishing the split causes latches to be re-acquired.
            return false;
        }

        LocalDatabase db = mTree.mDatabase;
        if (!db.shouldMarkDirty(node)) {
            return true;
        }

        CursorFrame parentFrame = frame.mParentFrame;
        if (parentFrame == null) {
            db.doMarkDirty(mTree, node);
            return true;
        }

        // Make sure the parent is not split and dirty too.
        Node parentNode = parentFrame.tryAcquireExclusive();

        if (parentNode == null) {
            // The child latch would need to be released to acquire the parent latch.
            return false;
        }

        try {
            if (!tryNotSplitDirty(parentFrame)) {
                return false;
            }
            // Now that parent is ready to be updated, can safely dirty the child node and
            // update the reference to it.
            db.doMarkDirty(mTree, node);
            parentNode.updateChildRefId(parentFrame.mNodePos, node.id());
            return true;
        } finally {
            parentNode.releaseExclusive();
        }
    }

    /**
     * Caller must hold exclusive latch, which is released by this method.
     */
    void mergeLeaf(final CursorFrame leaf, Node node) throws IOException {
        final CursorFrame parentFrame = leaf.mParentFrame;

        if (parentFrame == null) {
            // Root node cannot merge into anything.
            node.releaseExclusive();
            return;
        }

        // Try-latch up the tree to avoid deadlocks.
        Node parentNode = parentFrame.tryAcquireExclusive();
        if (parentNode == null) {
            node.releaseExclusive();
            node = null;
            parentNode = parentFrame.acquireExclusive();
        }

        Node leftNode;
        doMerge: {
            Node rightNode;
            int leftPos;
            select: while (true) {
                latchNode: {
                    if (parentNode.mSplit != null) {
                        if (node != null) {
                            node.releaseExclusive();
                        }
                        parentNode = mTree.finishSplit(parentFrame, parentNode);
                    } else if (node != null) {
                        // Should already be latched.
                        break latchNode;
                    }

                    node = leaf.acquireExclusive();
                }

                // Double check that node should still merge.
                int nodeAvail = node.availableLeafBytes();
                if (!node.shouldMerge(nodeAvail)) {
                    node.releaseExclusive();
                    parentNode.releaseExclusive();
                    return;
                }

                // Attempt to latch the left and right siblings, but without waiting in order
                // to avoid deadlocks.

                int leftAvail;

                int pos = parentFrame.mNodePos;
                if (pos == 0) {
                    leftNode = null;
                    leftAvail = -1;
                } else {
                    try {
                        leftNode = mTree.mDatabase
                            .latchChildRetainParentEx(parentNode, pos - 2, false);
                    } catch (Throwable e) {
                        node.releaseExclusive();
                        throw e;
                    }

                    if (leftNode == null) {
                        leftAvail = -1;
                    } else {
                        if (leftNode.mSplit != null) {
                            // Finish sibling split.
                            node.releaseExclusive();
                            node = null;
                            try {
                                parentNode.insertSplitChildRef
                                    (parentFrame, mTree, pos - 2, leftNode);
                                continue;
                            } catch (Throwable e) {
                                return;
                            }
                        }

                        if (!node.hasKeys()) {
                            // The node to merge is empty, and the left sibling has been
                            // latched. No need to examine the right sibling, since the merge
                            // into the left sibling will absolutely work.
                            leftPos = parentFrame.mNodePos - 2;
                            rightNode = node;
                            break select;
                        }

                        leftAvail = leftNode.availableLeafBytes();
                    }
                }

                int rightAvail;

                if (pos >= parentNode.highestInternalPos()) {
                    rightNode = null;
                    rightAvail = -1;
                } else {
                    try {
                        rightNode = mTree.mDatabase
                            .latchChildRetainParentEx(parentNode, pos + 2, false);
                    } catch (Throwable e) {
                        if (leftNode != null) {
                            leftNode.releaseExclusive();
                        }
                        node.releaseExclusive();
                        throw e;
                    }

                    if (rightNode == null) {
                        rightAvail = -1;
                    } else {
                        if (rightNode.mSplit != null) {
                            // Finish sibling split.
                            if (leftNode != null) {
                                leftNode.releaseExclusive();
                            }
                            node.releaseExclusive();
                            node = null;
                            try {
                                parentNode.insertSplitChildRef
                                    (parentFrame, mTree, pos + 2, rightNode);
                                continue;
                            } catch (Throwable e) {
                                return;
                            }
                        }

                        rightAvail = rightNode.availableLeafBytes();
                    }
                }

                // Select a left and right pair, and then don't operate directly on the
                // original node and leaf parameters afterwards. The original node ends up
                // being referenced as a left or right member of the pair.

                // Choose adjacent node pair which has the most available space, and then
                // determine if both nodes can fit in one node. If so, migrate and delete the
                // right node. Leave unbalanced otherwise.

                if (leftAvail <= rightAvail) {
                    if (leftNode != null) {
                        leftNode.releaseExclusive();
                    }
                    leftPos = parentFrame.mNodePos;
                    leftNode = node;
                    leftAvail = nodeAvail;
                } else {
                    if (rightNode != null) {
                        rightNode.releaseExclusive();
                    }
                    leftPos = parentFrame.mNodePos - 2;
                    rightNode = node;
                    rightAvail = nodeAvail;
                }

                int rem = leftAvail + rightAvail - pageSize(node.mPage) + Node.TN_HEADER_SIZE;

                if (rem >= 0) {
                    // Enough space will remain in the selected node, so proceed with merge.
                    break select;
                }

                if (rightNode != null) {
                    rightNode.releaseExclusive();
                }

                break doMerge;
            }

            // Migrate the entire contents of the right node into the left node, and then
            // delete the right node. Left must be marked dirty, and parent is already expected
            // to be dirty.

            try {
                if (mTree.markDirty(leftNode)) {
                    parentNode.updateChildRefId(leftPos, leftNode.id());
                }
            } catch (Throwable e) {
                leftNode.releaseExclusive();
                rightNode.releaseExclusive();
                parentNode.releaseExclusive();
                throw e;
            }

            try {
                Node.moveLeafToLeftAndDelete(mTree, leftNode, rightNode);
            } catch (Throwable e) {
                leftNode.releaseExclusive();
                parentNode.releaseExclusive();
                throw e;
            }

            parentNode.deleteRightChildRef(leftPos + 2);
        }

        mergeInternal(parentFrame, parentNode, leftNode);
    }

    /**
     * Caller must hold exclusive latch, which is released by this method.
     *
     * @param childNode never null, latched exclusively, always released by this method
     */
    private void mergeInternal(CursorFrame frame, Node node, Node childNode) throws IOException {
        if (!node.shouldInternalMerge()) {
            childNode.releaseExclusive();
            node.releaseExclusive();
            return;
        }

        if (!node.hasKeys() && node == mTree.mRoot) {
            // Delete the empty root node, eliminating a tree level.
            mTree.rootDelete(childNode);
            return;
        }

        childNode.releaseExclusive();

        // At this point, only one node latch is held, and it should merge with
        // a sibling node. Node is guaranteed to be an internal node.

        CursorFrame parentFrame = frame.mParentFrame;

        if (parentFrame == null) {
            // Root node cannot merge into anything.
            node.releaseExclusive();
            return;
        }

        // Try-latch up the tree to avoid deadlocks.
        Node parentNode = parentFrame.tryAcquireExclusive();
        if (parentNode == null) {
            node.releaseExclusive();
            node = null;
            parentNode = parentFrame.acquireExclusive();
        }

        if (parentNode.isLeaf()) {
            throw new AssertionError("Parent node is a leaf");
        }

        Node leftNode, rightNode;
        int nodeAvail;
        while (true) {
            latchNode: {
                if (parentNode.mSplit != null) {
                    if (node != null) {
                        node.releaseExclusive();
                    }
                    parentNode = mTree.finishSplit(parentFrame, parentNode);
                } else if (node != null) {
                    // Should already be latched.
                    break latchNode;
                }

                node = frame.acquireExclusive();
            }

            // Double check that node should still merge.
            if (!node.shouldMerge(nodeAvail = node.availableInternalBytes())) {
                node.releaseExclusive();
                parentNode.releaseExclusive();
                return;
            }

            // Attempt to latch the left and right siblings, but without waiting in order to
            // avoid deadlocks.

            int pos = parentFrame.mNodePos;
            if (pos == 0) {
                leftNode = null;
            } else {
                try {
                    leftNode = mTree.mDatabase
                        .latchChildRetainParentEx(parentNode, pos - 2, false);
                } catch (Throwable e) {
                    node.releaseExclusive();
                    throw e;
                }

                if (leftNode != null && leftNode.mSplit != null) {
                    // Finish sibling split.
                    node.releaseExclusive();
                    node = null;
                    try {
                        parentNode.insertSplitChildRef(parentFrame, mTree, pos - 2, leftNode);
                        continue;
                    } catch (Throwable e) {
                        return;
                    }
                }
            }

            if (pos >= parentNode.highestInternalPos()) {
                rightNode = null;
            } else {
                try {
                    rightNode = mTree.mDatabase
                        .latchChildRetainParentEx(parentNode, pos + 2, false);
                } catch (Throwable e) {
                    if (leftNode != null) {
                        leftNode.releaseExclusive();
                    }
                    node.releaseExclusive();
                    throw e;
                }

                if (rightNode != null && rightNode.mSplit != null) {
                    // Finish sibling split.
                    if (leftNode != null) {
                        leftNode.releaseExclusive();
                    }
                    node.releaseExclusive();
                    node = null;
                    try {
                        parentNode.insertSplitChildRef(parentFrame, mTree, pos + 2, rightNode);
                        continue;
                    } catch (Throwable e) {
                        return;
                    }
                }
            }

            break;
        }

        // Select a left and right pair, and then don't operate directly on the
        // original node and frame parameters afterwards. The original node
        // ends up being referenced as a left or right member of the pair.

        int leftAvail;
        if (leftNode == null) {
            if (rightNode == null) {
                // Tail call. I could just loop here, but this is simpler.
                mergeInternal(parentFrame, parentNode, node);
                return;
            }
            leftAvail = -1;
        } else {
            leftAvail = leftNode.availableInternalBytes();
        }

        int rightAvail = rightNode == null ? -1 : rightNode.availableInternalBytes();

        // Choose adjacent node pair which has the most available space, and then determine if
        // both nodes can fit in one node. If so, migrate and delete the right node. Leave
        // unbalanced otherwise.

        int leftPos;
        if (leftAvail <= rightAvail) {
            if (leftNode != null) {
                leftNode.releaseExclusive();
            }
            leftPos = parentFrame.mNodePos;
            leftNode = node;
            leftAvail = nodeAvail;
        } else {
            if (rightNode != null) {
                rightNode.releaseExclusive();
            }
            leftPos = parentFrame.mNodePos - 2;
            rightNode = node;
            rightAvail = nodeAvail;
        }

        var parentPage = parentNode.mPage;
        int parentEntryLoc = p_ushortGetLE(parentPage, parentNode.searchVecStart() + leftPos);
        int parentEntryLen = Node.keyLengthAtLoc(parentPage, parentEntryLoc);
        int remaining = leftAvail - parentEntryLen
            + rightAvail - pageSize(parentPage) + (Node.TN_HEADER_SIZE - 2);

        if (remaining < 0) {
            if (rightNode != null) {
                rightNode.releaseExclusive();
            }
        } else {
            // Migrate the entire contents of the right node into the left node, and then
            // delete the right node. Left must be marked dirty, and parent is already
            // expected to be dirty.

            try {
                if (mTree.markDirty(leftNode)) {
                    parentNode.updateChildRefId(leftPos, leftNode.id());
                }
            } catch (Throwable e) {
                leftNode.releaseExclusive();
                rightNode.releaseExclusive();
                parentNode.releaseExclusive();
                throw e;
            }

            try {
                Node.moveInternalToLeftAndDelete
                    (mTree, leftNode, rightNode, parentPage, parentEntryLoc, parentEntryLen);
            } catch (Throwable e) {
                leftNode.releaseExclusive();
                parentNode.releaseExclusive();
                throw e;
            }
            parentNode.deleteRightChildRef(leftPos + 2);
        }

        // Tail call. I could just loop here, but this is simpler.
        mergeInternal(parentFrame, parentNode, leftNode);
    }

    private int pageSize(/*P*/ byte[] page) {
        /*P*/ // [
        return page.length;
        /*P*/ // |
        /*P*/ // return mTree.pageSize();
        /*P*/ // ]
    }
}
