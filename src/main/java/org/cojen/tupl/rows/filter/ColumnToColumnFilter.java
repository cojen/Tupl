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

package org.cojen.tupl.rows.filter;

import java.util.Map;

import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.ConvertUtils;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class ColumnToColumnFilter extends ColumnFilter {
    private final ColumnInfo mOtherColumn;
    private final ColumnInfo mCommon;

    /**
     * @param common only needs to have a type and typeCode assigned
     */
    ColumnToColumnFilter(ColumnInfo column, int op, ColumnInfo other, ColumnInfo common) {
        super(hash(column, op, other), column, op);
        mOtherColumn = other;
        mCommon = common;
    }

    private static int hash(ColumnInfo column, int op, ColumnInfo other) {
        int hash = column.hashCode();
        hash = hash * 31 + op;
        hash = hash * 31 + other.hashCode();
        return hash;
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }

    @Override
    protected int maxArgument(int max) {
        return max;
    }

    @Override
    public int isMatch(RowFilter filter) {
        if (filter == this) {
            return 1; // equal
        }
        if (filter instanceof ColumnToColumnFilter other) {
            if (mColumn.equals(other.mColumn) && mOtherColumn.equals(other.mOtherColumn)) {
                if (mOperator == other.mOperator) {
                    return 1; // equal
                } else if (mOperator == flipOperator(other.mOperator)) {
                    return -1; // inverse is equal
                }
            }
        }
        return 0; // doesn't match
    }

    @Override
    public int matchHashCode() {
        int hash = mMatchHashCode;
        if (hash == 0) {
            hash = mColumn.hashCode();
            hash = hash * 31 + (mOperator & ~1); // exclude the bit used to flip the operator
            hash = hash * 31 + mOtherColumn.hashCode();
            mMatchHashCode = hash;
        }
        return hash;
    }

    @Override
    public RowFilter replaceArguments(IntUnaryOperator function) {
        return this;
    }

    @Override
    public RowFilter argumentAsNull(int argNum) {
        return this;
    }

    @Override
    public RowFilter retain(Predicate<String> pred, boolean strict, RowFilter undecided) {
        // If strict is true, then the predicate must return true for both columns in order for
        // this filter to be retained. If strict is false, then the predicate must return true
        // for at least one of the columns on order for this filter to be retained.
        if (pred.test(mColumn.name)) { 
            if (strict && !pred.test(mOtherColumn.name)) {
                return undecided;
            }
        } else {
            if (strict || !pred.test(mOtherColumn.name)) {
                return undecided;
            }
        }
        return this;
    }

    @Override
    protected boolean canSplit(Map<String, ?> columns) {
        return columns.containsKey(mColumn.name) && columns.containsKey(mOtherColumn.name);
    }

    @Override
    public boolean uniqueColumn(String columnName) {
        return false;
    }

    public ColumnInfo otherColumn() {
        return mOtherColumn;
    }

    /**
     * Returns a ColumnInfo that only has the type and typeCode assigned, which represents a
     * common type that both columns can be converted to for comparison.
     */
    public ColumnInfo common() {
        return mCommon;
    }

    @Override
    public final boolean equals(Object obj) {
        return obj == this || obj instanceof ColumnToColumnFilter other
            && mColumn.equals(other.mColumn) && mOperator == other.mOperator
            && mOtherColumn.equals(other.mOtherColumn);
    }

    @Override
    public int compareTo(RowFilter filter) {
        if (!(filter instanceof ColumnToColumnFilter other)) {
            return super.compareTo(filter);
        }
        int cmp = mColumn.name.compareTo(other.mColumn.name);
        if (cmp == 0) {
            cmp = Integer.compare(mOperator, other.mOperator);
            if (cmp == 0) {
                cmp = mOtherColumn.name.compareTo(other.mOtherColumn.name);
            }
        }
        return cmp;
    }

    @Override
    boolean equalRhs(ColumnFilter other) {
        return other instanceof ColumnToColumnFilter ctcf && mOtherColumn.equals(ctcf.mOtherColumn);
    }

    @Override
    ColumnToColumnFilter withOperator(int op) {
        return new ColumnToColumnFilter(mColumn, op, mOtherColumn, mCommon);
    }

    /**
     * @return null if a common type doesn't exist
     */
    public ColumnToColumnFilter tryWithColumns(ColumnInfo column, ColumnInfo other) {
        ColumnInfo common = ConvertUtils.commonType(column, other, mOperator);
        if (common == null) {
            return null;
        }
        return new ColumnToColumnFilter(column, mOperator, other, common);
    }

    @Override
    public void appendTo(StringBuilder b) {
        super.appendTo(b);
        b.append(mOtherColumn.name);
    }
}
