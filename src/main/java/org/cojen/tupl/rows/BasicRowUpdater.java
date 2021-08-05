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

package org.cojen.tupl.rows;

import java.io.IOException;

import java.util.Arrays;
import java.util.Objects;
import java.util.TreeSet;

import org.cojen.tupl.Cursor;
import org.cojen.tupl.LockResult;
import org.cojen.tupl.RowUpdater;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.UniqueConstraintException;
import org.cojen.tupl.UnpositionedCursorException;
import org.cojen.tupl.View;

import org.cojen.tupl.views.ViewUtils;

import org.cojen.tupl.core.CommitLock;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class BasicRowUpdater<R> extends BasicRowScanner<R> implements RowUpdater<R> {
    final View mView;
    final AbstractTable<R> mTriggerTable;

    private TreeSet<byte[]> mKeysToSkip;

    /**
     * @param cursor linked transaction must not be null
     * @param table only should be provided if table supports triggers
     */
    BasicRowUpdater(View view, Cursor cursor, RowDecoderEncoder<R> decoder,
                    AbstractTable<R> table)
    {
        super(cursor, decoder);
        mView = view;
        mTriggerTable = table;
    }

    @Override
    public final R update() throws IOException {
        return doUpdateAndStep(null);
    }

    @Override
    public final R update(R row) throws IOException {
        Objects.requireNonNull(row);
        return doUpdateAndStep(row);
    }

    private R doUpdateAndStep(R row) throws IOException {
        try {
            R current = mRow;
            if (current == null) {
                throw new IllegalStateException("No current row");
            }
            doUpdate(current);
        } catch (UnpositionedCursorException e) {
            finished();
            throw new IllegalStateException("No current row");
        } catch (UniqueConstraintException e) {
            throw e;
        } catch (Throwable e) {
            throw RowUtils.fail(this, e);
        }
        unlocked(); // prevent subclass from attempting to release the lock
        return doStep(row);
    }

    @Override
    public final R delete() throws IOException {
        return doDeleteAndStep(null);
    }

    @Override
    public final R delete(R row) throws IOException {
        Objects.requireNonNull(row);
        return doDeleteAndStep(row);
    }

    private R doDeleteAndStep(R row) throws IOException {
        doDelete: try {
            if (mTriggerTable == null) {
                doDelete();
            } else while (true) {
                Trigger<R> trigger = mTriggerTable.trigger();
                CommitLock.Shared shared = trigger.acquireShared();
                try {
                    int mode = trigger.mode();
                    if (mode == Trigger.SKIP) {
                        doDelete();
                        break doDelete;
                    }
                    if (mode != Trigger.DISABLED) {
                        doDelete(trigger, mRow);
                        break doDelete;
                    }
                } finally {
                    shared.release();
                }
            }
        } catch (UnpositionedCursorException e) {
            finished();
            throw new IllegalStateException("No current row");
        } catch (Throwable e) {
            throw RowUtils.fail(this, e);
        }

        unlocked(); // prevent subclass from attempting to release the lock

        return doStep(row);
    }

    @Override
    protected LockResult toFirst(Cursor c) throws IOException {
        LockResult result = c.first();
        c.register();
        return result;
    }

    protected final void doUpdate(R row) throws IOException {
        byte[] key, value;
        {
            RowDecoderEncoder<R> encoder = mDecoder;
            key = encoder.encodeKey(row);
            value = encoder.encodeValue(row);
        }

        Cursor c = mCursor;

        int cmp;
        if (key == null || (cmp = c.compareKeyTo(key)) == 0) {
            // Key didn't change.

            if (mTriggerTable == null) {
                storeValue(c, value);
                return;
            }

            while (true) {
                Trigger<R> trigger = mTriggerTable.trigger();
                CommitLock.Shared shared = trigger.acquireShared();
                try {
                    int mode = trigger.mode();
                    if (mode == Trigger.SKIP) {
                        storeValue(c, value);
                        return;
                    }
                    if (mode != Trigger.DISABLED) {
                        storeValue(trigger, mRow, c, value);
                        return;
                    }
                } finally {
                    shared.release();
                }
            }
        }

        // This point is reached when the key changed, and so the update is out of sequence. A
        // new value is inserted (if permitted), and the current one is deleted. If the new key
        // is higher, it's added to a remembered set and not observed again by this updater.

        if (cmp < 0) {
            if (mKeysToSkip == null) {
                mKeysToSkip = new TreeSet<>(Arrays::compareUnsigned);
            }
            // FIXME: For AutoCommitRowUpdater, consider limiting the size of the set and
            // use a temporary index. All other updaters maintain locks, and so the key
            // objects cannot be immediately freed anyhow.
            if (!mKeysToSkip.add(key)) {
                // Won't be removed from the set in case of UniqueConstraintException.
                cmp = 0;
            }
        }

        Transaction txn = ViewUtils.enterScope(mView, c.link());
        doUpdate: try {
            if (!mView.insert(txn, key, value)) {
                if (cmp < 0) {
                    mKeysToSkip.remove(key);
                }
                throw new UniqueConstraintException();
            }

            if (mTriggerTable == null) {
                c.commit(null);
            } else while (true) {
                Trigger<R> trigger = mTriggerTable.trigger();
                CommitLock.Shared shared = trigger.acquireShared();
                try {
                    int mode = trigger.mode();
                    if (mode == Trigger.SKIP) {
                        c.commit(null);
                        break doUpdate;
                    }
                    if (mode != Trigger.DISABLED) {
                        c.delete();
                        trigger.store(txn, mRow, c.key(), null, value);
                        txn.commit();
                        break doUpdate;
                    }
                } finally {
                    shared.release();
                }
            }
        } finally {
            txn.exit();
        }

        postStoreKeyValue(txn);
    }

    /**
     * Called when the key didn't change.
     */
    protected void storeValue(Cursor c, byte[] value) throws IOException {
        c.store(value);
    }

    /**
     * Called when the key didn't change.
     */
    protected void storeValue(Trigger<R> trigger, R row, Cursor c, byte[] value)
        throws IOException
    {
        Transaction txn = ViewUtils.enterScope(mView, c.link());
        try {
            byte[] oldValue = c.value();
            c.store(value);
            trigger.store(txn, row, c.key(), oldValue, value);
            txn.commit();
        } finally {
            txn.exit();
        }
    }

    /**
     * Called after the key and value changed and have been updated.
     */
    protected void postStoreKeyValue(Transaction txn) throws IOException {
    }

    protected void doDelete() throws IOException {
        mCursor.delete();
    }

    protected void doDelete(Trigger<R> trigger, R row) throws IOException {
        Cursor c = mCursor;
        Transaction txn = ViewUtils.enterScope(mView, c.link());
        try {
            byte[] oldValue = c.value();
            c.delete();
            trigger.store(txn, row, c.key(), oldValue, null);
            txn.commit();
        } finally {
            txn.exit();
        }
    }

    @Override
    protected R decodeRow(byte[] key, Cursor c, R row) throws IOException {
        if (mKeysToSkip != null && mKeysToSkip.remove(key)) {
            return null;
        }
        return super.decodeRow(key, c, row);
    }
}
