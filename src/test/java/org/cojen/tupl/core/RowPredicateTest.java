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

import java.util.Collections;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RowPredicateTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(RowPredicateTest.class.getName());
    }

    private CoreDatabase mDb;
    private RowPredicateSet<TestRow> mSet;

    @Before
    public void setup() throws Exception {
        mDb = (CoreDatabase) Database.open(new DatabaseConfig()
                                           .directPageAccess(false)
                                           .lockTimeout(100, TimeUnit.MILLISECONDS));
        mSet = mDb.newRowPredicateSet(1234);
    }

    @After
    public void teardown() throws Exception {
        mDb.close();
        mSet = null;
    }

    @Test
    public void basic() throws Exception {
        basic(0, true);
    }

    private void basic(int offset, boolean count) throws Exception {
        var row1 = new TestRow(1 + offset);
        var row2 = new TestRow(2 + offset);

        var key1 = row1.key();
        var key2 = row2.key();

        var pred1 = new TestPredicate(row1);
        var pred2 = new TestPredicate(row2);

        var txn1 = mDb.newTransaction();
        var txn2 = mDb.newTransaction();

        txn1.attach("txn1_" + offset);
        txn2.attach("txn2_" + offset);

        if (count) {
            assertEquals(0, mSet.countPredicates());
        }

        {
            // Simulate txn1 starting a range scan, and txn2 modifies a non-conflicting row.
            // Nothing is blocked.

            txn1.lockTimeout(2, TimeUnit.SECONDS);
            mSet.addPredicate(txn1, pred1); // range scan action
            txn1.lockTimeout(100, TimeUnit.MILLISECONDS);
            mSet.openAcquire(txn2, row2).close(); // modify action

            if (count) {
                assertEquals(1, mSet.countPredicates());
            }

            txn1.reset();
            txn2.reset();
        }

        if (count) {
            assertEquals(0, mSet.countPredicates());
        }

        {
            // Simulate txn1 starting a range scan, and txn2 modifies a conflicting row.
            // txn2 is blocked.

            txn1.lockTimeout(2, TimeUnit.SECONDS);
            mSet.addPredicate(txn1, pred1); // range scan action
            txn1.lockTimeout(100, TimeUnit.MILLISECONDS);

            if (count) {
                assertEquals(1, mSet.countPredicates());
            }

            try {
                mSet.openAcquire(txn2, row1).close(); // modify action
                fail();
            } catch (LockTimeoutException e) {
                String message = e.getMessage();
                assertTrue(message, message.contains("owner attachment: txn1"));
            }

            txn2.lockTimeout(2, TimeUnit.SECONDS);

            Waiter w = start(() -> mSet.openAcquire(txn2, row1).close()); // modify action

            // Unblock txn2.
            txn1.reset();

            w.await();

            txn2.reset();
        }

        if (count) {
            assertEquals(0, mSet.countPredicates());
        }

        {
            // Simulate txn2 modifying non-conflicting rows, and txn1 starts a range scan.
            // Nothing is blocked.

            // Lock against same index, but a non-conflicting key (modify action).
            var acq = mSet.openAcquire(txn2, row2);
            txn2.lockExclusive(1234, key2);
            acq.close();

            // Lock against a different index, but the same key (modify action).
            txn2.lockExclusive(5678, key1);

            txn1.lockTimeout(2, TimeUnit.SECONDS);
            mSet.addPredicate(txn1, pred1); // range scan action
            txn1.lockTimeout(100, TimeUnit.MILLISECONDS);

            if (count) {
                assertEquals(1, mSet.countPredicates());
            }

            txn1.reset();
            txn2.reset();
        }

        if (count) {
            assertEquals(0, mSet.countPredicates());
        }

        {
            // Simulate txn2 modifying a conflicting row, and txn1 starts a range scan.
            // txn1 is blocked.

            // Lock against same index, but a non-conflicting key (modify action).
            var acq = mSet.openAcquire(txn2, row2);
            txn2.lockExclusive(1234, key2);
            acq.close();

            // Lock against a different index, but the same key (modify action).
            txn2.lockExclusive(5678, key1);

            // Lock against same index and a conflicting key (modify action).
            acq = mSet.openAcquire(txn2, row1);
            txn2.lockExclusive(1234, key1);
            acq.close();

            try {
                mSet.addPredicate(txn1, pred1); // range scan action
                fail();
            } catch (LockTimeoutException e) {
                // expected
            }

            if (count) {
                assertEquals(0, mSet.countPredicates());
            }

            txn1.lockTimeout(2, TimeUnit.SECONDS);

            Waiter w = start(() -> mSet.addPredicate(txn1, pred1)); // range scan action

            if (count) {
                assertEquals(1, mSet.countPredicates());
            }

            // Unblock txn1.
            txn2.reset();

            w.await();

            if (count) {
                assertEquals(1, mSet.countPredicates());
            }

            txn1.reset();
        }

        if (count) {
            assertEquals(0, mSet.countPredicates());
        }
    }

    @Test
    public void fuzz() throws Exception {
        var waiters = new Waiter[5];

        for (int i=0; i<waiters.length; i++) {
            int offset = i * 2;

            waiters[i] = new Waiter(() -> {
                for (int j=0; j<10; j++) {
                    basic(offset, false);
                }
            });
        }

        for (Waiter w : waiters) {
            w.start();
        }

        for (Waiter w : waiters) {
            w.await();
        }
    }

    @Test
    public void deadlock() throws Exception {
        var row1 = new TestRow(1);
        var row2 = new TestRow(2);

        var key1 = row1.key();
        var key2 = row2.key();

        var pred1 = new TestPredicate(row1);
        var pred2 = new TestPredicate(row2);

        {
            // Two transactions deadlocked trying to add a new predicate. Each holds a row lock
            // that the other needs.

            var txn1 = mDb.newTransaction();
            var txn2 = mDb.newTransaction();

            txn1.lockTimeout(2, TimeUnit.SECONDS);
            txn2.lockTimeout(2, TimeUnit.SECONDS);

            txn1.attach("txn1");
            txn2.attach("txn2");

            var acq = mSet.openAcquire(txn1, row1);
            txn1.lockExclusive(1234, key1);
            acq.close();

            acq = mSet.openAcquire(txn2, row2);
            txn2.lockExclusive(1234, key2);
            acq.close();

            Waiter w = start(() -> {
                mSet.addPredicate(txn1, pred2);
            });

            try {
                mSet.addPredicate(txn2, pred1);
                fail();
            } catch (DeadlockException e) {
                assertEquals("txn1", e.ownerAttachment());
            } finally {
                txn2.reset();
            }

            w.await();
            txn1.reset();
        }

        {
            // Two transactions deadlocked waiting on a matched predicate. Each holds a
            // predicate lock that the other needs.

            var txn1 = mDb.newTransaction();
            var txn2 = mDb.newTransaction();

            txn1.lockTimeout(2, TimeUnit.SECONDS);
            txn2.lockTimeout(2, TimeUnit.SECONDS);

            txn1.attach("txn1");
            txn2.attach("txn2");

            mSet.addPredicate(txn1, pred1);
            mSet.addPredicate(txn2, pred2);

            txn1.lockTimeout(2, TimeUnit.SECONDS);
            txn2.lockTimeout(2, TimeUnit.SECONDS);

            LockTimeoutException e1 = null, e2 = null;

            Waiter w = start(() -> {
                mSet.openAcquire(txn1, row2).close();
            });

            try {
                mSet.openAcquire(txn2, row1).close();
                fail();
            } catch (LockTimeoutException e) {
                e1 = e;
            }

            try {
                w.await();
                fail();
            } catch (LockTimeoutException e) {
                e2 = e;
            }

            assertEquals("txn1", e1.ownerAttachment());
            assertEquals("txn2", e2.ownerAttachment());

            assertTrue(e1 instanceof DeadlockException || e2 instanceof DeadlockException);

            txn1.reset();
            txn2.reset();
        }
    }

    @Test
    public void selfOwnership() throws Exception {
        // Test that a transaction can add a predicate for a lock it already owns.

        var row1 = new TestRow(1);
        var key1 = row1.key();
        var pred1 = new TestPredicate(row1);

        var txn1 = mDb.newTransaction();

        var acq = mSet.openAcquire(txn1, row1);
        txn1.lockExclusive(1234, key1);
        acq.close();

        // Shouldn't block.
        mSet.addPredicate(txn1, pred1);

        try {
            var txn2 = mDb.newTransaction();
            mSet.addPredicate(txn2, pred1);
            fail();
        } catch (LockTimeoutException e) {
        }

        Waiter w = start(() -> {
            var txn2 = mDb.newTransaction();
            txn2.lockTimeout(2, TimeUnit.SECONDS);
            mSet.addPredicate(txn2, pred1);
        });

        assertEquals(Thread.State.TIMED_WAITING, w.getState());

        // Unblock the waiter.
        txn1.reset();

        w.await();
    }

    @Test
    public void movingPredicate() throws Exception {
        // Simulate a range scan which updates its predicate as it moves along, allowing other
        // transactions access to rows which it doesn't care about anymore. This technique
        // shouldn't be used for true serializable transactions, but it's fine for scans which
        // release the predicate lock when finished.

        var row1 = new TestRow(1);
        var row2 = new TestRow(2);

        var key1 = row1.key();
        var key2 = row2.key();

        var predSet = Collections.<TestRow>newSetFromMap(new ConcurrentHashMap<>());
        predSet.add(row1);
        predSet.add(row2);

        var pred = new TestPredicate(predSet);

        var txn1 = mDb.newTransaction();
        var txn2 = mDb.newTransaction();

        mSet.addPredicate(txn1, pred);

        try {
            mSet.openAcquire(txn2, row1).close();
            fail();
        } catch (LockTimeoutException e) {
            // Cannot acquire because predicate is at row1.
        }

        // Advance past row1 and remove from the predicate.
        predSet.remove(row1);

        // Can acquire the lock now.
        var acq = mSet.openAcquire(txn2, row1);
        txn2.lockUpgradable(1234, key1);
        acq.close();

        try {
            mSet.openAcquire(txn2, row2).close();
            fail();
        } catch (LockTimeoutException e) {
            // Cannot acquire because predicate is at row2.
        }

        txn1.reset();

        // Can acquire the lock now.
        acq = mSet.openAcquire(txn2, row2);
        txn2.lockExclusive(1234, key2);
        acq.close();

        try {
            mSet.addPredicate(txn1, new TestPredicate(row1));
            fail();
        } catch (LockTimeoutException e) {
            // Cannot add predicate because txn2 owns conflicting row locks.
        }

        txn2.reset();

        mSet.addPredicate(txn1, new TestPredicate(row1));
        txn1.reset();
    }

    @Test
    public void nonConflict() throws Exception {
        // A lock held shared doesn't prevent a predicate from being added. Only upgradable and
        // exclusive locks can conflict with the predicate.

        var row1 = new TestRow(1);
        var key1 = row1.key();
        var pred1 = new TestPredicate(row1);

        var txn1 = mDb.newTransaction();
        var txn2 = mDb.newTransaction();

        var acq = mSet.openAcquire(txn1, row1);
        txn1.lockShared(1234, key1);
        acq.close();

        // Shouldn't block.
        mSet.addPredicate(txn2, pred1);
    }

    @Test
    public void sharedPredicates() throws Exception {
        // Two predicates which match the same rows shouldn't conflict with each other.

        var row1 = new TestRow(1);
        var key1 = row1.key();

        var pred1 = new TestPredicate(row1);
        var pred2 = new TestPredicate(row1);

        {
            var txn1 = mDb.newTransaction();
            var txn2 = mDb.newTransaction();

            mSet.addPredicate(txn1, pred1);
            mSet.addPredicate(txn2, pred2);

            var txn3 = mDb.newTransaction();

            try {
                mSet.openAcquire(txn3, row1).close();
                fail();
            } catch (LockTimeoutException e) {
            }

            txn2.reset();

            try {
                mSet.openAcquire(txn3, row1).close();
                fail();
            } catch (LockTimeoutException e) {
            }

            txn3.lockTimeout(2, TimeUnit.SECONDS);

            Waiter w = start(() -> {
                mSet.openAcquire(txn3, row1).close();
                txn3.reset();
            });

            txn1.reset();

            w.await();
        }

        // Again, but the predicates are added to one transaction.
        {
            var txn1 = mDb.newTransaction();

            mSet.addPredicate(txn1, pred1);
            mSet.addPredicate(txn1, pred2);

            var txn3 = mDb.newTransaction();

            try {
                mSet.openAcquire(txn3, row1).close();
                fail();
            } catch (LockTimeoutException e) {
            }

            Waiter w = start(() -> {
                mSet.openAcquire(txn3, row1).close();
                txn3.reset();
            });

            txn1.reset();

            w.await();
        }
    }

    @Test
    public void stalledAcquire() throws Exception {
        // Test an openAcquire call that doesn't immediately finish.

        var row1 = new TestRow(1);
        var key1 = row1.key();
        var pred1 = new TestPredicate(row1);

        var txn1 = mDb.newTransaction();
        var acq = mSet.openAcquire(txn1, row1);

        var txn2 = mDb.newTransaction();
        try {
            mSet.addPredicate(txn2, pred1);
            fail();
        } catch (LockTimeoutException e) {
            // The openAcquire isn't finished.
        }

        // Finish the acquire.
        txn1.lockUpgradable(1234, key1);
        acq.close();

        try {
            mSet.addPredicate(txn2, pred1);
            fail();
        } catch (LockTimeoutException e) {
            // A required row lock is held.
        }

        txn1.reset();

        mSet.addPredicate(txn2, pred1);
        txn2.lockUpgradable(1234, key1);
        txn2.reset();
    }

    static interface Task {
        void run() throws Exception;
    }

    static class Waiter extends Thread {
        final Task mTask;

        volatile Throwable mFailed;

        Waiter(Task task) {
            mTask = task;
        }

        void await() throws Exception {
            join();
            Throwable failed = mFailed;
            if (failed != null) {
                Utils.addLocalTrace(failed);
                Utils.rethrow(failed);
            }
        }

        @Override
        public void run() {
            try {
                mTask.run();
            } catch (Throwable e) {
                mFailed = e;
            }
        }
    }

    static Waiter start(Task task) {
        return TestUtils.startAndWaitUntilBlocked(new Waiter(task));
    }

    static record TestRow(int value) {
        byte[] key() {
            return new byte[] {(byte) value};
        }
    }

    static class TestPredicate implements RowPredicate<TestRow> {
        private final Set<TestRow> mMatches;

        TestPredicate(TestRow match) {
            this(Collections.singleton(match));
        }

        TestPredicate(Set<TestRow> matches) {
            mMatches = matches;
        }

        @Override
        public boolean test(TestRow row) {
            return mMatches.contains(row);
        }

        @Override
        public boolean test(TestRow row, byte[] value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean test(byte[] key, byte[] value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean test(byte[] key) {
            for (TestRow match : mMatches) {
                if (key.length == 1 && key[0] == match.value) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "TestPredicate: " + mMatches;
        }
    }
}
