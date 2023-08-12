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

import java.io.Flushable;
import java.io.IOException;

import java.util.concurrent.TimeUnit;

import org.cojen.tupl.core.LocalTransaction;

/**
 * Defines a logical unit of work. Transaction instances can only be safely
 * used by one thread at a time, and they must be {@link #reset reset} when no
 * longer needed. Instances can be exchanged by threads, as long as a
 * happens-before relationship is established. Without proper exclusion,
 * multiple threads interacting with a Transaction instance may cause database
 * corruption.
 *
 * <p>Transactions also contain various methods for directly controlling locks,
 * although their use is not required. Methods which operate upon transactions
 * acquire and release locks automatically. Direct control over locks is
 * provided for advanced use cases. One such use is record filtering:
 *
 * {@snippet lang="java" :
 * Transaction txn = ...
 * Cursor c = index.newCursor(txn);
 * for (LockResult result = c.first(); c.key() != null; result = c.next()) {
 *     if (shouldDiscard(c.value()) && result == LockResult.ACQUIRED) {
 *         // Unlock record which doesn't belong in the transaction.
 *         txn.unlock();
 *         continue;
 *     }
 *     ...
 * }
 * }
 *
 * <p>Note: Transaction instances are never fully closed after they are reset
 * or have fully exited. Any operation which acts upon a reset transaction can
 * resurrect it.
 *
 * @author Brian S O'Neill
 * @see Database#newTransaction Database.newTransaction
 */
public interface Transaction extends Flushable {
    /**
     * Transaction instance which isn't a transaction at all. It always operates in an
     * {@linkplain LockMode#UNSAFE unsafe} lock mode and a {@linkplain DurabilityMode#NO_REDO
     * no-redo} durability mode. For safe auto-commit transactions, pass null for the
     * transaction argument.
     */
    public static final Transaction BOGUS = LocalTransaction.BOGUS;

    /**
     * Sets the lock mode for the current scope. Transactions begin in {@link
     * LockMode#UPGRADABLE_READ UPGRADABLE_READ} mode, as do newly entered scopes. Exiting a
     * scope reverts the lock mode.
     *
     * @param mode new lock mode
     * @throws IllegalArgumentException if mode is null
     */
    void lockMode(LockMode mode);

    /**
     * Returns the current lock mode.
     */
    LockMode lockMode();

    /**
     * Sets the lock timeout for the current scope. A negative timeout is
     * infinite.
     *
     * @param unit required unit if timeout is more than zero
     */
    void lockTimeout(long timeout, TimeUnit unit);

    /**
     * Returns the current lock timeout, in the given unit.
     */
    long lockTimeout(TimeUnit unit);

    /**
     * Sets the durability mode for the entire transaction, not just the current scope. The
     * durability mode primarily affects commit behavior at the top-level scope, but it can also
     * be used to switch redo logging behavior.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param mode new durability mode
     * @throws IllegalArgumentException if mode is null
     */
    void durabilityMode(DurabilityMode mode);

    /**
     * Returns the durability mode of this transaction.
     */
    DurabilityMode durabilityMode();

    /**
     * Checks the validity of the transaction.
     *
     * @throws InvalidTransactionException if transaction is broken or was invalidated by
     * an earlier exception
     * @throws IllegalStateException if transaction is bogus
     */
    void check() throws DatabaseException;

    boolean isBogus();

    /**
     * Commits all modifications made within the current transaction scope. The
     * current scope is still valid after this method is called, unless an
     * exception is thrown. Call exit or reset to fully release transaction
     * resources.
     *
     * @see CommitCallback
     */
    void commit() throws IOException;

    /**
     * Commits and exits all transaction scopes.
     *
     * @see CommitCallback
     */
    void commitAll() throws IOException;

    /**
     * Enters a nested transaction scope, which begins with the {@link LockMode#UPGRADABLE_READ
     * UPGRADABLE_READ} lock mode.
     *
     * @see #lockMode(LockMode) lockMode
     */
    void enter() throws IOException;

    /**
     * Exits the current transaction scope, rolling back all uncommitted
     * modifications made within. The transaction is still valid after this
     * method is called, unless an exception is thrown.
     */
    void exit() throws IOException;

    /**
     * Exits all transaction scopes, rolling back all uncommitted modifications. Equivalent to:
     *
     * {@snippet lang="java" :
     * while (txn.isNested()) {
     *     txn.exit();
     * }
     * txn.exit();
     * }
     */
    void reset() throws IOException;

    /**
     * Reset the transaction due to the given cause. This provides an opportunity to prevent
     * this transaction from being used any further. No exception is thrown when invoking this
     * method.
     *
     * @param cause pass a cause to reset and disable the transaction; pass null to simply
     * reset the transaction and ignore any exception when doing so
     */
    void reset(Throwable cause);

