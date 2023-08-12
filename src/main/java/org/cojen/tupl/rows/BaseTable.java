/*
 *  Copyright 2021 Cojen.org
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

package org.cojen.tupl.rows;

import java.io.DataOutput;
import java.io.IOException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.lang.ref.WeakReference;

import java.util.Map;
import java.util.Objects;

import org.cojen.tupl.ClosedIndexException;
import org.cojen.tupl.DatabaseException;
import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.Entry;
import org.cojen.tupl.Index;
import org.cojen.tupl.LockFailureException;
import org.cojen.tupl.LockMode;
import org.cojen.tupl.Scanner;
import org.cojen.tupl.Updater;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;

import org.cojen.tupl.core.RowPredicate;
import org.cojen.tupl.core.RowPredicateLock;

import org.cojen.tupl.diag.EventListener;
import org.cojen.tupl.diag.EventType;
import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.rows.filter.ComplexFilterException;
import org.cojen.tupl.rows.filter.FalseFilter;
import org.cojen.tupl.rows.filter.Parser;
import org.cojen.tupl.rows.filter.Query;
import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.TrueFilter;

import org.cojen.tupl.io.Utils;

import org.cojen.tupl.remote.RemoteTableProxy;

import org.cojen.tupl.views.ViewUtils;

import static java.util.Spliterator.*;

/**
 * Base class for all generated table classes.
 *
 * @author Brian S O'Neill
 */
public abstract class BaseTable<R> implements Table<R>, ScanControllerFactory<R> {
    final TableManager<R> mTableManager;

    protected final Index mSource;

    // MultiCache types.
    private static final int PLAIN = 0b00, DOUBLE_CHECK = 0b01,
        FOR_UPDATE = 0b10, FOR_UPDATE_DOUBLE_CHECK = FOR_UPDATE | DOUBLE_CHECK;

    private final MultiCache<String, ScanControllerFactory<R>, Query> mFilterFactoryCache;
    private final MultiCache<String, QueryLauncher<R>, IndexSelector> mQueryLauncherCache;

    private Trigger<R> mTrigger;
    private static final VarHandle cTriggerHandle;

    protected final RowPredicateLock<R> mIndexLock;

    private WeakCache<Object, MethodHandle, byte[]> mDecodePartialCache;
    private static final VarHandle cDecodePartialCacheHandle;

