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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cojen.tupl.Database;
import org.cojen.tupl.Table;

import org.cojen.tupl.rows.ColumnInfo;
import org.cojen.tupl.rows.GroupedTable;
import org.cojen.tupl.rows.RowInfo;
import org.cojen.tupl.rows.RowUtils;
import org.cojen.tupl.rows.SimpleParser;

import org.cojen.tupl.rows.filter.RowFilter;
import org.cojen.tupl.rows.filter.TrueFilter;

/**
 * @author Brian S O'Neill
 */
final class JoinSpec {
    // Note: The toLeftJoin method depends on the right types being left plus one. Also, no
    // types can be zero.
    static final int T_INNER = 1, T_STRAIGHT = 2,
        T_LEFT_OUTER = 3, T_RIGHT_OUTER = 4, T_FULL_OUTER = 5,
        T_LEFT_ANTI = 6, T_RIGHT_ANTI = 7, T_FULL_ANTI = 8;

    /**
     * JoinOp = Source { Type Source }
     * Source = Column | Group
     * Group = "(" JoinOp ")"
     * Column = string
     * Type   = ":" | "::" | ">:" | ":<" | ">:<" | ">" | "<" | "><"
     *
     * a : b    inner join
     * a :: b   straight inner join (cannot be reordered)
     * a >: b   left outer join
     * a :< b   right outer join
     * a >:< b  full outer join
     * a > b    left anti join
     * a < b    right anti join
     * a >< b   full anti join
     */
    static JoinSpec parse(RowInfo joinInfo, String spec, Table... tables) {
        try {
            return new JoinSpec(new Parser(joinInfo.allColumns, spec, tables, null).parse());
        } catch (IOException e) {
            // Not expected.
            throw RowUtils.rethrow(e);
        }
    }

    /**
     * Variant which opens tables automatically.
     */
    static JoinSpec parse(RowInfo joinInfo, String spec, Database db) throws IOException {
        return new JoinSpec(new Parser(joinInfo.allColumns, spec, null, db).parse());
    }

    private final Node mRoot;
    private final RowFilter mFilter;

    private int mNumSources;

    private String mStr;

    JoinSpec(Node root) {
        this(root, null);
    }

    /**
     * Construct a planned spec.
     *
     * @see JoinPlanner
     */
    JoinSpec(Node root, RowFilter filter) {
        mRoot = root;
        mFilter = filter;
        mNumSources = -1;
    }

    public Node root() {
        return mRoot;
    }

    /**
     * Returns a final filter to apply for all rows. Is null or TrueFilter if no filter should
     * be applied.
     *
     * @see JoinPlanner
     */
    public RowFilter filter() {
        return mFilter;
    }

    /**
     * Returns the number of Column and and FullJoin instances. A FullJoin counts as one
     * source, regardless of the number of columns it has.
     */
    public int numSources() {
        int numSources = mNumSources;
        if (numSources < 0) {
            mNumSources = numSources = mRoot.numSources();
        }
        return numSources;
    }

    /**
     * Copies Column and FullJoin instances.
     */
    public Source[] copySources() {
        var sources = new Source[numSources()];

        mRoot.accept(new Visitor() {
            int mPos;

            @Override
            public Node visit(Column node) {
                sources[mPos++] = node;
                return node;
            }

            @Override
            public Node visit(FullJoin node) {
                sources[mPos++] = node;
                return node;
            }
        });

        return sources;
    }

    /**
     * Copies the names of Column and FullJoin instances.
     */
    public String[] copySourceNames() {
        var names = new String[numSources()];

        mRoot.accept(new Visitor() {
            int mPos;

            @Override
            public Node visit(Column node) {
                names[mPos++] = node.name();
                return node;
            }

            @Override
            public Node visit(FullJoin node) {
                names[mPos++] = node.name();
                return node;
            }
        });

        return names;
    }

    /**
     * Replace all right joins (if any) with left joins.
     */
    public JoinSpec toLeftJoin() {
        Node root = mRoot.toLeftJoin();
        return root == mRoot ? this : new JoinSpec(root);
    }

    /**
     * If the root node is a full join, split the spec into two disjoint specs which can be
     * combined with a union.
     *
     * @throws IllegalStateException if this spec has a filter, which implies that it's a
     * planned spec
     */
    public JoinSpec[] trySplitFullJoin() {
        if (mFilter != null) {
            throw new IllegalStateException();
        }
        if (mRoot instanceof FullJoin full) {
            return full.split();
        } else if (mRoot instanceof JoinOp jop) {
            return jop.trySplitFullJoin();
        } else {
            return null;
        }
    }

