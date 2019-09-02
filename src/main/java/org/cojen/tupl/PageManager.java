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

package org.cojen.tupl;

import java.io.IOException;

import java.util.concurrent.locks.ReentrantLock;

import org.cojen.tupl.io.PageArray;

/**
 * Manages free and deleted pages for {@link DurablePageDb}.
 *
 * @author Brian S O'Neill
 */
final class PageManager {
    /*

    Header structure is encoded as follows, in 140 bytes:

    +--------------------------------------------+
    | long: total page count                     |
    | PageQueue: regular free list (44 bytes)    |
    | PageQueue: recycle free list (44 bytes)    |
    | PageQueue: reserve list (44 bytes)         |
    +--------------------------------------------+

    */

    // Indexes of entries in header.
    static final int I_TOTAL_PAGE_COUNT  = 0;
    static final int I_REGULAR_QUEUE     = I_TOTAL_PAGE_COUNT + 8;
    static final int I_RECYCLE_QUEUE     = I_REGULAR_QUEUE + PageQueue.HEADER_SIZE;
    static final int I_RESERVE_QUEUE     = I_RECYCLE_QUEUE + PageQueue.HEADER_SIZE;

    private final PageArray mPageArray;

    // One remove lock for all queues.
    private final ReentrantLock mRemoveLock;
    private long mTotalPageCount;
    private long mPageLimit;
    private ThreadLocal<Long> mPageLimitOverride;

    private final PageQueue mRegularFreeList;
    private final PageQueue mRecycleFreeList;

    private volatile boolean mCompacting;
    private long mCompactionTargetPageCount = Long.MAX_VALUE;
    private PageQueue mReserveList;
    private long mReclaimUpperBound = Long.MIN_VALUE;

    static final int
        ALLOC_TRY_RESERVE = -1, // Create pages: no.  Compaction zone: yes
        ALLOC_NORMAL = 0,       // Create pages: yes. Compaction zone: no
        ALLOC_RESERVE = 1;      // Create pages: yes. Compaction zone: yes

    /**
     * Create a new PageManager.
     */
    PageManager(PageArray array) throws IOException {
        this(null, false, array, PageOps.p_null(), 0);
    }

    /**
     * Create a restored PageManager.
     *
     * @param header source for reading allocator root structure
     * @param offset offset into header
     */
    PageManager(EventListener debugListener, PageArray array, /*P*/ byte[] header, int offset)
        throws IOException
    {
        this(debugListener, true, array, header, offset);
    }

    private PageManager(EventListener debugListener,
                        boolean restored, PageArray array, /*P*/ byte[] header, int offset)
        throws IOException
    {
        if (array == null) {
            throw new IllegalArgumentException("PageArray is null");
        }

        mPageArray = array;

        // Lock must be reentrant and unfair. See notes in PageQueue.
        mRemoveLock = new ReentrantLock(false);
        mRegularFreeList = PageQueue.newRegularFreeList(this);
        mRecycleFreeList = PageQueue.newRecycleFreeList(this);

        mPageLimit = -1; // no limit

        try {
            if (!restored) {
                // Pages 0 and 1 are reserved.
                mTotalPageCount = 4;
                mRegularFreeList.init(2);
                mRecycleFreeList.init(3);
            } else {
                mTotalPageCount = readTotalPageCount(header, offset);

                if (debugListener != null) {
                    debugListener.notify(EventType.DEBUG, "TOTAL_PAGE_COUNT: %1$d",
                                         mTotalPageCount);
                }

                long actualPageCount = array.getPageCount();
                if (actualPageCount > mTotalPageCount) {
                    if (!array.isReadOnly()) {
                        // Attempt to truncate extra uncommitted pages.
                        if (mTotalPageCount < 4) {
                            // Don't truncate to something obviously wrong.
                            throw new CorruptDatabaseException
                                ("Invalid total page count: " + mTotalPageCount);
                        }
                        array.truncatePageCount(mTotalPageCount);
                    }
                } else if (actualPageCount < mTotalPageCount) {
                    // Not harmful -- can be caused by pre-allocated append tail node.
                }

                PageQueue reserve;
                fullLock();
                try {
                    mRegularFreeList.init(debugListener, header, offset + I_REGULAR_QUEUE);
                    mRecycleFreeList.init(debugListener, header, offset + I_RECYCLE_QUEUE);

                    if (PageQueue.exists(header, offset + I_RESERVE_QUEUE)) {
                        reserve = mRegularFreeList.newReserveFreeList();
                        try {
                            reserve.init(debugListener, header, offset + I_RESERVE_QUEUE);
                        } catch (Throwable e) {
                            reserve.delete();
                            throw e;
                        }
                    } else {
                        reserve = null;
                        if (debugListener != null) {
                            debugListener.notify(EventType.DEBUG, "Reserve free list is null");
                        }
                    }
                } finally {
                    fullUnlock();
                }

                if (reserve != null) {
                    try {
                        // Reclaim reserved pages from an aborted compaction.
                        reserve.reclaim(mRemoveLock, mTotalPageCount - 1);
                    } finally {
                        reserve.delete();
                    }
                }
            }
        } catch (Throwable e) {
            delete();
            throw e;
        }
    }

