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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.io.IOException;

import org.cojen.tupl.CommitCallback;
import org.cojen.tupl.EventListener;
import org.cojen.tupl.EventType;

/**
 * References an UndoLog and a set of exclusive locks from a transaction ready to be committed.
 *
 * @author Brian S O'Neill
 */
/*P*/
final class PendingTxn extends Locker implements Runnable {
    final TransactionContext mContext;
    final long mTxnId;
    final UndoLog mUndoLog;
    final int mHasState;

    private Object mAttachment;

    private volatile long mCommitPos;
    private static final VarHandle cCommitPosHandle;

    volatile PendingTxn mNext;
    private static final VarHandle cNextHandle;

    static {
        try {
            cCommitPosHandle =
                MethodHandles.lookup().findVarHandle
                (PendingTxn.class, "mCommitPos", long.class);

            cNextHandle =
                MethodHandles.lookup().findVarHandle
                (PendingTxn.class, "mNext", PendingTxn.class);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
    }

    PendingTxn(LocalTransaction from) {
        super(from.mManager, from.mHash);

        mContext = from.mContext;
        mTxnId = from.mTxnId;
        mUndoLog = from.mUndoLog;
        mHasState = from.mHasState;
        Object att = from.attachment();
        mAttachment = att;

        from.transferExclusive(this);

        from.mUndoLog = null;
        from.mHasState = 0;
        from.mTxnId = 0;

        if (att instanceof CommitCallback) {
            try {
                ((CommitCallback) att).pending(mTxnId);
            } catch (Throwable e) {
                Utils.uncaught(e);
            }
        }
    }

    long commitPos() {
        return (long) cCommitPosHandle.getOpaque(this);
    }

    void commitPos(long pos) {
        cCommitPosHandle.setOpaque(this, pos);
    }

    /**
     * @return null if locked, else the next pending txn
     */
    PendingTxn tryLockLast() {
        return (PendingTxn) cNextHandle.compareAndExchange(this, null, this);
    }

    void unlockLast() {
        mNext = null;
    }

    void setNext(PendingTxn next) {
        while (!cNextHandle.compareAndSet(this, null, next)) {
            Thread.onSpinWait();
        }
    }

    @Override
    public final LocalDatabase getDatabase() {
        UndoLog undo = mUndoLog;
        return undo == null ? super.getDatabase() : undo.getDatabase();
    }

    @Override
    public Object attachment() {
        return mAttachment;
    }

    @Override
    public void run() {
        try {
            long commitPos = commitPos();
            Object status = null;
            if (commitPos < 0) {
                doRollback();
                status = "Replication failure"; // lame, but it's at least something
            } else {
                scopeUnlockAll();
                UndoLog undo = mUndoLog;
                if (undo != null) {
                    undo.truncate();
                    mContext.unregister(undo);
                }
                if ((mHasState & LocalTransaction.HAS_TRASH) != 0) {
                    FragmentedTrash.emptyTrash(getDatabase().fragmentedTrash(), mTxnId);
                }
            }

            finished(status);
        } catch (Throwable e) {
            LocalDatabase db = getDatabase();
            if (db != null && !db.isClosed()) {
                EventListener listener = db.eventListener();
                if (listener != null) {
                    listener.notify(EventType.REPLICATION_PANIC,
                                    "Unexpected transaction exception: %1$s", e);
                } else {
                    Utils.uncaught(e);
                }
                finished(e);
            }
        }
    }

    RuntimeException rollback(Throwable cause) {
        try {
            doRollback();
        } catch (Throwable e) {
            Utils.suppress(cause, e);
        }
        throw Utils.rethrow(cause);
    }

    private void doRollback() throws IOException {
        UndoLog undo = mUndoLog;
        if (undo != null) {
            mContext.uncommitted(mTxnId);
            undo.rollback();
        }
        scopeUnlockAll();
        if (undo != null) {
            mContext.unregister(undo);
        }
    }

    private void finished(Object status) {
        if (mAttachment instanceof CommitCallback) {
            try {
                ((CommitCallback) mAttachment).finished(mTxnId, status);
            } catch (Throwable e) {
                Utils.uncaught(e);
            }
        }
    }
}