    @Override
    public String toString() {
        String str = mStr;
        if (str == null) {
            mStr = str = mRoot.toString().intern();
        }
        return str;
    }

    public static boolean isOuterJoin(int type) {
        return type != T_INNER && type != T_STRAIGHT;
    }

    public static boolean isFullJoin(int type) {
        return type == T_FULL_OUTER || type == T_FULL_ANTI;
    }

    /**
     * Equivalent to: str.startsWith(start + '.')
     */
    static boolean startsWith(String str, String start) {
        return str.length() > start.length() && str.startsWith(start)
            && str.charAt(start.length()) == '.';
    }

    static RowFilter and(RowFilter a, RowFilter b) {
        return a == null ? b : (b == null ? a : a.and(b));
    }

    public static interface Visitor {
        /**
         * @return a replacement node, or the same instance if not applicable
         */
        public Node visit(Column node);

        /**
         * @return a replacement node, or the same instance if not applicable
         */
        public default Node visit(JoinOp node) {
            Node left = node.leftChild();
            Node newLeft = left.accept(this);

            Node right = node.rightChild();
            Node newRight = right.accept(this);

            if (left == newLeft && right == newRight) {
                return node;
            } else {
                return new JoinOp(newLeft, newRight, node.type());
            }
        }

        /**
         * @return a replacement node, or the same instance if not applicable
         */
        public default Node visit(InnerJoins node) {
            Node[] subNodes = node.mNodes;
            int numNodes = node.mNumNodes;

            for (int i=0; i<numNodes; i++) {
                Node sub = subNodes[i];
                Node newSub = sub.accept(this);
                if (sub != newSub) {
                    if (subNodes == node.mNodes) {
                        subNodes = Arrays.copyOf(subNodes, numNodes);
                    }
                    subNodes[i] = newSub;
                }
            }

            return subNodes == node.mNodes ? node : new InnerJoins(subNodes, numNodes);
        }

        /**
         * @return a replacement node, or the same instance if not applicable
         */
        public Node visit(FullJoin node);
    }

    /**
     * Supports iteration over all the sources of a node (Column or FullJoin);
     */
    public static interface SourceIterator {
        /**
         * Returns the next source or null if none left.
         */
        public Source tryNext();
    }

    /**
     * Supports iteration over all the columns of a node.
     */
    public static interface ColumnIterator extends SourceIterator {
        /**
         * Returns the next column or null if none left.
         */
        @Override
        public Column tryNext();
    }

    public static interface Source {
        public String name();

        /**
         * Returns a filter to apply when scanning the table. Is null or TrueFilter if no
         * filter should be applied.
         *
         * @see JoinPlanner
         */
        public RowFilter filter();

        /**
         * Replace the predicate filter, if supported.
         */
        public void predicate(RowFilter predicate);

        /**
         * Compares this source with another for determining the best inner join ordering.
         * This method requires that assignScore has been called.
         *
         * @return -1 if this node is worse, 0 if same, 1 if this node is better
         */
        public int compareScore(Source other);

        public long filterScore();

        /**
         * Returns this source or a new instance if it has assigned scores.
         */
        public Node copyWithScore();

        /**
         * Returns an optional map of arguments to assign before scanning the next table. Keys
         * are argument numbers and values are columns.
         *
         * <p>Negative argument numbers indicate that the runtime argument value might be null,
         * but the column which accepts the argument cannot be null. Special handling is
         * required to select an appropriate filter, and the argument number must be flipped
         * positive. Note that the first proper argument number is one.
         */
        public Map<Integer, ColumnInfo> argAssignments();

        /**
         * Add an argument assignment, if supported.
         */
        public void addArgAssignment(Integer argNum, ColumnInfo column);

        /**
         * Returns an optional set of source columns that this column depends on for argument
         * assignments.
         */
        public Set<String> argSources();

        /**
         * Add an argument source column, if supported.
         */
        public void addArgSource(String source);
    }

    public static abstract sealed class Node {
        public abstract Node accept(Visitor visitor);

        public SourceIterator sourceIterator() {
            return columnIterator();
        }

        public abstract ColumnIterator columnIterator();

        /**
         * Calculates a score for use by the compareScore method.
         *
         * @param fs used to perform the calculation
         * @param available set of columns known to be available
         */
        public abstract void assignScore(FilterScorer fs, Set<String> available);

