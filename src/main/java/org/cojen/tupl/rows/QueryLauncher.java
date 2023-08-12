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

import java.io.Closeable;
import java.io.IOException;

import org.cojen.tupl.Scanner;
import org.cojen.tupl.Updater;
import org.cojen.tupl.Transaction;

import org.cojen.tupl.diag.QueryPlan;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface QueryLauncher<R> extends Closeable {
    /**
     * @param row initial row; can be null
     */
    Scanner<R> newScannerWith(Transaction txn, R row, Object... args) throws IOException;

    /**
     * @param row initial row; can be null
     */
    Updater<R> newUpdaterWith(Transaction txn, R row, Object... args) throws IOException;

    /**
     * Scan and write rows to a remote endpoint.
     */
    void scanWrite(Transaction txn, RowWriter writer, Object... args) throws IOException;

    QueryPlan scannerPlan(Transaction txn, Object... args);

    QueryPlan updaterPlan(Transaction txn, Object... args);
}
