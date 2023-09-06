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

package org.cojen.tupl.rows.join;

import java.io.IOException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.MethodMaker;

import org.cojen.tupl.Database;
import org.cojen.tupl.Table;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.RowGen;
import org.cojen.tupl.rows.RowInfo;
import org.cojen.tupl.rows.RowUtils;
import org.cojen.tupl.rows.WeakClassCache;

import org.cojen.tupl.util.Canonicalizer;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class JoinTableMaker {
    private static final WeakClassCache<Class<?>> cClassCache;
    private static final Canonicalizer cInstanceCache;

    static {
        cClassCache = new WeakClassCache<>() {
            @Override
            protected Class<?> newValue(Class<?> joinType, Object unused) {
                return new JoinTableMaker(joinType).finish();
            }
        };

        cInstanceCache = new Canonicalizer();
    }

    /**
     * @see Table#join
     */
    public static <J> JoinTable<J> join(Class<J> joinType, String specStr, Table<?>... tables) {
        JoinSpec spec = JoinSpec.parse(RowInfo.find(joinType), specStr, tables);
        return join(joinType, spec);
    }

    /**
     * @see Database#openJoinTable
     */
    public static <J> JoinTable<J> join(Class<J> joinType, String specStr, Database db)
        throws IOException
    {
        RowInfo joinInfo = RowInfo.find(joinType);
        JoinSpec spec = JoinSpec.parse(joinInfo, specStr, db);
        return join(joinType, spec);
    }

    static <J> JoinTable<J> join(Class<J> joinType, JoinSpec spec) {
        Class<?> clazz = findClass(joinType);

        spec = spec.toLeftJoin();

        var tables = new Table[spec.numSources()];

        spec.root().accept(new JoinSpec.Visitor() {
            int mPos;

            @Override
            public JoinSpec.Node visit(JoinSpec.Column node) {
                tables[mPos++] = node.table();
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.FullJoin node) {
                tables[mPos++] = join(joinType, node.toSpec());
                return node;
            }
        });

        String specStr = spec.toString();

        MethodType mt = MethodType.methodType
            (void.class, String.class, JoinSpec.class, Table[].class);

        JoinTable<J> table;
        try {
            table = (JoinTable<J>) MethodHandles.lookup()
                .findConstructor(clazz, mt).invoke(specStr, spec, tables);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }

        return cInstanceCache.apply(table);
    }

    /**
     * Returns a class which is constructed with:
     *
     *   (String specStr, JoinSpec spec, Table... tables)
     */
    private static Class<?> findClass(Class<?> joinType) {
        return cClassCache.obtain(joinType, null);
    }

    private final Class<?> mJoinType;
    private final Class<?> mJoinClass;
    private final RowInfo mJoinInfo;
    private final ClassMaker mClassMaker;

    private JoinTableMaker(Class<?> joinType) {
        mJoinType = joinType;
        mJoinClass = JoinRowMaker.find(joinType);
        mJoinInfo = RowInfo.find(joinType);

        mClassMaker = RowGen.beginClassMaker
            (JoinTableMaker.class, joinType, mJoinInfo.name, null, "table")
            .extend(JoinTable.class).public_();
    }

    private Class<?> finish() {
        addConstructor();

        // Add the simple rowType method.
        mClassMaker.addMethod(Class.class, "rowType").public_().return_(mJoinType);

        // Add the newRow method and its bridge.
        {
            MethodMaker mm = mClassMaker.addMethod(mJoinType, "newRow").public_();
            mm.return_(mm.new_(mJoinClass));
            mm = mClassMaker.addMethod(Object.class, "newRow").public_().bridge();
            mm.return_(mm.this_().invoke(mJoinType, "newRow", null));
        }

        // Add the cloneRow method and its bridge.
        {
            MethodMaker mm = mClassMaker.addMethod(mJoinType, "cloneRow", Object.class).public_();
            mm.return_(mm.param(0).cast(mJoinClass).invoke("clone"));
            mm = mClassMaker.addMethod(Object.class, "cloneRow", Object.class).public_().bridge();
            mm.return_(mm.this_().invoke(mJoinType, "cloneRow", null, mm.param(0)));
        }

        // Add the unsetRow method.
        {
            MethodMaker mm = mClassMaker.addMethod(null, "unsetRow", Object.class).public_();
            var rowVar = mm.param(0).cast(mJoinType);
            for (ColumnInfo info : mJoinInfo.allColumns.values()) {
                rowVar.invoke(info.name, (Object) null);
            }
        }

        // Add the copyRow method.
        {
            MethodMaker mm = mClassMaker.addMethod
                (null, "copyRow", Object.class, Object.class).public_();
            var srcRowVar = mm.param(0).cast(mJoinType);
            var dstRowVar = mm.param(1).cast(mJoinType);
            for (ColumnInfo info : mJoinInfo.allColumns.values()) {
                dstRowVar.invoke(info.name, srcRowVar.invoke(info.name));
            }
        }

        return mClassMaker.finish();
    }

    private void addConstructor() {
        MethodMaker mm = mClassMaker.addConstructor(String.class, JoinSpec.class, Table[].class);
        mm.varargs().public_();

        var specStrVar = mm.param(0);
        var specVar = mm.param(1);
        var tablesVar = mm.param(2);

        mm.invokeSuperConstructor(specStrVar, specVar, tablesVar);
    }
}