        /**
         * Compares this node with another for determining the best inner join ordering.
         * This method requires that assignScore has been called.
         *
         * @return -1 if this node is worse, 0 if same, 1 if this node is better
         */
        public int compareScore(Node other) {
            SourceIterator it1 = this.sourceIterator();
            SourceIterator it2 = other.sourceIterator();
            while (true) {
                Source s1 = it1.tryNext();
                if (s1 == null) {
                    return 0;
                }
                Source s2 = it2.tryNext();
                if (s2 == null) {
                    return 0;
                }
                int cmp = s1.compareScore(s2);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }

        public abstract Node toLeftJoin();

        /**
         * Returns the original filters for all of the node columns, with join prefixes still
         * in place, and no additional arguments. Returns null or TrueFilter if no predicate
         * should be applied. Note that the predicate only needs to be considered for
         * outer/anti joins, and some terms might have been removed to faciliate this.
         *
         * Calling this method might cache the result, and so any changes to the nodes
         * afterwards might not be observed.
         *
         * @see JoinPlanner
         */
        public abstract RowFilter predicate();

        /**
         * Returns true if the join prefix of the given column name matches any of the columns
         * of this node.
         */
        abstract boolean matches(String name);

        /**
         * Outer join predicates are weaker to allow for null columns to be permitted when an
         * additional row must be generated. For terms which fully refer to the columns of this
         * node, remove them (they're replaced with TrueFilter). Terms which refer to columns
         * unknown to this node are retained. The strict parameter is false because a term only
         * needs to refer to at least one unknown column to be retained.
         *
         * This method should only be called by outer join nodes.
         *
         * @param predicate must not be null
         */
        RowFilter adjustOuterPredicate(RowFilter predicate) {
            return predicate.retain(name -> !matches(name), false, TrueFilter.THE);
        }

        abstract int numSources();

        /**
         * Implementation of the JoinTable.isEmpty method.
         */
        abstract boolean isEmpty() throws IOException;

        @Override
        public final String toString() {
            var b = new StringBuilder();
            appendTo(b);
            return b.toString();
        }

        abstract void appendTo(StringBuilder b);

        void appendGroupedTo(StringBuilder b) {
            b.append('(');
            appendTo(b);
            b.append(')');
        }
    }

    public static final class JoinOp extends Node {
        private final Node mLeftChild, mRightChild;
        private final int mType;

        private RowFilter mPredicate;

        JoinOp(Node leftChild, Node rightChild, int type) {
            mLeftChild = leftChild;
            mRightChild = rightChild;
            mType = type;
        }

        @Override
        public Node accept(Visitor visitor) {
            return visitor.visit(this);
        }

        @Override
        public ColumnIterator columnIterator() {
            return new ColumnIterator() {
                ColumnIterator mIterator = leftChild().columnIterator();
                boolean mOnRight;

                @Override
                public Column tryNext() {
                    ColumnIterator it = mIterator;
                    while (true) {
                        Column next = it.tryNext();
                        if (next != null || mOnRight) {
                            return next;
                        }
                        mOnRight = true;
                        mIterator = it = rightChild().columnIterator();
                    }
                }
            };
        }

        @Override
        public void assignScore(FilterScorer fs, Set<String> available) {
            mLeftChild.assignScore(fs, available);
            mRightChild.assignScore(fs, available);
        }

        @Override
        public JoinOp toLeftJoin() {
            Node leftChild = mLeftChild.toLeftJoin();
            Node rightChild = mRightChild.toLeftJoin();
            if (mType == T_RIGHT_OUTER || mType == T_RIGHT_ANTI) {
                return new JoinOp(rightChild, leftChild, mType - 1);
            } else if (leftChild != mLeftChild || rightChild != mRightChild) {
                return new JoinOp(leftChild, rightChild, mType);
            } else {
                return this;
            }
        }

        @Override
        public RowFilter predicate() {
            RowFilter predicate = mPredicate;

            if (predicate == null) {
                predicate = and(mLeftChild.predicate(), mRightChild.predicate());
                if (predicate != null && isOuterJoin(mType)) {
                    predicate = adjustOuterPredicate(predicate);
                }
                mPredicate = predicate;
            }

            return predicate;
        }

        @Override
        boolean matches(String name) {
            return mLeftChild.matches(name) || mRightChild.matches(name);
        }

        @Override
        int numSources() {
            return mLeftChild.numSources() + mRightChild.numSources();
        }

        public int type() {
            return mType;
        }

