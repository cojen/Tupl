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

package org.cojen.tupl.filter;

import java.util.ArrayList;
import java.util.Map;

import org.cojen.tupl.rows.ColumnInfo;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class Parser {
    /*
      RowFilter    = AndFilter { "|" AndFilter }
      AndFilter    = EntityFilter { "&" EntityFilter }
      EntityFilter = ColumnFilter | ParenFilter
      ParenFilter  = [ "!" ] "(" Filter ")"
      ColumnFilter = ColumnName RelOp ( ArgRef | ColumnName )
                   | ColumnName "in" ArgRef
      RelOp        = "==" | "!=" | "<" | ">=" | ">" | "<="
      ColumnName   = string
      ArgRef       = "?" [ uint ]
     */

    private final Map<String, ColumnInfo> mAllColumns;
    private final String mFilter;
    private int mPos;
    private int mNextArg;

    public Parser(Map<String, ColumnInfo> allColumns, String filter) {
        mAllColumns = allColumns;
        mFilter = filter;
    }

    public RowFilter parse() {
        RowFilter filter = parseFilter();
        int c = nextCharIgnoreWhitespace();
        if (c >= 0) {
            mPos--;
            throw error("Unexpected trailing characters");
        }
        return filter;
    }

    private RowFilter parseFilter() {
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
        if (filter instanceof OrFilter) {
            addAll(list, (OrFilter) filter);
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
        if (filter instanceof AndFilter) {
            addAll(list, (AndFilter) filter);
        } else {
            list.add(filter);
        }
    }

    private static void addAll(ArrayList<RowFilter> list, GroupFilter group) {
        for (RowFilter subFilters : group.mSubFilters) {
            list.add(subFilters);
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

        ColumnInfo column = parseColumn();

        c = nextCharIgnoreWhitespace();

        int op;
        switch (c) {
        case '=':
            c = nextChar();
            if (c == '=') {
                op = ColumnFilter.OP_EQ;
            } else {
                mPos--;
                throw error("Equality operator must be specified as '=='");
            }
            operatorCheck();
            break;
        case '!':
            c = nextChar();
            if (c == '=') {
                op = ColumnFilter.OP_NE;
            } else {
                mPos--;
                throw error("Inequality operator must be specified as '!='");
            }
            operatorCheck();
            break;
        case '<':
            c = nextChar();
            if (c == '=') {
                op = ColumnFilter.OP_LE;
            } else {
                mPos--;
                op = ColumnFilter.OP_LT;
            }
            operatorCheck();
            break;
        case '>':
            c = nextChar();
            if (c == '=') {
                op = ColumnFilter.OP_GE;
            } else {
                mPos--;
                op = ColumnFilter.OP_GT;
            }
            operatorCheck();
            break;
        case 'i':
            c = nextChar();
            if (c == 'n') {
                op = ColumnFilter.OP_IN;
            } else {
                mPos--;
                throw error("Unknown operator");
            }
            operatorCheck(true);
            break;
        case '?':
            mPos--;
            throw error("Relational operator missing");
        case -1:
            throw error("Relational operator expected");
        default:
            mPos--;
            throw error("Unknown operator");
        }

        c = nextCharIgnoreWhitespace();

        int arg;
        if (c == '?') {
            arg = tryParseArgNumber();
            if (arg < 0) {
                arg = mNextArg++;
            }
        } else {
            mPos--;
            if (op >= ColumnFilter.OP_IN) {
                throw error("Argument number or '?' expected");
            }
            return new ColumnToColumnFilter(column, op, parseColumn());
        }

        if (op < ColumnFilter.OP_IN) {
            return new ColumnToArgFilter(column, op, arg);
        } else {
            return new InFilter(column, arg);
        }
    }

    // Left paren has already been consumed.
    private RowFilter parseParenFilter() {
        RowFilter filter = parseFilter();
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
        case '?':
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

    private ColumnInfo parseColumn() {
        int start = mPos;
        int c = nextChar();
        if (c < 0) {
            throw error("Column name expected");
        }
        if (!Character.isJavaIdentifierStart(c)) {
            mPos--;
            throw error("Not a valid character for start of column name: '" + (char) c + '\'');
        }
        do {
            c = nextChar();
        } while (Character.isJavaIdentifierPart(c));

        String name = mFilter.substring(start, --mPos);
        ColumnInfo column = mAllColumns.get(name);

        if (column == null) {
            throw error("Unknown column: " + name);
        }

        return column;
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
            if (c == ')' || Character.isWhitespace(c)) {
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

        return (int) arg;
    }

    /**
     * Returns -1 if no more characters.
     */
    private int nextChar() {
        String filter = mFilter;
        int pos = mPos;
        int c = (pos >= filter.length()) ? -1 : mFilter.charAt(pos);
        mPos = pos + 1;
        return c;
    }

    private int nextCharIgnoreWhitespace() {
        int c;
        while ((c = nextChar()) >= 0) {
            switch (c) {
            case ' ': case '\r': case '\n': case '\t': case '\0':
                break;
            default:
                if (Character.isWhitespace(c)) {
                    break;
                }
                return c;
            }
        }
        return c;
    }

    private IllegalArgumentException error(String message) {
        return error(message, mPos);
    }

    private IllegalArgumentException error(String message, int pos) {
        if (pos <= 0 || mFilter.length() == 0) {
            message += " (at start of filter expession)";
        } else if (pos >= mFilter.length()) {
            message += " (at end of filter expression)";
        } else {
            // Show the next 20 characters, or show 17 + ellipsis if more than 20.
            int remaining = mFilter.length() - pos;
            if (remaining <= 20) {
                message = message + " (at \"" + mFilter.substring(pos) + "\")";
            } else {
                message = message + " (at \"" + mFilter.substring(pos, pos + 17) + "...\")";
            }
        }
        // TODO: More specific exception type?
        return new IllegalArgumentException(message);
    }
}