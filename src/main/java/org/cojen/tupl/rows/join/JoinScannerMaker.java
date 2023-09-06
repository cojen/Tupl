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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.Field;
import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;
import org.cojen.maker.Variable;

import org.cojen.tupl.Scanner;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.EmptyScanner;
import org.cojen.tupl.rows.RowGen;
import org.cojen.tupl.rows.RowInfo;
import org.cojen.tupl.rows.WeakCache;

import org.cojen.tupl.rows.filter.FalseFilter;
import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.TrueFilter;

/**
 * Implements a nested loops join.
 *
 * @author Brian S O'Neill
 */
final class JoinScannerMaker {
    private static final WeakCache<Key, Class<?>, JoinSpec> cBaseCache;

    static {
        cBaseCache = new WeakCache<>() {
            @Override
            protected Class<?> newValue(Key key, JoinSpec spec) {
                return new JoinScannerMaker(key.mJoinType, spec, true).finish();
            }
        };
    }

    /**
     * Returns a class implementing JoinScanner, which is constructed with these parameters:
     *
     *   (Transaction txn, J joinRow, Table table0, ..., Object... args)
     *
     * @param joinType interface which defines a join row
     * @see JoinScanner
     */
    static Class<?> make(Class<?> joinType, JoinSpec spec) {
        return new JoinScannerMaker(joinType, spec, false).finish();
    }

    private static final class Key {
        final Class<?> mJoinType;
        final String[] mSourceNames;

        Key(Class<?> joinType, JoinSpec spec) {
            mJoinType = joinType;
            mSourceNames = spec.copySourceNames();
            Arrays.sort(mSourceNames);
        }

        @Override
        public int hashCode() {
            return mJoinType.hashCode() + Arrays.hashCode(mSourceNames);
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Key other
                && mJoinType == other.mJoinType && Arrays.equals(mSourceNames, other.mSourceNames);
        }
    }

    private final Class<?> mJoinType;
    private final JoinSpec mSpec;
    private final boolean mBase;

    private final Class<?> mJoinClass;

    private final ClassMaker mClassMaker;

    private MethodMaker mCtorMaker;

    private JoinSpec.Source mLastSource;

    // The remaining fields are updated by the addLoopMethod and are stateful.

    private Variable mJoinRowVar;

    // Is set to null when the loop end is reached.
    private Variable mObjectResultVar;
    // Lazily defined, and is set to false when the loop end is reached.
    private Variable mBooleanResultVar;
    // Is mObjectResultVar or mBooleanResultVar.
    private Variable mChosenResultVar;

    private int mParentType;
    private JoinSpec.Source mLastExists; // is set when exists was called for the last source

    private int mReadyNum;

    private Map<String, String> mPredicateFieldMap;

    private JoinScannerMaker(Class<?> joinType, JoinSpec spec, boolean base) {
        mJoinType = joinType;
        mSpec = spec;
        mBase = base;

        mJoinClass = JoinRowMaker.find(joinType);

        RowInfo joinInfo = RowInfo.find(joinType);

        if (base) {
            // Generate a new sub-package to facilitate unloading.
            String subPackage = RowGen.newSubPackage();
            mClassMaker = RowGen.beginClassMaker
                (getClass(), joinType, joinInfo.name, subPackage, "scanner")
                .extend(JoinScanner.class).public_();
        } else {
            Class<?> parentClass = cBaseCache.obtain(new Key(joinType, spec), spec);
            mClassMaker = RowGen.anotherClassMaker
                (getClass(), joinInfo.name, parentClass, "scanner")
                .extend(parentClass);
        }
    }

    private Class<?> finish() {
        return mBase ? finishBase() : finishImpl();
    }

