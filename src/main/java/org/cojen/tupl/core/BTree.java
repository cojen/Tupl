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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.util.Comparator;

import java.util.concurrent.ThreadLocalRandom;

import org.cojen.tupl.Database;
import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.Filter;
import org.cojen.tupl.Index;
import org.cojen.tupl.LockFailureException;
import org.cojen.tupl.LockMode;
import org.cojen.tupl.LockResult;
import org.cojen.tupl.Ordering;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.View;

import org.cojen.tupl.diag.CompactionObserver;
import org.cojen.tupl.diag.EventListener;
import org.cojen.tupl.diag.EventType;
import org.cojen.tupl.diag.IndexStats;
import org.cojen.tupl.diag.VerificationObserver;

import org.cojen.tupl.views.BoundedView;
import org.cojen.tupl.views.UnmodifiableView;

import static org.cojen.tupl.core.PageOps.*;
import static org.cojen.tupl.core.Utils.*;

import static java.util.Arrays.compareUnsigned;

/**
 * B-tree implementation.
 *
 * @author Brian S O'Neill
 */
class BTree extends Tree implements View, Index {
    final LocalDatabase mDatabase;
    final LockManager mLockManager;

    // Id range is [0, 255] for all internal trees.
    final long mId;

    // Id is null for registry.
    final byte[] mIdBytes;

    // Although tree roots can be created and deleted, the object which refers
    // to the root remains the same. Internal state is transferred to/from this
    // object when the tree root changes.
    final Node mRoot;

    // Name is null for all internal trees.
    volatile byte[] mName;

    // Linked list of stubs, which are created when the root node is deleted. They need to
    // stick around indefinitely, to ensure that any bound cursors still function normally.
    // When tree height increases again, the stub is replaced with a real node. Root node must
    // be latched exclusively when modifying this list.
    private Node mStubTail;

    BTree(LocalDatabase db, long id, byte[] idBytes, Node root) {
        mDatabase = db;
        mLockManager = db.mLockManager;
        mId = id;
        mIdBytes = idBytes;
        mRoot = root;
    }

    /**
     * Unnamed tree which prohibits redo durability. The BTreeCursor class checks this with the
     * allowRedo method.
     */
    static final class Temp extends BTree {
        Temp(LocalDatabase db, long id, byte[] idBytes, Node root) {
            super(db, id, idBytes, root);
        }
    }

    /**
     * BTree which requires an explicit transaction when none is specified, excluding
     * loads. The BTreeCursor class checks this with the requireTransaction method.
     */
    static final class Repl extends BTree {
        Repl(LocalDatabase db, long id, byte[] idBytes, Node root) {
            super(db, id, idBytes, root);
        }
    }

    final int pageSize() {
        return mDatabase.pageSize();
    }

    @Override
    public final Ordering ordering() {
        return Ordering.ASCENDING;
    }

    @Override
    public Comparator<byte[]> comparator() {
        return KEY_COMPARATOR;
    }

    @Override
    public final long id() {
        return mId;
    }

    @Override
    public final byte[] name() {
        return cloneArray(mName);
    }

    @Override
    public final String nameString() {
        return utf8(mName);
    }

    @Override
    public <R> Table<R> asTable(Class<R> type) throws IOException {
        return mDatabase.rowStore().asTable(this, type);
    }

    @Override
    public BTreeCursor newCursor(Transaction txn) {
        return new BTreeCursor(this, txn);
    }

    @Override
    public Transaction newTransaction(DurabilityMode durabilityMode) {
        return mDatabase.newTransaction(durabilityMode);
    }

    @Override
    public boolean isEmpty() throws IOException {
        Node root = mRoot;
        root.acquireShared();
        try {
            checkClosedIndexException(root.mPage);        
            return root.isLeaf() && !root.hasKeys();
        } finally {
            root.releaseShared();
        }
    }

    @Override
    public long count(byte[] lowKey, boolean lowInclusive,
                      byte[] highKey, boolean highInclusive)
        throws IOException
    {
        BTreeCursor cursor = newCursor(Transaction.BOGUS);
        BTreeCursor high = null;
        try {
            cursor.mKeyOnly = true;

            if (lowKey == null) {
                cursor.first();
            } else if (lowInclusive) {
                cursor.findGe(lowKey);
            } else {
                cursor.findGt(lowKey);
            }

            long adjust = 0;
            if (highKey != null) {
                high = newCursor(Transaction.BOGUS);
                high.mKeyOnly = true;
                high.find(highKey);
                if (highInclusive && high.value() != null) {
                    adjust = 1;
                }
            }

            return cursor.countTo(high) + adjust;
        } finally {
            cursor.reset();
            if (high != null) {
                high.reset();
            }
        }
    }

