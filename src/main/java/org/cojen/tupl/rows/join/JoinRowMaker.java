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

package org.cojen.tupl.rows.join;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.lang.reflect.Method;

import java.util.Objects;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.ComparatorMaker;
import org.cojen.tupl.rows.CompareUtils;
import org.cojen.tupl.rows.OrderBy;
import org.cojen.tupl.rows.RowGen;
import org.cojen.tupl.rows.RowInfo;
import org.cojen.tupl.rows.RowMaker;
import org.cojen.tupl.rows.WeakClassCache;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class JoinRowMaker {
    // Maps rowType interface classes to implementation classes.
    private static final WeakClassCache<Class<?>> cache = new WeakClassCache<>();

    /**
     * Returns the Row implementation class, creating it if necessary.
     */
    @SuppressWarnings("unchecked")
    public static <J> Class<? extends J> find(Class<J> rowType) {
        Class clazz = cache.get(rowType);

        if (clazz == null) {
            synchronized (cache) {
                clazz = cache.get(rowType);
                if (clazz == null) {
                    clazz = new JoinRowMaker(rowType, RowInfo.find(rowType)).finish();
                    cache.put(rowType, clazz);
                }
            }
        }

        return clazz;
    }

    private final Class<?> mJoinType;
    private final RowInfo mJoinInfo;
    private final ClassMaker mClassMaker;

    private JoinRowMaker(Class<?> joinType, RowInfo joinInfo) {
        for (ColumnInfo info : joinInfo.allColumns.values()) {
            if (info.isScalarType()) {
                throw new IllegalArgumentException
                    ("Join type cannot have any scalar columns: " + info.name);
            }
        }

        mJoinType = joinType;
        mJoinInfo = joinInfo;
        mClassMaker = RowGen.beginClassMaker(getClass(), joinType, joinInfo.name, null, null)
            .implement(joinType).implement(Cloneable.class).final_().public_();
    }

    private Class<?> finish() {
        mClassMaker.addConstructor().public_();

        // Add column fields.
        for (ColumnInfo info : mJoinInfo.allColumns.values()) {
            // Define fields as public such that they're accessible by generated code located
            // in different packages. Using different packages faciliates class unloading.
            mClassMaker.addField(info.type, info.name).public_();
        }

        // Add column methods.
        for (ColumnInfo info : mJoinInfo.allColumns.values()) {
            if (info.accessor != null) {
                MethodMaker mm = addMethod(info.accessor).public_();
                mm.return_(mm.field(info.name));
            }

            if (info.mutator != null) {
                MethodMaker mm = addMethod(info.mutator).public_();
                mm.field(info.name).set(mm.param(0));
            }
        }

        // Add common Object methods.
        addHashCode();
        addEquals();
        addToString();
        addClone();

        // Add Comparable methods.
        addCompareTo();

        return mClassMaker.finish();
    }

    /**
     * Generates hashCode, equals, toString, and clone methods.
     */
    public static CallSite indyObjectMethod(MethodHandles.Lookup lookup, String name, MethodType mt,
                                            Class<?> joinType)
    {
        MethodMaker mm = MethodMaker.begin(lookup, name, mt);

        Class<?> retType = mt.returnType();
        if (retType == String.class) {
            addToString(mm, joinType, mm.param(0));
        } else if (retType == int.class) {
            addHashCode(mm, joinType, mm.param(0));
        } else if (retType == boolean.class) {
            addEquals(mm, joinType, mm.param(0), mm.param(1));
        } else {
            addClone(mm, joinType, mm.param(0));
        }

        return new ConstantCallSite(mm.finish());
    }

    private void addHashCode() {
        MethodMaker mm = mClassMaker.addMethod(int.class, "hashCode").public_();
        var indy = mm.var(JoinRowMaker.class).indy("indyObjectMethod", mJoinType);
        mm.return_(indy.invoke(int.class, "hashCode", null, mm.this_()));
    }

    private static void addHashCode(MethodMaker mm, Class<?> joinType, Variable rowObject) {
        RowInfo joinInfo = RowInfo.find(joinType);

        // Start with an initially complex hash, in case all the columns are null.
        final var hash = mm.var(int.class).set(joinInfo.name.hashCode());

        int n = 0;
        for (ColumnInfo info : joinInfo.allColumns.values()) {
            if (n != 0) {
                hash.set(hash.mul(31));
            }
            hash.inc(mm.var(Objects.class).invoke("hashCode", rowObject.field(info.name)));
            n++;
        }

        mm.return_(hash);
    }

    private void addEquals() {
        MethodMaker mm = mClassMaker.addMethod(boolean.class, "equals", Object.class).public_();
        var indy = mm.var(JoinRowMaker.class).indy("indyObjectMethod", mJoinType);
        mm.return_(indy.invoke(boolean.class, "equals", null, mm.this_(), mm.param(0)));
    }

    private static void addEquals(MethodMaker mm, Class<?> joinType,
                                  Variable rowObject, Variable otherObject)
    {
        RowInfo joinInfo = RowInfo.find(joinType);

        // Quick check.
        {
            Label cont = mm.label();
            rowObject.ifNe(otherObject, cont);
            mm.return_(true);
            cont.here();
        }

        Label notEqual = mm.label();

        otherObject.instanceOf(rowObject).ifFalse(notEqual);
        var other = otherObject.cast(rowObject);

        for (ColumnInfo info : joinInfo.allColumns.values()) {
            String name = info.name;
            Field field = rowObject.field(name);
            Field otherField = other.field(name);
            mm.var(Objects.class).invoke("equals", field, otherField).ifFalse(notEqual);
        }

        mm.return_(true);

        notEqual.here();
        mm.return_(false);
    }

    private void addToString() {
        MethodMaker mm = mClassMaker.addMethod(String.class, "toString").public_();
        var indy = mm.var(JoinRowMaker.class).indy("indyObjectMethod", mJoinType);
        mm.return_(indy.invoke(String.class, "toString", null, mm.this_()));
    }

    private static void addToString(MethodMaker mm, Class<?> joinType, Variable rowObject) {
        RowInfo joinInfo = RowInfo.find(joinType);

        var bob = mm.new_(StringBuilder.class)
            //.invoke("append", mm.var(Class.class).set(joinType).invoke("getName"))
            .invoke("append", '{');

        int n = 0;
        for (ColumnInfo info : joinInfo.allColumns.values()) {
            if (n != 0) {
                bob = bob.invoke("append", ", ");
            }
            bob = bob.invoke("append", info.name).invoke("append", '=')
                .invoke("append", rowObject.field(info.name));
            n++;
        }

        mm.return_(bob.invoke("append", '}').invoke("toString"));
    }

    private void addClone() {
        MethodMaker mm = mClassMaker.addMethod(mClassMaker, cloneMethodName(mJoinInfo)).private_();
        mm.return_(mm.super_().invoke("clone").cast(mClassMaker));

        mm = mClassMaker.addMethod(mClassMaker, "clone").public_();
        var indy = mm.var(JoinRowMaker.class).indy("indyObjectMethod", mJoinType);
        mm.return_(indy.invoke(mClassMaker, "clone", null, mm.this_()));

        // Now implement the bridge methods.

        mm = mClassMaker.addMethod(mJoinType, "clone").public_().bridge();
        mm.return_(mm.this_().invoke(mClassMaker, "clone", null));

        mm = mClassMaker.addMethod(Object.class, "clone").public_().bridge();
        mm.return_(mm.this_().invoke(mClassMaker, "clone", null));
    }

    private static void addClone(MethodMaker mm, Class<?> joinType, Variable rowObject) {
        RowInfo joinInfo = RowInfo.find(joinType);

        Variable clone = rowObject.invoke(cloneMethodName(joinInfo));

        for (ColumnInfo info : joinInfo.allColumns.values()) {
            Field field = clone.field(info.name);
            Variable fieldVar = field.get();

            Label cont = mm.label();
            fieldVar.ifEq(null, cont);

            Class<?> fieldType = fieldVar.classType();

            try {
                fieldType.getMethod("clone");
                field.set(fieldVar.invoke("clone").cast(fieldType));
            } catch (NoSuchMethodException e) {
                // No public clone method, but can still clone it if implementation is known.
                var fieldClass = RowMaker.find(fieldType);
                fieldVar.instanceOf(fieldClass).ifFalse(cont);
                field.set(fieldVar.cast(fieldClass).invoke("clone"));
            }

            cont.here();
        }

        mm.return_(clone);
    }

    /**
     * Returns a unique method name for invoking the inherited Object.clone method.
     */
    private static String cloneMethodName(RowInfo joinInfo) {
        String name = "clone$";
        while (joinInfo.allColumns.containsKey(name)) {
            name += '$';
        }
        return name;
    }

    private void addCompareTo() {
        if (!CompareUtils.needsCompareTo(mJoinType)) {
            return;
        }

        MethodMaker mm = mClassMaker.addMethod(int.class, "compareTo", mClassMaker).public_();
        var indy = mm.var(JoinRowMaker.class).indy("indyCompare", mJoinType);
        mm.return_(indy.invoke(int.class, "compare", null, mm.this_(), mm.param(0)));

        // Now implement the bridge methods.

        mm = mClassMaker.addMethod(int.class, "compareTo", mJoinType).public_().bridge();
        mm.return_(mm.this_().invoke("compareTo", mm.param(0).cast(mClassMaker)));

        mm = mClassMaker.addMethod(int.class, "compareTo", Object.class).public_().bridge();
        mm.return_(mm.this_().invoke("compareTo", mm.param(0).cast(mClassMaker)));
    }

    public static CallSite indyCompare(MethodHandles.Lookup lookup, String name, MethodType mt,
                                       Class<?> joinType)
    {
        MethodMaker mm = MethodMaker.begin(lookup, name, mt);
        OrderBy orderBy = OrderBy.forColumns(RowInfo.find(joinType).allColumns.values());
        ComparatorMaker.makeCompare(mm, orderBy);
        return new ConstantCallSite(mm.finish());
    }

    private MethodMaker addMethod(Method m) {
        return mClassMaker.addMethod(m.getReturnType(), m.getName(),
                                     (Object[]) m.getParameterTypes());
    }
}
