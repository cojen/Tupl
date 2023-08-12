/*
 *  Copyright (C) 2021-2022 Cojen.org
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

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.Cursor;
import org.cojen.tupl.Index;
import org.cojen.tupl.LockResult;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.Updater;

import org.cojen.tupl.core.RowPredicateLock;

import org.cojen.tupl.rows.filter.ColumnFilter;

/**
 * Makes Table classes that extend BaseTableIndex for secondary index tables that can join to
 * the primary table.
 *
 * @author Brian S O'Neill
 */
public class JoinedTableMaker extends TableMaker {
    final Class<?> mPrimaryTableClass;
    final Class<?> mUnjoinedClass;

    /**
     * Constructor for making a secondary index table.
     *
     * @param rowGen describes row encoding
     * @param codecGen describes key and value codecs (different than rowGen)
     * @param secondaryDesc secondary index descriptor
     * @param primaryTableClass the primary table implementation class
     * @param unjoinedClass the table implementation which is passed as the last constructor
     * parameter
     */
    JoinedTableMaker(Class<?> type, RowGen rowGen, RowGen codecGen, byte[] secondaryDesc,
                     Class<?> primaryTableClass, Class<?> unjoinedClass)
    {
        super(type, rowGen, codecGen, secondaryDesc);

        mPrimaryTableClass = primaryTableClass;
        mUnjoinedClass = unjoinedClass;
    }

    /**
     * Return a constructor which accepts a (Index, RowPredicateLock, TableImpl primary,
     * TableImpl unjoined) and returns a BaseTableIndex implementation.
     */
    MethodHandle finish() {
        Objects.requireNonNull(mPrimaryTableClass);

        mClassMaker = mCodecGen.beginClassMaker(getClass(), mRowType, "joined")
            .public_().final_().extend(mUnjoinedClass);

        MethodType mt = MethodType.methodType
            (void.class, Index.class, RowPredicateLock.class, mPrimaryTableClass, mUnjoinedClass);

        MethodMaker ctor = mClassMaker.addConstructor(mt);

        var indexVar = ctor.param(0);
        var lockVar = ctor.param(1);
        var primaryVar = ctor.param(2);
        var unjoinedVar = ctor.param(3);
        var managerVar = primaryVar.invoke("tableManager");

        ctor.invokeSuperConstructor(managerVar, indexVar, lockVar);

        mClassMaker.addField(mPrimaryTableClass, "primaryTable").private_().final_();
        ctor.field("primaryTable").set(primaryVar);

        mClassMaker.addField(Index.class, "primaryIndex").private_().final_();
        ctor.field("primaryIndex").set(managerVar.invoke("primaryIndex"));

        mClassMaker.addField(mUnjoinedClass, "unjoined").private_().final_();
        ctor.field("unjoined").set(unjoinedVar);

        {
            MethodMaker mm = mClassMaker.addMethod
                (BaseTable.class, "joinedPrimaryTable").protected_();
            mm.return_(mm.field("primaryTable"));
        }

        {
            MethodMaker mm = mClassMaker.addMethod
                (Class.class, "joinedPrimaryTableClass").protected_();
            mm.return_(mPrimaryTableClass);
        }

        {
            MethodMaker mm = mClassMaker.addMethod(Table.class, "viewUnjoined").protected_();
            mm.return_(mm.field("unjoined"));
        }

        {
            // Create three toPrimaryKey methods. A value param is always needed for alternate
            // keys but never for ordinary secondary indexes.
            int mode = mCodecGen.info.isAltKey() ? 0b011 : 0b010;
            while (mode <= 0b111) {
                addToPrimaryKeyMethod(mode);
                mode += 0b010;
            }
        }

        addJoinedLoadMethod();

        // Override the methods inherited from the unjoined class, defined in BaseTable.
        addJoinedUnfilteredMethods();

        // Override the methods inherited from the unjoined class, defined in ScanControllerFactory.
        addPlanMethod(0b10);
        addPlanMethod(0b11); // reverse option

        // Override the method inherited from BaseTableIndex.
        MethodMaker mm = mClassMaker.addMethod
            (Updater.class, "newUpdaterWith",
             Transaction.class, Object.class, ScanController.class);
        mm.protected_();
        mm.return_(mm.invoke("newJoinedUpdater", mm.param(0), mm.param(1), mm.param(2),
                             mm.field("primaryTable")));

        return doFinish(mt);
    }