        public JoinOp asType(int type) {
            return new JoinOp(mLeftChild, mRightChild, type);
        }

        public Node leftChild() {
            return mLeftChild;
        }

        public Node rightChild() {
            return mRightChild;
        }

        JoinSpec[] trySplitFullJoin() {
            return isFullJoin(mType) ? splitFullJoin() : null;
        }

        /**
         * Should only be called when type is a full join.
         */
        JoinSpec[] splitFullJoin() {
            int leftType = mType == T_FULL_OUTER ? T_LEFT_OUTER : T_LEFT_ANTI;
            return new JoinSpec[] {
                new JoinSpec(asType(leftType)),
                new JoinSpec(asType(T_RIGHT_ANTI))
            };
        }

        @Override
        boolean isEmpty() throws IOException {
            return switch (mType) {
                default -> mLeftChild.isEmpty() || mRightChild.isEmpty();
                case T_LEFT_OUTER -> mLeftChild.isEmpty();
                case T_RIGHT_OUTER -> mRightChild.isEmpty();
                case T_FULL_OUTER -> mLeftChild.isEmpty() && mRightChild.isEmpty();
                case T_LEFT_ANTI -> mLeftChild.isEmpty() || !mRightChild.isEmpty();
                case T_RIGHT_ANTI -> mRightChild.isEmpty() || !mLeftChild.isEmpty();
                case T_FULL_ANTI -> mLeftChild.isEmpty() == mRightChild.isEmpty();
            };
        }

        @Override
        void appendTo(StringBuilder b) {
            mLeftChild.appendTo(b);

            String typeStr = switch (mType) {
                default -> ":";
                case T_STRAIGHT -> "::";
                case T_LEFT_OUTER -> ">:";
                case T_RIGHT_OUTER -> ":<";
                case T_FULL_OUTER -> ">:<";
                case T_LEFT_ANTI -> ">";
                case T_RIGHT_ANTI -> "<";
                case T_FULL_ANTI -> "><";
            };

            b.append(' ').append(typeStr).append(' ');

            mRightChild.appendGroupedTo(b);
        }
    }

    public static sealed class Column extends Node implements Source {
        private final Table mTable;
        private final ColumnInfo mColumn;

        Column(Table table, ColumnInfo column) {
            mTable = table;
            mColumn = column;
        }

        @Override
        public final Node accept(Visitor visitor) {
            return visitor.visit(this);
        }

        @Override
        public ColumnIterator columnIterator() {
            return new ColumnIterator() {
                Column mNext = Column.this;

                @Override
                public Column tryNext() {
                    Column next = mNext;
                    mNext = null;
                    return next;
                }
            };
        }

        @Override
        public void assignScore(FilterScorer fs, Set<String> available) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int compareScore(Source other) {
            return compareScore((Node) other);
        }

        @Override
        public long filterScore() {
            return 0;
        }

        @Override
        public Column copyWithScore() {
            return this;
        }

        public final Table table() {
            return mTable;
        }

        public final ColumnInfo column() {
            return mColumn;
        }

        /**
         * Returns a filter to apply when scanning the table. Is null or TrueFilter if no
         * filter should be applied.
         *
         * @see JoinPlanner
         */
        @Override
        public RowFilter filter() {
            return null;
        }

        /**
         * Replace the filter if supported.
         */
        public void filter(RowFilter filter) {
            throw new UnsupportedOperationException();
        }

        /**
         * Returns a remainder filter to apply to the join row before it can be accepted. Is
         * null or TrueFilter if no filter should be applied.
         *
         * @see JoinPlanner
         */
        public RowFilter remainder() {
            return null;
        }

        /**
         * Replace the remainder filter, if supported.
         */
        public void remainder(RowFilter remainder) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RowFilter predicate() {
            return null;
        }

        /**
         * Replace the predicate filter, if supported.
         */
        @Override
        public void predicate(RowFilter predicate) {
            throw new UnsupportedOperationException();
        }

        @Override
        final boolean matches(String name) {
            return startsWith(name, mColumn.name);
        }

        @Override
        final int numSources() {
            return 1;
        }

        @Override
        public final String name() {
            return column().name;
        }

        @Override
        public Map<Integer, ColumnInfo> argAssignments() {
            return null;
        }

        @Override
        public void addArgAssignment(Integer argNum, ColumnInfo column) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> argSources() {
            return null;
        }