    /**
     * Returns a common base class which implements everything except the loop method.
     */
    private Class<?> finishBase() {
        mClassMaker.abstract_();
        mClassMaker.addMethod(mJoinType, "loop", mJoinType, boolean.class).protected_().abstract_();

        mClassMaker.addField(Transaction.class, "txn").protected_().final_();
        mClassMaker.addField(mJoinType, "row").protected_();

        MethodMaker ctor = mClassMaker.addConstructor(Transaction.class, mJoinType);
        ctor.invokeSuperConstructor();
        ctor.field("txn").set(ctor.param(0));

        mSpec.root().accept(new JoinSpec.Visitor() {
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
                String fieldName = source.name() + "_s";
                mClassMaker.addField(Scanner.class, fieldName).protected_();
                // The scanner instances must never be null.
                ctor.field(fieldName).set(ctor.var(EmptyScanner.class).field("THE"));
            }
        });

        addRowMethod();
        addStepMethod();

        addClearMethod();
        addCloseMethod();

        Label tryStart = ctor.label().here();
        ctor.invoke("loop", ctor.param(1), false);
        ctor.return_();
        Label tryEnd = ctor.label().here();

        var exVar = ctor.catch_(tryStart, tryEnd, Throwable.class);
        ctor.invoke("close", ctor.param(1), exVar).throw_();

