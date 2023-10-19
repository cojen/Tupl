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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.util.Map;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;

import org.cojen.tupl.Mapper;
import org.cojen.tupl.Table;
import org.cojen.tupl.ViewConstraintException;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.io.Utils;

import org.cojen.tupl.rows.RowGen;

/**
 * Defines a SelectNode which relies on a Mapper to perform custom transformations and
 * filtering. A call to {@link SelectNode#make} determines if a SelectMappedNode is required.
 *
 * @author Brian S. O'Neill
 */
public final class SelectMappedNode extends SelectNode {
    /**
     * @see SelectNode#make
     */
    SelectMappedNode(TupleType type, String name,
                     RelationNode from, Node where, Node[] projection)
    {
        super(type, name, from, where, projection);
    }

    /* FIXME

       - The Mapper evaluates the "where" filter and returns null if it yields false. Any
         ColumnNodes which were projected (and have inverses) cannot be skipped because
         MappedTable doesn't always apply an appropriate filter against the source. The
         QueryPlan node should show the additional filtering which is applied.

       - The above comment might be wrong with respect to column skipping. Need to check the
         implementation of the MappedTable with respect to load, etc. If columns can be removed
         from the filter, it needs to obey the rules of RowFilter.split. It might be related to
         full inverse mapping of the primary key. If the primary key is fully (inverse) mapped,
         then CRUD operations work. It's the store operation that must always check the full
         filter.

       - If the primary key is fully mapped, CRUD operations work, and so the Mapper must apply
         the full filter. Only when CRUD operations don't work can the Mapper use a split
         remainder filter.
    */

    @Override
    protected Query<?> doMakeQuery() {
        Query fromQuery = mFrom.makeQuery();

        int argCount = highestParamOrdinal();
        MapperFactory factory = makeMapper(argCount);

        Class targetClass = type().tupleType().clazz();

        return new Query.Wrapped(fromQuery, argCount) {
            @Override
            @SuppressWarnings("unchecked")
            public Table asTable(Object... args) {
                checkArgumentCount(args);
                return mFromQuery.asTable(args).map(targetClass, factory.get(args));
            }
        };
    }

    public static interface MapperFactory {
        /**
         * Returns a new or singleton Mapper instance.
         */
        Mapper<?, ?> get(Object[] args);
    }

    // FIXME: I might want to cache these things. Use a string key?