    /**
     * Must be called when object is no longer referenced.
     */
    void delete() {
        if (mRegularFreeList != null) {
            mRegularFreeList.delete();
        }
        if (mRecycleFreeList != null) {
            mRecycleFreeList.delete();
        }
        PageQueue reserve = mReserveList;
        if (reserve != null) {
            reserve.delete();
            mReserveList = null;
        }
    }

    static long readTotalPageCount(/*P*/ byte[] header, int offset) {
        return PageOps.p_longGetLE(header, offset + I_TOTAL_PAGE_COUNT);
    }

    public PageArray pageArray() {
        return mPageArray;
    }

    /**
     * Allocates a page from the free list or by growing the underlying page
     * array. No page allocations are permanent until after commit is called.
     *
     * @return non-zero page id
     */
    public long allocPage() throws IOException {
        return allocPage(ALLOC_NORMAL);
    }

    /**
     * @param mode ALLOC_TRY_RESERVE, ALLOC_NORMAL, ALLOC_RESERVE
     * @return 0 if mode is ALLOC_TRY_RESERVE and free lists are empty
     */
    long allocPage(int mode) throws IOException {
        while (true) {
            long pageId;
            alloc: {
                // Remove recently recycled pages first, reducing page writes caused by queue
                // drains. For allocations by the reserve lists (during compaction), pages can
                // come from the reserve lists themselves. This favors using pages in the
                // compaction zone, since the reserve lists will be discarded anyhow.

                pageId = mRecycleFreeList.tryUnappend();
                if (pageId != 0) {
                    break alloc;
                }

                final ReentrantLock lock = mRemoveLock;
                lock.lock();
                pageId = mRecycleFreeList.tryRemove(lock);
                if (pageId != 0) {
                    // Lock has been released as a side-effect.
                    break alloc;
                }
                pageId = mRegularFreeList.tryRemove(lock);
                if (pageId != 0) {
                    // Lock has been released as a side-effect.
                    break alloc;
                }

                if (mode >= ALLOC_NORMAL) {
                    // Expand the file or abort compaction.

                    PageQueue reserve = mReserveList;
                    if (reserve != null) {
                        if (mCompacting) {
                            // Only do a volatile write the first time.
                            mCompacting = false;
                        }
                        // Attempt to raid the reserves.
                        if (mReclaimUpperBound == Long.MIN_VALUE) {
                            pageId = reserve.tryRemove(lock);
                            if (pageId != 0) {
                                // Lock has been released as a side-effect.
                                return pageId;
                            }
                        }
                    }

                    try {
                        pageId = increasePageCount();
                    } catch (Throwable e) {
                        lock.unlock();
                        throw e;
                    }
                }

                lock.unlock();
                return pageId;
            }

            if (mode == ALLOC_NORMAL && pageId >= mCompactionTargetPageCount && mCompacting) {
                // Page is in the compaction zone, so allocate another.
                mReserveList.append(pageId, true);
                continue;
            }

            return pageId;
        }
    }