    /**
     * Define a static method which encodes a primary key when given an encoded secondary key.
     * When a row parameter is defined, all key columns must be set.
     *
     *  000:  byte[] toPrimaryKey()               not used / illegal
     *  001:  byte[] toPrimaryKey(byte[] value)   not used
     *  010:  byte[] toPrimaryKey(byte[] key)
     *  011:  byte[] toPrimaryKey(byte[] key, byte[] value)
     *  100:  byte[] toPrimaryKey(Row row)
     *  101:  byte[] toPrimaryKey(Row row, byte[] value)
     *  110:  byte[] toPrimaryKey(Row row, byte[] key)
     *  111:  byte[] toPrimaryKey(Row row, byte[] key, byte[] value)
     *
     * @param options bit 2: pass a row with a fully specified secondary key, bit 1: pass an
     * encoded key, bit 0: pass an encoded value
     */
    private void addToPrimaryKeyMethod(int options) {
        var paramList = new ArrayList<Object>(3);
        if ((options & 0b100) != 0) {
            paramList.add(mRowClass); // row
        }
        if ((options & 0b010) != 0) {
            paramList.add(byte[].class); // key
        }
        if ((options & 0b001) != 0) {
            paramList.add(byte[].class); // value
        }

        Object[] params = paramList.toArray();

        MethodMaker mm = mClassMaker.addMethod(byte[].class, "toPrimaryKey", params).static_();

        var paramVars = new Object[params.length];
        for (int i=0; i<params.length; i++) {
            paramVars[i] = mm.param(i);
        }

        // Use indy to create on demand, reducing code bloat.
        var indy = mm.var(JoinedTableMaker.class).indy
            ("toPrimaryKey", mRowType, mSecondaryDescriptor, options);
        mm.return_(indy.invoke(byte[].class, "toPrimaryKey", null, paramVars));
    }

    public static CallSite toPrimaryKey(MethodHandles.Lookup lookup, String name, MethodType mt,
                                        Class<?> rowType, byte[] secondaryDesc, int options)
    {
        RowInfo primaryInfo = RowInfo.find(rowType);
        RowInfo secondaryInfo = RowStore.secondaryRowInfo(primaryInfo, secondaryDesc);

        MethodMaker mm = MethodMaker.begin(lookup, name, mt);

        Variable rowVar = null, keyVar = null, valueVar = null;
        int offset = 0;
        if ((options & 0b100) != 0) {
            rowVar = mm.param(offset++);
        }
        if ((options & 0b010) != 0) {
            keyVar = mm.param(offset++);
        }
        if ((options & 0b001) != 0) {
            valueVar = mm.param(offset++);
        }

        Map<String, TransformMaker.Availability> available = null;

        if (rowVar != null) {
            available = new HashMap<>();
            for (String colName : secondaryInfo.keyColumns.keySet()) {
                available.put(colName, TransformMaker.Availability.ALWAYS);
            }
        }

        var tm = new TransformMaker<>(rowType, secondaryInfo, available);
        tm.addKeyTarget(primaryInfo, 0, true);
        tm.begin(mm, rowVar, keyVar, valueVar, 0);
        mm.return_(tm.encode(0));

        return new ConstantCallSite(mm.finish());
    }

