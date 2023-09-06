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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.Cursor;
import org.cojen.tupl.Entry;
import org.cojen.tupl.LockResult;
import org.cojen.tupl.Transaction;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.rows.codec.ColumnCodec;

/**
 * Base class for StaticTableMaker, DynamicTableMaker, and JoinedTableMaker.
 *
 * @author Brian S O'Neill
 */
public class TableMaker {
    protected final Class<?> mRowType;
    protected final RowGen mRowGen;
    protected final RowInfo mRowInfo;
    protected final RowGen mCodecGen;
    protected final Class<?> mRowClass;
    protected final byte[] mSecondaryDescriptor;

    protected ClassMaker mClassMaker;

    /**
     * @param rowGen describes row encoding
     * @param codecGen describes key and value codecs (can be different than rowGen)
     * @param secondaryDesc secondary index descriptor
     */
    TableMaker(Class<?> type, RowGen rowGen, RowGen codecGen, byte[] secondaryDesc) {
        mRowType = type;
        mRowGen = rowGen;
        mRowInfo = rowGen.info;
        mCodecGen = codecGen;
        mRowClass = RowMaker.find(type);
        mSecondaryDescriptor = secondaryDesc;
    }

    protected MethodHandle doFinish(MethodType mt) {
        try {
            var lookup = mClassMaker.finishLookup();
            return lookup.findConstructor(lookup.lookupClass(), mt);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    protected boolean isPrimaryTable() {
        return mRowGen == mCodecGen;
    }

    protected boolean supportsTriggers() {
        // Triggers expect that values have a schema version encoded. Only evolvable types have
        // a schema version encoded. Therefore, to have triggers, the type must be evolvable.
        return isEvolvable();
    }

    protected boolean isEvolvable() {
        return isPrimaryTable() && mRowType != Entry.class;
    }

    /**
     * @return null if no field is defined for the column (probably SchemaVersionColumnCodec)
     */
    protected static Field findField(Variable row, ColumnCodec codec) {
        ColumnInfo info = codec.info;
        return info == null ? null : row.field(info.name);
    }

    /**
     * Copies fields from a source row to a destination row.
     *
     * @param infos specifies the fields to copy
     */
    protected static void copyFields(Variable srcRow, Variable dstRow,
                                     Collection<ColumnInfo> infos)
    {
        for (ColumnInfo info : infos) {
            Variable srcField = srcRow.field(info.name);

            if (info.isArray()) {
                srcField = srcField.get();
                Label isNull = null;
                if (info.isNullable()) {
                    isNull = srcRow.methodMaker().label();
                    srcField.ifEq(null, isNull);
                }
                srcField.set(srcField.invoke("clone").cast(info.type));
                if (isNull != null) {
                    isNull.here();
                }
            }

            dstRow.field(info.name).set(srcField);
        }
    }

    protected void markAllClean(Variable rowVar) {
        markAllClean(rowVar, mRowGen, mCodecGen);
    }

    protected static void markAllClean(Variable rowVar, RowGen rowGen, RowGen codecGen) {
        if (rowGen == codecGen) { // isPrimaryTable, so truly mark all clean
            int mask = 0x5555_5555;
            int i = 0;
            String[] stateFields = rowGen.stateFields();
            for (; i < stateFields.length - 1; i++) {
                rowVar.field(stateFields[i]).set(mask);
            }
            mask >>>= (32 - ((rowGen.info.allColumns.size() & 0b1111) << 1));
            rowVar.field(stateFields[i]).set(mask);
        } else {
            // Only mark columns clean that are defined by codecGen. All others are unset.
            markClean(rowVar, rowGen, codecGen.info.allColumns);
        }
    }

    /**
     * Mark only the given columns as CLEAN. All others are UNSET.
     */
    protected static void markClean(final Variable rowVar, final RowGen rowGen,
                                    final Map<String, ColumnInfo> columns)
    {
        markClean(rowVar, rowGen, columns.keySet());
    }

    /**
     * Mark only the given columns as CLEAN. All others are UNSET.
     */
    protected static void markClean(final Variable rowVar, final RowGen rowGen,
                                    final Set<String> columnNames)
    {
        final int maxNum = rowGen.info.allColumns.size();

        int num = 0, mask = 0;

        for (int step = 0; step < 2; step++) {
            // Key columns are numbered before value columns. Add checks in two steps.
            // Note that the codecs are accessed, to match encoding order.
            var baseCodecs = step == 0 ? rowGen.keyCodecs() : rowGen.valueCodecs();

            for (ColumnCodec codec : baseCodecs) {
                if (columnNames.contains(codec.info.name)) {
                    mask |= RowGen.stateFieldMask(num, 0b01); // clean state
                }
                if ((++num & 0b1111) == 0 || num >= maxNum) {
                    rowVar.field(rowGen.stateField(num - 1)).set(mask);
                    mask = 0;
                }
            }
        }
    }

    /**
     * Remaining states are UNSET or CLEAN.
     */
    protected static void markAllUndirty(Variable rowVar, RowInfo info) {
        int mask = 0x5555_5555;
        int i = 0;
        String[] stateFields = info.rowGen().stateFields();
        for (; i < stateFields.length - 1; i++) {
            var field = rowVar.field(stateFields[i]);
            field.set(field.and(mask));
        }
        mask >>>= (32 - ((info.allColumns.size() & 0b1111) << 1));
        var field = rowVar.field(stateFields[i]);
        field.set(field.and(mask));
    }

    /**
     * Mark all the value columns as UNSET without modifying the key column states.
     */
    protected void markValuesUnset(Variable rowVar) {
        if (isPrimaryTable()) {
            mRowGen.markNonPrimaryKeyColumnsUnset(rowVar);
        } else {
            // If acting on an alternate key or secondary index, then the key/value columns are
            // different.
            markUnset(rowVar, mRowGen, mCodecGen.info.keyColumns);
        }
    }

    /**
     * Mark all columns except for the excluded ones as UNSET.
     */
    protected static void markUnset(final Variable rowVar, final RowGen rowGen,
                                    final Map<String, ColumnInfo> exclude)
    {
        final int maxNum = rowGen.info.allColumns.size();

        int num = 0, mask = 0;

        for (int step = 0; step < 2; step++) {
            // Key columns are numbered before value columns. Add checks in two steps.
            // Note that the codecs are accessed, to match encoding order.
            var baseCodecs = step == 0 ? rowGen.keyCodecs() : rowGen.valueCodecs();

            for (ColumnCodec codec : baseCodecs) {
                if (!exclude.containsKey(codec.info.name)) {
                    mask |= RowGen.stateFieldMask(num);
                }
                if ((++num & 0b1111) == 0 || num >= maxNum) {
                    Field field = rowVar.field(rowGen.stateField(num - 1));
                    mask = ~mask;
                    if (mask == 0) {
                        field.set(mask);
                    } else {
                        field.set(field.and(mask));
                        mask = 0;
                    }
                }
            }
        }
    }

    /**
     * Unset all columns except for the excluded ones.
     *
     * @param rowVar type must be the row implementation class
     */
    protected static void unset(RowInfo info, Variable rowVar, Map<String, ColumnInfo> excluded) {
        markUnset(rowVar, info.rowGen(), excluded);

        // Clear the unset target column fields that refer to objects.

        for (ColumnInfo target : info.allColumns.values()) {
            String name = target.name;
            if (!excluded.containsKey(name) && !target.type.isPrimitive()) {
                rowVar.field(name).set(null);
            }
        }
    }

    /**
     * Makes code which obtains the current trigger and acquires the lock which must be held
     * for the duration of the operation. The lock must be held even if no trigger must be run.
     *
     * @param triggerVar type is Trigger and is assigned by the generated code
     * @param skipLabel label to branch when trigger shouldn't run
     */
    protected static void prepareForTrigger(MethodMaker mm, Variable tableVar,
                                            Variable triggerVar, Label skipLabel)
    {
        Label acquireTriggerLabel = mm.label().here();
        triggerVar.set(tableVar.invoke("trigger"));
        triggerVar.invoke("acquireShared");
        var modeVar = triggerVar.invoke("mode");
        modeVar.ifEq(Trigger.SKIP, skipLabel);
        Label activeLabel = mm.label();
        modeVar.ifNe(Trigger.DISABLED, activeLabel);
        triggerVar.invoke("releaseShared");
        mm.goto_(acquireTriggerLabel);
        activeLabel.here();
    }

    /**
     * Defines a static method which returns a new composite byte[] key or value. Caller must
     * check that the columns are set.
     *
     * @param name method name
     */
    protected void addEncodeColumnsMethod(String name, ColumnCodec[] codecs) {
        MethodMaker mm = mClassMaker.addMethod(byte[].class, name, mRowClass).static_();

        if (mRowType == Entry.class && codecs.length == 1) {
            // No need to encode anything -- just return the byte[] reference directly.
            mm.return_(mm.param(0).field(codecs[0].info.name));
            return;
        }

        addEncodeColumns(mm, ColumnCodec.bind(codecs, mm));
    }

    /**
     * @param mm param(0): Row object, return: byte[]
     * @param codecs must be bound to the MethodMaker
     */
    protected static void addEncodeColumns(MethodMaker mm, ColumnCodec[] codecs) {
        if (codecs.length == 0) {
            mm.return_(mm.var(RowUtils.class).field("EMPTY_BYTES"));
            return;
        }

        // Determine the minimum byte array size and prepare the encoders.
        int minSize = 0;
        for (ColumnCodec codec : codecs) {
            minSize += codec.minSize();
            codec.encodePrepare();
        }

        // Generate code which determines the additional runtime length.
        Variable totalVar = null;
        for (ColumnCodec codec : codecs) {
            Field srcVar = findField(mm.param(0), codec);
            totalVar = codec.encodeSize(srcVar, totalVar);
        }

        // Generate code which allocates the destination byte array.
        Variable dstVar;
        if (totalVar == null) {
            dstVar = mm.new_(byte[].class, minSize);
        } else {
            if (minSize != 0) {
                totalVar = totalVar.add(minSize);
            }
            dstVar = mm.new_(byte[].class, totalVar);
        }

        // Generate code which fills in the byte array.
        var offsetVar = mm.var(int.class).set(0);
        for (ColumnCodec codec : codecs) {
            codec.encode(findField(mm.param(0), codec), dstVar, offsetVar);
        }

        mm.return_(dstVar);
    }

    /**
     * Defines a static method which decodes columns from a composite byte[] parameter.
     *
     * @param name method name
     */
    protected void addDecodeColumnsMethod(String name, ColumnCodec[] codecs) {
        MethodMaker mm = mClassMaker.addMethod(null, name, mRowClass, byte[].class)
            .static_().public_();

        if (mRowType == Entry.class && codecs.length == 1) {
            // No need to decode anything -- just copy the byte[] reference directly.
            mm.param(0).field(codecs[0].info.name).set(mm.param(1));
            return;
        }

        addDecodeColumns(mm, mRowInfo, codecs, 0);
    }

    /**
     * @param mm param(0): Row object, param(1): byte[], return: void
     * @param fixedOffset must be after the schema version (when applicable)
     */
    protected static void addDecodeColumns(MethodMaker mm, RowInfo dstRowInfo,
                                           ColumnCodec[] srcCodecs, int fixedOffset)
    {
        srcCodecs = ColumnCodec.bind(srcCodecs, mm);

        Variable srcVar = mm.param(1);
        Variable offsetVar = mm.var(int.class).set(fixedOffset);

        for (ColumnCodec srcCodec : srcCodecs) {
            String name = srcCodec.info.name;
            ColumnInfo dstInfo = dstRowInfo.allColumns.get(name);

            if (dstInfo == null) {
                srcCodec.decodeSkip(srcVar, offsetVar, null);
            } else {
                var rowVar = mm.param(0);
                Field dstVar = rowVar.field(name);
                Converter.decodeLossy(mm, srcVar, offsetVar, null, srcCodec, dstInfo, dstVar);
            }
        }
    }

    protected static class UpdateEntry {
        Variable newEntryVar;  // byte[]
        Variable[] offsetVars; // int offsets
    }

    /**
     * Makes code which encodes a new key by comparing dirty row columns to the original
     * encoded key. Returns the new entry and the column offsets from the original entry.
     *
     * @param rowVar non-null
     * @param tableVar doesn't need to be initialized (is used to invoke static methods)
     * @param originalVar original non-null encoded key
     */
    protected static UpdateEntry encodeUpdateKey
        (MethodMaker mm, RowInfo rowInfo, Variable tableVar, Variable rowVar, Variable originalVar)
    {
        RowGen rowGen = rowInfo.rowGen();
        ColumnCodec[] codecs = rowGen.keyCodecs();
        return encodeUpdateEntry(mm, rowGen, codecs, 0, tableVar, rowVar, originalVar);
    }

    /**
     * Makes code which encodes a new value by comparing dirty row columns to the original
     * encoded value. Returns the new entry and the column offsets from the original entry.
     *
     * @param schemaVersion pass 0 if value has no schema version to decode
     * @param rowVar non-null
     * @param tableVar doesn't need to be initialized (is used to invoke static methods)
     * @param originalVar original non-null encoded value
     */
    protected static UpdateEntry encodeUpdateValue
        (MethodMaker mm, RowInfo rowInfo, int schemaVersion,
         Variable tableVar, Variable rowVar, Variable originalVar)
    {
        RowGen rowGen = rowInfo.rowGen();
        ColumnCodec[] codecs = rowGen.valueCodecs();
        return encodeUpdateEntry(mm, rowGen, codecs, schemaVersion, tableVar, rowVar, originalVar);
    }

    /**
     * Makes code which encodes a new entry (a key or value) by comparing dirty row columns to
     * the original entry. Returns the new entry and the column offsets from the original entry.
     *
     * @param schemaVersion pass 0 if key or value has no schema version
     * @param rowVar non-null
     * @param tableVar doesn't need to be initialized (is used to invoke static methods)
     * @param originalVar original non-null encoded key or value
     */
    private static UpdateEntry encodeUpdateEntry
        (MethodMaker mm, RowGen rowGen, ColumnCodec[] codecs, int schemaVersion,
         Variable tableVar, Variable rowVar, Variable originalVar)
    {
        int fixedOffset;

        if (schemaVersion == 0) {
            fixedOffset = 0;
        } else {
            convertValueIfNecessary(tableVar, rowVar, schemaVersion, originalVar);
            fixedOffset = schemaVersion < 128 ? 1 : 4;
        }

        // Identify the offsets to all the columns in the original entry, and calculate the
        // size of the new entry.

        Map<String, Integer> columnNumbers = rowGen.columnNumbers();
        codecs = ColumnCodec.bind(codecs, mm);

        Variable[] offsetVars = new Variable[codecs.length];

        var offsetVar = mm.var(int.class).set(fixedOffset);
        var newSizeVar = mm.var(int.class).set(fixedOffset); // need room for schemaVersion

        String stateFieldName = null;
        Variable stateField = null;

        for (int i=0; i<codecs.length; i++) {
            ColumnCodec codec = codecs[i];
            codec.encodePrepare();

            offsetVars[i] = offsetVar.get();
            codec.decodeSkip(originalVar, offsetVar, null);

            ColumnInfo info = codec.info;
            int num = columnNumbers.get(info.name);

            String sfName = rowGen.stateField(num);
            if (!sfName.equals(stateFieldName)) {
                stateFieldName = sfName;
                stateField = rowVar.field(stateFieldName).get();
            }

            int sfMask = RowGen.stateFieldMask(num);
            Label isDirty = mm.label();
            stateField.and(sfMask).ifEq(sfMask, isDirty);

            // Add in the size of original column, which won't be updated.
            codec.encodeSkip();
            newSizeVar.inc(offsetVar.sub(offsetVars[i]));
            Label cont = mm.label().goto_();

            // Add in the size of the dirty column, which needs to be encoded.
            isDirty.here();
            newSizeVar.inc(codec.minSize());
            codec.encodeSize(rowVar.field(info.name), newSizeVar);

            cont.here();
        }

        // Encode the new byte[] entry...

        var newEntryVar = mm.new_(byte[].class, newSizeVar);

        var srcOffsetVar = mm.var(int.class).set(0);
        var dstOffsetVar = mm.var(int.class).set(0);
        var spanLengthVar = mm.var(int.class).set(fixedOffset);
        var sysVar = mm.var(System.class);

        for (int i=0; i<codecs.length; i++) {
            ColumnCodec codec = codecs[i];
            ColumnInfo info = codec.info;
            int num = columnNumbers.get(info.name);

            Variable columnLenVar;
            {
                Variable endVar;
                if (i + 1 < codecs.length) {
                    endVar = offsetVars[i + 1];
                } else {
                    endVar = originalVar.alength();
                }
                columnLenVar = endVar.sub(offsetVars[i]);
            }

            String sfName = rowGen.stateField(num);
            if (!sfName.equals(stateFieldName)) {
                stateFieldName = sfName;
                stateField = rowVar.field(stateFieldName).get();
            }

            int sfMask = RowGen.stateFieldMask(num);
            Label isDirty = mm.label();
            stateField.and(sfMask).ifEq(sfMask, isDirty);

            // Increase the copy span length.
            Label cont = mm.label();
            spanLengthVar.inc(columnLenVar);
            mm.goto_(cont);

            isDirty.here();

            // Copy the current span and prepare for the next span.
            {
                Label noSpan = mm.label();
                spanLengthVar.ifEq(0, noSpan);
                sysVar.invoke("arraycopy", originalVar, srcOffsetVar,
                              newEntryVar, dstOffsetVar, spanLengthVar);
                srcOffsetVar.inc(spanLengthVar);
                dstOffsetVar.inc(spanLengthVar);
                spanLengthVar.set(0);
                noSpan.here();
            }

            // Encode the dirty column, and skip over the original column value.
            codec.encode(rowVar.field(info.name), newEntryVar, dstOffsetVar);
            srcOffsetVar.inc(columnLenVar);

            cont.here();
        }

        // Copy any remaining span.
        {
            Label noSpan = mm.label();
            spanLengthVar.ifEq(0, noSpan);
            sysVar.invoke("arraycopy", originalVar, srcOffsetVar,
                          newEntryVar, dstOffsetVar, spanLengthVar);
            noSpan.here();
        }

        var ue = new UpdateEntry();
        ue.newEntryVar = newEntryVar;
        ue.offsetVars = offsetVars;
        return ue;
    }

    /**
     * Convert the given encoded value if its schema version doesn't match the desired version.
     * The desired version must be the version that the given table encodes to naturally.
     *
     * @param tableVar doesn't need to be initialized (is used to invoke static methods)
     * @param rowClass row implementation class (can be supplied by a Variable)
     * @param schemaVersion desired schema version (int or Variable)
     * @param valueVar byte[] encoded value, which might be replaced with a re-encoded value
     */
    static void convertValueIfNecessary(Variable tableVar, Object rowClass,
                                        Object schemaVersion, Variable valueVar)
    {
        MethodMaker mm = valueVar.methodMaker();

        var decodeVersion = mm.var(RowUtils.class).invoke("decodeSchemaVersion", valueVar);
        Label sameVersion = mm.label();
        decodeVersion.ifEq(schemaVersion, sameVersion);

        // Schema versions differ, so need to convert. The simplest technique is to create a
        // new temp row object, decode the value into it, and then create a new value from it.
        var tempRowVar = mm.new_(rowClass);
        tableVar.invoke("decodeValue", tempRowVar, valueVar);
        valueVar.set(tableVar.invoke("encodeValue", tempRowVar));

        sameVersion.here();
    }

    /**
     * Adds a method which does most of the work for the update and merge methods. The
     * transaction parameter must not be null, which is committed when changes are made.
     *
     *     boolean doUpdate(Transaction txn, ActualRow row, boolean merge);
     */
    protected void addDoUpdateMethod() {
        // Override the inherited abstract method.
        MethodMaker mm = mClassMaker.addMethod
            (boolean.class, "doUpdate", Transaction.class, mRowClass, boolean.class).protected_();
        addDoUpdateMethod(mm);
    }

    protected void addDoUpdateMethod(MethodMaker mm) {
        Variable txnVar = mm.param(0);
        Variable rowVar = mm.param(1);
        Variable mergeVar = mm.param(2);

        Label ready = mm.label();
        mm.invoke("checkPrimaryKeySet", rowVar).ifTrue(ready);
        mm.new_(IllegalStateException.class, "Primary key isn't fully specified").throw_();

        ready.here();

        final var keyVar = mm.invoke("encodePrimaryKey", rowVar);
        final var source = mm.field("mSource");
        final var cursorVar = source.invoke("newCursor", txnVar);

        Label cursorStart = mm.label().here();

        // If all value columns are dirty, replace the whole row and commit.
        {
            Label cont;
            if (mCodecGen.info.valueColumns.isEmpty()) {
                // If the checkValueAllDirty method was defined, it would always return true.
                cont = null;
            } else {
                cont = mm.label();
                mm.invoke("checkValueAllDirty", rowVar).ifFalse(cont);
            }

            final Variable triggerVar;
            final Label triggerStart;

            if (!supportsTriggers()) {
                triggerVar = null;
                triggerStart = null;
            } else {
                triggerVar = mm.var(Trigger.class);
                Label skipLabel = mm.label();
                prepareForTrigger(mm, mm.this_(), triggerVar, skipLabel);
                triggerStart = mm.label().here();

                cursorVar.invoke("find", keyVar);
                var oldValueVar = cursorVar.invoke("value");
                Label replace = mm.label();
                oldValueVar.ifNe(null, replace);
                mm.return_(false);
                replace.here();
                var valueVar = mm.invoke("encodeValue", rowVar);
                cursorVar.invoke("store", valueVar);
                // Only need to enable redoPredicateMode for the trigger, since it might insert
                // new secondary index entries (and call openAcquire).
                mm.invoke("redoPredicateMode", txnVar);
                triggerVar.invoke("store", txnVar, rowVar, keyVar, oldValueVar, valueVar);
                txnVar.invoke("commit");
                markAllClean(rowVar);
                mm.return_(true);

                skipLabel.here();
            }

            cursorVar.invoke("autoload", false);
            cursorVar.invoke("find", keyVar);
            Label replace = mm.label();
            cursorVar.invoke("value").ifNe(null, replace);
            mm.return_(false);
            replace.here();
            cursorVar.invoke("commit", mm.invoke("encodeValue", rowVar));

            if (triggerStart != null) {
                mm.finally_(triggerStart, () -> triggerVar.invoke("releaseShared"));
            }

            markAllClean(rowVar);
            mm.return_(true);

            if (cont == null) {
                return;
            }

            cont.here();
        }

        cursorVar.invoke("find", keyVar);

        Label hasValue = mm.label();
        cursorVar.invoke("value").ifNe(null, hasValue);
        mm.return_(false);
        hasValue.here();

        // The bulk of the method might not be implemented until needed, delaying
        // acquisition/creation of the current schema version.
        finishDoUpdate(mm, rowVar, mergeVar, cursorVar);

        mm.return_(true);

        mm.finally_(cursorStart, () -> cursorVar.invoke("reset"));
    }

    /**
     * Subclass must override this method in order for the addDoUpdateMethod to work.
     */
    protected void finishDoUpdate(MethodMaker mm,
                                  Variable rowVar, Variable mergeVar, Variable cursorVar)
    {
        throw new UnsupportedOperationException();
    }

    /**
     * @param triggers 0 for false, 1 for true
     */
    protected static void finishDoUpdate(MethodMaker mm, RowInfo rowInfo, int schemaVersion,
                                         int triggers, boolean returnTrue, Variable tableVar,
                                         Variable rowVar, Variable mergeVar, Variable cursorVar)
    {
        Variable valueVar = cursorVar.invoke("value");

        var ue = encodeUpdateValue(mm, rowInfo, schemaVersion, tableVar, rowVar, valueVar);
        Variable newValueVar = ue.newEntryVar;
        Variable[] offsetVars = ue.offsetVars;

        if (triggers == 0) {
            cursorVar.invoke("commit", newValueVar);
        }

        Label doMerge = mm.label();
        mergeVar.ifTrue(doMerge);

        if (triggers != 0) {
            var triggerVar = mm.var(Trigger.class);
            Label skipLabel = mm.label();
            prepareForTrigger(mm, tableVar, triggerVar, skipLabel);
            Label triggerStart = mm.label().here();

            cursorVar.invoke("store", newValueVar);

            // Only need to enable redoPredicateMode for the trigger, since it might insert
            // new secondary index entries (and call openAcquire).
            var txnVar = cursorVar.invoke("link");
            tableVar.invoke("redoPredicateMode", txnVar);
            var keyVar = cursorVar.invoke("key");
            triggerVar.invoke("storeP", txnVar, rowVar, keyVar, valueVar, newValueVar);
            txnVar.invoke("commit");
            Label cont = mm.label().goto_();

            skipLabel.here();

            cursorVar.invoke("commit", newValueVar);

            mm.finally_(triggerStart, () -> triggerVar.invoke("releaseShared"));

            cont.here();
        }

        tableVar.invoke("cleanRow", rowVar);

        if (returnTrue) {
            mm.return_(true);
        } else {
            mm.return_();
        }

        doMerge.here();

        // Decode all the original column values that weren't updated into the row.

        RowGen rowGen = rowInfo.rowGen();
        Map<String, Integer> columnNumbers = rowGen.columnNumbers();
        ColumnCodec[] codecs = ColumnCodec.bind(rowGen.valueCodecs(), mm);

        String stateFieldName = null;
        Variable stateField = null;

        for (int i=0; i<codecs.length; i++) {
            ColumnCodec codec = codecs[i];
            ColumnInfo info = codec.info;
            int num = columnNumbers.get(info.name);

            String sfName = rowGen.stateField(num);
            if (!sfName.equals(stateFieldName)) {
                stateFieldName = sfName;
                stateField = rowVar.field(stateFieldName).get();
            }

            int sfMask = RowGen.stateFieldMask(num);
            Label cont = mm.label();
            stateField.and(sfMask).ifEq(sfMask, cont);

            codec.decode(rowVar.field(info.name), valueVar, offsetVars[i], null);

            cont.here();
        }

        if (triggers != 0) {
            var triggerVar = mm.var(Trigger.class);
            Label skipLabel = mm.label();
            prepareForTrigger(mm, tableVar, triggerVar, skipLabel);
            Label triggerStart = mm.label().here();

            var txnVar = cursorVar.invoke("link");
            var keyVar = cursorVar.invoke("key");
            triggerVar.invoke("store", txnVar, rowVar, keyVar, valueVar, newValueVar);
            cursorVar.invoke("commit", newValueVar);
            Label cont = mm.label().goto_();

            skipLabel.here();

            cursorVar.invoke("commit", newValueVar);

            mm.finally_(triggerStart, () -> triggerVar.invoke("releaseShared"));

            cont.here();
        }

        markAllClean(rowVar, rowGen, rowGen);
    }

    /**
     * @param option bit 1: reverse, bit 2: joined
     */
    protected void addPlanMethod(int option) {
        String name = "plan";
        if ((option & 0b01) != 0) {
            name += "Reverse";
        }
        MethodMaker mm = mClassMaker.addMethod
            (QueryPlan.class, name, Object[].class).varargs().public_();
        var condy = mm.var(TableMaker.class).condy
            ("condyPlan", mRowType, mSecondaryDescriptor, option);
        mm.return_(condy.invoke(QueryPlan.class, "plan"));
    }

    /**
     * @param option bit 1: reverse, bit 2: joined
     */
    public static QueryPlan condyPlan(MethodHandles.Lookup lookup, String name, Class type,
                                      Class rowType, byte[] secondaryDesc, int option)
    {
        RowInfo primaryRowInfo = RowInfo.find(rowType);

        RowInfo rowInfo;
        String which;

        if (secondaryDesc == null) {
            rowInfo = primaryRowInfo;
            which = "primary key";
        } else {
            rowInfo = RowStore.secondaryRowInfo(primaryRowInfo, secondaryDesc);
            which = rowInfo.isAltKey() ? "alternate key" : "secondary index";
        }

        boolean reverse = (option & 0b01) != 0;
        QueryPlan plan = new QueryPlan.FullScan(rowInfo.name, which, rowInfo.keySpec(), reverse);
    
        if ((option & 0b10) != 0) {
            rowInfo = primaryRowInfo;
            plan = new QueryPlan.PrimaryJoin(rowInfo.name, rowInfo.keySpec(), plan);
        }

        return plan;
    }

    /**
     * Defines methods which return a SingleScanController instance.
     *
     * @param tableId pass 0 if table is unevolvable
     */
    protected void addUnfilteredMethods(long tableId) {
        MethodMaker mm = mClassMaker.addMethod
            (SingleScanController.class, "unfiltered").protected_();
        var condyClass = mm.var(TableMaker.class).condy
            ("condyDefineUnfiltered", mRowType, mRowClass, tableId, mSecondaryDescriptor);
        var scanControllerCtor = condyClass.invoke(MethodHandle.class, "unfiltered");
        Class<?>[] paramTypes = {
            byte[].class, boolean.class, byte[].class, boolean.class, boolean.class
        };
        mm.return_(scanControllerCtor.invoke
                   (SingleScanController.class, "invokeExact", paramTypes,
                    null, false, null, false, false));

        mm = mClassMaker.addMethod(SingleScanController.class, "unfilteredReverse").protected_();
        // Use the same scanControllerCtor constant, but must set against the correct maker.
        scanControllerCtor = mm.var(MethodHandle.class).set(scanControllerCtor);
        mm.return_(scanControllerCtor.invoke
                   (SingleScanController.class, "invokeExact", paramTypes,
                    null, false, null, false, true));
    }

    /**
     * Makes a subclass of SingleScanController with matching constructors.
     *
     * @param secondaryDesc pass null for primary table
     * @return the basic constructor handle
     */
    public static MethodHandle condyDefineUnfiltered
        (MethodHandles.Lookup lookup, String name, Class type,
         Class rowType, Class rowClass, long tableId, byte[] secondaryDesc)
        throws Throwable
    {
        RowInfo rowInfo = RowInfo.find(rowType);
        RowGen rowGen = rowInfo.rowGen();
        RowGen codecGen = rowGen;

        if (secondaryDesc != null) {
            codecGen = RowStore.secondaryRowInfo(rowInfo, secondaryDesc).rowGen();
        }

        ClassMaker cm = RowGen.beginClassMaker
            (TableMaker.class, rowType, rowInfo, null, name)
            .extend(SingleScanController.class).public_();

        // Constructors are protected, for use by filter implementation subclasses.
        final MethodType ctorType;
        {
            ctorType = MethodType.methodType(void.class, byte[].class, boolean.class,
                                             byte[].class, boolean.class, boolean.class);
            MethodMaker mm = cm.addConstructor(ctorType).protected_();
            mm.invokeSuperConstructor
                (mm.param(0), mm.param(1), mm.param(2), mm.param(3), mm.param(4));

            // Define a reverse scan copy constructor.
            mm = cm.addConstructor(SingleScanController.class).protected_();
            mm.invokeSuperConstructor(mm.param(0));
        }

        // Specified by RowEvaluator.
        cm.addMethod(long.class, "evolvableTableId").public_().final_().return_(tableId);

        if (secondaryDesc != null) {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod(byte[].class, "secondaryDescriptor").public_();
            mm.return_(mm.var(byte[].class).setExact(secondaryDesc));
        }

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (Object.class, "evalRow", Cursor.class, LockResult.class, Object.class).public_();

            final var cursorVar = mm.param(0);
            final var keyVar = cursorVar.invoke("key");
            final var valueVar = cursorVar.invoke("value");
            final var rowVar = mm.param(2);

            final Label notRow = mm.label();
            final var typedRowVar = CodeUtils.castOrNew(rowVar, rowClass, notRow);

            var tableVar = mm.var(lookup.lookupClass());
            tableVar.invoke("decodePrimaryKey", typedRowVar, keyVar);
            tableVar.invoke("decodeValue", typedRowVar, valueVar);
            markAllClean(typedRowVar, rowGen, codecGen);
            mm.return_(typedRowVar);

            // Assume the passed in row is actually a RowConsumer.
            notRow.here();
            CodeUtils.acceptAsRowConsumerAndReturn(rowVar, rowClass, keyVar, valueVar);
        }

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (Object.class, "decodeRow", Object.class, byte[].class, byte[].class).public_();
            var rowVar = CodeUtils.castOrNew(mm.param(0), rowClass);
            var tableVar = mm.var(lookup.lookupClass());
            tableVar.invoke("decodePrimaryKey", rowVar, mm.param(1));
            tableVar.invoke("decodeValue", rowVar, mm.param(2));
            markAllClean(rowVar, rowGen, codecGen);
            mm.return_(rowVar);
        }

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (null, "writeRow", RowWriter.class, byte[].class, byte[].class).public_();
            var tableVar = mm.var(lookup.lookupClass());
            tableVar.invoke("writeRow", mm.param(0), mm.param(1), mm.param(2));
        }

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (byte[].class, "updateKey", Object.class, byte[].class).public_();
            var rowVar = mm.param(0).cast(rowClass);
            var tableVar = mm.var(lookup.lookupClass());
            Label unchanged = mm.label();
            tableVar.invoke("checkPrimaryKeyAnyDirty", rowVar).ifFalse(unchanged);
            mm.return_(tableVar.invoke("updatePrimaryKey", rowVar, mm.param(1)));
            unchanged.here();
            mm.return_(null);
        }

        {
            // Specified by RowEvaluator.
            MethodMaker mm = cm.addMethod
                (byte[].class, "updateValue", Object.class, byte[].class).public_();
            var rowVar = mm.param(0).cast(rowClass);
            var tableVar = mm.var(lookup.lookupClass());
            mm.return_(tableVar.invoke("updateValue", rowVar, mm.param(1)));
        }

        if (rowGen == codecGen && rowType != Entry.class) {
            // If evolvable, a schema must be decoded, to be used by filter subclasses. The int
            // param is the schema version.
            MethodMaker mm = cm.addMethod
                (MethodHandle.class, "decodeValueHandle", int.class).protected_().static_();
            var tableVar = mm.var(lookup.lookupClass());
            mm.return_(tableVar.invoke("decodeValueHandle", mm.param(0)));
        }

        lookup = cm.finishLookup();

        MethodHandle mh = lookup.findConstructor(lookup.lookupClass(), ctorType);

        return mh.asType(mh.type().changeReturnType(SingleScanController.class));
    }
}
