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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import java.util.function.Function;
import java.util.function.Predicate;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.LockMode;
import org.cojen.tupl.Mapper;
import org.cojen.tupl.Scanner;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.UnmodifiableViewException;
import org.cojen.tupl.Updater;
import org.cojen.tupl.ViewConstraintException;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.rows.filter.ColumnFilter;
import org.cojen.tupl.rows.filter.ColumnToArgFilter;
import org.cojen.tupl.rows.filter.ColumnToColumnFilter;
import org.cojen.tupl.rows.filter.ComplexFilterException;
import org.cojen.tupl.rows.filter.Parser;
import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.Query;
import org.cojen.tupl.rows.filter.TrueFilter;

/**
 * Base class for generated mapped tables.
 *
 * @author Brian S O'Neill
 * @see Table#map
 */
public abstract class MappedTable<S, T> implements Table<T> {
    /**
     * Although the generated factories only depend on the targetType, a full key is needed
     * because the mScannerFactoryCache and mInverse* fields rely on code which is generated
     * against the source and target type.
     */
    private record FactoryKey(Class<?> sourceType, Class<?> targetType, Class<?> mapperClass) { }

    private static final WeakCache<FactoryKey, MethodHandle, Object> cFactoryCache;

    static {
        cFactoryCache = new WeakCache<>() {
            @Override
            public MethodHandle newValue(FactoryKey key, Object unused) {
                return makeTableFactory(key);
            }
        };
    }

