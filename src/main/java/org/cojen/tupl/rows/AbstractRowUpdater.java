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

import org.cojen.tupl.Cursor;
import org.cojen.tupl.RowUpdater;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.View;

/**
 * 
 *
 * @author Brian S O'Neill
 */
abstract class AbstractRowUpdater<R> extends AbstractRowScanner<R> implements RowUpdater<R> {
    AbstractRowUpdater(View source, Transaction txn) {
        super(source, txn);
    }

    @Override
    protected Cursor initCursor() throws IOException {
        Cursor c = mView.newCursor(mTxn);
        c.first();
        try {
            c.register();
        } catch (Throwable e) {
            c.reset();
            throw e;
        }
        return c;
    }
}
