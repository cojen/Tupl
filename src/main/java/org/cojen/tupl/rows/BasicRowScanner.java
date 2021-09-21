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

import java.util.Objects;

import org.cojen.tupl.Cursor;
import org.cojen.tupl.LockResult;
import org.cojen.tupl.RowScanner;
import org.cojen.tupl.UnpositionedCursorException;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class BasicRowScanner<R> implements RowScanner<R> {
    final Cursor mCursor;
    final RowDecoderEncoder<R> mDecoder;
    R mRow;

    BasicRowScanner(Cursor cursor, RowDecoderEncoder<R> decoder) {
        mCursor = cursor;
        mDecoder = decoder;
    }

    /**
     * Must be called after construction.
     */
    final void init() throws IOException {
        Cursor c = mCursor;
        LockResult result = toFirst(c);
        while (true) {
            byte[] key = c.key();
            if (key == null) {
                break;
            }
            try {
                R decoded = mDecoder.decodeRow(key, c, null);
                if (decoded != null) {
                    mRow = decoded;
                    return;
                }
            } catch (Throwable e) {
                throw RowUtils.fail(this, e);
            }
            if (result == LockResult.ACQUIRED) {
                c.link().unlock();
                unlocked();
            }
            result = toNext(c);
        }
        finished();
    }

    @Override
    public final R row() {
        return mRow;
    }

    @Override
    public final R step() throws IOException {
        return doStep(null);
    }

    @Override
    public final R step(R row) throws IOException {
        Objects.requireNonNull(row);
        return doStep(row);
    }

    protected final R doStep(R row) throws IOException {
        Cursor c = mCursor;
        try {
            while (true) {
                LockResult result = toNext(c);
                byte[] key = c.key();
                if (key == null) {
                    break;
                }
                R decoded = decodeRow(key, c, row);
                if (decoded != null) {
                    mRow = decoded;
                    return decoded;
                }
                if (result == LockResult.ACQUIRED) {
                    c.link().unlock();
                    unlocked();
                }
            }
        } catch (UnpositionedCursorException e) {
        } catch (Throwable e) {
            throw RowUtils.fail(this, e);
        }
        finished();
        return null;
    }

    protected R decodeRow(byte[] key, Cursor c, R row) throws IOException {
        return mDecoder.decodeRow(key, c, row);
    }

    @Override
    public final void close() throws IOException {
        finished();
        mCursor.reset();
    }

    protected LockResult toFirst(Cursor c) throws IOException {
        return c.first();
    }

    protected LockResult toNext(Cursor c) throws IOException {
        return c.next();
    }

    protected void unlocked() {
    }

    protected void finished() throws IOException {
        mRow = null;
    }
}