/*
 *  Copyright (C) 2021 Cojen.org
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

import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.Cursor;

import org.cojen.tupl.filter.AndFilter;
import org.cojen.tupl.filter.ColumnToArgFilter;
import org.cojen.tupl.filter.ColumnToColumnFilter;
import org.cojen.tupl.filter.OrFilter;
import org.cojen.tupl.filter.RowFilter;
import org.cojen.tupl.filter.Visitor;

/**
 * Generates code to filter and decode rows for a specific schema version. After passing this
 * visitor to the RowFilter.accept method, call a finish method.
 *
 * @author Brian S O'Neill
 * @see FilteredScanMaker
 */
class DecodeVisitor extends Visitor {
    private final MethodMaker mMaker;
    private final Variable mValueVar;
    private final int mValueOffset;
    private final RowGen mRowGen;
    private final Variable mPredicateVar;
    private final String mStopColumn;
    private final int mStopArgument;

    private final ColumnCodec[] mKeyCodecs, mValueCodecs;

    private Label mPass, mFail;

    private LocatedColumn[] mLocatedKeys;
    private int mHighestLocatedKey;

    private LocatedColumn[] mLocatedValues;
    private int mHighestLocatedValue;

    /**
     * Supports two forms of decode methods and two forms of predicate methods.
     *
     *     R decodeRow(byte[] key, byte[] value, ...)
     *     R decodeRow(byte[] key, Cursor c, ...)
     *
     * If a stopColumn and stopArgument are provided, then the cursor method form is
     * required in order for the stop to actually work.
     *
     *     boolean test(byte[] key, byte[] value, ...)
     *     boolean test(R row, byte[] value, ...)
     *
     * The predicate form which accepts a row only examines primary key columns from it.
     *
     * Note that other forms are supported, but the only the common forms are listed above.
     *
     * @param mm signature: R decodeRow(byte[] key, byte[] value, ...)
     * @param valueOffset offset to skip past the schema version
     * @param rowGen actual row definition to be decoded
     * @param predicateVar implements RowPredicate
     */
    DecodeVisitor(MethodMaker mm, int valueOffset, RowGen rowGen,
                  Variable predicateVar, String stopColumn, int stopArgument)
    {
        mMaker = mm;
        mValueOffset = valueOffset;
        mRowGen = rowGen;
        mPredicateVar = predicateVar;
        mStopColumn = stopColumn;
        mStopArgument = stopArgument;

        var valueVar = mm.param(1);
        if (valueVar.classType() == Cursor.class) {
            valueVar = valueVar.invoke("value");
        }
        mValueVar = valueVar;

        mKeyCodecs = ColumnCodec.bind(rowGen.keyCodecs(), mm);
        mValueCodecs = ColumnCodec.bind(rowGen.valueCodecs(), mm);

        mPass = mm.label();
        mFail = mm.label();
    }

    /**
     * Must be called after visiting when making a full decoder.
     *
     * @param decoder performs full decoding of the value columns; pass null to invoke the full
     * decode method in the generated table class
     * @param tableClass current table implementation class
     * @param rowClass current row implementation
     * @param rowVar refers to the row parameter to allocate or fill in
     */
    void finishDecode(MethodHandle decoder,
                      Class<?> tableClass, Class<?> rowClass, Variable rowVar)
    {
        mFail.here();
        mMaker.return_(null);

        mPass.here();

        // FIXME: Some columns may have already been decoded, so don't double decode them.

        var tableVar = mMaker.var(tableClass);
        rowVar = rowVar.cast(rowClass);
        Label hasRow = mMaker.label();
        rowVar.ifNe(null, hasRow);
        rowVar.set(mMaker.new_(rowClass));
        hasRow.here();
        tableVar.invoke("decodePrimaryKey", rowVar, mMaker.param(0));

        // Invoke the schema-specific decoder directly, instead of calling the decodeValue
        // method which redundantly examines the schema version and switches on it.
        if (decoder != null) {
            mMaker.invoke(decoder, rowVar, mValueVar);
        } else {
            mMaker.var(tableClass).invoke("decodeValue", rowVar, mValueVar);
        }

        mMaker.var(tableClass).invoke("markAllClean", rowVar);

        mMaker.return_(rowVar);
    }