    private void addJoinedLoadMethod() {
        MethodMaker mm = mClassMaker.addMethod
            (boolean.class, "load", Transaction.class, Object.class).public_();

        Variable txnVar = mm.param(0);
        Variable rowVar = mm.param(1).cast(mRowClass);

        {
            Label ready = mm.label();
            mm.invoke("checkPrimaryKeySet", rowVar).ifTrue(ready);
            mm.new_(IllegalStateException.class, "Primary key isn't fully specified").throw_();
            ready.here();
        }

        Variable valueVar = null;
        Variable repeatableVar = null;
        final Variable joinedPkVar;

        Label notFound = mm.label();

        if (mCodecGen.info.isAltKey()) {
            var keyVar = mm.invoke("encodePrimaryKey", rowVar);
            valueVar = mm.field("mSource").invoke("load", txnVar, keyVar);
            valueVar.ifEq(null, notFound);
            joinedPkVar = mm.invoke("toPrimaryKey", rowVar, keyVar, valueVar);
        } else {
            joinedPkVar = mm.var(byte[].class);
            Label cont = mm.label();
            Label repeatable = mm.label();
            mm.var(RowUtils.class).invoke("isRepeatable", txnVar).ifTrue(repeatable);
            joinedPkVar.set(mm.invoke("toPrimaryKey", rowVar));
            mm.goto_(cont);
            repeatable.here();
            var keyVar = mm.invoke("encodePrimaryKey", rowVar);
            // Calling exists is necessary for proper lock acquisition order. A return value of
            // false isn't expected, but handle it just in case.
            mm.field("mSource").invoke("exists", txnVar, keyVar).ifFalse(notFound);
            joinedPkVar.set(mm.invoke("toPrimaryKey", rowVar, keyVar));
            cont.here();
        }

        var joinedValueVar = mm.field("primaryIndex").invoke("load", txnVar, joinedPkVar);

        Label notNull = mm.label();
        joinedValueVar.ifNe(null, notNull);

        notFound.here();
        markValuesUnset(rowVar);
        mm.return_(false);

        notNull.here();

        if (repeatableVar == null) {
            repeatableVar = mm.var(RowUtils.class).invoke("isRepeatable", txnVar);
        }

        Label checked = mm.label();
        repeatableVar.ifFalse(checked);

        if (valueVar != null) {
            // Decode the primary key columns (required by alt key only).
            mm.invoke("decodeValue", rowVar, valueVar);
        }

        mm.var(mPrimaryTableClass).invoke("decodeValue", rowVar, joinedValueVar);

        Label success = mm.label().here();
        markAllClean(rowVar, mRowGen, mRowGen);
        mm.return_(true);

        // This point is reached for double checking that the joined row matches to the
        // secondary row, which is required when a lock isn't held.
        checked.here();

        // Copy of all the columns which will be modified by decodeValue.
        Map<String, ColumnInfo> copiedColumns;
        if (valueVar != null) {
            // For alt key, the primary key columns will be modified too.
            copiedColumns = mRowInfo.allColumns;
        } else {
            copiedColumns = mRowInfo.valueColumns;
        }
        Map<String, Variable> copiedVars = new LinkedHashMap<>(copiedColumns.size());
        for (String name : copiedColumns.keySet()) {
            copiedVars.put(name, rowVar.field(name).get());
        }

        if (valueVar != null) {
            // For alt key, decode the primary key columns too.
            mm.invoke("decodeValue", rowVar, valueVar);
        }

        mm.var(mPrimaryTableClass).invoke("decodeValue", rowVar, joinedValueVar);

        // Check all the secondary columns, except those that refer to the primary key, which
        // won't have changed.
        Label fail = mm.label();
        Map<String, ColumnInfo> pkColumns = mRowInfo.keyColumns;
        for (ColumnInfo column : mCodecGen.info.allColumns.values()) {
            String name = column.name;
            if (pkColumns.containsKey(name)) {
                continue;
            }
            Label pass = mm.label();
            // Note that the secondary columns are passed as the compare arguments, because
            // that's what they effectively are -- a type of filter expression. This is
            // important because the comparison isn't necessarily symmetrical. See
            // BigDecimalUtils.matches.
            CompareUtils.compare(mm, column, rowVar.field(name),
                                 copiedColumns.get(name), copiedVars.get(name),
                                 ColumnFilter.OP_EQ, pass, fail);
            pass.here();
        }

        mm.goto_(success);

        fail.here();

        // Restore all the columns back to their original values, preventing any side-effects.
        // When the load method returns false, it's not supposed to modify any columns,
        // regardless of their state.
        for (Map.Entry<String, Variable> e : copiedVars.entrySet()) {
            rowVar.field(e.getKey()).set(e.getValue());
        }

        mm.goto_(notFound);
    }

