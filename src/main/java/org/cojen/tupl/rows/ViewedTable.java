/*
 *  Copyright (C) 2023 Cojen.org
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

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import java.util.function.Predicate;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;

import org.cojen.tupl.NoSuchRowException;
import org.cojen.tupl.Scanner;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.Updater;
import org.cojen.tupl.ViewConstraintException;

import org.cojen.tupl.core.Pair;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.rows.filter.ColumnFilter;
import org.cojen.tupl.rows.filter.ColumnToArgFilter;
import org.cojen.tupl.rows.filter.ColumnToColumnFilter;
import org.cojen.tupl.rows.filter.Parser;
import org.cojen.tupl.rows.filter.Query;
import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.TrueFilter;
import org.cojen.tupl.rows.filter.Visitor;

/**
 * Base class for view tables.
 *
 * @author Brian S. O'Neill
 * @see Table#view
 */
public abstract sealed class ViewedTable<R> extends WrappedTable<R, R> {
    private static final WeakCache<Pair<Class, String>, MethodHandle, Query> cFactoryCache;

    private static final WeakCache<Pair<Class, String>, Helper, ViewedTable> cHelperCache;

    static {
        cFactoryCache = new WeakCache<>() {
            @Override
            public MethodHandle newValue(Pair<Class, String> key, Query query) {
                Class<?> rowType = key.a();
                String queryStr = key.b();

                RowInfo rowInfo = RowInfo.find(rowType);

                if (query == null) {
                    query = new Parser(rowInfo.allColumns, queryStr).parseQuery(null);
                    String canonical = query.toString();
                    if (!canonical.equals(queryStr)) {
                        return obtain(new Pair<>(rowType, canonical), query);
                    }
                }

                try {
                    return makeTableFactory(rowType, queryStr, query);
                } catch (Throwable e) {
                    throw RowUtils.rethrow(e);
                } finally {
                    Reference.reachabilityFence(query);
                }
            }
        };

        cHelperCache = new WeakCache<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Helper newValue(Pair<Class, String> key, ViewedTable table) {
                Class rowType = key.a();
                return makeHelper(rowType, table);
            }
        };
    }

    public static <R> ViewedTable<R> view(Table<R> source, String query, Object... args) {
        Objects.requireNonNull(query);
        try {
            var key = new Pair<Class, String>(source.rowType(), query);
            return (ViewedTable<R>) cFactoryCache.obtain(key, null).invokeExact(source, args);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    /**
     * MethodHandle signature: ViewedTable<R> make(Table<R> source, Object... args)
     */
    private static MethodHandle makeTableFactory(Class<?> rowType, String queryStr, Query query)
        throws NoSuchMethodException, IllegalAccessException
    {
        RowInfo info = RowInfo.find(rowType);

        Class<?> viewedClass;

        Set<String> projected = query.projection() == null ? null : query.projection().keySet();

        if (projected == null || projected.containsAll(info.allColumns.keySet())) {
            // All columns are projected.
            if (query.filter() == TrueFilter.THE) {
                viewedClass = NoFilter.class;
            } else {
                viewedClass = HasFilter.class;
            }
        } else if (projected.containsAll(info.keyColumns.keySet())) {
            // All primary key columns are projected.
            if (query.filter() == TrueFilter.THE) {
                viewedClass = HasProjectionAndNoFilter.class;
            } else {
                viewedClass = HasProjectionAndHasFilter.class;
            }
        } else {
            // No primary key columns are projected, and so no CRUD operations work.
            if (query.filter() == TrueFilter.THE) {
                viewedClass = NoPrimaryKeyAndNoFilter.class;
            } else {
                viewedClass = NoPrimaryKeyAndHasFilter.class;
            }
        }

        MethodType ctorType = MethodType.methodType
            (void.class, String.class, SoftReference.class, int.class, Table.class, Object[].class);
        MethodHandle mh = MethodHandles.lookup().findConstructor(viewedClass, ctorType);
        int maxArg = query.filter().maxArgument();
        var queryRef = new SoftReference<Query>(query);
        mh = MethodHandles.insertArguments(mh, 0, queryStr, queryRef, maxArg);
        return mh.asType(MethodType.methodType(ViewedTable.class, Table.class, Object[].class));
    }

    private static final Object[] NO_ARGS = new Object[0];

    protected final String mQueryStr;
    protected final int mMaxArg;
    protected final Object[] mArgs;

    private SoftReference<Query> mQueryRef;

    private volatile SoftCache<String, String, Object> mFusedQueries;
    private static final VarHandle cFusedQueriesHandle;

    private Helper<R> mHelper;
    private static final VarHandle cHelperHandle;

    static {
        try {
            var lookup = MethodHandles.lookup();
            cFusedQueriesHandle = lookup.findVarHandle
                (ViewedTable.class, "mFusedQueries", SoftCache.class);
            cHelperHandle = lookup.findVarHandle(ViewedTable.class, "mHelper", Helper.class);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    protected ViewedTable(String queryStr, SoftReference<Query> queryRef, int maxArg,
                          Table<R> source, Object... args)
    {
        super(source);

        if (maxArg == 0) {
            args = NO_ARGS;
        } else {
            if (args.length < maxArg) {
                throw new IllegalArgumentException("Not enough query arguments provided");
            }
            var copy = new Object[maxArg];
            System.arraycopy(args, 0, copy, 0, copy.length);
            args = copy;
        }

        mQueryStr = queryStr;
        mMaxArg = maxArg;
        mArgs = args;

        mQueryRef = queryRef;
    }

    @Override
    public Class<R> rowType() {
        return mSource.rowType();
    }

    @Override
    public R newRow() {
        return mSource.newRow();
    }

    @Override
    public R cloneRow(R row) {
        return mSource.cloneRow(row);
    }

    @Override
    public void unsetRow(R row) {
        mSource.unsetRow(row);
    }

    @Override
    public void cleanRow(R row) {
        mSource.cleanRow(row);
    }

    @Override
    public void copyRow(R from, R to) {
        mSource.copyRow(from, to);
    }

    @Override
    public boolean isSet(R row, String name) {
        return mSource.isSet(row, name);
    }

    @Override
    public final Scanner<R> newScannerWith(Transaction txn, R row) throws IOException {
        return mSource.newScannerWith(txn, row, mQueryStr, mArgs);
    }

    @Override
    public final Scanner<R> newScannerWith(Transaction txn, R row, String query, Object... args)
        throws IOException
    {
        return mSource.newScannerWith(txn, row, fuseQuery(query), fuseArguments(args));
    }

    @Override
    public Updater<R> newUpdater(Transaction txn) throws IOException {
        return new CheckedUpdater<>(helper(), mSource.newUpdater(txn, mQueryStr, mArgs));
    }

    @Override
    public Updater<R> newUpdater(Transaction txn, String query, Object... args)
        throws IOException
    {
        return new CheckedUpdater<>
            (helper(), mSource.newUpdater(txn, fuseQuery(query), fuseArguments(args)));
    }

    @Override
    public long deleteAll(Transaction txn, String query, Object... args) throws IOException {
        return mSource.deleteAll(txn, fuseQuery(query), fuseArguments(args));
    }

    @Override
    public final Table<R> view(String query, Object... args) {
        return mSource.view(fuseQuery(query), fuseArguments(args));
    }

    @Override
    public final QueryPlan scannerPlan(Transaction txn, String query, Object... args)
        throws IOException
    {
        return mSource.scannerPlan(txn, fuseQuery(query), fuseArguments(args));
    }

    @Override
    public final QueryPlan updaterPlan(Transaction txn, String query, Object... args)
        throws IOException
    {
        return mSource.updaterPlan(txn, fuseQuery(query), fuseArguments(args));
    }

    /**
     * Returns this view's query specification.
     */
    protected final Query query() {
        Query query = mQueryRef.get();

        if (query == null) {
            query = new Parser(RowInfo.find(rowType()).allColumns, mQueryStr).parseQuery(null);
            mQueryRef = new SoftReference<>(query);
        }

        return query;
    }

    /**
     * Fuse this view's query specification with the one given. The arguments of the given
     * query are incremented by mMaxArg.
     */
    protected String fuseQuery(String queryStr) {
        if (queryStr == null) {
            return mQueryStr;
        }

        SoftCache<String, String, Object> cache = mFusedQueries;

        if (cache == null) {
            cache = new SoftCache<>() {
                @Override
                public String newValue(String queryStr, Object unused) {
                    return doFuseQuery(queryStr).toString();
                }
            };

            var actual = (SoftCache<String, String, Object>)
                cFusedQueriesHandle.compareAndExchange(this, null, cache);

            if (actual != null) {
                cache = actual;
            }
        }

        return cache.obtain(queryStr, null);
    }

    private Query doFuseQuery(String queryStr) {
        Query thisQuery = query();
        Map<String, ? extends ColumnInfo> allColumns = thisQuery.projection();

        if (allColumns == null) {
            allColumns = RowInfo.find(rowType()).allColumns;
        }

        Query query = new Parser(allColumns, queryStr, mMaxArg).parseQuery(null);

        Map<String, ColumnInfo> projection = query.projection();

        if (projection == null) {
            projection = thisQuery.projection();
        }

        OrderBy orderBy = query.orderBy();
        if (orderBy == null && thisQuery.orderBy() != null) {
            orderBy = thisQuery.orderBy().truncate(projection);
        }

        RowFilter filter = thisQuery.filter().and(query.filter());

        return new Query(projection, orderBy, filter);
    }

    /**
     * Used in conjunction with fuseQuery.
     */
    protected Object[] fuseArguments(Object... args) {
        if (args == null || args.length == 0) {
            return mArgs;
        } else if (mMaxArg == 0) {
            return args;
        } else {
            var newArgs = new Object[mMaxArg + args.length];
            System.arraycopy(mArgs, 0, newArgs, 0, mArgs.length);
            System.arraycopy(args, 0, newArgs, mMaxArg, args.length);
            return newArgs;
        }
    }

    /**
     * Fuse this view's query specification with a filter against the primary key columns.
     *
     * @param emptyProjection pass true to project no columns
     */
    private String fuseQueryWithPk(boolean emptyProjection) {
        Query query = query();
        RowFilter filter = query.filter();
        int argNum = mMaxArg;
        for (ColumnInfo column : RowInfo.find(rowType()).keyColumns.values()) {
            filter = filter.and(new ColumnToArgFilter(column, ColumnFilter.OP_EQ, ++argNum));
        }
        query = query.withFilter(filter);
        if (emptyProjection) {
            query = query.withProjection(Collections.emptyMap());
        }
        return query.toString();
    }

    /**
     * Returns true if the view filter only accesses columns from the primary key.
     */
    private boolean isPkFilter() {
        Map<String, ColumnInfo> pkColumns = RowInfo.find(rowType()).keyColumns;

        var visitor = new Visitor() {
            boolean mPkOnly = true;

            @Override
            public void visit(ColumnToArgFilter filter) {
                check(filter.column());
            }

            @Override
            public void visit(ColumnToColumnFilter filter) {
                check(filter.column());
                check(filter.otherColumn());
            }

            private void check(ColumnInfo column) {
                mPkOnly &= pkColumns.containsKey(column.name);
            }
        };

        query().filter().accept(visitor);

        return visitor.mPkOnly;
    }

    public static ViewConstraintException projectionConstraint() {
        return new ViewConstraintException("Restricted by view projection");
    }

    public static ViewConstraintException filterConstraint() {
        return new ViewConstraintException("Restricted by view filter");
    }

    protected final Helper<R> helper() {
        Helper<R> helper = mHelper;
        if (helper == null) {
            helper = newHelper();
        }
        return helper;
    }

    @SuppressWarnings("unchecked")
    private Helper<R> newHelper() {
        var helper = (Helper<R>) cHelperHandle.getVolatile(this);

        if (helper == null) {
            var key = new Pair<Class, String>(rowType(), mQueryStr);
            try {
                helper = (Helper<R>) cHelperCache.obtain(key, this);
            } catch (Throwable e) {
                throw RowUtils.rethrow(e);
            }
            var actual = (Helper<R>) cHelperHandle.compareAndExchange(this, null, helper);
            if (actual != null) {
                helper = actual;
            }
        }

        return helper;
    }

    /**
     * MethodHandle signature: Helper<R> make(ViewedTable<R> table)
     *
     * @param table used to obtain the Query
     */
    private static <R> Helper<R> makeHelper(Class<R> rowType, ViewedTable<R> table) {
        RowInfo info = RowInfo.find(rowType);
        Map<String, ColumnInfo> keyColumns = info.keyColumns;
        RowGen rowGen = info.rowGen();
        Class rowClass = RowMaker.find(rowType);

        ClassMaker cm = rowGen.beginClassMaker(ViewedTable.class, rowType, "view");
        cm.final_().extend(Helper.class);

        // Keep a singleton instance, in order for the weakly cached reference to the
        // MethodHandle to stick around until the class is unloaded.
        cm.addField(Object.class, "_").private_().static_();

        MethodMaker mm = cm.addConstructor().private_();

        // Implement the protected methods.

        if (table.isPkFilter()) {
            cm.addMethod(boolean.class, "isPkFilter").protected_().override().return_(true);
        }

        Query query = table.query();

        // Implement the various check* methods.
        for (int which = 1; which <= 3; which++) {
            String methodName;
            Map<String, ColumnInfo> projection = query.projection();
            RowFilter filter = query.filter();

            switch (which) {
            default: throw new AssertionError();
            case 1:
                methodName = "checkFull";
                break;
            case 2:
                methodName = "checkPk";
                projection = null;
                filter = filter.retain(keyColumns::containsKey, true, TrueFilter.THE);
                break;
            case 3:
                methodName = "checkUpdate";
                if (projection != null) {
                    filter = filter.retain(projection::containsKey, true, TrueFilter.THE);
                }
                break;
            }

            if (projection == null && filter == TrueFilter.THE) {
                // Don't need to override if nothing is checked.
                continue;
            }

            mm = cm.addMethod(null, methodName, Object.class).protected_().override();
            var rowVar = mm.param(0);

            if (projection != null) {
                var disallowed = new HashMap<>(info.allColumns);
                for (String name : projection.keySet()) {
                    disallowed.remove(name);
                }
                rowVar = rowVar.cast(rowClass);
                var resultVar = mm.var(boolean.class);
                rowGen.checkAnySet(mm, disallowed, resultVar, rowVar);
                Label pass = mm.label();
                resultVar.ifFalse(pass);
                mm.var(ViewedTable.class).invoke("projectionConstraint").throw_();
                pass.here();
            }

            if (filter != TrueFilter.THE) {
                var predicateVar = mm.var(Predicate.class)
                    .setExact(PlainPredicateMaker.predicate(rowType, filter, table.mArgs));
                Label pass = mm.label();
                predicateVar.invoke("test", rowVar).ifTrue(pass);
                mm.var(ViewedTable.class).invoke("filterConstraint").throw_();
                pass.here();
            }
        }

        {
            mm = cm.addMethod(Object[].class, "fusePkArguments",
                              Object[].class, int.class, Object.class).protected_().override();
            var argsVar = mm.param(0);
            var maxArg = mm.param(1);
            var rowVar = mm.param(2).cast(rowType);

            argsVar = mm.var(Arrays.class).invoke("copyOf", argsVar, maxArg.add(keyColumns.size()));

            for (String name : keyColumns.keySet()) {
                argsVar.aset(maxArg, rowVar.invoke(name));
                maxArg.inc(1);
            }

            mm.return_(argsVar);
        }

        {
            mm = cm.addMethod(null, "unsetValueColumns", Object.class).protected_().override();
            var rowVar = mm.param(0).cast(rowClass);
            TableMaker.markUnset(rowVar, rowGen, keyColumns);
        }

        {
            mm = cm.addMethod(null, "copyNonPkDirtyColumns", Object.class, Object.class)
                .protected_().override();
            var fromVar = mm.param(0).cast(rowClass);
            var toVar = mm.param(1).cast(rowClass);
            // This rest of this method might be large, so generate upon first use.
            var indy = mm.var(ViewedTable.class).indy("indyCopyNonPkDirtyColumns", rowType);
            indy.invoke(null, "copyNonPkDirtyColumns", null, fromVar, toVar);
        }

        MethodHandles.Lookup lookup = cm.finishHidden();
        Class<?> helperClass = lookup.lookupClass();

        try {
            var helper = (Helper<R>) lookup.findConstructor
                (helperClass, MethodType.methodType(void.class)).invoke();

            // Assign the singleton reference.
            lookup.findStaticVarHandle(helperClass, "_", Object.class).set(helper);

            return helper;
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    public static CallSite indyCopyNonPkDirtyColumns(MethodHandles.Lookup lookup, String name,
                                                     MethodType type, Class<?> rowType)
    {
        RowInfo info = RowInfo.find(rowType);
        RowGen rowGen = info.rowGen();
        Map<String, Integer> columnNumbers = rowGen.columnNumbers();

        MethodMaker mm = MethodMaker.begin(lookup, name, type);

        var fromVar = mm.param(0);
        var toVar = mm.param(1);

        for (ColumnInfo column : info.valueColumns.values()) {
            String colName = column.name;
            int num = columnNumbers.get(colName);
            var stateVar = fromVar.field(rowGen.stateField(num)).get();
            int sfMask = RowGen.stateFieldMask(num);
            Label cont = mm.label();
            stateVar.and(sfMask).ifNe(sfMask, cont);
            toVar.invoke(colName, fromVar.field(colName));
            cont.here();
        }

        return new ConstantCallSite(mm.finish());
    }

    /**
     * Base class for generated code. Although it's possible to fold these methods into
     * ViewedTable and generate ViewedTable subclasses, the classes shouldn't be hidden. Hidden
     * classes are more likely to be unloaded when not needed anymore. If the table classes
     * were generated as hidden, it would lead to confusing stack traces.
     */
    public static abstract class Helper<R> {
        private String mFusedPkQuery, mFusedPkQueryEmptyProjection;

        protected Helper() {
        }

        // Note: The checks performed by this class have a slight weakness in that they assume
        // no thread is concurrently modifying the row which is being acted upon. Copying the
        // row (and any array columns) will help. Also see CheckedUpdater.

        /**
         * Loads the row using a Scanner against a query filter which fuses the view's query
         * specification and the row's primary key columns.
         */
        final boolean load(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            checkPk(row);

            // Note that there's no quick option (unlike `exists` or `delete`) because a
            // projection might need to be applied when loading the row. The caller of this method
            // is responsible for implementing the quick option if all columns are projected.

            String query = fusedPkQuery(table);
            Object[] args = fusePkArguments(table.mArgs, table.mMaxArg, row);

            try (var scanner = table.mSource.newScanner(txn, query, args)) {
                R found = scanner.row();
                if (found == null) {
                    unsetValueColumns(row);
                    return false;
                } else {
                    table.copyRow(found, row);
                    return true;
                }
            }
        }

        /**
         * Checks for row existence using a Scanner against a query filter which fuses the
         * view's query specification and the row's primary key columns.
         */
        final boolean exists(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            checkPk(row);

            if (isPkFilter()) { // quick option
                return table.mSource.exists(txn, row);
            }

            // Use an empty projection because the columns don't need to be decoded.
            String query = fusedPkQueryEmptyProjection(table);
            Object[] args = fusePkArguments(table.mArgs, table.mMaxArg, row);

            try (var scanner = table.mSource.newScanner(txn, query, args)) {
                return scanner.row() != null;
            }
        }

        final void store(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            checkFull(row);
            table.mSource.store(txn, row);
        }

        final R exchange(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            checkFull(row);
            return table.mSource.exchange(txn, row);
        }

        final void insert(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            checkFull(row);
            table.mSource.insert(txn, row);
        }

        final void replace(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            checkFull(row);
            table.mSource.replace(txn, row);
        }

        final void update(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            doUpdate(table, txn, row, false);
        }

        /**
         * Updates the row using an Updater against a query filter which fuses the view's query
         * specification and the row's primary key columns.
         */
        final void merge(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            doUpdate(table, txn, row, true);
        }

        private void doUpdate(ViewedTable<R> table, Transaction txn, R row, boolean copyBack)
            throws IOException
        {
            // Only check the primary key at first because it's required. Ideally, I'd also
            // check the columns which are set, but this requires a custom filter for each
            // permutation. I can't call checkUpdate yet because the update method doesn't
            // require that all columns within the projection be set.
            checkPk(row);

            String query = fusedPkQuery(table);
            Object[] args = fusePkArguments(table.mArgs, table.mMaxArg, row);

            try (var updater = table.mSource.newUpdater(txn, query, args)) {
                R found = updater.row();
                if (found == null) {
                    // It's possible that the row wasn't found because it was filtered out or
                    // because it doesn't truly exist. Determining if a ViewConstraintException
                    // should be thrown is tricky. See checkPk comment above.
                    throw new NoSuchRowException();
                }
                copyNonPkDirtyColumns(row, found);
                checkUpdate(found);
                updater.update();
                if (copyBack) {
                    table.copyRow(found, row);
                }
                table.cleanRow(row);
            }
        }

        /**
         * Deletes the row using an Updater against a query filter which fuses the view's query
         * specification and the row's primary key columns.
         */
        final boolean delete(ViewedTable<R> table, Transaction txn, R row) throws IOException {
            checkPk(row);

            if (isPkFilter()) { // quick option
                return table.mSource.delete(txn, row);
            }

            String query = fusedPkQueryEmptyProjection(table);
            Object[] args = fusePkArguments(table.mArgs, table.mMaxArg, row);

            return table.mSource.deleteAll(txn, query, args) != 0;
        }

        private String fusedPkQuery(ViewedTable<R> table) {
            String query = mFusedPkQuery;
            if (query == null) {
                mFusedPkQuery = query = table.fuseQueryWithPk(false);
            }
            return query;
        }

        private String fusedPkQueryEmptyProjection(ViewedTable<R> table) {
            String query = mFusedPkQueryEmptyProjection;
            if (query == null) {
                mFusedPkQueryEmptyProjection = query = table.fuseQueryWithPk(true);
            }
            return query;
        }

        /**
         * Returns true if the view filter only accesses columns from the primary key.
         */
        protected boolean isPkFilter() {
            return false;
        }

        /**
         * Checks the view projection and filter, throwing an exception if the given row cannot
         * be written to the view.
         */
        protected void checkFull(R row) throws ViewConstraintException {
        }

        /**
         * Checks a filter which only considers columns within the primary key, and ignores the
         * projection.
         */
        protected void checkPk(R row) throws ViewConstraintException {
        }

        /**
         * Checks the view projection and a filter which only considers columns within the
         * projection.
         */
        protected void checkUpdate(R row) throws ViewConstraintException {
        }

        /**
         * Used in conjunction with fuseQueryWithPk.
         */
        protected abstract Object[] fusePkArguments(Object[] args, int maxArg, R row);
        
        protected abstract void unsetValueColumns(R row);

        protected abstract void copyNonPkDirtyColumns(R from, R to);
    }

    private static final class CheckedUpdater<R> implements Updater<R> {
        private final Helper<R> mHelper;
        private final Updater<R> mUpdater;

        CheckedUpdater(Helper<R> helper, Updater<R> updater) {
            mHelper = helper;
            mUpdater = updater;
        }

        // Note: The checks performed by this class have a slight weakness in that they assume
        // no thread is concurrently modifying the row which is being acted upon. A secure (and
        // efficient) design isn't obvious.

        @Override
        public R row() {
            return mUpdater.row();
        }

        @Override
        public R step() throws IOException {
            return mUpdater.step();
        }

        @Override
        public R step(R row) throws IOException {
            return mUpdater.step(row);
        }

        @Override
        public R update() throws IOException {
            checkUpdate();
            return mUpdater.update();
        }

        @Override
        public R update(R row) throws IOException {
            checkUpdate();
            return mUpdater.update(row);
        }

        @Override
        public R delete() throws IOException {
            checkUpdate();
            return mUpdater.delete();
        }

        @Override
        public R delete(R row) throws IOException {
            checkUpdate();
            return mUpdater.delete(row);
        }

        @Override
        public long estimateSize() {
            return mUpdater.estimateSize();
        }

        @Override
        public int characteristics() {
            return mUpdater.characteristics();
        }

        @Override
        public void close() throws IOException {
            mUpdater.close();
        }

        private void checkUpdate() throws IOException {
            try {
                mHelper.checkUpdate(mUpdater.row());
            } catch (Throwable e) {
                try {
                    close();
                } catch (Throwable e2) {
                    RowUtils.suppress(e, e2);
                }
                throw e;
            }
        }
    }

    /**
     * This class is used when all columns are available and only a natural ordering is required.
     */
    private static sealed class NoFilter<R> extends ViewedTable<R> {
        NoFilter(String queryStr, SoftReference<Query> queryRef, int maxArg,
                 Table<R> source, Object... args)
        {
            super(queryStr, queryRef, maxArg, source, args);
        }

        @Override
        public Updater<R> newUpdater(Transaction txn) throws IOException {
            return mSource.newUpdater(txn, mQueryStr, mArgs);
        }

        @Override
        public Updater<R> newUpdater(Transaction txn, String query, Object... args)
            throws IOException
        {
            return mSource.newUpdater(txn, fuseQuery(query), fuseArguments(args));
        }

        @Override
        public boolean load(Transaction txn, R row) throws IOException {
            return mSource.load(txn, row);
        }

        @Override
        public boolean exists(Transaction txn, R row) throws IOException {
            return mSource.exists(txn, row);
        }

        @Override
        public void store(Transaction txn, R row) throws IOException {
            mSource.store(txn, row);
        }

        @Override
        public R exchange(Transaction txn, R row) throws IOException {
            return mSource.exchange(txn, row);
        }

        @Override
        public void insert(Transaction txn, R row) throws IOException {
            mSource.insert(txn, row);
        }

        @Override
        public void replace(Transaction txn, R row) throws IOException {
            mSource.replace(txn, row);
        }

        @Override
        public void update(Transaction txn, R row) throws IOException {
            mSource.update(txn, row);
        }

        @Override
        public void merge(Transaction txn, R row) throws IOException {
            mSource.merge(txn, row);
        }

        @Override
        public boolean delete(Transaction txn, R row) throws IOException {
            return mSource.delete(txn, row);
        }
    }

    /**
     * This class is used when all columns are available and a filter is required.
     */
    private static sealed class HasFilter<R> extends ViewedTable<R> {
        HasFilter(String queryStr, SoftReference<Query> queryRef, int maxArg,
                  Table<R> source, Object... args)
        {
            super(queryStr, queryRef, maxArg, source, args);
        }

        @Override
        public boolean load(Transaction txn, R row) throws IOException {
            Helper<R> helper = helper();
            if (helper.isPkFilter()) { // quick option (all columns are projected)
                helper.checkPk(row);
                return mSource.load(txn, row);
            } else {
                return helper.load(this, txn, row);
            }
        }

        @Override
        public boolean exists(Transaction txn, R row) throws IOException {
            return helper().exists(this, txn, row);
        }

        @Override
        public void store(Transaction txn, R row) throws IOException {
            helper().store(this, txn, row);
        }

        @Override
        public R exchange(Transaction txn, R row) throws IOException {
            return helper().exchange(this, txn, row);
        }

        @Override
        public void insert(Transaction txn, R row) throws IOException {
            helper().insert(this, txn, row);
        }

        @Override
        public void replace(Transaction txn, R row) throws IOException {
            helper().replace(this, txn, row);
        }

        @Override
        public void update(Transaction txn, R row) throws IOException {
            helper().update(this, txn, row);
        }

        @Override
        public void merge(Transaction txn, R row) throws IOException {
            helper().merge(this, txn, row);
        }

        @Override
        public boolean delete(Transaction txn, R row) throws IOException {
            return helper().delete(this, txn, row);
        }
    }

    /**
     * This class is used when a subset of columns is available (but includes the primary key)
     * and no rows are filtered. CRUD operations which require all columns aren't supported.
     */
    private static sealed class HasProjectionAndNoFilter<R> extends NoFilter<R> {
        HasProjectionAndNoFilter(String queryStr, SoftReference<Query> queryRef, int maxArg,
                                 Table<R> source, Object... args)
        {
            super(queryStr, queryRef, maxArg, source, args);
        }

        @Override
        public boolean load(Transaction txn, R row) throws IOException {
            return helper().load(this, txn, row);
        }

        // Inherited from NoFilter class (just calls mSource.exists).
        //public boolean exists(Transaction txn, R row) throws IOException

        @Override
        public final void store(Transaction txn, R row) throws IOException {
            // Requires all columns.
            throw projectionConstraint();
        }

        @Override
        public final R exchange(Transaction txn, R row) throws IOException {
            // Requires all columns.
            throw projectionConstraint();
        }

        @Override
        public final void insert(Transaction txn, R row) throws IOException {
            // Requires all columns.
            throw projectionConstraint();
        }

        @Override
        public final void replace(Transaction txn, R row) throws IOException {
            // Requires all columns.
            throw projectionConstraint();
        }

        @Override
        public void update(Transaction txn, R row) throws IOException {
            helper().update(this, txn, row);
        }

        @Override
        public void merge(Transaction txn, R row) throws IOException {
            helper().merge(this, txn, row);
        }

        // Inherited from NoFilter class (just calls mSource.delete).
        //public boolean delete(Transaction txn, R row) throws IOException
    }

    /**
     * This class is used when a subset of columns is available (but includes the primary key)
     * and a filter is required. CRUD operations which require all columns aren't supported.
     */
    private static sealed class HasProjectionAndHasFilter<R> extends HasFilter<R> {
        HasProjectionAndHasFilter(String queryStr, SoftReference<Query> queryRef, int maxArg,
                                  Table<R> source, Object... args)
        {
            super(queryStr, queryRef, maxArg, source, args);
        }

        @Override
        public boolean load(Transaction txn, R row) throws IOException {
            return helper().load(this, txn, row);
        }

        // Inherited from HasFilter class (just calls the helper).
        //public boolean exists(Transaction txn, R row) throws IOException

        @Override
        public final void store(Transaction txn, R row) throws IOException {
            // Requires all columns.
            throw projectionConstraint();
        }

        @Override
        public final R exchange(Transaction txn, R row) throws IOException {
            // Requires all columns.
            throw projectionConstraint();
        }

        @Override
        public final void insert(Transaction txn, R row) throws IOException {
            // Requires all columns.
            throw projectionConstraint();
        }

        @Override
        public final void replace(Transaction txn, R row) throws IOException {
            // Requires all columns.
            throw projectionConstraint();
        }

        // Inherited from HasFilter class (just calls the helper).
        //public void update(Transaction txn, R row) throws IOException

        // Inherited from HasFilter class (just calls the helper).
        //public void merge(Transaction txn, R row) throws IOException

        // Inherited from HasFilter class (just calls the helper).
        //public boolean delete(Transaction txn, R row) throws IOException
    }

    /**
     * This class is used when the primary key columns aren't available and no rows are filtered.
     * No CRUD operations are supported.
     */
    private static final class NoPrimaryKeyAndNoFilter<R> extends HasProjectionAndNoFilter<R> {
        NoPrimaryKeyAndNoFilter(String queryStr, SoftReference<Query> queryRef, int maxArg,
                                Table<R> source, Object... args)
        {
            super(queryStr, queryRef, maxArg, source, args);
        }

        @Override
        public boolean load(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }

        @Override
        public boolean exists(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }

        // Inherited from HasProjectionAndNoFilter class (always throws ViewConstraintException).
        //public void store(Transaction txn, R row) throws IOException

        // Inherited from HasProjectionAndNoFilter class (always throws ViewConstraintException).
        //public R exchange(Transaction txn, R row) throws IOException

        // Inherited from HasProjectionAndNoFilter class (always throws ViewConstraintException).
        //public void insert(Transaction txn, R row) throws IOException

        // Inherited from HasProjectionAndNoFilter class (always throws ViewConstraintException).
        //public final void replace(Transaction txn, R row) throws IOException

        @Override
        public void update(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }

        @Override
        public void merge(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }

        @Override
        public boolean delete(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }
    }

    /**
     * This class is used when the primary key columns aren't available and a filter is required.
     * No CRUD operations are supported.
     */
    private static final class NoPrimaryKeyAndHasFilter<R> extends HasProjectionAndHasFilter<R> {
        NoPrimaryKeyAndHasFilter(String queryStr, SoftReference<Query> queryRef, int maxArg,
                                 Table<R> source, Object... args)
        {
            super(queryStr, queryRef, maxArg, source, args);
        }

        @Override
        public boolean load(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }

        @Override
        public boolean exists(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }

        // Inherited from HasProjectionAndHasFilter class (always throws ViewConstraintException).
        //public void store(Transaction txn, R row) throws IOException

        // Inherited from HasProjectionAndHasFilter class (always throws ViewConstraintException).
        //public R exchange(Transaction txn, R row) throws IOException

        // Inherited from HasProjectionAndHasFilter class (always throws ViewConstraintException).
        //public void insert(Transaction txn, R row) throws IOException

        // Inherited from HasProjectionAndHasFilter class (always throws ViewConstraintException).
        //public void replace(Transaction txn, R row) throws IOException

        @Override
        public void update(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }

        @Override
        public void merge(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }

        @Override
        public boolean delete(Transaction txn, R row) throws IOException {
            // Requires primary key columns.
            throw projectionConstraint();
        }
    }
}