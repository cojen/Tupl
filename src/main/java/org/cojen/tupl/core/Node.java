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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.util.concurrent.ThreadLocalRandom;

import org.cojen.tupl.CorruptDatabaseException;
import org.cojen.tupl.Cursor;
import org.cojen.tupl.DatabaseException;
import org.cojen.tupl.DatabaseFullException;
import org.cojen.tupl.WriteFailureException;

import org.cojen.tupl.diag.VerificationObserver;

import org.cojen.tupl.util.Clutch;

import static org.cojen.tupl.core.PageOps.*;

import static org.cojen.tupl.core.Utils.EMPTY_BYTES;
import static org.cojen.tupl.core.Utils.rethrow;

import static java.util.Arrays.compareUnsigned;

/**
 * Node within a B-tree, undo log, or a large value fragment.
 *
 * @author Brian S O'Neill
 */
final class Node extends Clutch implements DatabaseAccess {
    // Note: Changing these values affects how the LocalDatabase class handles the commit
    // flag. It only needs to flip bit 0 to switch dirty states.
    static final byte
        CACHED_CLEAN     = 0, // 0b0000
        CACHED_DIRTY_0   = 2, // 0b0010
        CACHED_DIRTY_1   = 3; // 0b0011

    /*
      Node type encoding strategy:

      bits 7..4: major type   0010 (fragment), 0100 (undo log),
                              0110 (internal), 0111 (bottom internal), 1000 (leaf)
      bits 3..1: sub type     for leaf: x0x (normal)
                              for internal: x1x (6 byte child pointer + 2 byte count), x0x (unused)
                              for both: bit 1 is set if low extremity, bit 3 for high extremity
      bit  0:    unused       0

      TN == Tree Node

      Note that leaf type is always negative. If type encoding changes, the isLeaf and
      isInternal methods might need to be updated.

     */

    static final byte
        TYPE_NONE     = 0,
        /*P*/ // [
        TYPE_FRAGMENT = (byte) 0x20, // 0b0010_000_0 (never persisted)
        /*P*/ // ]
        TYPE_UNDO_LOG = (byte) 0x40, // 0b0100_000_0
        TYPE_TN_IN    = (byte) 0x64, // 0b0110_010_0
        TYPE_TN_BIN   = (byte) 0x74, // 0b0111_010_0
        TYPE_TN_LEAF  = (byte) 0x80; // 0b1000_000_0

    static final byte LOW_EXTREMITY = 0x02, HIGH_EXTREMITY = 0x08;

    // Tree node header size.
    static final int TN_HEADER_SIZE = 12;

    // Negative id indicates that node is not in use, and 1 is a reserved page id.
    private static final int CLOSED_ID = -1;

    static final int ENTRY_FRAGMENTED = 0x40;

    static final VarHandle cIdHandle;

    static {
        try {
            cIdHandle = MethodHandles.lookup().findVarHandle(Node.class, "mId", long.class);
        } catch (Throwable e) {
            throw rethrow(e);
        }
    }

    // Group this node belongs to, for tracking dirty nodes and most recently used nodes.
    final NodeGroup mGroup;

    // Links within usage list, guarded by NodeGroup.
    Node mMoreUsed; // points to more recently used node
    Node mLessUsed; // points to less recently used node

    // Links within dirty list, guarded by NodeGroup.
    Node mNextDirty;
    Node mPrevDirty;

    /*
      Nodes define the contents of Trees and UndoLogs. All node types start
      with a two byte header.

      +----------------------------------------+
      | byte:   node type                      |  header
      | byte:   reserved (must be 0)           |
      -                                        -

      There are two types of tree nodes, having a similar structure and
      supporting a maximum page size of 65536 bytes. The ushort type is an
      unsigned byte pair, and the ulong type is eight bytes. All multibyte
      types are little endian encoded.

      +----------------------------------------+
      | byte:   node type                      |  header
      | byte:   reserved (must be 0)           |
      | ushort: garbage in segments            |
      | ushort: pointer to left segment tail   |
      | ushort: pointer to right segment tail  |
      | ushort: pointer to search vector start |
      | ushort: pointer to search vector end   |
      +----------------------------------------+
      | left segment                           |
      -                                        -
      |                                        |
      +----------------------------------------+
      | free space                             | <-- left segment tail (exclusive)
      -                                        -
      |                                        |
      +----------------------------------------+
      | search vector                          | <-- search vector start (inclusive)
      -                                        -
      |                                        | <-- search vector end (inclusive)
      +----------------------------------------+
      | free space                             |
      -                                        -
      |                                        | <-- right segment tail (exclusive)
      +----------------------------------------+
      | right segment                          |
      -                                        -
      |                                        |
      +----------------------------------------+

      The left and right segments are used for allocating variable sized entries, and the
      tail points to the next allocation. Allocations are made toward the search vector
      such that the free space before and after the search vector remain the roughly the
      same. The search vector may move if a contiguous allocation is not possible on
      either side.

      The search vector is used for performing a binary search against keys. The keys are
      variable length and are stored anywhere in the left and right segments. The search
      vector itself must point to keys in the correct order, supporting binary search. The
      search vector is also required to be aligned to an even address, contain fixed size
      entries, and it never has holes. Adding or removing entries from the search vector
      requires entries to be shifted. The shift operation can be performed from either
      side, but the smaller shift is always chosen as a performance optimization.
      
      Garbage refers to the amount of unused bytes within the left and right allocation
      segments. Garbage accumulates when entries are deleted and updated from the
      segments. Segments are not immediately shifted because the search vector would also
      need to be repaired. A compaction operation reclaims garbage by rebuilding the
      segments and search vector. A copying garbage collection algorithm is used for this.

      The compaction implementation allocates all surviving entries in the left segment,
      leaving an empty right segment. There is no requirement that the segments be
      balanced -- this only applies to the free space surrounding the search vector.

      Leaf nodes support variable length keys and values, encoded as a pair, within the
      segments. Entries in the search vector are ushort pointers into the segments. No
      distinction is made between the segments because the pointers are absolute.

      Entries start with a one byte key header:

      0b0xxx_xxxx: key is 1..128 bytes
      0b1fxx_xxxx: key is 0..16383 bytes

      For keys 1..128 bytes in length, the length is defined as (header + 1). For
      keys 0..16383 bytes in length, a second header byte is used. The second byte is
      unsigned, and the length is defined as (((header & 0x3f) << 8) | header2). The key
      contents immediately follow the header byte(s).

      When the 'f' bit is zero, the entry is a normal key. Very large keys are stored in a
      fragmented fashion, which is also used by large values. The encoding format is defined by
      LocalDatabase.fragment.

      The value follows the key, and its header encodes the entry length:

      0b0xxx_xxxx: value is 0..127 bytes
      0b1f0x_xxxx: value/entry is 1..8192 bytes
      0b1f10_xxxx: value/entry is 1..1048576 bytes
      0b1111_1111: ghost value (null)

      When the 'f' bit is zero, the entry is a normal value. Otherwise, it is a fragmented
      value, defined by LocalDatabase.fragment.

      For entries 1..8192 bytes in length, a second header byte is used. The
      length is then defined as ((((h0 & 0x1f) << 8) | h1) + 1). For larger
      entries, the length is ((((h0 & 0x0f) << 16) | (h1 << 8) | h2) + 1).
      Node limit is currently 65536 bytes, which limits maximum entry length.

      The "values" for internal nodes are actually identifiers for child nodes. The number
      of child nodes is always one more than the number of keys. For this reason, the
      key-value format used by leaf nodes cannot be applied to internal nodes. Also, the
      identifiers are always a fixed length, ulong type.

      Child node identifiers are encoded immediately following the search vector. Free space
      management must account for this, treating it as an extension to the search vector.

      Each entry in the child node id segment contains 6 byte child node id, followed by
      2 byte count of keys in the child node. The child node ids are in the same order as
      keys in the search vector.

      +----------------------------------------+
      | byte:   node type                      |  header
      | byte:   reserved (must be 0)           |
      | ushort: garbage in segments            |
      | ushort: pointer to left segment tail   |
      | ushort: pointer to right segment tail  |
      | ushort: pointer to search vector start |
      | ushort: pointer to search vector end   |
      +----------------------------------------+
      | left key segment                       |
      -                                        -
      |                                        |
      +----------------------------------------+
      | free space                             | <-- left segment tail (exclusive)
      -                                        -
      |                                        |
      +----------------------------------------+
      | search vector                          | <-- search vector start (inclusive)
      -                                        -
      |                                        | <-- search vector end (inclusive)
      +----------------------------------------+
      | child node id segment                  |
      -                                        -
      |                                        |
      +----------------------------------------+
      | free space                             |
      -                                        -
      |                                        | <-- right segment tail (exclusive)
      +----------------------------------------+
      | right key segment                      |
      -                                        -
      |                                        |
      +----------------------------------------+

     */

    // Raw contents of node.
    /*P*/ byte[] mPage;

    private long mId;

    byte mCachedState;

    /*P*/ // [
    // Entries from header, available as fields for quick access.
    private byte mType;
    private int mGarbage;
    private int mLeftSegTail;
    private int mRightSegTail;
    private int mSearchVecStart;
    private int mSearchVecEnd;
    /*P*/ // ]

    // Next in NodeMap collision chain.
    Node mNodeMapNext;

    // Linked stack of CursorFrames bound to this Node.
    volatile CursorFrame mLastCursorFrame;

    // Set by a partially completed split.
    Split mSplit;

    Node(NodeGroup group, /*P*/ byte[] page) {
        mGroup = group;
        mPage = page;
    }

    // Construct a stub node, latched exclusively.
    Node(NodeGroup group) {
        super(EXCLUSIVE);

        mGroup = group;
        mPage = p_stubTreePage();

        // Special stub id. Page 0 and 1 are never used by nodes, and negative indicates that
        // node shouldn't be persisted.
        id(-1);

        mCachedState = CACHED_CLEAN;

        /*P*/ // [
        type(TYPE_TN_IN);
        garbage(0);
        leftSegTail(TN_HEADER_SIZE);
        rightSegTail(TN_HEADER_SIZE + 8 - 1);
        searchVecStart(TN_HEADER_SIZE);
        searchVecEnd(TN_HEADER_SIZE - 2); // inclusive
        /*P*/ // ]
    }

    // Construct a "lock" object for use when loading a node. See loadChild method.
    private Node(long id) {
        super(EXCLUSIVE);
        mGroup = null;
        id(id);
    }

    // Construct a simple marker Node instance, which is otherwise unusable.
    Node() {
        mGroup = null;
        id(Long.MIN_VALUE);
    }

    /**
     * Must be called when object is no longer referenced.
     */
    void delete(LocalDatabase db) {
        acquireExclusive();
        try {
            doDelete(db);
        } finally {
            releaseExclusive();
        }
    }

    /**
     * Must be called when object is no longer referenced. Caller must acquire exclusive latch.
     */
    void doDelete(LocalDatabase db) {
        /*P*/ // [|
        /*P*/ // if (db.mFullyMapped) {
        /*P*/ //     // Cannot delete mapped pages.
        /*P*/ //     closeRoot(p_closedTreePage());
        /*P*/ //     return;
        /*P*/ // }
        /*P*/ // ]

        var page = mPage;
        if (!isClosedOrDeleted(page)) {
            p_delete(page);
            closeRoot(p_closedTreePage());
        }
    }

    @Override
    protected Clutch.Pack getPack() {
        return mGroup;
    }

    @Override
    public LocalDatabase getDatabase() {
        return mGroup.mDatabase;
    }

    void asEmptyRoot() {
        id(0);
        mCachedState = CACHED_CLEAN;
        type((byte) (TYPE_TN_LEAF | LOW_EXTREMITY | HIGH_EXTREMITY));
        clearEntries();
    }

    void asTrimmedRoot() {
        asEmptyLeaf(LOW_EXTREMITY | HIGH_EXTREMITY);
    }

    void asEmptyLeaf(int extremity) {
        type((byte) (TYPE_TN_LEAF | extremity));
        clearEntries();
    }

    /**
     * Prepares the node for appending entries out-of-order, and then sorting them.
     *
     * @see #appendToSortLeaf
     */
    void asSortLeaf() {
        type((byte) (TYPE_TN_LEAF | LOW_EXTREMITY | HIGH_EXTREMITY));
        garbage(0);
        leftSegTail(TN_HEADER_SIZE);
        int pageSize = pageSize(mPage);
        rightSegTail(pageSize - 1);
        // Position search vector on the left side, but appendToSortLeaf will move it to the
        // right. It's not safe to position an empty search vector on the right side, because
        // the inclusive start position would wrap around for the largest page size.
        searchVecStart(TN_HEADER_SIZE);
        searchVecEnd(TN_HEADER_SIZE - 2); // inclusive
    }

    /**
     * Close the root node when closing a tree.
     *
     * @page page p_closedTreePage or p_deletedTreePage
     */
    void closeRoot(/*P*/ byte[] page) {
        // Prevent node from being marked dirty.
        id(CLOSED_ID);
        mCachedState = CACHED_CLEAN;
        mPage = page;
        readFields();
    }

    Node cloneNode() {
        var newNode = new Node(mGroup, mPage);
        newNode.id(id());
        newNode.mCachedState = mCachedState;
        /*P*/ // [
        newNode.type(type());
        newNode.garbage(garbage());
        newNode.leftSegTail(leftSegTail());
        newNode.rightSegTail(rightSegTail());
        newNode.searchVecStart(searchVecStart());
        newNode.searchVecEnd(searchVecEnd());
        /*P*/ // ]
        return newNode;
    }

    private void clearEntries() {
        garbage(0);
        leftSegTail(TN_HEADER_SIZE);
        int pageSize = pageSize(mPage);
        rightSegTail(pageSize - 1);
        // Search vector location must be even.
        searchVecStart((TN_HEADER_SIZE + ((pageSize - TN_HEADER_SIZE) >> 1)) & ~1);
        searchVecEnd(searchVecStart() - 2); // inclusive
    }

    /**
     * Indicate that a non-root node is most recently used. Root node is not managed in usage
     * list and cannot be evicted. Caller must hold any latch on node. Latch is never released
     * by this method, even if an exception is thrown.
     */
    void used() {
        used(ThreadLocalRandom.current());
    }

    void used(ThreadLocalRandom rnd) {
        mGroup.used(this, rnd);
    }

    /**
     * Indicate that node is least recently used, allowing it to be recycled immediately
     * without evicting another node. Node must be latched by caller, which is always released
     * by this method.
     */
    void unused() {
        mGroup.unused(this);
    }

    /**
     * Allow a Node which was allocated as unevictable to be evictable, starting off as the
     * most recently used.
     */
    void makeEvictable() {
        mGroup.makeEvictable(this);
    }

    /**
     * Allow a Node which was allocated as unevictable to be evictable, as the least recently
     * used.
     */
    void makeEvictableNow() {
        mGroup.makeEvictableNow(this);
    }

    /**
     * Allow a Node which was allocated as evictable to be unevictable.
     */
    void makeUnevictable() {
        mGroup.makeUnevictable(this);
    }

    /**
     * Options for loadChild. Caller must latch parent as shared or exclusive, which can be
     * retained (default) or released if shared. Child node is latched shared (default) or
     * exclusive.
     */
    static final int OPTION_PARENT_RELEASE_SHARED = 0b001, OPTION_CHILD_ACQUIRE_EXCLUSIVE = 0b100;

    /**
     * With this parent node latched shared or exclusive, loads child with shared or exclusive
     * latch. Caller must ensure that child is not already loaded. If an exception is thrown,
     * parent and child latches are always released.
     *
     * @param options described by OPTION_* fields
     * @return child node, possibly split
     */
    Node loadChild(LocalDatabase db, long childId, int options) throws IOException {
        // Insert a "lock", which is a temporary node latched exclusively. All other threads
        // attempting to load the child node will block trying to acquire the exclusive latch.
        Node lock;
        try {
            lock = new Node(childId);

            if (childId <= 1) {
                checkClosedIndexException(mPage);
                throw new CorruptDatabaseException("Illegal child id: " + childId);
            }
        } catch (Throwable e) {
            releaseEither();
            throw e;
        }

        try {
            while (true) {
                Node childNode = db.nodeMapPutIfAbsent(lock);
                if (childNode == null) {
                    break;
                }

                // Was already loaded, or is currently being loaded.
                if ((options & OPTION_CHILD_ACQUIRE_EXCLUSIVE) == 0) {
                    childNode.acquireShared();
                    if (childId == childNode.id()) {
                        return childNode;
                    }
                    childNode.releaseShared();
                } else {
                    childNode.acquireExclusive();
                    if (childId == childNode.id()) {
                        return childNode;
                    }
                    childNode.releaseExclusive();
                }
            }
        } finally {
            // Release parent latch before child has been loaded. Any threads which wish to
            // access the same child will block until this thread has finished loading the
            // child and released its exclusive latch.
            if ((options & OPTION_PARENT_RELEASE_SHARED) != 0) {
                releaseShared();
            }
        }

        try {
            Node childNode;
            try {
                childNode = db.allocLatchedNode();
                childNode.id(childId);
            } catch (Throwable e) {
                db.nodeMapRemove(lock);
                throw e;
            }

            // Replace the lock with the real child node, but don't notify any threads waiting
            // on the lock just yet. They'd go back to sleep waiting for the read to finish.
            db.nodeMapReplace(lock, childNode);

            try {
                childNode.read(db, childId);
            } catch (Throwable e) {
                // Another thread might access child and see that it's invalid because the id
                // is zero. It will assume it got evicted and will attempt to reload it
                db.nodeMapRemove(childNode);
                childNode.id(0);
                childNode.type(TYPE_NONE);
                childNode.releaseExclusive();
                throw e;
            }

            if ((options & OPTION_CHILD_ACQUIRE_EXCLUSIVE) == 0){
                childNode.downgrade();
            }

            return childNode;
        } catch (Throwable e) {
            if ((options & OPTION_PARENT_RELEASE_SHARED) == 0) {
                // Obey the method contract and release parent latch due to exception.
                releaseEither();
            }
            throw e;
        } finally {
            // Wake any threads waiting on the lock now that the real child node is ready, or
            // if the load failed. Lock id must be set to zero to ensure that it's not accepted
            // as the child node.
            lock.id(0);
            lock.releaseExclusive();
        }
    }

    /**
     * With this parent node held exclusively, attempts to return child with exclusive latch
     * held. If an exception is thrown, parent and child latches are always released. This
     * method is intended to be called for rebalance operations.
     *
     * @return null or child node, never split
     */
    private Node tryLatchChildNotSplit(int childPos) throws IOException {
        final long childId = childId(childPos);
        final LocalDatabase db = getDatabase();
        Node childNode = db.nodeMapGet(childId);

        latchChild: {
            if (childNode != null) {
                if (!childNode.tryAcquireExclusive()) {
                    return null;
                }
                // Need to check again in case evict snuck in.
                if (childId == childNode.id()) {
                    break latchChild;
                }
                childNode.releaseExclusive();
            }
            childNode = loadChild(db, childId, OPTION_CHILD_ACQUIRE_EXCLUSIVE);
        }

        if (childNode.mSplit == null) {
            // Return without updating LRU position. Node contents were not user requested.
            return childNode;
        } else {
            childNode.releaseExclusive();
            return null;
        }
    }

    /**
     * Caller must hold exclusive root latch and it must verify that root has split.
     */
    void finishSplitRoot() throws IOException {
        // Create a child node and copy this root node state into it. Then update this
        // root node to point to new and split child nodes. New root is always an internal node.

        LocalDatabase db = mGroup.mDatabase;
        Node child = db.allocDirtyNode();
        db.nodeMapPut(child);

        /*P*/ byte[] newRootPage;

        /*P*/ // [
        newRootPage = child.mPage;
        child.mPage = mPage;
        child.type(type());
        child.garbage(garbage());
        child.leftSegTail(leftSegTail());
        child.rightSegTail(rightSegTail());
        child.searchVecStart(searchVecStart());
        child.searchVecEnd(searchVecEnd());
        /*P*/ // |
        /*P*/ // if (db.mFullyMapped) {
        /*P*/ //     // Page cannot change, so copy it instead.
        /*P*/ //     newRootPage = mPage;
        /*P*/ //     p_copy(newRootPage, 0, child.mPage, 0, db.pageSize());
        /*P*/ // } else {
        /*P*/ //     newRootPage = child.mPage;
        /*P*/ //     child.mPage = mPage;
        /*P*/ // }
        /*P*/ // ]

        final Split split = mSplit;
        final Node sibling = rebindSplitFrames(split);
        mSplit = null;

        // Fix child node cursor frame bindings.
        for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            frame.rebind(child, frame.mNodePos);
            frame = prev;
        }

        Node left, right;
        if (split.mSplitRight) {
            left = child;
            right = sibling;
        } else {
            left = sibling;
            right = child;
        }

        int leftSegTail = split.copySplitKeyToParent(newRootPage, TN_HEADER_SIZE);

        // Create new single-element search vector. Center it using the same formula as the
        // compactInternal method.
        final int searchVecStart = pageSize(newRootPage) -
            (((pageSize(newRootPage) - leftSegTail + (2 + 8 + 8)) >> 1) & ~1);
        p_shortPutLE(newRootPage, searchVecStart, TN_HEADER_SIZE);
        p_longPutLE(newRootPage, searchVecStart + 2, left.id());
        p_longPutLE(newRootPage, searchVecStart + 2 + 8, right.id());

        byte newType = isLeaf() ? (byte) (TYPE_TN_BIN | LOW_EXTREMITY | HIGH_EXTREMITY)
            : (byte) (TYPE_TN_IN | LOW_EXTREMITY | HIGH_EXTREMITY);

        mPage = newRootPage;
        /*P*/ // [
        type(newType);
        garbage(0);
        /*P*/ // |
        /*P*/ // p_intPutLE(newRootPage, 0, newType & 0xff); // type, reserved byte, and garbage
        /*P*/ // ]
        leftSegTail(leftSegTail);
        rightSegTail(pageSize(newRootPage) - 1);
        searchVecStart(searchVecStart);
        searchVecEnd(searchVecStart);

        // Add a parent cursor frame for all left and right node cursors.
        var lock = new CursorFrame();
        addParentFrames(lock, left, 0);
        addParentFrames(lock, right, 2);

        child.releaseExclusive();
        sibling.releaseExclusive();