    @Override
    public final byte[] load(Transaction txn, byte[] key) throws IOException {
        LocalTransaction local = check(txn);

        // If lock must be acquired and retained, acquire now and skip the quick check later.
        if (local != null) {
            int lockType = local.lockMode().repeatable;
            if (lockType != 0) {
                int hash = LockManager.hash(mId, key);
                local.doLock(lockType, mId, key, hash, local.mLockTimeoutNanos);
            }
        }

        Node node = mRoot;
        node.acquireShared();

        // Note: No need to check if root has split, since root splits are always completed
        // before releasing the root latch. Also, Node.used is not invoked for the root node,
        // because it cannot be evicted.

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        while (!node.isLeaf()) {
            int childPos;
            try {
                childPos = Node.internalPos(node.binarySearch(key));
            } catch (Throwable e) {
                node.releaseShared();
                throw e;
            }

            long childId = node.retrieveChildRefId(childPos);
            Node childNode = mDatabase.nodeMapGetShared(childId);

            if (childNode != null) {
                node.releaseShared();
                node = childNode;
                node.used(rnd);
            } else {
                node = node.loadChild(mDatabase, childId, Node.OPTION_PARENT_RELEASE_SHARED);
            }

            if (node.mSplit != null) {
                node = node.mSplit.selectNode(node, key);
            }
        }

        // Sub search into leaf with shared latch held.

        // Same code as binarySearch, but instead of returning the position, it directly copies
        // the value if found. This avoids having to decode the found value location twice.

        CursorFrame frame;
        int keyHash;

        search: try {
            final var page = node.mPage;
            final int keyLen = key.length;
            int lowPos = node.searchVecStart();
            int highPos = node.searchVecEnd();

            // TODO: Using this feature reduces performance for small keys. Is the potential
            // benefit for large keys worth it?
            //int lowMatch = 0, highMatch = 0;

            outer: while (lowPos <= highPos) {
                int midPos = ((lowPos + highPos) >> 1) & ~1;

                int compareLoc, compareLen, i;
                compare: {
                    compareLoc = p_ushortGetLE(page, midPos);
                    compareLen = p_byteGet(page, compareLoc++);
                    if (compareLen >= 0) {
                        compareLen++;
                    } else {
                        int header = compareLen;
                        compareLen = ((compareLen & 0x3f) << 8) | p_ubyteGet(page, compareLoc++);

                        if ((header & Node.ENTRY_FRAGMENTED) != 0) {
                            // Note: An optimized version wouldn't need to copy the whole key.
                            byte[] compareKey = mDatabase
                                .reconstructKey(page, compareLoc, compareLen);

                            int fullCompareLen = compareKey.length;
                            int minLen = Math.min(fullCompareLen, keyLen);
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

                            // Update compareLen and compareLoc for use by the code after the
                            // current scope. The compareLoc is completely bogus at this point,
                            // but it's corrected when the value is retrieved below.
                            compareLoc += compareLen - fullCompareLen;
                            compareLen = fullCompareLen;

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
                                //lowMatch = i;
                            } else {
                                highPos = midPos - 2;
                                //highMatch = i;
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
                    if ((local != null && local.lockMode() != LockMode.READ_COMMITTED) ||
                        mLockManager.isAvailable
                        (local, mId, key, keyHash = LockManager.hash(mId, key)))
                    {
                        return Node.retrieveLeafValueAtLoc(node, page, compareLoc + compareLen);
                    }
                    // Need to acquire the lock before loading. To prevent deadlock, a cursor
                    // frame must be bound and then the node latch can be released.
                    frame = new CursorFrame();
                    int pos = midPos - node.searchVecStart();
                    if (node.mSplit != null) {
                        pos = node.mSplit.adjustBindPosition(pos);
                    }
                    frame.bind(node, pos);
                    break search;
                }
            }

            if ((local != null && local.lockMode() != LockMode.READ_COMMITTED) ||
                mLockManager.isAvailable(local, mId, key, keyHash = LockManager.hash(mId, key)))
            {
                return null;
            }

            // Need to lock even if no value was found.
            frame = new CursorFrame();
            frame.mNotFoundKey = key;
            int pos = lowPos - node.searchVecStart();
            if (node.mSplit != null) {
                pos = node.mSplit.adjustBindPosition(pos);
            }
            frame.bind(node, ~pos);
            break search;
        } finally {
            node.releaseShared();
        }

        try {
            Locker locker;
            if (local == null) {
                locker = lockSharedLocal(key, keyHash);
            } else if (local.doLockShared(mId, key, keyHash) == LockResult.ACQUIRED) {
                locker = local;
            } else {
                // Transaction already had the lock for some reason, so don't release it.
                locker = null;
            }

            try {
                node = frame.acquireShared();
                try {
                    int pos = frame.mNodePos;
                    if (pos < 0) {
                        return null;
                    } else if (node.mSplit == null) {
                        return node.retrieveLeafValue(pos);
                    } else {
                        return node.mSplit.retrieveLeafValue(node, pos);
                    }
                } finally {
                    node.releaseShared();
                }
            } finally {
                if (locker != null) {
                    locker.doUnlock();
                }
            }
        } finally {
            CursorFrame.popAll(frame);
        }
    }

    @Override
    public final boolean exists(Transaction txn, byte[] key) throws IOException {
        LocalTransaction local = check(txn);

        // If lock must be acquired and retained, acquire now and skip the quick check later.
        if (local != null) {
            int lockType = local.lockMode().repeatable;
            if (lockType != 0) {
                int hash = LockManager.hash(mId, key);
                local.doLock(lockType, mId, key, hash, local.mLockTimeoutNanos);
            }
        }

        Node node = mRoot;
        node.acquireShared();

        // Note: No need to check if root has split, since root splits are always completed
        // before releasing the root latch. Also, Node.used is not invoked for the root node,
        // because it cannot be evicted.

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        while (!node.isLeaf()) {
            int childPos;
            try {
                childPos = Node.internalPos(node.binarySearch(key));
            } catch (Throwable e) {
                node.releaseShared();
                throw e;
            }

            long childId = node.retrieveChildRefId(childPos);
            Node childNode = mDatabase.nodeMapGetShared(childId);

            if (childNode != null) {
                node.releaseShared();
                node = childNode;
                node.used(rnd);
            } else {
                node = node.loadChild(mDatabase, childId, Node.OPTION_PARENT_RELEASE_SHARED);
            }

            if (node.mSplit != null) {
                node = node.mSplit.selectNode(node, key);
            }
        }

        // Sub search into leaf with shared latch held.

        CursorFrame frame;
        int keyHash;

        try {
            int pos = node.binarySearch(key);

            if ((local != null && local.lockMode() != LockMode.READ_COMMITTED) ||
                mLockManager.isAvailable(local, mId, key, keyHash = LockManager.hash(mId, key)))
            {
                return pos >= 0 && node.hasLeafValue(pos) != null;
            }

            // Need to acquire the lock before loading. To prevent deadlock, a cursor
            // frame must be bound and then the node latch can be released.
            frame = new CursorFrame();

            if (pos >= 0) {
                if (node.mSplit != null) {
                    pos = node.mSplit.adjustBindPosition(pos);
                }
            } else {
                frame.mNotFoundKey = key;
                if (node.mSplit != null) {
                    pos = ~node.mSplit.adjustBindPosition(~pos);
                }
            }

            frame.bind(node, pos);
        } finally {
            node.releaseShared();
        }

        try {
            Locker locker;
            if (local == null) {
                locker = lockSharedLocal(key, keyHash);
            } else if (local.doLockShared(mId, key, keyHash) == LockResult.ACQUIRED) {
                locker = local;
            } else {
                // Transaction already had the lock for some reason, so don't release it.
                locker = null;
            }

            try {
                node = frame.acquireShared();
                int pos = frame.mNodePos;
                boolean result = pos >= 0 && node.hasLeafValue(pos) != null;
                node.releaseShared();
                return result;
            } finally {
                if (locker != null) {
                    locker.doUnlock();
                }
            }
        } finally {
            CursorFrame.popAll(frame);
        }
    }

    @Override
    public final void store(Transaction txn, byte[] key, byte[] value) throws IOException {
        keyCheck(key);
        BTreeCursor cursor = newCursor(txn);
        try {
            cursor.mKeyOnly = true;
            cursor.findAndStore(key, value);
        } finally {
            cursor.reset();
        }
    }

    @Override
    public final byte[] exchange(Transaction txn, byte[] key, byte[] value) throws IOException {
        keyCheck(key);
        BTreeCursor cursor = newCursor(txn);
        try {
            return cursor.findAndStore(key, value);
        } finally {
            cursor.reset();
        }
    }

    @Override
    public final boolean insert(Transaction txn, byte[] key, byte[] value) throws IOException {
        keyCheck(key);
        BTreeCursor cursor = newCursor(txn);
        try {
            return cursor.findAndModify(key, BTreeCursor.MODIFY_INSERT, value);
        } finally {
            cursor.reset();
        }
    }

    @Override
    public final boolean replace(Transaction txn, byte[] key, byte[] value) throws IOException {
        keyCheck(key);
        BTreeCursor cursor = newCursor(txn);
        try {
            return cursor.findAndModify(key, BTreeCursor.MODIFY_REPLACE, value);
        } finally {
            cursor.reset();
        }
    }

    @Override
    public final boolean update(Transaction txn, byte[] key, byte[] value) throws IOException {
        keyCheck(key);
        BTreeCursor cursor = newCursor(txn);
        try {
            // TODO: Optimize by disabling autoload and do an in-place comparison.
            return cursor.findAndModify(key, BTreeCursor.MODIFY_UPDATE, value);
        } finally {
            cursor.reset();
        }
    }

    @Override
    public final boolean update(Transaction txn, byte[] key, byte[] oldValue, byte[] newValue)
        throws IOException
    {
        keyCheck(key);
        BTreeCursor cursor = newCursor(txn);
        try {
            // TODO: Optimize by disabling autoload and do an in-place comparison.
            return cursor.findAndModify(key, oldValue, newValue);
        } finally {
            cursor.reset();
        }
    }

    @Override
    public LockResult touch(Transaction txn, byte[] key) throws LockFailureException {
        LocalTransaction local = check(txn);

        LockMode mode;
        if (local == null || (mode = local.lockMode()) == LockMode.READ_COMMITTED) {
            int hash = LockManager.hash(mId, key);
            if (!isLockAvailable(local, key, hash)) {
                // Acquire and release.
                if (local == null) {
                    lockSharedLocal(key, hash).doUnlock();
                } else {
                    LockResult result = local.doLock(0, mId, key, hash, local.mLockTimeoutNanos);
                    if (result == LockResult.ACQUIRED) {
                        local.doUnlock();
                    }
                }
            }
        } else if (!mode.noReadLock) {
            int hash = LockManager.hash(mId, key);
            return local.doLock(mode.repeatable, mId, key, hash, local.mLockTimeoutNanos);
        }

        return LockResult.UNOWNED;
    }

    @Override
    public final LockResult tryLockShared(Transaction txn, byte[] key, long nanosTimeout)
        throws LockFailureException
    {
        return check(txn).tryLockShared(mId, key, nanosTimeout);
    }

    @Override
    public final LockResult lockShared(Transaction txn, byte[] key) throws LockFailureException {
        return check(txn).lockShared(mId, key);
    }

    @Override
    public final LockResult tryLockUpgradable(Transaction txn, byte[] key, long nanosTimeout)
        throws LockFailureException
    {
        return check(txn).tryLockUpgradable(mId, key, nanosTimeout);
    }

    @Override
    public final LockResult lockUpgradable(Transaction txn, byte[] key)
        throws LockFailureException
    {
        return check(txn).lockUpgradable(mId, key);
    }

    @Override
    public final LockResult tryLockExclusive(Transaction txn, byte[] key, long nanosTimeout)
        throws LockFailureException
    {
        return check(txn).tryLockExclusive(mId, key, nanosTimeout);
    }

    @Override
    public final LockResult lockExclusive(Transaction txn, byte[] key)
        throws LockFailureException
    {
        return check(txn).lockExclusive(mId, key);
    }

    @Override
    public final LockResult lockCheck(Transaction txn, byte[] key) {
        return check(txn).lockCheck(mId, key);
    }

    @Override
    public View viewGe(byte[] key) {
        return BoundedView.viewGe(this, key);
    }

    @Override
    public View viewGt(byte[] key) {
        return BoundedView.viewGt(this, key);
    }

    @Override
    public View viewLe(byte[] key) {
        return BoundedView.viewLe(this, key);
    }

    @Override
    public View viewLt(byte[] key) {
        return BoundedView.viewLt(this, key);
    }

    @Override
    public View viewPrefix(byte[] prefix, int trim) {
        return BoundedView.viewPrefix(this, prefix, trim);
    }

    @Override
    public final boolean isUnmodifiable() {
        return isClosed();
    }

    @Override
    public final boolean isModifyAtomic() {
        return true;
    }

    /**
     * Current approach for evicting data is as follows:
     * - Search for a random Node, steered towards un-cached nodes. 
     * - Once a node is picked, iterate through the keys in the node 
     *   and delete all the entries from it (provided they are within 
     *   the highkey and lowKey boundaries).
     * - This simple algorithm is an approximate LRU algorithm, which
     *   is expected to evict entries that are least recently accessed.
     * 
     * An alternative approach that was considered:
     * - Search for a random Node, steered towards un-cached nodes.
     * - Delete the node directly. 
     * - This works when all the keys and values fit within a page.  
     *   If they don't, then the entries must be fully decoded. This is
     *   necessary because there's no quick way of determining if any of
     *   the entries in a page overflow.  
     * 
     * Note: It could be that the node initially has three keys: A, B, D. As eviction is
     * progressing along, a key C could be inserted concurrently, which could then be
     * immediately deleted. This case is expected to be rare and harmless.
     */
    @Override
    public long evict(Transaction txn, byte[] lowKey, byte[] highKey,
                      Filter evictionFilter, boolean autoload)
        throws IOException
    {
        long length = 0;
        BTreeCursor cursor = newCursor(txn);
        cursor.autoload(autoload);

        try {
            byte[] endKey = cursor.randomNode(lowKey, highKey);
            if (endKey == null) {
                // We did not find anything to evict.  Move on.
                return length;
            }
            
            if (lowKey != null) { 
                if (compareUnsigned(lowKey, endKey) > 0) {
                    // lowKey is past the end key.  Move on.
                    return length;
                }
                if (cursor.compareKeyTo(lowKey) < 0) {
                    // lowKey is past the current cursor position: move cursor position to lowKey
                    // findNearby will position the cursor to lowKey even if it does not exist.
                    // So we will need to skip values that don't exist before processing the keys.
                    // findNearby returns a lockResult. We can safely ignore it.
                    cursor.findNearby(lowKey);
                }
            }
            
            if (highKey != null && compareUnsigned(highKey, endKey) <= 0) {
                endKey = highKey; 
            }
            
            var stats = new long[2];
            while (cursor.key() != null) {
                byte[] key = cursor.key();
                byte[] value = cursor.value();
                if (value != null) {
                    cursor.valueStats(stats);
                    if (stats[0] > 0 &&
                        (evictionFilter == null || evictionFilter.isAllowed(key, value)))
                    {
                        length += key.length + stats[0]; 
                        cursor.store(null);
                    }
                } else {
                    // This is either a ghost or findNearby got us to a 
                    // key that does not exist.  Move on to next key.
                }
                cursor.nextLe(endKey);
            }
        } finally {
            cursor.reset();
        }
        return length;
    }

    @Override
    public IndexStats analyze(byte[] lowKey, byte[] highKey) throws IOException {
        BTreeCursor cursor = newCursor(Transaction.BOGUS);
        try {
            cursor.mKeyOnly = true;
            cursor.random(lowKey, highKey);
            return cursor.key() == null ? new IndexStats() : cursor.analyze();
        } catch (Throwable e) {
            cursor.reset();
            throw e;
        }
    }

    @Override
    final Index observableView() {
        return isInternal(mId) ? new UnmodifiableView(this) : this;
    }

    @Override
    final boolean compactTree(Index view, long highestNodeId, CompactionObserver observer)
        throws IOException
    {
        try {
            if (!observer.indexBegin(view)) {
                return false;
            }
        } catch (Throwable e) {
            uncaught(e);
            return false;
        }

        BTreeCursor cursor = newCursor(Transaction.BOGUS);
        try {
            cursor.mKeyOnly = true;

            // Find the first node instead of calling first() to ensure that cursor is
            // positioned. Otherwise, empty trees would be skipped even when the root node
            // needed to be moved out of the compaction zone.
            cursor.firstLeaf();

            if (!cursor.compact(highestNodeId, observer)) {
                return false;
            }

            try {
                if (!observer.indexComplete(view)) {
                    return false;
                }
            } catch (Throwable e) {
                uncaught(e);
                return false;
            }

            return true;
        } finally {
            cursor.reset();
        }
    }

    @Override
    final boolean verifyTree(Index view, VerificationObserver observer) throws IOException {
        BTreeCursor cursor = newCursor(Transaction.BOGUS);
        try {
            cursor.mKeyOnly = true;
            cursor.first(); // must start with loaded key
            int height = cursor.height();
            if (!observer.indexBegin(view, height)) {
                cursor.reset();
                return false;
            }
            if (!cursor.verify(height, observer)) {
                cursor.reset();
                return false;
            }
            cursor.reset();
        } catch (Throwable e) {
            observer.failed = true;
            throw e;
        }
        return true;
    }

    @Override
    long countCursors(boolean strict) {
        return mRoot.countCursors(strict);
    }

    @Override
    public final void close() {
        close(false, false, false);
    }

    /**
     * Closes the database such that it throws DeletedIndexException, but doesn't actually run
     * the code to truly delete the index.
     */
    final void closeAsDeleted() {
        close(false, false, false, p_deletedTreePage());
    }

    /**
     * @param rootLatched true if root node is already latched by the current thread
     * @return root node if forDelete; null if already closed
     */
    final Node close(boolean forDelete, boolean rootLatched) {
        return close(forDelete, rootLatched, false);
    }

    /**
     * Close any kind of index, even an internal one.
     */
    @Override
    final void forceClose() {
        close(false, false, true);
    }

    /**
     * @param rootLatched true if root node is already latched by the current thread
     * @return root node if forDelete; null if already closed
     */
    private Node close(boolean forDelete, boolean rootLatched, boolean force) {
        var closedPage = forDelete ? p_deletedTreePage() : p_closedTreePage();
        return close(forDelete, rootLatched, force, closedPage);
    }

    /**
     * @param rootLatched true if root node is already latched by the current thread
     * @return root node if forDelete; null if already closed
     */
    private Node close(boolean forDelete, final boolean rootLatched, boolean force,
                       /*P*/ byte[] closedPage)
    {
        Node root = mRoot;

        if (!rootLatched) {
            root.acquireExclusive();
        }

        try {
            if (isClosedOrDeleted(root.mPage)) {
                // Already closed.
                return null;
            }

            if (!force && isInternal(mId)) {
                throw new IllegalStateException("Cannot close an internal index");
            }

            // Invalidate all cursors such that they refer to empty nodes.

            if (root.hasKeys()) {
                // If any active cursors, they might be in the middle of performing node splits
                // and merges. With the exclusive commit lock held, this is no longer the case.
                root.releaseExclusive();
                mDatabase.commitLock().acquireExclusive();
                try {
                    root.acquireExclusive();
                    if (isClosedOrDeleted(root.mPage)) {
                        return null;
                    }
                    root.invalidateCursors(closedPage);
                } finally {
                    mDatabase.commitLock().releaseExclusive();
                }
            } else {
                // No keys in the root means that no splits or merges are in progress. No need
                // to release the latch, preventing a race condition when Index.drop is called.
                // Releasing the root latch would allow another thread to sneak in and insert
                // entries, which would then get silently deleted.
                root.invalidateCursors(closedPage);
            }

            // Root node reference cannot be cleared, so instead make it non-functional. Move
            // the page reference into a new evictable Node object, allowing it to be recycled.

            Node newRoot = root.cloneNode();
            mDatabase.swapIfDirty(root, newRoot);

            if (root.id() > 0) {
                mDatabase.nodeMapRemove(root);
            }

            root.closeRoot(closedPage);

            if (forDelete) {
                mDatabase.treeClosed(this);
                return newRoot;
            }

            newRoot.acquireShared();
            try {
                mDatabase.treeClosed(this);
                if (newRoot.id() > 0) {
                    mDatabase.nodeMapPut(newRoot);
                }
            } finally {
                newRoot.releaseShared();
                newRoot.makeEvictableNow();
            }

            return null;
        } finally {
            if (!rootLatched) {
                root.releaseExclusive();
            }
        }
    }

    @Override 
    public final boolean isClosed() {
        Node root = mRoot;
        root.acquireShared();
        boolean closed = isClosedOrDeleted(root.mPage);
        root.releaseShared();
        return closed;
    }

    @Override
    final boolean isMemberOf(Database db) {
        return mDatabase == db;
    }

    @Override
    final boolean isUserOf(Tree tree) {
        return this == tree;
    }

    @Override
    final void rename(byte[] newName, long redoTxnId) throws IOException {
        mDatabase.renameBTree(this, newName, redoTxnId);
    }

    /**
     * @return delete task
     */
    @Override
    final Runnable drop(boolean mustBeEmpty) throws IOException {
        // Acquire early to avoid deadlock when moving tree to trash.
        CommitLock.Shared shared = mDatabase.commitLock().acquireShared();

        Node root;
        try {
            root = mRoot;
            root.acquireExclusive();
        } catch (Throwable e) {
            shared.release();
            throw e;
        }

        try {
            try {
                checkClosedIndexException(root.mPage);

                if (mustBeEmpty && (!root.isLeaf() || root.hasKeys())) {
                    // Note that this check also covers the transactional case, because deletes
                    // store ghosts. The message could be more accurate, but it would require
                    // scanning the whole index looking for ghosts. Using LockMode.UNSAFE
                    // deletes it's possible to subvert the transactional case, allowing the
                    // drop to proceed. The rollback logic in UndoLog accounts for this,
                    // ignoring undo operations for missing indexes. Preventing the drop in
                    // this case isn't worth the trouble, because UNSAFE is what it is.
                    throw new IllegalStateException("Cannot drop a non-empty index");
                }

                if (isInternal(mId)) {
                    throw new IllegalStateException("Cannot close an internal index");
                }
            } catch (Throwable e) {
                shared.release();
                throw e;
            }

            return mDatabase.deleteTree(this, shared);
        } finally {
            root.releaseExclusive();
        }
    }

    /**
     * Non-transactionally deletes all entries in the tree. No other cursors or threads can be
     * active in the tree.
     *
     * @return false if stopped because database is closed
     */
    final boolean deleteAll() throws IOException {
        return newCursor(Transaction.BOGUS).deleteAll();
    }

    /**
     * Graft two non-empty temporary trees together into a single surviving tree, which is
     * returned. All keys of this tree must be less than all keys of the other tree, which
     * isn't verified or concurrently enforced. No cursors or threads can be active in either
     * tree when grafting them together.
     *
     * <p>The victim tree is deleted, although the Tree object isn't completely invalidated.
     * Just discard it and don't close it.
     */
    static BTree graftTempTree(BTree lowTree, BTree highTree) throws IOException {
        // Note: Supporting non-temporary trees would require special redo and replication
        // code. Also, all active cursors must be reset and the root latch would need to be
        // held the whole time.

        BTreeCursor lowCursor, highCursor;

        lowCursor = lowTree.newCursor(Transaction.BOGUS);
        try {
            lowCursor.mKeyOnly = true;
            lowCursor.last();

            highCursor = highTree.newCursor(Transaction.BOGUS);
            try {
                highCursor.mKeyOnly = true;
                highCursor.first();

                CommitLock.Shared shared = lowTree.mDatabase.commitLock().acquireShared();
                try {
                    return doGraftTempTree(lowTree, highTree, lowCursor, highCursor);
                } finally {
                    shared.release();
                }
            } finally {
                highCursor.reset();
            }
        } finally {
            lowCursor.reset();
        }
    }

    private static BTree doGraftTempTree(BTree lowTree, BTree highTree,
                                         BTreeCursor lowCursor, BTreeCursor highCursor)
        throws IOException
    {

        // Dirty the edge nodes and find the mid key.

        byte[] midKey;
        CursorFrame lowFrame, highFrame;
        {
            lowFrame = lowCursor.frameExclusive();
            Node lowNode = lowCursor.notSplitDirty(lowFrame);
            try {
                highFrame = highCursor.frameExclusive();
                Node highNode = highCursor.notSplitDirty(highFrame);
                try {
                    midKey = lowNode.midKey(lowNode.highestLeafPos(), highNode, 0);
                } finally {
                    highNode.releaseExclusive();
                }
            } finally {
                lowNode.releaseExclusive();
            }
        }

        // Find the level to perform the graft, which is at the victim root node.

        BTree survivor, victim;
        CursorFrame survivorFrame;
        Node victimNode;

        while (true) {
            CursorFrame lowParent = lowFrame.mParentFrame;
            CursorFrame highParent = highFrame.mParentFrame;

            if (highParent == null) {
                survivor = lowTree;
                survivorFrame = lowFrame;
                victim = highTree;
                victimNode = highFrame.acquireExclusive();
                break;
            } else if (lowParent == null) {
                survivor = highTree;
                survivorFrame = highFrame;
                victim = lowTree;
                victimNode = lowFrame.acquireExclusive();
                break;
            }

            lowFrame = lowParent;
            highFrame = highParent;
        }

        Node survivorNode;
        try {
            var split = new Split(lowTree == survivor, victimNode);
            split.setKey(survivor, midKey);
            survivorNode = survivorFrame.acquireExclusive();
            survivorNode.mSplit = split;
        } finally {
            victimNode.releaseExclusive();
        }

        try {
            // Clear the extremity bits, before any exception from finishSplit.
            clearExtremityBits(lowCursor.mFrame, survivorFrame, ~Node.HIGH_EXTREMITY);
            clearExtremityBits(highCursor.mFrame, survivorFrame, ~Node.LOW_EXTREMITY);

            survivor.finishSplit(survivorFrame, survivorNode).releaseExclusive();
        } catch (Throwable e) {
            survivorNode.cleanupFragments(e, survivorNode.mSplit.fragmentedKey());
            throw e;
        }

        victim.mDatabase.removeGraftedTempTree(victim);

        Node rootNode = survivor.mRoot;
        rootNode.acquireExclusive();

        if (rootNode.numKeys() == 1 && rootNode.isInternal()) {
            // Try to remove a level, which was likely created by the split.

            LocalDatabase db = survivor.mDatabase;
            Node leftNode = db.latchChildRetainParentEx(rootNode, 0, true);
            Node rightNode;
            try {
                rightNode = db.latchChildRetainParentEx(rootNode, 2, true);
            } catch (Throwable e) {
                leftNode.releaseExclusive();
                throw e;
            }

            tryMerge: {
                if (leftNode.isLeaf()) {
                    // See BTreeCursor.mergeLeaf method.

                    int leftAvail = leftNode.availableLeafBytes();
                    int rightAvail = rightNode.availableLeafBytes();

                    int remaining = leftAvail
                        + rightAvail - survivor.pageSize() + Node.TN_HEADER_SIZE;

                    if (remaining < 0) {
                        // No room to merge.
                        break tryMerge;
                    }

                    try {
                        Node.moveLeafToLeftAndDelete(survivor, leftNode, rightNode);
                    } catch (Throwable e) {
                        leftNode.releaseExclusive();
                        rootNode.releaseExclusive();
                        throw e;
                    }
                } else {
                    // See BTreeCursor.mergeInternal method.

                    var rootPage = rootNode.mPage;
                    int rootEntryLoc = p_ushortGetLE(rootPage, rootNode.searchVecStart());
                    int rootEntryLen = Node.keyLengthAtLoc(rootPage, rootEntryLoc);

                    int leftAvail = leftNode.availableInternalBytes();
                    int rightAvail = rightNode.availableInternalBytes();

                    int remaining = leftAvail - rootEntryLen
                        + rightAvail - survivor.pageSize() + (Node.TN_HEADER_SIZE - 2);

                    if (remaining < 0) {
                        // No room to merge.
                        break tryMerge;
                    }

                    try {
                        Node.moveInternalToLeftAndDelete
                            (survivor, leftNode, rightNode, rootPage, rootEntryLoc, rootEntryLen);
                    } catch (Throwable e) {
                        leftNode.releaseExclusive();
                        rootNode.releaseExclusive();
                        throw e;
                    }
                }

                // Success!
                rootNode.deleteRightChildRef(2);
                survivor.rootDelete(leftNode);
                return survivor;
            }

            rightNode.releaseExclusive();
            leftNode.releaseExclusive();
        }

        rootNode.releaseExclusive();

        return survivor;
    }

    /**
     * Called by the graft method.
     *
     * @param frame leaf frame
     * @param stop latched frame to stop at after being cleared (if found)
     * @param mask ~HIGH_EXTREMITY or ~LOW_EXTREMITY
     */
    private static void clearExtremityBits(CursorFrame frame, CursorFrame stop, int mask) {
        do {
            if (frame == stop) {
                Node node = frame.mNode;
                node.type((byte) (node.type() & mask));
                break;
            }
            Node node = frame.acquireExclusive();
            node.type((byte) (node.type() & mask));
            node.releaseExclusive();
            frame = frame.mParentFrame;
        } while (frame != null);
    }

    @FunctionalInterface
    static interface NodeVisitor {
        void visit(Node node) throws IOException;
    }

    /**
     * Performs a depth-first traversal of the tree, only visiting loaded nodes. Nodes passed
     * to the visitor are latched shared, and they must be released by the visitor.
     */
    final void traverseLoaded(NodeVisitor visitor) throws IOException {
        Node node = mRoot;
        node.acquireShared();

        if (node.mSplit != null) {
            // Create a temporary frame for the root split.
            var frame = new CursorFrame();
            frame.bind(node, 0);
            try {
                node = finishSplitShared(frame, node);
            } catch (Throwable e) {
                CursorFrame.popAll(frame);
                throw e;
            }
        }

        // Frames are only used for backtracking up the tree. Frame creation and binding is
        // performed late, and none are created for leaf nodes.
        CursorFrame frame = null;
        int pos = 0;

        while (true) {
            toLower: while (node.isInternal()) {
                final int highestPos = node.highestInternalPos();
                while (true) {
                    if (pos > highestPos) {
                        break toLower;
                    }
                    long childId = node.retrieveChildRefId(pos);
                    Node child = mDatabase.nodeMapGetShared(childId);
                    if (child != null) {
                        try {
                            frame = new CursorFrame(frame);
                            frame.bind(node, pos);
                        } finally {
                            node.releaseShared();
                        }
                        node = child;
                        pos = 0;
                        continue toLower;
                    }
                    pos += 2;
                }
            }

            try {
                visitor.visit(node);
            } catch (Throwable e) {
                CursorFrame.popAll(frame);
                throw e;
            }

            if (frame == null) {
                return;
            }

            node = frame.acquireShared();

            if (node.mSplit != null) {
                try {
                    node = finishSplitShared(frame, node);
                } catch (Throwable e) {
                    CursorFrame.popAll(frame);
                    throw e;
                }
            }

            pos = frame.mNodePos;
            frame = frame.pop();
            pos += 2;
        }
    }

    @Override
    final void writeCachePrimer(final DataOutput dout) throws IOException {
        // Encode name instead of identifier, to support priming set portability
        // between databases. The identifiers won't match, but the names might.
        byte[] name = mName;
        dout.writeInt(name.length);
        dout.write(name);

        traverseLoaded((node) -> {
            byte[] midKey;
            try {
                if (!node.isLeaf()) {
                    return;
                }
                int numKeys = node.numKeys();
                if (numKeys > 1) {
                    int highPos = numKeys & ~1;
                    midKey = node.midKey(highPos - 2, node, highPos);
                } else if (numKeys == 1) {
                    midKey = node.retrieveKey(0);
                } else {
                    return;
                }
            } finally {
                node.releaseShared();
            }

            // Omit entries with very large keys. The primer encoding format would need to
            // change for supporting larger keys.
            if (midKey.length < 0xffff) {
                dout.writeShort(midKey.length);
                dout.write(midKey);
            }
        });

        // Terminator.
        dout.writeShort(0xffff);
    }

    @Override
    final void applyCachePrimer(DataInput din) throws IOException {
        new Primer(this, din).run();
    }

    /**
     * Caller must hold exclusive latch and it must verify that node has
     * split. Node latch is released if an exception is thrown.
     *
     * The caller should also hold at least a shared commit lock, because this function tries
     * to allocate pages, and the page db may try to acquire a shared commit lock. Since the
     * commit lock is reentrant, if the caller already holds a shared commit lock, it
     * guarantees the page db can acquire another shared commit lock.
     *
     * @param frame bound cursor frame
     * @param node node which is bound to the frame, latched exclusively
     * @return replacement node, still latched
     */
    final Node finishSplit(final CursorFrame frame, Node node) throws IOException {
        while (true) {
            if (node == mRoot) {
                // When tree loses a level, a stub node remains for any cursors which were
                // bound to the old root. When a level is added back, the cursors bound to the
                // stub must rebind to the new root node. This happens when the
                // Node.finishSplitRoot method is called, but the stub node must be latched
                // exclusively for this to work correctly. The latch direction is in reverse
                // order, and so deadlock is possible. To avoid this, fail fast and retry as
                // necessary. Whenever the node latch is released and reacquired, the split
                // state must be checked again. Another thread might have finished the split.

                Node stub = mStubTail;

                if (stub == null) {
                    try {
                        node.finishSplitRoot();
                    } finally {
                        node.releaseExclusive();
                    }
                } else withStub: {
                    if (!stub.tryAcquireExclusive()) {
                        // Try to relatch in a different order.

                        node.releaseExclusive();
                        stub.acquireExclusive();

                        try {
                            node = frame.tryAcquireExclusive();
                        } catch (Throwable e) {
                            stub.releaseExclusive();
                            throw e;
                        }

                        if (node == null) {
                            // Latch attempt failed, so start over.
                            stub.releaseExclusive();
                            break withStub;
                        }

                        if (node.mSplit == null) {
                            // Split is finished now.
                            stub.releaseExclusive();
                            return node;
                        }

                        if (node != mRoot || stub != mStubTail) {
                            // Too much state has changed, so start over.
                            node.releaseExclusive();
                            stub.releaseExclusive();
                            break withStub;
                        }
                    }

                    try {
                        node.finishSplitRoot();
                        mStubTail = stub.mNodeMapNext;

                        // Note: Some cursor frames might still be bound to the stub. This is
                        // because the cursor is popping up to the stub, as part of an
                        // iteration or findNearby operation. Since popping to a stub is
                        // equivalent to popping past the root, the cursor operation is able to
                        // handle this. Iteration will finish normally, and findNearby will
                        // start over from the root. Also see stub comments in PageOps.
                    } finally {
                        node.releaseExclusive();
                        stub.releaseExclusive();
                    }
                }

                // Must always relatch node as referenced by the frame.
                node = frame.acquireExclusive();

                if (node.mSplit != null) {
                    // Still split.
                    continue;
                }

                return node;
            }

            final CursorFrame parentFrame = frame.mParentFrame;
            node.releaseExclusive();

            Node parentNode = parentFrame.acquireExclusive();
            while (true) {
                if (parentNode.mSplit != null) {
                    parentNode = finishSplit(parentFrame, parentNode);
                }
                node = frame.acquireExclusive();
                if (node.mSplit == null) {
                    parentNode.releaseExclusive();
                    return node;
                }
                if (node == mRoot) {
                    // Node became the root in between the time the latch was released and
                    // re-acquired. Go back to the case for handling root splits.
                    parentNode.releaseExclusive();
                    break;
                }
                parentNode.insertSplitChildRef(parentFrame, this, parentFrame.mNodePos, node);
            }
        }
    }

    /**
     * Variant of finishSplit which must finish or else panic the database. Any recoverable
     * exception causes a retry to be performed, indefinitely. Any other exception type panics
     * the database.
     *
     * @param frame bound cursor frame
     * @param node node which is bound to the frame, latched exclusively
     * @return replacement node, still latched
     */
    final Node finishSplitCritical(CursorFrame frame, Node node) throws IOException {
        try {
            return finishSplit(frame, node);
        } catch (Throwable e) {
            return finishSplitRetry(e, frame);
        }
    }

    private Node finishSplitRetry(Throwable e, CursorFrame frame) {
        boolean reported = false;

        while (true) {
            if (!isRecoverable(e)) {
                // Panic.
                closeQuietly(mDatabase, e);
                throw rethrow(e);
            }

            if (!reported) {
                Throwable cause = rootCause(e);

                EventListener listener = mDatabase.eventListener();
                if (listener == null) {
                    Utils.uncaught(cause);
                } else {
                    listener.notify(EventType.PANIC_UNHANDLED_EXCEPTION,
                                    "Retrying node split due to exception: %1$s", cause);
                }

                reported = true;
            }

            Thread.yield();

            try {
                Node node = frame.acquireExclusive();
                return finishSplit(frame, node);
            } catch (Throwable e2) {
                e = e2;
            }
        }
    }

    /**
     * Caller must hold shared latch and it must verify that node has split. Node latch is
     * released if an exception is thrown.
     *
     * @param frame bound cursor frame
     * @param node node which is bound to the frame, latched shared
     * @return replacement node, still latched
     */
    final Node finishSplitShared(final CursorFrame frame, Node node) throws IOException {
        doSplit: {
            // In order to call finishSplit, the caller must hold an exclusive node lock, and
            // a shared commit lock. At this point only a shared node lock is held.
            //
            // Following proper lock order, the shared commit lock should be obtained before
            // the node lock. If another thread is attempting to acquire the exclusive commit
            // lock, attempting to acquire the shared commit lock here might stall or deadlock.
            //
            // One strategy is to optimistically try to get the shared commit lock, then
            // upgrade the shared node lock to an exclusive node lock. If either of those steps
            // fail, all locks are released and then acquired in the proper order.
            //
            // The actual approach used here acquires the shared commit lock without checking
            // the exclusive lock. This doesn't lead to starvation of any exclusive waiter
            // because it cannot proceed when the split exists anyhow. The thread which created
            // the split must be holding a shared commit lock already.
            CommitLock.Shared shared = mDatabase.commitLock().acquireSharedUnchecked();
            try {
                if (!node.tryUpgrade()) {
                    node.releaseShared();
                    node = frame.acquireExclusive();
                    if (node.mSplit == null) {
                        break doSplit;
                    }
                }
                node = finishSplit(frame, node);
            } finally {
                shared.release();
            }
        }
        node.downgrade();
        return node;
    }

    /**
     * Caller must have exclusively latched the tree root node instance and the lone child node.
     */
    final void rootDelete(Node child) throws IOException {
        // Allocate stuff early in case of out of memory, and while root is latched. Note that
        // stub is assigned a NodeGroup. Because the stub isn't in the group usage list,
        // attempting to update its position within it has no effect. Note too that the stub
        // isn't placed into the database node map.
        var stub = new Node(mRoot.mGroup);

        // Stub isn't in the node map, so use this pointer field to link the stubs together.
        stub.mNodeMapNext = mStubTail;
        mStubTail = stub;

        mRoot.rootDelete(this, child, stub);
    }

    /**
     * Atomically swaps the root node of this tree with another.
     */
    @Override
    final void rootSwap(Tree other) throws IOException {
        rootSwap((BTree) other);
    }

    private void rootSwap(BTree other) throws IOException {
        CommitLock.Shared shared = mDatabase.commitLock().acquireShared();
        try {
            final Node aRoot = mRoot;
            final Node bRoot = other.mRoot;

            aRoot.acquireExclusive();
            try {
                markDirty(aRoot);
                bRoot.acquireExclusive();
                try {
                    other.markDirty(bRoot);
                    aRoot.rootSwap(bRoot);
                } finally {
                    bRoot.releaseExclusive();
                }
            } finally {
                aRoot.releaseExclusive();
            }

            final Node aTail = mStubTail;
            final Node bTail = other.mStubTail;

            mStubTail = bTail;
            other.mStubTail = aTail;
        } finally {
            shared.release();
        }
    }

    final LocalTransaction check(Transaction txn) throws IllegalArgumentException {
        if (txn instanceof LocalTransaction local) {
            LocalDatabase txnDb = local.mDatabase;
            if (txnDb == mDatabase || txnDb == null) {
                return local;
            }
        }
        if (txn != null) {
            /*P*/ // [|
            /*P*/ // if (txn == Transaction.BOGUS) return LocalTransaction.BOGUS;
            /*P*/ // ]
            throw new IllegalStateException("Transaction belongs to a different database");
        }
        return null;
    }

    /**
     * Returns true if a shared lock can be granted for the given key. Caller must hold the
     * node latch which contains the key.
     *
     * @param locker optional locker
     */
    final boolean isLockAvailable(Locker locker, byte[] key, int hash) {
        return mLockManager.isAvailable(locker, mId, key, hash);
    }

    final Locker lockSharedLocal(byte[] key, int hash) throws LockFailureException {
        return mLockManager.lockSharedLocal(mId, key, hash);
    }

    final Locker lockExclusiveLocal(byte[] key, int hash) throws LockFailureException {
        return mLockManager.lockExclusiveLocal(mId, key, hash);
    }

    /**
     * Writes to the redo log if defined and the default durability mode isn't NO_REDO.
     *
     * @return non-zero position if caller should call txnCommitSync
     */
    final long redoStoreNullTxn(byte[] key, byte[] value) throws IOException {
        RedoWriter redo = mDatabase.mRedoWriter;
        DurabilityMode mode;
        if (redo == null || (mode = mDatabase.mDurabilityMode) == DurabilityMode.NO_REDO) {
            return 0;
        }
        return mDatabase.anyTransactionContext().redoStoreAutoCommit
            (redo.txnRedoWriter(), mId, key, value, mode);
    }

    final void txnCommitSync(long commitPos) throws IOException {
        mDatabase.mRedoWriter.txnCommitSync(commitPos);
    }

    /**
     * @see LocalDatabase#markDirty
     */
    final boolean markDirty(Node node) throws IOException {
        return mDatabase.markDirty(this, node);
    }
}
