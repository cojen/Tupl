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

package org.cojen.tupl;

import java.io.Closeable;
import java.io.IOException;

import java.util.Comparator;

import java.util.function.Predicate;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.io.Utils;

import org.cojen.tupl.rows.ComparatorMaker;
import org.cojen.tupl.rows.MappedTable;
import org.cojen.tupl.rows.PlainPredicateMaker;

import org.cojen.tupl.rows.join.JoinTableMaker;

/**
 * Defines a relational collection of persistent rows. A row is defined by an interface
 * consisting of accessor/mutator methods corresponding to each column:
 *
 * {@snippet lang="java" :
 * @PrimaryKey("id")
 * public interface MyRow {
 *     long id();
 *     void id(long id);
 *
 *     String name();
 *     void name(String str);
 *
 *     String message();
 *     void message(String str);
 * }
 * }
 *
 * <p>Supported column types:
 * <ul>
 * <li>Simple objects — {@code String}, {@code BigInteger}, and {@code BigDecimal}
 * <li>Primitives — {@code int}, {@code double}, etc
 * <li>Boxed primitives — {@code Integer}, {@code Double}, etc
 * <li>Primitive arrays — {@code int[]}, {@code double[]}, etc
 * </ul>
 *
 * By default, object columns cannot be set to null, and attempting to do so causes an {@code
 * IllegalArgumentException} to be thrown. The column definition must be annotated with {@link
 * Nullable @Nullable} to support nulls.
 *
 * <p>The actual row implementation class is generated at runtime, and the standard {@code
 * equals}, {@code hashCode}, {@code toString}, and {@code clone} methods are automatically
 * generated as well. If the row interface declares a {@code clone} method which returns the
 * exact row type, then the row can be cloned without requiring an explicit cast. If the row
 * interface extends {@link Comparable}, then rows are comparable by primary key. Any default
 * methods defined in the row interface are never overridden by the generated class, unless the
 * method is defined in the {@link Object} class.
 *
 * <p>Scans over the rows of the table can be reduced by a query, described by this syntax:
 *
 * <blockquote><pre>{@code
 * Query        = RowFilter
 *              | Projection [ RowFilter ]
 * RowFilter    = AndFilter { "||" AndFilter }
 * AndFilter    = EntityFilter { "&&" EntityFilter }
 * EntityFilter = ColumnFilter | ParenFilter
 * ParenFilter  = [ "!" ] "(" RowFilter ")"
 * ColumnFilter = ColumnName RelOp ( ArgRef | ColumnName )
 *              | ColumnName "in" ArgRef
 * RelOp        = "==" | "!=" | ">=" | "<" | "<=" | ">"
 * Projection   = "{" ProjColumns "}"
 * ProjColumns  = [ ProjColumn { "," ProjColumn } ]
 * ProjColumn   = ( ( ( ( "+" | "-" ) [ "!" ] ) | "~" ) ColumnName ) | "*"
 * ColumnName   = string
 * ArgRef       = "?" [ uint ]
 * }</pre></blockquote>
 *
 * @author Brian S O'Neill
 * @see Database#openTable Database.openTable
 * @see PrimaryKey
 */
public interface Table<R> extends Closeable {
    /**
     * Returns the interface which defines the rows of this table.
     */
    public Class<R> rowType();

    /**
     * Returns a new row instance with unset columns.
     */
    public R newRow();

    /**
     * Returns a new row instance which is an exact copy of the given row.
     */
    public R cloneRow(R row);

    /**
     * Resets the state of the given row such that all columns are unset.
     */
    public void unsetRow(R row);

    /**
     * Copies all columns and states from one row to another.
     */
    public void copyRow(R from, R to);

    /**
     * Returns a new scanner for all rows of this table.
     *
     * @param txn optional transaction for the scanner to use; pass null for auto-commit mode
     * @return a new scanner positioned at the first row in the table
     * @throws IllegalStateException if transaction belongs to another database instance
     * @see #scannerPlan scannerPlan
     */
    public Scanner<R> newScanner(Transaction txn) throws IOException;