    /**
     * Attempts to acquire a shared lock for the given key, denying exclusive locks. If return
     * value is {@linkplain LockResult#isAlreadyOwned owned}, transaction already owns a strong
     * enough lock, and no extra unlock should be performed.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link
     * LockResult#OWNED_SHARED OWNED_SHARED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws IllegalStateException if too many shared locks
     * @throws LockFailureException if interrupted or timed out
     * @throws DeadlockException if deadlock was detected after waiting the full timeout
     */
    LockResult lockShared(long indexId, byte[] key) throws LockFailureException;

    /**
     * Attempts to acquire an upgradable lock for the given key, denying exclusive and
     * additional upgradable locks. If return value is {@linkplain LockResult#isAlreadyOwned
     * owned}, transaction already owns a strong enough lock, and no extra unlock should be
     * performed.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws LockFailureException if interrupted, timed out, or illegal upgrade
     * @throws DeadlockException if deadlock was detected after waiting the full timeout
     */
    LockResult lockUpgradable(long indexId, byte[] key) throws LockFailureException;

    /**
     * Attempts to acquire an exclusive lock for the given key, denying any additional
     * locks. If return value is {@linkplain LockResult#isAlreadyOwned owned}, transaction
     * already owns exclusive lock, and no extra unlock should be performed.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link LockResult#UPGRADED
     * UPGRADED}, or {@link LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws LockFailureException if interrupted, timed out, or illegal upgrade
     * @throws DeadlockException if deadlock was detected after waiting the full timeout
     */
    LockResult lockExclusive(long indexId, byte[] key) throws LockFailureException;

    /**
     * Returns true if the current transaction scope is nested.
     */
    boolean isNested();

    /**
     * Counts the current transaction scope nesting level. Count is zero if non-nested.
     */
    int nestingLevel();

    /**
     * Attempts to acquire a shared lock for the given key, denying exclusive locks. If return
     * value is {@linkplain LockResult#isAlreadyOwned owned}, transaction already owns a strong
     * enough lock, and no extra unlock should be performed.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#INTERRUPTED INTERRUPTED}, {@link
     * LockResult#TIMED_OUT_LOCK TIMED_OUT_LOCK}, {@link LockResult#ACQUIRED
     * ACQUIRED}, {@link LockResult#OWNED_SHARED OWNED_SHARED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws IllegalStateException if too many shared locks
     * @throws DeadlockException if deadlock was detected after waiting the full timeout,
     * unless the timeout is zero
     */
    LockResult tryLockShared(long indexId, byte[] key, long nanosTimeout)
        throws DeadlockException, LockFailureException;

    /**
     * Attempts to acquire a shared lock for the given key, denying exclusive locks. If return
     * value is {@linkplain LockResult#isAlreadyOwned owned}, transaction already owns a strong
     * enough lock, and no extra unlock should be performed.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link
     * LockResult#OWNED_SHARED OWNED_SHARED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws IllegalStateException if too many shared locks
     * @throws LockFailureException if interrupted or timed out
     * @throws DeadlockException if deadlock was detected after waiting the full timeout
     */
    LockResult lockShared(long indexId, byte[] key, long nanosTimeout)
        throws LockFailureException;

    /**
     * Attempts to acquire an upgradable lock for the given key, denying exclusive and
     * additional upgradable locks. If return value is {@linkplain LockResult#isAlreadyOwned
     * owned}, transaction already owns a strong enough lock, and no extra unlock should be
     * performed. If {@link LockResult#ILLEGAL ILLEGAL} is returned, transaction holds a shared
     * lock, which cannot be upgraded.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#ILLEGAL ILLEGAL}, {@link
     * LockResult#INTERRUPTED INTERRUPTED}, {@link LockResult#TIMED_OUT_LOCK
     * TIMED_OUT_LOCK}, {@link LockResult#ACQUIRED ACQUIRED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws DeadlockException if deadlock was detected after waiting the full timeout,
     * unless the timeout is zero
     */
    LockResult tryLockUpgradable(long indexId, byte[] key, long nanosTimeout)
        throws DeadlockException, LockFailureException;

    /**
     * Attempts to acquire an upgradable lock for the given key, denying exclusive and
     * additional upgradable locks. If return value is {@linkplain LockResult#isAlreadyOwned
     * owned}, transaction already owns a strong enough lock, and no extra unlock should be
     * performed.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws LockFailureException if interrupted, timed out, or illegal upgrade
     * @throws DeadlockException if deadlock was detected after waiting the full timeout
     */
    LockResult lockUpgradable(long indexId, byte[] key, long nanosTimeout)
        throws LockFailureException;

    /**
     * Attempts to acquire an exclusive lock for the given key, denying any
     * additional locks. If return value is {@linkplain LockResult#isAlreadyOwned
     * owned}, transaction already owns exclusive lock, and no extra unlock
     * should be performed. If {@link LockResult#ILLEGAL ILLEGAL} is returned,
     * transaction holds a shared lock, which cannot be upgraded.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#ILLEGAL ILLEGAL}, {@link
     * LockResult#INTERRUPTED INTERRUPTED}, {@link LockResult#TIMED_OUT_LOCK
     * TIMED_OUT_LOCK}, {@link LockResult#ACQUIRED ACQUIRED}, {@link
     * LockResult#UPGRADED UPGRADED}, or {@link LockResult#OWNED_EXCLUSIVE
     * OWNED_EXCLUSIVE}
     * @throws DeadlockException if deadlock was detected after waiting the full timeout,
     * unless the timeout is zero
     */
    LockResult tryLockExclusive(long indexId, byte[] key, long nanosTimeout)
        throws DeadlockException, LockFailureException;

