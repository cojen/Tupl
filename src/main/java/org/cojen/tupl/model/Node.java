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

package org.cojen.tupl.model;

import java.util.Map;
import java.util.Set;

import org.cojen.maker.Label;
import org.cojen.maker.Variable;

import org.cojen.tupl.rows.RowInfo;

import org.cojen.tupl.rows.filter.OpaqueFilter;
import org.cojen.tupl.rows.filter.RowFilter;

/**
 * A node represents an AST element of a query.
 *
 * @author Brian S. O'Neill
 */
public abstract class Node {
    public abstract Type type();

    /**
     * @return this or a replacement node
     */
    public abstract Node asType(Type type);

    /**
     * Returns a non-null name, which doesn't affect the functionality of this node.
     *
     * @see #equals
     */
    public abstract String name();

    /**
     * Returns the highest query argument needed by this node, which is zero if none are
     * needed.
     */
    public abstract int maxArgument();

    /**
     * Returns true if this node represents a pure function with respect to the current row,
     * returning the same result upon each invocation.
     */
    public abstract boolean isPureFunction();

    /**
     * Performs a best effort conversion of this node into a RowFilter. And nodes which cannot be
     * converted are represented by OpaqueFilters which have the node attached.
     *
     * @param columns all converted columns are put into this map
     */
    public RowFilter toRowFilter(RowInfo info, Map<String, ColumnNode> columns) {
        return new OpaqueFilter(false, this);
    }

    /**
     * Adds into the given set the fully qualified names of all the columns that makeEval will
     * directly use.
     */
    public abstract void evalColumns(Set<String> columns);

    /**
     * Generates code which evaluates an expression. The context tracks nodes which have
     * already been evaluated and is updated by this method.
     */
    public abstract Variable makeEval(EvalContext context);

    /**
     * Generates code which evaluates an expression for branching to a pass or fail label.
     * Short-circuit logic is used, and so the expression might only be partially evaluated.
     *
     * @throws IllegalStateException if unsupported
     */
    public void makeFilter(EvalContext context, Label pass, Label fail) {
        makeEval(context).ifTrue(pass);
        fail.goto_();
    }

    /**
     * Generates filter code which returns a boolean Variable.
     *
     * @throws IllegalStateException if unsupported
     */
    public Variable makeFilterEval(EvalContext context) {
        Variable result = makeEval(context);
        if (result.classType() != boolean.class) {
            throw new IllegalStateException();
        }
        return result;
    }

    /**
     * Generates filter code for the SelectMappedNode remap method. It performs eager
     * evaluation and suppresses exceptions when possible.
     *
     * @return an assigned Object variable which references a Boolean or a RuntimeException
     */
    public Variable makeFilterEvalRemap(EvalContext context) {
        return context.methodMaker().var(Boolean.class).set(makeFilterEval(context));
    }

    /**
     * Returns true if the code generated by this node might throw a RuntimeException. A return
     * value of false doesn't indicate that a RuntimeException isn't possible, but instead
     * it indicates that it's unlikely.
     */
    public boolean canThrowRuntimeException() {
        return true;
    }

    /**
     * Returns true if the filtering code generated by this node can throw a RuntimeException
     * which might be suppressed if the evaluation order was changed. This implies that the
     * node performs short-circuit logic and that canThrowRuntimeException returns true.
     */
    public boolean hasOrderDependentException() {
        return false;
    }

    /**
     * @see #equals
     */
    @Override
    public abstract int hashCode();

    /**
     * The equals and hashCode methods only compare for equivalent functionality, and thus the
     * node's name is generally excluded from the comparison.
     */
    @Override
    public abstract boolean equals(Object obj);

    @Override
    public String toString() {
        return name();
    }
}