        // Split complete, so allow new node to be evictable.
        sibling.makeEvictable();
    }

    private void addParentFrames(CursorFrame lock, Node child, int pos) {
        for (CursorFrame frame = child.mLastCursorFrame; frame != null; ) {
            CursorFrame lockResult = frame.tryLock(lock);
            if (lockResult != null) {
                try {
                    CursorFrame parentFrame = frame.mParentFrame;
                    if (parentFrame == null) {
                        parentFrame = new CursorFrame();
                        parentFrame.bind(this, pos);
                        frame.mParentFrame = parentFrame;
                    } else {
                        parentFrame.rebind(this, pos);
                    }
                } finally {
                    frame.unlock(lockResult);
                }
            }

            frame = frame.mPrevCousin;
        }
    }

    /**
     * Caller must hold exclusive latch. Latch is never released by this method, even if
     * an exception is thrown.
     */
    void read(LocalDatabase db, long id) throws IOException {
        db.readNode(this, id);
        try {
            readFields();
        } catch (IllegalStateException e) {
            throw new CorruptDatabaseException(e.getMessage());
        }
    }

    private void readFields() throws IllegalStateException {
        var page = mPage;

        byte type = p_byteGet(page, 0);

        /*P*/ // [
        type(type);

        // For undo log node, this is top entry pointer.
        garbage(p_ushortGetLE(page, 2));
        /*P*/ // ]

        if (type != TYPE_UNDO_LOG) {
            /*P*/ // [
            leftSegTail(p_ushortGetLE(page, 4));
            rightSegTail(p_ushortGetLE(page, 6));
            searchVecStart(p_ushortGetLE(page, 8));
            searchVecEnd(p_ushortGetLE(page, 10));
            /*P*/ // ]
            type &= ~(LOW_EXTREMITY | HIGH_EXTREMITY);
            if (type >= 0 && type != TYPE_TN_IN && type != TYPE_TN_BIN) {
                throw new IllegalStateException("Unknown node type: " + type + ", id: " + id());
            }
        }

        if (p_byteGet(page, 1) != 0) {
            throw new IllegalStateException
                ("Illegal reserved byte in node: " + p_byteGet(page, 1) + ", id: " + id());
        }
    }

    /**
     * Caller must hold any latch, which is not released, even if an exception is thrown.
     */
    void write(PageDb db) throws WriteFailureException {
        var page = prepareWrite();
        try {
            db.writePage(id(), page);
        } catch (IOException e) {
            throw WriteFailureException.from(e);
        }
    }

    private /*P*/ byte[] prepareWrite() {
        if (mSplit != null) {
            throw new AssertionError("Cannot write partially split node");
        }

        var page = mPage;

        /*P*/ // [
        if (type() != TYPE_FRAGMENT) {
            p_bytePut(page, 0, type());
            p_bytePut(page, 1, 0); // reserved

            // For undo log node, this is top entry pointer.
            p_shortPutLE(page, 2, garbage());

            if (type() != TYPE_UNDO_LOG) {
                p_shortPutLE(page, 4, leftSegTail());
                p_shortPutLE(page, 6, rightSegTail());
                p_shortPutLE(page, 8, searchVecStart());
                p_shortPutLE(page, 10, searchVecEnd());
            }
        }
        /*P*/ // ]

        return page;
    }

    /**
     * Caller must hold exclusive latch on node. Latch is released by this
     * method when false is returned or if an exception is thrown.
     *
     * @return false if cannot evict
     */
    boolean evict(LocalDatabase db) throws IOException {
        CursorFrame last = mLastCursorFrame;

        if (last != null) {
            // Cannot evict if in use by a cursor or if splitting, unless the only frames are
            // for deleting ghosts. No explicit split check is required, since a node cannot be
            // in a split state without a cursor bound to it.

            CursorFrame frame = last;
            do {
                if (!(frame instanceof GhostFrame)) {
                    releaseExclusive();
                    return false;
                }
                frame = frame.mPrevCousin;
            } while (frame != null);

            // Allow eviction. A full search will be required when the ghost is
            // eventually deleted.

            do {
                frame = last.mPrevCousin;
                CursorFrame.popAll(last);
                last = frame;
            } while (last != null);
        }

        try {
            // Check if <= 0 (already evicted).
            long id = id();
            if (id > 0) {
                if (mCachedState != CACHED_CLEAN) {
                    var page = prepareWrite();
                    var newPage = db.mPageDb.evictPage(id, page);
                    if (newPage != page) {
                        mPage = newPage;
                    }
                    mCachedState = CACHED_CLEAN;
                }

                db.nodeMapRemove(this, Long.hashCode(id));
                id(0);

                // Note: Don't do this. In the fully mapped mode (using MappedPageArray),
                // setting the type will corrupt the evicted node. The caller swaps in a
                // different page, which is where the type should be written to.
                //type(TYPE_NONE);
            }

            return true;
        } catch (Throwable e) {
            releaseExclusive();
            throw e;
        }
    }

    /**
     * Invalidate all cursors, starting from the root. Used when closing an index which still
     * has active cursors. Caller must hold exclusive latch on node.
     *
     * @page page p_closedTreePage or p_deletedTreePage
     */
    void invalidateCursors(/*P*/ byte[] page) {
        invalidateCursors(page, createClosedNode(page));
    }

    private void invalidateCursors(/*P*/ byte[] page, Node closed) {
        int pos = isLeaf() ? -1 : 0;

        closed.acquireExclusive();
        try {
            for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
                // Capture previous frame from linked list before changing the links.
                CursorFrame prev = frame.mPrevCousin;
                frame.rebind(closed, pos);
                frame = prev;
            }
        } finally {
            closed.releaseExclusive();
        }

        if (!isInternal()) {
            return;
        }

        LocalDatabase db = mGroup.mDatabase;

        closed = null;

        int childPtr = searchVecEnd() + 2;
        final int highestPtr = childPtr + (highestInternalPos() << 2);
        for (; childPtr <= highestPtr; childPtr += 8) {
            long childId = childIdByOffset(mPage, childPtr);
            Node child = db.nodeMapGetExclusive(childId);
            if (child != null) {
                try {
                    if (closed == null) {
                        closed = createClosedNode(page);
                    }
                    child.invalidateCursors(page, closed);
                } finally {
                    child.releaseExclusive();
                }
            }
        }
    }

    /**
     * @page page p_closedTreePage or p_deletedTreePage
     */
    private static Node createClosedNode(/*P*/ byte[] page) {
        var closed = new Node(null, page);
        closed.id(CLOSED_ID);
        closed.mCachedState = CACHED_CLEAN;
        closed.readFields();
        return closed;
    }

    private int pageSize(/*P*/ byte[] page) {
        /*P*/ // [
        return page.length;
        /*P*/ // |
        /*P*/ // return mGroup.pageSize();
        /*P*/ // ]
    }

    /**
     * Get the node identifier, with opaque access. The identifier is often optimistically read
     * without acquiring latch, and then it's double checked with a latch held. Opaque access
     * ensures safe ordered access to the identifier, helping to reduce double check retries.
     */
    long id() {
        return (long) cIdHandle.getOpaque(this);
    }

    /**
     * Set the node identifier, with opaque access.
     */
    void id(long id) {
        cIdHandle.setOpaque(this, id);
    }

    /**
     * Get the node type.
     */
    byte type() {
        /*P*/ // [
        return mType;
        /*P*/ // |
        /*P*/ // return p_byteGet(mPage, 0);
        /*P*/ // ]
    }

    /**
     * Set the node type.
     */
    void type(byte type) {
        /*P*/ // [
        mType = type;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 0, type & 0xff); // clear reserved byte too
        /*P*/ // ]
    }

    /**
     * Get the node garbage size.
     */
    int garbage() {
        /*P*/ // [
        return mGarbage;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 2);
        /*P*/ // ]
    }

    /**
     * Set the node garbage size.
     */
    void garbage(int garbage) {
        /*P*/ // [
        mGarbage = garbage;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 2, garbage);
        /*P*/ // ]
    }

    /**
     * Get the undo log node top entry pointer. (same field as garbage)
     */
    int undoTop() {
        /*P*/ // [
        return mGarbage;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 2);
        /*P*/ // ]
    }

    /**
     * Set the undo log node top entry pointer. (same field as garbage)
     */
    void undoTop(int top) {
        /*P*/ // [
        mGarbage = top;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 2, top);
        /*P*/ // ]
    }

    /**
     * Get the left segment tail pointer.
     */
    private int leftSegTail() {
        /*P*/ // [
        return mLeftSegTail;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 4);
        /*P*/ // ]
    }

    /**
     * Set the left segment tail pointer.
     */
    private void leftSegTail(int tail) {
        /*P*/ // [
        mLeftSegTail = tail;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 4, tail);
        /*P*/ // ]
    }

    /**
     * Get the right segment tail pointer.
     */
    private int rightSegTail() {
        /*P*/ // [
        return mRightSegTail;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 6);
        /*P*/ // ]
    }

    /**
     * Set the right segment tail pointer.
     */
    private void rightSegTail(int tail) {
        /*P*/ // [
        mRightSegTail = tail;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 6, tail);
        /*P*/ // ]
    }

    /**
     * Get the search vector start pointer.
     */
    int searchVecStart() {
        /*P*/ // [
        return mSearchVecStart;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 8);
        /*P*/ // ]
    }

    /**
     * Set the search vector start pointer.
     */
    void searchVecStart(int start) {
        /*P*/ // [
        mSearchVecStart = start;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 8, start);
        /*P*/ // ]
    }

    /**
     * Get the search vector end pointer.
     */
    int searchVecEnd() {
        /*P*/ // [
        return mSearchVecEnd;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 10);
        /*P*/ // ]
    }

    /**
     * Set the search vector end pointer.
     */
    void searchVecEnd(int end) {
        /*P*/ // [
        mSearchVecEnd = end;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 10, end);
        /*P*/ // ]
    }

    /**
     * Caller must hold any latch.
     */
    boolean isLeaf() {
        return type() < 0;
    }

    /**
     * Caller must hold any latch. Returns true if node is any kind of internal node.
     */
    boolean isInternal() {
        return (type() & 0xe0) == 0x60;
    }

    /**
     * Caller must hold any latch.
     */
    boolean isBottomInternal() {
        return (type() & 0xf0) == 0x70;
    }

    /**
     * Caller must hold any latch.
     */
    boolean isNonBottomInternal() {
        return (type() & 0xf0) == 0x60;
    }

    /**
     * Caller must hold any latch.
     *
     * @see #countNonGhostKeys
     */
    int numKeys() {
        return (searchVecEnd() - searchVecStart() + 2) >> 1;
    }

    /**
     * Caller must hold any latch.
     */
    boolean hasKeys() {
        return searchVecEnd() >= searchVecStart();
    }

    /**
     * Returns the highest possible key position, which is an even number. If
     * node has no keys, return value is negative. Caller must hold any latch.
     */
    int highestKeyPos() {
        return searchVecEnd() - searchVecStart();
    }

    /**
     * Returns highest leaf or internal position. Caller must hold any latch.
     */
    int highestPos() {
        int pos = searchVecEnd() - searchVecStart();
        if (!isLeaf()) {
            pos += 2;
        }
        return pos;
    }

    /**
     * Returns the highest possible leaf key position, which is an even
     * number. If leaf node is empty, return value is negative. Caller must
     * hold any latch.
     */
    int highestLeafPos() {
        return searchVecEnd() - searchVecStart();
    }

    /**
     * Returns the highest possible internal node position, which is an even
     * number. Highest position doesn't correspond to a valid key, but instead
     * a child node position. If internal node has no keys, node has one child
     * at position zero. Caller must hold any latch.
     */
    int highestInternalPos() {
        return searchVecEnd() - searchVecStart() + 2;
    }

    /**
     * Caller must hold any latch.
     */
    int availableBytes() {
        return isLeaf() ? availableLeafBytes() : availableInternalBytes();
    }

    /**
     * Caller must hold any latch.
     */
    int availableLeafBytes() {
        return garbage() + searchVecStart() - searchVecEnd()
            - leftSegTail() + rightSegTail() + (1 - 2);
    }

    /**
     * Caller must hold any latch.
     */
    int availableInternalBytes() {
        return garbage() + 5 * (searchVecStart() - searchVecEnd())
            - leftSegTail() + rightSegTail() + (1 - (5 * 2 + 8));
    }

    /**
     * Applicable only to leaf nodes, not split. Caller must hold any latch.
     */
    int countNonGhostKeys() {
        return countNonGhostKeys(searchVecStart(), searchVecEnd());
    }

    /**
     * Applicable only to leaf nodes, not split. Caller must hold any latch.
     *
     * @param lowPos 2-based search vector position (inclusive)
     * @param highPos 2-based search vector position (inclusive)
     */
    int countNonGhostKeys(int lowPos, int highPos) {
        final var page = mPage;

        int count = 0;
        for (int i = lowPos; i <= highPos; i += 2) {
            int loc = p_ushortGetLE(page, i);
            if (p_byteGet(page, loc + keyLengthAtLoc(page, loc)) != -1) {
                count++;
            }
        }

        return count;
    }

    /**
     * Returns true if leaf is not split and underutilized. If so, it should be
     * merged with its neighbors, and possibly deleted. Caller must hold any latch.
     */
    boolean shouldLeafMerge() {
        return shouldMerge(availableLeafBytes());
    }

    /**
     * Returns true if non-leaf is not split and underutilized. If so, it should be
     * merged with its neighbors, and possibly deleted. Caller must hold any latch.
     */
    boolean shouldInternalMerge() {
        return shouldMerge(availableInternalBytes());
    }

    boolean shouldMerge(int availBytes) {
        return mSplit == null
            && (((type() & (LOW_EXTREMITY | HIGH_EXTREMITY)) == 0
                 && availBytes >= ((pageSize(mPage) - TN_HEADER_SIZE) >> 1))
                || !hasKeys());
    }

    /**
     * Determines if deleting an entry would cause this non-root leaf node to become empty. If
     * so, then caller should delete the ghost using a cursor, which can then delete this node.
     */
    boolean canQuickDeleteGhost() {
        // If both extremity bits are set, then this is a root node and it can become empty.
        // Otherwise, check if the node has more than one entry in it. After the delete, the
        // node will have one entry remaining.
        return type() == (TYPE_TN_LEAF | LOW_EXTREMITY | HIGH_EXTREMITY) ||
            searchVecEnd() > searchVecStart();
    }

    /**
     * @return 2-based insertion pos, which is negative if key isn't found
     */
    int binarySearch(byte[] key) throws IOException {
        final var page = mPage;
        final int keyLen = key.length;
        final int startPos = searchVecStart();
        int lowPos = startPos;
        int highPos = searchVecEnd();

        // TODO: Using this feature reduces performance for small keys. Is the potential
        // benefit for large keys worth it?
        // int lowMatch = 0, highMatch = 0;

        outer: while (lowPos <= highPos) {
            int midPos = ((lowPos + highPos) >> 1) & ~1;

            int compareLen, i;
            compare: {
                int compareLoc = p_ushortGetLE(page, midPos);
                compareLen = p_byteGet(page, compareLoc++);
                if (compareLen >= 0) {
                    compareLen++;
                } else {
                    int header = compareLen;
                    compareLen = ((compareLen & 0x3f) << 8) | p_ubyteGet(page, compareLoc++);

                    if ((header & ENTRY_FRAGMENTED) != 0) {
                        // Note: An optimized version wouldn't need to copy the whole key.
                        byte[] compareKey = getDatabase()
                            .reconstructKey(page, compareLoc, compareLen);

                        compareLen = compareKey.length;
                        int minLen = Math.min(compareLen, keyLen);
                        int cmp = compareUnsigned(compareKey, 0, minLen, key, 0, minLen);

                        if (cmp != 0) {
                            if (cmp < 0) {
                                lowPos = midPos + 2;
                                //lowMatch = mismatch & ~7;
                            } else {
                                highPos = midPos - 2;
                                //highMatch = mismatch & ~7;
                            }
                            continue outer;
                        }

                        break compare;
                    }
                }

                int minLen = Math.min(compareLen, keyLen);
                int minLen8 = minLen & ~7;
                i = 0;//Math.min(lowMatch, highMatch);

                for (; i < minLen8; i += 8) {
                    long cv = p_longGetBE(page, compareLoc + i);
                    long kv = Utils.decodeLongBE(key, i);
                    int cmp = Long.compareUnsigned(cv, kv);
                    if (cmp != 0) {
                        if (cmp < 0) {
                            lowPos = midPos + 2;
                            //lowMatch = i;
                        } else {
                            highPos = midPos - 2;
                            //highMatch = i;
                        }
                        continue outer;
                    }
                }

                for (; i < minLen; i++) {
                    byte cb = p_byteGet(page, compareLoc + i);
                    byte kb = key[i];
                    if (cb != kb) {
                        if ((cb & 0xff) < (kb & 0xff)) {
                            lowPos = midPos + 2;
                        } else {
                            highPos = midPos - 2;
                        }
                        continue outer;
                    }
                }
            }

            if (compareLen < keyLen) {
                lowPos = midPos + 2;
                //lowMatch = i & ~7;
            } else if (compareLen > keyLen) {
                highPos = midPos - 2;
                //highMatch = i & ~7;
            } else {
                return midPos - startPos;
            }
        }

        return ~(lowPos - startPos);
    }

    /**
     * @param midPos 2-based starting position
     * @return 2-based insertion pos, which is negative if key isn't found
     */
    int binarySearch(byte[] key, int midPos) throws IOException {
        final int startPos = searchVecStart();
        int lowPos = startPos;
        int highPos = searchVecEnd();
        if (lowPos > highPos) {
            return -1;
        }
        midPos += lowPos;
        if (midPos > highPos) {
            midPos = highPos;
        }

        final var page = mPage;
        final int keyLen = key.length;

        // TODO: Using this feature reduces performance for small keys. Is the potential
        // benefit for large keys worth it?
        // int lowMatch = 0, highMatch = 0;

        while (true) {
            compare: {
                int compareLen, i;
                c2: {
                    int compareLoc = p_ushortGetLE(page, midPos);
                    compareLen = p_byteGet(page, compareLoc++);
                    if (compareLen >= 0) {
                        compareLen++;
                    } else {
                        int header = compareLen;
                        compareLen = ((compareLen & 0x3f) << 8) | p_ubyteGet(page, compareLoc++);

                        if ((header & ENTRY_FRAGMENTED) != 0) {
                            // Note: An optimized version wouldn't need to copy the whole key.
                            byte[] compareKey = getDatabase()
                                .reconstructKey(page, compareLoc, compareLen);

                            compareLen = compareKey.length;
                            int minLen = Math.min(compareLen, keyLen);
                            int cmp = compareUnsigned(compareKey, 0, minLen, key, 0, minLen);

                            if (cmp != 0) {
                                if (cmp < 0) {
                                    lowPos = midPos + 2;
                                    //lowMatch = mismatch & ~7;
                                } else {
                                    highPos = midPos - 2;
                                    //highMatch = mismatch & ~7;
                                }
                                break compare;
                            }

                            break c2;
                        }
                    }

                    int minLen = Math.min(compareLen, keyLen);
                    int minLen8 = minLen & ~7;
                    i = 0;//Math.min(lowMatch, highMatch);

                    for (; i < minLen8; i += 8) {
                        long cv = p_longGetBE(page, compareLoc + i);
                        long kv = Utils.decodeLongBE(key, i);
                        int cmp = Long.compareUnsigned(cv, kv);
                        if (cmp != 0) {
                            if (cmp < 0) {
                                lowPos = midPos + 2;
                                //lowMatch = i;
                            } else {
                                highPos = midPos - 2;
                                //highMatch = i;
                            }
                            break compare;
                        }
                    }

                    for (; i < minLen; i++) {
                        byte cb = p_byteGet(page, compareLoc + i);
                        byte kb = key[i];
                        if (cb != kb) {
                            if ((cb & 0xff) < (kb & 0xff)) {
                                lowPos = midPos + 2;
                            } else {
                                highPos = midPos - 2;
                            }
                            break compare;
                        }
                    }
                }

                if (compareLen < keyLen) {
                    lowPos = midPos + 2;
                    //lowMatch = i & ~7;
                } else if (compareLen > keyLen) {
                    highPos = midPos - 2;
                    //highMatch = i & ~7;
                } else {
                    return midPos - startPos;
                }
            }

            if (lowPos > highPos) {
                break;
            }

            midPos = ((lowPos + highPos) >> 1) & ~1;
        }

        return ~(lowPos - startPos);
    }

    /**
     * Ensure binary search position is positive, for internal node.
     */
    static int internalPos(int pos) {
        return pos < 0 ? ~pos : (pos + 2);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    int compareKey(int pos, byte[] rightKey) throws IOException {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            if ((header & ENTRY_FRAGMENTED) != 0) {
                // Note: An optimized version wouldn't need to copy the whole key.
                byte[] leftKey = getDatabase().reconstructKey(page, loc, keyLen);
                return compareUnsigned(leftKey, rightKey);
            }
        }
        return p_compareKeysPageToArray(page, loc, keyLen, rightKey, 0, rightKey.length);
    }

    /**
     * Compares two node keys, in place if possible.
     *
     * @param leftLoc absolute location of left key
     * @param rightLoc absolute location of right key
     */
    static int compareKeys(Node left, int leftLoc, Node right, int rightLoc) throws IOException {
        final var leftPage = left.mPage;
        final var rightPage = right.mPage;

        int leftLen = p_byteGet(leftPage, leftLoc++);
        int rightLen = p_byteGet(rightPage, rightLoc++);

        c1: { // break out of this scope when both keys are in the page
            c2: { // break out of this scope when the left key is in the page
                if (leftLen >= 0) {
                    // Left key is tiny... break out and examine the right key.
                    leftLen++;
                    break c2;
                }

                int leftHeader = leftLen;
                leftLen = ((leftLen & 0x3f) << 8) | p_ubyteGet(leftPage, leftLoc++);
                if ((leftHeader & ENTRY_FRAGMENTED) == 0) {
                    // Left key is medium... break out and examine the right key.
                    break c2;
                }

                // Left key is fragmented...
                // Note: An optimized version wouldn't need to copy the whole key.
                byte[] leftKey = left.getDatabase().reconstructKey(leftPage, leftLoc, leftLen);

                if (rightLen >= 0) {
                    // Left key is fragmented, and right key is tiny.
                    rightLen++;
                } else {
                    int rightHeader = rightLen;
                    rightLen = ((rightLen & 0x3f) << 8) | p_ubyteGet(rightPage, rightLoc++);
                    if ((rightHeader & ENTRY_FRAGMENTED) != 0) {
                        // Right key is fragmented too.
                        // Note: An optimized version wouldn't need to copy the whole key.
                        byte[] rightKey = right.getDatabase()
                            .reconstructKey(rightPage, rightLoc, rightLen);
                        return compareUnsigned(leftKey, rightKey);
                    }
                }

                return -p_compareKeysPageToArray(rightPage, rightLoc, rightLen,
                                                 leftKey, 0, leftKey.length);
            } // end c2

            if (rightLen >= 0) {
                // Left key is tiny/medium, right key is tiny, and both fit in the page.
                rightLen++;
                break c1;
            }

            int rightHeader = rightLen;
            rightLen = ((rightLen & 0x3f) << 8) | p_ubyteGet(rightPage, rightLoc++);
            if ((rightHeader & ENTRY_FRAGMENTED) == 0) {
                // Left key is tiny/medium, right key is medium, and both fit in the page.
                break c1;
            }

            // Left key is tiny/medium, and right key is fragmented.
            // Note: An optimized version wouldn't need to copy the whole key.
            byte[] rightKey = right.getDatabase().reconstructKey(rightPage, rightLoc, rightLen);
            return p_compareKeysPageToArray(leftPage, leftLoc, leftLen,
                                            rightKey, 0, rightKey.length);
        } // end c1

        return p_compareKeysPageToPage(leftPage, leftLoc, leftLen, rightPage, rightLoc, rightLen);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @param stats {@literal [0]: full length, [1]: number of pages (>0 if fragmented)}
     */
    void retrieveKeyStats(int pos, long[] stats) throws IOException {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);

        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            if ((header & ENTRY_FRAGMENTED) != 0) {
                getDatabase().reconstruct(page, loc, keyLen, stats);
                return;
            }
        }

        stats[0] = keyLen;
        stats[1] = 0;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    byte[] retrieveKey(int pos) throws IOException {
        final var page = mPage;
        return retrieveKeyAtLoc(this, page, p_ushortGetLE(page, searchVecStart() + pos));
    }

    /**
     * @param loc absolute location of entry
     */
    byte[] retrieveKeyAtLoc(final /*P*/ byte[] page, int loc) throws IOException {
        return retrieveKeyAtLoc(this, page, loc);
    }

    /**
     * @param loc absolute location of entry
     */
    static byte[] retrieveKeyAtLoc(DatabaseAccess dbAccess, final /*P*/ byte[] page, int loc)
        throws IOException
    {
        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            if ((header & ENTRY_FRAGMENTED) != 0) {
                return dbAccess.getDatabase().reconstructKey(page, loc, keyLen);
            }
        }
        var key = new byte[keyLen];
        p_copyToArray(page, loc, key, 0, keyLen);
        return key;
    }

    /**
     * @param loc absolute location of entry
     * @param split store the results here, capturing the actual key if fragmented
     */
    private void retrieveKeyAtLoc(final /*P*/ byte[] page, int loc, Split split)
        throws IOException
    {
        byte[] fullKey, actualKey;
        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
            actualKey = new byte[keyLen];
            p_copyToArray(page, loc, actualKey, 0, keyLen);
            fullKey = actualKey;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            actualKey = new byte[keyLen];
            p_copyToArray(page, loc, actualKey, 0, keyLen);
            fullKey = actualKey;
            if ((header & ENTRY_FRAGMENTED) != 0) {
                fullKey = getDatabase().reconstructKey(page, loc, keyLen);
            }
        }
        split.setKey(fullKey, actualKey);
    }

    /**
     * @param loc absolute location of entry
     * @param akeyRef [0] is set to the actual key
     * @return false if key is fragmented and actual doesn't match original
     */
    private static boolean retrieveActualKeyAtLoc(final /*P*/ byte[] page, int loc,
                                                  final byte[][] akeyRef)
        throws IOException
    {
        boolean result = true;

        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            result = (header & ENTRY_FRAGMENTED) == 0;
        }
        var akey = new byte[keyLen];
        p_copyToArray(page, loc, akey, 0, keyLen);
        akeyRef[0] = akey;

        return result;
    }

    /**
     * Copies the key at the given position based on a limit. If equal, the
     * limitKey instance is returned. If beyond the limit, null is returned.
     *
     * @param pos position as provided by binarySearch; must be positive
     * @param limitKey comparison key
     * @param limitMode positive for LE behavior, negative for GE behavior
     */
    byte[] retrieveKeyCmp(int pos, byte[] limitKey, int limitMode) throws IOException {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);

            if ((header & ENTRY_FRAGMENTED) != 0) {
                byte[] key = getDatabase().reconstructKey(page, loc, keyLen);
                int cmp = compareUnsigned(key, limitKey);
                if (cmp == 0) {
                    return limitKey;
                } else {
                    return (cmp ^ limitMode) < 0 ? key : null;
                }
            }
        }

        int cmp = p_compareKeysPageToArray(page, loc, keyLen, limitKey, 0, limitKey.length);
        if (cmp == 0) {
            return limitKey;
        } else if ((cmp ^ limitMode) < 0) {
            var key = new byte[keyLen];
            p_copyToArray(page, loc, key, 0, keyLen);
            return key;
        } else {
            return null;
        }
    }

    /**
     * Used by UndoLog for decoding entries. Only works for non-fragmented values.
     *
     * @param loc absolute location of entry
     */
    static byte[][] retrieveKeyValueAtLoc(DatabaseAccess dbAccess,
                                          final /*P*/ byte[] page, int loc)
        throws IOException
    {
        int header = p_byteGet(page, loc++);

        int keyLen;
        byte[] key;
        copyKey: {
            if (header >= 0) {
                keyLen = header + 1;
            } else {
                keyLen = ((header & 0x3f) << 8) | p_ubyteGet(page, loc++);
                if ((header & ENTRY_FRAGMENTED) != 0) {
                    key = dbAccess.getDatabase().reconstructKey(page, loc, keyLen);
                    break copyKey;
                }
            }
            key = new byte[keyLen];
            p_copyToArray(page, loc, key, 0, keyLen);
        }

        return new byte[][] {key, retrieveLeafValueAtLoc(null, page, loc + keyLen)};
    }

    /**
     * Given an entry with a fragmented key (caller must verify this), retrieves the key and
     * returns a new entry with the full key encoded inline. The length of the key is encoded
     * in varint format instead of the usual key header. The value portion of entry is copied
     * immediately after the inline key, with the header stripped off if requested. This
     * format is expected to be used only by UndoLog.
     */
    static byte[] expandKeyAtLoc(DatabaseAccess dbAccess, /*P*/ byte[] page, int loc, int len,
                                 boolean stripValueHeader)
        throws IOException
    {
        int endLoc = loc + len;

        int keyLen = ((p_byteGet(page, loc++) & 0x3f) << 8) | p_ubyteGet(page, loc++);

        int valueLoc = loc + keyLen;
        int valueLen = endLoc - valueLoc;

        if (stripValueHeader) {
            int skip = 1;
            int header = p_byteGet(page, valueLoc);
            if (header < 0) {
                if ((header & 0x20) == 0) {
                    skip = 2;
                } else if (header != -1) {
                    skip = 3;
                }
            }
            valueLoc += skip;
            valueLen -= skip;
        }

        byte[] key = dbAccess.getDatabase().reconstructKey(page, loc, keyLen);
        int keyHeaderLen = Utils.calcUnsignedVarIntLength(key.length);

        var expanded = new byte[keyHeaderLen + key.length + valueLen];

        int offset = Utils.encodeUnsignedVarInt(expanded, 0, key.length);
        System.arraycopy(key, 0, expanded, offset, key.length);
        offset += key.length;
        p_copyToArray(page, valueLoc, expanded, offset, valueLen);

        return expanded;
    }

    /**
     * Returns a new key between the low key in this node and the given high key.
     *
     * @see Utils#midKey
     */
    private byte[] midKey(int lowPos, byte[] highKey) throws IOException {
        final var lowPage = mPage;
        int lowLoc = p_ushortGetLE(lowPage, searchVecStart() + lowPos);
        int lowKeyLen = p_byteGet(lowPage, lowLoc);
        if (lowKeyLen < 0) {
            // Note: An optimized version wouldn't need to copy the whole key.
            return Utils.midKey(retrieveKeyAtLoc(lowPage, lowLoc), highKey);
        } else {
            return p_midKeyLowPage(lowPage, lowLoc + 1, lowKeyLen + 1, highKey, 0);
        }
    }

    /**
     * Returns a new key between the given low key and the high key in this node.
     *
     * @see Utils#midKey
     */
    private byte[] midKey(byte[] lowKey, int highPos) throws IOException {
        final var highPage = mPage;
        int highLoc = p_ushortGetLE(highPage, searchVecStart() + highPos);
        int highKeyLen = p_byteGet(highPage, highLoc);
        if (highKeyLen < 0) {
            // Note: An optimized version wouldn't need to copy the whole key.
            return Utils.midKey(lowKey, retrieveKeyAtLoc(highPage, highLoc));
        } else {
            return p_midKeyHighPage(lowKey, 0, lowKey.length, highPage, highLoc + 1);
        }
    }

    /**
     * Returns a new key between the low key in this node and the high key of another node.
     *
     * @see Utils#midKey
     */
    byte[] midKey(int lowPos, Node highNode, int highPos) throws IOException {
        final var lowPage = mPage;
        int lowLoc = p_ushortGetLE(lowPage, searchVecStart() + lowPos);
        int lowKeyLen = p_byteGet(lowPage, lowLoc);
        if (lowKeyLen < 0) {
            // Note: An optimized version wouldn't need to copy the whole key.
            return highNode.midKey(retrieveKeyAtLoc(lowPage, lowLoc), highPos);
        }

        lowLoc++;
        lowKeyLen++;

        final var highPage = highNode.mPage;
        int highLoc = p_ushortGetLE(highPage, highNode.searchVecStart() + highPos);
        int highKeyLen = p_byteGet(highPage, highLoc);
        if (highKeyLen < 0) {
            // Note: An optimized version wouldn't need to copy the whole key.
            byte[] highKey = retrieveKeyAtLoc(highPage, highLoc);
            return p_midKeyLowPage(lowPage, lowLoc, lowKeyLen, highKey, 0);
        }

        return p_midKeyLowHighPage(lowPage, lowLoc, lowKeyLen, highPage, highLoc + 1);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @return Cursor.NOT_LOADED if value exists, null if ghost
     */
    byte[] hasLeafValue(int pos) {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        loc += keyLengthAtLoc(page, loc);
        return p_byteGet(page, loc) == -1 ? null : Cursor.NOT_LOADED;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @param stats {@literal [0]: full length, [1]: number of pages (>0 if fragmented)}
     */
    void retrieveLeafValueStats(int pos, long[] stats) throws IOException {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        loc += keyLengthAtLoc(page, loc);

        final int header = p_byteGet(page, loc++);

        int len;
        if (header >= 0) {
            len = header;
        } else {
            if ((header & 0x20) == 0) {
                len = 1 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc++));
            } else if (header != -1) {
                len = 1 + (((header & 0x0f) << 16)
                           | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
            } else {
                // ghost
                stats[0] = 0;
                stats[1] = 0;
                return;
            }
            if ((header & ENTRY_FRAGMENTED) != 0) {
                getDatabase().reconstruct(page, loc, len, stats);
                return;
            }
        }

        stats[0] = len;
        stats[1] = 0;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @return null if ghost
     */
    byte[] retrieveLeafValue(int pos) throws IOException {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        loc += keyLengthAtLoc(page, loc);
        return retrieveLeafValueAtLoc(this, page, loc);
    }

    static byte[] retrieveLeafValueAtLoc(DatabaseAccess dbAccess, /*P*/ byte[] page, int loc)
        throws IOException
    {
        final int header = p_byteGet(page, loc++);
        if (header == 0) {
            return EMPTY_BYTES;
        }

        int len;
        if (header >= 0) {
            len = header;
        } else {
            if ((header & 0x20) == 0) {
                len = 1 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc++));
            } else if (header != -1) {
                len = 1 + (((header & 0x0f) << 16)
                           | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
            } else {
                // ghost
                return null;
            }
            if ((header & ENTRY_FRAGMENTED) != 0) {
                return dbAccess.getDatabase().reconstruct(page, loc, len);
            }
        }

        var value = new byte[len];
        p_copyToArray(page, loc, value, 0, len);
        return value;
    }

    /**
     * Sets the cursor key and value references. If mode is key-only, then set value is
     * Cursor.NOT_LOADED for a value which exists, null if ghost.
     *
     * @param pos position as provided by binarySearch; must be positive
     * @param cursor key and value are updated
     */
    void retrieveLeafEntry(int pos, BTreeCursor cursor) throws IOException {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        int header = p_byteGet(page, loc++);

        int keyLen;
        byte[] key;
        copyKey: {
            if (header >= 0) {
                keyLen = header + 1;
            } else {
                keyLen = ((header & 0x3f) << 8) | p_ubyteGet(page, loc++);
                if ((header & ENTRY_FRAGMENTED) != 0) {
                    key = getDatabase().reconstructKey(page, loc, keyLen);
                    break copyKey;
                }
            }
            key = new byte[keyLen];
            p_copyToArray(page, loc, key, 0, keyLen);
        }

        loc += keyLen;
        cursor.mKey = key;

        byte[] value;
        if (cursor.mKeyOnly) {
            value = p_byteGet(page, loc) == -1 ? null : Cursor.NOT_LOADED;
        } else {
            value = retrieveLeafValueAtLoc(this, page, loc);
        }

        cursor.mValue = value;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    boolean isFragmentedKey(int pos) {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        return (p_byteGet(page, loc) & 0xc0) == 0xc0;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    boolean isFragmentedLeafValue(int pos) {
        final var page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        loc += keyLengthAtLoc(page, loc);
        int header = p_byteGet(page, loc);
        return ((header & 0xc0) == 0xc0) & (header < -1);
    }

    /**
     * Transactionally delete a leaf entry (but with no redo logging), replacing the value with
     * a ghost. When read back, it is interpreted as null. Ghosts are used by transactional
     * deletes, to ensure that they are not visible by cursors in other transactions. They need
     * to acquire a lock first. When the original transaction commits, it deletes all the
     * ghosted entries it created.
     *
     * <p>Caller must hold commit lock and exclusive latch on node.
     *
     * @param pos position as provided by binarySearch; must be positive
     */
    void txnDeleteLeafEntry(LocalTransaction txn, BTree tree, byte[] key, int keyHash, int pos)
        throws IOException
    {
        // Allocate early, in case out of memory.
        var frame = new GhostFrame();

        final var page = mPage;
        final int entryLoc = p_ushortGetLE(page, searchVecStart() + pos);
        int loc = entryLoc;

        // Skip the key.
        loc += keyLengthAtLoc(page, loc);

        // Read value header.
        final int valueHeaderLoc = loc;
        int header = p_byteGet(page, loc++);

        doUndo: {
            // Note: Similar to leafEntryLengthAtLoc.
            if (header >= 0) {
                // Short value. Move loc to just past end of value.
                loc += header;
            } else {
                // Medium value. Move loc to just past end of value.
                if ((header & 0x20) == 0) {
                    loc += 2 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc));
                } else if (header != -1) {
                    loc += 3 + (((header & 0x0f) << 16)
                                | (p_ubyteGet(page, loc) << 8) | p_ubyteGet(page, loc + 1));
                } else {
                    // Already a ghost, so nothing to undo.
                    break doUndo;
                }

                if ((header & ENTRY_FRAGMENTED) != 0) {
                    int valueStartLoc = valueHeaderLoc + 2 + ((header & 0x20) >> 5);
                    FragmentedTrash.add
                        (tree.mDatabase.fragmentedTrash(),
                         txn, tree.mId, page,
                         entryLoc, valueHeaderLoc - entryLoc,  // keyStart, keyLen
                         valueStartLoc, loc - valueStartLoc);  // valueStart, valueLen
                    break doUndo;
                }
            }

            // Copy whole entry into undo log.
            txn.pushUndoStore(tree.mId, UndoLog.OP_UNDELETE, page, entryLoc, loc - entryLoc);
        }

        frame.bind(this, pos);

        // Ghost will be deleted later when locks are released.
        tree.mLockManager.ghosted(tree.mId, key, keyHash, frame);

        // Replace value with ghost.
        p_bytePut(page, valueHeaderLoc, -1);
        spaceFreed(valueHeaderLoc + 1, loc);
    }

    /**
     * Copies existing entry to undo log prior to it being updated. Fragmented values are added
     * to the trash and the fragmented bit is cleared. Caller must hold commit lock and
     * exclusive latch on node.
     *
     * @param pos position as provided by binarySearch; must be positive
     */
    void txnPreUpdateLeafEntry(LocalTransaction txn, BTree tree, int pos)
        throws IOException
    {
        final var page = mPage;
        final int entryLoc = p_ushortGetLE(page, searchVecStart() + pos);
        int loc = entryLoc;

        // Skip the key.
        loc += keyLengthAtLoc(page, loc);

        // Read value header.
        final int valueHeaderLoc = loc;
        int header = p_byteGet(page, loc++);

        examineEntry: {
            // Note: Similar to leafEntryLengthAtLoc.
            if (header >= 0) {
                // Short value. Move loc to just past end of value.
                loc += header;
                break examineEntry;
            } else {
                // Medium value. Move loc to just past end of value.
                if ((header & 0x20) == 0) {
                    loc += 2 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc));
                } else if (header != -1) {
                    loc += 3 + (((header & 0x0f) << 16)
                                | (p_ubyteGet(page, loc) << 8) | p_ubyteGet(page, loc + 1));
                } else {
                    // Already a ghost, so nothing to undo.
                    break examineEntry;
                }

                if ((header & ENTRY_FRAGMENTED) != 0) {
                    int valueStartLoc = valueHeaderLoc + 2 + ((header & 0x20) >> 5);
                    FragmentedTrash.add
                        (tree.mDatabase.fragmentedTrash(),
                         txn, tree.mId, page,
                         entryLoc, valueHeaderLoc - entryLoc,  // keyStart, keyLen
                         valueStartLoc, loc - valueStartLoc);  // valueStart, valueLen
                    // Clearing the fragmented bit prevents the update from double-deleting the
                    // fragments, and it also allows the old entry slot to be re-used.
                    p_bytePut(page, valueHeaderLoc, header & ~ENTRY_FRAGMENTED);
                    return;
                }
            }
        }

        // Copy whole entry into undo log.
        txn.pushUndoStore(tree.mId, UndoLog.OP_UNUPDATE, page, entryLoc, loc - entryLoc);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    long childId(int pos) {
        return childIdByOffset(mPage, searchVecEnd() + 2 + (pos << 2));
    }

    /**
     * @param offset page offset
     */
    private static long childIdByOffset(/*P*/ byte[] page, int offset) {
        return p_longGetLE(page, offset) & 0xffff_ffff_ffffL;
    }

    /**
     * Retrieves the count of entries for the child node at the given position, or negative if
     * unknown. Counts are only applicable to bottom internal nodes, and are invalidated when
     * the node is dirty.
     *
     * @param pos position as provided by binarySearch; must be positive
     */
    int childEntryCount(int pos) {
        return p_ushortGetLE(mPage, searchVecEnd() + (2 + 6) + (pos << 2)) - 1;
    }

    /**
     * Stores the count of entries for the child node at the given position. Counts are only
     * applicable to bottom internal nodes, and are invalidated when the node is dirty.
     *
     * @param pos position as provided by binarySearch; must be positive
     * @param count 0..65534
     * @see #countNonGhostKeys
     */
    void storeChildEntryCount(int pos, int count) {
        if (count < 65535) { // safety check
            p_shortPutLE(mPage, searchVecEnd() + (2 + 6) + (pos << 2), count + 1);
        }
    }

    /**
     * @return length of encoded entry at given location
     */
    static int leafEntryLengthAtLoc(/*P*/ byte[] page, final int entryLoc) {
        int loc = entryLoc + keyLengthAtLoc(page, entryLoc);
        int header = p_byteGet(page, loc++);
        if (header >= 0) {
            loc += header;
        } else {
            if ((header & 0x20) == 0) {
                loc += 2 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc));
            } else if (header != -1) {
                loc += 3 + (((header & 0x0f) << 16)
                            | (p_ubyteGet(page, loc) << 8) | p_ubyteGet(page, loc + 1));
            }
        }
        return loc - entryLoc;
    }

    /**
     * @return length of encoded key at given location, including the header
     */
    static int keyLengthAtLoc(/*P*/ byte[] page, final int keyLoc) {
        int header = p_byteGet(page, keyLoc);
        return (header >= 0 ? header
                : (((header & 0x3f) << 8) | p_ubyteGet(page, keyLoc + 1))) + 2;
    }

    /**
     * @param frame optional frame which is bound to this node; only used for rebalancing
     * @param pos complement of position as provided by binarySearch; must be positive
     * @param okey original key
     */
    void insertLeafEntry(CursorFrame frame, BTree tree, int pos, byte[] okey, byte[] value)
        throws IOException
    {
        final LocalDatabase db = tree.mDatabase;

        byte[] akey = okey;
        int encodedKeyLen = calculateAllowedKeyLength(db, okey);

        if (encodedKeyLen < 0) {
            // Key must be fragmented.
            akey = db.fragmentKey(okey);
            encodedKeyLen = 2 + akey.length;
        }

        try {
            int encodedLen = encodedKeyLen + calculateLeafValueLength(value);

            int vfrag;
            if (encodedLen <= db.mMaxEntrySize) {
                vfrag = 0;
            } else {
                value = db.fragment(value, value.length,
                                    db.mMaxFragmentedEntrySize - encodedKeyLen);
                if (value == null) {
                    throw new AssertionError();
                }
                encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);
                vfrag = ENTRY_FRAGMENTED;
            }

            try {
                int entryLoc = createLeafEntry(frame, tree, pos, encodedLen);

                if (entryLoc < 0) {
                    splitLeafAndCreateEntry(tree, okey, akey, vfrag, value, encodedLen, pos, true);
                } else {
                    copyToLeafEntry(okey, akey, vfrag, value, entryLoc);
                }
            } catch (Throwable e) {
                if (vfrag == ENTRY_FRAGMENTED) {
                    cleanupFragments(e, value);
                }
                throw e;
            }
        } catch (Throwable e) {
            if (okey != akey) {
                cleanupFragments(e, akey);
            }
            throw e;
        }
    }

    /**
     * @param frame optional frame which is bound to this node; only used for rebalancing
     * @param pos complement of position as provided by binarySearch; must be positive
     * @param okey original key
     */
    void insertBlankLeafEntry(CursorFrame frame, BTree tree, int pos, byte[] okey, long vlength)
        throws IOException
    {
        if (vlength < 0) {
            // This method is called by BTreeValue.action to create a new value.
            throw new IllegalArgumentException("Length overflow");
        }

        final LocalDatabase db = tree.mDatabase;

        byte[] akey = okey;
        int encodedKeyLen = calculateAllowedKeyLength(db, okey);

        if (encodedKeyLen < 0) {
            // Key must be fragmented.
            akey = db.fragmentKey(okey);
            encodedKeyLen = 2 + akey.length;
        }

        try {
            long longEncodedLen = encodedKeyLen + calculateLeafValueLength(vlength);
            int encodedLen;

            int vfrag;
            byte[] value;
            if (longEncodedLen <= db.mMaxEntrySize && ((int) vlength) >= 0) {
                vfrag = 0;
                value = new byte[(int) vlength];
                encodedLen = (int) longEncodedLen;
            } else {
                value = db.fragment(null, vlength, db.mMaxFragmentedEntrySize - encodedKeyLen);
                if (value == null) {
                    throw new AssertionError();
                }
                encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);
                vfrag = ENTRY_FRAGMENTED;
            }

            try {
                int entryLoc = createLeafEntry(frame, tree, pos, encodedLen);

                if (entryLoc < 0) {
                    splitLeafAndCreateEntry(tree, okey, akey, vfrag, value, encodedLen, pos, true);
                } else {
                    copyToLeafEntry(okey, akey, vfrag, value, entryLoc);
                }
            } catch (Throwable e) {
                if (vfrag == ENTRY_FRAGMENTED) {
                    cleanupFragments(e, value);
                }
                throw e;
            }
        } catch (Throwable e) {
            if (okey != akey) {
                cleanupFragments(e, akey);
            }
            throw e;
        }
    }

    /**
     * @param frame optional frame which is bound to this node; only used for rebalancing
     * @param pos complement of position as provided by binarySearch; must be positive
     * @param okey original key
     */
    void insertFragmentedLeafEntry(CursorFrame frame,
                                   BTree tree, int pos, byte[] okey, byte[] value)
        throws IOException
    {
        final LocalDatabase db = tree.mDatabase;

        byte[] akey = okey;
        int encodedKeyLen = calculateAllowedKeyLength(db, okey);

        if (encodedKeyLen < 0) {
            // Key must be fragmented.
            akey = db.fragmentKey(okey);
            encodedKeyLen = 2 + akey.length;
        }

        try {
            int encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);

            int entryLoc = createLeafEntry(frame, tree, pos, encodedLen);

            if (entryLoc < 0) {
                splitLeafAndCreateEntry
                    (tree, okey, akey, ENTRY_FRAGMENTED, value, encodedLen, pos, true);
            } else {
                copyToLeafEntry(okey, akey, ENTRY_FRAGMENTED, value, entryLoc);
            }
        } catch (Throwable e) {
            if (okey != akey) {
                cleanupFragments(e, akey);
            }
            throw e;
        }
    }

    private void panic(Throwable cause) {
        try {
            getDatabase().close(cause);
        } catch (Throwable e) {
            // Ignore.
        }
    }

    void cleanupFragments(Throwable cause, byte[] fragmented) {
        if (fragmented != null) {
            var copy = p_transfer(fragmented);
            try {
                getDatabase().deleteFragments(copy, 0, fragmented.length);
            } catch (Throwable e) {
                Utils.suppress(cause, e);
                panic(cause);
            } finally {
                p_delete(copy);
            }
        }
    }

    /**
     * @param frame optional frame which is bound to this node; only used for rebalancing
     * @param pos complement of position as provided by binarySearch; must be positive
     * @return Location for newly allocated entry, already pointed to by search vector, or
     * negative if leaf must be split. Complement of negative value is available leaf bytes.
     */
    int createLeafEntry(final CursorFrame frame, BTree tree, int pos, final int encodedLen) {
        int searchVecStart = searchVecStart();
        int searchVecEnd = searchVecEnd();

        int leftSpace = searchVecStart - leftSegTail();
        int rightSpace = rightSegTail() - searchVecEnd - 1;

        final var page = mPage;

        int entryLoc;
        alloc: {
            if (pos < ((searchVecEnd - searchVecStart + 2) >> 1)) {
                // Shift subset of search vector left or prepend.
                if ((leftSpace -= 2) >= 0 &&
                    (entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0)
                {
                    p_copy(page, searchVecStart, page, searchVecStart -= 2, pos);
                    pos += searchVecStart;
                    searchVecStart(searchVecStart);
                    break alloc;
                }
                // Need to make space, but restore leftSpace value first.
                leftSpace += 2;
            } else {
                // Shift subset of search vector right or append.
                if ((rightSpace -= 2) >= 0 &&
                    (entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0)
                {
                    pos += searchVecStart;
                    p_copy(page, pos, page, pos + 2, (searchVecEnd += 2) - pos);
                    searchVecEnd(searchVecEnd);
                    break alloc;
                }
                // Need to make space, but restore rightSpace value first.
                rightSpace += 2;
            }

            // Compute remaining space surrounding search vector after insert completes.
            int remaining = leftSpace + rightSpace - encodedLen - 2;
            int garbage = garbage();

            if (garbage > remaining) {
                // Do full compaction and free up the garbage, or else node must be split.

                if (garbage + remaining >= 0) {
                    return compactLeaf(encodedLen, pos, true);
                }

                // Node compaction won't make enough room, but attempt to rebalance
                // before splitting.

                CursorFrame parentFrame;
                if (frame != null && (parentFrame = frame.mParentFrame) != null) {
                    int result = tryRebalanceLeaf(tree, parentFrame, pos, encodedLen, -remaining);
                    if (result > 0) {
                        // Rebalance worked.
                        return result;
                    }
                }

                // Return the total available space.
                return ~(garbage + leftSpace + rightSpace);
            }

            int vecLen = searchVecEnd - searchVecStart + 2;
            int newSearchVecStart;

            if (remaining > 0 || (rightSegTail() & 1) != 0) {
                // Re-center search vector, biased to the right, ensuring proper alignment.
                newSearchVecStart = (rightSegTail() - vecLen + (1 - 2) - (remaining >> 1)) & ~1;

                // Allocate entry from left segment.
                entryLoc = leftSegTail();
                leftSegTail(entryLoc + encodedLen);
            } else if ((leftSegTail() & 1) == 0) {
                // Move search vector left, ensuring proper alignment.
                newSearchVecStart = leftSegTail() + ((remaining >> 1) & ~1);

                // Allocate entry from right segment.
                entryLoc = rightSegTail() - encodedLen + 1;
                rightSegTail(entryLoc - 1);
            } else {
                // Search vector is misaligned, so do full compaction.
                return compactLeaf(encodedLen, pos, true);
            }

            p_copies(page,
                     searchVecStart, newSearchVecStart, pos,
                     searchVecStart + pos, newSearchVecStart + pos + 2, vecLen - pos);

            pos += newSearchVecStart;
            searchVecStart(newSearchVecStart);
            searchVecEnd(newSearchVecStart + vecLen);
        }

        // Write pointer to new allocation.
        p_shortPutLE(page, pos, entryLoc);
        return entryLoc;
    }

    /**
     * Attempt to make room in this node by moving entries to the left or right sibling
     * node. First determines if moving entries to the sibling node is allowed and would free
     * up enough space. Next, attempts to latch parent and child nodes without waiting,
     * avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param pos position to insert into
     * @param insertLen encoded length of entry to insert
     * @param minAmount minimum amount of bytes to move to make room
     * @return 0 if try failed, or entry location of re-used slot
     */
    private int tryRebalanceLeaf(BTree tree, CursorFrame parentFrame,
                                 int pos, int insertLen, int minAmount)
    {
        int result;
        // "Randomly" choose left or right node first.
        if ((id() & 1) == 0) {
            result = tryRebalanceLeafLeft(tree, parentFrame, pos, insertLen, minAmount);
            if (result <= 0) {
                result = tryRebalanceLeafRight(tree, parentFrame, pos, insertLen, minAmount);
            }
        } else {
            result = tryRebalanceLeafRight(tree, parentFrame, pos, insertLen, minAmount);
            if (result <= 0) {
                result = tryRebalanceLeafLeft(tree, parentFrame, pos, insertLen, minAmount);
            }
        }
        return result;
    }

    /**
     * Attempt to make room in this node by moving entries to the left sibling node. First
     * determines if moving entries to the left node is allowed and would free up enough space.
     * Next, attempts to latch parent and child nodes without waiting, avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param pos position to insert into; this position cannot move left
     * @param insertLen encoded length of entry to insert
     * @param minAmount minimum amount of bytes to move to make room
     * @return 0 if try failed, or entry location of re-used slot
     */
    private int tryRebalanceLeafLeft(BTree tree, CursorFrame parentFrame,
                                     int pos, int insertLen, int minAmount)
    {
        final var rightPage = mPage;

        int moveAmount = 0;
        final int lastSearchVecLoc;
        int insertLoc = 0;
        int insertSlack = Integer.MAX_VALUE;

        check: {
            int searchVecLoc = searchVecStart();
            int searchVecEnd = searchVecLoc + pos - 2;

            // Note that loop doesn't examine last entry. At least one must remain.
            for (; searchVecLoc < searchVecEnd; searchVecLoc += 2) {
                int entryLoc = p_ushortGetLE(rightPage, searchVecLoc);
                int encodedLen = leafEntryLengthAtLoc(rightPage, entryLoc);

                // Find best fitting slot for insert entry.
                int slack = encodedLen - insertLen;
                if (slack >= 0 && slack < insertSlack) {
                    insertLoc = entryLoc;
                    insertSlack = slack;
                }

                moveAmount += encodedLen + 2;
                if (moveAmount >= minAmount && insertLoc != 0) {
                    lastSearchVecLoc = searchVecLoc + 2; // +2 to be exclusive
                    break check;
                }
            }

            return 0;
        }

        final Node parent = parentFrame.tryAcquireExclusive();
        if (parent == null) {
            return 0;
        }

        final int childPos = parentFrame.mNodePos;
        if (childPos <= 0
            || parent.mSplit != null
            || parent.mCachedState != mCachedState)
        {
            // No left child or sanity checks failed.
            parent.releaseExclusive();
            return 0;
        }

        final Node left;
        try {
            left = parent.tryLatchChildNotSplit(childPos - 2);
        } catch (IOException e) {
            return 0;
        }

        if (left == null) {
            parent.releaseExclusive();
            return 0;
        }

        // Notice that try-finally pattern is not used to release the latches. An uncaught
        // exception can only be caused by a bug. Leaving the latches held prevents database
        // corruption from being persisted.

        final byte[] newKey;
        final int newKeyLen;
        final /*P*/ byte[] parentPage;
        final int parentKeyLoc;
        final int parentKeyGrowth;

        check: {
            try {
                int leftAvail = left.availableLeafBytes();
                if (leftAvail >= moveAmount) {
                    // Parent search key will be updated, so verify that it has room.
                    int highPos = lastSearchVecLoc - searchVecStart();
                    newKey = midKey(highPos - 2, this, highPos);
                    // Only attempt rebalance if new key doesn't need to be fragmented.
                    newKeyLen = calculateAllowedKeyLength(tree.mDatabase, newKey);
                    if (newKeyLen > 0) {
                        parentPage = parent.mPage;
                        parentKeyLoc = p_ushortGetLE
                            (parentPage, parent.searchVecStart() + childPos - 2);
                        parentKeyGrowth = newKeyLen - keyLengthAtLoc(parentPage, parentKeyLoc);
                        if (parentKeyGrowth <= 0 ||
                            parentKeyGrowth <= parent.availableInternalBytes())
                        {
                            // Parent has room for the new search key, so proceed with rebalancing.
                            break check;
                        }
                    }
                }
            } catch (IOException e) {
                // Caused by failed read of a large key. Abort the rebalance attempt.
            }
            left.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        try {
            if (tree.mDatabase.markDirty(tree, left)) {
                parent.updateChildRefId(childPos - 2, left.id());
            }
        } catch (IOException e) {
            left.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        // Update the parent key.
        if (parentKeyGrowth <= 0) {
            encodeNormalKey(newKey, parentPage, parentKeyLoc);
            parent.garbage(parent.garbage() - parentKeyGrowth);
        } else {
            parent.updateInternalKey(childPos - 2, parentKeyGrowth, newKey, newKeyLen);
        }

        int garbageAccum = 0;
        int searchVecLoc = searchVecStart();
        final int lastPos = lastSearchVecLoc - searchVecLoc;

        for (; searchVecLoc < lastSearchVecLoc; searchVecLoc += 2) {
            int entryLoc = p_ushortGetLE(rightPage, searchVecLoc);
            int encodedLen = leafEntryLengthAtLoc(rightPage, entryLoc);
            int leftEntryLoc = left.createLeafEntry
                (null, tree, left.highestLeafPos() + 2, encodedLen);
            // Note: Must access left page each time, since compaction can replace it.
            p_copy(rightPage, entryLoc, left.mPage, leftEntryLoc, encodedLen);
            garbageAccum += encodedLen;
        }

        garbage(garbage() + garbageAccum);
        searchVecStart(lastSearchVecLoc);

        // Fix cursor positions or move them to the left node.
        final int leftEndPos = left.highestLeafPos() + 2;
        for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            int mask = framePos >> 31;
            int newPos = (framePos ^ mask) - lastPos;
            // This checks for nodes which should move and also includes not-found frames at
            // the low position. They might need to move just higher than the left node high
            // position, because the parent key has changed. A new search would position the
            // search there. Note that tryRebalanceLeafRight has an identical check, after
            // applying De Morgan's law. Because the chosen parent node is not strictly the
            // lowest from the right, a comparison must be made to the actual new parent node.
            byte[] frameKey;
            if (newPos < 0 |
                ((newPos == 0 & mask != 0) &&
                 ((frameKey = frame.mNotFoundKey) != null &&
                  compareUnsigned(frameKey, newKey) < 0)))
            {
                frame.rebind(left, (leftEndPos + newPos) ^ mask);
                frame.adjustParentPosition(-2);
            } else {
                frame.mNodePos = newPos ^ mask;
            }
            frame = prev;
        }

        left.releaseExclusive();
        parent.releaseExclusive();

        // Expand search vector for inserted entry and write pointer to the re-used slot.
        garbage(garbage() - insertLen);
        pos -= lastPos;
        int searchVecStart = searchVecStart();
        p_copy(rightPage, searchVecStart, rightPage, searchVecStart -= 2, pos);
        searchVecStart(searchVecStart);
        p_shortPutLE(rightPage, searchVecStart + pos, insertLoc);
        return insertLoc;
    }

    /**
     * Attempt to make room in this node by moving entries to the right sibling node. First
     * determines if moving entries to the right node is allowed and would free up enough space.
     * Next, attempts to latch parent and child nodes without waiting, avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param pos position to insert into; this position cannot move right
     * @param insertLen encoded length of entry to insert
     * @param minAmount minimum amount of bytes to move to make room
     * @return 0 if try failed, or entry location of re-used slot
     */
    private int tryRebalanceLeafRight(BTree tree, CursorFrame parentFrame,
                                      int pos, int insertLen, int minAmount)
    {
        final var leftPage = mPage;

        int moveAmount = 0;
        final int firstSearchVecLoc;
        int insertLoc = 0;
        int insertSlack = Integer.MAX_VALUE;

        check: {
            int searchVecStart = searchVecStart() + pos;
            int searchVecLoc = searchVecEnd();

            // Note that loop doesn't examine first entry. At least one must remain.
            for (; searchVecLoc > searchVecStart; searchVecLoc -= 2) {
                int entryLoc = p_ushortGetLE(leftPage, searchVecLoc);
                int encodedLen = leafEntryLengthAtLoc(leftPage, entryLoc);

                // Find best fitting slot for insert entry.
                int slack = encodedLen - insertLen;
                if (slack >= 0 && slack < insertSlack) {
                    insertLoc = entryLoc;
                    insertSlack = slack;
                }

                moveAmount += encodedLen + 2;
                if (moveAmount >= minAmount && insertLoc != 0) {
                    firstSearchVecLoc = searchVecLoc;
                    break check;
                }
            }

            return 0;
        }

        final Node parent = parentFrame.tryAcquireExclusive();
        if (parent == null) {
            return 0;
        }

        final int childPos = parentFrame.mNodePos;
        if (childPos >= parent.highestInternalPos()
            || parent.mSplit != null
            || parent.mCachedState != mCachedState)
        {
            // No right child or sanity checks failed.
            parent.releaseExclusive();
            return 0;
        }

        final Node right;
        try {
            right = parent.tryLatchChildNotSplit(childPos + 2);
        } catch (IOException e) {
            return 0;
        }

        if (right == null) {
            parent.releaseExclusive();
            return 0;
        }

        // Notice that try-finally pattern is not used to release the latches. An uncaught
        // exception can only be caused by a bug. Leaving the latches held prevents database
        // corruption from being persisted.

        final byte[] newKey;
        final int newKeyLen;
        final /*P*/ byte[] parentPage;
        final int parentKeyLoc;
        final int parentKeyGrowth;

        check: {
            try {
                int rightAvail = right.availableLeafBytes();
                if (rightAvail >= moveAmount) {
                    // Parent search key will be updated, so verify that it has room.
                    int highPos = firstSearchVecLoc - searchVecStart();
                    newKey = midKey(highPos - 2, this, highPos);
                    // Only attempt rebalance if new key doesn't need to be fragmented.
                    newKeyLen = calculateAllowedKeyLength(tree.mDatabase, newKey);
                    if (newKeyLen > 0) {
                        parentPage = parent.mPage;
                        parentKeyLoc = p_ushortGetLE
                            (parentPage, parent.searchVecStart() + childPos);
                        parentKeyGrowth = newKeyLen - keyLengthAtLoc(parentPage, parentKeyLoc);
                        if (parentKeyGrowth <= 0 ||
                            parentKeyGrowth <= parent.availableInternalBytes())
                        {
                            // Parent has room for the new search key, so proceed with rebalancing.
                            break check;
                        }
                    }
                }
            } catch (IOException e) {
                // Caused by failed read of a large key. Abort the rebalance attempt.
            }
            right.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        try {
            if (tree.mDatabase.markDirty(tree, right)) {
                parent.updateChildRefId(childPos + 2, right.id());
            }
        } catch (IOException e) {
            right.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        // Update the parent key.
        if (parentKeyGrowth <= 0) {
            encodeNormalKey(newKey, parentPage, parentKeyLoc);
            parent.garbage(parent.garbage() - parentKeyGrowth);
        } else {
            parent.updateInternalKey(childPos, parentKeyGrowth, newKey, newKeyLen);
        }

        int garbageAccum = 0;
        int searchVecLoc = searchVecEnd();
        final int moved = searchVecLoc - firstSearchVecLoc + 2;

        for (; searchVecLoc >= firstSearchVecLoc; searchVecLoc -= 2) {
            int entryLoc = p_ushortGetLE(leftPage, searchVecLoc);
            int encodedLen = leafEntryLengthAtLoc(leftPage, entryLoc);
            int rightEntryLoc = right.createLeafEntry(null, tree, 0, encodedLen);
            // Note: Must access right page each time, since compaction can replace it.
            p_copy(leftPage, entryLoc, right.mPage, rightEntryLoc, encodedLen);
            garbageAccum += encodedLen;
        }

        garbage(garbage() + garbageAccum);
        searchVecEnd(firstSearchVecLoc - 2);

        // Fix cursor positions in the right node.
        for (CursorFrame frame = right.mLastCursorFrame; frame != null; ) {
            int framePos = frame.mNodePos;
            int mask = framePos >> 31;
            frame.mNodePos = ((framePos ^ mask) + moved) ^ mask;
            frame = frame.mPrevCousin;
        }

        // Move affected cursor frames to the right node.
        final int leftEndPos = firstSearchVecLoc - searchVecStart();
        for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            int mask = framePos >> 31;
            int newPos = (framePos ^ mask) - leftEndPos;
            // This checks for nodes which should move, but it excludes not-found frames at the
            // high position. They might otherwise move to position zero of the right node, but
            // the parent key has changed. A new search would position the frame just beyond
            // the high position of the left node, which is where it is now. Note that
            // tryRebalanceLeafLeft has an identical check, after applying De Morgan's law.
            // Because the chosen parent node is not strictly the lowest from the right, a
            // comparison must be made to the actual new parent node.
            byte[] frameKey;
            if (newPos >= 0 &
                ((newPos != 0 | mask == 0) ||
                 ((frameKey = frame.mNotFoundKey) != null &&
                  compareUnsigned(frameKey, newKey) >= 0)))
            {
                frame.rebind(right, newPos ^ mask);
                frame.adjustParentPosition(+2);
            }
            frame = prev;
        }

        right.releaseExclusive();
        parent.releaseExclusive();

        // Expand search vector for inserted entry and write pointer to the re-used slot.
        garbage(garbage() - insertLen);
        pos += searchVecStart();
        int newSearchVecEnd = searchVecEnd() + 2;
        p_copy(leftPage, pos, leftPage, pos + 2, newSearchVecEnd - pos);
        searchVecEnd(newSearchVecEnd);
        p_shortPutLE(leftPage, pos, insertLoc);
        return insertLoc;
    }

    /**
     * Insert into an internal node following a child node split. This parent node and child
     * node must have an exclusive latch held. Child latch is always released, and an exception
     * releases the parent latch too.
     *
     * @param frame optional frame which is bound to this node; only used for rebalancing
     * @param keyPos position to insert split key
     * @param splitChild child node which split
     */
    void insertSplitChildRef(final CursorFrame frame, BTree tree, int keyPos, Node splitChild)
        throws IOException
    {
        final Split split = splitChild.mSplit;
        final Node newChild = splitChild.rebindSplitFrames(split);

        //final Node leftChild;
        final Node rightChild;
        int newChildPos = keyPos >> 1;
        if (split.mSplitRight) {
            //leftChild = splitChild;
            rightChild = newChild;
            newChildPos++;
        } else {
            //leftChild = newChild;
            rightChild = splitChild;
        }

        // Positions of frames higher than split key need to be incremented.
        for (CursorFrame f = mLastCursorFrame; f != null; ) {
            int fPos = f.mNodePos;
            if (fPos > keyPos) {
                f.mNodePos = fPos + 2;
            }
            f = f.mPrevCousin;
        }

        // Positions of frames equal to split key are in the split itself. Only
        // frames for the right split need to be incremented.
        for (CursorFrame childFrame = rightChild.mLastCursorFrame; childFrame != null; ) {
            childFrame.adjustParentPosition(+2);
            childFrame = childFrame.mPrevCousin;
        }

        InResult result;
        try {
            result = new InResult();
            // Note: Invocation of createInternalEntry may cause splitInternal to be called,
            // which in turn might throw a recoverable exception.
            createInternalEntry(frame, result, tree, keyPos, split.splitKeyEncodedLength(),
                                newChildPos << 3, true);
        } catch (Throwable e) {
            // Undo the earlier changes. They were performed early because the call to
            // createInternalEntry depends on them, even for child node bindings. A rebalance
            // operation will access the child nodes.

            try {
                for (CursorFrame childFrame = rightChild.mLastCursorFrame; childFrame != null; ) {
                    childFrame.adjustParentPosition(-2);
                    childFrame = childFrame.mPrevCousin;
                }

                for (CursorFrame f = mLastCursorFrame; f != null; ) {
                    int fPos = f.mNodePos;
                    if (fPos > keyPos) {
                        f.mNodePos = fPos - 2;
                    }
                    f = f.mPrevCousin;
                }

                splitChild.unrebindSplitFrames(split, newChild);

                splitChild.releaseExclusive();
                newChild.releaseExclusive();
                releaseExclusive();
            } catch (Throwable e2) {
                Utils.suppress(e, e2);
                panic(e);
            }

            throw e;
        }

        splitChild.mSplit = null;

        // Write new child id.
        p_longPutLE(result.mPage, result.mNewChildLoc, newChild.id());

        int entryLoc = result.mEntryLoc;
        if (entryLoc < 0) {
            // If loc is negative, then node was split and new key was chosen to be promoted.
            // It must be written into the new split.
            mSplit.setKey(split);
        } else {
            // Write key entry itself.
            split.copySplitKeyToParent(result.mPage, entryLoc);
        }

        splitChild.releaseExclusive();
        newChild.releaseExclusive();

        try {
            // Split complete, so allow new node to be evictable.
            newChild.makeEvictable();
        } catch (Throwable e) {
            releaseExclusive();
            throw e;
        }
    }

    /**
     * Insert into an internal node following a child node split. This parent
     * node and child node must have an exclusive latch held.
     *
     * @param frame optional frame which is bound to this node; only used for rebalancing
     * @param result return result stored here; if node was split, key and entry loc is -1 if
     * new key was promoted to parent
     * @param keyPos 2-based position
     * @param newChildPos 8-based position
     * @param allowSplit true if this internal node can be split as a side-effect
     * @throws AssertionError if entry must be split to make room but split is not allowed
     */
    private void createInternalEntry(final CursorFrame frame, InResult result,
                                     BTree tree, int keyPos, int encodedLen,
                                     int newChildPos, boolean allowSplit)
        throws IOException
    {
        int searchVecStart = searchVecStart();
        int searchVecEnd = searchVecEnd();

        int leftSpace = searchVecStart - leftSegTail();
        int rightSpace = rightSegTail() - searchVecEnd
            - ((searchVecEnd - searchVecStart) << 2) - 17;

        var page = mPage;

        int entryLoc;
        alloc: {
            // Need to make room for one new search vector entry (2 bytes) and one new child
            // id entry (8 bytes). Determine which shift operations minimize movement.
            if (newChildPos < ((3 * (searchVecEnd - searchVecStart + 2) + keyPos + 8) >> 1)) {
                // Attempt to shift search vector left by 10, shift child ids left by 8.

                if ((leftSpace -= 10) >= 0 &&
                    (entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0)
                {
                    p_copy(page, searchVecStart, page, searchVecStart - 10, keyPos);
                    p_copy(page, searchVecStart + keyPos,
                           page, searchVecStart + keyPos - 8,
                           searchVecEnd - searchVecStart + 2 - keyPos + newChildPos);
                    searchVecStart(searchVecStart -= 10);
                    keyPos += searchVecStart;
                    searchVecEnd(searchVecEnd -= 8);
                    newChildPos += searchVecEnd + 2;
                    break alloc;
                }

                // Need to make space, but restore leftSpace value first.
                leftSpace += 10;
            } else {
                // Attempt to shift search vector left by 2, shift child ids right by 8.

                leftSpace -= 2;
                rightSpace -= 8;

                if (leftSpace >= 0 && rightSpace >= 0 &&
                    (entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0)
                {
                    p_copy(page, searchVecStart, page, searchVecStart -= 2, keyPos);
                    searchVecStart(searchVecStart);
                    keyPos += searchVecStart;
                    p_copy(page, searchVecEnd + newChildPos + 2,
                           page, searchVecEnd + newChildPos + (2 + 8),
                           ((searchVecEnd - searchVecStart) << 2) + 8 - newChildPos);
                    newChildPos += searchVecEnd + 2;
                    break alloc;
                }

                // Need to make space, but restore space values first.
                leftSpace += 2;
                rightSpace += 8;
            }

            // Compute remaining space surrounding search vector after insert completes.
            int remaining = leftSpace + rightSpace - encodedLen - 10;
            int garbage = garbage();

            if (garbage > remaining) {
                compact: {
                    // Do full compaction and free up the garbage, or else node must be split.

                    if ((garbage + remaining) < 0) {
                        // Node compaction won't make enough room, but attempt to rebalance
                        // before splitting.

                        CursorFrame parentFrame;
                        if (frame == null || (parentFrame = frame.mParentFrame) == null) {
                            // No sibling nodes, so cannot rebalance.
                            break compact;
                        }
                        
                        // "Randomly" choose left or right node first.
                        if ((id() & 1) == 0) {
                            int adjust = tryRebalanceInternalLeft
                                (tree, parentFrame, keyPos, -remaining);
                            if (adjust == 0) {
                                // First rebalance attempt failed.
                                if (!tryRebalanceInternalRight
                                    (tree, parentFrame, keyPos, -remaining))
                                {
                                    // Second rebalance attempt failed too, so split.
                                    break compact;
                                }
                            } else {
                                keyPos -= adjust;
                                newChildPos -= (adjust << 2);
                            }
                        } else if (!tryRebalanceInternalRight
                                   (tree, parentFrame, keyPos, -remaining))
                        {
                            // First rebalance attempt failed.
                            int adjust = tryRebalanceInternalLeft
                                (tree, parentFrame, keyPos, -remaining);
                            if (adjust == 0) {
                                // Second rebalance attempt failed too, so split.
                                break compact;
                            } else {
                                keyPos -= adjust;
                                newChildPos -= (adjust << 2);
                            }
                        }
                    }

                    compactInternal(result, encodedLen, keyPos, newChildPos);
                    return;
                }

                // Node is full, so split it.

                if (!allowSplit) {
                    throw new AssertionError("Split not allowed");
                }

                // No side-effects if an IOException is thrown here.
                splitInternal(result, encodedLen, keyPos, newChildPos);
                return;
            }

            int vecLen = searchVecEnd - searchVecStart + 2;
            int childIdsLen = (vecLen << 2) + 8;
            int newSearchVecStart;

            if (remaining > 0 || (rightSegTail() & 1) != 0) {
                // Re-center search vector, biased to the right, ensuring proper alignment.
                newSearchVecStart =
                    (rightSegTail() - vecLen - childIdsLen + (1 - 10) - (remaining >> 1)) & ~1;

                // Allocate entry from left segment.
                entryLoc = leftSegTail();
                leftSegTail(entryLoc + encodedLen);
            } else if ((leftSegTail() & 1) == 0) {
                // Move search vector left, ensuring proper alignment.
                newSearchVecStart = leftSegTail() + ((remaining >> 1) & ~1);

                // Allocate entry from right segment.
                entryLoc = rightSegTail() - encodedLen + 1;
                rightSegTail(entryLoc - 1);
            } else {
                // Search vector is misaligned, so do full compaction.
                compactInternal(result, encodedLen, keyPos, newChildPos);
                return;
            }

            int newSearchVecEnd = newSearchVecStart + vecLen;

            p_copies(page,
                     // Move search vector up to new key position.
                     searchVecStart, newSearchVecStart, keyPos,

                     // Move search vector after new key position, to new child id position.
                     searchVecStart + keyPos,
                     newSearchVecStart + keyPos + 2,
                     vecLen - keyPos + newChildPos,

                     // Move search vector after new child id position.
                     searchVecEnd + 2 + newChildPos,
                     newSearchVecEnd + 10 + newChildPos,
                     childIdsLen - newChildPos);

            keyPos += newSearchVecStart;
            newChildPos += newSearchVecEnd + 2;
            searchVecStart(newSearchVecStart);
            searchVecEnd(newSearchVecEnd);
        }

        // Write pointer to key entry.
        p_shortPutLE(page, keyPos, entryLoc);

        result.mPage = page;
        result.mNewChildLoc = newChildPos;
        result.mEntryLoc = entryLoc;
    }

    /**
     * Attempt to make room in this node by moving entries to the left sibling node. First
     * determines if moving entries to the left node is allowed and would free up enough space.
     * Next, attempts to latch parent and child nodes without waiting, avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param keyPos position to insert into; this position cannot move left
     * @param minAmount minimum amount of bytes to move to make room
     * @return 2-based position increment; 0 if try failed
     */
    private int tryRebalanceInternalLeft(BTree tree, CursorFrame parentFrame,
                                         int keyPos, int minAmount)
    {
        final Node parent = parentFrame.tryAcquireExclusive();
        if (parent == null) {
            return 0;
        }

        final int childPos = parentFrame.mNodePos;
        if (childPos <= 0
            || parent.mSplit != null
            || parent.mCachedState != mCachedState)
        {
            // No left child or sanity checks failed.
            parent.releaseExclusive();
            return 0;
        }

        final var parentPage = parent.mPage;
        final var rightPage = mPage;

        int rightShrink = 0;
        int leftGrowth = 0;

        final int lastSearchVecLoc;

        check: {
            int searchVecLoc = searchVecStart();
            int searchVecEnd = searchVecLoc + keyPos - 2;

            // Note that loop doesn't examine last entry. At least one must remain.
            for (; searchVecLoc < searchVecEnd; searchVecLoc += 2) {
                int keyLoc = p_ushortGetLE(rightPage, searchVecLoc);
                int len = keyLengthAtLoc(rightPage, keyLoc) + (2 + 8);

                rightShrink += len;
                leftGrowth += len;

                if (rightShrink >= minAmount) {
                    lastSearchVecLoc = searchVecLoc;

                    // Leftmost key to move comes from the parent, and first moved key in the
                    // right node does not affect left node growth.
                    leftGrowth -= len;
                    keyLoc = p_ushortGetLE(parentPage, parent.searchVecStart() + childPos - 2);
                    leftGrowth += keyLengthAtLoc(parentPage, keyLoc) + (2 + 8);

                    break check;
                }
            }

            parent.releaseExclusive();
            return 0;
        }

        final Node left;
        try {
            left = parent.tryLatchChildNotSplit(childPos - 2);
        } catch (IOException e) {
            return 0;
        }

        if (left == null) {
            parent.releaseExclusive();
            return 0;
        }

        // Notice that try-finally pattern is not used to release the latches. An uncaught
        // exception can only be caused by a bug. Leaving the latches held prevents database
        // corruption from being persisted.

        final int searchKeyLoc;
        final int searchKeyLen;
        final int parentKeyLoc;
        final int parentKeyLen;
        final int parentKeyGrowth;

        check: {
            int leftAvail = left.availableInternalBytes();
            if (leftAvail >= leftGrowth) {
                // Parent search key will be updated, so verify that it has room.
                searchKeyLoc = p_ushortGetLE(rightPage, lastSearchVecLoc);
                searchKeyLen = keyLengthAtLoc(rightPage, searchKeyLoc);
                parentKeyLoc = p_ushortGetLE(parentPage, parent.searchVecStart() + childPos - 2);
                parentKeyLen = keyLengthAtLoc(parentPage, parentKeyLoc);
                parentKeyGrowth = searchKeyLen - parentKeyLen;
                if (parentKeyGrowth <= 0 || parentKeyGrowth <= parent.availableInternalBytes()) {
                    // Parent has room for the new search key, so proceed with rebalancing.
                    break check;
                }
            }
            left.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        try {
            if (tree.mDatabase.markDirty(tree, left)) {
                parent.updateChildRefId(childPos - 2, left.id());
            }
        } catch (IOException e) {
            left.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        int garbageAccum = searchKeyLen;
        int searchVecLoc = searchVecStart();
        final int moved = lastSearchVecLoc - searchVecLoc + 2;

        try {
            // Leftmost key to move comes from the parent.
            int pos = left.highestInternalPos();
            var result = new InResult();
            left.createInternalEntry(null, result, tree, pos, parentKeyLen, (pos + 2) << 2, false);
            // Note: Must access left page each time, since compaction can replace it.
            p_copy(parentPage, parentKeyLoc, left.mPage, result.mEntryLoc, parentKeyLen);

            // Remaining keys come from the right node.
            for (; searchVecLoc < lastSearchVecLoc; searchVecLoc += 2) {
                int keyLoc = p_ushortGetLE(rightPage, searchVecLoc);
                int encodedLen = keyLengthAtLoc(rightPage, keyLoc);
                pos = left.highestInternalPos();
                left.createInternalEntry
                    (null, result, tree, pos, encodedLen, (pos + 2) << 2, false);
                // Note: Must access left page each time, since compaction can replace it.
                p_copy(rightPage, keyLoc, left.mPage, result.mEntryLoc, encodedLen);
                garbageAccum += encodedLen;
            }
        } catch (IOException e) {
            // Can only be caused by node split, but this is not possible.
            throw rethrow(e);
        }

        // Update the parent key after moving it to the left node.
        if (parentKeyGrowth <= 0) {
            p_copy(rightPage, searchKeyLoc, parentPage, parentKeyLoc, searchKeyLen);
            parent.garbage(parent.garbage() - parentKeyGrowth);
        } else {
            parent.updateInternalKeyEncoded
                (childPos - 2, parentKeyGrowth, rightPage, searchKeyLoc, searchKeyLen);
        }

        // Move encoded child pointers.
        {
            int start = searchVecEnd() + 2;
            int len = moved << 2;
            int end = left.searchVecEnd();
            end = end + ((end - left.searchVecStart()) << 2) + (2 + 16) - len;
            p_copy(rightPage, start, left.mPage, end, len);
            p_copy(rightPage, start + len, rightPage, start, (start - lastSearchVecLoc) << 2);
        }

        garbage(garbage() + garbageAccum);
        searchVecStart(lastSearchVecLoc + 2);

        // Fix cursor positions or move them to the left node.
        final int leftEndPos = left.highestInternalPos() + 2;
        for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            int newPos = framePos - moved;
            if (newPos < 0) {
                frame.rebind(left, leftEndPos + newPos);
                frame.adjustParentPosition(-2);
            } else {
                frame.mNodePos = newPos;
            }
            frame = prev;
        }

        left.releaseExclusive();
        parent.releaseExclusive();

        return moved;
    }

    /**
     * Attempt to make room in this node by moving entries to the right sibling node. First
     * determines if moving entries to the right node is allowed and would free up enough space.
     * Next, attempts to latch parent and child nodes without waiting, avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param keyPos position to insert into; this position cannot move right
     * @param minAmount minimum amount of bytes to move to make room
     */
    private boolean tryRebalanceInternalRight(BTree tree, CursorFrame parentFrame,
                                              int keyPos, int minAmount)
    {
        final Node parent = parentFrame.tryAcquireExclusive();
        if (parent == null) {
            return false;
        }

        final int childPos = parentFrame.mNodePos;
        if (childPos >= parent.highestInternalPos()
            || parent.mSplit != null
            || parent.mCachedState != mCachedState)
        {
            // No right child or sanity checks failed.
            parent.releaseExclusive();
            return false;
        }

        final var parentPage = parent.mPage;
        final var leftPage = mPage;

        int leftShrink = 0;
        int rightGrowth = 0;

        final int firstSearchVecLoc;

        check: {
            int searchVecStart = searchVecStart() + keyPos;
            int searchVecLoc = searchVecEnd();

            // Note that loop doesn't examine first entry. At least one must remain.
            for (; searchVecLoc > searchVecStart; searchVecLoc -= 2) {
                int keyLoc = p_ushortGetLE(leftPage, searchVecLoc);
                int len = keyLengthAtLoc(leftPage, keyLoc) + (2 + 8);

                leftShrink += len;
                rightGrowth += len;

                if (leftShrink >= minAmount) {
                    firstSearchVecLoc = searchVecLoc;

                    // Rightmost key to move comes from the parent, and first moved key in the
                    // left node does not affect right node growth.
                    rightGrowth -= len;
                    keyLoc = p_ushortGetLE(parentPage, parent.searchVecStart() + childPos);
                    rightGrowth += keyLengthAtLoc(parentPage, keyLoc) + (2 + 8);

                    break check;
                }
            }

            parent.releaseExclusive();
            return false;
        }

        final Node right;
        try {
            right = parent.tryLatchChildNotSplit(childPos + 2);
        } catch (IOException e) {
            return false;
        }

        if (right == null) {
            parent.releaseExclusive();
            return false;
        }

        // Notice that try-finally pattern is not used to release the latches. An uncaught
        // exception can only be caused by a bug. Leaving the latches held prevents database
        // corruption from being persisted.

        final int searchKeyLoc;
        final int searchKeyLen;
        final int parentKeyLoc;
        final int parentKeyLen;
        final int parentKeyGrowth;

        check: {
            int rightAvail = right.availableInternalBytes();
            if (rightAvail >= rightGrowth) {
                // Parent search key will be updated, so verify that it has room.
                searchKeyLoc = p_ushortGetLE(leftPage, firstSearchVecLoc);
                searchKeyLen = keyLengthAtLoc(leftPage, searchKeyLoc);
                parentKeyLoc = p_ushortGetLE(parentPage, parent.searchVecStart() + childPos);
                parentKeyLen = keyLengthAtLoc(parentPage, parentKeyLoc);
                parentKeyGrowth = searchKeyLen - parentKeyLen;
                if (parentKeyGrowth <= 0 || parentKeyGrowth <= parent.availableInternalBytes()) {
                    // Parent has room for the new search key, so proceed with rebalancing.
                    break check;
                }
            }
            right.releaseExclusive();
            parent.releaseExclusive();
            return false;
        }

        try {
            if (tree.mDatabase.markDirty(tree, right)) {
                parent.updateChildRefId(childPos + 2, right.id());
            }
        } catch (IOException e) {
            right.releaseExclusive();
            parent.releaseExclusive();
            return false;
        }

        int garbageAccum = searchKeyLen;
        int searchVecLoc = searchVecEnd();
        final int moved = searchVecLoc - firstSearchVecLoc + 2;

        try {
            // Rightmost key to move comes from the parent.
            var result = new InResult();
            right.createInternalEntry(null, result, tree, 0, parentKeyLen, 0, false);
            // Note: Must access right page each time, since compaction can replace it.
            p_copy(parentPage, parentKeyLoc, right.mPage, result.mEntryLoc, parentKeyLen);

            // Remaining keys come from the left node.
            for (; searchVecLoc > firstSearchVecLoc; searchVecLoc -= 2) {
                int keyLoc = p_ushortGetLE(leftPage, searchVecLoc);
                int encodedLen = keyLengthAtLoc(leftPage, keyLoc);
                right.createInternalEntry(null, result, tree, 0, encodedLen, 0, false);
                // Note: Must access right page each time, since compaction can replace it.
                p_copy(leftPage, keyLoc, right.mPage, result.mEntryLoc, encodedLen);
                garbageAccum += encodedLen;
            }
        } catch (IOException e) {
            // Can only be caused by node split, but this is not possible.
            throw rethrow(e);
        }

        // Update the parent key after moving it to the right node.
        if (parentKeyGrowth <= 0) {
            p_copy(leftPage, searchKeyLoc, parentPage, parentKeyLoc, searchKeyLen);
            parent.garbage(parent.garbage() - parentKeyGrowth);
        } else {
            parent.updateInternalKeyEncoded
                (childPos, parentKeyGrowth, leftPage, searchKeyLoc, searchKeyLen);
        }

        // Move encoded child pointers.
        {
            int start = searchVecEnd() + 2;
            int len = ((start - searchVecStart()) << 2) + 8 - (moved << 2);
            p_copy(leftPage, start, leftPage, start - moved, len);
            p_copy(leftPage, start + len, right.mPage, right.searchVecEnd() + 2, moved << 2);
        }

        garbage(garbage() + garbageAccum);
        searchVecEnd(firstSearchVecLoc - 2);

        // Fix cursor positions in the right node.
        for (CursorFrame frame = right.mLastCursorFrame; frame != null; ) {
            frame.mNodePos += moved;
            frame = frame.mPrevCousin;
        }

        // Move affected cursor frames to the right node.
        final int adjust = firstSearchVecLoc - searchVecStart() + 4;
        for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            int newPos = frame.mNodePos - adjust;
            if (newPos >= 0) {
                frame.rebind(right, newPos);
                frame.adjustParentPosition(+2);
            }
            frame = prev;
        }

        right.releaseExclusive();
        parent.releaseExclusive();

        return true;
    }

    /**
     * Rebind cursor frames affected by split to correct node and
     * position. Caller must hold exclusive latch.
     *
     * @return latched sibling
     */
    private Node rebindSplitFrames(Split split) {
        final Node sibling = split.latchSiblingEx();
        try {
            for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
                // Capture previous frame from linked list before changing the links.
                CursorFrame prev = frame.mPrevCousin;
                split.rebindFrame(frame, sibling);
                frame = prev;
            }
            return sibling;
        } catch (Throwable e) {
            sibling.releaseExclusive();
            throw e;
        }
    }

    /**
     * Reverses the action of the rebindSplitFrames method. Original and sibling nodes must be
     * held exclusively.
     */
    private void unrebindSplitFrames(Split split, Node sibling) {
        for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            split.unrebindOriginalFrame(frame);
            frame = prev;
        }

        for (CursorFrame frame = sibling.mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            split.unrebindSiblingFrame(frame, this);
            frame = prev;
        }
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @param vfrag 0 or ENTRY_FRAGMENTED
     */
    void updateLeafValue(BTree tree, int pos, int vfrag, byte[] value)
        throws IOException
    {
        var page = mPage;
        final int searchVecStart = searchVecStart();

        final int start;
        final int keyLen;
        final int garbage;
        quick: {
            int loc;
            start = loc = p_ushortGetLE(page, searchVecStart + pos);
            loc += keyLengthAtLoc(page, loc);

            final int valueHeaderLoc = loc;

            // Note: Similar to leafEntryLengthAtLoc and retrieveLeafValueAtLoc.
            int len = p_byteGet(page, loc++);
            if (len < 0) largeValue: {
                int header;
                if ((len & 0x20) == 0) {
                    header = len;
                    len = 1 + (((len & 0x1f) << 8) | p_ubyteGet(page, loc++));
                } else if (len != -1) {
                    header = len;
                    len = 1 + (((len & 0x0f) << 16)
                               | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
                } else {
                    // ghost
                    len = 0;
                    break largeValue;
                }
                if ((header & ENTRY_FRAGMENTED) != 0) {
                    tree.mDatabase.deleteFragments(page, loc, len);
                    // Clearing the fragmented bit prevents the update from double-deleting the
                    // fragments, and it also allows the old entry slot to be re-used.
                    p_bytePut(page, valueHeaderLoc, header & ~ENTRY_FRAGMENTED);
                }
            }

            final int valueLen = value.length;
            if (valueLen > len) {
                // Old entry is too small, and so it becomes garbage.
                // TODO: Try to extend the length instead of creating garbage.
                keyLen = valueHeaderLoc - start;
                garbage = garbage() + loc + len - start;
                break quick;
            }

            if (valueLen == len) {
                // Quick copy with no garbage created.
                if (valueLen == 0) {
                    // Ensure ghost is replaced.
                    p_bytePut(page, valueHeaderLoc, 0);
                } else {
                    p_copyFromArray(value, 0, page, loc, valueLen);
                    if (vfrag != 0) {
                        p_bytePut(page, valueHeaderLoc, p_byteGet(page, valueHeaderLoc) | vfrag);
                    }
                }
            } else {
                // New entry is smaller, so some space is freed.
                int valueLoc = copyToLeafValue(page, vfrag, value, valueHeaderLoc);
                spaceFreed(valueLoc + valueLen, loc + len);
            }

            return;
        }

        // What follows is similar to createLeafEntry method, except the search
        // vector doesn't grow.

        int searchVecEnd = searchVecEnd();

        int leftSpace = searchVecStart - leftSegTail();
        int rightSpace = rightSegTail() - searchVecEnd - 1;

        final int vfragOriginal = vfrag;

        int encodedLen;
        if (vfrag != 0) {
            encodedLen = keyLen + calculateFragmentedValueLength(value);
        } else {
            LocalDatabase db = tree.mDatabase;
            encodedLen = keyLen + calculateLeafValueLength(value);
            if (encodedLen > db.mMaxEntrySize) {
                value = db.fragment(value, value.length, db.mMaxFragmentedEntrySize - keyLen);
                if (value == null) {
                    throw new AssertionError();
                }
                encodedLen = keyLen + calculateFragmentedValueLength(value);
                vfrag = ENTRY_FRAGMENTED;
            }
        }

        int entryLoc;
        alloc: try {
            if ((entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0) {
                pos += searchVecStart;
                break alloc;
            }

            // Compute remaining space surrounding search vector after update completes.
            int remaining = leftSpace + rightSpace - encodedLen;

            if (garbage > remaining) {
                // Do full compaction and free up the garbage, or split the node.

                var akeyRef = new byte[1][];
                boolean isOriginal = retrieveActualKeyAtLoc(page, start, akeyRef);
                byte[] akey = akeyRef[0];

                if ((garbage + remaining) < 0) {
                    if (mSplit == null) {
                        // TODO: use frame for rebalancing
                        // Node is full, so split it.
                        byte[] okey = isOriginal ? akey : retrieveKeyAtLoc(this, page, start);
                        splitLeafAndCreateEntry
                            (tree, okey, akey, vfrag, value, encodedLen, pos, false);
                        return;
                    }

                    // Node is already split, and so value is too large.
                    if (vfrag != 0) {
                        // Not expected.
                        throw new DatabaseException("Fragmented entry doesn't fit");
                    }
                    LocalDatabase db = tree.mDatabase;
                    int max = Math.min(db.mMaxFragmentedEntrySize,
                                       garbage + leftSpace + rightSpace);
                    value = db.fragment(value, value.length, max - keyLen);
                    if (value == null) {
                        throw new AssertionError();
                    }
                    encodedLen = keyLen + calculateFragmentedValueLength(value);
                    vfrag = ENTRY_FRAGMENTED;
                }

                garbage(garbage);
                entryLoc = compactLeaf(encodedLen, pos, false);
                page = mPage;
                entryLoc = isOriginal ? encodeNormalKey(akey, page, entryLoc)
                    : encodeFragmentedKey(akey, page, entryLoc);
                copyToLeafValue(page, vfrag, value, entryLoc);
                return;
            }

            int vecLen = searchVecEnd - searchVecStart + 2;
            int newSearchVecStart;

            if (remaining > 0 || (rightSegTail() & 1) != 0) {
                // Re-center search vector, biased to the right, ensuring proper alignment.
                newSearchVecStart = (rightSegTail() - vecLen + (1 - 0) - (remaining >> 1)) & ~1;

                // Allocate entry from left segment.
                entryLoc = leftSegTail();
                leftSegTail(entryLoc + encodedLen);
            } else if ((leftSegTail() & 1) == 0) {
                // Move search vector left, ensuring proper alignment.
                newSearchVecStart = leftSegTail() + ((remaining >> 1) & ~1);

                // Allocate entry from right segment.
                entryLoc = rightSegTail() - encodedLen + 1;
                rightSegTail(entryLoc - 1);
            } else {
                // Search vector is misaligned, so do full compaction.
                var akeyRef = new byte[1][];
                int loc = p_ushortGetLE(page, searchVecStart + pos);
                boolean isOriginal = retrieveActualKeyAtLoc(page, loc, akeyRef);
                byte[] akey = akeyRef[0];

                garbage(garbage);
                entryLoc = compactLeaf(encodedLen, pos, false);
                page = mPage;
                entryLoc = isOriginal ? encodeNormalKey(akey, page, entryLoc)
                    : encodeFragmentedKey(akey, page, entryLoc);
                copyToLeafValue(page, vfrag, value, entryLoc);
                return;
            }

            p_copy(page, searchVecStart, page, newSearchVecStart, vecLen);

            pos += newSearchVecStart;
            searchVecStart(newSearchVecStart);
            searchVecEnd(newSearchVecStart + vecLen - 2);
        } catch (Throwable e) {
            if (vfrag == ENTRY_FRAGMENTED && vfragOriginal != ENTRY_FRAGMENTED) {
                cleanupFragments(e, value);
            }
            throw e;
        }

        // Copy existing key, and then copy value.
        p_copy(page, start, page, entryLoc, keyLen);
        copyToLeafValue(page, vfrag, value, entryLoc + keyLen);
        p_shortPutLE(page, pos, entryLoc);

        garbage(garbage);
    }

    /**
     * Update an internal node key to be larger than what is currently allocated. Caller must
     * ensure that node has enough space available and that it's not split. New key must not
     * force this node to split. Key MUST be a normal, non-fragmented key.
     *
     * @param pos must be positive
     * @param growth key size growth
     * @param key normal unencoded key
     */
    void updateInternalKey(int pos, int growth, byte[] key, int encodedLen) {
        int entryLoc = doUpdateInternalKey(pos, growth, encodedLen);
        encodeNormalKey(key, mPage, entryLoc);
    }

    /**
     * Update an internal node key to be larger than what is currently allocated. Caller must
     * ensure that node has enough space available and that it's not split. New key must not
     * force this node to split.
     *
     * @param pos must be positive
     * @param growth key size growth
     * @param key page with encoded key
     * @param keyStart encoded key start; includes header
     */
    void updateInternalKeyEncoded(int pos, int growth,
                                  /*P*/ byte[] key, int keyStart, int encodedLen)
    {
        int entryLoc = doUpdateInternalKey(pos, growth, encodedLen);
        p_copy(key, keyStart, mPage, entryLoc, encodedLen);
    }

    /**
     * @return entryLoc
     */
    int doUpdateInternalKey(int pos, final int growth, final int encodedLen) {
        int garbage = garbage() + encodedLen - growth;

        // What follows is similar to createInternalEntry method, except the search
        // vector doesn't grow.

        int searchVecStart = searchVecStart();
        int searchVecEnd = searchVecEnd();

        int leftSpace = searchVecStart - leftSegTail();
        int rightSpace = rightSegTail() - searchVecEnd
            - ((searchVecEnd - searchVecStart) << 2) - 17;

        int entryLoc;
        alloc: {
            if ((entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0) {
                pos += searchVecStart;
                break alloc;
            }

            makeRoom: {
                // Compute remaining space surrounding search vector after update completes.
                int remaining = leftSpace + rightSpace - encodedLen;

                if (garbage > remaining) {
                    // Do full compaction and free up the garbage.
                    if ((garbage + remaining) < 0) {
                        // New key doesn't fit.
                        throw new AssertionError();
                    }
                    break makeRoom;
                }

                int vecLen = searchVecEnd - searchVecStart + 2;
                int childIdsLen = (vecLen << 2) + 8;
                int newSearchVecStart;

                if (remaining > 0 || (rightSegTail() & 1) != 0) {
                    // Re-center search vector, biased to the right, ensuring proper alignment.
                    newSearchVecStart =
                        (rightSegTail() - vecLen - childIdsLen + (1 - 0) - (remaining >> 1)) & ~1;

                    // Allocate entry from left segment.
                    entryLoc = leftSegTail();
                    leftSegTail(entryLoc + encodedLen);
                } else if ((leftSegTail() & 1) == 0) {
                    // Move search vector left, ensuring proper alignment.
                    newSearchVecStart = leftSegTail() + ((remaining >> 1) & ~1);

                    // Allocate entry from right segment.
                    entryLoc = rightSegTail() - encodedLen + 1;
                    rightSegTail(entryLoc - 1);
                } else {
                    // Search vector is misaligned, so do full compaction.
                    break makeRoom;
                }

                var page = mPage;
                p_copy(page, searchVecStart, page, newSearchVecStart, vecLen + childIdsLen);

                pos += newSearchVecStart;
                searchVecStart(newSearchVecStart);
                searchVecEnd(newSearchVecStart + vecLen - 2);

                break alloc;
            }

            // This point is reached for making room via node compaction.

            garbage(garbage);

            var result = new InResult();
            compactInternal(result, encodedLen, pos, Integer.MIN_VALUE);

            return result.mEntryLoc;
        }

        // Point to entry. Caller must copy the key to the location.
        p_shortPutLE(mPage, pos, entryLoc);

        garbage(garbage);

        return entryLoc;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    void updateChildRefId(int pos, long id) {
        p_longPutLE(mPage, searchVecEnd() + 2 + (pos << 2), id);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    void deleteLeafEntry(int pos) throws IOException {
        var page = mPage;
        int startLoc = p_ushortGetLE(page, searchVecStart() + pos);
        int endLoc = doDeleteLeafEntry(page, startLoc);
        finishDeleteLeafEntry(pos, startLoc, endLoc);
    }

    /**
     * @param loc start location in page
     * @return location just after end of cleared entry
     */
    private int doDeleteLeafEntry(/*P*/ byte[] page, int loc) throws IOException {
        // Note: Similar to leafEntryLengthAtLoc and retrieveLeafValueAtLoc.

        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            loc += keyLen + 1;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            if ((header & ENTRY_FRAGMENTED) != 0) {
                getDatabase().deleteFragments(page, loc, keyLen);
            }
            loc += keyLen;
        }

        int header = p_byteGet(page, loc++);
        if (header >= 0) {
            loc += header;
        } else largeValue: {
            int len;
            if ((header & 0x20) == 0) {
                len = 1 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc++));
            } else if (header != -1) {
                len = 1 + (((header & 0x0f) << 16)
                           | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
            } else {
                // ghost
                break largeValue;
            }
            if ((header & ENTRY_FRAGMENTED) != 0) {
                getDatabase().deleteFragments(page, loc, len);
            }
            loc += len;
        }

        return loc;
    }

    /**
     * Increase the garbage size or adjust the free space, depending on the location of the
     * space which was freed.
     *
     * @param startLoc start location of freed space, inclusive
     * @param endLoc end location of freed space, exclusive
     */
    private void spaceFreed(int startLoc, int endLoc) {
        if (endLoc == leftSegTail()) {
            // Deleted entry is adjacent to the left free space, so just extend it.
            leftSegTail(startLoc);
        } else if ((startLoc - 1) == rightSegTail()) {
            // Deleted entry is adjacent to the right free space, so just extend it.
            rightSegTail(endLoc - 1);
        } else {
            // Not adjacent to free space, so update the garbage size.
            garbage(garbage() + (endLoc - startLoc));
        }
    }

    /**
     * Finish the delete by updating garbage size and adjusting the search vector. Call this
     * variant when it's not expected that the node will have entries inserted into it again.
     */
    void finishDeleteLeafEntry(int pos, int entryLen) {
        // Increment garbage by the size of the encoded entry.
        garbage(garbage() + entryLen);
        doFinishDeleteLeafEntry(pos);
    }

    /**
     * Finish the delete by adjusting the free space or by updating the garbage size, and then
     * adjust the search vector.
     *
     * @param startLoc entry start location, inclusive
     * @param endLoc entry end location, exclusive
     */
    void finishDeleteLeafEntry(int pos, int startLoc, int endLoc) {
        spaceFreed(startLoc, endLoc);
        doFinishDeleteLeafEntry(pos);
    }

    /**
     * Finish the delete by only adjusting the search vector.
     */
    private void doFinishDeleteLeafEntry(int pos) {
        var page = mPage;
        int searchVecStart = searchVecStart();
        int searchVecEnd = searchVecEnd();

        // When current size is odd, favor shifting left. This ensures that when the page size
        // is 65536 and the last entry is deleted, the search vector start is in bounds.
        if (pos < ((searchVecEnd - searchVecStart) >> 1)) {
            // Shift left side of search vector to the right.
            p_copy(page, searchVecStart, page, searchVecStart += 2, pos);
            searchVecStart(searchVecStart);
        } else {
            // Shift right side of search vector to the left.
            pos += searchVecStart;
            p_copy(page, pos + 2, page, pos, searchVecEnd - pos);
            searchVecEnd(searchVecEnd - 2);
        }
    }

    /**
     * Fixes all bound cursors after a delete. Node must be latched exclusively.
     *
     * @param pos positive position of entry that was deleted
     * @param key not-found key to set for cursors at given position
     */
    void postDelete(int pos, byte[] key) {
        int newPos = ~pos;
        CursorFrame frame = mLastCursorFrame;
        do {
            int framePos = frame.mNodePos;
            if (framePos == pos) {
                frame.mNodePos = newPos;
                frame.mNotFoundKey = key;
            } else if (framePos > pos) {
                frame.mNodePos = framePos - 2;
            } else if (framePos < newPos) {
                // Position is a complement, so add instead of subtract.
                frame.mNodePos = framePos + 2;
            }
        } while ((frame = frame.mPrevCousin) != null);
    }

    /**
     * Moves all the entries from the right node into the tail of the given
     * left node, and then deletes the right node node. Caller must ensure that
     * left node has enough room, and that both nodes are latched exclusively.
     * Caller must also hold commit lock. The right node is always released as
     * a side effect, but left node is never released by this method.
     */
    static void moveLeafToLeftAndDelete(BTree tree, Node leftNode, Node rightNode)
        throws IOException
    {
        tree.mDatabase.prepareToDelete(rightNode);

        final var rightPage = rightNode.mPage;
        final int searchVecEnd = rightNode.searchVecEnd();
        final int leftEndPos = leftNode.highestLeafPos() + 2;

        int searchVecStart = rightNode.searchVecStart();
        while (searchVecStart <= searchVecEnd) {
            int entryLoc = p_ushortGetLE(rightPage, searchVecStart);
            int encodedLen = leafEntryLengthAtLoc(rightPage, entryLoc);
            int leftEntryLoc = leftNode.createLeafEntry
                (null, tree, leftNode.highestLeafPos() + 2, encodedLen);
            // Note: Must access left page each time, since compaction can replace it.
            p_copy(rightPage, entryLoc, leftNode.mPage, leftEntryLoc, encodedLen);
            searchVecStart += 2;
        }

        // All cursors in the right node must be moved to the left node.
        for (CursorFrame frame = rightNode.mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            frame.rebind(leftNode, framePos + (framePos < 0 ? (-leftEndPos) : leftEndPos));
            frame = prev;
        }

        // If right node was high extremity, left node now is.
        leftNode.type((byte) (leftNode.type() | (rightNode.type() & HIGH_EXTREMITY)));

        tree.mDatabase.finishDeleteNode(rightNode);
    }

    /**
     * Moves all the entries from the right node into the tail of the given
     * left node, and then deletes the right node node. Caller must ensure that
     * left node has enough room, and that both nodes are latched exclusively.
     * Caller must also hold commit lock. The right node is always released as
     * a side effect, but left node is never released by this method.
     *
     * @param parentPage source of entry to merge from parent
     * @param parentLoc location of parent entry
     * @param parentLen length of parent entry
     */
    static void moveInternalToLeftAndDelete(BTree tree, Node leftNode, Node rightNode,
                                            /*P*/ byte[] parentPage, int parentLoc, int parentLen)
        throws IOException
    {
        tree.mDatabase.prepareToDelete(rightNode);

        // Create space to absorb parent key.
        int leftEndPos = leftNode.highestInternalPos();
        var result = new InResult();
        leftNode.createInternalEntry
            (null, result, tree, leftEndPos, parentLen, (leftEndPos += 2) << 2, false);

        // Copy child id associated with parent key.
        final var rightPage = rightNode.mPage;
        int rightChildIdsLoc = rightNode.searchVecEnd() + 2;
        p_copy(rightPage, rightChildIdsLoc, result.mPage, result.mNewChildLoc, 8);
        rightChildIdsLoc += 8;

        // Write parent key.
        p_copy(parentPage, parentLoc, result.mPage, result.mEntryLoc, parentLen);

        final int searchVecEnd = rightNode.searchVecEnd();

        int searchVecStart = rightNode.searchVecStart();
        while (searchVecStart <= searchVecEnd) {
            int entryLoc = p_ushortGetLE(rightPage, searchVecStart);
            int encodedLen = keyLengthAtLoc(rightPage, entryLoc);

            // Allocate entry for left node.
            int pos = leftNode.highestInternalPos();
            leftNode.createInternalEntry
                (null, result, tree, pos, encodedLen, (pos + 2) << 2, false);

            // Copy child id.
            p_copy(rightPage, rightChildIdsLoc, result.mPage, result.mNewChildLoc, 8);
            rightChildIdsLoc += 8;

            // Copy key.
            // Note: Must access left page each time, since compaction can replace it.
            p_copy(rightPage, entryLoc, result.mPage, result.mEntryLoc, encodedLen);
            searchVecStart += 2;
        }

        // All cursors in the right node must be moved to the left node.
        for (CursorFrame frame = rightNode.mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            CursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            frame.rebind(leftNode, leftEndPos + framePos);
            frame = prev;
        }

        // If right node was high extremity, left node now is.
        leftNode.type((byte) (leftNode.type() | (rightNode.type() & HIGH_EXTREMITY)));

        tree.mDatabase.finishDeleteNode(rightNode);
    }

    /**
     * Delete a parent reference to a right child which merged left.
     *
     * @param childPos non-zero two-based position of the right child
     */
    void deleteRightChildRef(final int childPos) throws IOException {
        final var page = mPage;
        int keyPos = childPos - 2;
        int searchVecStart = searchVecStart();

        int entryLoc = p_ushortGetLE(page, searchVecStart + keyPos);
        int keyLen = keyLengthAtLoc(page, entryLoc);

        // If the key is fragmented, must delete it, but only if this is a bottom internal
        // node. In all other cases, the parent key is effectively moved down. See "absorb
        // parent key" in the moveInternalToLeftAndDelete method. Leaf nodes don't absorb the
        // key, which is why only bottom internal nodes can delete it.
        if (isBottomInternal() && (p_byteGet(page, entryLoc) & 0xc0) == 0xc0) {
            getDatabase().deleteFragments(page, entryLoc + 2, keyLen - 2);
        }

        // Increment garbage by the size of the encoded entry.
        garbage(garbage() + keyLen);

        // Rescale for long ids as encoded in page.
        int childLoc = childPos << 2;

        int searchVecEnd = searchVecEnd();

        // Remove search vector entry (2 bytes) and remove child id entry
        // (8 bytes). Determine which shift operations minimize movement.
        if (childLoc < (3 * (searchVecEnd - searchVecStart) + keyPos + 8) >> 1) {
            // Shift child ids right by 8, shift search vector right by 10.
            p_copy(page, searchVecStart + keyPos + 2,
                   page, searchVecStart + keyPos + (2 + 8),
                   searchVecEnd - searchVecStart - keyPos + childLoc);
            p_copy(page, searchVecStart, page, searchVecStart += 10, keyPos);
            searchVecEnd(searchVecEnd + 8);
        } else {
            // Shift child ids left by 8, shift search vector right by 2.
            p_copy(page, searchVecEnd + childLoc + (2 + 8),
                   page, searchVecEnd + childLoc + 2,
                   ((searchVecEnd - searchVecStart) << 2) + 8 - childLoc);
            p_copy(page, searchVecStart, page, searchVecStart += 2, keyPos);
        }

        searchVecStart(searchVecStart);

        // Fix affected cursors.
        for (CursorFrame frame = mLastCursorFrame; frame != null; ) {
            int framePos = frame.mNodePos;
            if (framePos >= childPos) {
                frame.mNodePos = framePos - 2;
            }
            frame = frame.mPrevCousin;
        }
    }

    /**
     * Delete a parent reference to the lowest child which was deleted. No cursors or threads
     * can be active in the tree except for one at the lowest position.
     *
     * This method only should be called when deleting the entire tree, because parent keys
     * aren't moved downwards. Fragmented keys are always deleted.
     */
    void deleteLowestChildRef() throws IOException {
        final var page = mPage;
        final int searchVecStart = searchVecStart();

        final int entryLoc = p_ushortGetLE(page, searchVecStart);
        final int keyLen = keyLengthAtLoc(page, entryLoc);

        // Unlike deleteRightChildRef, always delete fragmented keys. This method isn't called
        // by anything which moves keys downwards.
        if ((p_byteGet(page, entryLoc) & 0xc0) == 0xc0) {
            getDatabase().deleteFragments(page, entryLoc + 2, keyLen - 2);
        }

        // Increment garbage by the size of the encoded entry.
        garbage(garbage() + keyLen);

        final int searchVecEnd = searchVecEnd();

        p_copy(page, searchVecStart + 2,
               page, searchVecStart + (2 + 8),
               searchVecEnd - searchVecStart);

        searchVecStart(searchVecStart + (2 + 8));
        searchVecEnd(searchVecEnd + 8);
    }

    /**
     * Delete this non-leaf root node, after all keys have been deleted. Caller must hold
     * exclusive latches for root node, lone child, and stub. Caller must also ensure that both
     * nodes are not splitting. All latches are released, even if an exception is thrown.
     *
     * @param stub frames bound to root node move here
     */
    void rootDelete(BTree tree, Node child, Node stub) throws IOException {
        try {
            tree.mDatabase.prepareToDelete(child);

            try {
                doRootDelete(tree, child, stub);
            } catch (Throwable e) {
                child.releaseExclusive();
                throw e;
            }

            // The node can be deleted earlier in the method, but doing it here might prevent
            // corruption if an unexpected exception occurs.
            tree.mDatabase.finishDeleteNode(child);
        } finally {
            stub.releaseExclusive();
            releaseExclusive();
        }
    }

    private void doRootDelete(BTree tree, Node child, Node stub) throws IOException {
        var oldRootPage = mPage;

        /*P*/ // [
        mPage = child.mPage;
        type(child.type());
        garbage(child.garbage());
        leftSegTail(child.leftSegTail());
        rightSegTail(child.rightSegTail());
        searchVecStart(child.searchVecStart());
        searchVecEnd(child.searchVecEnd());
        /*P*/ // |
        /*P*/ // if (tree.mDatabase.mFullyMapped) {
        /*P*/ //     // Page cannot change, so copy it instead.
        /*P*/ //     p_copy(child.mPage, 0, oldRootPage, 0, tree.mDatabase.pageSize());
        /*P*/ //     oldRootPage = child.mPage;
        /*P*/ // } else {
        /*P*/ //     mPage = child.mPage;
        /*P*/ // }
        /*P*/ // ]

        // Lock the last frames, preventing concurrent unbinding of those frames...
        var lock = new CursorFrame();
        CursorFrame childLastFrame = child.lockLastFrame(lock);
        CursorFrame thisLastFrame = this.lockLastFrame(lock);

        // ...now they can be moved around...

        // 1. Frames from child move to this node, the root.
        if (!CursorFrame.cLastHandle.compareAndSet(this, thisLastFrame, childLastFrame)) {
            throw new AssertionError();
        }
        // 2. Frames of child node are cleared.
        if (!CursorFrame.cLastHandle.compareAndSet(child, childLastFrame, null)) {
            throw new AssertionError();
        }
        // 3. Frames from empty root move to the stub.
        if (!CursorFrame.cLastHandle.compareAndSet(stub, null, thisLastFrame)) {
            throw new AssertionError();
        }

        this.fixFrameBindings(lock, childLastFrame); // Note: frames were moved
        stub.fixFrameBindings(lock, thisLastFrame);

        // Old page is moved to child, to be recycled after caller deletes the child.
        /*P*/ // [
        child.mPage = oldRootPage;
        /*P*/ // |
        /*P*/ // if (tree.mDatabase.mFullyMapped) {
        /*P*/ //     // Must use a special reserved page because existing one will be recycled.
        /*P*/ //     child.mPage = p_nonTreePage();
        /*P*/ // } else {
        /*P*/ //     child.mPage = oldRootPage;
        /*P*/ // }
        /*P*/ // ]
    }

    /**
     * Lock the last frame, for use by the rootDelete method.
     */
    private CursorFrame lockLastFrame(CursorFrame lock) {
        while (true) {
            CursorFrame last = mLastCursorFrame;
            CursorFrame lockResult = last.tryLock(lock);
            if (lockResult == last) {
                return last;
            }
            if (lockResult != null) {
                last.unlock(lockResult);
            }
            // Must keep trying against the last cursor frame instead of iterating to the
            // previous frame. The lock attempt failed because of a concurrent unbind, but the
            // last cursor frame reference might not have been updated yet. Assertions in the
            // doRootDelete method further verify that the locked frame is in fact the last,
            // with a compareAndSet call.
        }
    }

    /**
     * Bind all the frames of this node, to this node, for use by the rootDelete method. Frame
     * locks are released as a side-effect.
     *
     * @param frame last frame, locked; is unlocked with itself
     */
    private void fixFrameBindings(final CursorFrame lock, CursorFrame frame) {
        CursorFrame lockResult = frame;
        while (true) {
            Node existing = frame.mNode;
            if (existing != null) {
                if (existing == this) {
                    throw new AssertionError();
                }
                frame.mNode = this;
            }

            CursorFrame prev = frame.tryLockPrevious(lock);
            frame.unlock(lockResult);
            if (prev == null) {
                return;
            }

            lockResult = frame;
            frame = prev;
        }
    }

    /**
     * Atomically swaps the contents of this root node with another. Both must be latched
     * exclusively.
     */
    void rootSwap(Node other) {
        // Need to copy the quick access fields into the page so that they can be read back
        // correctly below. Not needed for direct page access, which doesn't have these fields.
        /*P*/ // [
        prepareWrite();
        other.prepareWrite();
        /*P*/ // ]

        int pageSize = pageSize(mPage);
        var tempPage = new byte[pageSize];
        p_copyToArray(mPage, 0, tempPage, 0, pageSize);
        p_copy(other.mPage, 0, mPage, 0, pageSize);
        p_copyFromArray(tempPage, 0, other.mPage, 0, pageSize);

        /*P*/ // [
        readFields();
        other.readFields();
        /*P*/ // ]
    }

    private static final int SMALL_KEY_LIMIT = 128;

    /**
     * Calculate encoded key length, including header. Returns -1 if key is too large and must
     * be fragmented.
     */
    static int calculateAllowedKeyLength(LocalDatabase db, byte[] key) {
        int len = key.length;
        if (((len - 1) & ~(SMALL_KEY_LIMIT - 1)) == 0) {
            // Always safe because minimum node size is 512 bytes.
            return len + 1;
        } else {
            return len > db.mMaxKeySize ? -1 : (len + 2);
        }
    }

    /**
     * Calculate encoded key length, including header. Key must fit in the node and hasn't been
     * fragmented. Fragmented keys always lead with a 2-byte header.
     */
    static int calculateKeyLength(byte[] key) {
        int len = key.length - 1;
        return len + ((len & ~(SMALL_KEY_LIMIT - 1)) == 0 ? 2 : 3);
    }

    /**
     * Calculate encoded value length for leaf, including header. Value must fit in the node
     * and hasn't been fragmented.
     */
    private static int calculateLeafValueLength(byte[] value) {
        int len = value.length;
        return len + ((len <= 127) ? 1 : ((len <= 8192) ? 2 : 3));
    }

    /**
     * Calculate encoded value length for leaf, including header. Value must fit in the node
     * and hasn't been fragmented.
     */
    private static long calculateLeafValueLength(long vlength) {
        return vlength + ((vlength <= 127) ? 1 : ((vlength <= 8192) ? 2 : 3));
    }

    /**
     * Calculate encoded value length for leaf, including header. Value must have been encoded
     * as fragmented.
     */
    private static int calculateFragmentedValueLength(byte[] value) {
        return calculateFragmentedValueLength(value.length);
    }

    /**
     * Calculate encoded value length for leaf, including header. Value must have been encoded
     * as fragmented.
     */
    static int calculateFragmentedValueLength(int vlength) {
        return vlength + ((vlength <= 8192) ? 2 : 3);
    }

    /**
     * @param key unencoded key
     * @param page destination for encoded key, with room for key header
     * @return updated pageLoc
     */
    static int encodeNormalKey(final byte[] key, final /*P*/ byte[] page, int pageLoc) {
        final int keyLen = key.length;

        if (keyLen <= SMALL_KEY_LIMIT && keyLen > 0) {
            p_bytePut(page, pageLoc++, keyLen - 1);
        } else {
            p_bytePut(page, pageLoc++, 0x80 | (keyLen >> 8));
            p_bytePut(page, pageLoc++, keyLen);
        }
        p_copyFromArray(key, 0, page, pageLoc, keyLen);

        return pageLoc + keyLen;
    }

    /**
     * @param key fragmented key
     * @param page destination for encoded key, with room for key header
     * @return updated pageLoc
     */
    static int encodeFragmentedKey(final byte[] key, final /*P*/ byte[] page, int pageLoc) {
        final int keyLen = key.length;
        p_bytePut(page, pageLoc++, (0x80 | ENTRY_FRAGMENTED) | (keyLen >> 8));
        p_bytePut(page, pageLoc++, keyLen);
        p_copyFromArray(key, 0, page, pageLoc, keyLen);
        return pageLoc + keyLen;
    }

    /**
     * @return -1 if not enough contiguous space surrounding search vector
     */
    private int allocPageEntry(int encodedLen, int leftSpace, int rightSpace) {
        final int entryLoc;
        if (encodedLen <= leftSpace && leftSpace >= rightSpace) {
            // Allocate entry from left segment.
            entryLoc = leftSegTail();
            leftSegTail(entryLoc + encodedLen);
        } else if (encodedLen <= rightSpace) {
            // Allocate entry from right segment.
            entryLoc = rightSegTail() - encodedLen + 1;
            rightSegTail(entryLoc - 1);
        } else {
            // No room.
            return -1;
        }
        return entryLoc;
    }

    /**
     * @param okey original key
     * @param akey key to actually store
     * @param vfrag 0 or ENTRY_FRAGMENTED
     */
    private void copyToLeafEntry(byte[] okey, byte[] akey, int vfrag, byte[] value, int entryLoc) {
        final var page = mPage;
        int vloc = okey == akey ? encodeNormalKey(akey, page, entryLoc)
            : encodeFragmentedKey(akey, page, entryLoc);
        copyToLeafValue(page, vfrag, value, vloc);
    }

    /**
     * @param vfrag 0 or ENTRY_FRAGMENTED
     * @return page location for first byte of value (first location after header)
     */
    private static int copyToLeafValue(/*P*/ byte[] page, int vfrag, byte[] value, int vloc) {
        final int vlen = value.length;
        vloc = encodeLeafValueHeader(page, vfrag, vlen, vloc);
        p_copyFromArray(value, 0, page, vloc, vlen);
        return vloc;
    }

    /**
     * @param vfrag 0 or ENTRY_FRAGMENTED
     * @return page location for first byte of value (first location after header)
     */
    static int encodeLeafValueHeader(/*P*/ byte[] page, int vfrag, int vlen, int vloc) {
        if (vlen > 127 || vfrag != 0) {
            vlen--;
            if (vlen < 8192) {
                p_bytePut(page, vloc++, 0x80 | vfrag | (vlen >> 8));
            } else {
                p_bytePut(page, vloc++, 0xa0 | vfrag | (vlen >> 16));
                p_bytePut(page, vloc++, vlen >> 8);
            }
        }
        p_bytePut(page, vloc++, vlen);
        return vloc;
    }

    /**
     * Compact leaf by reclaiming garbage and moving search vector towards
     * tail. Caller is responsible for ensuring that new entry will fit after
     * compaction. Space is allocated for new entry, and the search vector
     * points to it.
     *
     * @param encodedLen length of new entry to allocate
     * @param pos normalized search vector position of entry to insert/update
     * @return location for newly allocated entry, already pointed to by search vector
     */
    private int compactLeaf(int encodedLen, int pos, boolean forInsert) {
        var page = mPage;

        int searchVecLoc = searchVecStart();
        // Size of search vector, possibly with new entry.
        int newSearchVecSize = searchVecEnd() - searchVecLoc + 2;
        if (forInsert) {
            newSearchVecSize += 2;
        }
        pos += searchVecLoc;

        // Determine new location of search vector, with room to grow on both ends.
        int newSearchVecStart;
        // Capacity available to search vector after compaction.
        int searchVecCap = garbage() + rightSegTail() + 1 - leftSegTail() - encodedLen;
        newSearchVecStart = pageSize(page) - (((searchVecCap + newSearchVecSize) >> 1) & ~1);

        // Copy into a fresh buffer.

        int destLoc = TN_HEADER_SIZE;
        int newSearchVecLoc = newSearchVecStart;
        int newLoc = 0;
        final int searchVecEnd = searchVecEnd();

        var dest = mGroup.acquireSparePage();

        /*P*/ // [|
        /*P*/ // p_intPutLE(dest, 0, type() & 0xff); // set type, reserved byte, and garbage
        /*P*/ // ]

        for (; searchVecLoc <= searchVecEnd; searchVecLoc += 2, newSearchVecLoc += 2) {
            if (searchVecLoc == pos) {
                newLoc = newSearchVecLoc;
                if (forInsert) {
                    newSearchVecLoc += 2;
                } else {
                    continue;
                }
            }
            p_shortPutLE(dest, newSearchVecLoc, destLoc);
            int sourceLoc = p_ushortGetLE(page, searchVecLoc);
            int len = leafEntryLengthAtLoc(page, sourceLoc);
            p_copy(page, sourceLoc, dest, destLoc, len);
            destLoc += len;
        }

        /*P*/ // [
        // Recycle old page buffer and swap in compacted page.
        mGroup.releaseSparePage(page);
        mPage = dest;
        garbage(0);
        /*P*/ // |
        /*P*/ // if (getDatabase().mFullyMapped) {
        /*P*/ //     // Copy compacted entries to original page and recycle spare page buffer.
        /*P*/ //     p_copy(dest, 0, page, 0, pageSize(page));
        /*P*/ //     mGroup.releaseSparePage(dest);
        /*P*/ //     dest = page;
        /*P*/ // } else {
        /*P*/ //     // Recycle old page buffer and swap in compacted page.
        /*P*/ //     mGroup.releaseSparePage(page);
        /*P*/ //     mPage = dest;
        /*P*/ // }
        /*P*/ // ]

        // Write pointer to new allocation.
        p_shortPutLE(dest, newLoc == 0 ? newSearchVecLoc : newLoc, destLoc);

        leftSegTail(destLoc + encodedLen);
        rightSegTail(pageSize(dest) - 1);
        searchVecStart(newSearchVecStart);
        searchVecEnd(newSearchVecStart + newSearchVecSize - 2);

        return destLoc;
    }

    private void cleanupSplit(Throwable cause, Node newNode, Split split) {
        if (split != null) {
            cleanupFragments(cause, split.fragmentedKey());
        }

        try {
            // No need to prepare for delete because node contents are unreferenced.
            getDatabase().finishDeleteNode(newNode);
        } catch (Throwable e) {
            Utils.suppress(cause, e);
            panic(cause);
        }
    }

    /**
     * Split leaf for ascending order, and copy an entry from another page. The source entry
     * must be ordered higher than all the entries of this target leaf node.
     *
     * @param snode source node to copy entry from
     * @param spos source position to copy entry from
     * @param encodedLen length of new entry to allocate
     */
    void splitLeafAscendingAndCopyEntry(BTree tree, Node snode, int spos, int encodedLen)
        throws IOException
    {
        // Note: This method is a specialized variant of the splitLeafAndCreateEntry method.

        if (mSplit != null) {
            throw new AssertionError("Node is already split");
        }

        var page = mPage;
        checkClosedIndexException(page);

        Node newNode = tree.mDatabase.allocDirtyNode(NodeGroup.MODE_UNEVICTABLE);
        tree.mDatabase.nodeMapPut(newNode);

        var newPage = newNode.mPage;

        /*P*/ // [
        newNode.garbage(0);
        /*P*/ // |
        /*P*/ // p_intPutLE(newPage, 0, 0); // set type (fixed later), reserved byte, and garbage
        /*P*/ // ]

        Split split = null;
        try {
            split = newSplitRight(newNode);
            // Choose an appropriate middle key for suffix compression.
            split.setKey(tree, midKey(highestLeafPos(), snode, spos));
        } catch (Throwable e) {
            cleanupSplit(e, newNode, split);
            throw e;
        }

        mSplit = split;

        // Position search vector at extreme right, allowing new entries to be placed in a
        // natural ascending order.
        newNode.rightSegTail(pageSize(newPage) - 1);
        int newSearchVecStart = pageSize(newPage) - 2;
        newNode.searchVecStart(newSearchVecStart);
        newNode.searchVecEnd(newSearchVecStart);

        final var spage = snode.mPage;
        final int sloc = p_ushortGetLE(spage, snode.searchVecStart() + spos);
        p_copy(spage, sloc, newPage, TN_HEADER_SIZE, encodedLen);
        p_shortPutLE(newPage, pageSize(newPage) - 2, TN_HEADER_SIZE);

        newNode.leftSegTail(TN_HEADER_SIZE + encodedLen);
        newNode.releaseExclusive();
    }

    /**
     * @param okey original key
     * @param akey key to actually store
     * @param vfrag 0 or ENTRY_FRAGMENTED
     * @param encodedLen length of new entry to allocate
     * @param pos normalized search vector position of entry to insert/update
     */
    private void splitLeafAndCreateEntry(BTree tree, byte[] okey, byte[] akey,
                                         int vfrag, byte[] value,
                                         int encodedLen, int pos, boolean forInsert)
        throws IOException
    {
        if (mSplit != null) {
            throw new AssertionError("Node is already split");
        }

        // Split can move node entries to a new left or right node. Choose such that the
        // new entry is more likely to go into the new node. This distributes the cost of
        // the split by postponing compaction of this node.

        // Since the split key and final node sizes are not known in advance, don't
        // attempt to properly center the new search vector. Instead, minimize
        // fragmentation to ensure that split is successful.

        var page = mPage;
        checkClosedIndexException(page);

        Node newNode = tree.mDatabase.allocDirtyNode(NodeGroup.MODE_UNEVICTABLE);
        tree.mDatabase.nodeMapPut(newNode);

        var newPage = newNode.mPage;

        /*P*/ // [
        newNode.garbage(0);
        /*P*/ // |
        /*P*/ // p_intPutLE(newPage, 0, 0); // set type (fixed later), reserved byte, and garbage
        /*P*/ // ]

        if (forInsert && pos == 0) {
            // Inserting into left edge of node, possibly because inserts are
            // descending. Split into new left node, but only the new entry
            // goes into the new node.

            Split split = null;
            try {
                split = newSplitLeft(newNode);
                // Choose an appropriate middle key for suffix compression.
                split.setKey(tree, midKey(okey, 0));
            } catch (Throwable e) {
                cleanupSplit(e, newNode, split);
                throw e;
            }

            mSplit = split;

            // Position search vector at extreme left, allowing new entries to
            // be placed in a natural descending order.
            newNode.leftSegTail(TN_HEADER_SIZE);
            newNode.searchVecStart(TN_HEADER_SIZE);
            newNode.searchVecEnd(TN_HEADER_SIZE);

            int destLoc = pageSize(newPage) - encodedLen;
            newNode.copyToLeafEntry(okey, akey, vfrag, value, destLoc);
            p_shortPutLE(newPage, TN_HEADER_SIZE, destLoc);

            newNode.rightSegTail(destLoc - 1);
            newNode.releaseExclusive();

            return;
        }

        final int searchVecStart = searchVecStart();
        final int searchVecEnd = searchVecEnd();

        pos += searchVecStart;

        if (forInsert && pos == searchVecEnd + 2) {
            // Inserting into right edge of node, possibly because inserts are
            // ascending. Split into new right node, but only the new entry
            // goes into the new node.

            Split split = null;
            try {
                split = newSplitRight(newNode);
                // Choose an appropriate middle key for suffix compression.
                split.setKey(tree, midKey(pos - searchVecStart - 2, okey));
            } catch (Throwable e) {
                cleanupSplit(e, newNode, split);
                throw e;
            }

            mSplit = split;

            // Position search vector at extreme right, allowing new entries to
            // be placed in a natural ascending order.
            newNode.rightSegTail(pageSize(newPage) - 1);
            int newSearchVecStart = pageSize(newPage) - 2;
            newNode.searchVecStart(newSearchVecStart);
            newNode.searchVecEnd(newSearchVecStart);

            newNode.copyToLeafEntry(okey, akey, vfrag, value, TN_HEADER_SIZE);
            p_shortPutLE(newPage, pageSize(newPage) - 2, TN_HEADER_SIZE);

            newNode.leftSegTail(TN_HEADER_SIZE + encodedLen);
            newNode.releaseExclusive();

            return;
        }

        // Amount of bytes available in unsplit node.
        int avail = availableLeafBytes();

        int garbageAccum = 0;
        int newLoc = 0;
        int newAvail = pageSize(newPage) - TN_HEADER_SIZE;

        // Guess which way to split by examining search position. This doesn't take into
        // consideration the variable size of the entries. If the guess is wrong, the new
        // entry is inserted into original node, which now has space.

        if ((pos - searchVecStart) < (searchVecEnd - pos)) {
            // Split into new left node.

            int destLoc = pageSize(newPage);
            int newSearchVecLoc = TN_HEADER_SIZE;

            // Is assigned if value needed to be fragmented. Used by exception handler below.
            byte[] fv = null;

            int searchVecLoc = searchVecStart;
            for (; newAvail > avail; searchVecLoc += 2, newSearchVecLoc += 2) {
                int entryLoc = p_ushortGetLE(page, searchVecLoc);
                int entryLen = leafEntryLengthAtLoc(page, entryLoc);

                if (searchVecLoc == pos) {
                    if ((newAvail -= encodedLen + 2) < 0) {
                        // Entry doesn't fit into new node. If value hasn't been fragmented
                        // yet, then fragment the value to make it fit.
                        if (vfrag != 0) {
                            break;
                        }

                        newAvail += encodedLen + 2; // undo

                        var params = new FragParams();
                        params.value = value;
                        params.encodedLen = encodedLen;
                        params.available = newAvail;

                        try {
                            fragmentValueForSplit(tree, params);
                        } catch (Throwable e) {
                            cleanupSplit(e, newNode, null);
                            throw e;
                        }

                        vfrag = ENTRY_FRAGMENTED;
                        fv = value = params.value;
                        encodedLen = params.encodedLen;
                        newAvail = params.available;
                    }

                    newLoc = newSearchVecLoc;

                    if (forInsert) {
                        // Reserve slot in vector for new entry.
                        newSearchVecLoc += 2;
                        if (newAvail <= avail) {
                            // Balanced enough.
                            break;
                        }
                    } else {
                        // Don't copy old entry.
                        garbageAccum += entryLen;
                        avail += entryLen;
                        continue;
                    }
                }

                if (searchVecLoc == searchVecEnd) {
                    // At least one entry must remain in the original node.
                    break;
                }

                if ((newAvail -= entryLen + 2) < 0) {
                    // Entry doesn't fit into new node.
                    break;
                }

                // Copy entry and point to it.
                destLoc -= entryLen;
                p_copy(page, entryLoc, newPage, destLoc, entryLen);
                p_shortPutLE(newPage, newSearchVecLoc, destLoc);

                garbageAccum += entryLen;
                avail += entryLen + 2;
            }

            newNode.leftSegTail(TN_HEADER_SIZE);
            newNode.searchVecStart(TN_HEADER_SIZE);
            newNode.searchVecEnd(newSearchVecLoc - 2);

            // Prune off the left end of this node.
            final int originalStart = searchVecStart();
            final int originalGarbage = garbage();
            searchVecStart(searchVecLoc);
            garbage(originalGarbage + garbageAccum);

            try {
                // Assign early, to signal to updateLeafValue that it should fragment a large
                // value instead of attempting to double split the node.
                mSplit = newSplitLeft(newNode);

                if (newLoc == 0) {
                    // Unable to insert new entry into left node. Insert it
                    // into the right node, which should have space now.
                    fv = storeIntoSplitLeaf(tree, okey, akey, vfrag, value, encodedLen, forInsert);
                } else {
                    // Create new entry and point to it.
                    destLoc -= encodedLen;
                    newNode.copyToLeafEntry(okey, akey, vfrag, value, destLoc);
                    p_shortPutLE(newPage, newLoc, destLoc);
                }

                // Choose an appropriate middle key for suffix compression.
                mSplit.setKey(tree, newNode.midKey(newNode.highestKeyPos(), this, 0));

                newNode.rightSegTail(destLoc - 1);
                newNode.releaseExclusive();
            } catch (Throwable e) {
                searchVecStart(originalStart);
                garbage(originalGarbage);
                cleanupFragments(e, fv);
                cleanupSplit(e, newNode, mSplit);
                mSplit = null;
                throw e;
            }
        } else {
            // Split into new right node.

            int destLoc = TN_HEADER_SIZE;
            int newSearchVecLoc = pageSize(newPage) - 2;

            // Is assigned if value needed to be fragmented. Used by exception handler below.
            byte[] fv = null;

            int searchVecLoc = searchVecEnd;
            for (; newAvail > avail; searchVecLoc -= 2, newSearchVecLoc -= 2) {
                int entryLoc = p_ushortGetLE(page, searchVecLoc);
                int entryLen = leafEntryLengthAtLoc(page, entryLoc);

                if (forInsert) {
                    if (searchVecLoc + 2 == pos) {
                        if ((newAvail -= encodedLen + 2) < 0) {
                            // Inserted entry doesn't fit into new node. If value hasn't been
                            // fragmented yet, then fragment the value to make it fit.
                            if (vfrag != 0) {
                                break;
                            }

                            newAvail += encodedLen + 2; // undo

                            var params = new FragParams();
                            params.value = value;
                            params.encodedLen = encodedLen;
                            params.available = newAvail;

                            try {
                                fragmentValueForSplit(tree, params);
                            } catch (Throwable e) {
                                cleanupSplit(e, newNode, null);
                                throw e;
                            }

                            vfrag = ENTRY_FRAGMENTED;
                            fv = value = params.value;
                            encodedLen = params.encodedLen;
                            newAvail = params.available;
                        }

                        // Reserve spot in vector for new entry.
                        newLoc = newSearchVecLoc;
                        newSearchVecLoc -= 2;
                        if (newAvail <= avail) {
                            // Balanced enough.
                            break;
                        }
                    }
                } else {
                    if (searchVecLoc == pos) {
                        if ((newAvail -= encodedLen + 2) < 0) {
                            // Updated entry doesn't fit into new node. If value hasn't been
                            // fragmented yet, then fragment the value to make it fit.
                            if (vfrag != 0) {
                                break;
                            }

                            newAvail += encodedLen + 2; // undo

                            var params = new FragParams();
                            params.value = value;
                            params.encodedLen = encodedLen;
                            params.available = newAvail;

                            try {
                                fragmentValueForSplit(tree, params);
                            } catch (Throwable e) {
                                cleanupSplit(e, newNode, null);
                                throw e;
                            }

                            vfrag = ENTRY_FRAGMENTED;
                            fv = value = params.value;
                            encodedLen = params.encodedLen;
                            newAvail = params.available;
                        }

                        // Don't copy old entry.
                        newLoc = newSearchVecLoc;
                        garbageAccum += entryLen;
                        avail += entryLen;
                        continue;
                    }
                }

                if (searchVecLoc == searchVecStart) {
                    // At least one entry must remain in the original node.
                    break;
                }

                if ((newAvail -= entryLen + 2) < 0) {
                    // Entry doesn't fit into new node.
                    break;
                }

                // Copy entry and point to it.
                p_copy(page, entryLoc, newPage, destLoc, entryLen);
                p_shortPutLE(newPage, newSearchVecLoc, destLoc);
                destLoc += entryLen;

                garbageAccum += entryLen;
                avail += entryLen + 2;
            }

            newNode.rightSegTail(pageSize(newPage) - 1);
            newNode.searchVecStart(newSearchVecLoc + 2);
            newNode.searchVecEnd(pageSize(newPage) - 2);

            // Prune off the right end of this node.
            final int originalEnd = searchVecEnd();
            final int originalGarbage = garbage();
            searchVecEnd(searchVecLoc);
            garbage(originalGarbage + garbageAccum);

            try {
                // Assign early, to signal to updateLeafValue that it should fragment a large
                // value instead of attempting to double split the node.
                mSplit = newSplitRight(newNode);

                if (newLoc == 0) {
                    // Unable to insert new entry into new right node. Insert it into the
                    // left node, which should have space now.
                    fv = storeIntoSplitLeaf(tree, okey, akey, vfrag, value, encodedLen, forInsert);
                } else {
                    // Create new entry and point to it.
                    newNode.copyToLeafEntry(okey, akey, vfrag, value, destLoc);
                    p_shortPutLE(newPage, newLoc, destLoc);
                    destLoc += encodedLen;
                }

                // Choose an appropriate middle key for suffix compression.
                mSplit.setKey(tree, this.midKey(this.highestKeyPos(), newNode, 0));

                newNode.leftSegTail(destLoc);
                newNode.releaseExclusive();
            } catch (Throwable e) {
                searchVecEnd(originalEnd);
                garbage(originalGarbage);
                cleanupFragments(e, fv);
                cleanupSplit(e, newNode, mSplit);
                mSplit = null;
                throw e;
            }
        }
    }

    /**
     * In/out parameters passed to the fragmentValue method.
     */
    private static final class FragParams {
        byte[] value;   // in: unfragmented value;  out: fragmented value
        int encodedLen; // in: entry encoded length;  out: updated entry encoded length
        int available;  // in: available bytes in the target leaf node;  out: updated
    }

    /**
     * Fragments a value to fit into a node which is splitting.
     */
    private static void fragmentValueForSplit(BTree tree, FragParams params) throws IOException {
        byte[] value = params.value;

        // Compute the encoded key length by subtracting off the value length. This properly
        // handles the case where the key has been fragmented.
        int encodedKeyLen = params.encodedLen - calculateLeafValueLength(value);

        LocalDatabase db = tree.mDatabase;

        // Maximum allowed size for fragmented value is limited by available node space
        // (accounting for the entry pointer), the maximum allowed fragmented entry size, and
        // the space occupied by the key.
        int max = Math.min(params.available - 2, db.mMaxFragmentedEntrySize) - encodedKeyLen;

        value = db.fragment(value, value.length, max);

        if (value == null) {
            // This shouldn't happen with a properly defined maximum key size.
            throw new AssertionError("Frag max: " + max);
        }

        params.value = value;
        params.encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);

        if ((params.available -= params.encodedLen + 2) < 0) {
            // Miscalculated the maximum allowed size.
            throw new AssertionError();
        }
    }

    /**
     * Store an entry into a node which has just been split and has room. If for update, caller
     * must ensure that the mSplit field has been set. It doesn't need to be fully filled in
     * yet, however. The updateLeafValue checks if the mSplit field has been set to prevent
     * double splitting.
     *
     * @param okey original key
     * @param akey key to actually store
     * @param vfrag 0 or ENTRY_FRAGMENTED
     * @return non-null if value got fragmented
     */
    private byte[] storeIntoSplitLeaf(BTree tree, byte[] okey, byte[] akey,
                                      int vfrag, byte[] value,
                                      int encodedLen, boolean forInsert)
        throws IOException
    {
        int pos = binarySearch(okey);
        if (!forInsert) {
            if (pos < 0) {
                throw new AssertionError("Key not found");
            }
            updateLeafValue(tree, pos, vfrag, value);
            return null;
        }

        if (pos >= 0) {
            throw new AssertionError("Key exists");
        }

        int entryLoc = createLeafEntry(null, tree, ~pos, encodedLen);
        byte[] result = null;

        while (entryLoc < 0) {
            if (vfrag != 0) {
                // Not expected.
                throw new DatabaseException("Fragmented entry doesn't fit");
            }

            var params = new FragParams();
            params.value = value;
            params.encodedLen = encodedLen;
            params.available = ~entryLoc;

            fragmentValueForSplit(tree, params);

            vfrag = ENTRY_FRAGMENTED;
            result = value = params.value;
            encodedLen = params.encodedLen;

            entryLoc = createLeafEntry(null, tree, ~pos, encodedLen);
        }

        copyToLeafEntry(okey, akey, vfrag, value, entryLoc);
        return result;
    }

    /**
     * @param result split result stored here; key and entry loc is -1 if new key was promoted
     * to parent
     * @throws IOException if new node could not be allocated; no side-effects
     */
    private void splitInternal(final InResult result, final int encodedLen,
                               final int keyPos, final int newChildPos)
        throws IOException
    {
        if (mSplit != null) {
            throw new AssertionError("Node is already split");
        }

        // Split can move node entries to a new left or right node. Choose such that the
        // new entry is more likely to go into the new node. This distributes the cost of
        // the split by postponing compaction of this node.

        // Alloc early in case an exception is thrown.

        final LocalDatabase db = getDatabase();

        Node newNode;
        try {
            newNode = db.allocDirtyNode(NodeGroup.MODE_UNEVICTABLE);
        } catch (DatabaseFullException e) {
            // Internal node splits are critical. If a child node reference cannot be inserted,
            // then it would be orphaned. Try allocating again without any capacity limit, or
            // else the caller must panic the database.
            db.capacityLimitOverride(-1);
            try {
                newNode = db.allocDirtyNode(NodeGroup.MODE_UNEVICTABLE);
            } finally {
                db.capacityLimitOverride(0);
            }
        }

        db.nodeMapPut(newNode);

        final var newPage = newNode.mPage;

        /*P*/ // [
        newNode.garbage(0);
        /*P*/ // |
        /*P*/ // p_intPutLE(newPage, 0, 0); // set type (fixed later), reserved byte, and garbage
        /*P*/ // ]

        final var page = mPage;

        final int searchVecStart = searchVecStart();
        final int searchVecEnd = searchVecEnd();

        if ((searchVecEnd - searchVecStart) == 2 && keyPos == 2) {
            // Node has two keys and the key to insert should go in the middle. The new key
            // should not be inserted, but instead be promoted to the parent. Treat this as a
            // special case -- the code below only promotes an existing key to the parent.
            // This case is expected to only occur when using large keys.

            // Allocate Split object first, in case it throws an OutOfMemoryError.
            Split split;
            try {
                split = newSplitLeft(newNode);
            } catch (Throwable e) {
                cleanupSplit(e, newNode, null);
                throw e;
            }

            // Signals that key should not be inserted.
            result.mEntryLoc = -1;

            int leftKeyLoc = p_ushortGetLE(page, searchVecStart);
            int leftKeyLen = keyLengthAtLoc(page, leftKeyLoc);

            // Assume a large key will be inserted later, so arrange it with room: entry at far
            // left and search vector at far right.
            p_copy(page, leftKeyLoc, newPage, TN_HEADER_SIZE, leftKeyLen);
            int leftSearchVecStart = pageSize(newPage) - (2 + 8 + 8);
            p_shortPutLE(newPage, leftSearchVecStart, TN_HEADER_SIZE);

            if (newChildPos == 8) {
                // Caller must store child id into left node.
                result.mPage = newPage;
                result.mNewChildLoc = leftSearchVecStart + (2 + 8);
            } else {
                if (newChildPos != 16) {
                    throw new AssertionError();
                }
                // Caller must store child id into right node.
                result.mPage = page;
                result.mNewChildLoc = searchVecEnd + (2 + 8);
            }

            // Copy one or two left existing child ids to left node (newChildPos is 8 or 16).
            p_copy(page, searchVecEnd + 2, newPage, leftSearchVecStart + 2, newChildPos);

            newNode.leftSegTail(TN_HEADER_SIZE + leftKeyLen);
            newNode.rightSegTail(leftSearchVecStart + (2 + 8 + 8 - 1));
            newNode.searchVecStart(leftSearchVecStart);
            newNode.searchVecEnd(leftSearchVecStart);
            newNode.releaseExclusive();

            // Prune off the left end of this node by shifting vector towards child ids.
            p_copy(page, searchVecEnd, page, searchVecEnd + 8, 2);
            int newSearchVecStart = searchVecEnd + 8;
            searchVecStart(newSearchVecStart);
            searchVecEnd(newSearchVecStart);

            garbage(garbage() + leftKeyLen);

            // Caller must set the split key.
            mSplit = split;

            return;
        }

        result.mPage = newPage;
        final int keyLoc = keyPos + searchVecStart;

        int garbageAccum;
        int newKeyLoc;

        // Guess which way to split by examining search position. This doesn't take into
        // consideration the variable size of the entries. If the guess is wrong, do over
        // the other way. Internal splits are infrequent, and split guesses are usually
        // correct. For these reasons, it isn't worth the trouble to create a special case
        // to charge ahead with the wrong guess. Leaf node splits are more frequent, and
        // incorrect guesses are easily corrected due to the simpler leaf node structure.

        // -2: left
        // -1: guess left
        // +1: guess right
        // +2: right
        int splitSide = (keyPos < (searchVecEnd - searchVecStart - keyPos)) ? -1 : 1;

        Split split = null;
        doSplit: while (true) {
            garbageAccum = 0;
            newKeyLoc = 0;

            // Amount of bytes used in unsplit node, including the page header.
            int size = 5 * (searchVecEnd - searchVecStart) + (1 + 8 + 8)
                + leftSegTail() + pageSize(page) - rightSegTail() - garbage();

            int newSize = TN_HEADER_SIZE;

            // Adjust sizes for extra child id -- always one more than number of keys.
            size -= 8;
            newSize += 8;

            if (splitSide < 0) {
                // Split into new left node.

                // Since the split key and final node sizes are not known in advance,
                // don't attempt to properly center the new search vector. Instead,
                // minimize fragmentation to ensure that split is successful.

                int destLoc = pageSize(newPage);
                int newSearchVecLoc = TN_HEADER_SIZE;

                int searchVecLoc = searchVecStart;
                while (true) {
                    if (searchVecLoc == keyLoc) {
                        newKeyLoc = newSearchVecLoc;
                        newSearchVecLoc += 2;
                        // Reserve slot in vector for new entry and account for size increase.
                        newSize += encodedLen + (2 + 8);
                        if (newSize > pageSize(newPage)) {
                            // New entry doesn't fit.
                            if (splitSide == -1) {
                                // Guessed wrong; do over on left side.
                                splitSide = 2;
                                continue doSplit;
                            }
                            // Impossible split. No room for new entry anywhere.
                            throw new AssertionError();
                        }
                    }

                    int entryLoc = p_ushortGetLE(page, searchVecLoc);
                    int entryLen = keyLengthAtLoc(page, entryLoc);

                    // Size change must incorporate child id, although they are copied later.
                    int sizeChange = entryLen + (2 + 8);
                    size -= sizeChange;
                    newSize += sizeChange;

                    searchVecLoc += 2;

                    // Note that last examined key is not moved but is dropped. Garbage must
                    // account for this.
                    garbageAccum += entryLen;

                    boolean full = size < TN_HEADER_SIZE | newSize > pageSize(newPage);

                    if (full || newSize >= size) {
                        // New node has accumulated enough entries...

                        if (newKeyLoc != 0) {
                            // ...and split key has been found.
                            try {
                                split = newSplitLeft(newNode);
                                retrieveKeyAtLoc(page, entryLoc, split);
                            } catch (Throwable e) {
                                cleanupSplit(e, newNode, split);
                                throw e;
                            }
                            break;
                        }

                        if (splitSide == -1) {
                            // Guessed wrong; do over on right side.
                            splitSide = 2;
                            continue doSplit;
                        }

                        // Keep searching on this side for new entry location.
                        if (full || splitSide != -2) {
                            throw new AssertionError();
                        }
                    }

                    // Copy key entry and point to it.
                    destLoc -= entryLen;
                    p_copy(page, entryLoc, newPage, destLoc, entryLen);
                    p_shortPutLE(newPage, newSearchVecLoc, destLoc);
                    newSearchVecLoc += 2;
                }

                result.mEntryLoc = destLoc - encodedLen;

                // Copy existing child ids and insert new child id.
                {
                    p_copy(page, searchVecEnd + 2, newPage, newSearchVecLoc, newChildPos);

                    // Leave gap for new child id, to be set by caller.
                    result.mNewChildLoc = newSearchVecLoc + newChildPos;

                    int tailChildIdsLen = ((searchVecLoc - searchVecStart) << 2) - newChildPos;
                    p_copy(page, searchVecEnd + 2 + newChildPos,
                           newPage, newSearchVecLoc + newChildPos + 8, tailChildIdsLen);
                }

                newNode.leftSegTail(TN_HEADER_SIZE);
                newNode.rightSegTail(destLoc - encodedLen - 1);
                newNode.searchVecStart(TN_HEADER_SIZE);
                newNode.searchVecEnd(newSearchVecLoc - 2);
                newNode.releaseExclusive();

                // Prune off the left end of this node by shifting vector towards child ids.
                int shift = (searchVecLoc - searchVecStart) << 2;
                int len = searchVecEnd - searchVecLoc + 2;
                int newSearchVecStart = searchVecLoc + shift;
                p_copy(page, searchVecLoc, page, newSearchVecStart, len);
                searchVecStart(newSearchVecStart);
                searchVecEnd(searchVecEnd + shift);
            } else {
                // Split into new right node.

                // First copy keys and not the child ids. After keys are copied, shift to
                // make room for child ids and copy them in place.

                int destLoc = TN_HEADER_SIZE;
                int newSearchVecLoc = pageSize(newPage);

                int searchVecLoc = searchVecEnd + 2;
                moveEntries: while (true) {
                    if (searchVecLoc == keyLoc) {
                        newSearchVecLoc -= 2;
                        newKeyLoc = newSearchVecLoc;
                        // Reserve slot in vector for new entry and account for size increase.
                        newSize += encodedLen + (2 + 8);
                        if (newSize > pageSize(newPage)) {
                            // New entry doesn't fit.
                            if (splitSide == 1) {
                                // Guessed wrong; do over on left side.
                                splitSide = -2;
                                continue doSplit;
                            }
                            // Impossible split. No room for new entry anywhere.
                            throw new AssertionError();
                        }
                    }

                    searchVecLoc -= 2;

                    int entryLoc = p_ushortGetLE(page, searchVecLoc);
                    int entryLen = keyLengthAtLoc(page, entryLoc);

                    // Size change must incorporate child id, although they are copied later.
                    int sizeChange = entryLen + (2 + 8);
                    size -= sizeChange;
                    newSize += sizeChange;

                    // Note that last examined key is not moved but is dropped. Garbage must
                    // account for this.
                    garbageAccum += entryLen;

                    boolean full = size < TN_HEADER_SIZE | newSize > pageSize(newPage);

                    if (full || newSize >= size) {
                        // New node has accumulated enough entries...

                        if (newKeyLoc != 0) {
                            // ...and split key has been found.
                            try {
                                split = newSplitRight(newNode);
                                retrieveKeyAtLoc(page, entryLoc, split);
                            } catch (Throwable e) {
                                cleanupSplit(e, newNode, split);
                                throw e;
                            }
                            break moveEntries;
                        }

                        if (splitSide == 1) {
                            // Guessed wrong; do over on left side.
                            splitSide = -2;
                            continue doSplit;
                        }

                        // Keep searching on this side for new entry location.
                        if (full || splitSide != 2) {
                            throw new AssertionError();
                        }
                    }

                    // Copy key entry and point to it.
                    p_copy(page, entryLoc, newPage, destLoc, entryLen);
                    newSearchVecLoc -= 2;
                    p_shortPutLE(newPage, newSearchVecLoc, destLoc);
                    destLoc += entryLen;
                }

                result.mEntryLoc = destLoc;

                // Move new search vector to make room for child ids and be centered between
                // the segments.
                int newVecLen = pageSize(page) - newSearchVecLoc;
                {
                    int highestLoc = pageSize(newPage) - (5 * newVecLen) - 8;
                    int midLoc = ((destLoc + encodedLen + highestLoc + 1) >> 1) & ~1;
                    p_copy(newPage, newSearchVecLoc, newPage, midLoc, newVecLen);
                    newKeyLoc -= newSearchVecLoc - midLoc;
                    newSearchVecLoc = midLoc;
                }

                int newSearchVecEnd = newSearchVecLoc + newVecLen - 2;

                // Copy existing child ids and insert new child id.
                {
                    int headChildIdsLen = newChildPos - ((searchVecLoc - searchVecStart + 2) << 2);
                    int newDestLoc = newSearchVecEnd + 2;
                    p_copy(page, searchVecEnd + 2 + newChildPos - headChildIdsLen,
                           newPage, newDestLoc, headChildIdsLen);

                    // Leave gap for new child id, to be set by caller.
                    newDestLoc += headChildIdsLen;
                    result.mNewChildLoc = newDestLoc;

                    int tailChildIdsLen =
                        ((searchVecEnd - searchVecStart) << 2) + 16 - newChildPos;
                    p_copy(page, searchVecEnd + 2 + newChildPos,
                           newPage, newDestLoc + 8, tailChildIdsLen);
                }

                newNode.leftSegTail(destLoc + encodedLen);
                newNode.rightSegTail(pageSize(newPage) - 1);
                newNode.searchVecStart(newSearchVecLoc);
                newNode.searchVecEnd(newSearchVecEnd);
                newNode.releaseExclusive();

                // Prune off the right end of this node by shifting vector towards child ids.
                int len = searchVecLoc - searchVecStart;
                int newSearchVecStart = searchVecEnd + 2 - len;
                p_copy(page, searchVecStart, page, newSearchVecStart, len);
                searchVecStart(newSearchVecStart);
            }

            break;
        } // end doSplit

        garbage(garbage() + garbageAccum);
        mSplit = split;

        // Write pointer to key entry.
        p_shortPutLE(newPage, newKeyLoc, result.mEntryLoc);
    }

    /**
     * Compact internal node by reclaiming garbage and moving search vector
     * towards tail. Caller is responsible for ensuring that new entry will fit
     * after compaction. Space is allocated for new entry, and the search
     * vector points to it.
     *
     * @param result return result stored here
     * @param encodedLen length of new entry to allocate
     * @param keyPos normalized search vector position of key to insert/update
     * @param childPos normalized search vector position of child node id to insert; pass
     * MIN_VALUE if updating
     */
    private void compactInternal(InResult result, int encodedLen, int keyPos, int childPos) {
        var page = mPage;

        int searchVecLoc = searchVecStart();
        keyPos += searchVecLoc;
        // Size of search vector, possibly with new entry.
        int newSearchVecSize = searchVecEnd() - searchVecLoc + (2 + 2) + (childPos >> 30);

        // Determine new location of search vector, with room to grow on both ends.
        int newSearchVecStart;
        // Capacity available to search vector after compaction.
        int searchVecCap = garbage() + rightSegTail() + 1 - leftSegTail() - encodedLen;
        newSearchVecStart = pageSize(page) -
            (((searchVecCap + newSearchVecSize + ((newSearchVecSize + 2) << 2)) >> 1) & ~1);

        // Copy into a fresh buffer.

        int destLoc = TN_HEADER_SIZE;
        int newSearchVecLoc = newSearchVecStart;
        int newLoc = 0;
        final int searchVecEnd = searchVecEnd();

        var dest = mGroup.acquireSparePage();

        /*P*/ // [|
        /*P*/ // p_intPutLE(dest, 0, type() & 0xff); // set type, reserved byte, and garbage
        /*P*/ // ]

        for (; searchVecLoc <= searchVecEnd; searchVecLoc += 2, newSearchVecLoc += 2) {
            if (searchVecLoc == keyPos) {
                newLoc = newSearchVecLoc;
                if (childPos >= 0) {
                    newSearchVecLoc += 2;
                } else {
                    continue;
                }
            }
            p_shortPutLE(dest, newSearchVecLoc, destLoc);
            int sourceLoc = p_ushortGetLE(page, searchVecLoc);
            int len = keyLengthAtLoc(page, sourceLoc);
            p_copy(page, sourceLoc, dest, destLoc, len);
            destLoc += len;
        }

        if (childPos >= 0) {
            if (newLoc == 0) {
                newLoc = newSearchVecLoc;
                newSearchVecLoc += 2;
            }

            // Copy child ids, and leave room for inserted child id.
            p_copy(page, searchVecEnd() + 2, dest, newSearchVecLoc, childPos);
            p_copy(page, searchVecEnd() + 2 + childPos,
                   dest, newSearchVecLoc + childPos + 8,
                   (newSearchVecSize << 2) - childPos);
        } else {
            if (newLoc == 0) {
                newLoc = newSearchVecLoc;
            }

            // Copy child ids.
            p_copy(page, searchVecEnd() + 2, dest, newSearchVecLoc, (newSearchVecSize << 2) + 8);
        }

        /*P*/ // [
        // Recycle old page buffer and swap in compacted page.
        mGroup.releaseSparePage(page);
        mPage = dest;
        garbage(0);
        /*P*/ // |
        /*P*/ // if (getDatabase().mFullyMapped) {
        /*P*/ //     // Copy compacted entries to original page and recycle spare page buffer.
        /*P*/ //     p_copy(dest, 0, page, 0, pageSize(page));
        /*P*/ //     mGroup.releaseSparePage(dest);
        /*P*/ //     dest = page;
        /*P*/ // } else {
        /*P*/ //     // Recycle old page buffer and swap in compacted page.
        /*P*/ //     mGroup.releaseSparePage(page);
        /*P*/ //     mPage = dest;
        /*P*/ // }
        /*P*/ // ]

        // Write pointer to key entry.
        p_shortPutLE(dest, newLoc, destLoc);

        leftSegTail(destLoc + encodedLen);
        rightSegTail(pageSize(dest) - 1);
        searchVecStart(newSearchVecStart);
        searchVecEnd(newSearchVecLoc - 2);

        result.mPage = dest;
        result.mNewChildLoc = newSearchVecLoc + childPos;
        result.mEntryLoc = destLoc;
    }

    /**
     * Provides information necessary to complete split by copying split key, pointer to
     * split key, and pointer to new child id.
     */
    static final class InResult {
        /*P*/ byte[] mPage;
        int mNewChildLoc; // location of child pointer
        int mEntryLoc;    // location of key entry, referenced by search vector
    }

    private Split newSplitLeft(Node newNode) {
        var split = new Split(false, newNode);
        // New left node cannot be a high extremity, and this node cannot be a low extremity.
        newNode.type((byte) (type() & ~HIGH_EXTREMITY));
        type((byte) (type() & ~LOW_EXTREMITY));
        return split;
    }

    private Split newSplitRight(Node newNode) {
        var split = new Split(true, newNode);
        // New right node cannot be a low extremity, and this node cannot be a high extremity.
        newNode.type((byte) (type() & ~LOW_EXTREMITY));
        type((byte) (type() & ~HIGH_EXTREMITY));
        return split;
    }

    @FunctionalInterface
    static interface Supplier {
        /**
         * @return new node, properly initialized
         */
        Node newNode() throws IOException;
    }

    /**
     * Appends an entry to a node, in no particular order. Node must have been originally
     * initialized with the asSortLeaf method. Call sortLeaf to sort the entries by key and
     * delete any duplicates. The duplicates added last are kept.
     *
     * <p>If this node is full, the given supplier is called. It must supply a node which was
     * properly initialized for receiving appended entries.
     *
     * @param node node to append to
     * @param okey original key
     * @return given node, or the next node from the supplier
     */
    static Node appendToSortLeaf(Node node, LocalDatabase db,
                                 byte[] okey, byte[] value, Supplier supplier)
        throws IOException
    {
        byte[] akey = okey;
        int encodedKeyLen = calculateAllowedKeyLength(db, okey);

        if (encodedKeyLen < 0) {
            // Key must be fragmented.
            akey = db.fragmentKey(okey);
            encodedKeyLen = 2 + akey.length;
        }

        try {
            int encodedLen = encodedKeyLen + calculateLeafValueLength(value);

            int vfrag;
            if (encodedLen <= db.mMaxEntrySize) {
                vfrag = 0;
            } else {
                value = db.fragment(value, value.length,
                                    db.mMaxFragmentedEntrySize - encodedKeyLen);
                if (value == null) {
                    throw new AssertionError();
                }
                encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);
                vfrag = ENTRY_FRAGMENTED;
            }

            try {
                while (true) {
                    var page = node.mPage;
                    int tail = node.leftSegTail();

                    int start;
                    if (tail == TN_HEADER_SIZE) {
                        // Freshly initialized node.
                        if (isClosedOrDeleted(page)) {
                            throw new DatabaseException("Closed");
                        }
                        start = node.pageSize(page) - 2;
                        node.searchVecEnd(start);
                    } else {
                        start = node.searchVecStart() - 2;
                        if (encodedLen > (start - tail)) {
                            // Entry doesn't fit, so get another node.
                            node.releaseExclusive();
                            node = supplier.newNode();
                            continue;
                        }
                    }

                    node.copyToLeafEntry(okey, akey, vfrag, value, tail);
                    node.leftSegTail(tail + encodedLen);

                    p_shortPutLE(page, start, tail);
                    node.searchVecStart(start);
                    return node;
                }
            } catch (Throwable e) {
                if (vfrag == ENTRY_FRAGMENTED) {
                    node.cleanupFragments(e, value);
                }
                throw e;
            }
        } catch (Throwable e) {
            if (okey != akey) {
                node.cleanupFragments(e, akey);
            }
            throw e;
        }
    }

    /**
     * Sorts all the entries in a leaf node by key, and deletes any duplicates. The duplicates
     * at the highest node locations are kept.
     */
    void sortLeaf() throws IOException {
        final int len = searchVecEnd() + 2 - searchVecStart();
        if (len <= 2) { // two-based length; actual length is half
            return;
        }

        // First heapify, highest at the root.

        final int halfPos = (len >>> 1) & ~1;
        for (int pos = halfPos; (pos -= 2) >= 0; ) {
            siftDownLeaf(pos, len, halfPos);
        }

        // Now finish the sort, reversing the heap order.

        final var page = mPage;
        final int start = searchVecStart();

        int lastHighLoc = -1;
        int vecPos = start + len;
        int pos = len - 2;
        do {
            int highLoc = p_ushortGetLE(page, start);
            p_shortPutLE(page, start, p_ushortGetLE(page, start + pos));
            if (highLoc != lastHighLoc) {
                // Add a non-duplicated pointer.
                p_shortPutLE(page, vecPos -= 2, highLoc);
                lastHighLoc = highLoc;
            }
            if (pos > 2) {
                siftDownLeaf(0, pos, (pos >>> 1) & ~1);
            }
        } while ((pos -= 2) >= 0);

        searchVecStart(vecPos);
    }

    /**
     * @param pos two-based position in search vector
     * @param endPos two-based exclusive end position in search vector
     * @param halfPos {@literal (endPos >>> 1) & ~1}
     */
    private void siftDownLeaf(int pos, int endPos, int halfPos) throws IOException {
        final var page = mPage;
        final int start = searchVecStart();
        int loc = p_ushortGetLE(page, start + pos);

        do {
            int childPos = (pos << 1) + 2;
            int childLoc = p_ushortGetLE(page, start + childPos);
            int rightPos = childPos + 2;
            if (rightPos < endPos) {
                int rightLoc = p_ushortGetLE(page, start + rightPos);
                int compare = compareKeys(this, childLoc, this, rightLoc);
                if (compare < 0) {
                    childPos = rightPos;
                    childLoc = rightLoc;
                } else if (compare == 0) {
                    // Found a duplicate key. Use a common pointer, favoring the higher one.
                    if (childLoc < rightLoc) {
                        replaceDuplicateLeafEntry(page, childLoc, rightLoc);
                        if (loc == childLoc) {
                            return;
                        }
                        childLoc = rightLoc;
                    } else if (childLoc > rightLoc) {
                        replaceDuplicateLeafEntry(page, rightLoc, childLoc);
                        if (loc == rightLoc) {
                            return;
                        }
                    }
                }
            }
            int compare = compareKeys(this, loc, this, childLoc);
            if (compare < 0) {
                p_shortPutLE(page, start + pos, childLoc);
                pos = childPos;
            } else {
                if (compare == 0) {
                    // Found a duplicate key. Use a common pointer, favoring the higher one.
                    if (loc < childLoc) {
                        replaceDuplicateLeafEntry(page, loc, childLoc);
                        loc = childLoc;
                    } else if (loc > childLoc) {
                        replaceDuplicateLeafEntry(page, childLoc, loc);
                    }
                }
                break;
            }
        } while (pos < halfPos);

        p_shortPutLE(page, start + pos, loc);
    }

    private void replaceDuplicateLeafEntry(/*P*/ byte[] page, int loc, int newLoc)
        throws IOException
    {
        int entryLen = doDeleteLeafEntry(page, loc) - loc;

        // Increment garbage by the size of the encoded entry.
        garbage(garbage() + entryLen);

        // Encode an empty key and a ghost value, to facilitate cleanup when an exception
        // occurs. This ensures that cleanup won't double-delete fragmented keys or values.
        p_shortPutLE(page, loc, 0x8000); // encoding for an empty key
        p_bytePut(page, loc + 2, -1); // encoding for a ghost value

        // Replace all references to the old location.
        int pos = searchVecStart();
        int endPos = searchVecEnd();
        for (; pos<=endPos; pos+=2) {
            if (p_ushortGetLE(page, pos) == loc) {
                p_shortPutLE(page, pos, newLoc);
            }
        }
    }

    /**
     * Deletes the first entry, and leaves the garbage field alone.
     */
    void deleteFirstSortLeafEntry() throws IOException {
        var page = mPage;
        int start = searchVecStart();
        doDeleteLeafEntry(page, p_ushortGetLE(page, start));
        searchVecStart(start + 2);
    }

    /**
     * Count the number of cursors bound to this node.
     */
    long countCursors(boolean strict) {
        // Attempt an exclusive latch to prevent frames from being visited multiple times due
        // to recycling.
        if (tryAcquireExclusive()) {
            long count = 0;
            try {
                CursorFrame frame = mLastCursorFrame;
                while (frame != null) {
                    if (!(frame instanceof GhostFrame)) {
                        count++;
                    }
                    frame = frame.mPrevCousin;
                }
            } finally {
                releaseExclusive();
            }
            return count;
        }

        // Iterate over the frames using a lock coupling strategy. Frames which are being
        // concurrently removed are skipped over. A shared latch is required to prevent
        // observing an in-flight split, which breaks iteration due to rebinding.

        if (strict) {
            acquireShared();
        } else if (!tryAcquireShared()) {
            return 0;
        }

        try {
            CursorFrame frame = mLastCursorFrame;

            if (frame == null) {
                return 0;
            }

            var lock = new CursorFrame();
            CursorFrame lockResult;

            while (true) {
                lockResult = frame.tryLock(lock);
                if (lockResult != null) {
                    break;
                }
                frame = frame.mPrevCousin;
                if (frame == null) {
                    return 0;
                }
            }

            long count = 0;

            while (true) {
                if (!(frame instanceof GhostFrame)) {
                    count++;
                }
                CursorFrame prev = frame.tryLockPrevious(lock);
                frame.unlock(lockResult);
                if (prev == null) {
                    return count;
                }
                lockResult = frame;
                frame = prev;
            }
        } finally {
            releaseShared();
        }
    }

    /**
     * No latches are acquired by this method -- it is only used for debugging.
     */
    @Override
    @SuppressWarnings("fallthrough")
    public String toString() {
        String prefix;

        switch (type()) {
        case TYPE_UNDO_LOG:
            return "UndoNode{id=" + id() +
                ", cachedState=" + mCachedState +
                ", topEntry=" + garbage() +
                ", lowerNodeId=" + p_longGetLE(mPage, 4) +
                ", latchState=" + super.toString() +
                '}';
            /*P*/ // [
        case TYPE_FRAGMENT:
            return "FragmentNode{id=" + id() +
                ", cachedState=" + mCachedState +
                ", latchState=" + super.toString() +
                '}';
            /*P*/ // ]
        case TYPE_TN_IN:
        case (TYPE_TN_IN | LOW_EXTREMITY):
        case (TYPE_TN_IN | HIGH_EXTREMITY):
        case (TYPE_TN_IN | LOW_EXTREMITY | HIGH_EXTREMITY):
            prefix = "Internal";
            break;

        case TYPE_TN_BIN:
        case (TYPE_TN_BIN | LOW_EXTREMITY):
        case (TYPE_TN_BIN | HIGH_EXTREMITY):
        case (TYPE_TN_BIN | LOW_EXTREMITY | HIGH_EXTREMITY):
            prefix = "BottomInternal";
            break;
        default:
            if (!isLeaf()) {
                return "Node{id=" + id() +
                    ", cachedState=" + mCachedState +
                    ", latchState=" + super.toString() +
                    '}';
            }
            // Fallthrough...
        case TYPE_TN_LEAF:
            prefix = "Leaf";
            break;
        }

        char[] extremity = {'_', '_'};

        if ((type() & LOW_EXTREMITY) != 0) {
            extremity[0] = 'L';
        }
        if ((type() & HIGH_EXTREMITY) != 0) {
            extremity[1] = 'H';
        }

        return prefix + "Node{id=" + id() +
            ", cachedState=" + mCachedState +
            ", isSplit=" + (mSplit != null) +
            ", availableBytes=" + availableBytes() +
            ", extremity=" + new String(extremity) +
            ", latchState=" + super.toString() +
            '}';
    }

    /**
     * Caller must acquired shared latch before calling this method. Latch is
     * released unless an exception is thrown. If an exception is thrown by the
     * observer, the latch would have already been released.
     *
     * @param level passed to observer, if provided
     * @param observer pass null to never release any latch, and to throw a
     * CorruptDatabaseException if the node is invalid
     * @return false if should stop
     */
    boolean verifyTreeNode(int level, VerificationObserver observer) throws IOException {
        return verifyTreeNode(level, observer, false);
    }

    /**
     * @param fix true to ignore the current the garbage and tail segment fields and replace
     * them with the correct values instead
     */
    private boolean verifyTreeNode(int level, VerificationObserver observer, boolean fix)
        throws IOException
    {
        int type = type() & ~(LOW_EXTREMITY | HIGH_EXTREMITY);
        if (type != TYPE_TN_IN && type != TYPE_TN_BIN && !isLeaf()) {
            return verifyFailed(level, observer, "Not a tree node: " + type);
        }

        final var page = mPage;

        if (!fix) {
            if (leftSegTail() < TN_HEADER_SIZE) {
                return verifyFailed(level, observer, "Left segment tail: " + leftSegTail());
            }

            if (searchVecStart() < leftSegTail()) {
                return verifyFailed(level, observer, "Search vector start: " + searchVecStart());
            }

            if (searchVecEnd() < (searchVecStart() - 2)) {
                return verifyFailed(level, observer, "Search vector end: " + searchVecEnd());
            }

            if (rightSegTail() < searchVecEnd() || rightSegTail() > (pageSize(page) - 1)) {
                return verifyFailed(level, observer, "Right segment tail: " + rightSegTail());
            }
        }

        if (!isLeaf()) {
            int childIdsStart = searchVecEnd() + 2;
            int childIdsEnd = childIdsStart + ((childIdsStart - searchVecStart()) << 2) + 8;
            if (childIdsEnd > (rightSegTail() + 1)) {
                return verifyFailed(level, observer, "Child ids end: " + childIdsEnd);
            }

            var childIds = new LHashTable.Int(512);

            for (int i = childIdsStart; i < childIdsEnd; i += 8) {
                long childId = childIdByOffset(page, i);
                if (id() > 1 && childId <= 1) { // stubs don't have a valid child id
                    return verifyFailed(level, observer, "Illegal child id: " + childId);
                }
                LHashTable.IntEntry e = childIds.insert(childId);
                if (e.value != 0) {
                    return verifyFailed(level, observer, "Duplicate child id: " + childId);
                }
                e.value = 1;
            }
        }

        int used = TN_HEADER_SIZE;
        int leftTail = TN_HEADER_SIZE;
        int rightTail = pageSize(page); // compute as inclusive
        int largeValueCount = 0;
        int lastKeyLoc = 0;

        for (int i = searchVecStart(); i <= searchVecEnd(); i += 2) {
            final int keyLoc = p_ushortGetLE(page, i);
            int loc = keyLoc;

            if (loc < TN_HEADER_SIZE || loc >= pageSize(page) ||
                (!fix && loc >= leftSegTail() && loc <= rightSegTail()))
            {
                return verifyFailed(level, observer, "Entry location: " + loc);
            }

            if (isLeaf()) {
                used += leafEntryLengthAtLoc(page, loc);
            } else {
                used += keyLengthAtLoc(page, loc);
            }

            if (loc > searchVecEnd()) {
                rightTail = Math.min(loc, rightTail);
            }

            int keyLen;
            try {
                keyLen = p_byteGet(page, loc++);
                keyLen = keyLen >= 0 ? (keyLen + 1)
                    : (((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++));
            } catch (IndexOutOfBoundsException e) {
                return verifyFailed(level, observer, "Key location out of bounds");
            }

            loc += keyLen;

            if (loc > pageSize(page)) {
                return verifyFailed(level, observer, "Key end location: " + loc);
            }

            if (lastKeyLoc != 0) {
                int result = compareKeys(this, lastKeyLoc, this, keyLoc);
                if (result >= 0) {
                    return verifyFailed(level, observer, "Key order: " + result);
                }
            }

            lastKeyLoc = keyLoc;

            if (isLeaf()) value: {
                int len;
                try {
                    int header = p_byteGet(page, loc++);
                    if (header >= 0) {
                        len = header;
                    } else {
                        if ((header & 0x20) == 0) {
                            len = 1 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc++));
                        } else if (header != -1) {
                            len = 1 + (((header & 0x0f) << 16)
                                       | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
                        } else {
                            // ghost
                            break value;
                        }
                        if ((header & ENTRY_FRAGMENTED) != 0) {
                            largeValueCount++;
                        }
                    }
                } catch (IndexOutOfBoundsException e) {
                    return verifyFailed(level, observer, "Value location out of bounds");
                }
                loc += len;
                if (loc > pageSize(page)) {
                    return verifyFailed(level, observer, "Value end location: " + loc);
                }
            }

            if (loc <= searchVecStart()) {
                leftTail = Math.max(leftTail, loc);
            }
        }

        if (fix) {
            int garbage = pageSize(page) - (used + rightTail - leftTail);
            garbage(garbage);
            leftSegTail(leftTail);
            rightSegTail(rightTail - 1); // subtract one to be exclusive
        } else {
            used += rightSegTail() + 1 - leftSegTail();
            int garbage = pageSize(page) - used;
            if (garbage() != garbage && id() > 1) { // exclude stubs
                return verifyFailed(level, observer, "Garbage: " + garbage() + " != " + garbage);
            }
        }

        if (observer == null) {
            return true;
        }

        int entryCount = numKeys();
        int freeBytes = availableBytes();

        long id = id();
        releaseShared();

        return observer.indexNodePassed(id, level, entryCount, freeBytes, largeValueCount);
    }

    /**
     * @throws CorruptDatabaseException if observer is null
     */
    private boolean verifyFailed(int level, VerificationObserver observer, String message)
        throws CorruptDatabaseException
    {
        if (observer == null) {
            throw new CorruptDatabaseException(message);
        }
        long id = id();
        releaseShared();
        observer.failed = true;
        return observer.indexNodeFailed(id, level, message);
    }
}
