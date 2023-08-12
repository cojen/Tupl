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

import java.lang.ref.WeakReference;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.DatabaseException;

import org.cojen.tupl.rows.codec.ColumnCodec;

/**
 * Makes a call site for implementing the RowEvaluator.writeRow method.
 *
 * @author Brian S O'Neill
 */
public class WriteRowMaker {
    /**
     * Returns a SwitchCallSite suitable for writing rows from evolvable tables. The set of row
     * columns which are written is defined by the projection specification.
     *
     * MethodType is void (int schemaVersion, RowWriter writer, byte[] key, byte[] value)
     *
     * @param projectionSpec can be null if all columns are projected
     */
    public static SwitchCallSite indyWriteRow(MethodHandles.Lookup lookup,
                                              String name, MethodType mt,
                                              WeakReference<RowStore> storeRef,
                                              Class<?> rowType, long tableId,
                                              byte[] projectionSpec)
    {
        return new SwitchCallSite(lookup, mt, schemaVersion -> {
            MethodMaker mm = MethodMaker.begin
                (lookup, null, "writeRow", RowWriter.class, byte[].class, byte[].class);

            RowStore store = storeRef.get();
            if (store == null) {
                mm.new_(DatabaseException.class, "Closed").throw_();
            } else {
                RowInfo rowInfo;
                try {
                    rowInfo = store.rowInfo(rowType, tableId, schemaVersion);
                } catch (Exception e) {
                    return new ExceptionCallSite.Failed
                        (MethodType.methodType
                         (void.class, RowWriter.class, byte[].class, byte[].class), mm, e);
                }

                makeWriteRow(mm, rowInfo, schemaVersion < 128 ? 1 : 4, projectionSpec);
            }

            return mm.finish();
        });
    }

    /**
     * Returns a MethodHandle suitable for writing rows from evolvable tables. The set of row
     * columns which are written is defined by the projection specification.
     *
     * MethodType is void (int schemaVersion, RowWriter writer, byte[] key, byte[] value)
     *
     * @param projectionSpec can be null if all columns are projected
     */
    public static MethodHandle makeWriteRowHandle(WeakReference<RowStore> storeRef,
                                                  Class<?> rowType, long tableId,
                                                  byte[] projectionSpec)
    {
        // Because no special access is required, the local lookup is sufficient.
        var lookup = MethodHandles.lookup();

        MethodType mt = MethodType.methodType
            (void.class, int.class, RowWriter.class, byte[].class, byte[].class);

        return indyWriteRow(lookup, "writeRow", mt, storeRef, rowType, tableId, projectionSpec)
            .dynamicInvoker();
    }

    /**
     * Returns a MethodHandle suitable for writing rows from unevolvable tables. The set of row
     * columns which are written is defined by the projection specification.
     *
     * MethodType is void (RowWriter writer, byte[] key, byte[] value)
     *
     * @param projectionSpec can be null if all columns are projected
     */
    public static MethodHandle makeWriteRowHandle(RowInfo rowInfo, byte[] projectionSpec) {
        // Because no special access is required, the local lookup is sufficient.
        var lookup = MethodHandles.lookup();

        MethodMaker mm = MethodMaker.begin
            (lookup, null, "writeRow", RowWriter.class, byte[].class, byte[].class);

        makeWriteRow(mm, rowInfo, 0, projectionSpec);

        return mm.finish();
    }