    private WeakCache<Object, MethodHandle, byte[]> mWriteRowCache;
    private static final VarHandle cWriteRowCacheHandle;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            cTriggerHandle = lookup.findVarHandle
                (BaseTable.class, "mTrigger", Trigger.class);
            cDecodePartialCacheHandle = lookup.findVarHandle
                (BaseTable.class, "mDecodePartialCache", WeakCache.class);
            cWriteRowCacheHandle = lookup.findVarHandle
                (BaseTable.class, "mWriteRowCache", WeakCache.class);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
    }

    protected BaseTable(TableManager<R> manager, Index source, RowPredicateLock<R> indexLock) {
        mTableManager = Objects.requireNonNull(manager);
        mSource = Objects.requireNonNull(source);
        mIndexLock = Objects.requireNonNull(indexLock);

        {
            int[] typeMap = {PLAIN, DOUBLE_CHECK};
            if (joinedPrimaryTableClass() == null) {
                // Won't need to double check.
                typeMap[DOUBLE_CHECK] = PLAIN;
            }
            mFilterFactoryCache = MultiCache.newSoftCache(typeMap, this::newFilteredFactory);
        }

        if (supportsSecondaries()) {
            int[] typeMap = {PLAIN, DOUBLE_CHECK, FOR_UPDATE, FOR_UPDATE_DOUBLE_CHECK};
            if (joinedPrimaryTableClass() == null) {
                // Won't need to double check.
                typeMap[DOUBLE_CHECK] = PLAIN;
                typeMap[FOR_UPDATE_DOUBLE_CHECK] = FOR_UPDATE;
            }
            mQueryLauncherCache = MultiCache.newSoftCache(typeMap, (type, queryStr, selector) -> {
                try {
                    return newQueryLauncher(type, queryStr, selector);
                } catch (IOException e) {
                    throw RowUtils.rethrow(e);
                }
            });

            var trigger = new Trigger<R>();
            trigger.mMode = Trigger.SKIP;
            cTriggerHandle.setRelease(this, trigger);
        } else {
            mQueryLauncherCache = null;
        }
    }

    public final TableManager<R> tableManager() {
        return mTableManager;
    }

    @Override
    public final Scanner<R> newScanner(Transaction txn) throws IOException {
        return newScannerWith(txn, (R) null);
    }

    @Override
    public final Scanner<R> newScannerWith(Transaction txn, R row) throws IOException {
        return newScannerWith(txn, row, unfiltered());
    }

    @Override
    public final Scanner<R> newScanner(Transaction txn, String queryStr, Object... args)
        throws IOException
    {
        return newScannerWith(txn, (R) null, queryStr, args);
    }

    @Override
    public Scanner<R> newScannerWith(Transaction txn, R row, String queryStr, Object... args)
        throws IOException
    {
        QueryLauncher<R> launcher = scannerQueryLauncher(txn, queryStr);

        while (true) {
            try {
                return launcher.newScannerWith(txn, row, args);
            } catch (Throwable e) {
                launcher = newScannerRetry(txn, queryStr, launcher, e);
            }
        }
    }

    /**
     * To be called when attempting to launch a new scanner and an exception is thrown. An
     * index might have been dropped, so check and retry. Returns a new QueryLauncher to use or
     * else throws the original exception.
     */
    private QueryLauncher<R> newScannerRetry(Transaction txn, String queryStr,
                                             QueryLauncher<R> launcher, Throwable cause)
    {
        // A ClosedIndexException could have come from the dropped index directly, and a
        // LockFailureException could be caused while waiting for the index lock. Other
        // exceptions aren't expected so don't bother trying to obtain a new launcher.
        if (cause instanceof ClosedIndexException || cause instanceof LockFailureException) {
            QueryLauncher<R> newLauncher;
            try {
                newLauncher = scannerQueryLauncher(txn, queryStr);
                if (newLauncher != launcher) {
                    // Only return the launcher if it changed.
                    return newLauncher;
                }
            } catch (Throwable e) {
                cause.addSuppressed(e);
            }
        }

        throw Utils.rethrow(cause);
    }

    final Scanner<R> newScannerWith(Transaction txn, R row, ScanController<R> controller)
        throws IOException
    {
        final BasicScanner<R> scanner;
        RowPredicateLock.Closer closer = null;

        newScanner: {
            if (txn == null) {
                // A null transaction behaves like a read committed transaction (as usual), but
                // it doesn't acquire predicate locks. This makes it weaker than a transaction
                // which is explicitly read committed.

                if (joinedPrimaryTableClass() != null) {
                    // Need to guard against this secondary index from being concurrently
                    // dropped. This is like adding a predicate lock which matches nothing.
                    txn = mSource.newTransaction(null);
                    closer = mIndexLock.addGuard(txn);

                    if (controller.isJoined()) {
                        // Need to retain row locks against the secondary until after the primary
                        // row has been loaded.
                        txn.lockMode(LockMode.REPEATABLE_READ);
                        scanner = new AutoUnlockScanner<>(this, controller);
                    } else {
                        txn.lockMode(LockMode.READ_COMMITTED);
                        scanner = new TxnResetScanner<>(this, controller);
                    }

                    break newScanner;
                }
            } else if (!txn.lockMode().noReadLock) {
                // This case is reached when a transaction was provided which is read committed
                // or higher. Adding a predicate lock prevents new rows from being inserted
                // into the scan range for the duration of the transaction scope. If the lock
                // mode is repeatable read, then rows which have been read cannot be deleted,
                // effectively making the transaction serializable.
                closer = mIndexLock.addPredicate(txn, controller.predicate());
            }

            scanner = new BasicScanner<>(this, controller);
        }

        try {
            scanner.init(txn, row);
            return scanner;
        } catch (Throwable e) {
            if (closer != null) {
                closer.close();
            }
            throw e;
        }
    }

    /**
     * Note: Doesn't support orderBy.
     */
    final Scanner<R> newScannerThisTable(Transaction txn, R row,
                                         String queryStr, Object... args)
        throws IOException
    {
        return newScannerWith(txn, row, scannerFilteredFactory(txn, queryStr).scanController(args));
    }

    private ScanControllerFactory<R> scannerFilteredFactory(Transaction txn, String queryStr) {
        // Might need to double check the filter after joining to the primary, in case there
        // were any changes after the secondary entry was loaded.
        int type = RowUtils.isUnlocked(txn) ? DOUBLE_CHECK : PLAIN;
        return mFilterFactoryCache.obtain(type, queryStr, null);
    }

    private QueryLauncher<R> scannerQueryLauncher(Transaction txn, String queryStr)
        throws IOException
    {
        // Might need to double check the filter after joining to the primary, in case there
        // were any changes after the secondary entry was loaded.
        int type = RowUtils.isUnlocked(txn) ? DOUBLE_CHECK : PLAIN;
        return mQueryLauncherCache.obtain(type, queryStr, null);
    }

    @Override
    public final Updater<R> newUpdater(Transaction txn) throws IOException {
        return newUpdaterWith(txn, (R) null);
    }

    final Updater<R> newUpdaterWith(Transaction txn, R row) throws IOException {
        return newUpdaterWith(txn, row, unfiltered());
    }

    @Override
    public final Updater<R> newUpdater(Transaction txn, String queryStr, Object... args)
        throws IOException
    {
        return newUpdaterWith(txn, (R) null, queryStr, args);
    }

    protected Updater<R> newUpdaterWith(Transaction txn, R row, String queryStr, Object... args)
        throws IOException
    {
        QueryLauncher<R> launcher = updaterQueryLauncher(txn, queryStr);

        while (true) {
            try {
                return launcher.newUpdaterWith(txn, row, args);
            } catch (Throwable e) {
                launcher = newUpdaterRetry(txn, queryStr, launcher, e);
            }
        }
    }

    /**
     * To be called when attempting to launch a new updater and an exception is thrown. An
     * index might have been dropped, so check and retry. Returns a new QueryLauncher to use or
     * else throws the original exception.
     */
    private QueryLauncher<R> newUpdaterRetry(Transaction txn, String queryStr,
                                             QueryLauncher<R> launcher, Throwable cause)
    {
        // A ClosedIndexException could have come from the dropped index directly, and a
        // LockFailureException could be caused while waiting for the index lock. Other
        // exceptions aren't expected so don't bother trying to obtain a new launcher.
        if (cause instanceof ClosedIndexException || cause instanceof LockFailureException) {
            QueryLauncher<R> newLauncher;
            try {
                newLauncher = updaterQueryLauncher(txn, queryStr);
                if (newLauncher != launcher) {
                    // Only return the launcher if it changed.
                    return newLauncher;
                }
            } catch (Throwable e) {
                cause.addSuppressed(e);
            }
        }

        throw Utils.rethrow(cause);
    }

    protected Updater<R> newUpdaterWith(Transaction txn, R row, ScanController<R> controller)
        throws IOException
    {
        return newUpdaterWith(txn, row, controller, null);
    }

    /**
     * @param secondary non-null if joining from a secondary index to this primary table
     */
    final Updater<R> newUpdaterWith(Transaction txn, R row, ScanController<R> controller,
                                    BaseTableIndex<R> secondary)
        throws IOException
    {
        final BasicUpdater<R> updater;
        RowPredicateLock.Closer closer = null;

        addPredicate: {
            if (txn == null) {
                txn = mSource.newTransaction(null);
                updater = new AutoCommitUpdater<>(this, controller);
                if (secondary != null) {
                    // Need to guard against the secondary index from being concurrently
                    // dropped. This is like adding a predicate lock which matches nothing.
                    secondary.mIndexLock.addGuard(txn);
                }
                break addPredicate;
            }

            switch (txn.lockMode()) {
            case UPGRADABLE_READ: default: {
                updater = new BasicUpdater<>(this, controller);
                break;
            }

            case REPEATABLE_READ: {
                // Need to use upgradable locks to prevent deadlocks.
                updater = new UpgradableUpdater<>(this, controller);
                break;
            }

            case READ_COMMITTED: {
                // Row locks are released when possible, but a predicate lock will still be
                // held for the duration of the transaction. It's not worth the trouble to
                // determine if it can be safely released when the updater finishes.
                updater = new NonRepeatableUpdater<>(this, controller);
                break;
            }

            case READ_UNCOMMITTED:
                updater = new NonRepeatableUpdater<>(this, controller);
                // Don't add a predicate lock.
                break addPredicate;

            case UNSAFE:
                updater = new BasicUpdater<>(this, controller);
                // Don't add a predicate lock.
                break addPredicate;
            }

            RowPredicateLock<R> lock = secondary == null ? mIndexLock : secondary.mIndexLock;

            // This case is reached when a transaction was provided which is read committed
            // or higher. Adding a predicate lock prevents new rows from being inserted
            // into the scan range for the duration of the transaction scope. If the lock
            // mode is repeatable read, then rows which have been read cannot be deleted,
            // effectively making the transaction serializable.
            closer = lock.addPredicate(txn, controller.predicate());
        }

        try {
            if (secondary == null) {
                updater.init(txn, row);
                return updater;
            } else {
                var joined = new JoinedUpdater<>(secondary, controller, updater);
                joined.init(txn, row);
                return joined;
            }
        } catch (Throwable e) {
            if (closer != null) {
                closer.close();
            }
            throw e;
        }
    }

    /**
     * Note: Doesn't support orderBy.
     */
    final Updater<R> newUpdaterThisTable(Transaction txn, R row,
                                         String queryStr, Object... args)
        throws IOException
    {
        return newUpdaterWith(txn, row, updaterFilteredFactory(txn, queryStr).scanController(args));
    }

    private ScanControllerFactory<R> updaterFilteredFactory(Transaction txn, String queryStr) {
        // Might need to double check the filter after joining to the primary, in case there
        // were any changes after the secondary entry was loaded. Note that no double check is
        // needed with READ_UNCOMMITTED, because the updater for it still acquires locks. Also
        // note that FOR_UPDATE isn't used, because mFilterFactoryCache doesn't support it.
        int type = RowUtils.isUnsafe(txn) ? DOUBLE_CHECK : PLAIN;
        return mFilterFactoryCache.obtain(type, queryStr, null);
    }

    private QueryLauncher<R> updaterQueryLauncher(Transaction txn, String queryStr) {
        // Might need to double check the filter after joining to the primary, in case there
        // were any changes after the secondary entry was loaded. Note that no double check is
        // needed with READ_UNCOMMITTED, because the updater for it still acquires locks.
        int type = RowUtils.isUnsafe(txn) ? FOR_UPDATE_DOUBLE_CHECK : FOR_UPDATE;
        return mQueryLauncherCache.obtain(type, queryStr, null);
    }

    @Override
    public final String toString() {
        var b = new StringBuilder();
        RowUtils.appendMiniString(b, this);
        b.append('{');
        b.append("rowType").append(": ").append(rowType().getName());
        b.append(", ").append("primaryIndex").append(": ").append(mSource);
        return b.append('}').toString();
    }

    /**
     * Scan and write all rows of this table to a remote endpoint. This method doesn't flush
     * the output stream.
     */
    @SuppressWarnings("unchecked")
    public final void scanWrite(Transaction txn, DataOutput out) throws IOException {
        var writer = new RowWriter<R>(out);

        // Pass the writer as if it's a row, but it's actually a RowConsumer.
        Scanner<R> scanner = newScannerWith(txn, (R) writer);
        try {
            while (scanner.step((R) writer) != null);
        } catch (Throwable e) {
            Utils.closeQuietly(scanner);
            Utils.rethrow(e);
        }

        // Write the scan terminator. See RowWriter.writeHeader.
        out.writeByte(0);
    }

    /**
     * Scan and write a subset of rows from this table to a remote endpoint. This method
     * doesn't flush the output stream.
     */
    @SuppressWarnings("unchecked")
    public final void scanWrite(Transaction txn, DataOutput out, String queryStr, Object... args)
        throws IOException
    {
        var writer = new RowWriter<R>(out);

        scannerQueryLauncher(txn, queryStr).scanWrite(txn, writer, args);

        // Write the scan terminator. See RowWriter.writeHeader.
        out.writeByte(0);
    }

    @Override
    public final Transaction newTransaction(DurabilityMode durabilityMode) {
        return mSource.newTransaction(durabilityMode);
    }

    @Override
    public final boolean isEmpty() throws IOException {
        return mSource.isEmpty();
    }

    @Override
    public final RowPredicate<R> predicate(String queryStr, Object... args) {
        if (queryStr == null) {
            return RowPredicate.all();
        } else {
            return mFilterFactoryCache.obtain(PLAIN, queryStr, null).predicate(args);
        }
    }

    @Override
    public int characteristics() {
        return NONNULL | ORDERED | CONCURRENT | DISTINCT;
    }

    /**
     * Returns a view of this table which doesn't perform automatic index selection.
     */
    protected Table<R> viewPrimaryKey() {
        return new PrimaryTable<>(this);
    }

    /**
     * Returns a view of this table where the primary key is specified by the columns of an
     * alternate key, and the row is fully resolved by joining to the primary table. Direct
     * stores against the returned table aren't permitted, and an {@link
     * UnmodifiableViewException} is thrown when attempting to do so. Modifications are
     * permitted when using a {@link Updater}.
     *
     * @param columns column specifications for the alternate key
     * @return alternate key as a table
     * @throws IllegalStateException if alternate key wasn't found
     */
    protected BaseTableIndex<R> viewAlternateKey(String... columns) throws IOException {
        return viewIndexTable(true, columns);
    }

    /**
     * Returns a view of this table where the primary key is specified by the columns of a
     * secondary index, and the row is fully resolved by joining to the primary table. Direct
     * stores against the returned table aren't permitted, and an {@link
     * UnmodifiableViewException} is thrown when attempting to do so. Modifications are
     * permitted when using a {@link Updater}.
     *
     * @param columns column specifications for the secondary index
     * @return secondary index as a table
     * @throws IllegalStateException if secondary index wasn't found
     */
    protected BaseTableIndex<R> viewSecondaryIndex(String... columns) throws IOException {
        return viewIndexTable(false, columns);
    }

    final BaseTableIndex<R> viewIndexTable(boolean alt, String... columns) throws IOException {
        return rowStore().indexTable(this, alt, columns);
    }

    /**
     * Returns a direct view of an alternate key or secondary index, in the form of an
     * unmodifiable table. The rows of the table only contain the columns of the alternate key
     * or secondary index.
     *
     * @return an unjoined table, or else this table if it's not joined
     */
    protected Table<R> viewUnjoined() {
        return this;
    }

    @Override
    public QueryPlan scannerPlan(Transaction txn, String queryStr, Object... args)
        throws IOException
    {
        if (queryStr == null) {
            return plan(args);
        } else {
            return scannerQueryLauncher(txn, queryStr).scannerPlan(txn, args);
        }
    }

    @Override
    public QueryPlan updaterPlan(Transaction txn, String queryStr, Object... args)
        throws IOException
    {
        if (queryStr == null) {
            return plan(args);
        } else {
            return updaterQueryLauncher(txn, queryStr).updaterPlan(txn, args);
        }
    }

    /**
     * Note: Doesn't support orderBy.
     */
    final QueryPlan scannerPlanThisTable(Transaction txn, String queryStr, Object... args) {
        if (queryStr == null) {
            return plan(args);
        } else {
            return scannerFilteredFactory(txn, queryStr).plan(args);
        }
    }

    @Override
    public void close() throws IOException {
        mSource.close();

        // Secondary indexes aren't closed immediately, and so clearing the query cache forces
        // calls to be made to the checkClosed method. Explictly closing the old launchers
        // forces any in-progress scans to abort. Scans over sorted results aren't necessarily
        // affected, athough it would be nice if those always aborted too.

        if (mQueryLauncherCache != null) {
            mQueryLauncherCache.clear((QueryLauncher<R> launcher) -> {
                try {
                    launcher.close();
                } catch (IOException e) {
                    throw Utils.rethrow(e);
                }
            });
        }
    }

    @Override
    public boolean isClosed() {
        return mSource.isClosed();
    }

    private void checkClosed() throws IOException {
        if (isClosed()) {
            // Calling isEmpty should throw the preferred exception...
            mSource.isEmpty();
            // ...or else throw the generic one instead.
            throw new ClosedIndexException();
        }
    }

    @Override // ScanControllerFactory
    public final ScanControllerFactory<R> reverse() {
        return new ScanControllerFactory<R>() {
            @Override
            public QueryPlan plan(Object... args) {
                return planReverse(args);
            }

            @Override
            public ScanControllerFactory<R> reverse() {
                return BaseTable.this;
            }

            @Override
            public RowPredicate<R> predicate(Object... args) {
                return RowPredicate.all();
            }

            @Override
            public int characteristics() {
                return BaseTable.this.characteristics();
            }

            @Override
            public ScanController<R> scanController(Object... args) {
                return unfilteredReverse();
            }

            @Override
            public ScanController<R> scanController(RowPredicate<R> predicate) {
                return unfilteredReverse();
            }
        };
    }

    @Override // ScanControllerFactory
    public final RowPredicate<R> predicate(Object... args) {
        return RowPredicate.all();
    }

    @Override // ScanControllerFactory
    public final ScanController<R> scanController(Object... args) {
        return unfiltered();
    }

    @Override // ScanControllerFactory
    public final ScanController<R> scanController(RowPredicate<R> predicate) {
        return unfiltered();
    }

    /**
     * @param type PLAIN or DOUBLE_CHECK
     * @param queryStr the parsed and reduced query string; can be null initially
     */
    @SuppressWarnings("unchecked")
    private ScanControllerFactory<R> newFilteredFactory(int type, String queryStr, Query query) {
        Class<?> rowType = rowType();
        RowInfo rowInfo = RowInfo.find(rowType);
        Map<String, ColumnInfo> allColumns = rowInfo.allColumns;
        Map<String, ColumnInfo> availableColumns = allColumns;

        RowGen primaryRowGen = null;
        if (joinedPrimaryTableClass() != null) {
            // Join to the primary.
            primaryRowGen = rowInfo.rowGen();
        }

        byte[] secondaryDesc = secondaryDescriptor();
        if (secondaryDesc != null) {
            rowInfo = RowStore.secondaryRowInfo(rowInfo, secondaryDesc);
            if (joinedPrimaryTableClass() == null) {
                availableColumns = rowInfo.allColumns;
            }
        }

        if (query == null) {
            query = new Parser(allColumns, queryStr).parseQuery(availableColumns).reduce();
        }

        RowFilter rf = query.filter();

        if (rf instanceof FalseFilter) {
            return EmptyScanController.factory();
        }

        if (rf instanceof TrueFilter && query.projection() == null) {
            return this;
        }

        String canonical = query.toString();
        if (!canonical.equals(queryStr)) {
            // Don't actually return a specialized instance because it will be same.
            return mFilterFactoryCache.obtain(type, canonical, query);
        }

        var keyColumns = rowInfo.keyColumns.values().toArray(ColumnInfo[]::new);
        RowFilter[][] ranges = multiRangeExtract(rf, keyColumns);
        splitRemainders(rowInfo, ranges);

        if ((type & DOUBLE_CHECK) != 0 && primaryRowGen != null) {
            doubleCheckRemainder(ranges, primaryRowGen.info);
        }

        Class<? extends RowPredicate> baseClass = mIndexLock.evaluatorClass();
        RowGen rowGen = rowInfo.rowGen();

        Class<? extends RowPredicate> predClass = new RowPredicateMaker
            (rowStoreRef(), baseClass, rowType, rowGen, primaryRowGen,
             mTableManager.mPrimaryIndex.id(), mSource.id(), rf, queryStr, ranges).finish();

        if (ranges.length > 1) {
            var rangeFactories = new ScanControllerFactory[ranges.length];
            for (int i=0; i<ranges.length; i++) {
                rangeFactories[i] = newFilteredFactory
                    (rowGen, ranges[i], predClass, query.projection());
            }
            return new RangeUnionScanControllerFactory(rangeFactories);
        }

        // Only one range to scan.
        RowFilter[] range = ranges[0];

        if (range[0] == null && range[1] == null) {
            // Full scan, so just use the original reduced filter. It's possible that
            // the dnf/cnf form is reduced even further, but when doing a full scan,
            // let the user define the order in which the filter terms are examined.
            range[2] = rf;
            range[3] = null;
            splitRemainders(rowInfo, range);
        }

        return newFilteredFactory(rowGen, range, predClass, query.projection());
    }

    @SuppressWarnings("unchecked")
    private ScanControllerFactory<R> newFilteredFactory(RowGen rowGen, RowFilter[] range,
                                                        Class<? extends RowPredicate> predClass,
                                                        Map<String, ColumnInfo> projection)
    {
        SingleScanController<R> unfiltered = unfiltered();

        RowFilter lowBound = range[0];
        RowFilter highBound = range[1];
        RowFilter filter = range[2];
        RowFilter joinFilter = range[3];

        return new FilteredScanMaker<R>
            (rowStoreRef(), this, rowGen, unfiltered, predClass,
             lowBound, highBound, filter, joinFilter, projection).finish();
    }

    private RowFilter[][] multiRangeExtract(RowFilter rf, ColumnInfo... keyColumns) {
        try {
            return rf.dnf().multiRangeExtract(false, false, keyColumns);
        } catch (ComplexFilterException e) {
            complex(rf, e);
            try {
                return new RowFilter[][] {rf.cnf().rangeExtract(keyColumns)};
            } catch (ComplexFilterException e2) {
                return new RowFilter[][] {new RowFilter[] {null, null, rf, null}};
            }
        }
    }

    /**
     * @param rowInfo for secondary (method does nothing if this is the primary table)
     * @see RowFilter#multiRangeExtract
     */
    private void splitRemainders(RowInfo rowInfo, RowFilter[]... ranges) {
        if (joinedPrimaryTableClass() != null) {
            // First filter on the secondary entry, and then filter on the joined primary entry.
            RowFilter.splitRemainders(rowInfo.allColumns, ranges);
        }
    }

    /**
     * Applies a double check of the remainder filter, applicable only to joins.
     *
     * @param rowInfo for primary
     * @see RowFilter#multiRangeExtract
     */
    private static void doubleCheckRemainder(RowFilter[][] ranges, RowInfo rowInfo) {
        for (RowFilter[] r : ranges) {
            // Build up a complete remainder that performs fully redundant filtering. Order the
            // terms such that ones most likely to have any effect come first.
            RowFilter remainder = and(and(and(r[3], r[2]), r[0]), r[1]);
            // Remove terms that only check the primary key, because they won't change with a join.
            remainder = remainder.retain(rowInfo.valueColumns::containsKey, false, TrueFilter.THE);
            r[3] = remainder.reduceMore();
        }
    }

    private static RowFilter and(RowFilter a, RowFilter b) {
        return a != null ? (b != null ? a.and(b) : a) : (b != null ? b : TrueFilter.THE);
    }

    private void complex(RowFilter rf, ComplexFilterException e) {
        RowStore rs = rowStoreRef().get();
        if (rs != null) {
            EventListener listener = rs.mDatabase.eventListener();
            if (listener != null) {
                listener.notify(EventType.TABLE_COMPLEX_FILTER,
                                "Complex filter: %1$s \"%2$s\" %3$s",
                                rowType().getName(), rf.toString(), e.getMessage());
            }
        }
    }

    /**
     * @param type PLAIN, DOUBLE_CHECK, etc.
     */
    @SuppressWarnings("unchecked")
    private QueryLauncher<R> newQueryLauncher(int type, String queryStr, IndexSelector selector)
        throws IOException
    {
        checkClosed();

        RowInfo rowInfo;

        if (selector != null) {
            rowInfo = selector.primaryInfo();
        } else {
            rowInfo = RowInfo.find(rowType());
            Map<String, ColumnInfo> allColumns = rowInfo.allColumns;
            Query query = new Parser(allColumns, queryStr).parseQuery(allColumns).reduce();
            selector = new IndexSelector<R>(this, rowInfo, query, (type & FOR_UPDATE) != 0);
        }

        if ((type & FOR_UPDATE) != 0) {
            if (selector.orderBy() != null) {
                // The Updater needs to have a sort step applied, and so it needs access to
                // the primary key. This is because the update/delete operation is performed
                // by calling Table.update or Table.delete. See WrappedUpdater.
                // TODO: Remove this requirement by automatically decoding the primary key and
                // hiding the result.
                Map<String, ColumnInfo> proj = selector.query().projection();
                if (proj != null && !proj.keySet().containsAll(rowInfo.keyColumns.keySet())) {
                    throw new IllegalStateException
                        ("Sorted Updater query must select all primary key columns");
                }
            }

            if (!selector.forUpdateRuleChosen()) {
                // Don't actually return a specialized updater instance because it will be the
                // same as the scanner instance.
                return mQueryLauncherCache.obtain(type & ~FOR_UPDATE, queryStr, selector);
            }
        }

        int num = selector.numSelected();
        QueryLauncher<R> launcher;

        if (num <= 1) {
            launcher = newSubLauncher(type, selector, 0);
        } else {
            var launchers = new QueryLauncher[num];
            for (int i=0; i<num; i++) {
                launchers[i] = newSubLauncher(type, selector, i);
            }
            launcher = new DisjointUnionQueryLauncher<R>(launchers);
        }

        OrderBy orderBy = selector.orderBy();
        if (orderBy != null) {
            launcher = new SortedQueryLauncher<R>(this, launcher, selector.projection(), orderBy);
        }

        return launcher;
    }

    /**
     * @param type PLAIN, DOUBLE_CHECK, etc.
     */
    private QueryLauncher<R> newSubLauncher(int type, IndexSelector<R> selector, int i) {
        BaseTable<R> subTable = selector.selectedIndexTable(i);
        Query subQuery = selector.selectedQuery(i);
        String subQueryStr = subQuery.toString();

        ScanControllerFactory<R> subFactory =
            subTable.mFilterFactoryCache.obtain(type & ~FOR_UPDATE, subQueryStr, subQuery);

        if ((type & FOR_UPDATE) == 0 && subFactory.loadsOne()) {
            // Return an optimized launcher.
            return new LoadOneQueryLauncher<>(subTable, subFactory);
        }

        if (selector.selectedReverse(i)) {
            subFactory = subFactory.reverse();
        }

        return new ScanQueryLauncher<>(subTable, subFactory);
    }

    /**
     * To be called when the set of available secondary indexes and alternate keys has changed.
     */
    void clearQueryCache() {
        if (mQueryLauncherCache != null) {
            mQueryLauncherCache.clear();
        }
    }

    /**
     * Partially decodes a row from a key.
     */
    protected abstract R toRow(byte[] key);

    protected final RowStore rowStore() throws DatabaseException {
        var rs = rowStoreRef().get();
        if (rs == null) {
            throw new DatabaseException("Closed");
        }
        return rs;
    }

    protected final WeakReference<RowStore> rowStoreRef() {
        return mTableManager.mRowStoreRef;
    }

    protected abstract QueryPlan planReverse(Object... args);

    /**
     * Returns a new or singleton instance.
     */
    protected abstract SingleScanController<R> unfiltered();

    /**
     * Returns a new or singleton instance.
     */
    protected abstract SingleScanController<R> unfilteredReverse();

    /**
     * Returns a MethodHandle which decodes rows partially.
     *
     * MethodType is: void (RowClass row, byte[] key, byte[] value)
     *
     * The spec defines two BitSets, which refer to columns to decode. The first BitSet
     * indicates which columns aren't in the row object and must be decoded. The second BitSet
     * indicates which columns must be marked as clean. All other columns are unset.
     *
     * @param spec must have an even length; first half refers to columns to decode and second
     * half refers to columns to mark clean
     * @see DecodePartialMaker
     */
    protected final MethodHandle decodePartialHandle(byte[] spec, int schemaVersion) {
        WeakCache<Object, MethodHandle, byte[]> cache = mDecodePartialCache;

        if (cache == null) {
            cache = new WeakCache<>() {
                @Override
                protected MethodHandle newValue(Object key, byte[] spec) {
                    int schemaVersion = 0;
                    if (key instanceof ArrayKey.PrefixBytes pb) {
                        schemaVersion = pb.prefix;
                    }
                    return makeDecodePartialHandle(spec, schemaVersion);
                }
            };

            var existing = (WeakCache<Object, MethodHandle, byte[]>)
                cDecodePartialCacheHandle.compareAndExchange(this, null, cache);

            if (existing != null) {
                cache = existing;
            }
        }

        final Object key = schemaVersion == 0 ?
            ArrayKey.make(spec) : ArrayKey.make(schemaVersion, spec);

        return cache.obtain(key, spec);
    }

    protected abstract MethodHandle makeDecodePartialHandle(byte[] spec, int schemaVersion);

    /**
     * Returns a MethodHandle suitable for writing rows from evolvable or unevolvable
     * tables. The set of row columns which are written is defined by the projection
     * specification.
     *
     * For evolvable tables, the MethodType is:
     *     void (int schemaVersion, RowWriter writer, byte[] key, byte[] value)
     *
     * For unevolvable tables, the MethodType is:
     *     void (RowWriter writer, byte[] key, byte[] value)
     *
     * @param spec can be null if all columns are projected
     */
    protected final MethodHandle writeRowHandle(byte[] spec) {
        WeakCache<Object, MethodHandle, byte[]> cache = mWriteRowCache;

        if (cache == null) {
            cache = new WeakCache<>() {
                @Override
                protected MethodHandle newValue(Object key, byte[] spec) {
                    if (isEvolvable()) {
                        return WriteRowMaker.makeWriteRowHandle
                            (rowStoreRef(), rowType(), mSource.id(), spec);
                    }

                    RowInfo primaryRowInfo = RowInfo.find(rowType());
                    byte[] secondaryDesc = secondaryDescriptor();

                    RowInfo rowInfo;
                    if (secondaryDesc == null) {
                        rowInfo = primaryRowInfo;
                    } else {
                        rowInfo = RowStore.secondaryRowInfo(primaryRowInfo, secondaryDesc);
                    }

                    return WriteRowMaker.makeWriteRowHandle(rowInfo, spec);
                }
            };

            var existing = (WeakCache<Object, MethodHandle, byte[]>)
                cWriteRowCacheHandle.compareAndExchange(this, null, cache);

            if (existing != null) {
                cache = existing;
            }
        }

        return cache.obtain(ArrayKey.make(spec), spec);
    }

    public final RemoteTableProxy newRemoteProxy(byte[] descriptor) throws IOException {
        int schemaVersion;
        if (!isEvolvable()) {
            schemaVersion = 0;
        } else {
            RowInfo rowInfo = RowInfo.find(rowType());
            schemaVersion = rowStore().schemaVersion(rowInfo, false, mSource.id(), true);
        }
        return RemoteProxyMaker.make(this, schemaVersion, descriptor);
    }

    /**
     * This method should be called to replicate predicate lock acquisition before a local
     * mIndexLock.openAcquire is called. The given transaction should be nested, and it should
     * be exited after performing the local insert operation. If no local call to openAcquire
     * is made, calling redoPredicateMode is harmful because it causes the replica to acquire
     * an extra lock, which can lead to deadlock.
     *
     * <p>Note that operations which won't directly insert a new record might still trigger
     * changes to secondary indexes. This will cause secondary index records to be inserted, and
     * so a predicate lock is still required to ensure serializable isolation.
     *
     * <p>This method must be public to be accessible by code generated by
     * DynamicTableMaker.indyDoUpdate. The generated code won't be a subclass of BaseTable, and
     * so it cannot access this method if it was protected.
     */
    public final void redoPredicateMode(Transaction txn) throws IOException {
        mIndexLock.redoPredicateMode(txn);
    }

    public final Transaction enterScope(Transaction txn) throws IOException {
        return ViewUtils.enterScope(mSource, txn);
    }

    /**
     * Called when no trigger is installed.
     */
    protected final void storeNoTrigger(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        Index source = mSource;

        // RowPredicateLock requires a non-null transaction.
        txn = ViewUtils.enterScope(source, txn);
        try {
            redoPredicateMode(txn);
            try (var closer = mIndexLock.openAcquire(txn, row)) {
                source.store(txn, key, value);
            }
            txn.commit();
        } finally {
            txn.exit();
        }
    }

    /**
     * Called when no trigger is installed.
     */
    protected final byte[] exchangeNoTrigger(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        Index source = mSource;

        // RowPredicateLock requires a non-null transaction.
        txn = ViewUtils.enterScope(source, txn);
        byte[] oldValue;
        try {
            redoPredicateMode(txn);
            try (var closer = mIndexLock.openAcquire(txn, row)) {
                oldValue = source.exchange(txn, key, value);
            }
            txn.commit();
        } finally {
            txn.exit();
        }

        return oldValue;
    }

    /**
     * Called when no trigger is installed.
     */
    protected final boolean insertNoTrigger(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        Index source = mSource;

        // RowPredicateLock requires a non-null transaction.
        txn = ViewUtils.enterScope(source, txn);
        boolean result;
        try {
            redoPredicateMode(txn);
            try (var closer = mIndexLock.openAcquire(txn, row)) {
                result = source.insert(txn, key, value);
            }
            txn.commit();
        } finally {
            txn.exit();
        }

        return result;
    }

    /**
     * Called when no trigger is installed.
     */
    protected final boolean replaceNoTrigger(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        // Nothing is ever inserted, and so no need to use a predicate lock.
        return mSource.replace(txn, key, value);
    }

    /**
     * Store a fully encoded row and invoke a trigger. The given row instance is expected to
     * be empty, and it's never modified by this method.
     *
     * @see RemoteProxyMaker
     */
    final void storeAndTrigger(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        // This method must never be called on a secondary index or alternate key, and so
        // blindy assume that a trigger exists.

        // Note that this method resembles the same code that is generated by TableMaker and
        // StaticTableMaker. One major difference is that the row columns aren't marked clean.

        while (true) {
            Trigger<R> trigger = trigger();
            trigger.acquireShared();
            try {
                int mode = trigger.mode();

                if (mode == Trigger.SKIP) {
                    mSource.store(txn, key, value);
                    return;
                }

                if (mode != Trigger.DISABLED) {
                    Index source = mSource;

                    // RowPredicateLock and Trigger require a non-null transaction.
                    txn = ViewUtils.enterScope(source, txn);
                    try {
                        redoPredicateMode(txn);

                        try (var c = source.newCursor(txn)) {
                            try (var closer = mIndexLock.openAcquireP(txn, row, key, value)) {
                                c.find(key);
                            }
                            byte[] oldValue = c.value();
                            if (oldValue == null) {
                                trigger.insertP(txn, row, key, value);
                            } else {
                                trigger.storeP(txn, row, key, oldValue, value);
                            }
                            c.commit(value);
                        }
                    } finally {
                        txn.exit();
                    }

                    return;
                }
            } finally {
                trigger.releaseShared();
            }
        }
    }

    /**
     * Exchange a fully encoded row and invoke a trigger. The given row instance is expected to
     * be empty, and it's never modified by this method.
     *
     * @return the replaced value, or null if none
     * @see RemoteProxyMaker
     */
    final byte[] exchangeAndTrigger(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        // See comments in storeAndTrigger.

        while (true) {
            Trigger<R> trigger = trigger();
            trigger.acquireShared();
            try {
                int mode = trigger.mode();

                if (mode == Trigger.SKIP) {
                    return mSource.exchange(txn, key, value);
                }

                if (mode != Trigger.DISABLED) {
                    Index source = mSource;

                    // RowPredicateLock and Trigger require a non-null transaction.
                    txn = ViewUtils.enterScope(source, txn);
                    try {
                        redoPredicateMode(txn);

                        try (var c = source.newCursor(txn)) {
                            try (var closer = mIndexLock.openAcquireP(txn, row, key, value)) {
                                c.find(key);
                            }
                            byte[] oldValue = c.value();
                            if (oldValue == null) {
                                trigger.insertP(txn, row, key, value);
                            } else {
                                trigger.storeP(txn, row, key, oldValue, value);
                            }
                            c.commit(value);
                            return oldValue;
                        }
                    } finally {
                        txn.exit();
                    }
                }
            } finally {
                trigger.releaseShared();
            }
        }
    }

    /**
     * Insert a fully encoded row and invoke a trigger. The given row instance is expected to
     * be empty, and it's never modified by this method.
     *
     * @see RemoteProxyMaker
     */
    final boolean insertAndTrigger(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        // See comments in storeAndTrigger.

        while (true) {
            Trigger<R> trigger = trigger();
            trigger.acquireShared();
            try {
                int mode = trigger.mode();

                if (mode == Trigger.SKIP) {
                    return mSource.insert(txn, key, value);
                }

                if (mode != Trigger.DISABLED) {
                    Index source = mSource;

                    // RowPredicateLock and Trigger require a non-null transaction.
                    txn = ViewUtils.enterScope(source, txn);
                    try {
                        redoPredicateMode(txn);

                        try (var c = source.newCursor(txn)) {
                            c.autoload(false);
                            try (var closer = mIndexLock.openAcquireP(txn, row, key, value)) {
                                c.find(key);
                            }
                            if (c.value() != null) {
                                return false;
                            } else {
                                trigger.insertP(txn, row, key, value);
                                c.commit(value);
                                return true;
                            }
                        }
                    } finally {
                        txn.exit();
                    }
                }
            } finally {
                trigger.releaseShared();
            }
        }
    }

    /**
     * Replace a fully encoded row and invoke a trigger. The given row instance is expected to
     * be empty, and it's never modified by this method.
     *
     * @see RemoteProxyMaker
     */
    final boolean replaceAndTrigger(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        // See comments in storeAndTrigger.

        while (true) {
            Trigger<R> trigger = trigger();
            trigger.acquireShared();
            try {
                int mode = trigger.mode();

                if (mode == Trigger.SKIP) {
                    return mSource.replace(txn, key, value);
                }

                if (mode != Trigger.DISABLED) {
                    Index source = mSource;

                    // RowPredicateLock and Trigger require a non-null transaction.
                    txn = ViewUtils.enterScope(source, txn);
                    try (var c = source.newCursor(txn)) {
                        c.find(key);
                        byte[] oldValue = c.value();
                        if (oldValue == null) {
                            return false;
                        }
                        c.store(value);
                        // Only need to enable redoPredicateMode for the trigger, since it
                        // might insert new secondary index entries (and call openAcquire).
                        redoPredicateMode(txn);
                        trigger.storeP(txn, row, key, oldValue, value);
                        txn.commit();
                        return true;
                    } finally {
                        txn.exit();
                    }
                }
            } finally {
                trigger.releaseShared();
            }
        }
    }

    /**
     * @see #updateAndTrigger
     */
    @FunctionalInterface
    public static interface ValueUpdater {
        /**
         * Given an existing encoded row value, return a new encode row value which has a
         * schema version encoded.
         */
        byte[] updateValue(byte[] value);
    }

    /**
     * Update a fully encoded key and invoke a trigger. The given row instance is expected to
     * be empty, and it's never modified by this method.
     *
     * @see RemoteProxyMaker
     * @return the new value, or null if none
     */
    final byte[] updateAndTrigger(Transaction txn, R row, byte[] key, ValueUpdater updater)
        throws IOException
    {
        // See comments in storeAndTrigger.

        // RowPredicateLock and Trigger require a non-null transaction.
        txn = ViewUtils.enterScope(mSource, txn);
        try (var c = mSource.newCursor(txn)) {
            c.find(key);

            byte[] originalValue = c.value();
            if (originalValue == null) {
                return null;
            }

            byte[] newValue = updater.updateValue(originalValue);

            while (true) {
                Trigger<R> trigger = trigger();
                trigger.acquireShared();
                try {
                    int mode = trigger.mode();

                    if (mode == Trigger.SKIP) {
                        c.commit(newValue);
                        return newValue;
                    }

                    if (mode != Trigger.DISABLED) {
                        c.store(newValue);
                        // Only need to enable redoPredicateMode for the trigger, since it
                        // might insert new secondary index entries (and call openAcquire).
                        redoPredicateMode(txn);
                        trigger.storeP(txn, row, key, originalValue, newValue);
                        txn.commit();
                        return newValue;
                    }
                } finally {
                    trigger.releaseShared();
                }
            }
        } finally {
            txn.exit();
        }
    }

    /**
     * Delete a fully encoded key and invoke a trigger. The given row instance is expected to
     * be empty, and it's never modified by this method.
     *
     * @see RemoteProxyMaker
     */
    final boolean deleteAndTrigger(Transaction txn, byte[] key) throws IOException {
        // See comments in storeAndTrigger.

        while (true) {
            Trigger<R> trigger = trigger();
            trigger.acquireShared();
            try {
                int mode = trigger.mode();

                if (mode == Trigger.SKIP) {
                    return mSource.delete(txn, key);
                }

                if (mode != Trigger.DISABLED) {
                    Index source = mSource;

                    // Trigger requires a non-null transaction.
                    txn = ViewUtils.enterScope(source, txn);
                    try (var c = source.newCursor(txn)) {
                        c.find(key);
                        byte[] oldValue = c.value();
                        if (oldValue == null) {
                            return false;
                        } else {
                            trigger.delete(txn, key, oldValue);
                            c.commit(null);
                            return true;
                        }
                    } finally {
                        txn.exit();
                    }
                }
            } finally {
                trigger.releaseShared();
            }
        }
    }

    /**
     * Store a fully encoded row and invoke a trigger. The given row instance is expected to
     * be empty, and it's never modified by this method.
     *
     * @see RemoteProxyMaker
     * @return actual key that was stored
     */
    final byte[] storeAutoAndTrigger(AutomaticKeyGenerator<R> autogen,
                                     Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        // The row object is only passed to the Trigger.insertP method, because it always
        // requires a row even if it's empty. The row is never passed to the
        // AutomaticKeyGenerator, forcing it to call the Applier.tryOpenAcquire method instead
        // of applyToRow. This ensures that the row isn't modified.

        // Note that this method resembles the same code that is generated by TableMaker and
        // StaticTableMaker. One major difference is that the row columns aren't marked clean.

        // Call enterScopex because bogus transaction doesn't work with AutomaticKeyGenerator.
        txn = ViewUtils.enterScopex(mSource, txn);
        try {
            // Enable redoPredicateMode now because the call to the AutomaticKeyGenerator will
            // acquire a predicate lock.
            redoPredicateMode(txn);

            while (true) {
                Trigger<R> trigger = trigger();

                if (trigger == null) {
                    key = autogen.store(txn, null, key, value);
                    txn.commit();
                    return key;
                }

                trigger.acquireShared();
                try {
                    int mode = trigger.mode();
                    if (mode == Trigger.SKIP) {
                        key = autogen.store(txn, null, key, value);
                    } else if (mode == Trigger.DISABLED) {
                        continue;
                    } else {
                        key = autogen.store(txn, null, key, value);
                        trigger.insertP(txn, row, key, value);
                    }
                    txn.commit();
                    return key;
                } finally {
                    trigger.releaseShared();
                }
            }
        } finally {
            txn.exit();
        }
    }

    /**
     * Override if this table implements a secondary index.
     */
    protected byte[] secondaryDescriptor() {
        return null;
    }

    /**
     * Override if this table implements a secondary index and joins to the primary.
     */
    protected BaseTable<R> joinedPrimaryTable() {
        return null;
    }

    /**
     * Override if this table implements a secondary index and joins to the primary.
     */
    protected Class<?> joinedPrimaryTableClass() {
        return null;
    }

    /**
     * Note: Is overridden by BaseTableIndex.
     */
    boolean isEvolvable() {
        return isEvolvable(rowType());
    }

    static boolean isEvolvable(Class<?> rowType) {
        return rowType != Entry.class;
    }

    /**
     * Note: Is overridden by BaseTableIndex.
     */
    boolean supportsSecondaries() {
        return true;
    }

    /**
     * Set the table trigger and then wait for the old trigger to no longer be used. Waiting is
     * necessary to prevent certain race conditions. For example, when adding a secondary
     * index, a backfill task can't safely begin until it's known that no operations are in
     * flight which aren't aware of the new index. Until this method returns, it should be
     * assumed that both the old and new trigger are running concurrently.
     *
     * @param trigger can pass null to remove the trigger
     * @throws UnsupportedOperationException if triggers aren't supported by this table
     */
    final void setTrigger(Trigger<R> trigger) {
        if (mTrigger == null) {
            throw new UnsupportedOperationException();
        }

        if (trigger == null) {
            trigger = new Trigger<>();
            trigger.mMode = Trigger.SKIP;
        }

        ((Trigger<R>) cTriggerHandle.getAndSet(this, trigger)).disable();
    }

    /**
     * Returns the trigger quickly, which is null if triggers aren't supported.
     */
    final Trigger<R> getTrigger() {
        return mTrigger;
    }

    /**
     * Returns the current trigger, which must be held shared during the operation. As soon as
     * acquired, check if the trigger is disabled. This method must be public because it's
     * sometimes accessed from generated code which isn't a subclass of BaseTable.
     */
    public final Trigger<R> trigger() {
        return (Trigger<R>) cTriggerHandle.getOpaque(this);
    }

    static RowFilter parseFilter(Class<?> rowType, String queryStr) {
        var parser = new Parser(RowInfo.find(rowType).allColumns, queryStr);
        parser.skipProjection();
        return parser.parseFilter();
    }
}
