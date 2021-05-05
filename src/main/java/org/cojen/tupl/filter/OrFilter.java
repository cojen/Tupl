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

import java.util.Arrays;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class OrFilter extends GroupFilter {
    OrFilter(RowFilter... subFilters) {
        super(~Arrays.hashCode(subFilters), subFilters);
    }

    @Override
    public void accept(Visitor visitor) {
        visitor.visit(this);
    }

    @Override
    public AndFilter not() {
        return new AndFilter(subNot());
    }

    @Override
    char opChar() {
        return '|';
    }
}