    /**
     * Deletes a page to be reused after commit is called. No page deletions
     * are permanent until after commit is called.
     *
     * @param force when true, never throw an IOException; OutOfMemoryError is still possible
     * @throws IllegalArgumentException if id is less than or equal to one
     */
    public void deletePage(long id, boolean force) throws IOException {
        if (id >= mCompactionTargetPageCount && mCompacting) {
            // Page is in the compaction zone.
            mReserveList.append(id, force);
        } else {
            mRegularFreeList.append(id, force);
        }
    }

    /**
     * Recycles a page for immediate re-use. An IOException isn't expected, but an
     * OutOfMemoryError is always possible.
     *
     * @throws IllegalArgumentException if id is less than or equal to one
     */
    public void recyclePage(long id) throws IOException {
        if (id >= mCompactionTargetPageCount && mCompacting) {
            // Page is in the compaction zone.
            mReserveList.append(id, true);
        } else {
            mRecycleFreeList.append(id, true);
        }
    }

    public void allocAndRecyclePage() throws IOException {
        long pageId;
        mRemoveLock.lock();
        try {
            pageId = increasePageCount();
        } finally {
            mRemoveLock.unlock();
        }
        recyclePage(pageId);
    }

    /**
     * Caller must hold mRemoveLock.
     *
     * @return newly allocated page id
     */
    private long increasePageCount() throws IOException, DatabaseFullException {
        long total = mTotalPageCount;

        long limit;
        {
            ThreadLocal<Long> override = mPageLimitOverride;
            Long limitObj;
            if (override == null || (limitObj = override.get()) == null) {
                limit = mPageLimit;
            } else {
                limit = limitObj;
            }

            long max = mPageArray.getPageCountLimit();
            if (max > 0 && (limit < 0 || limit > max)) {
                limit = max;
            }
        }

        if (limit >= 0 && total >= limit) {
            throw new DatabaseFullException
                ("Capacity limit reached: " + (limit * mPageArray.pageSize()));
        }

        mTotalPageCount = total + 1;
        return total;
    }

    public void pageLimit(long limit) {
        mRemoveLock.lock();
        try {
            mPageLimit = limit;
        } finally {
            mRemoveLock.unlock();
        }
    }

    public void pageLimitOverride(long limit) {
        mRemoveLock.lock();
        try {
            if (limit == 0) {
                if (mPageLimitOverride != null) {
                    mPageLimitOverride.remove();
                }
            } else {
                if (mPageLimitOverride == null) {
                    mPageLimitOverride = new ThreadLocal<>();
                }
                mPageLimitOverride.set(limit);
            }
        } finally {
            mRemoveLock.unlock();
        }
    }

    public long pageLimit() {
        mRemoveLock.lock();
        try {
            return mPageLimit;
        } finally {
            mRemoveLock.unlock();
        }
    }

    /**
     * Caller must hold exclusive commit lock.
     *
     * @return false if target cannot be met
     */
    public boolean compactionStart(long targetPageCount) throws IOException {
        if (mCompacting) {
            throw new IllegalStateException("Compaction in progress");
        }

        if (mReserveList != null) {
            throw new IllegalStateException();
        }

        if (targetPageCount < 2) {
            return false;
        }

        mRemoveLock.lock();
        try {
            if (targetPageCount >= mTotalPageCount
                && targetPageCount >= mPageArray.getPageCount())
            {
                return false;
            }
        } finally {
            mRemoveLock.unlock();
        }

        long initPageId = allocPage(ALLOC_TRY_RESERVE);
        if (initPageId == 0) {
            return false;
        }

        PageQueue reserve;
        try {
            reserve = mRegularFreeList.newReserveFreeList();
            reserve.init(initPageId);
        } catch (Throwable e) {
            try {
                recyclePage(initPageId);
            } catch (IOException e2) {
                // Ignore.
            }
            throw e;
        }

        mRemoveLock.lock();
        if (mReserveList != null) {
            mReserveList.delete();
        }
        mReserveList = reserve;
        mCompactionTargetPageCount = targetPageCount;
        // Volatile write performed after all the states it depends on are set. Because remove
        // lock is held, only append operations depend on this ordering.
        mCompacting = true;
        mRemoveLock.unlock();

        return true;
    }