    /**
     * @hidden
     */
    public Scanner<R> newScannerWith(Transaction txn, R row) throws IOException;

    /**
     * Returns a new scanner for a subset of rows from this table, as specified by the query
     * expression.
     *
     * @param txn optional transaction for the scanner to use; pass null for auto-commit mode
     * @return a new scanner positioned at the first row in the table accepted by the query
     * @throws IllegalStateException if transaction belongs to another database instance
     * @see #scannerPlan scannerPlan
     */
    public Scanner<R> newScanner(Transaction txn, String query, Object... args)
        throws IOException;

    /**
     * @hidden
     */
    public Scanner<R> newScannerWith(Transaction txn, R row, String query, Object... args)
        throws IOException;

    /**
     * Returns a new updater for all rows of this table.
     *
     * <p>When providing a transaction which acquires locks (or the transaction is null),
     * upgradable locks are acquired for each row visited by the updater. If the transaction
     * lock mode is non-repeatable, any lock acquisitions for rows which are stepped over are
     * released when moving to the next row. Updates with a null transaction are auto-committed
     * and become visible to other transactions as the updater moves along.
     *
     * @param txn optional transaction for the updater to use; pass null for auto-commit mode
     * @return a new updater positioned at the first row in the table
     * @throws IllegalStateException if transaction belongs to another database instance
     * @see #updaterPlan updaterPlan
     */
    public default Updater<R> newUpdater(Transaction txn) throws IOException {
        throw new UnmodifiableViewException();
    }

    /**
     * Returns a new updater for a subset of rows from this table, as specified by the query
     * expression.
     *
     * <p>When providing a transaction which acquires locks (or the transaction is null),
     * upgradable locks are acquired for each row visited by the updater. If the transaction
     * lock mode is non-repeatable, any lock acquisitions for rows which are stepped over are
     * released when moving to the next row. Updates with a null transaction are auto-committed
     * and become visible to other transactions as the updater moves along.
     *
     * @param txn optional transaction for the updater to use; pass null for auto-commit mode
     * @return a new updater positioned at the first row in the table accepted by the query
     * @throws IllegalStateException if transaction belongs to another database instance
     * @see #updaterPlan updaterPlan
     */
    public default Updater<R> newUpdater(Transaction txn, String query, Object... args)
        throws IOException
    {
        throw new UnmodifiableViewException();
    }

    /**
     * Returns a new stream for all rows of this table. The stream must be explicitly closed
     * when no longer used, or else it must be used with a try-with-resources statement. If an
     * underlying {@code IOException} is generated, it's thrown as if it was unchecked.
     *
     * @param txn optional transaction for the stream to use; pass null for auto-commit mode
     * @return a new stream positioned at the first row in the table
     * @throws IllegalStateException if transaction belongs to another database instance
     * @see #streamPlan streamPlan
     */
    public default Stream<R> newStream(Transaction txn) {
        try {
            return newStream(newScanner(txn));
        } catch (IOException e) {
            throw Utils.rethrow(e);
        }
    }

    /**
     * Returns a new stream for a subset of rows from this table, as specified by the query
     * expression. The stream must be explicitly closed when no longer used, or else it must be
     * used with a try-with-resources statement. If an underlying {@code IOException} is
     * generated, it's thrown as if it was unchecked.
     *
     * @param txn optional transaction for the stream to use; pass null for auto-commit mode
     * @return a new stream positioned at the first row in the table accepted by the query
     * @throws IllegalStateException if transaction belongs to another database instance
     * @see #streamPlan streamPlan
     */
    public default Stream<R> newStream(Transaction txn, String query, Object... args) {
        try {
            return newStream(newScanner(txn, query, args));
        } catch (IOException e) {
            throw Utils.rethrow(e);
        }
    }