    /**
     * Makes the body of a writeRow method.
     *
     * Params: (RowWriter writer, byte[] key, byte[] value)
     *
     * @param rowInfo describes the key and value encoding
     * @param valueOffset start offset of the source binary value
     * @param projectionSpec can be null if all columns are projected
     */
    public static void makeWriteRow(MethodMaker mm, RowInfo rowInfo, int valueOffset,
                                    byte[] projectionSpec)
    {
        RowGen rowGen = rowInfo.rowGen();

        var writerVar = mm.param(0);
        var keyVar = mm.param(1);
        var valueVar = mm.param(2);

        BitSet projSet;
        RowHeader rh;

        if (projectionSpec == null) {
            projSet = null;
            rh = RowHeader.make(rowGen);
        } else {
            projSet = DecodePartialMaker.decodeSpec(projectionSpec)[0];
            rh = RowHeader.make(rowGen, projSet);
        }

        var headerVar = mm.var(byte[].class).setExact(rh.encode(true));
        writerVar.invoke("writeHeader", headerVar);

        ColumnCodec[] keyCodecs = rowGen.keyCodecs();
        ColumnCodec[] valueCodecs = rowGen.valueCodecs();

        Variable keyLengthVar;
        List<Range> keyRanges;
        ColumnCodec lastKeyCodec;

        if (rh.numKeys == 0) {
            // No key columns to write.
            keyLengthVar = mm.var(int.class).set(0);
            keyRanges = List.of();
            lastKeyCodec = null;
        } else if (projSet == null || DecodePartialMaker.allRequested
                   (projSet, 0, keyCodecs.length))
        {
            // All key columns are projected.
            keyLengthVar = keyVar.alength();
            keyRanges = List.of(new Range(0, keyLengthVar));
            lastKeyCodec = keyCodecs[rh.numKeys - 1];
        } else {
            // A subset of key columns is projected.
            keyRanges = new ArrayList<>();
            lastKeyCodec = prepareRanges(keyRanges, projSet, 0, keyCodecs, keyVar, 0);
            keyLengthVar = mm.var(int.class).set(0);
            incLength(keyLengthVar, keyRanges);
        }

        Variable rowLengthVar;
        List<Range> valueRanges;

        if (rh.numValues() == 0) {
            // No value columns to write.
            rowLengthVar = keyLengthVar;
            valueRanges = List.of();
        } else if (projSet == null || DecodePartialMaker.allRequested
                   (projSet, keyCodecs.length, keyCodecs.length + valueCodecs.length))
        {
            // All value columns are projected.
            Variable valueLengthVar = valueVar.alength().sub(valueOffset);
            rowLengthVar = keyLengthVar.add(valueLengthVar);
            valueRanges = List.of(new Range(valueOffset, valueLengthVar));
        } else {
            // A subset of value columns is projected.
            valueRanges = new ArrayList<>();
            prepareRanges(valueRanges, projSet, keyCodecs.length,
                          valueCodecs, valueVar, valueOffset);
            rowLengthVar = keyLengthVar.get();
            incLength(rowLengthVar, valueRanges);
        }

        if (rh.numValues() == 0 || lastKeyCodec == null || !lastKeyCodec.isLast()) {
            writerVar.invoke("writeRowLength", rowLengthVar);
        } else {
            // No length prefix is written for the last key column, but one should be when the
            // key and value are written together into the stream. Rather than retrofit the
            // encoded column, provide the full key length up front. Decoding of the last
            // column finishes when the offset reaches the end of the full key.
            writerVar.invoke("writeRowAndKeyLength", rowLengthVar, keyLengthVar);
        }

        writeRanges(writerVar, keyVar, keyRanges);
        writeRanges(writerVar, valueVar, valueRanges);
    }

    /**
     * @param ranges destination
     * @return the last codec
     */
    private static ColumnCodec prepareRanges(List<Range> ranges,
                                             BitSet projSet, int projOffset, ColumnCodec[] codecs,
                                             Variable bytesVar, int bytesStart)
    {
        codecs = ColumnCodec.bind(codecs, bytesVar.methodMaker());

        int numColumns = 0;

        for (int i=0; i<codecs.length; i++) {
            if (projSet.get(projOffset + i)) {
                numColumns++;
            }
        }

        MethodMaker mm = bytesVar.methodMaker();
        Variable offsetVar = mm.var(int.class).set(bytesStart);
        boolean gap = true;

        ColumnCodec lastCodec = null;

        for (int i = 0; numColumns > 0 && i < codecs.length; i++) {
            ColumnCodec codec = codecs[i];

            if (!projSet.get(projOffset + i)) {
                codec.decodeSkip(bytesVar, offsetVar, null);
                gap = true;
                continue;
            }

            Object start = i == 0 ? bytesStart : offsetVar.get();
            Variable length;

            if (i < codecs.length - 1) {
                codec.decodeSkip(bytesVar, offsetVar, null);
                length = offsetVar.sub(start);
            } else {
                length = bytesVar.alength().sub(start);
            }

            if (gap) {
                ranges.add(new Range(start, length));
            } else {
                ranges.get(ranges.size() - 1).length.inc(length);
            }

            numColumns--;
            gap = false;

            lastCodec = codec;
        }

        return lastCodec;
    }

    private static void incLength(Variable lengthVar, List<Range> ranges) {
        for (Range range : ranges) {
            lengthVar.inc(range.length);
        }
    }

    private static void writeRanges(Variable writerVar, Variable bytesVar, List<Range> ranges) {
        for (Range range : ranges) {
            writerVar.invoke("writeBytes", bytesVar, range.start, range.length);
        }
    }

    /**
     * @param start Variable or constant
     */
    private record Range(Object start, Variable length) { }
}
