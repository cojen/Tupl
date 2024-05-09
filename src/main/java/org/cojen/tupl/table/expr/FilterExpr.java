/*
 *  Copyright (C) 2024 Cojen.org
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

package org.cojen.tupl.table.expr;

import java.util.Map;

import org.cojen.tupl.table.ColumnInfo;
import org.cojen.tupl.table.ColumnSet;
import org.cojen.tupl.table.CompareUtils;
import org.cojen.tupl.table.RowInfo;

import org.cojen.tupl.table.filter.ColumnFilter;
import org.cojen.tupl.table.filter.ColumnToArgFilter;
import org.cojen.tupl.table.filter.ColumnToColumnFilter;
import org.cojen.tupl.table.filter.ColumnToConstantFilter;
import org.cojen.tupl.table.filter.RowFilter;

import static org.cojen.tupl.table.expr.Token.*;

import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public final class FilterExpr extends BinaryOpExpr {
    private final Expr mOriginalLeft, mOriginalRight;

    /**
     * @param op must be less than or equal to T_LOR
     * @see BinaryOpExpr#make
     */
    FilterExpr(int startPos, int endPos,
               int op, Expr originalLeft, Expr originalRight, Expr left, Expr right)
    {
        super(startPos, endPos, BasicType.BOOLEAN, op, left, right);
        mOriginalLeft = originalLeft;
        mOriginalRight = originalRight;
    }

    @Override
    public FilterExpr asType(Type type) {
        if (mType.equals(type)) {
            return this;
        }
        throw new QueryException("Cannot convert " + mType + " to " + type,
                                 mLeft.startPos(), mRight.endPos());
    }

    @Override
    public Expr not(int startPos) {
        int op = mOp;
        if (op < T_LAND) {
            return make(startPos, endPos(), op ^ 1, mOriginalLeft, mOriginalRight);
        } else if (op <= T_LOR) {
            // Apply De Morgan's law.
            return make(startPos, endPos(), op ^ 1, mOriginalLeft.not(), mOriginalRight.not());
        } else {
            throw new AssertionError();
        }
    }

    @Override
    public RowFilter toRowFilter(RowInfo info, Map<String, ColumnExpr> columns) {
        if (mOp == T_LAND) {
            return mLeft.toRowFilter(info, columns).and(mRight.toRowFilter(info, columns));
        } else if (mOp == T_LOR) {
            return mLeft.toRowFilter(info, columns).or(mRight.toRowFilter(info, columns));
        }

        // Attempt to push down the original expressions, not the ones which have been
        // converted to a common type. The lower filtering layer will perform any conversions
        // itself if necessary.

        if (mOriginalLeft instanceof ColumnExpr left) {
            ColumnInfo leftCol = tryFindColumn(info, left);
            if (leftCol != null) {
                if (mOriginalRight instanceof ColumnExpr right) {
                    ColumnInfo rightCol = tryFindColumn(info, right);
                    if (rightCol != null) {
                        var filter = ColumnToColumnFilter.tryMake(leftCol, mOp, rightCol);
                        if (filter != null) {
                            columns.putIfAbsent(leftCol.name, left);
                            columns.putIfAbsent(rightCol.name, right);
                            return filter;
                        }
                    }
                } else if (mOriginalRight instanceof ParamExpr right) {
                    columns.putIfAbsent(leftCol.name, left);
                    return new ColumnToArgFilter(leftCol, mOp, right.ordinal());
                } else if (mOriginalRight instanceof ConstantExpr right) {
                    columns.putIfAbsent(leftCol.name, left);
                    return new ColumnToConstantFilter(leftCol, mOp, right);
                }
            }
        } else if (mOriginalRight instanceof ColumnExpr right) {
            ColumnInfo rightCol = tryFindColumn(info, right);
            if (rightCol != null) {
                if (mOriginalLeft instanceof ParamExpr left) {
                    int op = ColumnFilter.reverseOperator(mOp);
                    columns.putIfAbsent(rightCol.name, right);
                    return new ColumnToArgFilter(rightCol, op, left.ordinal());
                } else if (mOriginalLeft instanceof ConstantExpr left) {
                    int op = ColumnFilter.reverseOperator(mOp);
                    columns.putIfAbsent(rightCol.name, right);
                    return new ColumnToConstantFilter(rightCol, op, left);
                }
            }
        }

        return super.toRowFilter(info, columns);
    }

    private static ColumnInfo tryFindColumn(RowInfo info, ColumnExpr expr) {
        return ColumnSet.findColumn(info.allColumns, expr.name());
    }

    @Override
    public boolean isNullable() {
        return false;
    }

    @Override
    public Variable makeEval(EvalContext context) {
        return makeEvalForFilter(context);
    }

    @Override
    public void makeFilter(EvalContext context, Label pass, Label fail) {
        if (isPureFunction()) {
            Variable result = context.refFor(this).get();
            if (result != null) {
                result.ifTrue(pass);
                fail.goto_();
                return;
            }
        }

        switch (mOp) {
        case T_EQ, T_NE, T_GE, T_LT, T_LE, T_GT:
            CompareUtils.compare(context.methodMaker(),
                                 mLeft.type(), mLeft.makeEval(context),
                                 mRight.type(), mRight.makeEval(context),
                                 mOp, pass, fail);
            return;

        case T_LAND: {
            Label andPass = context.methodMaker().label();
            mLeft.makeFilter(context, andPass, fail);
            andPass.here();
            int savepoint = context.refSavepoint();
            mRight.makeFilter(context, pass, fail);
            // Rollback the refs for the right expression, because it doesn't always execute.
            context.refRollback(savepoint);
            return;
        }

        case T_LOR:
            Label orFail = context.methodMaker().label();
            mLeft.makeFilter(context, pass, orFail);
            orFail.here();
            int savepoint = context.refSavepoint();
            mRight.makeFilter(context, pass, fail);
            // Rollback the refs for the right expression, because it doesn't always execute.
            context.refRollback(savepoint);
            return;
        }

        throw new AssertionError();
    }

    @Override
    public Variable makeFilterEval(EvalContext context) {
        return makeEval(context);
    }

    @Override
    public boolean canThrowRuntimeException() {
        return mLeft.canThrowRuntimeException() || mRight.canThrowRuntimeException();
    }
}