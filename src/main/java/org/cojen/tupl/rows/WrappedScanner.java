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

import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import java.util.function.Predicate;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.Scanner;

import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.TrueFilter;

/**
 * Wraps a Scanner and applies custom processing to each row.
 *
 * @author Brian S. O'Neill
 */
public abstract class WrappedScanner<R> implements Scanner<R> {

    public static interface Factory<R> {
        WrappedScanner<R> wrap(Scanner<R> source) throws IOException;
    }

    /**
     * Implementation which tests a predicate against each row.
     */
    public static class WithPredicate<R> extends WrappedScanner<R> {
        protected final Predicate<R> mPredicate;

        // Constructor for factory instances.
        protected WithPredicate() {
            mPredicate = null;
        }

        public WithPredicate(Scanner<R> source, Predicate<R> predicate) throws IOException {
            super(source);
            mPredicate = predicate;
        }

        @Override
        protected R process(R row) throws IOException {
            return mPredicate.test(row) ? row : null;
        }

        public static interface Factory<R> {
            WithPredicate<R> wrap(Scanner<R> source, Predicate<R> predicate) throws IOException;
        }

        private static final class SimpleFactory<R> implements Factory<R> {
            private static final SimpleFactory THE = new SimpleFactory();

            @Override
            public WithPredicate<R> wrap(Scanner<R> source, Predicate<R> predicate)
                throws IOException
            {
                var wrapped = new WithPredicate<R>(source, predicate);
                wrapped.init();
                return wrapped;
            }
        }
    }

    private record Key(Class<?> rowType, Set<String> projection, boolean withPredicate) { }

    private static final WeakCache<Key, Object, Map<String, ColumnInfo>> cCache;

    static {
        cCache = new WeakCache<>() {
            @Override
            public Object newValue(Key key, Map<String, ColumnInfo> projection) {
                Class<?> rowType = key.rowType();
                if (!key.withPredicate()) {
                    return makeForProjection(rowType, projection);
                } else {
                    return makeForProjectionAndPredicate(rowType, projection);
                }
            }
        };
    }

    /**
     * Returns a generated factory which applies the given projection to each row.
     */
    @SuppressWarnings("unchecked")
    public static <R> Factory<R> forProjection(Class<R> rowType,
                                               Map<String, ColumnInfo> projection)
    {
        Set<String> projSet = SortedQueryLauncher.canonicalize(projection.keySet());
        var key = new Key(rowType, projSet, false);
        return (Factory<R>) cCache.obtain(key, projection);
    }

    /**
     * Returns a generated factory which tests a predicate against each row, and then applies
     * the given projection to rows that pass the test.
     */
    @SuppressWarnings("unchecked")
    public static <R> WithPredicate.Factory<R> forProjectionAndPredicate
        (Class<R> rowType, Map<String, ColumnInfo> projection)
    {
        Set<String> projSet = SortedQueryLauncher.canonicalize(projection.keySet());
        var key = new Key(rowType, projSet, true);
        return (WithPredicate.Factory<R>) cCache.obtain(key, projection);
    }

    /**
     * Returns a factory which tests a predicate against each row.
     */
    @SuppressWarnings("unchecked")
    public static <R> WithPredicate.Factory<R> forPredicate() {
        return WithPredicate.SimpleFactory.THE;
    }

    /**
     * Generates code which possibly wraps a source scanner in order to test a predicate and
     * apply a projection.
     *
     * @param rowType the target row type
     * @param argsVar Object[] query arguments; used for making Predicate instances
     * @param sourceVar references the source scanner
     * @param filter defines the predicate; must not be FalseFilter
     * @param projection can be null if all columns are projected
     * @return the resulting scanner, which might be the original one
     */
    static Variable wrap(Class<?> rowType, Variable argsVar,
                         Variable sourceVar, RowFilter filter, Map<String, ColumnInfo> projection)
    {
        MethodMaker mm;
        WithPredicate.Factory<?> factory;

        if (projection == null) {
            if (filter == TrueFilter.THE) {
                return sourceVar;
            }
            mm = argsVar.methodMaker();
            factory = forPredicate();
        } else {
            mm = argsVar.methodMaker();

            if (filter == TrueFilter.THE) {
                return mm.var(Factory.class)
                    .setExact(forProjection(rowType, projection))
                    .invoke("wrap", sourceVar);
            }

            factory = forProjectionAndPredicate(rowType, projection);
        }

        var factoryVar = mm.var(WithPredicate.Factory.class).setExact(factory);

        MethodHandle mh = PlainPredicateMaker.predicateHandle(rowType, filter.toString());
        var predicateVar = argsVar.methodMaker().invoke(mh, argsVar);

        return factoryVar.invoke("wrap", sourceVar, predicateVar);
    }

