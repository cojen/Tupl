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

package org.cojen.tupl.rows;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ColumnSet {
    // Map order matches declaration order and excludes value columns.
    public Map<String, ColumnInfo> keyColumns;

    // Map is ordered lexicographically by name and excludes key columns.
    public NavigableMap<String, ColumnInfo> valueColumns;

    // Map is ordered lexicographically by name.
    public NavigableMap<String, ColumnInfo> allColumns;

    /**
     * Compares all columns for equality based on their natural order.
     */
    final boolean matches(ColumnSet other) {
        return matches(allColumns, other.allColumns) &&
            matches(valueColumns, other.valueColumns) && matches(keyColumns, other.keyColumns);
    }

    /**
     * Compares maps for equality based on their natural order.
     */
    private static <K, V> boolean matches(Map<K, V> a, Map<K, V> b) {
        if (a.size() == b.size()) {
            var ai = a.entrySet().iterator();
            var bi = b.entrySet().iterator();
            while (ai.hasNext()) {
                if (!ai.next().equals(bi.next())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Returns each key prefixed with a '+' or '-' character.
     */
    String[] keySpec() {
        return fullSpec(keyColumns.values(), Collections.emptySet());
    }

    /**
     * Returns each key prefixed with a '+' or '-' character, followed by unprefixed value
     * columns.
     */
    String[] fullSpec() {
        return fullSpec(keyColumns.values(), valueColumns.values());
    }

    private static String[] fullSpec(Collection<ColumnInfo> keys, Collection<ColumnInfo> values) {
        var spec = new String[keys.size() + values.size()];
        int i = 0;

        for (ColumnInfo column : keys) {
            String name = column.name;
            name = (column.isDescending() ? '-' : '+') + name;
            spec[i++] = name;
        }

        for (ColumnInfo column : values) {
            spec[i++] = column.name;
        }

        return spec;
    }

    /**
     * Returns a compact index specification string.
     */
    String indexSpec() {
        return appendIndexSpec(new StringBuilder(keyColumns.size() << 1)).toString();
    }

    /**
     * Appends a compact index specification string.
     */
    StringBuilder appendIndexSpec(StringBuilder bob) {
        for (ColumnInfo ci : keyColumns.values()) {
            bob.append(ci.isDescending() ? '-' : '+');
            if (ci.isNullLow()) {
                bob.append('!');
            }
            bob.append(ci.name);
        }

        if (this instanceof SecondaryInfo info && !info.isAltKey() && !valueColumns.isEmpty()) {
            // Append covering index columns.
            bob.append('|');
            for (ColumnInfo ci : valueColumns.values()) {
                bob.append('~').append(ci.name);
            }
        }

        return bob;
    }

    @Override
    public String toString() {
        return keyColumns.values() + " -> " + valueColumns.values() +
            ", allColumns: " + allColumns.values();
    }
}
