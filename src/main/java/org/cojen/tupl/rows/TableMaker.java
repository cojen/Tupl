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

import java.util.Map;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.diag.QueryPlan;

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
        return isPrimaryTable();
    }

    /**
     * @return null if no field is defined for the column (probably SchemaVersionColumnCodec)
     */
    protected static Field findField(Variable row, ColumnCodec codec) {
        ColumnInfo info = codec.mInfo;
        return info == null ? null : row.field(info.name);
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
        final int maxNum = rowGen.info.allColumns.size();

        int num = 0, mask = 0;

        for (int step = 0; step < 2; step++) {
            // Key columns are numbered before value columns. Add checks in two steps.
            // Note that the codecs are accessed, to match encoding order.
            var baseCodecs = step == 0 ? rowGen.keyCodecs() : rowGen.valueCodecs();

            for (ColumnCodec codec : baseCodecs) {
                if (columns.containsKey(codec.mInfo.name)) {
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
            // Clear the value column state fields. Skip the key columns, which are numbered
            // first. Note that the codecs are accessed, to match encoding order.
            int num = mRowInfo.keyColumns.size();
            int mask = 0;
            for (ColumnCodec codec : mRowGen.valueCodecs()) {
                mask |= RowGen.stateFieldMask(num);
                if (isMaskReady(++num, mask)) {
                    mask = maskRemainder(num, mask);
                    Field field = stateField(rowVar, num - 1);
                    mask = ~mask;
                    if (mask == 0) {
                        field.set(mask);
                    } else {
                        field.set(field.and(mask));
                        mask = 0;
                    }
                }
            }
            return;
        }

        final Map<String, ColumnInfo> keyColumns = mCodecGen.info.keyColumns;
        final int maxNum = mRowInfo.allColumns.size();

        int num = 0, mask = 0;

        for (int step = 0; step < 2; step++) {
            // Key columns are numbered before value columns. Add checks in two steps.
            // Note that the codecs are accessed, to match encoding order.
            var baseCodecs = step == 0 ? mRowGen.keyCodecs() : mRowGen.valueCodecs();

            for (ColumnCodec codec : baseCodecs) {
                if (!keyColumns.containsKey(codec.mInfo.name)) {
                    mask |= RowGen.stateFieldMask(num);
                }
                if ((++num & 0b1111) == 0 || num >= maxNum) {
                    Field field = rowVar.field(mRowGen.stateField(num - 1));
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
     * Called when building state field masks for columns, when iterating them in order.
     *
     * @param num column number pre-incremented to the next one
     * @param mask current group; must be non-zero to have any effect
     */
    protected boolean isMaskReady(int num, int mask) {
        return mask != 0 && ((num & 0b1111) == 0 || num >= mRowInfo.allColumns.size());
    }

    /**
     * When building a mask for the highest state field, sets the high unused bits on the
     * mask. This can eliminate an unnecessary 'and' operation.
     *
     * @param num column number pre-incremented to the next one
     * @param mask current group
     * @return updated mask
     */
    protected int maskRemainder(int num, int mask) {
        if (num >= mRowInfo.allColumns.size()) {
            int shift = (num & 0b1111) << 1;
            if (shift != 0) {
                mask |= 0xffff_ffff << shift;
            }
        }
        return mask;
    }

    protected Field stateField(Variable rowVar, int columnNum) {
        return rowVar.field(mRowGen.stateField(columnNum));
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
            String name = srcCodec.mInfo.name;
            ColumnInfo dstInfo = dstRowInfo.allColumns.get(name);

            if (dstInfo == null) {
                srcCodec.decodeSkip(srcVar, offsetVar, null);
            } else {
                var rowVar = mm.param(0);
                Field dstVar = rowVar.field(name);
                Converter.decode(mm, srcVar, offsetVar, null, srcCodec, dstInfo, dstVar);
            }
        }
    }

    protected static class UpdateEntry {
        Variable newEntryVar;
        Variable[] offsetVars;
    }

    /**
     * Makes code which encodes a new entry (a key or value) by comparing dirty row columns to
     * the original entry. Returns the new entry and the column offsets in the original entry.
     *
     * @param schemaVersion pass 0 if entry is a key instead of a value; implies that caller
     * must handle the case where the value must be empty
     * @param rowVar non-null
     * @param tableVar doesn't need to be initialized (is used to invoke a static method)
     * @param originalVar original non-null encoded key or value
     */
    protected static UpdateEntry encodeUpdateEntry
        (MethodMaker mm, RowInfo rowInfo, int schemaVersion,
         Variable tableVar, Variable rowVar, Variable originalVar)
    {
        RowGen rowGen = rowInfo.rowGen();
        ColumnCodec[] codecs;
        int fixedOffset;

        if (schemaVersion == 0) {
            codecs = rowGen.keyCodecs();
            fixedOffset = 0;
        } else {
            codecs = rowGen.valueCodecs();

            Variable decodeVersion = mm.var(RowUtils.class)
                .invoke("decodeSchemaVersion", originalVar);
            Label sameVersion = mm.label();
            decodeVersion.ifEq(schemaVersion, sameVersion);

            // If different schema versions, decode and re-encode a new entry, and then go to
            // the next step. The simplest way to perform this conversion is to create a new
            // temp row object, decode the entry into it, and then create a new entry from it.
            var tempRowVar = mm.new_(rowVar);
            tableVar.invoke("decodeValue", tempRowVar, originalVar);
            originalVar.set(tableVar.invoke("encodeValue", tempRowVar));

            sameVersion.here();

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

            ColumnInfo info = codec.mInfo;
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
            ColumnInfo info = codec.mInfo;
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
            plan = new QueryPlan.NaturalJoin(rowInfo.name, "primary key", rowInfo.keySpec(), plan);
        }

        return plan;
    }
}
