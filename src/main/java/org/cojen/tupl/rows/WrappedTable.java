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

import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.Scanner;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;

/**
 * @param <S> source row type
 * @param <T> target row type
 * @author Brian S. O'Neill
 */
public abstract class WrappedTable<S, T> implements Table<T> {
    protected final Table<S> mSource;

    protected WrappedTable(Table<S> source) {
        mSource = source;
    }

    @Override
    public Scanner<T> newScanner(Transaction txn) throws IOException {
        return newScannerWith(txn, null);
    }

    @Override
    public Scanner<T> newScanner(Transaction txn, String query, Object... args)
        throws IOException
    {
        return newScannerWith(txn, null, query, args);
    }

    @Override
    public Transaction newTransaction(DurabilityMode durabilityMode) {
        return mSource.newTransaction(durabilityMode);
    }

    @Override
    public boolean isEmpty() throws IOException {
        return mSource.isEmpty() || !anyRows(Transaction.BOGUS);
    }

    @Override
    public void close() throws IOException {
        // Do nothing.
    }

    @Override
    public boolean isClosed() {
        return false;
    }
}