    private MapperFactory makeMapper(int argCount) {
        Class<?> targetClass = type().tupleType().clazz();

        ClassMaker cm = RowGen.beginClassMaker
            (SelectMappedNode.class, targetClass, targetClass.getName(), null, "mapper")
            .implement(Mapper.class).implement(MapperFactory.class).final_();

        cm.addConstructor().private_();

        // The Mapper is also its own MapperFactory.
        {
            MethodMaker mm = cm.addMethod(Mapper.class, "get", Object[].class).public_();

            if (argCount == 0) {
                // Just return a singleton.
                mm.return_(mm.this_());
            } else {
                cm.addField(Object[].class, "args").private_().final_();

                MethodMaker ctor = cm.addConstructor(Object[].class).private_();
                ctor.invokeSuperConstructor();
                ctor.field("args").set(ctor.param(0));

                mm.return_(mm.new_(cm, mm.param(0)));
            }
        }

        MakerContext context = addMapMethod(cm, argCount);

        Map<String, ColumnNode> fromColumns = context.fromColumns(mFrom);
        addSourceProjectionMethod(cm, fromColumns);

        addInverseMappingFunctions(cm);

        addToStringMethod(cm);
        addPlanMethod(cm);

        // FIXME: These only need to be added if CRUD operations work.
        if (false) {
            addCheckStoreMethod(cm);
            addCheckUpdateMethod(cm);
            addCheckDeleteMethod(cm);
        }

        MethodHandles.Lookup lookup = cm.finishHidden();
        Class<?> clazz = lookup.lookupClass();

        try {
            MethodHandle mh = lookup.findConstructor(clazz, MethodType.methodType(void.class));
            return (MapperFactory) mh.invoke();
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
    }

    private MakerContext addMapMethod(ClassMaker cm, int argCount) {
        TupleType targetType = type().tupleType();

        MethodMaker mm = cm.addMethod
            (targetType.clazz(), "map", Object.class, Object.class).public_();

        // TODO: If source projection is empty, no need to cast the sourceRow. It won't be used.
        Class<?> sourceClass = mFrom.type().tupleType().clazz();
        var sourceRow = mm.param(0).cast(sourceClass);

        var targetRow = mm.param(1).cast(targetType.clazz());

        var argsVar = argCount == 0 ? null : mm.field("args").get();
        var context = new MakerContext(argsVar, sourceRow);

        if (mWhere != null) {
            Label pass = mm.label();
            Label fail = mm.label();

            mWhere.makeFilter(context, pass, fail);

            fail.here();
            mm.return_(null);
            pass.here();
        }

        int numColumns = targetType.numColumns();

        for (int i=0; i<numColumns; i++) {
            targetRow.invoke(targetType.field(i), mProjection[i].makeEval(context));
        }

        mm.return_(targetRow);

        // Now implement the bridge method.
        mm = cm.addMethod(Object.class, "map", Object.class, Object.class).public_().bridge();
        mm.return_(mm.this_().invoke(targetType.clazz(), "map", null, mm.param(0), mm.param(1)));

        return context;
    }

    private void addSourceProjectionMethod(ClassMaker cm, Map<String, ColumnNode> fromColumns) {
        int numColumns = fromColumns.size();
        int maxColumns = mFrom.type().tupleType().numColumns();

        if (numColumns == maxColumns) {
            // The default implementation indicates that all source columns are projected.
            return;
        }

        if (numColumns > maxColumns) {
            throw new AssertionError();
        }

        MethodMaker mm = cm.addMethod(String.class, "sourceProjection").public_();

        if (numColumns == 0) {
            mm.return_("");
            return;
        }

        // The sourceProjection string isn't used as a cache key, so it can just be constructed
        // as needed rather than stashing a reference to a big string instance.

        Object[] toConcat = new String[numColumns + numColumns - 1];

        int i = 0;
        for (String name : fromColumns.keySet()) {
            if (i > 0) {
                toConcat[i++] = ", ";
            }
            toConcat[i++] = name;
        }

        mm.return_(mm.concat(toConcat));
    }

    private void addInverseMappingFunctions(ClassMaker cm) {
        TupleType targetType = type().tupleType();
        int numColumns = targetType.numColumns();

        for (int i=0; i<numColumns; i++) {
            if (!(mProjection[i] instanceof ColumnNode source) || source.from() != mFrom) {
                continue;
            }

            Class columnType = targetType.column(i).type().clazz();
            if (columnType != source.type().clazz()) {
                continue;
            }

            String methodName = targetType.field(i) + "_to_" + source.column().name();
            MethodMaker mm = cm.addMethod(columnType, methodName, columnType).public_().static_();
            mm.return_(mm.param(0));
        }
    }

    private void addToStringMethod(ClassMaker cm) {
        MethodMaker mm = cm.addMethod(String.class, "toString").public_();
        mm.return_(mm.class_().invoke("getName"));
    }

    private void addPlanMethod(ClassMaker cm) {
        if (mWhere == null) {
            return;
        }
        String filterExpr = mWhere.name();
        MethodMaker mm = cm.addMethod(QueryPlan.class, "plan", QueryPlan.Mapper.class).public_();
        mm.return_(mm.new_(QueryPlan.Filter.class, filterExpr, mm.param(0)));
    }

    // FIXME: Finish the check methods, which just applies the filter. Columns that don't need
    // to be checked effectively evaluate to true. For checkUpdate, any columns not in the
    // primary key must also call the isSet method. If it returns false, then the column check
    // passes. If true, must check the column value.

    private void addCheckStoreMethod(ClassMaker cm) {
        MethodMaker mm = cm.addMethod(null, "checkStore", Table.class, Object.class).public_();

        if (mWhere == null) {
            // Nothing to check.
            return;
        }

        // FIXME: checkStore
        mm.new_(ViewConstraintException.class).throw_();
    }

    private void addCheckUpdateMethod(ClassMaker cm) {
        MethodMaker mm = cm.addMethod(null, "checkUpdate", Table.class, Object.class).public_();

        if (mWhere == null) {
            // Nothing to check.
            return;
        }

        // FIXME: checkUpdate
        mm.new_(ViewConstraintException.class).throw_();
    }

    private void addCheckDeleteMethod(ClassMaker cm) {
        MethodMaker mm = cm.addMethod(null, "checkDelete", Table.class, Object.class).public_();

        if (mWhere == null) {
            // Nothing to check.
            return;
        }

        // FIXME: checkDelete
        mm.new_(ViewConstraintException.class).throw_();
    }
}