    /**
     * @return false if aborted
     */
    public boolean compactionScanFreeList(CommitLock commitLock) throws IOException {
        return compactionScanFreeList(commitLock, mRecycleFreeList)
            && compactionScanFreeList(commitLock, mRegularFreeList);
    }

    private boolean compactionScanFreeList(CommitLock commitLock, PageQueue list)
        throws IOException
    {
        long target;
        mRemoveLock.lock();
        target = list.getRemoveScanTarget();
        mRemoveLock.unlock();

        CommitLock.Shared shared = commitLock.acquireShared();
        try {
            while (mCompacting) {
                mRemoveLock.lock();
                long pageId;
                if (list.isRemoveScanComplete(target)
                    || (pageId = list.tryRemove(mRemoveLock)) == 0)
                {
                    mRemoveLock.unlock();
                    return mCompacting;
                }
                if (pageId >= mCompactionTargetPageCount) {
                    mReserveList.append(pageId, true);
                } else {
                    mRecycleFreeList.append(pageId, true);
                }
                if (commitLock.hasQueuedThreads()) {
                    shared.release();
                    shared = commitLock.acquireShared();
                }
            }
        } finally {
            shared.release();
        }

        return false;
    }

    /**
     * @return false if verification failed
     */
    public boolean compactionVerify() throws IOException {
        if (!mCompacting) {
            // Prevent caller from doing any more work.
            return true;
        }
        long total;
        mRemoveLock.lock();
        total = mTotalPageCount;
        mRemoveLock.unlock();
        return mReserveList.verifyPageRange(mCompactionTargetPageCount, total);
    }

    /**
     * @return false if aborted
     */
    public boolean compactionEnd(CommitLock commitLock) throws IOException {
        // Default will reclaim everything.
        long upperBound = Long.MAX_VALUE;

        boolean ready = compactionVerify();

        // Need exclusive commit lock to prevent delete and recycle from attempting to operate
        // against a null reserve list. A race condition exists otherwise. Acquire full lock
        // too out of paranoia.
        commitLock.acquireExclusive();
        fullLock();

        if (ready && (ready = mCompacting && (mTotalPageCount > mCompactionTargetPageCount
                                              || mPageArray.getPageCount() > mTotalPageCount)))
        {
            // When locks are released, compaction is commit-ready. All pages in the compaction
            // zone are accounted for, but the reserve list's queue nodes might be in the valid
            // zone. Most pages will simply be discarded.
            mTotalPageCount = mCompactionTargetPageCount;
            upperBound = mTotalPageCount - 1;
        }

        mCompacting = false;
        mCompactionTargetPageCount = Long.MAX_VALUE;

        // Set the reclamation upper bound with full lock held to prevent allocPage from
        // stealing from the reserves. They're now off limits.
        mReclaimUpperBound = upperBound;

        fullUnlock();
        commitLock.releaseExclusive();

        return ready;
    }

    public void compactionReclaim() throws IOException {
        mRemoveLock.lock();
        PageQueue reserve = mReserveList;
        long upperBound = mReclaimUpperBound;
        mReserveList = null;
        mReclaimUpperBound = Long.MIN_VALUE;
        mRemoveLock.unlock();

        if (reserve != null) {
            try {
                reserve.reclaim(mRemoveLock, upperBound);
            } finally {
                reserve.delete();
            }
        }
    }

