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

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.lang.reflect.Method;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.UnsetColumnException;

import org.cojen.tupl.rows.codec.ColumnCodec;

/**
 * Makes code for the main Row implementation class.
 *
 * @author Brian S O'Neill
 */
public class RowMaker {
    // Maps rowType interface classes to implementation classes.
    private static final WeakClassCache<Class<?>> cache = new WeakClassCache<>();

    /**
     * Returns the Row implementation class, creating it if necessary.
     */
    @SuppressWarnings("unchecked")
    public static <R> Class<? extends R> find(Class<R> rowType) {
        Class clazz = cache.get(rowType);

        if (clazz == null) {
            synchronized (cache) {
                clazz = cache.get(rowType);
                if (clazz == null) {
                    clazz = new RowMaker(rowType, RowInfo.find(rowType).rowGen()).finish();
                    cache.put(rowType, clazz);
                }
            }
        }

        return clazz;
    }

    /**
     * Returns an uncached MethodHandle instance which populates all the row columns and marks
     * them clean. The signature looks like this:
     *
     * static R populate(R row, column...)
     *
     * If the given row is null (or an unknown type), then a new instance is made. Otherwise
     * the existing one is returned. The given columns are ordered lexicographically by name.
     */
    public static <R> MethodHandle makePopulator(Class<R> rowType) {
        Class<? extends R> rowClass = find(rowType);

        RowInfo rowInfo = RowInfo.find(rowType);
        RowGen rowGen = rowInfo.rowGen();

        ClassMaker cm = rowGen.beginClassMaker(null, rowType, "").final_();

        MethodType mt;
        {
            var paramTypes = new Class[1 + rowInfo.allColumns.size()];
            paramTypes[0] = rowType;
            int i = 1;
            for (ColumnInfo info : rowInfo.allColumns.values()) {
                paramTypes[i++] = info.type;
            }
            mt = MethodType.methodType(rowType, paramTypes);
        }

        MethodMaker mm = cm.addMethod("_", mt).static_();

        var rowVar = mm.var(rowClass);

        var rowParam = mm.param(0);
        Label correctType = mm.label();
        rowParam.instanceOf(rowClass).ifTrue(correctType);
        rowVar.set(mm.new_(rowClass));
        Label ready = mm.label().goto_();
        correctType.here();
        rowVar.set(rowParam.cast(rowClass));

        ready.here();

        int i = 1;
        for (ColumnInfo info : rowInfo.allColumns.values()) {
            rowVar.field(info.name).set(mm.param(i++));
        }

        TableMaker.markAllClean(rowVar, rowGen, rowGen);

        mm.return_(rowVar);

        MethodHandles.Lookup lookup = cm.finishHidden();

        try {
            return lookup.findStatic(lookup.lookupClass(), "_", mt);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // States used by the column state fields.
    //static final byte UNSET = 0b00, CLEAN = 0b01, DIRTY = 0b11;

    private final Class<?> mRowType;
    private final RowGen mRowGen;
    private final RowInfo mRowInfo;
    private final ClassMaker mClassMaker;

    private RowMaker(Class<?> rowType, RowGen rowGen) {
        mRowType = rowType;
        mRowGen = rowGen;
        mRowInfo = rowGen.info;
        mClassMaker = rowGen.beginClassMaker(getClass(), rowType, "")
            .implement(rowType).implement(Cloneable.class).final_().public_();
    }

    private Class<?> finish() {
        mClassMaker.addConstructor().public_();

        // Add column fields.
        for (ColumnInfo info : mRowInfo.allColumns.values()) {
            // Define fields as public such that they're accessible by generated code located
            // in different packages. Using different packages faciliates class unloading.
            mClassMaker.addField(info.type, info.name).public_();
        }

        // Add column state fields.
        for (String name : mRowGen.stateFields()) {
            mClassMaker.addField(int.class, name);
        }

        // Add column methods.
        {
            Map<String, Integer> columnNumbers = mRowGen.columnNumbers();
            for (ColumnInfo info : mRowInfo.allColumns.values()) {
                int num = columnNumbers.get(info.name);
                addAccessor(num, info);
                addMutator(num, info);
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

    private void addAccessor(int columnNum, ColumnInfo info) {
        if (info.accessor == null) {
            return;
        }
        MethodMaker mm = addMethod(info.accessor).public_();
        Label unset = mm.label();
        stateField(mm, columnNum).and(RowGen.stateFieldMask(columnNum)).ifEq(0, unset);
        mm.return_(mm.field(info.name));
        unset.here();
        mm.new_(UnsetColumnException.class, info.name).throw_();
    }

    private void addMutator(int columnNum, ColumnInfo info) {
        if (info.mutator == null) {
            return;
        }
        MethodMaker mm = addMethod(info.mutator).public_();
        if (!info.isNullable() && !info.isPrimitive()) {
            Label notNull = mm.label();
            mm.param(0).ifNe(null, notNull);
            mm.var(RowUtils.class).invoke("nullColumnException", info.name).throw_();
            notNull.here();
        }
        mm.field(info.name).set(mm.param(0));
        Field state = stateField(mm, columnNum);
        state.set(state.or(RowGen.stateFieldMask(columnNum))); // set dirty
    }

    /**
     * Generates hashCode, equals, and toString methods.
     */
    public static CallSite indyObjectMethod(MethodHandles.Lookup lookup, String name, MethodType mt,
                                            Class<?> rowType)
    {
        MethodMaker mm = MethodMaker.begin(lookup, name, mt);

        Class<?> retType = mt.returnType();
        if (retType == String.class) {
            addToString(mm, rowType, mm.param(0));
        } else if (retType == int.class) {
            addHashCode(mm, rowType, mm.param(0));
        } else {
            addEquals(mm, rowType, mm.param(0), mm.param(1));
        }

        return new ConstantCallSite(mm.finish());
    }

    private void addHashCode() {
        MethodMaker mm = mClassMaker.addMethod(int.class, "hashCode").public_();
        if (mRowInfo.allColumns.isEmpty()) {
            mm.return_(0);
        } else {
            var indy = mm.var(RowMaker.class).indy("indyObjectMethod", mRowType);
            mm.return_(indy.invoke(int.class, "hashCode", null, mm.this_()));
        }
    }

    private static void addHashCode(MethodMaker mm, Class<?> rowType, Variable rowObject) {
        RowInfo rowInfo = RowInfo.find(rowType);
        RowGen rowGen = rowInfo.rowGen();

        // Start with an initially complex hash, in case all the columns are reset.
        final var hash = mm.var(int.class).set(rowInfo.name.hashCode());

        // Hash in column states.
        String[] stateFields = rowInfo.rowGen().stateFields();
        for (String stateField : stateFields) {
            hash.set(hash.mul(31).add(rowObject.field(stateField)));
        }

        // Hash in column fields, except those that are unset.

        Map<String, Integer> columnNumbers = rowGen.columnNumbers();
        for (ColumnInfo info : rowInfo.allColumns.values()) {
            String name = info.name;

            Label unset = mm.label();
            int num = columnNumbers.get(name);
            rowObject.field(rowGen.stateField(num)).and(RowGen.stateFieldMask(num)).ifEq(0, unset);

            hash.set(hash.mul(31));
            Field field = rowObject.field(name);

            Class invoker;
            String method = "hashCode";

            if (info.type.isPrimitive()) {
                invoker = info.type;
            } else if (!info.type.isArray()) {
                invoker = Objects.class;
            } else {
                invoker = Arrays.class;
                if (info.type.getComponentType().isArray()) {
                    method = "deepHashCode";
                }
            }

            hash.inc(mm.var(invoker).invoke(method, field));

            unset.here();
        }

        mm.return_(hash);
    }

    private void addEquals() {
        MethodMaker mm = mClassMaker.addMethod(boolean.class, "equals", Object.class).public_();
        var indy = mm.var(RowMaker.class).indy("indyObjectMethod", mRowType);
        mm.return_(indy.invoke(boolean.class, "equals", null, mm.this_(), mm.param(0)));
    }

    private static void addEquals(MethodMaker mm, Class<?> rowType,
                                  Variable rowObject, Variable otherObject)
    {
        RowInfo rowInfo = RowInfo.find(rowType);
        RowGen rowGen = rowInfo.rowGen();

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

        // Compare column state fields.
        String[] stateFields = rowGen.stateFields();
        for (String name : stateFields) {
            rowObject.field(name).ifNe(other.field(name), notEqual);
        }

        // Compare column fields, except those that are unset.

        Map<String, Integer> columnNumbers = rowGen.columnNumbers();
        for (ColumnInfo info : rowInfo.allColumns.values()) {
            String name = info.name;

            // Only need to check one of the objects to see if the column is unset, because all
            // states are known to be identical at this point.
            Label unset = mm.label();
            int num = columnNumbers.get(name);
            rowObject.field(rowGen.stateField(num)).and(RowGen.stateFieldMask(num)).ifEq(0, unset);

            Field field = rowObject.field(name);
            Field otherField = other.field(name);
            if (info.type.isPrimitive()) {
                field.ifNe(otherField, notEqual);
            } else {
                String method = info.isArray() ? "deepEquals" : "equals";
                mm.var(Objects.class).invoke(method, field, otherField).ifFalse(notEqual);
            }

            unset.here();
        }

        mm.return_(true);

        notEqual.here();
        mm.return_(false);
    }

    private void addToString() {
        MethodMaker mm = mClassMaker.addMethod(String.class, "toString").public_();
        var indy = mm.var(RowMaker.class).indy("indyObjectMethod", mRowType);
        mm.return_(indy.invoke(String.class, "toString", null, mm.this_()));
    }

    private static void addToString(MethodMaker mm, Class<?> rowType, Variable rowObject) {
        RowInfo rowInfo = RowInfo.find(rowType);
        RowGen rowGen = rowInfo.rowGen();

        var bob = mm.new_(StringBuilder.class);

        var initSize = bob
            //.invoke("append", mm.var(Class.class).set(rowType).invoke("getName"))
            .invoke("append", '{').invoke("length");

        int num = 0;
        for (ColumnInfo info : rowInfo.keyColumns.values()) {
            append(mm, rowGen, rowObject, bob, initSize, num, info);
            num++;
        }
        for (ColumnCodec codec : rowGen.valueCodecs()) { // use encoding order
            append(mm, rowGen, rowObject, bob, initSize, num, codec.info);
            num++;
        }

        mm.return_(bob.invoke("append", '}').invoke("toString"));
    }

    private static void append(MethodMaker mm, RowGen rowGen, Variable rowObject,
                               Variable bob, Variable initSize, int num, ColumnInfo info)
    {
        if (info.hidden) {
            return;
        }

        Label unset = mm.label();
        int mask = RowGen.stateFieldMask(num);
        var stateVar = rowObject.field(rowGen.stateField(num)).and(mask);
        stateVar.ifEq(0, unset);
        Label sep = mm.label();
        bob.invoke("length").ifEq(initSize, sep);
        bob.invoke("append", ", ");
        sep.here();

        Label clean = mm.label();
        stateVar.ifNe(mask, clean);
        bob.invoke("append", '*');
        clean.here();

        bob.invoke("append", info.name).invoke("append", '=');
        CodeUtils.appendValue(bob, info, rowObject.field(info.name));

        unset.here();
    }

    private void addClone() {
        MethodMaker mm = mClassMaker.addMethod(mRowType, "clone").public_();
        var clone = mm.super_().invoke("clone").cast(mClassMaker);

        for (ColumnInfo info : mRowInfo.allColumns.values()) {
            if (info.isArray()) {
                Field field = clone.field(info.name);
                Label isNull = mm.label();
                field.ifEq(null, isNull);
                field.set(field.invoke("clone").cast(info.type));
                isNull.here();
            }
        }

        mm.return_(clone);

        // Now implement the bridge method.
        mm = mClassMaker.addMethod(Object.class, "clone").public_().bridge();
        mm.return_(mm.this_().invoke(mRowType, "clone", null));
    }

    private void addCompareTo() {
        if (!CompareUtils.needsCompareTo(mRowType)) {
            return;
        }

        MethodMaker mm = mClassMaker.addMethod(int.class, "compareTo", mClassMaker).public_();
        var indy = mm.var(RowMaker.class).indy("indyCompare", mRowType);
        mm.return_(indy.invoke(int.class, "compare", null, mm.this_(), mm.param(0)));

        // Now implement the bridge methods.

        mm = mClassMaker.addMethod(int.class, "compareTo", mRowType).public_().bridge();
        mm.return_(mm.this_().invoke("compareTo", mm.param(0).cast(mClassMaker)));

        mm = mClassMaker.addMethod(int.class, "compareTo", Object.class).public_().bridge();
        mm.return_(mm.this_().invoke("compareTo", mm.param(0).cast(mClassMaker)));
    }

    public static CallSite indyCompare(MethodHandles.Lookup lookup, String name, MethodType mt,
                                       Class<?> rowType)
    {
        MethodMaker mm = MethodMaker.begin(lookup, name, mt);
        new ComparatorMaker<>(rowType).makeCompare(mm);
        return new ConstantCallSite(mm.finish());
    }

    private Field stateField(MethodMaker mm, int columnNum) {
        return mm.field(mRowGen.stateField(columnNum));
    }

    private MethodMaker addMethod(Method m) {
        return mClassMaker.addMethod(m.getReturnType(), m.getName(),
                                     (Object[]) m.getParameterTypes());
    }
}
