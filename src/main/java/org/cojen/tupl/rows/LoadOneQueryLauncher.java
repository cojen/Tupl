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

package org.cojen.tupl.rows;

import java.io.IOException;

import org.cojen.tupl.Scanner;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.Updater;
import org.cojen.tupl.View;

import org.cojen.tupl.diag.QueryPlan;

/**
 * Optimized QueryLauncher for loading at most one row. Update isn't optimmized.
 *
 * @author Brian S O'Neill
 */
public final class LoadOneQueryLauncher<R> extends ScanQueryLauncher<R> {
    LoadOneQueryLauncher(BaseTable<R> table, ScanControllerFactory<R> factory) {
        super(table, factory);
    }

    @Override
    public Scanner<R> newScannerWith(Transaction txn, R row, Object... args) throws IOException {
        return new LoadOneScanner<>(mTable.mSource, txn, mFactory.scanController(args), row);
    }

    @Override
    public void scanWrite(Transaction txn, RowWriter writer, Object... args) throws IOException {
        // The one row is written indirectly by the constructor.
        new LoadOneScanner<>(writer, mTable.mSource, txn, mFactory.scanController(args));
    }

    @Override
    public QueryPlan scannerPlan(Transaction txn, Object... args) {
        return mFactory.loadOnePlan(args);
    }
}
