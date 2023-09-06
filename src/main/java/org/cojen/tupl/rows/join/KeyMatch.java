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

package org.cojen.tupl.rows.join;

import java.util.Map;
import java.util.Set;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.ColumnSet;
import org.cojen.tupl.rows.RowInfo;

import org.cojen.tupl.rows.filter.AndFilter;
import org.cojen.tupl.rows.filter.ColumnFilter;
import org.cojen.tupl.rows.filter.ColumnToArgFilter;
import org.cojen.tupl.rows.filter.ColumnToColumnFilter;
import org.cojen.tupl.rows.filter.OrFilter;
import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.Visitor;

import static org.cojen.tupl.rows.filter.ColumnFilter.*;

/**
 * Determines if a filter exactly specifies all columns of a key. Instances aren't thread-safe.
 *
 * @author Brian S O'Neill
 * @see JoinSpec
 */
abstract class KeyMatch implements Visitor {
    /**
     * @param prefix table column name with a '.' character at the end
     */
    public static KeyMatch build(String prefix, RowInfo info) {
        KeyMatch pk = newInstance(prefix, info.keyColumns); // primary key match

        Set<ColumnSet> alternateKeys = info.alternateKeys;
        if (alternateKeys != null && !alternateKeys.isEmpty()) {
            KeyMatch prev = pk;
            for (ColumnSet key : alternateKeys) {
                KeyMatch ak = newInstance(prefix, key.keyColumns); // alternate key match
                prev.mNext = ak;
                prev = ak;
            }
        }

        return pk;
    }

    private static KeyMatch newInstance(String prefix, Map<String, ColumnInfo> keyColumns) {
        int size = keyColumns.size();
        if (size == 1) {
            return new OneKey(prefix + keyColumns.keySet().iterator().next());
        } else if (size == 0) {
            return new NoKey();
        } else {
            // FIXME: If more than 64 (unlikely), define MegaKey which uses a BitSet.
            return new MultiKey(prefix, keyColumns);
        }
    }

    // The first in the chain is for the primary key, and the rest are for alternate keys.
    private KeyMatch mNext;

    protected boolean mMatch;

    /**
     * Calculate a score by examining the filter columns which start with the prefix given to
     * the build method.
     *
     * @return 2 if filter matches on a primary key, 1 if it matches on an alternate key, or 0
     * if no match
     */
    public final int score(RowFilter filter) {
        KeyMatch km = this;
        km.mMatch = false;
        filter.accept(km);
        if (km.mMatch) {
            return 2;
        }
        while ((km = km.mNext) != null) {
            km.mMatch = false;
            filter.accept(km);
            if (km.mMatch) {
                return 1;
            }
        }
        return 0;
    }

    @Override
    public final void visit(OrFilter filter) {
        for (RowFilter sub : filter.subFilters()) {
            sub.accept(this);
            if (!mMatch) {
                return;
            }
        }
    }

    @Override
    public abstract void visit(AndFilter filter);

    @Override
    public abstract void visit(ColumnToArgFilter filter);

    @Override
    public abstract void visit(ColumnToColumnFilter filter);

    public boolean hasPkColumns() {
        return true;
    }

    /**
     * KeyMatch for a primary or alternate key which consists of no columns. Isn't generally
     * applicable to ordinary row types, because they always require a primary key with at
     * least one column.
     */
    private static final class NoKey extends KeyMatch {
        NoKey() {
        }

        @Override
        public void visit(AndFilter filter) {
        }

        @Override
        public void visit(ColumnToArgFilter filter) {
        }

        @Override
        public void visit(ColumnToColumnFilter filter) {
        }

        @Override
        public boolean hasPkColumns() {
            return false;
        }
    }

    /**
     * KeyMatch for a primary or alternate key which consists of one column.
     */
    private static final class OneKey extends KeyMatch {
        private final String mColumnName;

        OneKey(String columnName) {
            mColumnName = columnName;
        }

        @Override
        public void visit(AndFilter filter) {
            for (RowFilter sub : filter.subFilters()) {
                sub.accept(this);
                if (mMatch) {
                    return;
                }
            }
        }

        @Override
        public void visit(ColumnToArgFilter filter) {
            mMatch = filter.operator() == OP_EQ && filter.column().name.equals(mColumnName);
        }

        @Override
        public void visit(ColumnToColumnFilter filter) {
            mMatch = filter.operator() == OP_EQ
                && (filter.column().name.equals(mColumnName)
                    || filter.otherColumn().name.equals(mColumnName));
        }
    }

    /**
     * KeyMatch for a primary or alternate key which consists of up to 64 columns.
     */
    private static final class MultiKey extends KeyMatch {
        private final Entry[] mEntries;
        private final long mFullMatch;

        MultiKey(String prefix, Map<String, ColumnInfo> keyColumns) {
            var entries = new Entry[keyColumns.size() << 1];

            long fullMatch = 0;
            long matchValue = 1;

            for (String name : keyColumns.keySet()) {
                name = prefix + name;
                int hash = name.hashCode();
                int index = hash & (entries.length - 1);
                entries[index] = new Entry(name, hash, matchValue, entries[index]);
                fullMatch |= matchValue;
                matchValue <<= 1;
            }

            mEntries = entries;
            mFullMatch = fullMatch;
        }

        @Override
        public void visit(AndFilter filter) {
            long matches = 0;

            for (RowFilter sub : filter.subFilters()) {
                if (sub instanceof ColumnFilter term) {
                    if (term.operator() == OP_EQ) {
                        matches |= find(term.column().name);
                        if (term instanceof ColumnToColumnFilter c2c) {
                            matches |= find(c2c.otherColumn().name);
                        }
                    }
                } else {
                    sub.accept(this);
                    if (mMatch) {
                        return;
                    }
                }
            }

            mMatch = matches == mFullMatch;
        }

        @Override
        public void visit(ColumnToArgFilter filter) {
            mMatch = mFullMatch == 1 && filter.operator() == OP_EQ
                && find(filter.column().name) == 1;
        }

        @Override
        public void visit(ColumnToColumnFilter filter) {
            mMatch = mFullMatch == 1 && filter.operator() == OP_EQ
                && (find(filter.column().name) == 1 || find(filter.otherColumn().name) == 1);
        }

        private long find(String name) {
            Entry[] entries = mEntries;
            for (var e = entries[name.hashCode() % entries.length]; e != null; e = e.mNext) {
                if (e.mName.equals(name)) {
                    return e.mValue;
                }
            }
            return 0;
        }

        private static class Entry {
            final String mName;
            final int mHash;
            final long mValue;
            final Entry mNext;

            Entry(String columnName, int hash, long value, Entry next) {
                mName = columnName;
                mHash = hash;
                mValue = value;
                mNext = next;
            }
        }
    }
}
