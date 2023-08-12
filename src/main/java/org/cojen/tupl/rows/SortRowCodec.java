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

import java.util.ArrayList;
import java.util.Set;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.core.Triple;

import org.cojen.tupl.rows.codec.ColumnCodec;

/**
 * Supports encoding and decoding of materialized rows such that they can fed into a Sorter.
 *
 * @author Brian S. O'Neill
 * @see RowSorter
 */
public abstract class SortRowCodec<R> implements RowDecoder<R> {
    /**
     * @param rowNum non-negative row number, used for allowing duplicates; is discarded when
     * rows are decoded
     */
    public abstract void encode(R row, long rowNum, byte[][] kvPairs, int offset)
        throws IOException;

    /**
     * Returns a new or cached instance.
     */
    @SuppressWarnings("unchecked")
    public static <R> SortRowCodec<R> find(Class<?> rowType,
                                           Set<String> projection, String orderBySpec)
    {
        return (SortRowCodec<R>) cCache.obtain
            (new Triple<>(rowType, projection, orderBySpec), null);
    }

    private static final WeakCache
        <Triple<Class<?>, Set<String>, String>, SortRowCodec<?>, Object> cCache;

    static {
        cCache = new WeakCache<>() {
            @Override
            public SortRowCodec<?> newValue
                (Triple<Class<?>, Set<String>, String> key, Object unused)
            {
                return make(key.a(), key.b(), key.c());
            }
        };
    }

