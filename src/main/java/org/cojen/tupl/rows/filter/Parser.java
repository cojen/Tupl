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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.ConvertUtils;
import org.cojen.tupl.rows.OrderBy;
import org.cojen.tupl.rows.SimpleParser;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class Parser extends SimpleParser {
    private final Map<String, ? extends ColumnInfo> mAllColumns;

    private int mNextArg;
    private Map<Integer, Boolean> mInArgs;
    private boolean mNoFilter;

    private LinkedHashMap<String, ColumnInfo> mProjection;
    private OrderBy mOrderBy;

    public Parser(Map<String, ? extends ColumnInfo> allColumns, String filter) {
        super(filter);
        mAllColumns = allColumns;
    }

    /**
     * Parses a projection and row filter.
     *
     * @param availableColumns can pass null if same as all columns
     */
    public Query parseQuery(Map<String, ? extends ColumnInfo> availableColumns) {
        parseProjection(availableColumns);
        return new Query(mProjection, mOrderBy, parseFilter());
    }

    /**
     * Projection   = "{" ProjColumns "}" [ RowFilter ]
     * ProjColumns  = [ ProjColumn { "," ProjColumn } ]
     * ProjColumn   = ( ( ( ( "+" | "-" ) [ "!" ] ) | "~" ) ColumnName ) | "*"
     *
     * Returns null if string doesn't start with a projection or if the projection is all of
     * the available columns. If the projection ends with a non-whitespace character, then a
     * subsequent call to parseFilter expects a filter. If only whitespace characters remain,
     * parseFilter expects nothing, and so it returns TrueFilter.
     *
     * @param availableColumns can pass null if same as all columns
     */
    private void parseProjection(Map<String, ? extends ColumnInfo> availableColumns) {
        if (availableColumns == null) {
            availableColumns = mAllColumns;
        }

        final int start = mPos;

        int c = nextCharIgnoreWhitespace();
        if (c != '{') {
            mPos = start;
            return;
        }

        var projection = new LinkedHashMap<String, ColumnInfo>();
        OrderBy orderBy = null;
        Map<String, ColumnInfo> excluded = null;
        boolean wildcard = false;

        if ((c = nextCharIgnoreWhitespace()) != '}') {
            mPos--;

            while (true) {
                if (c == '*') {
                    wildcard = true;
                    mPos++;
                } else {
                    int colStart = mPos;

                    //    -1: exclude
                    // 0b000: plain
                    // 0b010: ascending
                    // 0b011: descending
                    // 0b1xx: null low
                    int prefix;
                    prefix: {
                        if (c == '~') {
                            prefix = -1;
                            skipCharIgnoreWhitespace();
                        } else {
                            if (c == '+') {
                                prefix = 0b010;
                            } else if (c == '-') {
                                prefix = 0b011;
                            } else {
                                prefix = 0b000;
                                break prefix;
                            }
                            if (skipCharIgnoreWhitespace() == '!') {
                                prefix |= 0b100;
                                skipCharIgnoreWhitespace();
                            }
                        }
                    }

                    // If column is to be excluded, pass all available columns to suppress an
                    // error when attempting to remove a column that isn't available anyhow.
                    ColumnInfo column = parseColumn(prefix < 0 ? null : availableColumns);

                    if (mText.charAt(mPos) == '*') {
                        // Skip the implicit wildcard suffix if allowed.
                        if (column.isScalarType()) {
                            mPos = colStart;
                            throw error("Wildcard disallowed for scalar column");
                        }
                        mPos++;
                    }

                    String name = column.name;

                    if (prefix < 0) {
                        if (projection.containsKey(name)) {
                            mPos = colStart;
                            throw error("Cannot exclude a column which is explicitly selected");
                        }
                        if (excluded == null) {
                            excluded = new HashMap<>();
                        }
                        column.gatherScalarColumns(excluded);
                    } else {
                        if (excluded != null && excluded.containsKey(name)) {
                            mPos = colStart;
                            throw error("Cannot select a column which is also excluded");
                        }

                        projection.put(name, column);

                        if (prefix != 0) orderBy: {
                            if (orderBy == null) {
                                orderBy = new OrderBy();
                            } else if (orderBy.containsKey(name)) {
                                break orderBy;
                            }
                            int type = column.typeCode;
                            if ((prefix & 0b011) == 0b011) {
                                type |= ColumnInfo.TYPE_DESCENDING;
                            }
                            if ((prefix & 0b100) == 0b100) {
                                type |= ColumnInfo.TYPE_NULL_LOW;
                            }
                            orderBy.put(name, new OrderBy.Rule(column, type));
                        }
                    }
                }

                c = nextCharIgnoreWhitespace();
                if (c == '}') {
                    break;
                } else if (c != ',') {
                    mPos--;
                    throw error("Comma expected");
                }

                c = nextCharIgnoreWhitespace();
                mPos--;
            }
        }

        if (nextCharIgnoreWhitespace() < 0) {
            mNoFilter = true;
        } else {
            mPos--;
        }

        if (wildcard) {
            if (excluded == null) {
                // A null projection means all columns.
                projection = null;
            } else {
                for (ColumnInfo info : availableColumns.values()) {
                    info.gatherScalarColumns(projection);
                }
                if (!projection.keySet().removeAll(excluded.keySet())) {
                    // Nothing was actually excluded, and so all columns are projected.
                    projection = null;
                }
            }
        } else if (excluded != null) {
            mPos = start;
            throw error("Must include wildcard if any columns are excluded");
        }

        mProjection = projection;
        mOrderBy = orderBy;
    }

    /**
     * Skips the projection portion without performing any validation.
     */
    public void skipProjection() {
        final int start = mPos;
        int c = nextCharIgnoreWhitespace();
        if (c == '{') {
            mPos = mText.indexOf('}', mPos);
            if (mPos < 0) {
                mPos = mText.length();
                mNoFilter = true;
            } else {
                mPos++;
            }
        } else {
            mPos = start;
        }
    }

    /**
     * RowFilter    = AndFilter { "||" AndFilter }
     * AndFilter    = EntityFilter { "&&" EntityFilter }
     * EntityFilter = ColumnFilter | ParenFilter
     * ParenFilter  = [ "!" ] "(" RowFilter ")"
     * ColumnFilter = ColumnName RelOp ( ArgRef | ColumnName )
     *              | ColumnName "in" ArgRef
     * RelOp        = "==" | "!=" | ">=" | "<" | "<=" | ">"
     * ColumnName   = string
     * ArgRef       = "?" [ uint ]
     */
    public RowFilter parseFilter() {
        if (mNoFilter) {
            return TrueFilter.THE;
        }

        mInArgs = new HashMap<>();
        RowFilter filter = doParseFilter();
        int c = nextCharIgnoreWhitespace();
        if (c >= 0) {
            mPos--;
            throw error("Unexpected trailing characters");
        }
        return filter;
    }

    private RowFilter doParseFilter() {
        return parseOrFilter();
    }

    private RowFilter parseOrFilter() {
        ArrayList<RowFilter> list = null;
        RowFilter filter = parseAndFilter();

        while (true) {
            int c = nextCharIgnoreWhitespace();
            if (c != '|') {
                mPos--;
                break;
            }
            c = nextChar();
            if (c != '|') {
                mPos -= 2;
                throw error("Or operator must be specified as '||'");
            }
            operatorCheck();
            if (list == null) {
                list = new ArrayList<>();
                addToOrFilter(list, filter);
            }
            addToOrFilter(list, parseAndFilter());
        }

        if (list == null) {
            return filter;
        }

        return new OrFilter(list.toArray(new RowFilter[list.size()]));
    }

    private static void addToOrFilter(ArrayList<RowFilter> list, RowFilter filter) {
        if (filter instanceof OrFilter of) {
            Collections.addAll(list, of.mSubFilters);
        } else {
            list.add(filter);
        }
    }

    private RowFilter parseAndFilter() {
        ArrayList<RowFilter> list = null;
        RowFilter filter = parseEntityFilter();

        while (true) {
            int c = nextCharIgnoreWhitespace();
            if (c != '&') {
                mPos--;
                break;
            }
            c = nextChar();
            if (c != '&') {
                mPos -= 2;
                throw error("And operator must be specified as '&&'");
            }
            operatorCheck();
            if (list == null) {
                list = new ArrayList<>();
                addToAndFilter(list, filter);
            }
            addToAndFilter(list, parseEntityFilter());
        }

        if (list == null) {
            return filter;
        }

        return new AndFilter(list.toArray(new RowFilter[list.size()]));
    }

    private static void addToAndFilter(ArrayList<RowFilter> list, RowFilter filter) {
        if (filter instanceof AndFilter af) {
            Collections.addAll(list, af.mSubFilters);
        } else {
            list.add(filter);
        }
    }

    private RowFilter parseEntityFilter() {
        int c = nextCharIgnoreWhitespace();

        if (c == '!') {
            c = nextCharIgnoreWhitespace();
            if (c != '(') {
                mPos--;
                throw error("Left paren expected");
            }
            return parseParenFilter().not();
        } else if (c == '(') {
            return parseParenFilter();
        }

        mPos--;

        // parseColumnFilter

        int startPos = mPos;
        ColumnInfo column = parseColumn(null);

        c = nextCharIgnoreWhitespace();

        int op;
        switch (c) {
            case '=' -> {
                c = nextChar();
                if (c == '=') {
                    op = ColumnFilter.OP_EQ;
                } else {
                    mPos -= 2;
                    throw error("Equality operator must be specified as '=='");
                }
                operatorCheck();
            }
            case '!' -> {
                c = nextChar();
                if (c == '=') {
                    op = ColumnFilter.OP_NE;
                } else {
                    mPos -= 2;
                    throw error("Inequality operator must be specified as '!='");
                }
                operatorCheck();
            }
            case '<' -> {
                c = nextChar();
                if (c == '=') {
                    op = ColumnFilter.OP_LE;
                } else {
                    mPos--;
                    op = ColumnFilter.OP_LT;
                }
                operatorCheck();
            }
            case '>' -> {
                c = nextChar();
                if (c == '=') {
                    op = ColumnFilter.OP_GE;
                } else {
                    mPos--;
                    op = ColumnFilter.OP_GT;
                }
                operatorCheck();
            }
            case 'i' -> {
                c = nextChar();
                if (c == 'n') {
                    op = ColumnFilter.OP_IN;
                } else {
                    mPos -= 2;
                    throw error("Unknown operator");
                }
                operatorCheck(true);
            }
            case '?' -> {
                mPos--;
                throw error("Relational operator missing");
            }
            case '*' -> {
                mPos--;
                throw error("Wildcard disallowed here");
            }
            case -1 -> throw error("Relational operator expected");
            default -> {
                mPos--;
                throw error("Unknown operator");
            }
        }

        c = nextCharIgnoreWhitespace();

        int arg;
        if (c == '?') {
            // column-to-arg comparison
            arg = tryParseArgNumber();
            if (arg < 0) {
                arg = ++mNextArg;
            }
        } else {
            // column-to-column comparison

            mPos--;
            if (op >= ColumnFilter.OP_IN) {
                throw error("Argument number or '?' expected");
            }

            ColumnInfo match = parseColumn(null);

            ColumnInfo common = ConvertUtils.commonType(column, match, op);

            if (common == null) {
                throw error("Cannot compare '" + column.name + "' to '" + match.name + "' " +
                            "due to type mismatch", startPos);
            }

            return new ColumnToColumnFilter(column, op, match, common);
        }

        ColumnToArgFilter filter;
        Boolean in;

        if (op < ColumnFilter.OP_IN) {
            filter = new ColumnToArgFilter(column, op, arg);
            in = Boolean.FALSE;
        } else {
            filter = new InFilter(column, arg);
            in = Boolean.TRUE;
        }

        Boolean existing = mInArgs.putIfAbsent(arg, in);

        if (existing != null && existing != in) {
            throw error("Mismatched argument usage with 'in' operator", startPos);
        }

        return filter;
    }

    // Left paren has already been consumed.
    private RowFilter parseParenFilter() {
        RowFilter filter = doParseFilter();
        if (nextCharIgnoreWhitespace() != ')') {
            mPos--;
            throw error("Right paren expected");
        }
        return filter;
    }

    private void operatorCheck() {
        operatorCheck(false);
    }

    private void operatorCheck(boolean text) {
        int c = nextChar();
        mPos--;
        switch (c) {
        case -1:
        case '?': case '!':
        case '(': case ')':
        case ' ': case '\r': case '\n': case '\t': case '\0':
            return;
        default:
            if (Character.isWhitespace(c)) {
                return;
            }
        }
        if (Character.isJavaIdentifierStart(c) == text) {
            throw error("Unknown operator");
        }
    }

    /**
     * @param availableColumns can pass null if same as all columns
     */
    private ColumnInfo parseColumn(Map<String, ? extends ColumnInfo> availableColumns) {
        int start = mPos;
        String name = parseColumnName(start);

        ColumnInfo column = mAllColumns.get(name);

        while (true) {
            if (column == null) {
                mPos = start;
                throw error("Unknown column: " + name);
            }
            if (nextCharIgnoreWhitespace() != '.') {
                mPos--;
                break;
            }
            boolean wildcard = nextCharIgnoreWhitespace() == '*';
            mPos--;
            if (wildcard) {
                break;
            }
            String subName = parseColumnName(mPos);
            name = name + '.' + subName;
            column = column.subColumn(subName);
        }

        if (availableColumns == null || availableColumns == mAllColumns) {
            // All columns are available.
            if (!name.equals(column.name)) {
                // Assign the full path name.
                column = column.copy();
                column.name = name;
            }
        } else {
            column = availableColumns.get(name);
            if (column == null) {
                mPos = start;
                throw error("Column is unavailable for selection: " + name);
            }
        }

        return column;
    }

    private String parseColumnName(int start) {
        int c = nextCharIgnoreWhitespace();
        if (c < 0) {
            throw error("Column name expected");
        }
        if (!Character.isJavaIdentifierStart(c)) {
            mPos--;
            if (c == '*') {
                throw error("Wildcard disallowed here");
            }
            throw error("Not a valid character for start of column name: '" + (char) c + '\'');
        }
        do {
            c = nextChar();
        } while (Character.isJavaIdentifierPart(c));

        return mText.substring(start, --mPos);
    }

    /**
     * @return -1 if no arg number
     */
    private int tryParseArgNumber() {
        final int start = mPos;

        long arg;
        int c = nextChar();
        if ('0' <= c && c <= '9') {
            arg = c - '0';
        } else if (c < 0) {
            return -1;
        } else {
            if (c == ')' || c == '|' || c == '&' || Character.isWhitespace(c)) {
                mPos--;
                return -1;
            }
            mPos = start;
            throw error("Malformed argument number");
        }

        while (true) {
            c = nextChar();
            if ('0' <= c && c <= '9') {
                arg = arg * 10 + (c - '0');
                if (arg > Integer.MAX_VALUE) {
                    mPos = start;
                    throw error("Argument number is too large");
                }
            } else {
                mPos--;
                break;
            }
        }

        if (arg <= 0) {
            mPos = start;
            throw error("Argument number must be at least one");
        }

        return (int) arg;
    }

    @Override
    protected String describe() {
        return "filter expression";
    }
}
