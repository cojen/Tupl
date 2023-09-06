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

package org.cojen.tupl;

import java.io.Closeable;
import java.io.IOException;

import org.cojen.tupl.diag.QueryPlan;

/**
 * Interface which processes groups of rows into aggregate results.
 *
 * @author Brian S. O'Neill
 * @see Table#group Table.group
 */
public interface Grouper<R, T> extends Closeable {
    /**
     * Called for the first source row in the group.
     *
     * @param source never null
     * @return the next source row instance to use, or null if it was kept by the grouper
     */
    R begin(R source) throws IOException;

    /**
     * Called for each source row in the group, other than the first one.
     *
     * @param source never null
     * @return the next source row instance to use, or null if it was kept by the grouper
     */
    R accumulate(R source) throws IOException;

    /**
     * Called produce an aggregate result for the current group. This method is expected to
     * assign the appropriate target column values, except for the primary key. The primary key
     * columns are assigned automatically by the caller.
     *
     * @param target never null; all columns are initially unset
     * @return null if filtered out
     */
    T finish(T target) throws IOException;

    /**
     * Returns a comma-separated list of source columns which are needed by this {@code
     * Grouper}. Null is returned by default, which indicates that all columns are needed.
     */
    default String sourceProjection() {
        return null;
    }

    /**
     * Is called when this {@code Grouper} instance is no longer needed.
     */
    @Override
    default void close() throws IOException {
    }

    /**
     * Override this method to customize the grouper's query plan.
     *
     * @param plan original plan
     * @return original or replacement plan
     */
    default QueryPlan plan(QueryPlan.Grouper plan) {
        return plan;
    }
}