    /**
     * Returns true if any rows exist in this table.
     *
     * @param txn optional transaction to use; pass null for auto-commit mode
     * @throws IllegalStateException if transaction belongs to another database instance
     * @see #isEmpty
     */
    public default boolean anyRows(Transaction txn) throws IOException {
        // FIXME: Subclasses should provide an optimized implementation.
        return anyRowsWith(txn, null);
    }

    /**
     * @hidden
     */
    public default boolean anyRowsWith(Transaction txn, R row) throws IOException {
        // FIXME: Subclasses should provide an optimized implementation.
        Scanner<R> s = newScannerWith(txn, row);
        boolean result = s.row() != null;
        s.close();
        return result;
    }

    /**
     * Returns true if a subset of rows from this table exists, as specified by the query
     * expression.
     *
     * @param txn optional transaction to use; pass null for auto-commit mode
     * @throws IllegalStateException if transaction belongs to another database instance
     */
    public default boolean anyRows(Transaction txn, String query, Object... args)
        throws IOException
    {
        // FIXME: Subclasses should provide an optimized implementation.
        return anyRowsWith(txn, null, query, args);
    }

    /**
     * @hidden
     */
    public default boolean anyRowsWith(Transaction txn, R row, String query, Object... args)
        throws IOException
    {
        // FIXME: Subclasses should provide an optimized implementation.
        Scanner<R> s = newScannerWith(txn, row, query, args);
        boolean result = s.row() != null;
        s.close();
        return result;
    }

    private static <R> Stream<R> newStream(Scanner<R> scanner) {
        return StreamSupport.stream(scanner, false).onClose(() -> {
            try {
                scanner.close();
            } catch (Throwable e) {
                Utils.rethrow(e);
            }
        });
    }

    /**
     * Returns a new transaction which is compatible with this table. If the provided durability
     * mode is null, a default mode is selected.
     */
    public Transaction newTransaction(DurabilityMode durabilityMode);

    /**
     * Non-transactionally determines if the table has nothing in it. A return value of true
     * guarantees that the table is empty, but false negatives are possible.
     *
     * @see #anyRows(Transaction)
     */
    public boolean isEmpty() throws IOException;

    /**
     * Fully loads the row by primary key.
     *
     * @return false if a corresponding row doesn't exist
     * @throws IllegalStateException if primary key isn't fully specified
     */
    public boolean load(Transaction txn, R row) throws IOException;

    /**
     * Checks if a row exists by searching against the primary key. This method should be
     * called only if the row doesn't need to be loaded or stored &mdash; calling exists and
     * then calling a load or store method is typically less efficient than skipping the exists
     * check entirely.
     *
     * @return false if a corresponding row doesn't exist
     * @throws IllegalStateException if primary key isn't fully specified
     */
    public boolean exists(Transaction txn, R row) throws IOException;

