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

import java.io.IOException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.lang.ref.WeakReference;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

import org.cojen.tupl.DatabaseException;
import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.Index;
import org.cojen.tupl.LockMode;
import org.cojen.tupl.RowScanner;
import org.cojen.tupl.RowUpdater;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;

import org.cojen.tupl.core.RowPredicate;
import org.cojen.tupl.core.RowPredicateLock;

import org.cojen.tupl.diag.EventListener;
import org.cojen.tupl.diag.EventType;
import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.filter.ComplexFilterException;
import org.cojen.tupl.filter.FalseFilter;
import org.cojen.tupl.filter.Parser;
import org.cojen.tupl.filter.Query;
import org.cojen.tupl.filter.RowFilter;
import org.cojen.tupl.filter.TrueFilter;

import org.cojen.tupl.io.Utils;

import org.cojen.tupl.views.ViewUtils;

/**
 * Base class for all generated table classes.
 *
 * @author Brian S O'Neill
 */
public abstract class BaseTable<R> implements Table<R>, ScanControllerFactory<R> {
    final TableManager<R> mTableManager;

    protected final Index mSource;

    private final FilterFactoryCache mFilterFactoryCache;
    private final FilterFactoryCache mFilterFactoryCacheDoubleCheck;

    private final QueryLauncherCache mQueryLauncherCache;
    private final QueryLauncherCache mQueryLauncherCacheDoubleCheck;

    private Trigger<R> mTrigger;
    private static final VarHandle cTriggerHandle;

    private WeakCache<String, Comparator<R>, Object> mComparatorCache;
    private static final VarHandle cComparatorCacheHandle;

    // Is null if unsupported.
    protected final RowPredicateLock<R> mIndexLock;

