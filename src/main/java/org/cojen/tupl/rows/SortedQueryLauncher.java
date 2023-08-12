/*
 *  Copyright (C) 2022 Cojen.org
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

package org.cojen.tupl.rows;

import java.io.IOException;

import java.lang.invoke.MethodHandle;

import java.util.Comparator;
import java.util.Set;

import org.cojen.tupl.LockMode;
import org.cojen.tupl.Scanner;
import org.cojen.tupl.Updater;
import org.cojen.tupl.Transaction;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.util.Canonicalizer;

import static java.util.Spliterator.*;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see RowSorter
 */
final class SortedQueryLauncher<R> implements QueryLauncher<R> {
    private static volatile Canonicalizer cProjectionCache;

    /**
     * Returns a cached instance, to reduce the memory footprint of SortedQueryLauncher
     * instances, which can be long lived.
     */
    static Set<String> canonicalize(Set<String> projection) {
        if (projection == null) {
            return null;
        }

        Canonicalizer c = cProjectionCache;

        if (c == null) {
            synchronized (SortedQueryLauncher.class) {
                if ((c = cProjectionCache) == null) {
                    cProjectionCache = c = new Canonicalizer();
                }
            }
        }

        return c.apply(Set.of(projection.toArray(String[]::new)));
    }

    final BaseTable<R> mTable;
    final QueryLauncher<R> mSource;
    final Set<String> mProjection;
    final String mSpec;
    final Comparator<R> mComparator;

    // These fields are assigned by RowSorter.
    SecondaryInfo mSortedInfo;
    RowDecoder<R> mDecoder;
    MethodHandle mWriteRow;

    /**
     * @param projection can be null to indicate all columns
     */
    SortedQueryLauncher(BaseTable<R> table, QueryLauncher<R> source,
                        Set<String> projection, OrderBy orderBy)
    {
        mTable = table;
        mSource = source;
        mProjection = canonicalize(projection);
        mSpec = orderBy.spec();
        mComparator = table.comparator(mSpec);
    }

    @Override
    public Scanner<R> newScannerWith(Transaction txn, R row, Object... args) throws IOException {
        return RowSorter.sort(this, txn, args);
    }

    /**
     * @see MappedTable.newWrappedUpdater
     */
    @Override
    public Updater<R> newUpdaterWith(Transaction txn, R row, Object... args) throws IOException {
        if (txn != null) {
            if (txn.lockMode() != LockMode.UNSAFE) {
                txn.enter();
            }

            Scanner<R> scanner;
            try {
                scanner = newScannerWith(txn, row, args);
                // Commit the transaction scope to promote and keep all the locks which were
                // acquired by the sort operation.
                txn.commit();
            } finally {
                txn.exit();
            }

            return new WrappedUpdater<>(mTable, txn, scanner);
        }

        // Need to create a transaction to acquire locks, but true auto-commit behavior isn't
        // really feasible because update order won't match lock acquisition order. In
        // particular, the locks cannot be released out of order. Instead, keep the transaction
        // open until the updater finishes and always commit. Unfortunately, if the commit
        // fails then all updates fail instead of just one.

        txn = mTable.mSource.newTransaction(null);

        Scanner<R> scanner;
        try {
            scanner = newScannerWith(txn, row, args);
        } catch (Throwable e) {
            txn.exit();
            throw e;
        }

        return new WrappedUpdater.EndCommit<>(mTable, txn, scanner);
    }

    @Override
    public void scanWrite(Transaction txn, RowWriter writer, Object... args) throws IOException {
        writer.writeCharacteristics(NONNULL | ORDERED | IMMUTABLE | SORTED, 0);

        RowSorter.sortWrite(this, writer, txn, args);
    }

    @Override
    public QueryPlan scannerPlan(Transaction txn, Object... args) {
        return new QueryPlan.Sort(OrderBy.splitSpec(mSpec), mSource.scannerPlan(txn, args));
    }

    @Override
    public QueryPlan updaterPlan(Transaction txn, Object... args) {
        return new QueryPlan.Sort(OrderBy.splitSpec(mSpec), mSource.updaterPlan(txn, args));
    }

    @Override
    public void close() throws IOException {
        mSource.close();
        mTable.close();
    }
}
