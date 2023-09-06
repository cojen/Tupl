/*
 *  Copyright (C) 2022 Cojen.org
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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.Table;

import org.cojen.tupl.core.Pair;

/**
 * @see Table#comparator
 * @author Brian S O'Neill
 */
public final class ComparatorMaker<R> {
    private static final WeakCache<Pair<Class<?>, String>, Comparator<?>, OrderBy> cCache;

    static {
        cCache = new WeakCache<>() {
            @Override
            public Comparator<?> newValue(Pair<Class<?>, String> key, OrderBy orderBy) {
                Class<?> rowType = key.a();
                if (orderBy != null) {
                    return new ComparatorMaker<>(rowType, orderBy).finish();
                } else {
                    String spec = key.b();
                    var maker = new ComparatorMaker<>(rowType, spec);
                    String canonical = maker.canonicalSpec();
                    if (spec.equals(canonical)) {
                        return maker.finish();
                    } else {
                        return obtain(new Pair<>(rowType, canonical), null);
                    }
                }
            }
        };
    }

    /**
     * Returns a new or cached comparator instance.
     */
    @SuppressWarnings("unchecked")
    public static <R> Comparator<R> comparator(Class<R> rowType, String spec) {
        return (Comparator<R>) cCache.obtain(new Pair<>(rowType, spec), null);
    }

    /**
     * Returns a new or cached comparator instance.
     *
     * @param spec must be orderBy.spec()
     */
    @SuppressWarnings("unchecked")
    public static <R> Comparator<R> comparator(Class<R> rowType, OrderBy orderBy, String spec) {
        return (Comparator<R>) cCache.obtain(new Pair<>(rowType, spec), orderBy);
    }

    private final Class<R> mRowType;
    private final RowInfo mRowInfo;
    private final OrderBy mOrderBy;

    /**
     * Constructor for primary key ordering.
     */
    ComparatorMaker(Class<R> rowType) {
        mRowType = rowType;
        mRowInfo = RowInfo.find(rowType);
        mOrderBy = OrderBy.forPrimaryKey(mRowInfo);
    }

    ComparatorMaker(Class<R> rowType, String spec) {
        mRowType = rowType;
        mRowInfo = RowInfo.find(rowType);
        mOrderBy = OrderBy.forSpec(mRowInfo, spec);
    }

    ComparatorMaker(Class<R> rowType, OrderBy orderBy) {
        mRowType = rowType;
        mRowInfo = RowInfo.find(rowType);
        mOrderBy = orderBy;
    }

    String canonicalSpec() {
        return mOrderBy.spec();
    }

    Comparator<R> finish() {
        Class rowClass = RowMaker.find(mRowType);
        ClassMaker cm = mRowInfo.rowGen().anotherClassMaker
            (ComparatorMaker.class, rowClass, "comparator").implement(Comparator.class).final_();

        // Keep a singleton instance, in order for a weakly cached reference to the comparator
        // to stick around until the class is unloaded.
        cm.addField(Comparator.class, "THE").private_().static_();

        MethodMaker mm = cm.addConstructor().private_();
        mm.invokeSuperConstructor();
        mm.field("THE").set(mm.this_());

        makeCompare(cm.addMethod(int.class, "compare", rowClass, rowClass).public_());

        // Now implement the bridge methods.

        mm = cm.addMethod(int.class, "compare", mRowType, mRowType).public_().bridge();
        mm.return_(mm.invoke("compare", mm.param(0).cast(rowClass), mm.param(1).cast(rowClass)));

        mm = cm.addMethod(int.class, "compare", Object.class, Object.class).public_().bridge();
        mm.return_(mm.invoke("compare", mm.param(0).cast(rowClass), mm.param(1).cast(rowClass)));

        try {
            MethodHandles.Lookup lookup = cm.finishHidden();
            MethodHandle mh = lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class));
            return (Comparator<R>) mh.invoke();
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    void makeCompare(MethodMaker mm) {
        makeCompare(mm, mOrderBy);
    }

    public static void makeCompare(MethodMaker mm, OrderBy orderBy) {
        var row0 = mm.param(0);
        var row1 = mm.param(1);

        Iterator<OrderBy.Rule> it = orderBy.values().iterator();
        while (it.hasNext()) {
            OrderBy.Rule rule = it.next();
            Label nextLabel = mm.label();

            Variable col0, col1;

            {
                Label isNull = mm.label();
                col0 = columnValue(rule, row0, isNull);
                Label ready = mm.label().goto_();
                isNull.here();
                // Goto nextLabel if col1 is also null (they're equal).
                columnValue(rule, row1, nextLabel);
                mm.return_((rule.isDescending() ^ rule.isNullLow()) ? -1 : 1);
                ready.here();
            }

            {
                Label isNull = mm.label();
                col1 = columnValue(rule, row1, isNull);
                Label ready = mm.label().goto_();
                isNull.here();
                mm.return_((rule.isDescending() ^ rule.isNullLow()) ? 1 : -1);
                ready.here();
            }

            if (rule.isDescending()) {
                var temp = col0;
                col0 = col1;
                col1 = temp;
            }

            ColumnInfo column = rule.column();
            Variable resultVar;

            if (column.isPrimitive()) {
                String compareName = column.isUnsignedInteger() ? "compareUnsigned" : "compare";
                resultVar = col0.invoke(compareName, col0, col1);
            } else {
                col0 = col0.get();
                col1 = col1.get();

                // Compare against nulls. Even though the column type might not support nulls,
                // be lenient. The row being compared might not be fully initialized.
                Label cont = mm.label();
                col0.ifNe(null, cont);
                col1.ifEq(null, nextLabel);
                mm.return_(rule.isNullLow() ? -1 : 1);
                cont.here();
                cont = mm.label();
                col1.ifNe(null, cont);
                mm.return_(rule.isNullLow() ? 1 : -1);
                cont.here();

                if (column.isArray()) {
                    String compareName = column.isUnsignedInteger() ? "compareUnsigned" : "compare";
                    resultVar = mm.var(Arrays.class).invoke(compareName, col0, col1);
                } else {
                    Class<?> colType = col0.classType();
                    if (Comparable.class.isAssignableFrom(colType)) {
                        resultVar = col0.invoke("compareTo", col1);
                    } else {
                        // Assume this is a join row, and so the column being compared is
                        // actually an ordinary row which doesn't implement Comparable.
                        String spec = OrderBy.forPrimaryKey(RowInfo.find(colType)).spec();
                        var cmpVar = mm.var(Comparator.class).setExact(comparator(colType, spec));
                        resultVar = cmpVar.invoke("compare", col0, col1);
                    }
                }
            }

            if (it.hasNext()) {
                resultVar.ifEq(0, nextLabel);
            }

            mm.return_(resultVar);

            nextLabel.here();
        }

        mm.return_(0);
    }

    /**
     * Generates code which follows a path to obtain a column value.
     *
     * @param isNull branch here if any path component is null.
     */
    private static Variable columnValue(OrderBy.Rule rule, Variable rowVar, Label isNull) {
        ColumnInfo ci = rule.column();
        String prefix = ci.prefix();

        if (prefix == null && ci.isScalarType()) {
            // Prefer accessing the field because the method can throw UnsetColumnException.
            return rowVar.field(ci.name);
        }

        while (true) {
            if (prefix == null) {
                return rowVar.invoke(ci.name);
            }
            rowVar = rowVar.invoke(prefix);
            rowVar.ifEq(null, isNull);
            ci = ci.tail();
            prefix = ci.prefix();
        }
    }
}
