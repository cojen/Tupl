/*
 *  Copyright (C) 2021 Cojen.org
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
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.core;

import java.util.concurrent.TimeUnit;

import org.cojen.tupl.LockFailureException;
import org.cojen.tupl.LockResult;
import org.cojen.tupl.Transaction;

import org.cojen.tupl.util.Latch;

/**
 * 
 *
 * @author Generated by PageAccessTransformer from DetachedLockImpl.java
 */
/*P*/
class _DetachedLockImpl extends _Lock implements DetachedLock {
    _LockManager.Bucket mBucket;

    _DetachedLockImpl() {
    }

    void init(int hash, _LocalTransaction owner, _LockManager.Bucket bucket) {
        mHashCode = hash;
        mLockCount = 0x80000000; // held upgradable by the owner
        mOwner = owner;

        mBucket = bucket;
    }

    @Override
    public final void acquireShared(Transaction txn) throws LockFailureException {
        acquireShared((_LocalTransaction) txn);
    }

    final void acquireShared(_LocalTransaction txn) throws LockFailureException {
        long nanosTimeout = txn.lockTimeout(TimeUnit.NANOSECONDS);
        LockResult result = tryAcquireShared(txn, nanosTimeout);
        if (!result.isHeld()) {
            throw txn.failed(_LockManager.TYPE_SHARED, result, nanosTimeout);
        }
    }

    /**
     * Acquire a shared lock, but don't push the lock into the owned lock stack. Returns this
     * lock if acquired, or null if already owned.
     */
    final _Lock acquireSharedNoPush(_LocalTransaction txn) throws LockFailureException {
        long nanosTimeout = txn.lockTimeout(TimeUnit.NANOSECONDS);

        LockResult result;

        _LockManager.Bucket bucket = mBucket;
        bucket.acquireExclusive();
        try {
            result = tryLockShared(bucket, txn, nanosTimeout);
        } finally {
            bucket.releaseExclusive();
        }

        if (!result.isHeld()) {
            throw txn.failed(_LockManager.TYPE_SHARED, result, nanosTimeout);
        }

        return result == LockResult.ACQUIRED ? this : null;
    }

    @Override
    public final LockResult tryAcquireShared(Transaction txn, long nanosTimeout) {
        return tryAcquireShared((_LocalTransaction) txn, nanosTimeout);
    }

    final LockResult tryAcquireShared(_LocalTransaction txn, long nanosTimeout) {
        LockResult result;

        _LockManager.Bucket bucket = mBucket;
        bucket.acquireExclusive();
        try {
            result = tryLockShared(bucket, txn, nanosTimeout);
        } finally {
            bucket.releaseExclusive();
        }

        if (result == LockResult.ACQUIRED) {
            txn.push(this);
        }

        return result;
    }

    @Override
    public final void acquireExclusive() throws LockFailureException {
        long nanosTimeout = ((_LocalTransaction) mOwner).lockTimeout(TimeUnit.NANOSECONDS);
        LockResult result = tryAcquireExclusive(nanosTimeout);
        if (!result.isHeld()) {
            throw mOwner.failed(_LockManager.TYPE_EXCLUSIVE, result, nanosTimeout);
        }
    }

    @Override
    public final LockResult tryAcquireExclusive(long nanosTimeout) {
        _Locker locker = mOwner;
        LockResult result;

        _LockManager.Bucket bucket = mBucket;
        bucket.acquireExclusive();
        try {
            result = tryLockExclusive(bucket, locker, nanosTimeout);
        } finally {
            bucket.releaseExclusive();
        }

        if (result == LockResult.UPGRADED) {
            locker.push(this);
            result = LockResult.ACQUIRED;
        }

        return result;
    }

    @Override
    protected void doUnlockOwnedUnrestricted(_LockManager.Bucket bucket) {
        // This is a stripped down version of the doUnlockToUpgradable method.

        if (mLockCount == ~0) {
            mLockCount = 0x80000000;
            Latch.Condition queueSX = mQueueSX;
            if (queueSX != null) {
                queueSX.signalTagged(bucket);
            }
        }

        bucket.releaseExclusive();
    }
}