    /**
     * Defines methods which return a JoinedScanController instance.
     */
    private void addJoinedUnfilteredMethods() {
        MethodMaker mm = mClassMaker.addMethod
            (SingleScanController.class, "unfiltered").protected_();
        var condyClass = mm.var(JoinedTableMaker.class).condy
            ("condyDefineJoinedUnfiltered", mRowType, mRowClass,
             mSecondaryDescriptor, mm.class_(), mPrimaryTableClass);
        var scanControllerCtor = condyClass.invoke(MethodHandle.class, "unfiltered");
        Class<?>[] paramTypes = {
            byte[].class, boolean.class, byte[].class, boolean.class, boolean.class, Index.class
        };
        mm.return_(scanControllerCtor.invoke
                   (JoinedScanController.class, "invokeExact", paramTypes,
                    null, false, null, false, false, mm.field("primaryIndex")));

        mm = mClassMaker.addMethod(SingleScanController.class, "unfilteredReverse").protected_();
        // Use the same scanControllerCtor constant, but must set against the correct maker.
        scanControllerCtor = mm.var(MethodHandle.class).set(scanControllerCtor);
        mm.return_(scanControllerCtor.invoke
                   (JoinedScanController.class, "invokeExact", paramTypes,
                    null, false, null, false, true, mm.field("primaryIndex")));
    }