    private WeakCache<Object, MethodHandle, byte[]> mPartialDecodeCache;
    private static final VarHandle cPartialDecodeCacheHandle;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            cTriggerHandle = lookup.findVarHandle
                (BaseTable.class, "mTrigger", Trigger.class);
            cComparatorCacheHandle = lookup.findVarHandle
                (BaseTable.class, "mComparatorCache", WeakCache.class);
            cPartialDecodeCacheHandle = lookup.findVarHandle
                (BaseTable.class, "mPartialDecodeCache", WeakCache.class);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
    }

    /**
     * @param indexLock is null if unsupported
     */
    protected BaseTable(TableManager<R> manager, Index source, RowPredicateLock<R> indexLock) {
        mTableManager = manager;

        mSource = Objects.requireNonNull(source);

        mFilterFactoryCache = new FilterFactoryCache();
        mFilterFactoryCacheDoubleCheck =
            joinedPrimaryTableClass() == null ? null : new FilterFactoryCache();

        if (supportsSecondaries()) {
            mQueryLauncherCache = new QueryLauncherCache(false);
            mQueryLauncherCacheDoubleCheck = new QueryLauncherCache(true);

            var trigger = new Trigger<R>();
            trigger.mMode = Trigger.SKIP;
            cTriggerHandle.setRelease(this, trigger);
        } else {
            mQueryLauncherCache = null;
            mQueryLauncherCacheDoubleCheck = null;
        }

        mIndexLock = indexLock;
    }

    private final class FilterFactoryCache
        extends SoftCache<String, ScanControllerFactory<R>, Query>
    {
        @Override
        protected ScanControllerFactory<R> newValue(String queryStr, Query query) {
            return newFilteredFactory(this, queryStr, query);
        }
    }

    final class QueryLauncherCache extends SoftCache<String, QueryLauncher<R>, Object> {
        private boolean mDoubleCheck;

        QueryLauncherCache(boolean doubleCheck) {
            mDoubleCheck = doubleCheck;
        }

        @Override
        protected QueryLauncher<R> newValue(String queryStr, Object unused) {
            try {
                return newQueryLauncher(queryStr, mDoubleCheck);
            } catch (IOException e) {
                throw RowUtils.rethrow(e);
            }
        }
    }

    public final TableManager<R> tableManager() {
        return mTableManager;
    }

    @Override
    public final RowScanner<R> newRowScanner(Transaction txn) throws IOException {
        return newRowScanner(txn, (R) null);
    }

    final RowScanner<R> newRowScanner(Transaction txn, R row) throws IOException {
        return newRowScanner(txn, row, unfiltered());
    }

    @Override
    public final RowScanner<R> newRowScanner(Transaction txn, String queryStr, Object... args)
        throws IOException
    {
        return newRowScanner(txn, (R) null, queryStr, args);
    }

    protected RowScanner<R> newRowScanner(Transaction txn, R row, String queryStr, Object... args)
        throws IOException
    {
        return scannerQueryLauncher(txn, queryStr).newRowScanner(txn, row, args);
    }

    final RowScanner<R> newRowScanner(Transaction txn, R row, ScanController<R> controller)
        throws IOException
    {
        final BasicRowScanner<R> scanner;
        RowPredicateLock.Closer closer = null;

        if (txn == null && controller.isJoined()) {
            txn = mSource.newTransaction(null);
            txn.lockMode(LockMode.REPEATABLE_READ);
            scanner = new AutoCommitRowScanner<>(this, controller);
        } else {
            scanner = new BasicRowScanner<>(this, controller);

            if (txn != null && !txn.lockMode().noReadLock) {
                RowPredicateLock<R> lock = mIndexLock;
                if (lock != null) {
                    // This case is reached when a transaction was provided which is read
                    // committed or higher. Adding a predicate lock prevents new rows from
                    // being inserted into the scan range for the duration of the transaction
                    // scope. If the lock mode is repeatable read, then rows which have been
                    // read cannot be deleted, effectively making the transaction serializable.
                    closer = lock.addPredicate(txn, controller.predicate());
                }
            }
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
    final RowScanner<R> newRowScannerThisTable(Transaction txn, R row,
                                               String queryStr, Object... args)
        throws IOException
    {
        return newRowScanner(txn, row, scannerFilteredFactory(txn, queryStr).scanController(args));
    }

    private ScanControllerFactory<R> scannerFilteredFactory(Transaction txn, String queryStr) {
        FilterFactoryCache cache;
        // Need to double check the filter after joining to the primary, in case there were any
        // changes after the secondary entry was loaded.
        if (!RowUtils.isUnlocked(txn) || (cache = mFilterFactoryCacheDoubleCheck) == null) {
            cache = mFilterFactoryCache;
        }
        return cache.obtain(queryStr, null);
    }

    private QueryLauncher<R> scannerQueryLauncher(Transaction txn, String queryStr)
        throws IOException
    {
        QueryLauncherCache cache;
        // Need to double check the filter after joining to the primary, in case there were any
        // changes after the secondary entry was loaded.
        if (!RowUtils.isUnlocked(txn) || (cache = mQueryLauncherCacheDoubleCheck) == null) {
            cache = mQueryLauncherCache;
        }
        return cache.obtain(queryStr, null);
    }

    @Override
    public final RowUpdater<R> newRowUpdater(Transaction txn) throws IOException {
        return newRowUpdater(txn, (R) null);
    }

    final RowUpdater<R> newRowUpdater(Transaction txn, R row) throws IOException {
        return newRowUpdater(txn, row, unfiltered());
    }

    @Override
    public final RowUpdater<R> newRowUpdater(Transaction txn, String queryStr, Object... args)
        throws IOException
    {
        return newRowUpdater(txn, (R) null, queryStr, args);
    }

    protected RowUpdater<R> newRowUpdater(Transaction txn, R row, String queryStr, Object... args)
        throws IOException
    {
        return updaterQueryLauncher(txn, queryStr).newRowUpdater(txn, row, args);
    }

    protected RowUpdater<R> newRowUpdater(Transaction txn, R row, ScanController<R> controller)
        throws IOException
    {
        return newRowUpdater(txn, row, controller, null);
    }

    /**
     * @param secondary non-null if joining from a secondary index to this primary table
     */
    final RowUpdater<R> newRowUpdater(Transaction txn, R row, ScanController<R> controller,
                                      BaseTableIndex<R> secondary)
        throws IOException
    {
        final BasicRowUpdater<R> updater;
        RowPredicateLock.Closer closer = null;

        addPredicate: {

            if (txn == null) {
                txn = mSource.newTransaction(null);
                updater = new AutoCommitRowUpdater<>(this, controller);
                // Don't add a predicate lock.
                break addPredicate;
            }

            switch (txn.lockMode()) {
            case UPGRADABLE_READ: default: {
                updater = new BasicRowUpdater<>(this, controller);
                break;
            }

            case REPEATABLE_READ: {
                // Need to use upgradable locks to prevent deadlocks.
                updater = new UpgradableRowUpdater<>(this, controller);
                break;
            }

            case READ_COMMITTED: {
                // Row locks are released when possible, but a predicate lock will still be
                // held for the duration of the transaction. It's not worth the trouble to
                // determine if it can be safely released when the updater finishes.
                updater = new NonRepeatableRowUpdater<>(this, controller);
                break;
            }

            case READ_UNCOMMITTED:
                updater = new NonRepeatableRowUpdater<>(this, controller);
                // Don't add a predicate lock.
                break addPredicate;

            case UNSAFE:
                updater = new BasicRowUpdater<>(this, controller);
                // Don't add a predicate lock.
                break addPredicate;
            }

            RowPredicateLock<R> lock = secondary == null ? mIndexLock : secondary.mIndexLock;
            if (lock == null) {
                break addPredicate;
            }

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
                var joined = new JoinedRowUpdater<>(secondary, controller, updater);
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
    final RowUpdater<R> newRowUpdaterThisTable(Transaction txn, R row,
                                               String queryStr, Object... args)
        throws IOException
    {
        return newRowUpdater(txn, row, updaterFilteredFactory(txn, queryStr).scanController(args));
    }

    private ScanControllerFactory<R> updaterFilteredFactory(Transaction txn, String queryStr) {
        FilterFactoryCache cache;
        // Need to double check the filter after joining to the primary, in case there were any
        // changes after the secondary entry was loaded. Note that no double check is needed
        // with READ_UNCOMMITTED, because the updater for it still acquires locks.
        if (!RowUtils.isUnsafe(txn) || (cache = mFilterFactoryCacheDoubleCheck) == null) {
            cache = mFilterFactoryCache;
        }
        return cache.obtain(queryStr, null);
    }

    private QueryLauncher<R> updaterQueryLauncher(Transaction txn, String queryStr) {
        QueryLauncherCache cache;
        // Need to double check the filter after joining to the primary, in case there were any
        // changes after the secondary entry was loaded. Note that no double check is needed
        // with READ_UNCOMMITTED, because the updater for it still acquires locks.
        if (!RowUtils.isUnsafe(txn) || (cache = mQueryLauncherCacheDoubleCheck) == null) {
            cache = mQueryLauncherCache;
        }
        return cache.obtain(queryStr, null);
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

    @Override
    public final Transaction newTransaction(DurabilityMode durabilityMode) {
        return mSource.newTransaction(durabilityMode);
    }

    @Override
    public final boolean isEmpty() throws IOException {
        return mSource.isEmpty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public final Comparator<R> comparator(String spec) {
        WeakCache<String, Comparator<R>, Object> cache = mComparatorCache;

        if (cache == null) {
            cache = new WeakCache<>() {
                @Override
                protected Comparator<R> newValue(String spec, Object unused) {
                    var maker = new ComparatorMaker<R>(rowType(), spec);
                    String clean = maker.cleanSpec();
                    return spec.equals(clean) ? maker.finish() : obtain(clean, null);
                }
            };

            var existing = (WeakCache<String, Comparator<R>, Object>)
                cComparatorCacheHandle.compareAndExchange(this, null, cache);

            if (existing != null) {
                cache = existing;
            }
        }

        return cache.obtain(spec, null);
    }

    @Override
    public final RowPredicate<R> predicate(String queryStr, Object... args) {
        if (queryStr == null) {
            return RowPredicate.all();
        } else {
            return mFilterFactoryCache.obtain(queryStr, null).predicate(args);
        }
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
     * permitted when using a {@link RowUpdater}.
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
     * permitted when using a {@link RowUpdater}.
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
    public QueryPlan queryPlan(Transaction txn, String queryStr, Object... args)
        throws IOException
    {
        if (queryStr == null) {
            return plan(args);
        } else {
            return scannerQueryLauncher(txn, queryStr).plan(args);
        }
    }

    /**
     * Note: Doesn't support orderBy.
     */
    final QueryPlan queryPlanThisTable(Transaction txn, String queryStr, Object... args) {
        if (queryStr == null) {
            return plan(args);
        } else {
            return scannerFilteredFactory(txn, queryStr).plan(args);
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
     * @param queryStr the parsed and reduced query string; can be null initially
     */
    @SuppressWarnings("unchecked")
    private ScanControllerFactory<R> newFilteredFactory
        (FilterFactoryCache cache, String queryStr, Query query)
    {
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
            return cache.obtain(canonical, query);
        }

        var keyColumns = rowInfo.keyColumns.values().toArray(ColumnInfo[]::new);
        RowFilter[][] ranges = multiRangeExtract(rf, keyColumns);
        splitRemainders(rowInfo, ranges);

        if (cache == mFilterFactoryCacheDoubleCheck && primaryRowGen != null) {
            doubleCheckRemainder(ranges, primaryRowGen.info);
        }

        Class<? extends RowPredicate> baseClass;

        // FIXME: Although no predicate lock is required, a row lock is required.
        if (false && ranges.length == 1 && RowFilter.matchesOne(ranges[0], keyColumns)) {
            // No predicate lock is required when the filter matches at most one row.
            baseClass = null;
        } else {
            baseClass = mIndexLock == null ? null : mIndexLock.evaluatorClass();
        }

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
            remainder = remainder.retain(rowInfo.valueColumns, false, TrueFilter.THE);
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

    @SuppressWarnings("unchecked")
    private QueryLauncher<R> newQueryLauncher(String queryStr, boolean doubleCheck)
        throws IOException
    {
        RowInfo rowInfo = RowInfo.find(rowType());
        Map<String, ColumnInfo> allColumns = rowInfo.allColumns;
        Query query = new Parser(allColumns, queryStr).parseQuery(allColumns).reduce();

        var selector = new IndexSelector(rowInfo, query);
        int num = selector.analyze();

        QueryLauncher<R> launcher;

        if (num <= 1) {
            launcher = newSubLauncher(doubleCheck, rowInfo, selector, 0);
        } else {
            var launchers = new QueryLauncher[num];
            for (int i=0; i<num; i++) {
                launchers[i] = newSubLauncher(doubleCheck, rowInfo, selector, i);
            }
            launcher = new DisjointUnionQueryLauncher<R>(launchers);
        }

        OrderBy orderBy = selector.orderBy();
        if (orderBy != null) {
            launcher = new SortedQueryLauncher<R>(this, launcher, orderBy);
        }

        return launcher;
    }

    private QueryLauncher<R> newSubLauncher(boolean doubleCheck, RowInfo rowInfo,
                                            IndexSelector selector, int i)
        throws IOException
    {
        ColumnSet subIndex = selector.selectedIndex(i);
        Query subQuery = selector.selectedQuery(i);
        String subQueryStr = subQuery.toString();

        BaseTable<R> subTable;
        if (subIndex.matches(rowInfo)) {
            subTable = this;
        } else {
            boolean alt = rowInfo.alternateKeys.contains(subIndex);
            subTable = viewIndexTable(alt, subIndex.fullSpec());
        }

        FilterFactoryCache ffc;
        if (doubleCheck) {
            ffc = subTable.mFilterFactoryCacheDoubleCheck;
        } else {
            ffc = subTable.mFilterFactoryCache;
        }

        ScanControllerFactory<R> subFactory = ffc.obtain(subQueryStr, subQuery);

        if (selector.selectedReverse(i)) {
            subFactory = subFactory.reverse();
        }

        return new ScanQueryLauncher<>(subTable, subFactory, selector.projection());
    }

    /**
     * Partially decodes a row from a key.
     */
    protected abstract R toRow(byte[] key);

    protected RowStore rowStore() throws DatabaseException {
        var rs = rowStoreRef().get();
        if (rs == null) {
            throw new DatabaseException("Closed");
        }
        return rs;
    }

    protected abstract WeakReference<RowStore> rowStoreRef();

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
     */
    protected final MethodHandle decodePartialHandle(byte[] spec, int schemaVersion) {
        WeakCache<Object, MethodHandle, byte[]> cache = mPartialDecodeCache;

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
                cPartialDecodeCacheHandle.compareAndExchange(this, null, cache);

            if (existing != null) {
                cache = existing;
            }
        }

        final Object key = schemaVersion == 0 ?
            ArrayKey.make(spec) : ArrayKey.make(schemaVersion, spec);

        return cache.obtain(key, spec);
    }

    protected abstract MethodHandle makeDecodePartialHandle(byte[] spec, int schemaVersion);

    protected final void redoPredicateMode(Transaction txn) throws IOException {
        RowPredicateLock<R> lock = mIndexLock;
        if (lock != null) {
            lock.redoPredicateMode(txn);
        }
    }

    /**
     * Called when no trigger is installed.
     */
    protected final void store(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        Index source = mSource;

        // RowPredicateLock requires a non-null transaction.
        txn = ViewUtils.enterScope(source, txn);
        try {
            redoPredicateMode(txn);
            try (RowPredicateLock.Closer closer = mIndexLock.openAcquire(txn, row)) {
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
    protected final byte[] exchange(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        Index source = mSource;

        // RowPredicateLock requires a non-null transaction.
        txn = ViewUtils.enterScope(source, txn);
        byte[] oldValue;
        try {
            redoPredicateMode(txn);
            try (RowPredicateLock.Closer closer = mIndexLock.openAcquire(txn, row)) {
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
    protected final boolean insert(Transaction txn, R row, byte[] key, byte[] value)
        throws IOException
    {
        Index source = mSource;

        // RowPredicateLock requires a non-null transaction.
        txn = ViewUtils.enterScope(source, txn);
        boolean result;
        try {
            redoPredicateMode(txn);
            try (RowPredicateLock.Closer closer = mIndexLock.openAcquire(txn, row)) {
                result = source.insert(txn, key, value);
            }
            txn.commit();
        } finally {
            txn.exit();
        }

        return result;
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
    protected Class<?> joinedPrimaryTableClass() {
        return null;
    }

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