    /**
     * Called after compaction, to actually shrink the file.
     */
    public boolean truncatePages() throws IOException {
        mRemoveLock.lock();
        try {
            if (mTotalPageCount < mPageArray.getPageCount()) {
                try {
                    mPageArray.truncatePageCount(mTotalPageCount);
                    return true;
                } catch (IllegalStateException e) {
                    // Snapshot in progress.
                    return false;
                }
            }
        } finally {
            mRemoveLock.unlock();
        }
        return false;
    }

    /**
     * Initiates the process for making page allocations and deletions permanent.
     *
     * @param header destination for writing page manager structures
     * @param offset offset into header
     */
    public void commitStart(/*P*/ byte[] header, int offset) throws IOException {
        fullLock();
        try {
            // Allow commit to exceed the page limit. Without this, database cannot complete a
            // checkpoint when the limit is reached.
            if (mPageLimit > 0) {
                if (mPageLimitOverride == null) {
                    mPageLimitOverride = new ThreadLocal<>();
                }
                mPageLimitOverride.set(-1L);
            }

            // Pre-commit all first, draining the append heaps.
            try {
                mRegularFreeList.preCommit();
                mRecycleFreeList.preCommit();
                if (mReserveList != null) {
                    mReserveList.preCommit();
                }
            } catch (WriteFailureException | DatabaseFullException e) {
                // Should not happen with page limit override.
                throw e;
            } catch (IOException e) {
                throw WriteFailureException.make(e);
            } finally {
                if (mPageLimitOverride != null) {
                    mPageLimitOverride.remove();
                }
            }

            // Total page count is written after append heaps have been
            // drained, because additional pages might have been allocated.
            PageOps.p_longPutLE(header, offset + I_TOTAL_PAGE_COUNT, mTotalPageCount);

            mRegularFreeList.commitStart(header, offset + I_REGULAR_QUEUE);
            mRecycleFreeList.commitStart(header, offset + I_RECYCLE_QUEUE);
            if (mReserveList != null) {
                mReserveList.commitStart(header, offset + I_RESERVE_QUEUE);
            }
        } finally {
            fullUnlock();
        }
    }

    /**
     * Finishes the commit process. Must pass in header and offset used by commitStart.
     *
     * @param header contains page manager structures
     * @param offset offset into header
     */
    public void commitEnd(/*P*/ byte[] header, int offset) throws IOException {
        mRemoveLock.lock();
        try {
            mRegularFreeList.commitEnd(header, offset + I_REGULAR_QUEUE);
            mRecycleFreeList.commitEnd(header, offset + I_RECYCLE_QUEUE);
            if (mReserveList != null) {
                mReserveList.commitEnd(header, offset + I_RESERVE_QUEUE);
            }
        } finally {
            mRemoveLock.unlock();
        }
    }

    private void fullLock() {
        // Avoid deadlock by acquiring append locks before remove lock. This matches the lock
        // order used by the deletePage method, which might call allocPage, which in turn
        // acquires remove lock.
        mRegularFreeList.appendLock().lock();
        mRecycleFreeList.appendLock().lock();
        if (mReserveList != null) {
            mReserveList.appendLock().lock();
        }
        mRemoveLock.lock();
    }

    private void fullUnlock() {
        mRemoveLock.unlock();
        if (mReserveList != null) {
            mReserveList.appendLock().unlock();
        }
        mRecycleFreeList.appendLock().unlock();
        mRegularFreeList.appendLock().unlock();
    }

    void addTo(PageDb.Stats stats) {
        fullLock();
        try {
            stats.totalPages += mTotalPageCount;
            mRegularFreeList.addTo(stats);
            mRecycleFreeList.addTo(stats);
            if (mReserveList != null) {
                mReserveList.addTo(stats);
            }
        } finally {
            fullUnlock();
        }
    }

    /**
     * Method must be invoked with remove lock held.
     */
    boolean isPageOutOfBounds(long id) {
        return id <= 1 || id >= mTotalPageCount;
    }
}