    /**
     * Must be called after visiting when making a predicate.
     */
    void finishPredicate() {
        mFail.here();
        mMaker.return_(false);
        mPass.here();
        mMaker.return_(true);
    }

    @Override
    public void visit(OrFilter filter) {
        final Label originalFail = mFail;

        RowFilter[] subFilters = filter.subFilters();

        if (subFilters.length == 0) {
            mMaker.goto_(originalFail);
            return;
        }

        mFail = mMaker.label();
        subFilters[0].accept(this);
        mFail.here();

        // Only the state observed on the left tree path can be preserved, because it's
        // guaranteed to have executed.
        final int hk = mHighestLocatedKey;
        final int hv = mHighestLocatedValue;

        for (int i=1; i<subFilters.length; i++) {
            mFail = mMaker.label();
            subFilters[i].accept(this);
            mFail.here();
        }

        resetHighestLocatedKey(hk);
        resetHighestLocatedValue(hv);

        mMaker.goto_(originalFail);
        mFail = originalFail;
    }

    @Override
    public void visit(AndFilter filter) {
        final Label originalPass = mPass;

        RowFilter[] subFilters = filter.subFilters();

        if (subFilters.length == 0) {
            mMaker.goto_(originalPass);
            return;
        }

        mPass = mMaker.label();
        subFilters[0].accept(this);
        mPass.here();

        // Only the state observed on the left tree path can be preserved, because it's
        // guaranteed to have executed.
        final int hk = mHighestLocatedKey;
        final int hv = mHighestLocatedValue;

        for (int i=1; i<subFilters.length; i++) {
            mPass = mMaker.label();
            subFilters[i].accept(this);
            mPass.here();
        }

        resetHighestLocatedKey(hk);
        resetHighestLocatedValue(hv);

        mMaker.goto_(originalPass);
        mPass = originalPass;
    }

    @Override
    public void visit(ColumnToArgFilter filter) {
        final Label originalFail = mFail;

        ColumnInfo colInfo = filter.column();
        int op = filter.operator();
        int argNum = filter.argument();

        Integer colNum = columnNumberFor(colInfo.name);

        Label stop = null;
        if (argNum == mStopArgument && colInfo.name.equals(mStopColumn)
            && mMaker.param(1).classType() == Cursor.class)
        {
            stop = mMaker.label();
            mFail = stop;
        }

        doCompare:
        if (colNum != null) {
            ColumnCodec codec = codecFor(colNum);
            Object dc = decodeColumn(colNum, colInfo, true);
            Variable decodedVar;
            if (dc instanceof LocatedColumn located) {
                Object decoded = located.mDecodedQuick;
                if (decoded != null) {
                    codec.filterQuickCompare(colInfo, located.mSrcVar, located.mOffsetVar, op,
                                             decoded, mPredicateVar, argNum, mPass, mFail);
                    break doCompare;
                }
                decodedVar = located.mDecodedVar;
            } else {
                decodedVar = (Variable) dc;
            }
            var argField = mPredicateVar.field(ColumnCodec.argFieldName(colInfo, argNum));
            CompareUtils.compare(mMaker, colInfo, decodedVar, colInfo, argField, op, mPass, mFail);
        } else {
            // Column doesn't exist in the row, so compare against a default. This code
            // assumes that value codecs always define an arg field which preserves the
            // original argument, possibly converted to the correct type.
            var argField = mPredicateVar.field(ColumnCodec.argFieldName(colInfo, argNum));
            var columnVar = mMaker.var(colInfo.type);
            Converter.setDefault(mMaker, colInfo, columnVar);
            CompareUtils.compare(mMaker, colInfo, columnVar, colInfo, argField, op, mPass, mFail);
        }

        if (stop != null) {
            stop.here();
            // Reset the cursor to stop scanning.
            mMaker.param(1).invoke("reset");
            mMaker.var(StoppedCursorException.class).field("THE").throw_();
            //mMaker.goto_(originalFail);
            mFail = originalFail;
        }
    }