    public static <S, T> MappedTable<S, T> map(Table<S> source, Class<T> targetType,
                                               Mapper<S, T> mapper)
    {
        Objects.requireNonNull(targetType);
        try {
            var key = new FactoryKey(source.rowType(), targetType, mapper.getClass());
            return (MappedTable<S, T>) cFactoryCache.obtain(key, null).invokeExact(source, mapper);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    /**
     * MethodHandle signature: MappedTable<S, T> make(Table<S> source, Mapper<S, T> mapper)
     */
    private static MethodHandle makeTableFactory(FactoryKey key) {
        Class<?> targetType = key.targetType();
        RowInfo info = RowInfo.find(targetType);

        ClassMaker tableMaker = info.rowGen().beginClassMaker
            (MappedTable.class, targetType, "mapped").final_()
            .extend(MappedTable.class).implement(TableBasicsMaker.find(targetType));

        {
            MethodMaker ctor = tableMaker.addConstructor(Table.class, Mapper.class).private_();
            ctor.invokeSuperConstructor(ctor.param(0), ctor.param(1));
        }

        // Keep a reference to the MethodHandle instance, to prevent it from being garbage
        // collected as long as the generated table class still exists.
        tableMaker.addField(MethodHandle.class, "_").static_().private_();

        addMarkValuesUnset(key, info, tableMaker);

        MethodHandles.Lookup lookup = tableMaker.finishLookup();
        Class<?> tableClass = lookup.lookupClass();

        MethodMaker mm = MethodMaker.begin
            (lookup, MappedTable.class, null, Table.class, Mapper.class);
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

    private static void addMarkValuesUnset(FactoryKey key, RowInfo targetInfo, ClassMaker cm) {
        RowInfo sourceInfo = RowInfo.find(key.sourceType());

        // Map of target columns which have an inverse mapping to a source primary key column.
        Map<String, ColumnInfo> mapToSource = new HashMap<>();

        for (Method m : key.mapperClass().getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }

            Class<?> retType = m.getReturnType();
            if (retType == null || retType == void.class) {
                continue;
            }

            Class<?>[] paramTypes = m.getParameterTypes();
            if (paramTypes.length != 1) {
                continue;
            }

            String name = m.getName();
            int ix = name.indexOf("_to_");
            if (ix <= 0) {
                continue;
            }

            String sourceName = name.substring(ix + "_to_".length());
            if (!sourceInfo.keyColumns.containsKey(sourceName)) {
                continue;
            }

            String targetName = name.substring(0, ix);
            ColumnInfo target = targetInfo.allColumns.get(targetName);
            if (target != null) {
                mapToSource.put(targetName, target);
            }
        }

        if (Mapper.Identity.class.isAssignableFrom(key.mapperClass())) {
            for (ColumnInfo target : targetInfo.allColumns.values()) {
                String name = target.name;
                if (!mapToSource.containsKey(name) && sourceInfo.keyColumns.containsKey(name)) {
                    mapToSource.put(name, target);
                }
            }
        }

        if (mapToSource.isEmpty()) {
            return;
        }

        MethodMaker mm = cm.addMethod(null, "markValuesUnset", Object.class).protected_();
        var targetRowVar = mm.param(0).cast(RowMaker.find(key.targetType()));
        TableMaker.unset(targetInfo, targetRowVar, mapToSource);
    }

    private final Table<S> mSource;
    private final Mapper<S, T> mMapper;

    private final SoftCache<String, ScannerFactory<S, T>, Query> mScannerFactoryCache;

    private InverseMapper<S, T> mInversePk, mInverseFull, mInverseUpdate;

    protected MappedTable(Table<S> source, Mapper<S, T> mapper) {
        mSource = source;
        mMapper = mapper;

        mScannerFactoryCache = new SoftCache<>() {
            @Override
            protected ScannerFactory<S, T> newValue(String queryStr, Query query) {
                if (query == null) {
                    RowInfo rowInfo = RowInfo.find(rowType());
                    query = new Parser(rowInfo.allColumns, queryStr).parseQuery(null);
                    String canonical = query.toString();
                    if (!canonical.equals(queryStr)) {
                        return obtain(canonical, query);
                    }
                }
                return makeScannerFactory(query);
            }
        };
    }

    @Override
    public final Scanner<T> newScanner(Transaction txn) throws IOException {
        return newScannerWith(txn, null);
    }

    @Override
    public final Scanner<T> newScannerWith(Transaction txn, T targetRow) throws IOException {
        return new MappedScanner<>(this, mSource.newScanner(txn), targetRow, mMapper);
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
        return mScannerFactoryCache.obtain(query, null).newScannerWith(this, txn, targetRow, args);
    }

    @Override
    public final Updater<T> newUpdater(Transaction txn) throws IOException {
        return new MappedUpdater<>(this, mSource.newUpdater(txn), null, mMapper);
    }

    @Override
    public final Updater<T> newUpdater(Transaction txn, String query, Object... args)
        throws IOException
    {
        return mScannerFactoryCache.obtain(query, null).newUpdaterWith(this, txn, null, args);
    }

    @Override
    public final Transaction newTransaction(DurabilityMode durabilityMode) {
        return mSource.newTransaction(durabilityMode);
    }

    @Override
    public final boolean isEmpty() throws IOException {
        return mSource.isEmpty() || !anyRows(Transaction.BOGUS);
    }

    @Override
    public final boolean load(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inversePk().inverseMapForLoad(mSource, targetRow);
        if (mSource.load(txn, sourceRow)) {
            T mappedRow = mMapper.map(sourceRow, newRow());
            if (mappedRow != null) {
                cleanRow(mappedRow);
                copyRow(mappedRow, targetRow);
                return true;
            }
        }
        markValuesUnset(targetRow);
        return false;
    }

    @Override
    public final boolean exists(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inversePk().inverseMapForLoad(mSource, targetRow);
        return mSource.load(txn, sourceRow) && mMapper.map(sourceRow, newRow()) != null;
    }

    @Override
    public final void store(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inverseFull().inverseMap(mSource, targetRow);
        mSource.store(txn, sourceRow);
        cleanRow(targetRow);
    }

    @Override
    public final T exchange(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inverseFull().inverseMap(mSource, targetRow);
        S oldSourceRow = mSource.exchange(txn, sourceRow);
        cleanRow(targetRow);
        if (oldSourceRow == null) {
            return null;
        }
        T oldTargetRow = mMapper.map(oldSourceRow, newRow());
        if (oldTargetRow != null) {
            cleanRow(oldTargetRow);
        }
        return oldTargetRow;
    }

    @Override
    public final boolean insert(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inverseFull().inverseMap(mSource, targetRow);
        if (!mSource.insert(txn, sourceRow)) {
            return false;
        }
        cleanRow(targetRow);
        return true;
    }

    @Override
    public final boolean replace(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inverseFull().inverseMap(mSource, targetRow);
        if (!mSource.replace(txn, sourceRow)) {
            return false;
        }
        cleanRow(targetRow);
        return true;
    }

    @Override
    public final boolean update(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inverseUpdate().inverseMap(mSource, targetRow);
        if (!mSource.update(txn, sourceRow)) {
            return false;
        }
        cleanRow(targetRow);
        return true;
    }

    @Override
    public final boolean merge(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inverseUpdate().inverseMap(mSource, targetRow);
        if (!mSource.merge(txn, sourceRow)) {
            return false;
        }
        T mappedRow = mMapper.map(sourceRow, newRow());
        if (mappedRow != null) {
            cleanRow(mappedRow);
            copyRow(mappedRow, targetRow);
        } else {
            // Can't load back the row. One option is to rollback the transaction, but then the
            // behavior would be inconsistent with the update operation. Unsetting all the
            // columns allows the operation to complete and signal that something is amiss.
            unsetRow(targetRow);
        }
        return true;
    }

    @Override
    public final boolean delete(Transaction txn, T targetRow) throws IOException {
        Objects.requireNonNull(targetRow);
        S sourceRow = inversePk().inverseMap(mSource, targetRow);
        return mSource.delete(txn, sourceRow);
    }

    @Override
    public final QueryPlan scannerPlan(Transaction txn, String query, Object... args)
        throws IOException
    {
        if (query == null) {
            return decorate(mSource.scannerPlan(txn, null));
        } else {
            return mScannerFactoryCache.obtain(query, null).plan(false, this, txn, args);
        }
    }

    @Override
    public final QueryPlan updaterPlan(Transaction txn, String query, Object... args)
        throws IOException
    {
        if (query == null) {
            return decorate(mSource.updaterPlan(txn, null));
        } else {
            return mScannerFactoryCache.obtain(query, null).plan(true, this, txn, args);
        }
    }

    private QueryPlan decorate(QueryPlan plan) {
        return mMapper.plan(new QueryPlan.Mapper(rowType().getName(), mMapper.toString(), plan));
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
     * All columns which don't map to source primary key columns are unset. Is overridden by
     * generated code if necessary.
     */
    protected void markValuesUnset(T targetRow) {
        unsetRow(targetRow);
    }

    /**
     * Returns an inverse mapper which requires that the primary key columns need to be set.
     */
    final InverseMapper<S, T> inversePk() {
        var invMapper = (InverseMapper<S, T>) mInversePk;
        if (invMapper == null) {
            invMapper = makeInversePk();
        }
        return invMapper;
    }

    private synchronized InverseMapper<S, T> makeInversePk() {
        var invMapper = mInversePk;
        if (invMapper == null) {
            mInversePk = invMapper = makeInverseMapper(1);
        }
        return invMapper;
    }

    /**
     * Returns an inverse mapper which requires that all columns need to be set.
     */
    private InverseMapper<S, T> inverseFull() {
        var invMapper = (InverseMapper<S, T>) mInverseFull;
        if (invMapper == null) {
            invMapper = makeInverseFull();
        }
        return invMapper;
    }

    private synchronized InverseMapper<S, T> makeInverseFull() {
        var invMapper = mInverseFull;
        if (invMapper == null) {
            mInverseFull = invMapper = makeInverseMapper(2);
        }
        return invMapper;
    }

    /**
     * Returns an inverse mapper which requires that the primary key columns need to be set,
     * and only dirty columns are updated.
     */
    final InverseMapper<S, T> inverseUpdate() {
        var invMapper = (InverseMapper<S, T>) mInverseUpdate;
        if (invMapper == null) {
            invMapper = makeInverseUpdate();
        }
        return invMapper;
    }

    private synchronized InverseMapper<S, T> makeInverseUpdate() {
        var invMapper = mInverseUpdate;
        if (invMapper == null) {
            mInverseUpdate = invMapper = makeInverseMapper(3);
        }
        return invMapper;
    }

    /**
     * @param mode 1: pk, mode 2: full, mode 3: update
     */
    private InverseMapper<S, T> makeInverseMapper(int mode) {
        RowInfo sourceInfo = RowInfo.find(mSource.rowType());
        RowInfo targetInfo = RowInfo.find(rowType());

        Map<String, ColumnInfo> sourceColumns;
        if (mode == 1) {
            // pk: only select sources that refer to the primary key
            sourceColumns = sourceInfo.keyColumns;
        } else {
            sourceColumns = sourceInfo.allColumns;
        }

        var finder = new InverseFinder(sourceColumns);

        // Maps source columns to the targets that map to it.
        var toTargetMap = new LinkedHashMap<String, Set<ColumnFunction>>();

        for (ColumnInfo targetColumn : targetInfo.allColumns.values()) {
            ColumnFunction source = finder.tryFindSource(targetColumn);

            if (source == null) {
                continue;
            }

            String sourceName = source.column().name;
            Set<ColumnFunction> set = toTargetMap.get(sourceName);

            if (set == null) {
                set = new TreeSet<>((a, b) -> {
                    // Prefer identity mappers, so order them first.
                    if (a.function() == null) {
                        if (b.function() != null) {
                            return -1;
                        }
                    } else if (b.function() == null) {
                        return 1;
                    }

                    return a.column().name.compareTo(b.column().name);
                });

                toTargetMap.put(sourceName, set);
            }

            set.add(new ColumnFunction(targetColumn, source.function()));
        }

        if (mode < 3) {
            if (toTargetMap.size() != sourceColumns.size()) {
                // Not enough source columns have been mapped.
                return NoInverse.instance();
            }
        } else {
            // At least the source primary key columns must be mapped.
            for (String name : sourceInfo.keyColumns.keySet()) {
                if (!toTargetMap.containsKey(name)) {
                    return NoInverse.instance();
                }
            }
        }

        RowGen targetGen = targetInfo.rowGen();

        ClassMaker cm = targetGen.beginClassMaker
            (MappedTable.class, rowType(), null).final_().implement(InverseMapper.class);

        cm.addConstructor().private_();

        MethodMaker mm = cm.addMethod(null, "inverseMap", Object.class, Object.class);
        mm.public_();

        var targetRowVar = mm.param(1).cast(RowMaker.find(rowType()));
        var sourceRowVar = mm.param(0).cast(mSource.rowType());

        Map<String, Integer> targetColumnNumbers = targetGen.columnNumbers();

        for (Map.Entry<String, Set<ColumnFunction>> e : toTargetMap.entrySet()) {
            String sourceColumnName = e.getKey();
            ColumnInfo sourceColumnInfo = sourceInfo.allColumns.get(sourceColumnName);

            Label nextSource = mm.label();

            // Assign the source column with the first target column which is set.

            Iterator<ColumnFunction> it = e.getValue().iterator();
            while (true) {
                ColumnFunction targetColumnFun = it.next();
                ColumnInfo targetColumn = targetColumnFun.column();

                int targetColumnNum = targetColumnNumbers.get(targetColumn.name);
                var stateVar = targetRowVar.field(targetGen.stateField(targetColumnNum));
                Label nextTarget = it.hasNext() ? mm.label() : nextSource;
                int stateMask = RowGen.stateFieldMask(targetColumnNum);

                if (mode < 3 || sourceInfo.keyColumns.containsKey(sourceColumnName)) {
                    stateVar.and(stateMask).ifEq(0, nextTarget);
                } else {
                    // Only update the column if it's dirty.
                    stateVar.and(stateMask).ifNe(stateMask, nextTarget);
                }

                var targetColumnVar = targetRowVar.field(targetColumn.name);
                Variable sourceColumnVar;

                Method fun = targetColumnFun.function();

                if (fun != null) {
                    sourceColumnVar = mm.var
                        (fun.getDeclaringClass()).invoke(fun.getName(), targetColumnVar);
                } else {
                    sourceColumnVar = mm.var(sourceColumnInfo.type);
                    Converter.convertExact(mm, sourceColumnName,
                                           targetColumn, targetColumnVar,
                                           sourceColumnInfo, sourceColumnVar);
                }

                sourceRowVar.invoke(sourceColumnName, sourceColumnVar);

                if (!it.hasNext()) {
                    break;
                }

                nextSource.goto_();

                nextTarget.here();
            }

            nextSource.here();
        }

        try {
            MethodHandles.Lookup lookup = cm.finishHidden();
            MethodHandle mh = lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class));
            return (InverseMapper<S, T>) mh.invoke();
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    private ScannerFactory<S, T> makeScannerFactory(Query targetQuery) {
        final RowFilter targetFilter;
        {
            RowFilter filter = targetQuery.filter();
            try {
                filter = filter.cnf();
            } catch (ComplexFilterException e) {
                // The split won't be as effective, and so the remainder mapper will do more work.
            }
            targetFilter = filter;
        }

        record ArgMapper(int targetArgNum, Method function) { } 

        RowInfo sourceInfo = RowInfo.find(mSource.rowType());
        var finder = new InverseFinder(sourceInfo.allColumns);

        var checker = new Function<ColumnFilter, RowFilter>() {
            Map<ArgMapper, Integer> argMappers;
            int maxArg;

            @Override
            public RowFilter apply(ColumnFilter cf) {
                ColumnFunction source = finder.tryFindSource(cf.column());
                if (source == null) {
                    return null;
                }

                if (cf instanceof ColumnToArgFilter c2a) {
                    c2a = c2a.withColumn(source.column());
                    if (source.function() == null) {
                        return c2a;
                    }
                    if (argMappers == null) {
                        argMappers = new HashMap<>();
                        maxArg = targetFilter.maxArgument();
                    }
                    var argMapper = new ArgMapper(c2a.argument(), source.function());
                    Integer sourceArg = argMappers.get(argMapper);
                    if (sourceArg == null) {
                        sourceArg = ++maxArg;
                        argMappers.put(argMapper, sourceArg);
                    }
                    return c2a.withArgument(sourceArg);
                } else if (cf instanceof ColumnToColumnFilter c2c) {
                    // Can only convert to a source filter when both columns rely on an
                    // identity mapping, and a common type exists. It's unlikely that a common
                    // type doesn't exist.
                    if (source.function() != null) {
                        return null;
                    }
                    ColumnFunction otherSource = finder.tryFindSource(c2c.otherColumn());
                    if (otherSource == null || otherSource.function() != null) {
                        return null;
                    }
                    return c2c.tryWithColumns(source.column(), otherSource.column());
                }

                return null;
            }

            void addPrepareArgsMethod(ClassMaker cm) {
                if (argMappers == null) {
                    // No special method is needed.
                    return;
                }

                MethodMaker mm = cm.addMethod(Object[].class, "prepareArgs", Object[].class)
                    .varargs().static_().final_();

                // Generate the necessary source query arguments from the provided target
                // arguments.

                Label ready = mm.label();
                var argsVar = mm.param(0);
                argsVar.ifEq(null, ready);

                argsVar.set(mm.var(Arrays.class).invoke("copyOf", argsVar, maxArg));

                for (Map.Entry<ArgMapper, Integer> e : argMappers.entrySet()) {
                    ArgMapper argMapper = e.getKey();
                    var argVar = argsVar.aget(argMapper.targetArgNum - 1);
                    Label cont = mm.label();
                    argVar.ifEq(null, cont);
                    Method fun = argMapper.function();
                    var targetArgVar = ConvertCallSite.make(mm, fun.getParameterTypes()[0], argVar);
                    var sourceArgVar =
                        mm.var(fun.getDeclaringClass()).invoke(fun.getName(), targetArgVar);
                    argsVar.aset(e.getValue() - 1, sourceArgVar);

                    cont.here();
                }

                ready.here();
                mm.return_(argsVar);
            }

            Variable prepareArgs(Variable argsVar) {
                if (argMappers == null) {
                    // No special method was defined.
                    return argsVar;
                } else {
                    return argsVar.methodMaker().invoke("prepareArgs", argsVar);
                }
            }
        };

        var split = new RowFilter[2];
        targetFilter.split(checker, split);

        RowFilter sourceFilter = split[0];
        RowFilter targetRemainder = split[1];

        Query sourceQuery;

        String sourceProjection = mMapper.sourceProjection();
        if (sourceProjection == null) {
            sourceQuery = new Query(null, null, sourceFilter);
        } else {
            sourceQuery = new Parser(sourceInfo.allColumns, '{' + sourceProjection + '}')
                .parseQuery(null).withFilter(sourceFilter);
        }

        SortPlan sortPlan = finder.analyzeSort(targetQuery);

        if (sortPlan != null && sortPlan.sourceOrder != null) {
            sourceQuery = sourceQuery.withOrderBy(sortPlan.sourceOrder);
        } else if (sourceQuery.projection() == null && sourceQuery.filter() == TrueFilter.THE) {
            // There's no orderBy, all columns are projected, there's no filter, so just do a
            // full scan.
            sourceQuery = null;
        }

        Class<T> targetType = rowType();
        RowInfo targetInfo = RowInfo.find(targetType);

        ClassMaker cm = targetInfo.rowGen().beginClassMaker
            (MappedTable.class, targetType, null).final_().implement(ScannerFactory.class);

        cm.addConstructor().private_();

        if (targetRemainder != TrueFilter.THE || targetQuery.projection() != null) {
            // Allow factory instances to serve as Mapper wrappers for supporting predicate
            // testing and projection.

            cm.implement(Mapper.class);

            MethodMaker mm = cm.addConstructor(Mapper.class, Object[].class).private_().varargs();
            mm.invokeSuperConstructor();

            cm.addField(Mapper.class, "mapper").private_().final_();
            mm.field("mapper").set(mm.param(0));

            if (targetRemainder != TrueFilter.THE) {
                cm.addField(Predicate.class, "predicate").private_();
                MethodHandle mh = PlainPredicateMaker
                    .predicateHandle(targetType, targetRemainder.toString());
                mm.field("predicate").set(mm.invoke(mh, mm.param(1)));
            }

            mm = cm.addMethod(Object.class, "map", Object.class, Object.class).public_();

            var sourceRowVar = mm.param(0);
            var targetRowVar = mm.param(1);

            targetRowVar.set(mm.field("mapper").invoke("map", sourceRowVar, targetRowVar));

            Label done = mm.label();
            targetRowVar.ifEq(null, done);

            if (targetRemainder != TrueFilter.THE) {
                Label cont = mm.label();
                mm.field("predicate").invoke("test", targetRowVar).ifTrue(cont);
                mm.return_(null);
                cont.here();
            }

            Map<String, ColumnInfo> projection = targetQuery.projection();

            if (projection != null) {
                TableMaker.unset
                    (targetInfo, targetRowVar.cast(RowMaker.find(targetType)), projection);
            }

            done.here();
            mm.return_(targetRowVar);
        }

        checker.addPrepareArgsMethod(cm);

        for (int which = 1; which <= 2; which++) {
            String methodName = which == 1 ? "newScanner" : "newUpdater";

            MethodMaker mm = cm.addMethod
                (which == 1 ? Scanner.class : Updater.class, methodName + "With",
                 MappedTable.class, Transaction.class, Object.class, Object[].class)
                .public_().varargs();

            var tableVar = mm.param(0);
            var txnVar = mm.param(1);
            var targetRowVar = mm.param(2);
            var argsVar = mm.param(3);

            if (which != 1 && sortPlan != null && sortPlan.sortOrder != null) {
                // Use a WrappedUpdater around a sorted Scanner.
                mm.return_(tableVar.invoke
                           ("newWrappedUpdater", mm.this_(), txnVar, targetRowVar, argsVar));
                continue;
            }

            argsVar = checker.prepareArgs(argsVar);

            var mapperVar = tableVar.invoke("mapper");

            if (targetRemainder != TrueFilter.THE || targetQuery.projection() != null) {
                mapperVar.set(mm.new_(cm, mapperVar, argsVar));
            }

            var sourceTableVar = tableVar.invoke("source");
            Variable sourceScannerVar;

            if (sourceQuery == null) {
                sourceScannerVar = sourceTableVar.invoke(methodName, txnVar);
            } else {
                sourceScannerVar = sourceTableVar.invoke
                    (methodName, txnVar, sourceQuery.toString(), argsVar);
            }

            Variable resultVar;

            if (sortPlan != null && sortPlan.sortOrder != null) {
                if (which != 1) {
                    throw new AssertionError();
                }

                var comparatorVar = mm.var(Comparator.class).setExact(sortPlan.sortComparator);

                Variable projectionVar = null;

                if (targetQuery.projection() != null) {
                    projectionVar = mm.var(Set.class).setExact
                        (SortedQueryLauncher.canonicalize(targetQuery.projection().keySet()));
                }

                resultVar = mm.new_(MappedScanner.class, tableVar,
                                    sourceScannerVar, targetRowVar, mapperVar);

                resultVar = tableVar.invoke("sort", resultVar, comparatorVar,
                                            projectionVar, sortPlan.sortOrderSpec);
            } else if (which == 1) {
                resultVar = mm.new_(MappedScanner.class, tableVar,
                                    sourceScannerVar, targetRowVar, mapperVar);
            } else {
                resultVar = mm.new_(MappedUpdater.class, tableVar,
                                    sourceScannerVar, targetRowVar, mapperVar);
            }

            mm.return_(resultVar);
        }

        // Add the plan method.

        {
            MethodMaker mm = cm.addMethod(QueryPlan.class, "plan", boolean.class,
                                          MappedTable.class, Transaction.class, Object[].class)
                .public_().varargs();

            var forUpdaterVar = mm.param(0);
            var tableVar = mm.param(1);
            var txnVar = mm.param(2);
            var argsVar = checker.prepareArgs(mm.param(3));

            String sourceQueryStr = sourceQuery == null ? null : sourceQuery.toString();

            var sourceTableVar = tableVar.invoke("source");
            var planVar = mm.var(QueryPlan.class);

            Label forUpdater = mm.label();
            forUpdaterVar.ifTrue(forUpdater);
            planVar.set(sourceTableVar.invoke("scannerPlan", txnVar, sourceQueryStr, argsVar));
            Label ready = mm.label().goto_();
            forUpdater.here();
            planVar.set(sourceTableVar.invoke("updaterPlan", txnVar, sourceQueryStr, argsVar));
            ready.here();

            var targetVar = mm.var(Class.class).set(targetType).invoke("getName");
            var mapperVar = tableVar.invoke("mapper");
            var usingVar = mapperVar.invoke("toString");

            var mapperPlanVar = mm.new_(QueryPlan.Mapper.class, targetVar, usingVar, planVar);
            planVar.set(mapperVar.invoke("plan", mapperPlanVar));

            if (targetRemainder != TrueFilter.THE) {
                planVar.set(mm.new_(QueryPlan.Filter.class, targetRemainder.toString(), planVar));
            }

            if (sortPlan != null && sortPlan.sortOrder != null) {
                var columnsVar = mm.var(OrderBy.class).invoke("splitSpec", sortPlan.sortOrderSpec);
                planVar.set(mm.new_(QueryPlan.Sort.class, columnsVar, planVar));
            }

            mm.return_(planVar);
        }

        try {
            MethodHandles.Lookup lookup = cm.finishHidden();
            MethodHandle mh = lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class));
            return (ScannerFactory<S, T>) mh.invoke();
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    public interface ScannerFactory<S, T> {
        Scanner<T> newScannerWith(MappedTable<S, T> table,
                                  Transaction txn, T targetRow, Object... args)
            throws IOException;

        Updater<T> newUpdaterWith(MappedTable<S, T> table,
                                  Transaction txn, T targetRow, Object... args)
            throws IOException;

        QueryPlan plan(boolean forUpdater, MappedTable<S, T> table, Transaction txn, Object... args)
            throws IOException;
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
    public final Mapper<S, T> mapper() {
        return mMapper;
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
     * Called by the generated ScannerFactory.
     *
     * @see SortedQueryLauncher#newUpdaterWith
     */
    public final Updater<T> newWrappedUpdater(ScannerFactory<S, T> factory,
                                              Transaction txn, T targetRow, Object... args)
        throws IOException
    {
        if (txn != null) {
            if (txn.lockMode() != LockMode.UNSAFE) {
                txn.enter();
            }

            Scanner<T> scanner;
            try {
                scanner = factory.newScannerWith(this, txn, targetRow, args);
                // Commit the transaction scope to promote and keep all the locks which were
                // acquired by the sort operation.
                txn.commit();
            } finally {
                txn.exit();
            }

            return new WrappedUpdater<>(this, txn, scanner);
        }

        // Need to create a transaction to acquire locks, but true auto-commit behavior isn't
        // really feasible because update order won't match lock acquisition order. In
        // particular, the locks cannot be released out of order. Instead, keep the transaction
        // open until the updater finishes and always commit. Unfortunately, if the commit
        // fails then all updates fail instead of just one.

        txn = newTransaction(null);

        Scanner<T> scanner;
        try {
            scanner = factory.newScannerWith(this, txn, targetRow, args);
        } catch (Throwable e) {
            txn.exit();
            throw e;
        }

        return new WrappedUpdater.EndCommit<>(this, txn, scanner);
    }

    public interface InverseMapper<S, T> {
        /**
         * @param sourceRow non null
         * @param targetRow non null
         * @throws IllegalStateException if any required target columns aren't set
         * @throws UnmodifiableViewException if inverse mapping isn't possible
         */
        void inverseMap(S sourceRow, T targetRow) throws IOException;

        /**
         * @param source only expected to be used for calling newRow
         * @param targetRow non null
         * @throws IllegalStateException if any required target columns aren't set
         * @throws UnmodifiableViewException if inverse mapping isn't possible
         */
        default S inverseMap(Table<S> source, T targetRow) throws IOException {
            S sourceRow = source.newRow();
            inverseMap(sourceRow, targetRow);
            return sourceRow;
        }

        /**
         * @param source only expected to be used for calling newRow
         * @param targetRow non null
         * @throws IllegalStateException if any required target columns aren't set
         * @throws ViewConstraintException if inverse mapping isn't possible
         */
        default S inverseMapForLoad(Table<S> source, T targetRow) throws IOException {
            return inverseMap(source, targetRow);
        }
    }

    public static class NoInverse implements InverseMapper {
        private static final NoInverse THE = new NoInverse();

        @SuppressWarnings("unchecked")
        public static <S, T> InverseMapper<S, T> instance() {
            return (InverseMapper<S, T>) THE;
        }

        private NoInverse() {
        }

        @Override
        public void inverseMap(Object sourceRow, Object targetRow) throws IOException {
            throw new UnmodifiableViewException();
        }

        @Override
        public Object inverseMap(Table source, Object targetRow) throws IOException {
            throw new UnmodifiableViewException();
        }

        @Override
        public Object inverseMapForLoad(Table source, Object targetRow) throws IOException {
            throw new ViewConstraintException();
        }
    }

    /**
     * @param function is null for identity mapping
     */
    private record ColumnFunction(ColumnInfo column, Method function) { }

    /**
     * Finds inverse mapping functions defined in a Mapper implementation.
     */
    private class InverseFinder {
        private final boolean mIdentity;
        private final Map<String, ColumnInfo> mSourceColumns;
        private final TreeMap<String, Method> mAllMethods;

        InverseFinder(Map<String, ColumnInfo> sourceColumns) {
            Class<? extends Mapper> mapperClass = mMapper.getClass();

            mIdentity = Mapper.Identity.class.isAssignableFrom(mapperClass);
            mSourceColumns = sourceColumns;

            mAllMethods = new TreeMap<>();
            for (Method m : mapperClass.getMethods()) {
                mAllMethods.put(m.getName(), m);
            }
        }

        ColumnFunction tryFindSource(ColumnInfo targetColumn) {
            String prefix = targetColumn.name + "_to_";

            for (Method candidate : mAllMethods.tailMap(prefix).values()) {
                String name = candidate.getName();
                if (!name.startsWith(prefix)) {
                    break;
                }

                if (!Modifier.isStatic(candidate.getModifiers())) {
                    continue;
                }

                Class<?> retType = candidate.getReturnType();
                if (retType == null || retType == void.class) {
                    continue;
                }

                Class<?>[] paramTypes = candidate.getParameterTypes();
                if (paramTypes.length != 1) {
                    continue;
                }

                if (!paramTypes[0].isAssignableFrom(targetColumn.type)) {
                    continue;
                }

                ColumnInfo sourceColumn = mSourceColumns.get(name.substring(prefix.length()));
                if (sourceColumn == null) {
                    continue;
                }

                if (!sourceColumn.type.isAssignableFrom(retType)) {
                    continue;
                }

                return new ColumnFunction(sourceColumn, candidate);
            }

            if (!mIdentity) {
                return null;
            }

            ColumnInfo sourceColumn = mSourceColumns.get(targetColumn.name);

            return sourceColumn == null ? null : new ColumnFunction(sourceColumn, null);
        }

        /**
         * @return null if nothing special needs to be done for sorting
         */
        SortPlan analyzeSort(Query targetQuery) {
            OrderBy targetOrder = targetQuery.orderBy();

            if (targetOrder == null) {
                return null;
            }

            var plan = new SortPlan();

            OrderBy sourceOrder = null;

            for (var rule : targetOrder.values()) {
                ColumnFunction source = tryFindSource(rule.column());
                if (source == null || source.function() != null) {
                    break;
                }
                if (sourceOrder == null) {
                    sourceOrder = new OrderBy();
                }
                ColumnInfo sourceColumn = source.column();
                sourceOrder.put(sourceColumn.name, new OrderBy.Rule(sourceColumn, rule.type()));
            }

            if (sourceOrder != null && sourceOrder.size() >= targetOrder.size()) {
                // Can push the entire sort operation to the source.
                plan.sourceOrder = sourceOrder;
                return plan;
            }

            /*
              Apply the entire sort operation on the target scanner. It's possible for a
              partial ordering to be performed on the source, it's a bit complicated. The key
              is to ensure that no sort operation is performed against the source. A double
              sort doesn't make any sense.

              For now partial ordering isn't supported, but here's what needs to happen:

              Examine the source query plan with the sourceOrder applied to it. If no sort is
              performed, then a partial sort is possible. Otherwise, examine the sort and
              remove the ordering columns that the sort applies to. Examine the surviving
              ordering columns, and keep the leading contiguous ones. If none remain, then no
              partial sort is possible. If a partial sort is still possible, examine the source
              query plan again to verify that no sort will be applied.

              The target sorter needs to be given the sourceComparator, which identifies row
              groups. The final sort only needs to apply these groups and not the whole set.
            */

            plan.sortOrder = targetOrder;
            String targetSpec = targetOrder.spec();
            plan.sortOrderSpec = targetSpec;
            plan.sortComparator = ComparatorMaker.comparator(rowType(), targetOrder, targetSpec);

            return plan;
        }
    }

    private static class SortPlan {
        // Optional ordering to apply to the source scanner.
        OrderBy sourceOrder;

        // Defines a partial order, and is required when sourceOrder and sortOrder is defined.
        //Comparator sourceComparator;

        // Optional sort order to apply to the target scanner.
        OrderBy sortOrder;

        // Is required when sortOrder is defined.
        String sortOrderSpec;

        // Is required when sortOrder is defined.
        Comparator sortComparator;
    }
}