    /**
     * Unconditionally stores the given row, potentially replacing a corresponding row which
     * already exists.
     *
     * @throws IllegalStateException if any required columns aren't set
     * @throws UniqueConstraintException if a conflicting alternate key exists
     */
    public default void store(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    /**
     * Unconditionally stores the given row, potentially replacing a corresponding row which
     * already exists.
     *
     * @return a copy of the replaced row, or null if none existed
     * @throws IllegalStateException if any required columns aren't set
     * @throws UniqueConstraintException if a conflicting alternate key exists
     */
    public default R exchange(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    /**
     * Stores the given row when a corresponding row doesn't exist.
     *
     * @return false if a corresponding row already exists and nothing was inserted
     * @throws IllegalStateException if any required columns aren't set
     * @throws UniqueConstraintException if a conflicting alternate key exists
     */
    public default boolean insert(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    /**
     * Stores the given row when a corresponding row already exists.
     *
     * @return false if a corresponding row doesn't exist
     * @throws IllegalStateException if any required columns aren't set
     * @throws UniqueConstraintException if a conflicting alternate key exists
     */
    public default boolean replace(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    /**
     * Updates an existing row with the modified columns of the given row, but the resulting
     * row isn't loaded back.
     *
     * @return false if a corresponding row doesn't exist
     * @throws IllegalStateException if primary key isn't fully specified
     * @throws UniqueConstraintException if a conflicting alternate key exists
     */
    public default boolean update(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    /**
     * Updates an existing row with the modified columns of the given row, and then loads the
     * result back into the given row.
     *
     * @return false if a corresponding row doesn't exist
     * @throws IllegalStateException if primary key isn't fully specified
     * @throws UniqueConstraintException if a conflicting alternate key exists
     */
    public default boolean merge(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    /**
     * Unconditionally removes an existing row by primary key.
     *
     * @return false if a corresponding row doesn't exist
     * @throws IllegalStateException if primary key isn't fully specified
     */
    public default boolean delete(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    /**
     * Returns a view backed by this table, whose rows have been mapped to target rows. The
     * returned table instance will throw a {@link ViewConstraintException} for operations
     * against rows not supported by the mapper, and closing the table has no effect.
     *
     * @throws NullPointerException if the given mapper is null
     */
    public default <T> Table<T> map(Class<T> targetType, Mapper<R, T> mapper) {
        return MappedTable.<R, T>map(this, targetType, mapper);
    }

    /**
     * Joins tables together into an unmodifiable view. The view doesn't have any primary key,
     * and so operations which act upon one aren't supported. In addition, closing the view
     * doesn't have any effect.
     *
     * <p>The {@code joinType} parameter is a class which resembles an ordinary row definition
     * except that all columns must refer to other row types. Annotations for defining keys and
     * indexes is unsupported.
     *
     * @param spec join specification
     * @throws NullPointerException if any parameters are null
     * @throws IllegalArgumentException if join type is malformed, or if the specification is
     * malformed, or if there are any table matching issues
     */
    public static <J> Table<J> join(Class<J> joinType, String spec, Table<?>... tables) {
        return JoinTableMaker.join(joinType, spec, tables);
    }

    /**
     * Returns a row comparator based on the given specification, which defines the ordering
     * columns. Each column name is prefixed with '+' or '-', to indicate ascending or
     * descending order. For example: {@code "+lastName+firstName-birthdate"}. By default,
     * nulls are treated as higher non-nulls, but a '!' after the '+'/'-' character causes
     * nulls to be treated as lower than non-nulls.
     *
     * @throws IllegalArgumentException if the specification is malformed
     * @throws IllegalStateException if the specification refers to non-existent columns
     */
    public default Comparator<R> comparator(String spec) {
        return ComparatorMaker.comparator(rowType(), spec);
    }

    /**
     * Returns a row predicate for the given query expression and arguments.
     */
    public default Predicate<R> predicate(String query, Object... args) {
        return PlainPredicateMaker.predicate(rowType(), query, args);
    }

    /**
     * Returns a query plan used by {@link #newScanner(Transaction, String, Object...)
     * newScanner}.
     *
     * @param txn optional transaction to be used; pass null for auto-commit mode
     * @param query optional query expression
     * @param args optional query arguments
     */
    public QueryPlan scannerPlan(Transaction txn, String query, Object... args) throws IOException;

    /**
     * Returns a query plan used by {@link #newUpdater(Transaction, String, Object...)
     * newUpdater}.
     *
     * @param txn optional transaction to be used; pass null for auto-commit mode
     * @param query optional query expression
     * @param args optional query arguments
     */
    public default QueryPlan updaterPlan(Transaction txn, String query, Object... args)
        throws IOException
    {
        throw new UnmodifiableViewException();
    }

    /**
     * Returns a query plan used by {@link #newStream(Transaction, String, Object...)
     * newStream}.
     *
     * @param txn optional transaction to be used; pass null for auto-commit mode
     * @param query optional query expression
     * @param args optional query arguments
     */
    public default QueryPlan streamPlan(Transaction txn, String query, Object... args)
        throws IOException
    {
        return scannerPlan(txn, query, args);
    }

    /**
     * @see Index#close
     */
    @Override
    public void close() throws IOException;

    public boolean isClosed();
}
