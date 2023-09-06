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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import java.util.function.Predicate;
import java.util.function.Supplier;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.Grouper;
import org.cojen.tupl.Scanner;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.Updater;

import org.cojen.tupl.core.Pair;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.rows.filter.ComplexFilterException;
import org.cojen.tupl.rows.filter.Parser;
import org.cojen.tupl.rows.filter.Query;
import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.TrueFilter;

import static java.util.Spliterator.*;

/**
 * Base class for generated grouped tables.
 *
 * @author Brian S. O'Neill
 * @see Table#group
 */
public abstract class GroupedTable<S, T> implements Table<T> {
    private static final WeakCache<Pair<Class, Class>, MethodHandle, Table> cFactoryCache;

    static {
        cFactoryCache = new WeakCache<>() {
            @Override
            public MethodHandle newValue(Pair<Class, Class> key, Table source) {
                return makeTableFactory(source, key.a(), key.b());
            }
        };
    }

    public static <S, T> GroupedTable<S, T> group(Table<S> source, Class<T> targetType,
                                                  Supplier<Grouper<S, T>> supplier)
    {
        Objects.requireNonNull(targetType);
        Objects.requireNonNull(supplier);
        try {
            var key = new Pair<Class, Class>(source.rowType(), targetType);
            return (GroupedTable<S, T>) cFactoryCache.obtain(key, source)
                .invokeExact(source, supplier);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    /**
     * MethodHandle signature: GroupedTable<S, T> make(Table<S> source, Supplier<Grouper<S, T>>)
     */
    private static MethodHandle makeTableFactory(Table<?> source, Class<?> sourceType,
                                                 Class<?> targetType)
    {
        assert source.rowType() == sourceType;

        RowInfo targetInfo = RowInfo.find(targetType);

        // Verify that the target primary key refers to source columns exactly.
        if (!targetInfo.keyColumns.isEmpty()) {
            RowInfo sourceInfo = RowInfo.find(source.rowType());
            for (ColumnInfo targetColumn : targetInfo.keyColumns.values()) {
                String name = targetColumn.name;
                ColumnInfo sourceColumn = sourceInfo.allColumns.get(name);
                if (sourceColumn == null) {
                    throw new IllegalArgumentException
                        ("Target primary key refers to a column which doesn't exist: " + name);
                }
                if (!sourceColumn.isCompatibleWith(targetColumn)) {
                    throw new IllegalArgumentException
                        ("Target primary key refers to an incompatible source column: " + name);
                }
            }
        }

        ClassMaker cm = targetInfo.rowGen().beginClassMaker
            (GroupedTable.class, targetType, "grouped").final_()
            .extend(GroupedTable.class).implement(TableBasicsMaker.find(targetType));

        {
            MethodMaker ctor = cm.addConstructor(Table.class, Supplier.class).private_();
            ctor.invokeSuperConstructor(ctor.param(0), ctor.param(1));
        }

        // Keep a reference to the MethodHandle instance, to prevent it from being garbage
        // collected as long as the generated table class still exists.
        cm.addField(MethodHandle.class, "_").static_().private_();

        // Add the compareSourceRows method.
        {
            MethodMaker mm = cm.addMethod
                (int.class, "compareSourceRows", Object.class, Object.class).protected_();

            if (targetInfo.keyColumns.isEmpty()) {
                mm.return_(0);
            } else {
                var spec = new StringBuilder();
                for (String name : targetInfo.keyColumns.keySet()) {
                    spec.append('+').append(name);
                }
                var cmpVar = mm.var(Comparator.class).setExact(source.comparator(spec.toString()));
                mm.return_(cmpVar.invoke("compare", mm.param(0), mm.param(1)));
            }
        }

        // Add the finishTarget method.
        {
            MethodMaker mm = cm.addMethod
                (null, "finishTarget", Object.class, Object.class).protected_();

            if (targetInfo.keyColumns.isEmpty()) {
                mm.invoke("cleanRow", mm.param(1));
            } else {
                var sourceVar = mm.param(0).cast(sourceType);
                var targetVar = mm.param(1).cast(targetType);
                for (String name : targetInfo.keyColumns.keySet()) {
                    targetVar.invoke(name, sourceVar.invoke(name));
                }
                mm.invoke("cleanRow", targetVar);
            }
        }

        // Override additional methods determined by whether or not the target row has a
        // primary key.

        if (targetInfo.keyColumns.isEmpty()) {
            cm.addMethod(long.class, "estimateSize").protected_().override().return_(1L);

            cm.addMethod(int.class, "characteristics", Scanner.class)
                .protected_().override().return_(DISTINCT | NONNULL);
        } else {
            // Define a method to check if the primary key is set, to be used by the load and
            // exists methods.
            Class<?> targetClass = RowMaker.find(targetType);
            {
                MethodMaker mm = cm.addMethod
                    (boolean.class, "checkPrimaryKeySet", targetClass).private_().static_();
                targetInfo.rowGen().checkSet(mm, targetInfo.keyColumns, mm.param(0));
            }

            // Override the load method.
            {
                MethodMaker mm = cm.addMethod
                    (boolean.class, "load", Transaction.class, Object.class).public_().override();

                Variable txnVar = mm.param(0);
                Variable rowVar = mm.param(1).cast(targetClass);

                Label ready = mm.label();
                mm.invoke("checkPrimaryKeySet", rowVar).ifTrue(ready);
                mm.new_(IllegalStateException.class, "Primary key isn't fully specified").throw_();
                ready.here();

                var argsVar = mm.new_(Object[].class, targetInfo.keyColumns.size());

                int argNum = 0;
                for (String name : targetInfo.keyColumns.keySet()) {
                    argsVar.aset(argNum++, rowVar.field(name));
                }

                String queryString = queryString(targetInfo.keyColumns.values());

                var scannerVar = mm.invoke("newScannerWith", txnVar, rowVar, queryString, argsVar);
                var foundVar = scannerVar.invoke("row").ne(null);
                scannerVar.invoke("close");

                Label cont = mm.label();
                foundVar.ifTrue(cont);
                targetInfo.rowGen().markNonPrimaryKeyColumnsUnset(rowVar);
                cont.here();
                mm.return_(foundVar);
            }

            // Override the exists method. Note that the implementation just calls the load
            // method, and so there's no performance advantage. In theory, a different query
            // could be used which doesn't project any columns, but it won't make a real
            // difference because the projection implementation just unsets the columns rather
            // than prevent them from being materialized in the first place.
            {
                MethodMaker mm = cm.addMethod
                    (boolean.class, "exists", Transaction.class, Object.class).public_().override();
                mm.return_(mm.invoke("load", mm.param(0), mm.invoke("cloneRow", mm.param(1))));
            }
        }

        MethodHandles.Lookup lookup = cm.finishLookup();
        Class<?> tableClass = lookup.lookupClass();

        MethodMaker mm = MethodMaker.begin
            (lookup, GroupedTable.class, null, Table.class, Supplier.class);
        mm.return_(mm.new_(tableClass, mm.param(0), mm.param(1)));

        MethodHandle mh = mm.finish();

        try {
            // Assign the singleton reference.
            lookup.findStaticVarHandle(tableClass, "_", MethodHandle.class).set(mh);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }

        return mh;
    }

    private static String groupByString(Collection<ColumnInfo> columns) {
        var bob = new StringBuilder();

        for (ColumnInfo column : columns) {
            if (bob.length() != 0) {
                bob.append(", ");
            }
            bob.append(column.isDescending() ? '-' : '+');
            if (column.isNullLow()) {
                bob.append('!');
            }
            bob.append(column.name);
        }

        return bob.toString();
    }

    private static String queryString(Collection<ColumnInfo> columns) {
        var bob = new StringBuilder();

        for (ColumnInfo column : columns) {
            if (bob.length() != 0) {
                bob.append(" && ");
            }
            bob.append(column.name).append(" == ?");
        }

        return bob.toString();
    }

    protected final Table<S> mSource;
    protected final Supplier<Grouper<S, T>> mGrouperSupplier;

    // Cache key is either a query string or a Pair of a query string and source projection.
    private final WeakCache<Object, ScannerFactory<S, T>, Query> mScannerFactoryCache;

    protected GroupedTable(Table<S> source, Supplier<Grouper<S, T>> supplier) {
        mSource = source;
        mGrouperSupplier = supplier;

        mScannerFactoryCache = new WeakCache<>() {
            @Override
            protected ScannerFactory<S, T> newValue(Object key, Query query) {
                String queryStr, sourceProjection;

                if (key instanceof Pair pair) {
                    queryStr = (String) pair.a();
                    sourceProjection = (String) pair.b();
                } else {
                    queryStr = (String) key;
                    sourceProjection = null;
                }

                if (query == null) {
                    RowInfo rowInfo = RowInfo.find(rowType());
                    query = new Parser(rowInfo.allColumns, queryStr).parseQuery(null);
                    String canonical = query.toString();
                    if (!canonical.equals(queryStr)) {
                        return obtain(makeScannerFactoryKey(canonical, sourceProjection), query);
                    }
                }

                return makeScannerFactory(query, sourceProjection);
            }
        };
    }

    private static Object makeScannerFactoryKey(String queryStr, String sourceProjection) {
        if (sourceProjection == null) {
            return queryStr;
        } else {
            return new Pair<String, String>(queryStr, sourceProjection);
        }
    }

    @Override
    public final Scanner<T> newScanner(Transaction txn) throws IOException {
        return newScannerWith(txn, null);
    }

    @Override
    public final Scanner<T> newScannerWith(Transaction txn, T targetRow) throws IOException {
        return newScannerWith(txn, targetRow, "{*}");
    }

    @Override
    public final Scanner<T> newScanner(Transaction txn, String query, Object... args)
        throws IOException
    {
        return newScannerWith(txn, null, query, args);
    }

    @Override
    public final Scanner<T> newScannerWith(Transaction txn, T targetRow,
                                           String query, Object... args)
        throws IOException
    {
        Grouper<S, T> grouper = mGrouperSupplier.get();
        String projection = grouper.sourceProjection();

        ScannerFactory<S, T> factory;
        try {
            factory = mScannerFactoryCache.obtain(makeScannerFactoryKey(query, projection), null);
        } catch (Throwable e) {
            try {
                grouper.close();
            } catch (Throwable e2) {
                RowUtils.suppress(e, e2);
            }
            throw e;
        }

        return factory.newScannerWith(this, grouper, txn, targetRow, args);
    }

    @Override
    public final Transaction newTransaction(DurabilityMode durabilityMode) {
        return mSource.newTransaction(durabilityMode);
    }

    @Override
    public final boolean isEmpty() throws IOException {
        return mSource.isEmpty() || !anyRows(Transaction.BOGUS);
    }

    /**
     * This method must be overridden when the target row has a primary key.
     */
    @Override
    public boolean load(Transaction txn, T targetRow) throws IOException {
        Scanner<T> s = newScannerWith(txn, targetRow);
        boolean found = s.row() != null;
        s.close();
        if (!found) {
            unsetRow(targetRow);
        }
        return found;
    }

    /**
     * This method must be overridden when the target row has a primary key.
     */
    @Override
    public boolean exists(Transaction txn, T targetRow) throws IOException {
        return anyRows(txn);
    }

    @Override
    public final QueryPlan scannerPlan(Transaction txn, String query, Object... args)
        throws IOException
    {
        try (Grouper<S, T> grouper = mGrouperSupplier.get()) {
            String projection = grouper.sourceProjection();
            return mScannerFactoryCache
                .obtain(makeScannerFactoryKey(query == null ? "{*}" : query, projection), null)
                .plan(this, grouper, txn, args);
        }
    }

    @Override
    public final void close() throws IOException {
        // Do nothing.
    }

    @Override
    public final boolean isClosed() {
        return false;
    }

    /**
     * Called by GroupedScanner.
     */
    final S newSourceRow() {
        return mSource.newRow();
    }

    /**
     * Called by GroupedScanner.
     */
    final void copySourceRow(S from, S to) {
        mSource.copyRow(from, to);
    }

    /**
     * Called by GroupedScanner. Returns zero if the source rows are in the same group.
     */
    protected abstract int compareSourceRows(S r1, S r2);

    /**
     * Called by GroupedScanner. Sets the primary key columns of the target row, and calls
     * cleanRow(target).
     */
    protected abstract void finishTarget(S source, T target);

    /**
     * Called by GroupedScanner. This method must be overridden when the target row doesn't
     * have a primary key.
     */
    protected long estimateSize() {
        return Long.MAX_VALUE;
    }

    /**
     * Called by GroupedScanner. This method must be overridden when the target row doesn't
     * have a primary key.
     */
    protected int characteristics(Scanner<S> source) {
        return (source.characteristics() & ~(SIZED | SUBSIZED)) | ORDERED | SORTED;
    }

    /**
     * Called by the generated ScannerFactory.
     */
    public final Table<S> source() {
        return mSource;
    }

    /**
     * Called by the generated ScannerFactory.
     */
    public final Scanner<T> sort(Scanner<T> source, Comparator<T> comparator,
                                 Set<String> projection, String orderBySpec)
        throws IOException
    {
        return RowSorter.sort(this, source, comparator, projection, orderBySpec);
    }

    /**
     * Returns columns (or null) for QueryPlan.Grouper.
     */
    public final String[] groupByColumns() {
        RowInfo info = RowInfo.find(rowType());
        if (info.keyColumns.isEmpty()) {
            return null;
        }
        var columns = new String[info.keyColumns.size()];
        int ix = 0;
        for (String name : info.keyColumns.keySet()) {
            columns[ix++] = name;
        }
        return columns;
    }

    private ScannerFactory<S, T> makeScannerFactory(Query targetQuery, String sourceProjection) {
        Class<S> sourceType = mSource.rowType();
        RowInfo sourceInfo = RowInfo.find(sourceType);

        // Prepare an initial source query, which will be replaced later.
        Query sourceQuery;
        {
            if (sourceProjection == null) {
                sourceQuery = new Query(null, null, TrueFilter.THE);
            } else {
                sourceQuery = new Parser
                    (sourceInfo.allColumns, '{' + sourceProjection + '}').parseQuery(null);
            }
        }

        Class<T> targetType = rowType();
        RowInfo targetInfo = RowInfo.find(targetType);

        final RowFilter sourceFilter, targetFilter;

        {
            RowFilter filter = targetQuery.filter();

            if (targetInfo.keyColumns.isEmpty()) {
                sourceFilter = TrueFilter.THE;
                targetFilter = filter;
            } else {
                try {
                    filter = filter.cnf();
                } catch (ComplexFilterException e) {
                    // The split won't be as effective, and so the target filter will do
                    // more work.
                }

                var split = new RowFilter[2];
                filter.split(sourceInfo.allColumns, split);

                sourceFilter = split[0];
                targetFilter = split[1];
            }
        }

        OrderBy sourceOrderBy = null, targetOrderBy;

        if (targetInfo.keyColumns.isEmpty()) {
            // At most one row is produced, so no need to sort.
            targetOrderBy = null;
        } else if ((targetOrderBy = targetQuery.orderBy()) != null) {
            // As soon as all of the group-by columns are encountered in the requested target
            // ordering, the rest can be discarded.

            int numGroupBy = targetInfo.keyColumns.size();
            Iterator<Map.Entry<String, OrderBy.Rule>> it = targetOrderBy.entrySet().iterator();

            while (it.hasNext()) {
                String name = it.next().getKey();
                if (targetInfo.keyColumns.containsKey(name) && --numGroupBy == 0 && it.hasNext()) {
                    // Copy and discard the rest.
                    targetOrderBy = new OrderBy(targetOrderBy);
                    do {
                        targetOrderBy.remove(it.next().getKey());
                    } while (it.hasNext());
                }
            }

            // Push as many order-by columns to the source as possible.

            it = targetOrderBy.entrySet().iterator();

            while (true) {
                if (!it.hasNext()) {
                    // All order-by columns have been pushed to the source, and so no target
                    // sort is required.
                    targetOrderBy = null;
                    break;
                }

                Map.Entry<String, OrderBy.Rule> e = it.next();
                String name = e.getKey();

                if (!sourceInfo.allColumns.containsKey(name)) {
                    // Once a target-only column is encountered, no more columns can be pushed
                    // to the source, and a target sort is required.
                    break;
                }

                if (sourceOrderBy == null) {
                    sourceOrderBy = new OrderBy();
                }

                sourceOrderBy.put(name, e.getValue());
            }
        }

        if (!targetInfo.keyColumns.isEmpty()) {
            // To ensure proper grouping, all group-by columns (the target primary key) must be
            // appended to the source order-by.
            if (sourceOrderBy == null) {
                sourceOrderBy = new OrderBy();
            }
            for (ColumnInfo column : targetInfo.keyColumns.values()) {
                String name = column.name;
                if (!sourceOrderBy.containsKey(name)) {
                    OrderBy.Rule rule = targetQuery.orderByRule(name);
                    if (rule == null && (rule = sourceQuery.orderByRule(name)) == null) {
                        rule = new OrderBy.Rule(column, column.typeCode);
                    }
                    sourceOrderBy.put(name, rule);
                }
            }
        }

        sourceQuery = sourceQuery.withOrderBy(sourceOrderBy).withFilter(sourceFilter);
        targetQuery = targetQuery.withOrderBy(targetOrderBy).withFilter(targetFilter);

        ClassMaker factoryMaker = targetInfo.rowGen().beginClassMaker
            (GroupedTable.class, targetType, "factory").final_().implement(ScannerFactory.class);

        // Keep a singleton instance, in order for a weakly cached reference to the factory to
        // stick around until the class is unloaded.
        factoryMaker.addField(Object.class, "THE").private_().static_();

        MethodMaker ctor = factoryMaker.addConstructor().private_();
        ctor.invokeSuperConstructor();
        ctor.field("THE").set(ctor.this_());

        MethodMaker mm = factoryMaker.addMethod
            (Scanner.class, "newScannerWith", GroupedTable.class, Grouper.class,
             Transaction.class, Object.class, Object[].class).public_().varargs();

        var tableVar = mm.param(0);
        var grouperVar = mm.param(1);
        var txnVar = mm.param(2);
        var targetRowVar = mm.param(3);
        var argsVar = mm.param(4);

        var sourceTableVar = tableVar.invoke("source");

        final Variable sourceScannerVar;

        if (sourceQuery.isFullScan()) {
            sourceScannerVar = sourceTableVar.invoke("newScanner", txnVar);
        } else {
            sourceScannerVar = sourceTableVar.invoke
                ("newScanner", txnVar, sourceQuery.toString(), argsVar);
        }

        // Define the comparator returned by GroupedScanner.

        Variable groupComparatorVar = null;

        if (!targetInfo.keyColumns.isEmpty()) {
            var groupOrderBy = new OrderBy(sourceOrderBy);
            Iterator<Map.Entry<String, OrderBy.Rule>> it = groupOrderBy.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry<String, OrderBy.Rule> e = it.next();
                if (!targetInfo.keyColumns.containsKey(e.getKey())) {
                    // Can only refer to target primary key columns.
                    it.remove();
                }
            }
            
            if (!sourceOrderBy.isEmpty()) {
                groupComparatorVar = mm.var(Comparator.class).setExact
                    (ComparatorMaker.comparator(targetType, groupOrderBy, groupOrderBy.spec()));
            }
        }

        var targetScannerVar = mm.new_(GroupedScanner.class, tableVar, sourceScannerVar,
                                       groupComparatorVar, targetRowVar, grouperVar);

        targetScannerVar = WrappedScanner.wrap(targetType, argsVar, targetScannerVar,
                                               targetQuery.filter(), targetQuery.projection());

        if (targetQuery.orderBy() != null) {
            OrderBy orderBy = targetQuery.orderBy();
            String orderBySpec = orderBy.spec();

            var comparatorVar = mm.var(Comparator.class).setExact
                (ComparatorMaker.comparator(targetType, orderBy, orderBySpec));

            Variable projectionVar = null;

            if (targetQuery.projection() != null) {
                projectionVar = mm.var(Set.class).setExact
                    (SortedQueryLauncher.canonicalize(targetQuery.projection().keySet()));
            }

            targetScannerVar = tableVar.invoke("sort", targetScannerVar, comparatorVar,
                                               projectionVar, orderBySpec);
        }

        mm.return_(targetScannerVar);

        // Add the plan method.

        mm = factoryMaker.addMethod
            (QueryPlan.class, "plan", GroupedTable.class, Grouper.class,
             Transaction.class, Object[].class).public_().varargs();

        tableVar = mm.param(0);
        grouperVar = mm.param(1);
        txnVar = mm.param(2);
        argsVar = mm.param(3);

        final var planVar = tableVar.invoke("source")
            .invoke("scannerPlan", txnVar, sourceQuery.toString(), argsVar);

        var targetVar = mm.var(Class.class).set(targetType).invoke("getName");
        var usingVar = grouperVar.invoke("toString");
        var columnVars = tableVar.invoke("groupByColumns");

        var grouperPlanVar = mm.new_
            (QueryPlan.Grouper.class, targetVar, usingVar, columnVars, planVar);
        planVar.set(grouperVar.invoke("plan", grouperPlanVar));

        if (targetQuery.filter() != TrueFilter.THE) {
            planVar.set(mm.new_(QueryPlan.Filter.class, targetQuery.filter().toString(), planVar));
        }

        if (targetQuery.orderBy() != null) {
            String spec = targetQuery.orderBy().spec();
            var columnsVar = mm.var(OrderBy.class).invoke("splitSpec", spec);
            planVar.set(mm.new_(QueryPlan.Sort.class, columnsVar, planVar));
        }

        mm.return_(planVar);

        try {
            MethodHandles.Lookup lookup = factoryMaker.finishHidden();
            MethodHandle mh = lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class));
            return (ScannerFactory<S, T>) mh.invoke();
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    public interface ScannerFactory<S, T> {
        Scanner<T> newScannerWith(GroupedTable<S, T> table, Grouper<S, T> grouper,
                                  Transaction txn, T targetRow, Object... args)
            throws IOException;

        QueryPlan plan(GroupedTable<S, T> table, Grouper<S, T> grouper,
                       Transaction txn, Object... args)
            throws IOException;
    }
}
