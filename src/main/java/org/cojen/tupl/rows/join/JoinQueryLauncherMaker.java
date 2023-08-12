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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.lang.ref.Reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.Scanner;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;

import org.cojen.tupl.core.Triple;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.DisjointUnionQueryLauncher;
import org.cojen.tupl.rows.QueryLauncher;
import org.cojen.tupl.rows.RowGen;
import org.cojen.tupl.rows.RowUtils;
import org.cojen.tupl.rows.RowWriter;
import org.cojen.tupl.rows.WeakCache;

import org.cojen.tupl.rows.filter.FalseFilter;
import org.cojen.tupl.rows.filter.Parser;
import org.cojen.tupl.rows.filter.Query;
import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.TrueFilter;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class JoinQueryLauncherMaker {
    private static final WeakCache<Triple<Class, String, String>, Class<?>, JoinTable<?>> cCache;

    static {
        cCache = new WeakCache<>() {
            @Override
            public Class<?> newValue(Triple<Class, String, String> key, JoinTable<?> table) {
                String queryStr = key.c();
                var maker = new JoinQueryLauncherMaker(table, queryStr);
                String canonical = maker.canonicalQuery();
                if (queryStr.equals(canonical)) {
                    return maker.finish();
                } else {
                    return obtain(new Triple<>(key.a(), key.b(), canonical), table);
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static <J> QueryLauncher<J> newInstance(JoinTable<J> table, String queryStr) {
        JoinSpec spec = table.joinSpec();
        JoinSpec[] split = spec.trySplitFullJoin();

        if (split != null) {
            Class<?> joinType = table.rowType();
            QueryLauncher[] launchers = {
                JoinTableMaker.join(joinType, split[0]).scannerQueryLauncher(queryStr),
                JoinTableMaker.join(joinType, split[1]).scannerQueryLauncher(queryStr)
            };
            return new DisjointUnionQueryLauncher<J>(launchers);
        }

        var key = new Triple<>((Class) table.rowType(), table.joinSpecString(), queryStr);
        Class<?> clazz = cCache.obtain(key, table);

        // Do this to prevent the table from discarding the reference to the JoinSpec early,
        // only to reparse it again. This isn't strictly necessary, however.
        Reference.reachabilityFence(spec);

        MethodType mt = MethodType.methodType(void.class, Table[].class);
        try {
            MethodHandle ctor = MethodHandles.lookup().findConstructor(clazz, mt);
            return (QueryLauncher<J>) ctor.invoke(table.joinTables());
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    // FIXME: Support projection and ordering.

    private final JoinSpec mTableSpec;
    private final Class<?> mJoinType;
    private final JoinRowInfo mJoinInfo;
    private final Query mQuery;

    private JoinPlanner mPlanner;
    private Class<?> mScannerClass;
    private ClassMaker mClassMaker;
    private JoinSpec.Source[] mSources;

    private JoinQueryLauncherMaker(JoinTable<?> table, String queryStr) {
        mTableSpec = table.joinSpec();
        mJoinType = table.rowType();
        mJoinInfo = JoinRowInfo.find(mJoinType);
        mQuery = new Parser(mJoinInfo.allColumns, queryStr).parseQuery(null);
    }

    private String canonicalQuery() {
        return mQuery.toString();
    }

    Class<?> finish() {
        mPlanner = new JoinPlanner(mTableSpec, mQuery.filter());

        JoinSpec spec = mPlanner.spec();
        mScannerClass = JoinScannerMaker.make(mJoinType, spec);

        mClassMaker = RowGen.anotherClassMaker
            (getClass(), mJoinInfo.name, mScannerClass, "launcher")
            .implement(QueryLauncher.class).public_().final_();

        mSources = spec.copySources();

        // Define fields for referencing all the joined tables.
        for (JoinSpec.Source source : mSources) {
            mClassMaker.addField(Table.class, source.name()).private_().final_();
        }

        addConstructor();
        addNewScannerMethod();
        addScanWriteMethod();
        addScannerPlanMethod();

        return mClassMaker.finish();
    }

    private void addConstructor() {
        MethodMaker mm = mClassMaker.addConstructor(Table[].class).public_();
        mm.invokeSuperConstructor();

        var arrayIndexes = new HashMap<String, Integer>();

        mTableSpec.root().accept(new JoinSpec.Visitor() {
            @Override
            public JoinSpec.Node visit(JoinSpec.Column node) {
                doVisit(node);
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.FullJoin node) {
                doVisit(node);
                return node;
            }

            private void doVisit(JoinSpec.Source source) {
                arrayIndexes.put(source.name(), arrayIndexes.size());
            }
        });

        var tablesVar = mm.param(0);

        for (JoinSpec.Source source : mSources) {
            String name = source.name();
            mm.field(name).set(tablesVar.aget(arrayIndexes.get(name)));
        }
    }

    private void addNewScannerMethod() {
        MethodMaker mm = mClassMaker.addMethod
            (Scanner.class, "newScannerWith", Transaction.class, Object.class, Object[].class)
            .public_().varargs();

        var txnVar = mm.param(0);
        var joinRowVar = mm.param(1).cast(mJoinType);
        var argsVar = mm.param(2);

        Label enoughArgs = mm.label();
        int original = mPlanner.originalNumArgs();
        if (original != 0) {
            var provided = argsVar.alength();
            provided.ifGe(original, enoughArgs);
            mm.var(RowUtils.class).invoke("tooFewArgumentsException", original, provided).throw_();
            enoughArgs.here();
        }

        int total = mPlanner.totalNumArgs();
        if (total > original) {
            var newArgsVar = mm.new_(Object[].class, total);
            mm.var(System.class).invoke("arraycopy", argsVar, 0, newArgsVar, 0, original);
            argsVar.set(newArgsVar);
        }

        Class<?> joinClass = JoinRowMaker.find(mJoinType);

        var notNull = mm.label();
        joinRowVar.ifNe(null, notNull);
        joinRowVar.set(mm.new_(joinClass));
        notNull.here();

        var params = new Object[2 + mSources.length + 1];
        params[0] = txnVar;
        params[1] = joinRowVar;
        params[params.length - 1] = argsVar;

        for (int i=0; i<mSources.length; i++) {
            params[2 + i] = mm.field(mSources[i].name());
        }

        mm.return_(mm.new_(mScannerClass, params));
    }

    private void addScanWriteMethod() {
        MethodMaker mm = mClassMaker.addMethod
            (null, "scanWrite", Transaction.class, RowWriter.class, Object[].class)
            .public_().varargs();

        // FIXME: scanWrite
        mm.new_(UnsupportedOperationException.class, "FIXME").throw_();
    }

    private void addScannerPlanMethod() {
        MethodMaker mm = mClassMaker.addMethod
            (QueryPlan.class, "scannerPlan", Transaction.class, Object[].class).public_().varargs();

        // Note that when joins have a "right side first" association order, the sub levels are
        // other nested loops. In other words, the plan is a nested loop of nested loops. This
        // doesn't exactly match what's generated, but it's a good logical representation.

        var visitor = new JoinSpec.Visitor() {
            int mType;
            List<Variable> mLevels = new ArrayList<>();

            @Override
            public JoinSpec.Node visit(JoinSpec.Column node) {
                mLevels.add(makeColumnLevelPlan(mm, mType, node));
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.JoinOp node) {
                node.leftChild().accept(this);

                final int originalType = mType;

                JoinSpec.Node rightChild = node.rightChild();

                if (rightChild instanceof JoinSpec.Source) {
                    mType = node.type();
                    rightChild.accept(this);
                } else {
                    final List<Variable> originalLevels = mLevels;
                    mType = 0;
                    mLevels = new ArrayList<>();
                    rightChild.accept(this);

                    Variable subPlanVar = makePlan();
                    var levelVar = mm.new_(QueryPlan.NestedLoopsJoin.Level.class,
                                           typeStr(node.type()), subPlanVar, null);
                    originalLevels.add(levelVar);

                    mLevels = originalLevels;
                }

                mType = originalType;
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.InnerJoins node) {
                return node.toJoinOp().accept(this);
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.FullJoin node) {
                mLevels.add(makeFullJoinLevelPlan(mm, mType, node));
                return node;
            }

            Variable makePlan() {
                var levelsVar = mm.new_(QueryPlan.NestedLoopsJoin.Level[].class, mLevels.size());
                for (int i=0; i<mLevels.size(); i++) {
                    levelsVar.aset(i, mLevels.get(i));
                }
                var planVar = mm.var(QueryPlan.class);
                planVar.set(mm.new_(QueryPlan.NestedLoopsJoin.class, levelsVar));
                return planVar;
            }
        };

        JoinSpec spec = mPlanner.spec();

        spec.root().accept(visitor);
        var planVar = visitor.makePlan();

        RowFilter filter = spec.filter();
        if (filter != null && filter != TrueFilter.THE) {
            planVar = mm.new_(QueryPlan.Filter.class, filter.toString(), planVar);
        }

        mm.return_(planVar);
    }

    private Variable makeAssignmentsVar(MethodMaker mm, Map<Integer, ColumnInfo> assignments) {
        final Variable assignmentsVar;

        if (assignments == null || assignments.isEmpty()) {
            assignmentsVar = null;
        } else if (assignments.size() == 1) {
            assignmentsVar = mm.var(Map.class);
            for (Map.Entry<Integer, ColumnInfo> e : assignments.entrySet()) {
                int argNum = Math.abs(e.getKey());
                assignmentsVar.set(assignmentsVar.invoke("of", argNum, e.getValue().name));
            }
        } else {
            assignmentsVar = mm.new_(TreeMap.class);
            for (Map.Entry<Integer, ColumnInfo> e : assignments.entrySet()) {
                int argNum = Math.abs(e.getKey());
                assignmentsVar.invoke("put", argNum, e.getValue().name);
            }
        }

        return assignmentsVar;
    }

    /**
     * @param type join type; pass 0 for outermost level
     */
    private Variable makeColumnLevelPlan(MethodMaker mm, int type, JoinSpec.Column node) {
        final Variable subPlanVar;
        final RowFilter filter = node.filter();

        if (filter == FalseFilter.THE) {
            subPlanVar = mm.new_(QueryPlan.Empty.class);
        } else {
            var tableVar = mm.field(node.name());
            var txnVar = mm.param(0);
            String queryStr = queryStr(filter);
            var argsVar = mm.param(1);
            subPlanVar = tableVar.invoke("scannerPlan", txnVar, queryStr, argsVar);
        }

        final RowFilter remainder = node.remainder();

        if (remainder != null && remainder != TrueFilter.THE) {
            String queryStr = remainder.replaceArguments(Math::abs).toString();
            subPlanVar.set(mm.new_(QueryPlan.Filter.class, queryStr, subPlanVar));
        }

        Variable assignmentsVar = makeAssignmentsVar(mm, node.argAssignments());

        if (assignmentsVar == null && type == JoinSpec.T_LEFT_ANTI
            && (remainder == null || remainder == TrueFilter.THE)
            && node == mSources[mSources.length - 1])
        {
            subPlanVar.set(subPlanVar.methodMaker().new_(QueryPlan.Exists.class, subPlanVar));
        }

        return mm.new_(QueryPlan.NestedLoopsJoin.Level.class,
                       typeStr(type), subPlanVar, assignmentsVar);
    }

    /**
     * @param type join type; pass 0 for outermost level
     */
    private Variable makeFullJoinLevelPlan(MethodMaker mm, int type, JoinSpec.FullJoin node) {
        String queryStr = queryStr(node.filter());

        var subPlanVar = mm.field(node.name())
            .invoke("scannerPlan", mm.param(0), queryStr, mm.param(1));

        Variable assignmentsVar = makeAssignmentsVar(mm, node.argAssignments());

        if (assignmentsVar == null && type == JoinSpec.T_LEFT_ANTI
            && node == mSources[mSources.length - 1])
        {
            subPlanVar.set(subPlanVar.methodMaker().new_(QueryPlan.Exists.class, subPlanVar));
        }

        return mm.new_(QueryPlan.NestedLoopsJoin.Level.class,
                       typeStr(type), subPlanVar, assignmentsVar);
    }

    private static String queryStr(RowFilter filter) {
        return (filter == null || filter == TrueFilter.THE) ? null
            : filter.replaceArguments(Math::abs).toString();
    }

    private static String typeStr(int type) {
        String prefix;

        switch (type) {
            case 0 -> {return "first";}
            default -> {return "join";}
            case JoinSpec.T_LEFT_OUTER, JoinSpec.T_RIGHT_OUTER -> prefix = "outer";
            case JoinSpec.T_LEFT_ANTI, JoinSpec.T_RIGHT_ANTI -> prefix = "anti";
        }

        return prefix + ' ' + "join";
    }
}
