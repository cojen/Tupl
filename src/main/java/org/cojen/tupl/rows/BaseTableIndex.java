/*
 *  Copyright (C) 2021 Cojen.org
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

import org.cojen.tupl.Index;
import org.cojen.tupl.Scanner;
import org.cojen.tupl.Updater;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.UnmodifiableViewException;

import org.cojen.tupl.core.RowPredicateLock;

import org.cojen.tupl.diag.QueryPlan;

/**
 * Base class for the tables returned by viewAlternateKey and viewSecondaryIndex.
 *
 * @author Brian S O'Neill
 */
public abstract class BaseTableIndex<R> extends BaseTable<R> {
    protected BaseTableIndex(TableManager<R> manager,
                             Index source, RowPredicateLock<R> indexLock)
    {
        super(manager, source, indexLock);
    }

    @Override
    boolean isEvolvable() {
        return false;
    }

    @Override
    boolean supportsSecondaries() {
        return false;
    }

    @Override
    public void store(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    @Override
    public R exchange(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    @Override
    public boolean insert(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    @Override
    public boolean replace(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    @Override
    public boolean update(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    @Override
    public boolean merge(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    @Override
    public boolean delete(Transaction txn, R row) throws IOException {
        throw new UnmodifiableViewException();
    }

    @Override
    protected BaseTableIndex<R> viewAlternateKey(String... columns) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    protected BaseTableIndex<R> viewSecondaryIndex(String... columns) throws IOException {
        throw new IllegalStateException();
    }

    @Override
    public Scanner<R> newScannerWith(Transaction txn, R row, String filter, Object... args)
        throws IOException
    {
        return newScannerThisTable(txn, row, filter, args);
    }

    @Override
    protected Updater<R> newUpdaterWith(Transaction txn, R row, String filter, Object... args)
        throws IOException
    {
        // By default, this will throw an UnmodifiableViewException. See below.
        return newUpdaterThisTable(txn, row, filter, args);
    }

    @Override
    protected Updater<R> newUpdaterWith(Transaction txn, R row, ScanController<R> controller)
        throws IOException
    {
        throw new UnmodifiableViewException();
    }

    protected Updater<R> newJoinedUpdater(Transaction txn, R row,
                                          ScanController<R> controller,
                                          BaseTable<R> primaryTable)
        throws IOException
    {
        return primaryTable.newUpdaterWith(txn, row, controller, this);
    }

    @Override
    public QueryPlan scannerPlan(Transaction txn, String filter, Object... args) {
        return scannerPlanThisTable(txn, filter, args);
    }

    @Override
    public QueryPlan updaterPlan(Transaction txn, String filter, Object... args) {
        return scannerPlan(txn, filter, args);
    }
}
