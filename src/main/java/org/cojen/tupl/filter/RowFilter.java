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

import org.cojen.tupl.rows.ColumnInfo;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see Parser
 */
public abstract class RowFilter {
    private final int mHash;

    RowFilter(int hash) {
        mHash = hash;
    }

    public abstract void accept(Visitor visitor);

    public abstract int numTerms();

    /**
     * Apply partial or full reduction of the filter.
     */
    public abstract RowFilter reduce();

    /**
     * @return true if this filter is in disjunctive normal form
     */
    public abstract boolean isDnf();

    /**
     * Returns this filter in disjunctive normal form.
     *
     * @throws ComplexFilterException if cannot be quickly transformed
     */
    public abstract RowFilter dnf();

    /**
     * @return true if this filter is in conjunctive normal form
     */
    public abstract boolean isCnf();

    /**
     * Returns this filter in conjunctive normal form.
     *
     * @throws ComplexFilterException if cannot be quickly transformed
     */
    public abstract RowFilter cnf();

    /**
     * Checks if the given filter (or its inverse) matches this one. If the filter consists of
     * a commutative group of sub-filters, then exact order isn't required for a match.
     *
     * @return 0 if doesn't match, 1 if equal match, or -1 if inverse equally matches
     */
    public abstract int isMatch(RowFilter filter);

    /**
     * Checks if the given filter (or its inverse) matches this one, or if the given filter
     * matches against a sub-filter of this one.
     *
     * @return 0 if doesn't match, 1 if equal match, or -1 if inverse equally matches
     */
    public abstract int isSubMatch(RowFilter filter);

    /**
     * Returns a hash code for use with the isMatch method.
     */
    public abstract int matchHashCode();

    /**
     * Returns the inverse of this filter.
     */
    public abstract RowFilter not();

    public RowFilter or(RowFilter filter) {
        RowFilter[] subFilters = {this, filter};
        return OrFilter.flatten(subFilters, 0, subFilters.length);
    }

    public RowFilter and(RowFilter filter) {
        RowFilter[] subFilters = {this, filter};
        return AndFilter.flatten(subFilters, 0, subFilters.length);
    }

    /**
     * Given a set of columns corresponding to the primary key of an index, extract a suitable
     * range for performing an efficient index scan against this filter. For best results, this
     * method should be called on a conjunctive normal form filter.
     *
     * <p>If null is returned, then everything has been filtered out, and no scan is needed at
     * all. Otherwise, an array of three filters is returned:
     *
     * <ul>
     * <li>The remaining filter that must be applied, or null if none
     * <li>A range low filter, or null if open
     * <li>A range high filter, or null if open
     * </ul>
     *
     * If no optimization is possible, then the remaining filter is the same as this, and the
     * range filters are both null (open).
     *
     * <p>The range filters are composed of the key columns, in their original order. If
     * multiple key columns are used, they are combined as an 'and' filter. The number of terms
     * never exceeds the number of key columns provided.
     *
     * <p>The last operator of the low range is one of {==, >=, >}, and the last operator of
     * the high range is one of {==, <=, <}. All prior operators (if any) are always ==.
     * The last operator is == only if the key is fully specified.
     *
     * @param reverse pass true if scan is to be performed in reverse order; note that the
     * returned ranges are never swapped
     * @param keyColumns must provide at least one
     */
    public RowFilter[] rangeExtract(boolean reverse, ColumnInfo... keyColumns) {
        return new RowFilter[] {this, null, null};
    }

    /**
     * Given a set of columns corresponding to the primary key of an index, extract disjoint
     * ranges for performing an efficient index scan against this filter. For best
     * results, this method should be called on a disjunctive normal form filter.
     *
     * <p>For each range, a separate scan must be performed, and they can be stitched together
     * as one. The order of the ranges doesn't match the natural order of the index, and it
     * cannot be known until actual argument values are specified.
     *
     * <p>If null is returned, then everything has been filtered out, and no scan is needed at
     * all. Otherwise, an array of array is returned, where each range is described by the
     * {@see #rangeExtract} method.
     *
     * @param reverse pass true if scan is to be performed in reverse order; note that the
     * returned ranges are never swapped
     * @param keyColumns must provide at least one
     * @throws ComplexFilterException if cannot be quickly reduced; call rangeExtract instead
     */
    public RowFilter[][] multiRangeExtract(boolean reverse, ColumnInfo... keyColumns) {
        RowFilter[] range = rangeExtract(reverse, keyColumns);
        return range == null ? null : new RowFilter[][] {range};
    }

    @Override
    public final int hashCode() {
        return mHash;
    }

    @Override
    public final String toString() {
        var b = new StringBuilder();
        appendTo(b);
        return b.toString();
    }

    abstract void appendTo(StringBuilder b);
}