        return mClassMaker.finish();
    }

    /**
     * Returns a class which only needs to implement the loop method.
     */
    private Class<?> finishImpl() {
        mClassMaker.addField(Object[].class, "args").private_().final_();

        var paramTypes = new Object[2 + mSpec.numSources() + 1];
        int i = 0;
        paramTypes[i++] = Transaction.class;
        paramTypes[i++] = mJoinType;
        for (; i < paramTypes.length - 1; i++) {
            paramTypes[i] = Table.class;
        }
        paramTypes[i] = Object[].class;

        mCtorMaker = mClassMaker.addConstructor(paramTypes);

        mSpec.root().accept(new JoinSpec.Visitor() {
            int mNum = 2;

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
                mLastSource = source;
                String fieldName = source.name() + "_t";
                mClassMaker.addField(Table.class, fieldName).private_().final_();
                mCtorMaker.field(fieldName).set(mCtorMaker.param(mNum++));
            }
        });

        mCtorMaker.field("args").set(mCtorMaker.param(paramTypes.length - 1));

        addLoopMethod();

        // Call this after defining the loops method because it might have added more code into
        // constructor which must run before the super class constructor.
        mCtorMaker.invokeSuperConstructor(mCtorMaker.param(0), mCtorMaker.param(1));

        return mClassMaker.finish();
    }

    /**
     * Add the public row method and the bridge.
     */
    private void addRowMethod() {
        MethodMaker mm = mClassMaker.addMethod(mJoinType, "row").public_().final_();
        mm.return_(mm.field("row"));

        mm = mClassMaker.addMethod(Object.class, "row").public_().final_().bridge();
        mm.return_(mm.this_().invoke(mJoinType, "row", null));
    }

    /**
     * Add the public step method which accepts a row and the bridge method.
     */
    private void addStepMethod() {
        MethodMaker mm = mClassMaker.addMethod(mJoinType, "step", Object.class);
        mm.public_().final_();

        var newJoinRowVar = mm.param(0).cast(mJoinType);

        var newRow = mm.label();
        newJoinRowVar.ifEq(null, newRow);

        var joinRowVar = mm.field("row").get();
        Label ready = mm.label();
        newJoinRowVar.ifEq(joinRowVar, ready);

        // The join row instance changed, so update it to match the current row.
        mSpec.root().accept(new JoinSpec.Visitor() {
            @Override
            public JoinSpec.Node visit(JoinSpec.Column node) {
                ColumnInfo column = node.column();
                var columnVar = mm.field(column.name + "_s").invoke("row").cast(column.type);
                newJoinRowVar.invoke(column.name, columnVar);
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.FullJoin node) {
                // The sub scanner will detect the row instance has changed when it's stepped.
                return node;
            }
        });

        ready.goto_();

        newRow.here();
        newJoinRowVar.set(mm.new_(mJoinClass));

        ready.here();

        mm.return_(mm.invoke("loop", newJoinRowVar, true));

        var exVar = mm.catch_(ready, mm.label().here(), Throwable.class);
        mm.invoke("close", newJoinRowVar, exVar).throw_();

        // Add the bridge method.
        MethodMaker bridge = mClassMaker.addMethod
            (Object.class, "step", Object.class).public_().final_().bridge();
        bridge.return_(bridge.this_().invoke(mJoinType, "step", null, bridge.param(0)));
    }

    private void addClearMethod() {
        MethodMaker mm = mClassMaker.addMethod(null, "clear", Object.class).protected_().final_();
        var joinRow = mm.param(0);
        Label cont = mm.label();
        joinRow.ifEq(null, cont);
        clearAll(joinRow.cast(mJoinType), mSpec.root());
        cont.here();
    }

    private void addCloseMethod() {
        MethodMaker mm = mClassMaker.addMethod(null, "close").public_().final_();
        mm.field("row").set(null);
        closeAll(mm, mSpec.root());
    }

    /**
     * Generates code to clear all the columns associated with the given node.
     */
    private static void clearAll(Variable joinRow, JoinSpec.Node node) {
        node.accept(new JoinSpec.Visitor() {
            @Override
            public JoinSpec.Node visit(JoinSpec.Column node) {
                joinRow.invoke(node.name(), (Object) null);
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.FullJoin node) {
                JoinSpec.ColumnIterator it = node.columnIterator();
                JoinSpec.Column column;
                while ((column = it.tryNext()) != null) {
                    column.accept(this);
                }
                return node;
            }
        });
    }

    /**
     * Generates code to close all the scanners associated with the given node.
     */
    private void closeAll(MethodMaker mm, JoinSpec.Node node) {
        node.accept(new JoinSpec.Visitor() {
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
                if (source == mLastExists) {
                    // If source is the last and exists was called, then no scanner was created.
                } else {
                    mm.field(source.name() + "_s").invoke("close");
                }
            }
        });
    }

    /**
     * private J loop(J joinRow, boolean jumpIn) ...
     */
    private void addLoopMethod() {
        MethodMaker mm = mClassMaker.addMethod
            (mJoinType, "loop", mJoinType, boolean.class).protected_().final_().override();

        mJoinRowVar = mm.param(0);
        mObjectResultVar = mm.var(Object.class);

        Label jumpIn = mm.label();
        mm.param(1).ifTrue(jumpIn);
        Label jumpOut = mm.label();
        loop(mSpec.root(), jumpOut, jumpIn);
        jumpOut.here();

        Label finished = mm.label();
        gotoIfLoopEnded(finished);

        RowFilter filter = mSpec.filter();
        if (filter != null && filter != TrueFilter.THE) {
            predicateField(mm, filter).invoke("test", mJoinRowVar).ifFalse(jumpIn);
        }

        mm.field("row").set(mJoinRowVar);
        mm.return_(mJoinRowVar);

        // No rows left.
        finished.here();
        mm.field("row").set(null);
        mm.return_(null);
    }

    /**
     * @param jumpOut a label which is positioned by the caller for jumping out of the loop
     * @param jumpIn a label which is positioned by this method for jumping back into the loop
     * right after the jumpOut branch
     */
    private void loop(JoinSpec.Node node, Label jumpOut, Label jumpIn) {
        node.accept(new JoinSpec.Visitor() {
            @Override
            public JoinSpec.Node visit(JoinSpec.Column node) {
                loopColumn(node, jumpOut, jumpIn);
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.JoinOp node) {
                loopJoinOp(node, jumpOut, jumpIn);
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.InnerJoins node) {
                return visit(node.toJoinOp());
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.FullJoin node) {
                loopFullJoin(node, jumpOut, jumpIn);
                return node;
            }
        });
    }

    private void loopColumn(JoinSpec.Column node, Label jumpOut, Label jumpIn) {
        MethodMaker mm = mJoinRowVar.methodMaker();

        RowFilter remainder = node.remainder();
        boolean hasRemainder = remainder != null && remainder != TrueFilter.THE;

        Map<Integer, ColumnInfo> argAssignments = node.argAssignments();
        boolean hasArgAssignments = argAssignments != null && !argAssignments.isEmpty();

        if (mParentType == JoinSpec.T_LEFT_ANTI && node == mLastSource
            && !hasRemainder && !hasArgAssignments)
        {
            // Only need to check if a row exists.
            mLastExists = node;
            setBooleanResult(makeExists(node));
            jumpOut.goto_();
            jumpIn.here();
            setBooleanResult(false);
            jumpOut.goto_();
            return;
        }

        Variable scannerVar = makeNewScanner(node);

        ColumnInfo column = node.column();

        Field scannerField = mm.field(column.name + "_s");
        scannerField.set(scannerVar);

        var levelRowVar = scannerVar.invoke("row");

        Label start = mm.label().here();

        var typedLevelRowVar = levelRowVar.cast(column.type);
        mJoinRowVar.invoke(column.name, typedLevelRowVar);

        Label cont = null;

        if (hasRemainder || hasArgAssignments) {
            // If the level row is null, then the scanner has reached the end. An explicit null
            // check isn't needed when there's no remainder or arg assignments because the "if"
            // target is the same whether or not the level row is null.
            cont = mm.label();
            typedLevelRowVar.ifEq(null, cont);
        }

        Label step = null;

        if (hasRemainder) {
            // Need to apply a predicate before the level row can be accepted. When the
            // predicate returns false, jump to the location which steps ahead.
            step = mm.label();
            predicateField(mm, remainder).invoke("test", mJoinRowVar).ifFalse(step);
        }

        if (hasArgAssignments) {
            // Set the necessary arguments which are needed by other levels.
            var argsVar = mm.field("args").get();
            for (Map.Entry<Integer, ColumnInfo> e : argAssignments.entrySet()) {
                argsVar.aset(e.getKey() - 1, typedLevelRowVar.invoke(e.getValue().tail().name));
            }
        }

        if (cont != null) {
            cont.here();
        }

        setObjectResult(typedLevelRowVar);

        jumpOut.goto_();
        jumpIn.here();

        scannerVar.set(scannerField.get());
        levelRowVar.set(scannerVar.invoke("row"));

        if (step != null) {
            step.here();
        }

        levelRowVar.set(scannerVar.invoke("step", mJoinRowVar.invoke(column.name)));

        start.goto_();
    }

    private void loopJoinOp(JoinSpec.JoinOp node, Label jumpOut, Label jumpIn) {
        MethodMaker mm = mJoinRowVar.methodMaker();

        Label step = mm.label();

        // Loop over the left child.
        {
            Label leftJumpOut = mm.label();
            loop(node.leftChild(), leftJumpOut, step);
            leftJumpOut.here();
        }

        gotoIfLoopEnded(jumpOut);

        int type = node.type();
        mParentType = type;

        // Loop over the right child.
        {
            Label rightJumpOut = mm.label();
            loop(node.rightChild(), rightJumpOut, jumpIn);
            rightJumpOut.here();
        }

        switch (type) {
            case JoinSpec.T_INNER, JoinSpec.T_STRAIGHT -> {
                // Regular inner join.
                gotoIfLoopEnded(step);
                jumpOut.goto_();
                return;
            }
            case JoinSpec.T_LEFT_OUTER, JoinSpec.T_LEFT_ANTI -> {
                // Okay.
            }
            default -> {
                throw new UnsupportedOperationException("Unsupported join: " + node);
            }
        }

        // Define a "ready" field which is set when the right child result isn't null. If the
        // result is null and "ready" is false, then produce a row with nulls.

        String readyName = addReadyField();
        var readyField = mm.field(readyName);

        Label hasResult = mm.label();
        gotoIfLoopNotEnded(hasResult);

        var readyVar = readyField.get().xor(1);
        readyField.set(readyVar);

        clearAll(mJoinRowVar, node.rightChild());

        // TODO: If 1, do any other ready fields need to be set?
        readyVar.ifEq(0, step); // step to the next row

        // Before producing a row with nulls, an additional predicate test might be needed.
        RowFilter predicate = node.predicate();
        if (predicate != null && predicate != TrueFilter.THE) {
            predicateField(mm, predicate).invoke("test", mJoinRowVar).ifFalse(step);
        }

        setObjectResult(mJoinRowVar);
        jumpOut.goto_();

        hasResult.here();
        readyField.set(1);

        if (type == JoinSpec.T_LEFT_ANTI) {
            closeAll(mm, node.rightChild());
            jumpIn.goto_();
        } else {
            jumpOut.goto_();
        }
    }

    private void loopFullJoin(JoinSpec.FullJoin node, Label jumpOut, Label jumpIn) {
        MethodMaker mm = mJoinRowVar.methodMaker();

        RowFilter predicate = node.predicate();
        boolean hasPredicate = predicate != null && predicate != TrueFilter.THE;

        Map<Integer, ColumnInfo> argAssignments = node.argAssignments();
        boolean hasArgAssignments = argAssignments != null && !argAssignments.isEmpty();

        if (mParentType == JoinSpec.T_LEFT_ANTI && node == mLastSource
            && !hasPredicate && !hasArgAssignments)
        {
            // Only need to check if a row exists.
            mLastExists = node;
            setBooleanResult(makeExists(node));
            jumpOut.goto_();
            jumpIn.here();
            setBooleanResult(false);
            jumpOut.goto_();
            return;
        }

        Variable scannerVar = makeNewScanner(node);

        Field scannerField = mm.field(node.name() + "_s");
        scannerField.set(scannerVar);

        var levelRowVar = scannerVar.invoke("row");

        Label start = mm.label().here();

        Label cont = null;

        if (hasPredicate || hasArgAssignments) {
            // If the level row is null, then the scanner has reached the end.
            cont = mm.label();
            levelRowVar.ifEq(null, cont);
        }

        Label step = null;

        if (hasPredicate) {
            // Before producing a row with nulls, an additional predicate test might be needed.
            step = mm.label();
            predicateField(mm, predicate).invoke("test", mJoinRowVar).ifFalse(step);
        }

        if (hasArgAssignments) {
            // Set the necessary arguments which are needed by other levels.
            var argsVar = mm.field("args").get();
            var typedLevelRowVar = levelRowVar.cast(mJoinType);
            for (Map.Entry<Integer, ColumnInfo> e : argAssignments.entrySet()) {
                argsVar.aset(e.getKey() - 1, accessPath(typedLevelRowVar, e.getValue()));
            }
        }

        if (cont != null) {
            cont.here();
        }

        setObjectResult(levelRowVar);

        jumpOut.goto_();
        jumpIn.here();

        scannerVar.set(scannerField.get());
        levelRowVar.set(scannerVar.invoke("row"));

        if (step != null) {
            step.here();
        }

        levelRowVar.set(scannerVar.invoke("step", mJoinRowVar));

        start.goto_();
    }

    /**
     * Generates code which follows a path to obtain the column value, yielding null if any
     * path component is null.
     */
    private static Variable accessPath(Variable objVar, ColumnInfo ci) {
        MethodMaker mm = objVar.methodMaker();
        Variable resultVar = mm.var(Object.class);
        Label fail = mm.label();

        while (true) {
            String prefix = ci.prefix();
            if (prefix == null) {
                resultVar.set(objVar.invoke(ci.name));
                break;
            }
            objVar = objVar.invoke(prefix);
            objVar.ifEq(null, fail);
            ci = ci.tail();
        }

        Label cont = mm.label().goto_();
        fail.here();
        resultVar.set(null);
        cont.here();

        return resultVar;
    }

    private void setObjectResult(Object result) {
        mObjectResultVar.set(result);
        mChosenResultVar = mObjectResultVar;
    }

    private void setBooleanResult(Object result) {
        var resultVar = mBooleanResultVar;
        if (resultVar == null) {
            mBooleanResultVar = resultVar = mJoinRowVar.methodMaker().var(boolean.class);
        }
        resultVar.set(result);
        mChosenResultVar = resultVar;
    }

    private void gotoIfLoopEnded(Label target) {
        var resultVar = mChosenResultVar;
        if (resultVar.classType() == boolean.class) {
            resultVar.ifFalse(target);
        } else {
            resultVar.ifEq(null, target);
        }
    }

    private void gotoIfLoopNotEnded(Label target) {
        var resultVar = mChosenResultVar;
        if (resultVar.classType() == boolean.class) {
            resultVar.ifTrue(target);
        } else {
            resultVar.ifNe(null, target);
        }
    }

    private String addReadyField() {
        String name = "r" + mReadyNum++;
        mClassMaker.addField(int.class, name).private_();
        return name;
    }

    /**
     * Returns a Variable of type Predicate which tests against the given RowFilter.
     */
    private Variable predicateField(MethodMaker mm, RowFilter predicate) {
        Map<String, String> map = mPredicateFieldMap;

        if (map == null) {
            mPredicateFieldMap = map = new HashMap<>();
        }

        String predicateStr = predicate.toString();
        String fieldName = map.get(predicateStr);

        if (fieldName == null) {
            // Define the field and assign it in the constructor.
            fieldName = "p" + map.size();
            mClassMaker.addField(Predicate.class, fieldName).private_().final_();
            MethodHandle predicateCtor = JoinPredicateMaker.find(mJoinType, predicate);
            var mhVar = mCtorMaker.var(MethodHandle.class).setExact(predicateCtor);
            var argsVar = mCtorMaker.param(mCtorMaker.paramCount() - 1);
            var predVar = mhVar.invoke(Predicate.class, "invoke", null, argsVar);
            mCtorMaker.field(fieldName).set(predVar);

            // Stash it so as not to create duplicate predicates.
            mPredicateFieldMap.put(predicateStr, fieldName);
        }

        return mm.field(fieldName);
    }

    /**
     * @return a Variable of type boolean
     */
    private Variable makeExists(JoinSpec.Source source) {
        return makeNewScannerOrExists(source, true);
    }

    /**
     * @return a Variable with a Scanner instance
     */
    private Variable makeNewScanner(JoinSpec.Source source) {
        return makeNewScannerOrExists(source, false);
    }

    private Variable makeNewScannerOrExists(JoinSpec.Source source, boolean exists) {
        // Define the code in a separate method, which helps with debugging because the stack
        // trace will include the name of the joined column. The code might also become quite
        // large if special null argument processing is required, and so defining it separately
        // reduces the likelihood that the loops method becomes too large.

        String name = source.name();
        Class<?> returnType;

        if (exists) {
            name += "_x";
            returnType = boolean.class;
        } else {
            name += "_s";
            returnType = Scanner.class;
        }

        MethodMaker mm = mClassMaker.addMethod(returnType, name, mJoinType).private_();
        new ScannerMaker(mm, source, exists).make();

        return mJoinRowVar.methodMaker().invoke(name, mJoinRowVar);
    }

    /**
     * Makes code that calls newScannerWith on the join column.
     */
    private static class ScannerMaker implements IntUnaryOperator {
        private final MethodMaker mMethodMaker;
        private final JoinSpec.Source mSource;
        private final boolean mExists;
        private final Variable mTableVar, mTxnVar, mArgsVar, mJoinRowVar;
        private final RowFilter mFilter;

        private int[] mArgsToCheck;
        private int mNumArgsToCheck;

        /**
         * @param exists pass true to call exists instead of newScannerWith
         */
        ScannerMaker(MethodMaker mm, JoinSpec.Source source, boolean exists) {
            mMethodMaker = mm;
            mSource = source;
            mExists = exists;

            mTableVar = mm.field(source.name() + "_t");
            mTxnVar = mm.field("txn");
            mArgsVar = mm.field("args");
            mJoinRowVar = mm.param(0);

            RowFilter filter = source.filter();

            if (filter == null) {
                mFilter = TrueFilter.THE;
            } else {
                mFilter = filter.replaceArguments(this);
            }
        }

        /**
         * Defined in IntUnaryOperator and used to find arguments which require null checks.
         */
        @Override
        public int applyAsInt(int argNum) {
            if (argNum < 0) {
                argNum = -argNum;
                if (mArgsToCheck == null) {
                    mArgsToCheck = new int[4];
                } else if (mNumArgsToCheck >= mArgsToCheck.length) {
                    mArgsToCheck = Arrays.copyOf(mArgsToCheck, mArgsToCheck.length << 1);
                }
                mArgsToCheck[mNumArgsToCheck++] = argNum;
            }

            return argNum;
        }

        void make() {
            doMake(mFilter, mNumArgsToCheck - 1);
        }

        /**
         * Recursively generates code which assigns arguments and returns a new scanner (or
         * calls exists).
         *
         * <p>This algorithm doesn't scale when too many arguments require null checks. If
         * there are 30 such arguments, then one billion code paths are generated (2^n), which
         * isn't going to work.
         *
         * <p>In practice, join predicates aren't expected to be that large, and filters
         * against each level aren't expected to be that complicated. The branches that handle
         * nulls operate against filters which have the null comparisons removed, and this
         * leads to filters eventually becoming "true" or "false". The branch can then
         * short-circuit, reducing the overall code that needs to be generated.
         */
        private void doMake(RowFilter filter, int checkArg) {
            while (checkArg >= 0) {
                int argNum = mArgsToCheck[checkArg];
                RowFilter asNullFilter = filter.argumentAsNull(argNum);

                if (asNullFilter == filter) {
                    break;
                }

                Label isNull = mMethodMaker.label();
                mArgsVar.aget(argNum - 1).ifEq(null, isNull);

                // Continue generating code down the branch where the argument isn't null.
                doMake(filter, checkArg - 1);

                isNull.here();

                // Continue generating code down the branch where the argument is null.
                filter = asNullFilter;
                checkArg--;
            }

            final Variable resultVar;

            if (mExists) {
                if (filter == FalseFilter.THE) {
                    resultVar = mMethodMaker.var(boolean.class).set(false);
                } else if (filter == TrueFilter.THE) {
                    resultVar = mTableVar.invoke("anyRowsWith", mTxnVar, levelRowVar());
                } else {
                    Set<String> sources = mSource.argSources();
                    if (sources != null) {
                        // If any argument source is null, return false.
                        Label empty = mMethodMaker.label();
                        for (String source : sources) {
                            Label cont = mMethodMaker.label();
                            mJoinRowVar.invoke(source).ifNe(null, cont);
                            mMethodMaker.return_(false);
                            cont.here();
                        }
                    }

                    resultVar = mTableVar.invoke("anyRowsWith", mTxnVar, levelRowVar(),
                                                 filter.toString(), mArgsVar);
                }
            } else {
                if (filter == FalseFilter.THE) {
                    resultVar = mMethodMaker.var(EmptyScanner.class).field("THE");
                } else if (filter == TrueFilter.THE) {
                    resultVar = mTableVar.invoke("newScannerWith", mTxnVar, levelRowVar());
                } else {
                    Set<String> sources = mSource.argSources();
                    if (sources != null) {
                        // If any argument source is null, return an empty scanner.
                        Label empty = mMethodMaker.label();
                        for (String source : sources) {
                            mJoinRowVar.invoke(source).ifEq(null, empty);
                        }
                        Label cont = mMethodMaker.label().goto_();
                        empty.here();
                        mMethodMaker.return_(mMethodMaker.var(EmptyScanner.class).field("THE"));
                        cont.here();
                    }

                    resultVar = mTableVar.invoke("newScannerWith", mTxnVar, levelRowVar(),
                                                 filter.toString(), mArgsVar);
                }
            }

            mMethodMaker.return_(resultVar);
        }

        private Variable levelRowVar() {
            if (mSource instanceof JoinSpec.Column c) {
                return mJoinRowVar.invoke(c.name());
            } else {
                return mJoinRowVar;
            }
        }
    }
}
