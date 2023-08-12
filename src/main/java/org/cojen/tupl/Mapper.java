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

import java.io.IOException;

/**
 * Interface for mapping source rows to target rows. Inverse mapping is optional, but it's
 * necessary for supporting modifiable views, and it's also used by the query optimizer.
 *
 * <p>For supporting inverse mapping, define a public static method for each target column
 * which can be mapped to a source column. The method naming pattern must be {@code
 * <target_name>_to_<source_name>}, and it must be a function. The parameter type must exactly
 * match the target column type, and the return type must exactly match the source column type.
 *
 * <p>If this {@code Mapper} mostly performs identity mappings, extend the {@link Identity}
 * interface in order for inverse mapping functions to be automatically defined. These serve as
 * defaults only and can be overridden with explicit functions.
 *
 * @author Brian S O'Neill
 * @see Table#map Table.map
 */
@FunctionalInterface
public interface Mapper<R, T> {
    // TODO: Define a method which returns the set of source columns that the map method
    // depends on, to help optimize query projection. By default, all columns are required.

    // TODO: Define a method which indicates whether or not the map method performs any
    // filtering, which is true by default. When false, it can help query sorting. In
    // particular, partial sorting.

    /**
     * Maps source rows to target rows.
     *
     * @param source never null
     * @param target never null; all columns are initially unset
     * @return null if filtered out
     */
    T map(R source, T target) throws IOException;

    /**
     * Defines a {@link Mapper} which automatically defines inverse mapping functions.
     */
    public interface Identity<R, T> extends Mapper<R, T> {
    }
}