    /**
     * Attempts to acquire an exclusive lock for the given key, denying any additional
     * locks. If return value is {@linkplain LockResult#isAlreadyOwned owned}, transaction
     * already owns exclusive lock, and no extra unlock should be performed.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @param key non-null key to lock; instance is not cloned and so it must not be modified
     * after calling this method
     * @param nanosTimeout maximum time to wait for lock; negative timeout is infinite
     * @return {@link LockResult#ACQUIRED ACQUIRED}, {@link LockResult#UPGRADED
     * UPGRADED}, or {@link LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     * @throws LockFailureException if interrupted, timed out, or illegal upgrade
     * @throws DeadlockException if deadlock was detected after waiting the full timeout
     */
    LockResult lockExclusive(long indexId, byte[] key, long nanosTimeout)
        throws LockFailureException;

    /**
     * Checks the lock ownership for the given key.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @return {@link LockResult#UNOWNED UNOWNED}, {@link
     * LockResult#OWNED_SHARED OWNED_SHARED}, {@link
     * LockResult#OWNED_UPGRADABLE OWNED_UPGRADABLE}, or {@link
     * LockResult#OWNED_EXCLUSIVE OWNED_EXCLUSIVE}
     */
    LockResult lockCheck(long indexId, byte[] key);

    /**
     * Returns the index id of the last lock acquired, within the current scope.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @return locked index id, 0 if no locks are held
     */
    long lastLockedIndex();

    /**
     * Returns the key of the last lock acquired, within the current scope.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @return locked key (not cloned), or null if no locks are held
     */
    byte[] lastLockedKey();

    /**
     * Fully releases the last lock or group acquired, within the current scope. If the last
     * lock operation was an upgrade, for a lock not immediately acquired, unlock is not
     * allowed. Instead, an IllegalStateException is thrown.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @throws IllegalStateException if no locks are held, or if unlocking an exclusive lock, or if
     * crossing a scope boundary, or if unlocking a non-immediate upgrade
     */
    void unlock();

    /**
     * Releases the last lock or group acquired, within the current scope, retaining a shared
     * lock. If the last lock operation was an upgrade, for a lock not immediately acquired,
     * unlock is not allowed. Instead, an IllegalStateException is thrown.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @throws IllegalStateException if no locks are held, or if unlocking an exclusive lock, or if
     * crossing a scope boundary, or if too many shared locks, or if unlocking a non-immediate
     * upgrade
     */
    void unlockToShared();

    /**
     * Combines the last lock acquired or upgraded into a group which can be unlocked together.
     *
     * <p><i>Note: This method is intended for advanced use cases.</i>
     *
     * @throws IllegalStateException if combining an acquire with an upgrade
     */
    void unlockCombine();

    /**
     * Returns the currently assigned identifier for this transaction, which is zero when the
     * transaction cannot replicate. After the transaction has finished, a new identifier is
     * generated when requested again.
     *
     * @return a positive transaction identifier, or zero if transaction cannot replicate
     */
    default long id() {
        return 0;
    }

    /**
     * Attach an arbitrary object to this transaction instance, for tracking it. Attachments
     * can become visible to other threads as a result of a {@link LockTimeoutException}. A
     * happens-before relationship is guaranteed only if the attachment is set before the
     * associated lock is acquired. Also, the LockTimeoutException constructor calls toString
     * on the attachment, and includes it in the message. A non-default toString implementation
     * must consider thread-safety.
     *
     * @param obj the object to be attached; may be null
     * @throws UnsupportedOperationException if completely unsupported by the transaction
     * implementation
     */
    default void attach(Object obj) {
        // Throw an exception for compatibility. All known implementations support the feature.
        throw new UnsupportedOperationException();
    }

    /**
     * Attach a commit callback to this transaction instance, in place of an ordinary
     * attachment. It behaves like an ordinary attachment, but it also receives special
     * attention by pending transactions.
     *
     * @throws UnsupportedOperationException if completely unsupported by the transaction
     * implementation
     */
    default void attach(CommitCallback callback) {
        attach((Object) callback);
    }

    /**
     * Returns any attachment which was set earlier.
     *
     * @return the current attachment, or null if none
     */
    default Object attachment() {
        return null;
    }

    /**
     * Manually flush all buffered changes made by this transaction to the shared redo
     * log. Flushing is performed automatically when the buffer fills up, or when the
     * transaction is committed. Flushing can be used in conjunction with replication, to
     * ensure that locks and changes are visible to replicas before the transaction commits.
     */
    @Override
    void flush() throws IOException;
}