        @Override
        public void addArgSource(String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public final Column toLeftJoin() {
            return this;
        }

        @Override
        boolean isEmpty() throws IOException {
            return mTable.isEmpty();
        }

        @Override
        void appendTo(StringBuilder b) {
            b.append(mColumn.name);
        }

        @Override
        final void appendGroupedTo(StringBuilder b) {
            // No need for parens around a single column.
            appendTo(b);
        }
    }

    /**
     * Represents a column which is ready for code generation.
     *
     * @see JoinPlanner
     */
    public static final class PlannedColumn extends Column implements Cloneable {
        private RowFilter mFilter, mRemainder, mPredicate;
        private Map<Integer, ColumnInfo> mArgAssignments;
        private Set<String> mArgSources;

        private KeyMatch mKeyMatch;

        // Is non-zero if primary key or alternate key is exactly matched. Higher is better.
        private int mKeyScore;

        private long mFilterScore;

        PlannedColumn(Column column) {
            super(column.mTable, column.mColumn);
        }

        @Override
        public void assignScore(FilterScorer fs, Set<String> available) {
            KeyMatch km = mKeyMatch;
            if (km == null) {
                ColumnInfo column = column();                
                mKeyMatch = km = KeyMatch.build(column.name + '.', RowInfo.find(column.type));
            }

            if (!km.hasPkColumns() && table() instanceof GroupedTable) {
                // Table has at most one row, and so it should be ordered first in the join.
                // Assign a score which is greater than what the KeyMatch.score method returns.
                mKeyScore = 3;
            } else {
                mKeyScore = km.score(mFilter);
            }

            mFilterScore = fs.calculate(mFilter, available);
        }

        @Override
        public int compareScore(Source other) {
            if (other instanceof PlannedColumn planned) {
                return compareScore((Node) planned);
            } else {
                return FilterScorer.compare(mFilterScore, other.filterScore());
            }
        }

        @Override
        public int compareScore(Node other) {
            if (this == other) {
                return 0;
            }

            if (!(other instanceof PlannedColumn planned)) {
                return super.compareScore(other);
            }

            int cmp = Integer.compare(mKeyScore, planned.mKeyScore);
            if (cmp != 0) {
                return cmp;
            }

            cmp = FilterScorer.compare(mFilterScore, planned.mFilterScore);
            if (cmp != 0) {
                return cmp;
            }

            // If either table is grouped (performs aggregatation), then it likely generates
            // fewer rows, and so it should be first in the join order.
            if (table() instanceof GroupedTable) {
                if (!(planned.table() instanceof GroupedTable)) {
                    return 1;
                }
            } else if (planned.table() instanceof GroupedTable) {
                return -1;
            }

            return cmp;
        }

        @Override
        public long filterScore() {
            return mFilterScore;
        }

        @Override
        public PlannedColumn copyWithScore() {
            try {
                return (PlannedColumn) clone();
            } catch (CloneNotSupportedException e) {
                throw RowUtils.rethrow(e);
            }
        }

        @Override
        public RowFilter filter() {
            return mFilter;
        }

        @Override
        public void filter(RowFilter filter) {
            mFilter = filter;
        }

        @Override
        public RowFilter remainder() {
            return mRemainder;
        }

        @Override
        public void remainder(RowFilter remainder) {
            mRemainder = remainder;
        }

        @Override
        public RowFilter predicate() {
            return mPredicate;
        }

        @Override
        public void predicate(RowFilter predicate) {
            mPredicate = predicate;
        }

        @Override
        public Map<Integer, ColumnInfo> argAssignments() {
            return mArgAssignments;
        }

        @Override
        public void addArgAssignment(Integer argNum, ColumnInfo column) {
            if (mArgAssignments == null) {
                mArgAssignments = new HashMap<>(4);
            }
            mArgAssignments.put(argNum, column);
        }

        @Override
        public Set<String> argSources() {
            return mArgSources;
        }

        @Override
        public void addArgSource(String source) {
            if (mArgSources == null) {
                mArgSources = new HashSet<>(4);
            }
            mArgSources.add(source);
        }
    }

    /**
     * Represents a group of nodes which can be reordered.
     */
    public static final class InnerJoins extends Node {
        private Node[] mNodes;
        private int mNumNodes;

        private RowFilter mPredicate;

        private JoinOp mJoinOp;

        InnerJoins(Node first, Node second) {
            mNodes = new Node[4];
            mNodes[0] = first;
            mNodes[1] = second;
            mNumNodes = 2;
        }

        InnerJoins(Node[] nodes, int num) {
            mNodes = nodes;
            mNumNodes = num;
        }

