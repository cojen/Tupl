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

import org.cojen.maker.Label;
import org.cojen.maker.Variable;

import org.cojen.tupl.rows.ConvertCallSite;

/**
 * Defines a parameter which is passed into a query at runtime.
 *
 * @author Brian S. O'Neill
 */
public final class ParamNode extends Node {
    /**
     * @param name can be null to automatically assign a name
     */
    public static ParamNode make(String name, int ordinal) {
        return new ParamNode(name, BasicType.OBJECT, ordinal);
    }

    private String mName;
    private final Type mType;
    private final int mOrdinal;

    private ParamNode(String name, Type type, int ordinal) {
        mName = name;
        mType = type;
        mOrdinal = ordinal;
    }

    @Override
    public Type type() {
        return mType;
    }

    @Override
    public Node asType(Type type) {
        if (mType.equals(type)) {
            return this;
        }
        return new ParamNode(mName, type, mOrdinal);
    }

    @Override
    public String name() {
        if (mName == null) {
            mName = "?" + mOrdinal;
        }
        return mName;
    }

    @Override
    public int maxArgument() {
        return mOrdinal;
    }

    @Override
    public boolean isPureFunction() {
        return true;
    }

    @Override
    public Variable makeEval(EvalContext context) {
        var resultRef = context.refFor(this);
        var result = resultRef.get();
        if (result != null) {
            return result;
        } else {
            var value = context.argsVar.aget(mOrdinal - 1);
            if (mType != BasicType.OBJECT) {
                value = ConvertCallSite.make(context.methodMaker(), mType.clazz(), value);
            }
            return resultRef.set(value);
        }
    }

    @Override
    public void makeFilter(EvalContext context, Label pass, Label fail) {
        ConvertCallSite.make(context.methodMaker(), boolean.class, makeEval(context)).ifTrue(pass);
        fail.goto_();
    }

    public int ordinal() {
        return mOrdinal;
    }

    @Override
    public int hashCode() {
        return mOrdinal * 970840757;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ParamNode pn && mOrdinal == pn.mOrdinal;
    }
}
