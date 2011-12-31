/*
 *  Copyright 2011 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import java.util.concurrent.locks.Lock;

import static org.cojen.tupl.Node.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class Database implements Closeable {
    private static final int DEFAULT_CACHED_NODES = 1000;

    // Approximate byte overhead per node. Influenced by many factors,
    // including pointer size and child node references. This estimate assumes
    // 32-bit pointers.
    private static final int NODE_OVERHEAD = 80;

    private static int nodeCountFromBytes(long bytes, int pageSize) {
         pageSize += NODE_OVERHEAD;
         long count = (bytes + pageSize - 1) / pageSize;
         return count <= Integer.MAX_VALUE ? (int) count : Integer.MAX_VALUE;
    }

    private static final int ENCODING_VERSION = 20111127;

    private static final int I_ENCODING_VERSION        = 0;
    private static final int I_ROOT_PAGE_ID            = I_ENCODING_VERSION + 4;
    private static final int I_MASTER_UNDO_LOG_PAGE_ID = I_ROOT_PAGE_ID + 8;
    private static final int I_TRANSACTION_ID          = I_MASTER_UNDO_LOG_PAGE_ID + 8;
    private static final int I_REDO_LOG_ID             = I_TRANSACTION_ID + 8;
    private static final int HEADER_SIZE               = I_REDO_LOG_ID + 8;

    private static final byte KEY_TYPE_INDEX_NAME = 0;
    private static final byte KEY_TYPE_INDEX_ID = 1;

    private static final int REGISTRY_ID = 0, REGISTRY_KEY_MAP_ID = 1, MAX_RESERVED_ID = 255;

    private static final int DEFAULT_PAGE_SIZE = 4096;

    final DurabilityMode mDurabilityMode;
    final long mDefaultLockTimeoutNanos;
    final LockManager mLockManager;
    final RedoLog mRedoLog;
    final PageStore mPageStore;

    private final BufferPool mSpareBufferPool;

    private final Latch mCacheLatch;
    private final int mMaxCachedNodeCount;
    private int mCachedNodeCount;
    private Node mMostRecentlyUsed;
    private Node mLeastRecentlyUsed;
    // Node somewhere in between least and most recently used. All nodes less
    // recently used than the barrier are in the "flush zone". If dirty, they
    // must be written and state be switched to flushed. All other nodes are in
    // the "safe zone", and they aren't immediately flushed.
    private Node mFlushBarrier;
    // Hops from most recently used. Is zero when flush barrier is the most recently used.
    private int mFlushBarrierDistance;
    private final int mFlushBarrierTargetDistance;

    private final Lock mSharedCommitLock;

    // Is either CACHED_DIRTY_0 or CACHED_DIRTY_1. Access is guarded by commit lock.
    private byte mCommitState;

    // The root tree, which maps tree ids to other tree root node ids.
    private final Tree mRegistry;
    // Maps tree name keys to ids.
    private final Tree mRegistryKeyMap;
    // Maps tree names to open trees.
    private final Map<byte[], Tree> mOpenTrees;
    private final Map<Long, Tree> mOpenTreesById;

    private final Object mTxnIdLock = new Object();
    // The following fields are guarded by mTxnIdLock.
    private long mTxnId;
    private UndoLog mTopUndoLog;
    private int mUndoLogCount;

    private final Object mCheckpointLock = new Object();

    public static Database open(DatabaseConfig config) throws IOException {
        return new Database(config);
    }

    private Database(DatabaseConfig config) throws IOException {
        File baseFile = config.mBaseFile;
        if (baseFile == null) {
            throw new IllegalArgumentException("No base file provided");
        }
        if (baseFile.isDirectory()) {
            throw new IllegalArgumentException("Base file is a directory: " + baseFile);
        }

        int pageSize = config.mPageSize;
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }

        int minCache, maxCache;
        cacheSize: {
            long minCachedBytes = config.mMinCachedBytes;
            long maxCachedBytes = config.mMaxCachedBytes;

            if (maxCachedBytes == 0) {
                maxCachedBytes = minCachedBytes;
                if (maxCachedBytes == 0) {
                    minCache = maxCache = DEFAULT_CACHED_NODES;
                    break cacheSize;
                }
            }

            if (minCachedBytes > maxCachedBytes) {
                throw new IllegalArgumentException
                    ("Minimum cache size exceeds maximum: " +
                     minCachedBytes + " > " + maxCachedBytes);
            }

            minCache = nodeCountFromBytes(minCachedBytes, pageSize);
            maxCache = nodeCountFromBytes(maxCachedBytes, pageSize);
        }

        if (maxCache < 3) {
            // One is needed for the root node, and at least two nodes are
            // required for eviction code to function correctly. It always
            // assumes that the least recently used node points to a valid,
            // more recently used node.
            throw new IllegalArgumentException
                ("Maximum cached node count is too small: " + maxCache);
        }

        mCacheLatch = new Latch();
        mMaxCachedNodeCount = maxCache - 1; // less one for root

        {
            long threshold = config.mFlushThresholdBytes;
            if (threshold < 0) {
                mFlushBarrierTargetDistance = Integer.MAX_VALUE;
            } else {
                long targetDistance = (threshold + pageSize - 1) / pageSize;
                targetDistance = Math.max(1, Math.min(targetDistance, Integer.MAX_VALUE));
                mFlushBarrierTargetDistance = (int) targetDistance;
            }
        }

        mDurabilityMode = config.mDurabilityMode;
        mDefaultLockTimeoutNanos = config.mLockTimeoutNanos;
        mLockManager = new LockManager(mDefaultLockTimeoutNanos);

        String basePath = baseFile.getPath();
        File file = new File(basePath + ".db");

        mPageStore = new FilePageStore(file, config.mFileSync, config.mReadOnly, pageSize);

        try {
            int spareBufferCount = Runtime.getRuntime().availableProcessors();
            mSpareBufferPool = new BufferPool(mPageStore.pageSize(), spareBufferCount);

            mSharedCommitLock = mPageStore.sharedCommitLock();
            mSharedCommitLock.lock();
            try {
                mCommitState = CACHED_DIRTY_0;
            } finally {
                mSharedCommitLock.unlock();
            }

            byte[] header = new byte[HEADER_SIZE];
            mPageStore.readExtraCommitData(header);

            mRegistry = new Tree(this, REGISTRY_ID, null, null, loadRegistryRoot(header));
            mOpenTrees = new TreeMap<byte[], Tree>(KeyComparator.THE);
            mOpenTreesById = new HashMap<Long, Tree>();

            synchronized (mTxnIdLock) {
                mTxnId = DataIO.readLong(header, I_TRANSACTION_ID);
            }
            long redoLogId = DataIO.readLong(header, I_REDO_LOG_ID);

            // Initialized, but not open yet.
            mRedoLog = new RedoLog(baseFile, redoLogId);

            // Pre-allocate nodes. They are automatically added to the usage
            // list, and so nothing special needs to be done to allow them to
            // get used. Since the initial state is clean, evicting these nodes
            // does nothing.
            try {
                for (int i=minCache; --i>0; ) { // less one for root
                    allocLatchedNode().releaseExclusive();
                }
            } catch (OutOfMemoryError e) {
                mMostRecentlyUsed = null;
                mLeastRecentlyUsed = null;
                mFlushBarrier = null;

                throw new OutOfMemoryError
                    ("Unable to allocate the minimum required number of cached nodes: " +
                     minCache);
            }

            // Open mRegistryKeyMap.
            {
                byte[] encodedRootId = mRegistry.get(Transaction.BOGUS, Utils.EMPTY_BYTES);

                Node rootNode;
                if (encodedRootId == null) {
                    // Create a new empty leaf node.
                    rootNode = new Node(mPageStore.pageSize(), true);
                } else {
                    rootNode = new Node(mPageStore.pageSize(), false);
                    rootNode.read(this, DataIO.readLong(encodedRootId, 0));
                }
            
                mRegistryKeyMap = new Tree
                    (this, REGISTRY_KEY_MAP_ID, Utils.EMPTY_BYTES, null, rootNode);
            }

            // Perform recovery by examining redo and undo logs.

            UndoLog masterUndoLog;
            LHashTable.Obj<UndoLog> undoLogs;
            {
                long nodeId = DataIO.readLong(header, I_MASTER_UNDO_LOG_PAGE_ID);
                if (nodeId == 0) {
                    masterUndoLog = null;
                    undoLogs = null;
                } else {
                    masterUndoLog = UndoLog.recoverMasterUndoLog(this, nodeId);
                    undoLogs = masterUndoLog.recoverLogs();
                }
            }

            // TODO: Recovery should only need to perform at most one checkpoint.
            // The current approach is fine, but it's just a bit messy.

            if (redoReplay(undoLogs)) {
                // Make sure old redo log is deleted. Process might have exited
                // before last checkpoint could delete it.
                mRedoLog.deleteOldFile(redoLogId - 1);

                // Checkpoint now to ensure all old redo log entries are durable.
                checkpoint(true);

                while (mRedoLog.isReplayMode()) {
                    // Last checkpoint was interrupted, so apply next log file too.
                    redoReplay(undoLogs);
                    checkpoint(true);
                }
            }

            if (masterUndoLog != null) {
                // Rollback all remaining undo logs. They were never explicitly
                // rolled back. This also deletes the master undo log.
                if (masterUndoLog.rollbackRemaining(undoLogs)) {
                    // Checkpoint again to ensure that undo logs don't get
                    // re-applied following a restart.
                    checkpoint(true);
                }
            }
        } catch (Throwable e) {
            try {
                close();
            } catch (IOException e2) {
                // Ignore.
            }
            throw Utils.rethrow(e);
        }
    }

    // FIXME: testing
    void trace() throws IOException {
        java.util.BitSet pages = mPageStore.tracePages();
        mRegistry.mRoot.tracePages(this, pages);
        mRegistryKeyMap.mRoot.tracePages(this, pages);
        synchronized (mOpenTrees) {
            for (Tree tree : mOpenTrees.values()) {
                tree.mRoot.tracePages(this, pages);
            }
        }
        System.out.println(pages);
        System.out.println("lost: " + pages.cardinality());
        System.out.println(mPageStore.stats());
    }

    private boolean redoReplay(LHashTable.Obj<UndoLog> undoLogs) throws IOException {
        RedoLogTxnScanner scanner = new RedoLogTxnScanner();
        if (!mRedoLog.replay(scanner) ||
            !mRedoLog.replay(new RedoLogApplier(this, scanner, undoLogs)))
        {
            return false;
        }

        long redoTxnId = scanner.highestTxnId();
        if (redoTxnId != 0) synchronized (mTxnIdLock) {
            // Subtract for modulo comparison.
            if (mTxnId == 0 || (redoTxnId - mTxnId) > 0) {
                mTxnId = redoTxnId;
            }
        }

        return true;
    }

    /**
     * Returns the given named index, return null if not found.
     *
     * @return shared Index instance; null if not found
     */
    public Index findIndex(byte[] name) throws IOException {
        return openIndex(name.clone(), false);
    }

    /**
     * Returns the given named index, return null if not found. Name is UTF-8
     * encoded.
     *
     * @return shared Index instance; null if not found
     */
    public Index findIndex(String name) throws IOException {
        return openIndex(name.getBytes("UTF-8"), false);
    }

    /**
     * Returns the given named index, creating it if necessary.
     *
     * @return shared Index instance
     */
    public Index openIndex(byte[] name) throws IOException {
        return openIndex(name.clone(), true);
    }

    /**
     * Returns the given named index, creating it if necessary. Name is UTF-8
     * encoded.
     *
     * @return shared Index instance
     */
    public Index openIndex(String name) throws IOException {
        return openIndex(name.getBytes("UTF-8"), true);
    }

    /**
     * Returns a new Transaction with the {@link DatabaseConfig
     * #setDurabilityMode default} durability mode.
     */
    public Transaction newTransaction() {
        return doNewTransaction(mDurabilityMode);
    }

    /**
     * Returns a new Transaction with the given durability mode. If null, the
     * default is used.
     */
    public Transaction newTransaction(DurabilityMode durabilityMode) {
        return doNewTransaction(durabilityMode == null ? mDurabilityMode : durabilityMode);
    }

    private Transaction doNewTransaction(DurabilityMode durabilityMode) {
        return new Transaction
            (this, durabilityMode, LockMode.UPGRADABLE_READ, mDefaultLockTimeoutNanos);
    }

    /**
     * Caller must hold commit lock. This ensures that highest transaction id
     * is persisted correctly by checkpoint.
     *
     * @param txnId pass zero to select a transaction id
     * @return non-zero transaction id
     */
    long register(UndoLog undo, long txnId) {
        synchronized (mTxnIdLock) {
            UndoLog top = mTopUndoLog;
            if (top != null) {
                undo.mPrev = top;
                top.mNext = undo;
            }
            mTopUndoLog = undo;
            mUndoLogCount++;

            while (txnId == 0) {
                txnId = ++mTxnId;
            }
            return txnId;
        }
    }

    /**
     * Caller must hold commit lock. This ensures that highest transaction id
     * is persisted correctly by checkpoint.
     *
     * @return non-zero transaction id
     */
    long nextTransactionId() {
        long txnId;
        do {
            synchronized (mTxnIdLock) {
                txnId = ++mTxnId;
            }
        } while (txnId == 0);
        return txnId;
    }

    /**
     * Called only by UndoLog.
     */
    void unregister(UndoLog log) {
        synchronized (mTxnIdLock) {
            UndoLog prev = log.mPrev;
            UndoLog next = log.mNext;
            if (prev != null) {
                prev.mNext = next;
                log.mPrev = null;
            }
            if (next != null) {
                next.mPrev = prev;
                log.mNext = null;
            } else if (log == mTopUndoLog) {
                mTopUndoLog = prev;
            }
            mUndoLogCount--;
        }
    }

    /**
     * Returns an index by its identifier, returning null if not found.
     *
     * @throws IllegalArgumentException if id is reserved
     */
    public Index indexById(long id) throws IOException {
        if (id >= REGISTRY_ID && id <= MAX_RESERVED_ID) {
            throw new IllegalArgumentException("Invalid id: " + id);
        }

        Index index;

        final Lock commitLock = sharedCommitLock();
        commitLock.lock();
        try {
            synchronized (mOpenTrees) {
                Tree tree = mOpenTreesById.get(id);
                if (tree != null) {
                    return tree;
                }
            }

            byte[] idKey = new byte[9];
            idKey[0] = KEY_TYPE_INDEX_ID;
            DataIO.writeLong(idKey, 1, id);

            byte[] name = mRegistryKeyMap.get(null, idKey);

            if (name == null) {
                return null;
            }

            index = openIndex(name, false);
        } catch (Throwable e) {
            throw Utils.closeOnFailure(this, e);
        } finally {
            commitLock.unlock();
        }

        if (index == null) {
            // Registry needs to be repaired to fix this.
            throw new DatabaseException("Unable to find index in registry");
        }

        return index;
    }

    /**
     * Allows access to internal indexes which can use the redo log.
     */
    Index anyIndexById(long id) throws IOException {
        if (id == REGISTRY_KEY_MAP_ID) {
            return mRegistryKeyMap;
        }
        return indexById(id);
    }

    /**
     * Preallocates pages for immediate use.
     */
    public void preallocate(long bytes) throws IOException {
        int pageSize = pageSize();
        long pageCount = (bytes + pageSize - 1) / pageSize;
        if (pageCount > 0) {
            mPageStore.preallocate(pageCount);
            checkpoint(true);
        }
    }

    /**
     * Flushes, but does not sync, all non-flushed transactions. Transactions
     * committed with {@link DurabilityMode#NO_FLUSH no-flush} effectively
     * become {@link DurabilityMode#NO_SYNC no-sync} durable.
     */
    public void flush() throws IOException {
        mRedoLog.flush();
    }

    /**
     * Persists all non-flushed and non-sync'd transactions. Transactions
     * committed with {@link DurabilityMode#NO_FLUSH no-flush} and {@link
     * DurabilityMode#NO_SYNC no-sync} effectively become {@link
     * DurabilityMode#SYNC sync} durable.
     */
    public void sync() throws IOException {
        mRedoLog.sync();
    }

    /**
     * Durably sync and checkpoint all changes to the database. In addition to
     * ensuring that all transactions are durable, checkpointing ensures that
     * non-transactional modifications are durable.
     */
    public void checkpoint() throws IOException {
        checkpoint(false);
    }

    /**
     * Closes the database, ensuring durability of committed transactions. No
     * checkpoint is performed by this method, and so non-transactional
     * modifications can be lost.
     */
    @Override
    public void close() throws IOException {
        if (mRedoLog != null) {
            mRedoLog.close();
        }
        if (mPageStore != null) {
            mPageStore.close();
        }
    }

    /**
     * Loads the root registry node, or creates one if store is new. Root node
     * is not eligible for eviction.
     */
    private Node loadRegistryRoot(byte[] header) throws IOException {
        int version = DataIO.readInt(header, I_ENCODING_VERSION);

        if (version != 0) {
            if (version != ENCODING_VERSION) {
                throw new CorruptPageStoreException("Unknown encoding version: " + version);
            }
            long rootId = DataIO.readLong(header, I_ROOT_PAGE_ID);
            if (rootId != 0) {
                Node root = new Node(pageSize(), false);
                root.read(this, rootId);
                return root;
            }
        }

        // Assume store is new and return a new empty leaf node.
        return new Node(pageSize(), true);
    }

    private Index openIndex(byte[] name, boolean create) throws IOException {
        final Lock commitLock = sharedCommitLock();
        commitLock.lock();
        try {
            synchronized (mOpenTrees) {
                Tree tree = mOpenTrees.get(name);
                if (tree != null) {
                    return tree;
                }
            }

            byte[] nameKey = newKey(KEY_TYPE_INDEX_NAME, name);
            byte[] treeIdBytes = mRegistryKeyMap.get(null, nameKey);
            long treeId;

            if (treeIdBytes != null) {
                treeId = DataIO.readLong(treeIdBytes, 0);
            } else if (!create) {
                return null;
            } else synchronized (mOpenTrees) {
                treeIdBytes = mRegistryKeyMap.get(null, nameKey);
                if (treeIdBytes != null) {
                    treeId = DataIO.readLong(treeIdBytes, 0);
                } else {
                    treeIdBytes = new byte[8];

                    do {
                        treeId = Utils.randomId(REGISTRY_ID, MAX_RESERVED_ID);
                        DataIO.writeLong(treeIdBytes, 0, treeId);
                    } while (!mRegistry.insert(Transaction.BOGUS, treeIdBytes, Utils.EMPTY_BYTES));

                    if (!mRegistryKeyMap.insert(null, nameKey, treeIdBytes)) {
                        mRegistry.delete(Transaction.BOGUS, treeIdBytes);
                        throw new DatabaseException("Unable to insert index name");
                    }

                    byte[] idKey = newKey(KEY_TYPE_INDEX_ID, treeIdBytes);

                    if (!mRegistryKeyMap.insert(null, idKey, name)) {
                        mRegistryKeyMap.delete(null, nameKey);
                        mRegistry.delete(Transaction.BOGUS, treeIdBytes);
                        throw new DatabaseException("Unable to insert index id");
                    }
                }
            }

            byte[] encodedRootId = mRegistry.get(Transaction.BOGUS, treeIdBytes);

            Node rootNode;
            if (encodedRootId == null || encodedRootId.length == 0) {
                // Create a new empty leaf node.
                rootNode = new Node(pageSize(), true);
            } else {
                rootNode = new Node(pageSize(), false);
                rootNode.read(this, DataIO.readLong(encodedRootId, 0));
            }

            synchronized (mOpenTrees) {
                Tree tree = mOpenTrees.get(name);
                if (tree == null) {
                    tree = new Tree(this, treeId, treeIdBytes, name, rootNode);
                    mOpenTrees.put(name, tree);
                    mOpenTreesById.put(treeId, tree);
                }
                return tree;
            }
        } catch (Throwable e) {
            throw Utils.closeOnFailure(this, e);
        } finally {
            commitLock.unlock();
        }
    }

    private static byte[] newKey(byte type, byte[] payload) {
        byte[] key = new byte[1 + payload.length];
        key[0] = type;
        System.arraycopy(payload, 0, key, 1, payload.length);
        return key;
    }

    /**
     * Returns the fixed size of all pages in the store, in bytes.
     */
    int pageSize() {
        return mPageStore.pageSize();
    }

    /**
     * Access the shared commit lock, which prevents commits while held.
     */
    Lock sharedCommitLock() {
        return mSharedCommitLock;
    }

    /**
     * Returns a new or recycled Node instance, latched exclusively, with an id
     * of zero and a clean state.
     */
    Node allocLatchedNode() throws IOException {
        Node toFlush;
        final Latch cacheLatch = mCacheLatch;
        cacheLatch.acquireExclusive();
        try {
            int max = mMaxCachedNodeCount;
            if (mCachedNodeCount < max) {
                Node node = new Node(pageSize(), false);
                node.acquireExclusive();

                mCachedNodeCount++;
                if ((node.mLessUsed = mMostRecentlyUsed) == null) {
                    mLeastRecentlyUsed = node;
                    node.mInSafeZone = true;
                    mFlushBarrier = node;
                } else {
                    mMostRecentlyUsed.mMoreUsed = node;
                    toFlush = adjustFlushBarrierMRU(node);
                }
                mMostRecentlyUsed = node;

                return node;
            }

            do {
                Node node = mLeastRecentlyUsed;
                toFlush = adjustFlushBarrierMRU(node);
                (mLeastRecentlyUsed = node.mMoreUsed).mLessUsed = null;
                node.mMoreUsed = null;
                (node.mLessUsed = mMostRecentlyUsed).mMoreUsed = node;
                mMostRecentlyUsed = node;

                if (toFlush != null) {
                    cacheLatch.releaseExclusive();
                    flushNode(toFlush);
                    toFlush = null;                    
                    cacheLatch.acquireExclusive();
                }

                if (node.tryAcquireExclusive()) {
                    if (node.evict(this)) {
                        // Return with latch still held.
                        return node;
                    } else {
                        node.releaseExclusive();
                    }
                }
            } while (--max > 0);
        } finally {
            cacheLatch.releaseExclusive();
        }

        if (toFlush != null) {
            flushNode(toFlush);
        }

        // FIXME: Throw a better exception. Also, try all nodes again, but with
        // stronger latch request before giving up.
        throw new IllegalStateException("Cache is full");
    }

    /**
     * Adjust the flush barrier before a node becomes most recently used. A node
     * may need to flush as a result. Caller must hold cache latch.
     *
     * @param moving node which will move to most recently used position
     * @return optionally return latched node which must be flushed
     */
    private Node adjustFlushBarrierMRU(Node moving) {
        Node barrier = mFlushBarrier;
        if (barrier == moving) {
            // Moving node is the barrier itself. Choose another barrier.
            mFlushBarrier = barrier.mMoreUsed;
            return null;
        }

        if (moving.mInSafeZone) {
            // Movement within safe zone changes nothing.
            return null;
        }

        moving.mInSafeZone = true;

        int distance = mFlushBarrierDistance;
        if (distance < mFlushBarrierTargetDistance) {
            // Safe zone is in a growing phase.
            mFlushBarrierDistance = distance + 1;
            return null;
        }

        mFlushBarrier = barrier.mMoreUsed;
        // Old barrier is now a candidate to be flushed.
        barrier.mInSafeZone = false;

        // Caller must flush the node if dirty, but it must be latched. If
        // latch attempt fails, give up. This potentially allows some nodes in
        // the flush zone to survive. They'll get flushed by the next
        // checkpoint and be marked clean.
        if (barrier.tryAcquireExclusive()) {
            // Only flush dirty nodes which aren't being committed. They're
            // already being flushed (properly) by the checkpoint. Also, don't
            // flush nodes being split, since they aren't ready yet. They
            // cannot be evicted either in this state.
            if (barrier.mCachedState == mCommitState &&
                barrier.mSplit == null && barrier.mId != STUB_ID)
            {
                return barrier;
            }
            barrier.releaseExclusive();
        }

        return null;
    }

    /**
     * Caller must have acquired exclusive latch and verified node is dirty.
     */
    private void flushNode(Node node) throws IOException {
        node.write(this);
        // Move from dirty state to flushed state.
        node.mCachedState -= 2;
        node.releaseExclusive();
    }

    /**
     * Adjust the flush barrier before a node becomes least recently used.
     * Caller must hold cache latch.
     *
     * @param moving node which will move to least recently used position
     */
    private void adjustFlushBarrierLRU(Node moving) {
        int distance = mFlushBarrierDistance;
        if (moving.mInSafeZone && distance < mCachedNodeCount) {
            moving.mInSafeZone = false;
            mFlushBarrierDistance = distance - 1;
            Node barrier = mFlushBarrier;
            if (barrier == moving) {
                mFlushBarrier = barrier.mMoreUsed;
            }
        }
    }

    /**
     * Returns a new reserved node, latched exclusively and marked dirty. Caller
     * must hold commit lock.
     */
    Node newDirtyNode() throws IOException {
        Node node = allocLatchedNode();
        node.mId = mPageStore.reservePage();
        node.mCachedState = mCommitState;
        return node;
    }

    /**
     * Caller must hold commit lock and any latch on node.
     */
    boolean shouldMarkDirty(Node node) {
        return node.mCachedState != mCommitState && node.mId != Node.STUB_ID;
    }

    /**
     * Caller must hold commit lock and exclusive latch on node.
     *
     * @return false if node still needs to be dirtied and receive a new id
     */
    boolean markDirtyQuick(Node node) {
        int nodeState = node.mCachedState;
        int test = nodeState ^ mCommitState;
        if (test == 0 || node.mId == Node.STUB_ID) {
            // Xor result of 0 means dirty state matches and nothing to do.
            return true;
        }
        if (test == 6) { // 0xb0110
            // Xor result of 6 indicates that node state is flushed and bit 0
            // matches too. No need to allocate a new id, just flip state back
            // to dirty. Note that this allows a node in the "flush zone" to be
            // unflushed, but typically these nodes are recently used anyhow.
            // They're often already in the safe zone. State values are carefully
            // chosen, and so adding 2 is all that's required to become dirty.
            node.mCachedState = (byte) (nodeState + 2);
            return true;
        }
        // Node state is either clean or bit 0 doesn't match.
        return false;
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. Method does
     * nothing if node is already dirty. Latch is never released by this method,
     * even if an exception is thrown.
     *
     * @return true if just made dirty and id changed
     */
    boolean markDirty(Tree tree, Node node) throws IOException {
        if (markDirtyQuick(node)) {
            return false;
        } else {
            doMarkDirty(tree, node);
            return true;
        }
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. Method does
     * nothing if node is already dirty. Latch is never released by this method,
     * even if an exception is thrown.
     */
    void markUndoLogDirty(Node node) throws IOException {
        int nodeState = node.mCachedState;
        int test = nodeState ^ mCommitState;
        if (test == 0) {
            return;
        }
        if (test == 6) {
            node.mCachedState = (byte) (nodeState + 2);
            return;
        }
        long oldId = node.mId;
        long newId = mPageStore.reservePage();
        mPageStore.deletePage(oldId);
        node.write(this);
        node.mId = newId;
        node.mCachedState = mCommitState;
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. Method must
     * not be called if node is already dirty. Latch is never released by this
     * method, even if an exception is thrown.
     */
    void doMarkDirty(Tree tree, Node node) throws IOException {
        long oldId = node.mId;
        long newId = mPageStore.reservePage();
        if (oldId != 0) {
            mPageStore.deletePage(oldId);
        }
        if (node.isDirty()) {
            // Need to write old node contents when node is dirty and state
            // differs from commit state.
            node.write(this);
        }
        if (node == tree.mRoot && tree.mIdBytes != null) {
            byte[] newEncodedId = new byte[8];
            DataIO.writeLong(newEncodedId, 0, newId);
            mRegistry.store(Transaction.BOGUS, tree.mIdBytes, newEncodedId);
        }
        node.mId = newId;
        node.mCachedState = mCommitState;
    }

    /**
     * Similar to markDirty method except no new page is reserved, and old page
     * is not immediately deleted. Caller must hold commit lock and exclusive
     * latch on node. Latch is never released by this method, even if an
     * exception is thrown.
     */
    void prepareToDelete(Node node) throws IOException {
        // Hello. My name is ��igo Montoya. You killed my father. Prepare to die. 
        byte nodeState = node.mCachedState;
        if (nodeState >= CACHED_DIRTY_0 && nodeState != mCommitState) {
            node.write(this);
            // Switch to flushed state.
            node.mCachedState = (byte) (nodeState - 2);
        }
    }

    /**
     * Caller must hold commit lock and exclusive latch on node. Latch is
     * never released by this method, even if an exception is thrown.
     */
    void deleteNode(Node node) throws IOException {
        deletePage(node.mId, node.mCachedState);

        node.mId = 0;
        // FIXME: child node array should be recycled
        node.mChildNodes = null;

        // When node is re-allocated, it will be evicted. Ensure that eviction
        // doesn't write anything.
        node.mCachedState = CACHED_CLEAN;

        // Indicate that node is least recently used, allowing it to be
        // re-allocated immediately without evicting another node.
        final Latch cacheLatch = mCacheLatch;
        cacheLatch.acquireExclusive();
        try {
            Node lessUsed = node.mLessUsed;
            if (lessUsed != null) {
                adjustFlushBarrierLRU(node);
                Node moreUsed = node.mMoreUsed;
                if ((lessUsed.mMoreUsed = moreUsed) == null) {
                    mMostRecentlyUsed = lessUsed;
                } else {
                    moreUsed.mLessUsed = lessUsed;
                }
                node.mLessUsed = null;
                (node.mMoreUsed = mLeastRecentlyUsed).mLessUsed = node;
                mLeastRecentlyUsed = node;
            }
        } finally {
            cacheLatch.releaseExclusive();
        }
    }

    /**
     * Caller must hold commit lock.
     */
    void deletePage(long id, int cachedState) throws IOException {
        if (id != 0) {
            // Test that cached state is flushed or dirty, and that bit 0 matches.
            int test = cachedState ^ mCommitState;
            if (test == 0 || test == 6) {
                // Newly reserved page was never used, so recycle it.
                mPageStore.recyclePage(id);
            } else {
                // Old data must survive until after checkpoint.
                mPageStore.deletePage(id);
            }
        }
    }

    /**
     * Indicate that non-root node is most recently used. Root node is not
     * managed in usage list and cannot be evicted.
     */
    void used(Node node) throws IOException {
        // Because this method can be a bottleneck, don't wait for exclusive
        // latch. If node is popular, it will get more chances to be identified
        // as most recently used. This strategy works well enough because cache
        // eviction is always a best-guess approach.
        final Latch cacheLatch = mCacheLatch;
        if (cacheLatch.tryAcquireExclusive()) {
            Node toFlush;
            try {
                Node moreUsed = node.mMoreUsed;
                if (moreUsed == null) {
                    return;
                }
                toFlush = adjustFlushBarrierMRU(node);
                Node lessUsed = node.mLessUsed;
                if ((moreUsed.mLessUsed = lessUsed) == null) {
                    mLeastRecentlyUsed = moreUsed;
                } else {
                    lessUsed.mMoreUsed = moreUsed;
                }
                node.mMoreUsed = null;
                (node.mLessUsed = mMostRecentlyUsed).mMoreUsed = node;
                mMostRecentlyUsed = node;
            } finally {
                cacheLatch.releaseExclusive();
            }

            if (toFlush != null) {
                flushNode(toFlush);
            }
        }
    }

    byte[] removeSpareBuffer() throws InterruptedIOException {
        return mSpareBufferPool.remove();
    }

    void addSpareBuffer(byte[] buffer) {
        mSpareBufferPool.add(buffer);
    }

    void readPage(long id, byte[] page) throws IOException {
        mPageStore.readPage(id, page);
    }

    void writeReservedPage(long id, byte[] page) throws IOException {
        mPageStore.writeReservedPage(id, page);
    }

    private void checkpoint(boolean force) throws IOException {
        // Checkpoint lock ensures consistent state between page store and logs.
        synchronized (mCheckpointLock) {
            final Node root = mRegistry.mRoot;

            // Commit lock must be acquired first, to prevent deadlock.
            mPageStore.exclusiveCommitLock().lock();

            root.acquireShared();

            if (!force && root.mCachedState == CACHED_CLEAN) {
                // Root is clean, so nothing to do.
                root.releaseShared();
                mPageStore.exclusiveCommitLock().unlock();
                return;
            }

            // TODO: I don't like all this activity with exclusive commit lock
            // held. New RedoLog file can probably be created optimistically.
            // UndoLog can be refactored to store into a special Tree, but this
            // requires more features to be added to Tree first. Specifically,
            // large values and appending to them.

            final long redoLogId;
            try {
                redoLogId = mRedoLog.openNewFile();
            } catch (IOException e) {
                root.releaseShared();
                mPageStore.exclusiveCommitLock().unlock();
                throw e;
            }

            // List of nodes which must flushed.
            final List<DirtyNode> dirtyList = new ArrayList<DirtyNode>
                (Math.min(1000, mMaxCachedNodeCount));

            final UndoLog masterUndoLog;
            final long masterUndoLogId;
            synchronized (mTxnIdLock) {
                int count = mUndoLogCount;
                if (count == 0) {
                    masterUndoLog = null;
                    masterUndoLogId = 0;
                } else {
                    final int stateToFlush = mCommitState;
                    masterUndoLog = UndoLog.newMasterUndoLog(this);
                    byte[] workspace = null;
                    for (UndoLog log = mTopUndoLog; log != null; log = log.mPrev) {
                        workspace = log.writeToMaster(masterUndoLog, workspace);
                        log.gatherDirtyNodes(dirtyList, stateToFlush);
                    }
                    masterUndoLog.gatherDirtyNodes(dirtyList, stateToFlush);
                    masterUndoLogId = masterUndoLog.mNode.mId;
                    // Release latch to allow flush to acquire and release it.
                    masterUndoLog.mNode.releaseExclusive();
                }
            }

            mPageStore.commit(new PageStore.CommitCallback() {
                @Override
                public byte[] prepare() throws IOException {
                    return flush(dirtyList, redoLogId, masterUndoLogId);
                }
            });

            if (masterUndoLog != null) {
                // Delete the master undo log, which won't take effect until
                // the next checkpoint.
                masterUndoLog.mNode.acquireExclusive();
                masterUndoLog.truncate(0);
            }

            // Note: The delete step can get skipped if process exits at this
            // point. File is deleted again when database is re-opened.
            mRedoLog.deleteOldFile(redoLogId);
        }
    }

    /**
     * Method is invoked with exclusive commit lock and shared root node latch held.
     */
    private byte[] flush(final List<DirtyNode> dirtyList,
                         final long redoLogId,
                         final long masterUndoLogId)
        throws IOException
    {
        // Snapshot of all open trees.
        Tree[] trees;
        synchronized (mOpenTrees) {
            trees = mOpenTrees.values().toArray(new Tree[mOpenTrees.size()]);
        }

        final long txnId;
        synchronized (mTxnIdLock) {
            txnId = mTxnId;
        }

        /* FIXME: This code does not properly account for concurrent splits. Dirty
           nodes might not get written into the commit, and this has also been observed:
          
           java.lang.AssertionError: Split child is not already marked dirty
             at org.cojen.tupl.TreeNode.insertSplitChildRef(TreeNode.java:1178)
             at org.cojen.tupl.TreeCursor.finishSplit(TreeCursor.java:1647)
             at org.cojen.tupl.TreeCursor.finishSplit(TreeCursor.java:1640)
             at org.cojen.tupl.TreeCursor.store(TreeCursor.java:969)
             at org.cojen.tupl.TreeCursor.store(TreeCursor.java:746)
             at org.cojen.tupl.FullCursor.store(FullCursor.java:114)
             at org.cojen.tupl.TreeNodeTest.testInsert(TreeNodeTest.java:135)
             at org.cojen.tupl.TreeNodeTest.main(TreeNodeTest.java:107)

           A cursor based approach instead of breadth-first traversal might help.
        */ 

        final Node root = mRegistry.mRoot;
        final long rootId = root.mId;
        final int stateToFlush = mCommitState;
        mCommitState = (byte) (stateToFlush ^ 1);
        mPageStore.exclusiveCommitLock().unlock();

        // Gather all tree nodes to flush...

        mRegistry.gatherDirtyNodes(dirtyList, stateToFlush);

        mRegistryKeyMap.mRoot.acquireShared();
        mRegistryKeyMap.gatherDirtyNodes(dirtyList, stateToFlush);

        for (Tree tree : trees) {
            tree.mRoot.acquireShared();
            tree.gatherDirtyNodes(dirtyList, stateToFlush);
        }

        // Sort nodes by id, which helps make writes more sequentially ordered.
        // FIXME: testing
        //Collections.sort(dirtyList);

        // Now write out all the dirty nodes. Some of them will have already
        // been concurrently written out, so check again.

        for (int mi=0; mi<dirtyList.size(); mi++) {
            Node node = dirtyList.get(mi).mNode;
            dirtyList.set(mi, null);
            node.acquireExclusive();
            if (node.mCachedState != stateToFlush) {
                // Was already flushed.
                node.releaseExclusive();
            } else {
                node.mCachedState = CACHED_CLEAN;
                node.downgrade();
                try {
                    node.write(this);
                } finally {
                    node.releaseShared();
                }
            }
        }

        byte[] header = new byte[HEADER_SIZE];
        DataIO.writeInt(header, I_ENCODING_VERSION, ENCODING_VERSION);
        DataIO.writeLong(header, I_ROOT_PAGE_ID, rootId);
        DataIO.writeLong(header, I_MASTER_UNDO_LOG_PAGE_ID, masterUndoLogId);
        DataIO.writeLong(header, I_TRANSACTION_ID, txnId);
        // Add one to redoLogId, indicating the active log id.
        DataIO.writeLong(header, I_REDO_LOG_ID, redoLogId + 1);

        return header;
    }
}
