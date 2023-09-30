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

import java.lang.invoke.MethodHandle;

import java.util.function.Predicate;

import org.cojen.tupl.core.Pair;

import org.cojen.tupl.rows.filter.FalseFilter;
import org.cojen.tupl.rows.filter.Parser;
import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.TrueFilter;

/**
 * Generates plain Predicate instances which check that columns are set.
 *
 * @see Table#predicate
 * @see RowPredicateMaker
 * @author Brian S O'Neill
 */
public final class PlainPredicateMaker {
    private static final WeakCache<Pair<Class<?>, String>, MethodHandle, RowFilter> cCache;

    static {
        cCache = new WeakCache<>() {
            @Override
            public MethodHandle newValue(Pair<Class<?>, String> key, RowFilter filter) {
                Class<?> rowType = key.a();
                String query = key.b();
                RowInfo info = RowInfo.find(rowType);
                if (filter == null) {
                    filter = new Parser(info.allColumns, query).parseQuery(null).filter();
                }
                String filterStr = filter.toString();
                if (filterStr.equals(query)
                    || filter == TrueFilter.THE || filter == FalseFilter.THE)
                {
                    var maker = new RowPredicateMaker(rowType, info.rowGen(), filter, filterStr);
                    return maker.finishPlain();
                } else {
                    return obtain(new Pair<>(rowType, filterStr), null);
                }
            }
        };
    }

    /**
     * Returns a new predicate instance.
     */
    public static <R> Predicate<R> predicate(Class<R> rowType, String query, Object... args) {
        try {
            return (Predicate<R>) predicateHandle(rowType, query).invokeExact(args);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    /**
     * Returns a new predicate instance.
     */
    public static <R> Predicate<R> predicate(Class<R> rowType, RowFilter filter, Object... args) {
        try {
            return (Predicate<R>) predicateHandle(rowType, filter).invokeExact(args);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    /**
     * MethodHandle signature: Predicate xxx(Object... args)
     */
    static MethodHandle predicateHandle(Class<?> rowType, String query) {
        return cCache.obtain(new Pair<>(rowType, query), null);
    }

    /**
     * MethodHandle signature: Predicate xxx(Object... args)
     */
    static MethodHandle predicateHandle(Class<?> rowType, RowFilter filter) {
        return cCache.obtain(new Pair<>(rowType, filter.toString()), filter);
    }
}