    /**
     * Makes a subclass of JoinedScanController with matching constructors.
     *
     * @return the basic constructor handle
     */
    public static MethodHandle condyDefineJoinedUnfiltered
        (MethodHandles.Lookup lookup, String name, Class type,
         Class rowType, Class rowClass, byte[] secondaryDesc,
         Class<?> tableClass, Class<?> primaryTableClass)
        throws Throwable
    {
        RowInfo rowInfo = RowInfo.find(rowType);
        RowGen rowGen = rowInfo.rowGen();
        RowGen codecGen = RowStore.secondaryRowInfo(rowInfo, secondaryDesc).rowGen();

        ClassMaker cm = RowGen.beginClassMaker
            (JoinedTableMaker.class, rowType, rowInfo, null, name)
            .extend(JoinedScanController.class).public_();

        // Constructors are protected, for use by filter implementation subclasses.
        final MethodType ctorType;
        {
            ctorType = MethodType.methodType
                (void.class, byte[].class, boolean.class, byte[].class, boolean.class,
                 boolean.class, Index.class);
            MethodMaker mm = cm.addConstructor(ctorType).protected_();
            mm.invokeSuperConstructor
                (mm.param(0), mm.param(1), mm.param(2), mm.param(3), mm.param(4), mm.param(5));

            // Define a reverse scan copy constructor.
            mm = cm.addConstructor(JoinedScanController.class).protected_();
            mm.invokeSuperConstructor(mm.param(0));
        }

        // Provide access to the toPrimaryKey method to be accessible by filter implementation
        // subclasses, which are defined in a different package.
        if (codecGen.info.isAltKey()) {
            MethodMaker mm = cm.addMethod(byte[].class, "toPrimaryKey", byte[].class, byte[].class);
            mm.static_().protected_();
            mm.return_(mm.var(tableClass).invoke("toPrimaryKey", mm.param(0), mm.param(1)));
        } else {
            MethodMaker mm = cm.addMethod(byte[].class, "toPrimaryKey", byte[].class);
            mm.static_().protected_();
            mm.return_(mm.var(tableClass).invoke("toPrimaryKey", mm.param(0)));
        }

        // Note regarding the RowEvaluator methods: The decode methods fully resolve rows
        // by joining to the primary table, and the encode methods return bytes for storing
        // into the primary table.

        // Specified by RowEvaluator.
        addJoinedDecodeRow(cm, codecGen, rowClass, tableClass, primaryTableClass, false);
        addJoinedDecodeRow(cm, codecGen, rowClass, tableClass, primaryTableClass, true);

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (Object.class, "decodeRow", Object.class, byte[].class, byte[].class).public_();
            var rowVar = CodeUtils.castOrNew(mm.param(0), rowClass);
            var tableVar = mm.var(primaryTableClass);
            tableVar.invoke("decodePrimaryKey", rowVar, mm.param(1));
            tableVar.invoke("decodeValue", rowVar, mm.param(2));
            tableVar.invoke("markAllClean", rowVar);
            mm.return_(rowVar);
        }

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (null, "writeRow", RowWriter.class, byte[].class, byte[].class).public_();
            mm.var(primaryTableClass).invoke("writeRow", mm.param(0), mm.param(1), mm.param(2));
        }

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (byte[].class, "updateKey", Object.class, byte[].class).public_();
            var rowVar = mm.param(0).cast(rowClass);
            var tableVar = mm.var(primaryTableClass);
            mm.return_(tableVar.invoke("updatePrimaryKey", rowVar, mm.param(1)));
        }

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (byte[].class, "updateValue", Object.class, byte[].class).public_();
            var rowVar = mm.param(0).cast(rowClass);
            var tableVar = mm.var(primaryTableClass);
            mm.return_(tableVar.invoke("updateValue", rowVar, mm.param(1)));
        }

        {
            // Used by filter subclasses when joining to the primary. The int param is the
            // schema version.
            MethodMaker mm = cm.addMethod
                (MethodHandle.class, "decodeValueHandle", int.class).protected_().static_();
            var tableVar = mm.var(primaryTableClass);
            mm.return_(tableVar.invoke("decodeValueHandle", mm.param(0)));
        }

        lookup = cm.finishLookup();

        MethodHandle mh = lookup.findConstructor(lookup.lookupClass(), ctorType);

        return mh.asType(mh.type().changeReturnType(JoinedScanController.class));
    }

    private static void addJoinedDecodeRow
        (ClassMaker cm, RowGen codecGen, Class rowClass, 
         Class<?> tableClass, Class<?> primaryTableClass, boolean withPrimaryCursor)
    {
        Object[] params;
        if (!withPrimaryCursor) {
            params = new Object[] {Cursor.class, LockResult.class, Object.class};
        } else {
            params = new Object[] {Cursor.class, LockResult.class, Object.class, Cursor.class};
        }

        MethodMaker mm = cm.addMethod(Object.class, "evalRow", params).public_();

        final var cursorVar = mm.param(0);
        final var resultVar = mm.param(1);
        final var rowVar = mm.param(2);
        final var keyVar = cursorVar.invoke("key");

        Variable primaryKeyVar;
        {
            var tableVar = mm.var(tableClass);
            if (codecGen.info.isAltKey()) {
                var valueVar = cursorVar.invoke("value");
                primaryKeyVar = tableVar.invoke("toPrimaryKey", keyVar, valueVar);
            } else {
                primaryKeyVar = tableVar.invoke("toPrimaryKey", keyVar);
            }
        }

        if (!withPrimaryCursor) {
            params = new Object[] {cursorVar, resultVar, primaryKeyVar};
        } else {
            params = new Object[] {cursorVar, resultVar, primaryKeyVar, mm.param(3)};
        }

        var primaryValueVar = mm.invoke("join", params);

        Label hasValue = mm.label();
        primaryValueVar.ifNe(null, hasValue);
        mm.return_(null);
        hasValue.here();

        final Label notRow = mm.label();
        final var typedRowVar = CodeUtils.castOrNew(rowVar, rowClass, notRow);

        var tableVar = mm.var(primaryTableClass);
        tableVar.invoke("decodePrimaryKey", typedRowVar, primaryKeyVar);
        tableVar.invoke("decodeValue", typedRowVar, primaryValueVar);
        tableVar.invoke("markAllClean", typedRowVar);
        mm.return_(typedRowVar);

        // Assume the passed in row is actually a RowConsumer.
        notRow.here();
        CodeUtils.acceptAsRowConsumerAndReturn(rowVar, rowClass, primaryKeyVar, primaryValueVar);
    }
}