    private static Factory<?> makeForProjection(Class<?> rowType,
                                                Map<String, ColumnInfo> projection)
    {
        RowInfo info = RowInfo.find(rowType);

        ClassMaker cm = info.rowGen().beginClassMaker
            (WrappedScanner.class, rowType, "wrapper").final_()
            .extend(WrappedScanner.class).implement(Factory.class);

        // Keep a singleton instance, in order for a weakly cached reference to the wrapper to
        // stick around until the class is unloaded.
        cm.addField(Object.class, "THE").private_().static_();

        // Add the constructor for the factory instance.
        MethodMaker ctor = cm.addConstructor().private_();
        ctor.invokeSuperConstructor();
        ctor.field("THE").set(ctor.this_());

        // Add the regular constructor.
        ctor = cm.addConstructor(Scanner.class).private_();
        ctor.invokeSuperConstructor(ctor.param(0));

        // Define the factory wrap method.
        MethodMaker mm = cm.addMethod(WrappedScanner.class, "wrap", Scanner.class).public_();
        var wrappedVar = mm.new_(cm, mm.param(0));
        wrappedVar.invoke("init");
        mm.return_(wrappedVar);

        // Define the all-important method which applies the projection.
        mm = cm.addMethod(Object.class, "process", Object.class).protected_();
        var rowVar = mm.param(0).cast(RowMaker.find(rowType));
        TableMaker.unset(info, rowVar, projection);
        mm.return_(rowVar);

        try {
            MethodHandles.Lookup lookup = cm.finishHidden();
            MethodHandle mh = lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class));
            return (Factory<?>) mh.invoke();
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    private static WithPredicate.Factory<?> makeForProjectionAndPredicate
        (Class<?> rowType, Map<String, ColumnInfo> projection)
    {
        RowInfo info = RowInfo.find(rowType);

        ClassMaker cm = info.rowGen().beginClassMaker
            (WrappedScanner.class, rowType, "wrapper").final_()
            .extend(WrappedScanner.WithPredicate.class).implement(WithPredicate.Factory.class);

        // Keep a singleton instance, in order for a weakly cached reference to the wrapper to
        // stick around until the class is unloaded.
        cm.addField(Object.class, "THE").private_().static_();

        // Add the constructor for the factory instance.
        MethodMaker ctor = cm.addConstructor().private_();
        ctor.invokeSuperConstructor();
        ctor.field("THE").set(ctor.this_());

        // Add the regular constructor.
        ctor = cm.addConstructor(Scanner.class, Predicate.class).private_();
        ctor.invokeSuperConstructor(ctor.param(0), ctor.param(1));

        // Define the factory wrap method.
        MethodMaker mm = cm.addMethod
            (WrappedScanner.WithPredicate.class, "wrap", Scanner.class, Predicate.class).public_();
        var wrappedVar = mm.new_(cm, mm.param(0), mm.param(1));
        wrappedVar.invoke("init");
        mm.return_(wrappedVar);

        // Define the all-important method which applies the projection.
        mm = cm.addMethod(Object.class, "process", Object.class).protected_();
        var rowVar = mm.param(0).cast(RowMaker.find(rowType));

        Label pass = mm.label();
        mm.field("mPredicate").invoke("test", rowVar).ifTrue(pass);
        mm.return_(null);
        pass.here();

        TableMaker.unset(info, rowVar, projection);
        mm.return_(rowVar);

        try {
            MethodHandles.Lookup lookup = cm.finishHidden();
            MethodHandle mh = lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class));
            return (WithPredicate.Factory<?>) mh.invoke();
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    protected final Scanner<R> mSource;

    protected R mRow;

    // Constructor for factory instances.
    protected WrappedScanner() {
        mSource = null;
    }

    /**
     * Constructor for regular instances. Must call init afterwards.
     */
    protected WrappedScanner(Scanner<R> source) {
        mSource = source;
    }

    protected void init() throws IOException {
        try {
            R row = mSource.row();
            if (row != null) {
                R processedRow = process(row);
                if (processedRow != null) {
                    mRow = processedRow;
                } else {
                    step(row);
                }
            }
        } catch (Throwable e) {
            try {
                close();
            } catch (Throwable e2) {
                RowUtils.suppress(e, e2);
            }
            throw e;
        }
    }

    @Override
    public R row() {
        return mRow;
    }

    @Override
    public R step(R row) throws IOException {
        try {
            Scanner<R> source = mSource;
            while (true) {
                row = source.step(row);
                if (row == null) {
                    mRow = null;
                    return null;
                }
                R processedRow = process(row);
                if (processedRow != null) {
                    mRow = processedRow;
                    return processedRow;
                }
            }
        } catch (Throwable e) {
            try {
                close();
            } catch (Throwable e2) {
                RowUtils.suppress(e, e2);
            }
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        mRow = null;
        mSource.close();
    }

    @Override
    public long estimateSize() {
        return mSource.estimateSize();
    }

    @Override
    public int characteristics() {
        return mSource.characteristics();
    }

    @Override
    public Comparator<? super R> getComparator() {
        return mSource.getComparator();
    }

    /**
     * Is called for each row.
     *
     * @param row not null
     * @return null if row is filtered out
     */
    protected R process(R row) throws IOException {
        return row;
    }
}