    @Override
    public void visit(ColumnToColumnFilter filter) {
        ColumnInfo aColInfo = filter.column();
        int op = filter.operator();
        ColumnInfo bColInfo = filter.otherColumn();

        Integer aColNum = columnNumberFor(aColInfo.name);
        Integer bColNum = columnNumberFor(bColInfo.name);

        if (aColNum == null && bColNum == null) {
            // Comparing two columns that don't exist. If this filter is part of a chain,
            // the rest will be dead code.
            mMaker.goto_(CompareUtils.selectNullColumnToNullArg(op, mPass, mFail));
            return;
        }

        Variable aVar = decodeColumnOrDefault(aColNum, aColInfo);
        Variable bVar = decodeColumnOrDefault(bColNum, bColInfo);

        if (aVar.classType() != bVar.classType()) {
            ColumnInfo cColInfo = filter.common();

            var aConvertedVar = mMaker.var(cColInfo.type);
            Converter.convertLossy(mMaker, aColInfo, aVar, cColInfo, aConvertedVar);
            aColInfo = cColInfo;
            aVar = aConvertedVar;

            var bConvertedVar = mMaker.var(cColInfo.type);
            Converter.convertLossy(mMaker, bColInfo, bVar, cColInfo, bConvertedVar);
            bColInfo = cColInfo;
            bVar = bConvertedVar;
        }

        CompareUtils.compare(mMaker, aColInfo, aVar, bColInfo, bVar, op, mPass, mFail);
    }

    private Variable decodeColumnOrDefault(Integer colNum, ColumnInfo colInfo) {
        if (colNum != null) {
            Object dc = decodeColumn(colNum, colInfo, false);
            if (dc instanceof LocatedColumn located) {
                return located.mDecodedVar;
            } else {
                return (Variable) dc;
            }
        } else {
            var colVar = mMaker.var(colInfo.type);
            Converter.setDefault(mMaker, colInfo, colVar);
            return colVar;
        }
    }

    private Integer columnNumberFor(String colName) {
        return mRowGen.columnNumbers().get(colName);
    }

    private ColumnCodec codecFor(int colNum) {
        ColumnCodec[] codecs = mKeyCodecs;
        return colNum < codecs.length ? codecs[colNum] : mValueCodecs[colNum - codecs.length];
    }

    /**
     * Decodes a column and remembers it if requested again later.
     *
     * @param colInfo current definition for column
     * @param quick allow quick decode
     * @return a Variable or LocatedColumn
     */
    private Object decodeColumn(int colNum, ColumnInfo colInfo, boolean quick) {
        Variable srcVar;
        LocatedColumn[] located;
        ColumnCodec[] codecs = mKeyCodecs;
        int highestNum;

        init: {
            int startOffset;
            if (colNum < codecs.length) {
                // Key column.
                highestNum = mHighestLocatedKey;
                srcVar = mMaker.param(0);
                if ((located = mLocatedKeys) != null) {
                    break init;
                }
                mLocatedKeys = located = new LocatedColumn[mRowGen.info.keyColumns.size()];
                startOffset = 0;
            } else {
                // Value column.
                colNum -= codecs.length;
                highestNum = mHighestLocatedValue;
                srcVar = mValueVar;
                codecs = mValueCodecs;
                if ((located = mLocatedValues) != null) {
                    break init;
                }
                mLocatedValues = located = new LocatedColumn[mRowGen.info.valueColumns.size()];
                startOffset = mValueOffset;
            }
            located[0] = new LocatedColumn();
            located[0].located(srcVar, mMaker.var(int.class).set(startOffset));
        }

        if (srcVar.classType() != byte[].class) {
            // Access the column from a row object.
            return srcVar.field(colInfo.name).get();
        }

        if (colNum < highestNum) {
            LocatedColumn col = located[colNum];
            if (col.isDecoded(quick)) {
                return col;
            }
            // Regress the highest to force the column to be decoded. The highest field
            // won't regress, since the field assignment (at the end) checks this.
            highestNum = colNum;
        }

        if (!located[highestNum].isLocated()) {
            throw new AssertionError();
        }

        for (; highestNum <= colNum; highestNum++) {
            // Offset will be mutated, and so a copy must be made before calling decode.
            Variable offsetVar = located[highestNum].mOffsetVar;

            LocatedColumn next;
            copyOffsetVar: {
                if (highestNum + 1 >= located.length) {
                    next = null;
                } else {
                    next = located[highestNum + 1];
                    if (next == null) {
                        next = new LocatedColumn();
                        located[highestNum + 1] = next;
                    } else if (!next.isLocated()) {
                        // Can recycle the offset variable because it's not used.
                        Variable freeVar = next.mOffsetVar;
                        if (freeVar != null) {
                            freeVar.set(offsetVar);
                            offsetVar = freeVar;
                            break copyOffsetVar;
                        }
                    }
                }
                offsetVar = offsetVar.get();
            }

            ColumnCodec codec = codecs[highestNum];
            Variable endVar = null;
            if (highestNum < colNum) {
                codec.decodeSkip(srcVar, offsetVar, endVar);
            } else if (quick && codec.canFilterQuick(colInfo)) {
                Object decoded = codec.filterQuickDecode(colInfo, srcVar, offsetVar, endVar);
                located[highestNum].decodedQuick(decoded);
            } else {
                Variable dstVar = mMaker.var(colInfo.type);
                Converter.decode(mMaker, srcVar, offsetVar, endVar, codec, colInfo, dstVar);
                located[highestNum].decodedVar(dstVar);
            }

            if (next != null && !next.isLocated()) {
                // The decode call incremented offsetVar as a side-effect. Note that if the
                // column is already located, then newly discovered offset will match. It can
                // simply be replaced, but by discarding it, the compiler can discard some of
                // the redundant steps which computed the offset again.
                next.located(srcVar, offsetVar);
            }
        }

        highestNum = Math.min(highestNum, located.length - 1);

        if (located == mLocatedKeys) {
            if (highestNum > mHighestLocatedKey) {
                mHighestLocatedKey = highestNum;
            }
        } else {
            if (highestNum > mHighestLocatedValue) {
                mHighestLocatedValue = highestNum;
            }
        }

        return located[colNum];
    }