    private static <R> SortRowCodec<R> make(Class<?> rowType,
                                            Set<String> projection, String orderBySpec)
    {
        RowInfo rowInfo = RowInfo.find(rowType);
        RowGen rowGen = rowInfo.rowGen();
        OrderBy orderBy = OrderBy.forSpec(rowInfo, orderBySpec);
        Class rowClass = RowMaker.find(rowType);

        var keyColumns = new ArrayList<ColumnInfo>(orderBy.size() + 1);

        for (OrderBy.Rule rule : orderBy.values()) {
            keyColumns.add(rule.asColumn());
        }

        // Reserve a slot at the end for the rowNum, but the actual encoding format will differ.
        {
            var rowNumColumn = new ColumnInfo();
            rowNumColumn.name = null; // ensures that a real column won't be accessed
            rowNumColumn.type = long.class;
            rowNumColumn.typeCode = ColumnInfo.TYPE_ULONG;
            keyColumns.add(rowNumColumn);
        }

        ColumnCodec[] keyCodecs = ColumnCodec.make(keyColumns, ColumnCodec.F_LEX);

        var valueColumns = new ArrayList<ColumnInfo>();

        for (ColumnInfo column : rowInfo.allColumns.values()) {
            if (!orderBy.containsKey(column.name)
                && (projection == null || projection.contains(column.name)))
            {
                valueColumns.add(column);
            }
        }

        ColumnCodec[] valueCodecs = ColumnCodec.make(valueColumns, 0);

        ClassMaker cm = rowGen.beginClassMaker(SortRowCodec.class, rowType, null);
        cm.extend(SortRowCodec.class).final_();

        // Keep a singleton instance, in order for a weakly cached reference to the comparator
        // to stick around until the class is unloaded.
        cm.addField(SortRowCodec.class, "THE").private_().static_();

        {
            MethodMaker mm = cm.addConstructor().private_();
            mm.invokeSuperConstructor();
            mm.field("THE").set(mm.this_());
        }

        {
            MethodMaker mm = cm.addMethod
                (null, "encode", Object.class, long.class, byte[][].class, int.class).public_();

            var rowVar = mm.param(0).cast(rowClass);
            var rowNumVar = mm.param(1);
            var kvPairsVar = mm.param(2);
            var offsetVar = mm.param(3);

            kvPairsVar.aset(offsetVar, encode(rowVar, rowNumVar, keyCodecs));
            kvPairsVar.aset(offsetVar.add(1), encode(rowVar, null, valueCodecs));
        }

        {
            MethodMaker mm = cm.addMethod
                (rowType, "decodeRow", Object.class, byte[].class, byte[].class).public_();

            var rowVar = CodeUtils.castOrNew(mm.param(0), rowClass);
            var keyVar = mm.param(1);
            var valueVar = mm.param(2);

            decode(rowVar, keyVar, keyCodecs, projection);
            decode(rowVar, valueVar, valueCodecs, projection);

            if (projection == null) {
                TableMaker.markAllClean(rowVar, rowGen, rowGen);
            } else {
                TableMaker.markClean(rowVar, rowGen, projection);
            }

            mm.return_(rowVar);
        }

        // Define the decode bridge method.
        {
            MethodMaker mm = cm.addMethod
                (Object.class, "decodeRow", Object.class, byte[].class, byte[].class)
                .public_().bridge();
            mm.return_(mm.this_().invoke(rowType, "decodeRow", null,
                                         mm.param(0), mm.param(1), mm.param(2)));
        }

        try {
            MethodHandles.Lookup lookup = cm.finishHidden();
            MethodHandle mh = lookup.findConstructor
                (lookup.lookupClass(), MethodType.methodType(void.class));
            return (SortRowCodec<R>) mh.invoke();
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    /**
     * @param rowNumVar pass null for value encoding
     * @return a byte[] variable.
     */
    private static Variable encode(Variable rowVar, Variable rowNumVar, ColumnCodec[] codecs) {
        MethodMaker mm = rowVar.methodMaker();
        codecs = ColumnCodec.bind(codecs, mm);

        int numColumns = codecs.length;

        int minSize = 0;

        if (rowNumVar != null) {
            /* Row number is encoded specially, but it requires at least four bytes.
               0xxx_xxxx +3 bytes (0 .. Integer.MAX_VALUE)
               1xxx_xxxx +7 bytes (Integer.MAX_VALUE + 1 .. Long.MAX_VALUE)
            */
            numColumns--;
            minSize += 4;
        }

        for (int i=0; i<numColumns; i++) {
            minSize += codecs[i].minSize();
            codecs[i].encodePrepare();
        }

        Variable totalVar = minSize == 0 ? null : (mm.var(int.class).set(minSize));

        for (int i=0; i<numColumns; i++) {
            ColumnCodec codec = codecs[i];
            totalVar = codec.encodeSize(rowVar.field(codec.info.name), totalVar);
        }

        if (rowNumVar != null) {
            Label cont = mm.label();
            rowNumVar.ifLe(Integer.MAX_VALUE, cont);
            totalVar.inc(4);
            cont.here();
        }

        var bytesVar = mm.new_(byte[].class, totalVar);
        var offsetVar = mm.var(int.class).set(0);

        for (int i=0; i<numColumns; i++) {
            ColumnCodec codec = codecs[i];
            codec.encode(rowVar.field(codec.info.name), bytesVar, offsetVar);
        }

        if (rowNumVar != null) {
            var utilsVar = mm.var(RowUtils.class);
            Label intSize = mm.label();
            rowNumVar.ifLe(Integer.MAX_VALUE, intSize);
            utilsVar.invoke("encodeLongBE", bytesVar, offsetVar, rowNumVar.or(1L << 63));
            Label cont = mm.label().goto_();
            intSize.here();
            utilsVar.invoke("encodeIntBE", bytesVar, offsetVar, rowNumVar.cast(int.class));
            cont.here();
        }

        return bytesVar;
    }

    private static void decode(Variable rowVar, Variable bytesVar, ColumnCodec[] codecs,
                               Set<String> projection)
    {
        int numToExamine = codecs.length;

        if (numToExamine <= 0) {
            return;
        }

        MethodMaker mm = rowVar.methodMaker();
        codecs = ColumnCodec.bind(codecs, mm);

        while (true) {
            ColumnInfo column = codecs[numToExamine - 1].info;
            if (column.name != null && (projection == null || projection.contains(column.name))) {
                break;
            }
            numToExamine--;
            if (numToExamine <= 0) {
                return;
            }
        }

        var offsetVar = mm.var(int.class).set(0);

        for (int i=0; i<numToExamine; i++) {
            ColumnCodec codec = codecs[i];
            ColumnInfo column = codec.info;
            if (column.name != null) {
                if (projection == null || projection.contains(column.name)) {
                    codec.decode(rowVar.field(codec.info.name), bytesVar, offsetVar, null);
                } else {
                    codec.decodeSkip(bytesVar, offsetVar, null);
                }
            }
        }
    }
}