        @Override
        public Node accept(Visitor visitor) {
            return visitor.visit(this);
        }

        @Override
        public ColumnIterator columnIterator() {
            return new ColumnIterator() {
                ColumnIterator mIterator = mNodes[0].columnIterator();
                int mPos;

                @Override
                public Column tryNext() {
                    ColumnIterator it = mIterator;
                    while (true) {
                        Column next = it.tryNext();
                        if (next != null || mPos >= mNumNodes) {
                            return next;
                        }
                        mIterator = it = mNodes[mPos++].columnIterator();
                    }
                }
            };
        }

        @Override
        public void assignScore(FilterScorer fs, Set<String> available) {
            for (int i=0; i<mNumNodes; i++) {
                mNodes[i].assignScore(fs, available);
            }
        }

        @Override
        public RowFilter predicate() {
            RowFilter predicate = mPredicate;
            if (predicate == null) {
                for (int i=0; i<mNumNodes; i++) {
                    predicate = and(predicate, mNodes[i].predicate());
                }
                mPredicate = predicate;
            }
            return predicate;
        }

        @Override
        boolean matches(String name) {
            for (int i=0; i<mNumNodes; i++) {
                if (mNodes[i].matches(name)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        int numSources() {
            int num = 0;
            for (int i=0; i<mNumNodes; i++) {
                num += mNodes[i].numSources();
            }
            return num;
        }

        Node get(int i) {
            return mNodes[i];
        }

        int numNodes() {
            return mNumNodes;
        }

        Node[] copyNodes() {
            return Arrays.copyOf(mNodes, mNumNodes);
        }

        void add(Node c) {
            mJoinOp = null;

            if (mNumNodes >= mNodes.length) {
                mNodes = Arrays.copyOf(mNodes, mNodes.length << 1);
            }

            mNodes[mNumNodes++] = c;
        }

        void addFirst(Node c) {
            mJoinOp = null;

            if (mNumNodes < mNodes.length) {
                System.arraycopy(mNodes, 0, mNodes, 1, mNumNodes);
            } else {
                Node[] newNodes = new Node[mNodes.length << 1];
                System.arraycopy(mNodes, 0, newNodes, 1, mNumNodes);
                mNodes = newNodes;
            }

            mNodes[0] = c;
            mNumNodes++;
        }

        void addAll(InnerJoins joins) {
            mJoinOp = null;

            int newNum = mNumNodes + joins.mNumNodes;

            if (newNum >= mNodes.length) {
                int newCapacity = Math.max(newNum, mNodes.length << 1);
                mNodes = Arrays.copyOf(mNodes, newCapacity);
            }

            System.arraycopy(joins.mNodes, 0, mNodes, mNumNodes, joins.mNumNodes);

            mNumNodes = newNum;
        }

        public JoinOp toJoinOp() {
            JoinOp joinOp = mJoinOp;

            if (joinOp == null) {
                joinOp = new JoinOp(mNodes[0], mNodes[1], T_INNER);
                for (int i=2; i<mNumNodes; i++) {
                    joinOp = new JoinOp(joinOp, mNodes[i], T_INNER);
                }
                mJoinOp = joinOp;
            }

            return joinOp;
        }

        @Override
        public InnerJoins toLeftJoin() {
            Node[] nodes = mNodes;
            int num = mNumNodes;

            for (int i=0; i<num; i++) {
                Node node = nodes[i];
                Node converted = node.toLeftJoin();
                if (converted != node) {
                    if (nodes == mNodes) {
                        nodes = Arrays.copyOf(mNodes, num);
                    }
                    nodes[i] = converted;
                }
            }

            return nodes == mNodes ? this : new InnerJoins(nodes, num);
        }

        @Override
        boolean isEmpty() throws IOException {
            for (int i=0; i<mNumNodes; i++) {
                if (mNodes[i].isEmpty()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        void appendTo(StringBuilder b) {
            mNodes[0].appendTo(b);
            for (int i=1; i<mNumNodes; i++) {
                b.append(' ').append(':').append(' ');
                mNodes[i].appendGroupedTo(b);
            }
        }
    }

    /**
     * A full outer/anti join is wrapped such that it can be processed specially. A scanner for
     * a full join is implemented by a disjoint union of two other scanners, each with a
     * different plan.
     */
    public static final class FullJoin extends Node implements Source, Cloneable {
        private final String mName;
        private final JoinOp mRoot;

        private Map<Integer, ColumnInfo> mArgAssignments;
        private Set<String> mArgSources;
        private Map<String, Column> mColumnMap;
        private RowFilter mFilter, mPredicate;

        private long mFilterScore;

        /**
         * @param node expected to be a JoinOp of type T_FULL_OUTER or T_FULL_ANTI
         */
        private FullJoin(String name, Node node) {
            mName = name;
            mRoot = (JoinOp) node;
        }

        @Override
        public Node accept(Visitor visitor) {
            return visitor.visit(this);
        }

        @Override
        public SourceIterator sourceIterator() {
            return new SourceIterator() {
                FullJoin mNext = FullJoin.this;

                @Override
                public FullJoin tryNext() {
                    FullJoin next = mNext;
                    mNext = null;
                    return next;
                }
            };
        }

        @Override
        public ColumnIterator columnIterator() {
            return mRoot.columnIterator();
        }

        @Override
        public void assignScore(FilterScorer fs, Set<String> available) {
            mFilterScore = fs.calculate(mFilter, available);
        }

        @Override
        public int compareScore(Source other) {
            return FilterScorer.compare(mFilterScore, other.filterScore());
        }

        @Override
        public int compareScore(Node other) {
            if (this == other) {
                return 0;
            } else if (other instanceof FullJoin full) {
                return FilterScorer.compare(mFilterScore, full.mFilterScore);
            } else {
                return super.compareScore(other);
            }
        }

        @Override
        public long filterScore() {
            return mFilterScore;
        }

        @Override
        public FullJoin copyWithScore() {
            try {
                return (FullJoin) clone();
            } catch (CloneNotSupportedException e) {
                throw RowUtils.rethrow(e);
            }
        }

        @Override
        public FullJoin toLeftJoin() {
            return new FullJoin(mName, mRoot.toLeftJoin());
        }

        @Override
        public RowFilter predicate() {
            return mPredicate;
        }

        @Override
        public void predicate(RowFilter predicate) {
            if (predicate != null) {
                predicate = adjustOuterPredicate(predicate);
            }
            mPredicate = predicate;
        }

        @Override
        boolean matches(String name) {
            return mRoot.matches(name);
        }

        @Override
        int numSources() {
            return 1;
        }

        @Override
        public String name() {
            return mName;
        }

        @Override
        public Map<Integer, ColumnInfo> argAssignments() {
            return mArgAssignments;
        }

        @Override
        public void addArgAssignment(Integer argNum, ColumnInfo column) {
            if (mArgAssignments == null) {
                mArgAssignments = new HashMap<>(4);
            }
            mArgAssignments.put(argNum, column);
        }

        @Override
        public Set<String> argSources() {
            return mArgSources;
        }

        @Override
        public void addArgSource(String source) {
            if (mArgSources == null) {
                mArgSources = new HashSet<>(4);
            }
            mArgSources.add(source);
        }

        public int type() {
            return mRoot.type();
        }

        /**
         * Returns a new unplanned JoinSpec instance.
         */
        public JoinSpec toSpec() {
            return new JoinSpec(mRoot);
        }

        public Map<String, Column> columnMap() {
            Map<String, Column> map = mColumnMap;

            if (map == null) {
                map = new HashMap<>();
                JoinSpec.ColumnIterator it = columnIterator();
                for (JoinSpec.Column column; (column = it.tryNext()) != null; ) {
                    map.put(column.name(), column);
                }
                mColumnMap = map;
            }

            return map;
        }

        /**
         * Returns a filter which applies to the full join. Is null or TrueFilter if no filter
         * should be applied.
         *
         * @see JoinPlanner
         */
        @Override
        public RowFilter filter() {
            return mFilter;
        }

        /**
         * Replace the filter.
         */
        public void filter(RowFilter filter) {
            mFilter = filter;
        }

        public FullJoin copyWith(Visitor visitor) {
            return new FullJoin(mName, mRoot.accept(visitor));
        }

        JoinSpec[] split() {
            return mRoot.splitFullJoin();
        }

        @Override
        boolean isEmpty() throws IOException {
            return mRoot.isEmpty();
        }

        @Override
        void appendTo(StringBuilder b) {
            mRoot.appendTo(b);
        }
    }

    private static final class Parser extends SimpleParser {
        private final Map<String, ColumnInfo> mAllColumns;
        private final Set<String> mAvailableNames;
        private final Table[] mTables;
        private final Database mDb;

        private int mNumTables;

        private int mFullOrdinal;

        /**
         * @param db optional; pass a database instance to open tables automatically
         */
        Parser(Map<String, ColumnInfo> allColumns, String spec, Table[] tables, Database db) {
            super(spec);
            mAllColumns = allColumns;
            mAvailableNames = new HashSet<>(allColumns.keySet());
            mTables = tables;
            mDb = db;
        }

        /**
         * @throws IOException if a database instance was provided and openTable failed
         */
        Node parse() throws IOException {
            Node root = doParse();

            if (mPos < mText.length()) {
                throw error("Unexpected trailing characters");
            }

            return root;
        }

        private Node doParse() throws IOException {
            Node root = parseSource();

            for (int c; (c = nextCharIgnoreWhitespace()) >= 0; ) {
                int type = tryParseType(c);

                if (type < 0) {
                    break;
                }

                Node right = parseSource();

                if (type != T_INNER) {
                    root = new JoinOp(root, right, type);
                    if (isFullJoin(type)) {
                        root = new FullJoin(String.valueOf(++mFullOrdinal).intern(), root);
                    }
                } else if (root instanceof InnerJoins j1) {
                    if (right instanceof InnerJoins j2) {
                        j1.addAll(j2);
                    } else {
                        j1.add(right);
                    }
                } else if (right instanceof InnerJoins j2) {
                    j2.addFirst(root);
                    root = j2;
                } else {
                    root = new InnerJoins(root, right);
                }
            }

            return root;
        }

        private Node parseSource() throws IOException {
            int c = nextCharIgnoreWhitespace();

            if (c == '(') {
                return parseParenSource();
            }

            // parseColumn

            if (c < 0) {
                throw error("Column name expected");
            }

            if (!Character.isJavaIdentifierStart(c)) {
                mPos--;
                throw error("Not a valid character for start of column name: '" + (char) c + '\'');
            }

            int start = mPos - 1;

            do {
                c = nextChar();
            } while (Character.isJavaIdentifierPart(c));

            String name = mText.substring(start, --mPos);
            ColumnInfo column = mAllColumns.get(name);

            if (column == null) {
                mPos = start;
                throw error("Unknown column");
            }

            if (!mAvailableNames.remove(name)) {
                mPos = start;
                throw error("Duplicate column");
            }

            Table table;

            if (mTables == null) {
                table = mDb == null ? null : mDb.openTable(column.type);
            } else if (mNumTables >= mTables.length) {
                throw new IllegalArgumentException("Not enough tables provided");
            } else {
                table = mTables[mNumTables];
                if (column.type != table.rowType()) {
                    throw new IllegalArgumentException
                        ("Column type doesn't match table type: " + column.type.getName() + " != " +
                         table.rowType().getName() + " (for specified column \"" + name + "\")");
                }
            }

            mNumTables++;

            return new Column(table, column);
        }

        /**
         * @param c must not be -1
         * @return -1 if no type
         */
        private int tryParseType(int c) {
            final int type;
            final int start = mPos;

            switch (c) {
            case ':':
                c = nextChar();
                if (c == ':') {
                    type = T_STRAIGHT;
                } else if (c == '<') {
                    type = T_RIGHT_OUTER;
                } else {
                    mPos--;
                    type = T_INNER;
                }
                break;
            case '>':
                c = nextChar();
                if (c == ':') {
                    c = nextChar();
                    if (c == '<') {
                        type = T_FULL_OUTER;
                    } else {
                        mPos--;
                        type = T_LEFT_OUTER;
                    }
                } else if (c == '<') {
                    type = T_FULL_ANTI;
                } else {
                    mPos--;
                    type = T_LEFT_ANTI;
                }
                break;
            case '<':
                type = T_RIGHT_ANTI;
                break;
            case ')':
                mPos--;
                return -1;
            default:
                mPos--;
                String message;
                if (c == '(' || Character.isLetterOrDigit(c)) {
                    message = "Join operator expected";
                } else {
                    message = "Unknown join operator";
                }
                throw error(message);
            }

            c = nextCharIgnoreWhitespace();

            if (c < 0 || c == '(' || Character.isJavaIdentifierStart(c)) {
                mPos--;
            } else {
                mPos = start - 1;
                throw error("Unknown join operator");
            }

            return type;
        }

        // Left paren has already been consumed.
        private Node parseParenSource() throws IOException {
            Node node = doParse();
            if (nextCharIgnoreWhitespace() != ')') {
                mPos--;
                throw error("Right paren expected");
            }
            return node;
        }
 
        @Override
        protected String describe() {
            return "join specification";
        }
    }
}