    /**
     * Reset the highest located key column. The trailing LocatedColumn instances can be
     * re-used, reducing the number of Variables created.
     */
    private void resetHighestLocatedKey(int colNum) {
        if (colNum < mHighestLocatedKey) {
            mHighestLocatedKey = colNum;
            finishReset(mLocatedKeys, colNum);
        }
    }

    /**
     * Reset the highest located value column. The trailing LocatedColumn instances can be
     * re-used, reducing the number of Variables created.
     *
     * @param colNum column number among all value columns
     */
    private void resetHighestLocatedValue(int colNum) {
        if (colNum < mHighestLocatedValue) {
            mHighestLocatedValue = colNum;
            finishReset(mLocatedValues, colNum);
        }
    }

    private static void finishReset(LocatedColumn[] columns, int colNum) {
        while (++colNum < columns.length) {
            var col = columns[colNum];
            if (col == null) {
                break;
            }
            col.unlocated();
        }
    }

    private static class LocatedColumn {
        // Used by mState field.
        private static final int UNLOCATED = 0, LOCATED = 1, DECODED = 2;

        private int mState;

        // Source byte array. Is valid when mState is LOCATED or DECODED.
        Variable mSrcVar;

        // Offset into the byte array. Is valid when mState is LOCATED or DECODED.
        Variable mOffsetVar;

        // Is only valid when mState is DECODED and canFilterQuick returned true.
        Object mDecodedQuick;

        // Is only valid when mState is DECODED.
        Variable mDecodedVar;

        LocatedColumn() {
        }

        boolean isLocated() {
            return mState >= LOCATED;
        }

        /**
         * @param quick when true, accepts quick or fully decoded forms; when false, only
         * accepts the fully decoded form
         */
        boolean isDecoded(boolean quick) {
            return mState == DECODED && (quick || mDecodedVar != null);
        }

        /**
         * @param srcVar source byte array
         * @param offsetVar start offset into the byte array
         */
        void located(Variable srcVar, Variable offsetVar) {
            mSrcVar = srcVar;
            mOffsetVar = offsetVar;
            mState = LOCATED;
        }

        /**
         * @param decoded object returned from ColumnCodec.filterQuickDecode
         */
        void decodedQuick(Object decoded) {
            if (mState == UNLOCATED) {
                throw new IllegalStateException();
            }
            mDecodedQuick = decoded;
            mState = DECODED;
        }

        void decodedVar(Variable decodedVar) {
            if (mState == UNLOCATED) {
                throw new IllegalStateException();
            }
            mDecodedVar = decodedVar;
            mState = DECODED;
        }

        void unlocated() {
            mDecodedQuick = null;
            mDecodedVar = null;
            mState = UNLOCATED;
        }
    }
}