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

package org.cojen.tupl.rows.filter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.OrderBy;

/**
 * Describes a fully parsed query specification.
 *
 * @param projection can be null if projection is all columns
 * @param orderBy can be null if none; all orderBy columns must also be in the projection
 * @param filter never null
 * @see Parser#parseQuery
 */
public record Query(Map<String, ColumnInfo> projection, OrderBy orderBy, RowFilter filter) {
    public Query withOrderBy(OrderBy ob) {
        if (Objects.equals(orderBy, ob)) {
            return this;
        }

        if (ob == null || projection == null || projection.keySet().containsAll(ob.keySet())) {
            return new Query(projection, ob, filter);
        }

        // Expand the projection to include the additional orderBy columns.

        var proj = new LinkedHashMap<>(projection);

        for (OrderBy.Rule rule : ob.values()) {
            ColumnInfo column = rule.column();
            proj.putIfAbsent(column.name, column);
        }

        return new Query(proj, ob, filter);
    }

    public Query withFilter(RowFilter rf) {
        return rf.equals(filter) ? this : new Query(projection, orderBy, rf);
    }

    public Query reduce() {
        return withFilter(filter.reduce());
    }

    /**
     * Returns the column order by rule, or null if not specified.
     */
    public OrderBy.Rule orderByRule(String name) {
        return orderBy == null ? null : orderBy.get(name);
    }

    /**
     * Returns true if effective query is "{*}".
     */
    public boolean isFullScan() {
        return projection == null && (orderBy == null || orderBy.isEmpty())
            && filter == TrueFilter.THE;
    }

    @Override
    public String toString() {
        Set<String> names;

        if (projection != null) {
            names = projection.keySet();
        } else if (orderBy != null) {
            names = orderBy.keySet();
        } else if (filter != TrueFilter.THE) {
            return filter.toString();
        } else {
            return "{*}";
        }

        var b = new StringBuilder().append('{');

        for (String name : names) {
            if (b.length() != 1) {
                b.append(',').append(' ');
            }
            OrderBy.Rule rule;
            if (orderBy != null && (rule = orderBy.get(name)) != null) {
                rule.appendTo(b);
            } else {
                b.append(name);
            }
        }

        if (projection == null) {
            b.append(", *");
        }

        b.append('}');

        if (filter != TrueFilter.THE) {
            b.append(' ');
            filter.appendTo(b);
        }

        return b.toString();
    }
}
