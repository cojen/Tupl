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

import org.cojen.tupl.Mapper;
import org.cojen.tupl.Scanner;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see MappedTable
 */
public class MappedScanner<S, T> implements Scanner<T> {
    protected final MappedTable<S, T> mMappedTable;
    protected final Scanner<S> mSource;
    protected final Mapper<S, T> mMapper;

    protected T mTargetRow;

    public MappedScanner(MappedTable<S, T> mappedTable, Scanner<S> source, T targetRow,
                         Mapper<S, T> mapper)
        throws IOException
    {
        mMappedTable = mappedTable;
        mSource = source;
        mMapper = mapper;

        try {
            S sourceRow = source.row();

            if (sourceRow != null) {
                targetRow = prepareTargetRow(targetRow);
                T mappedTargetRow = mMapper.map(sourceRow, targetRow);
                if (mappedTargetRow != null) {
                    mMappedTable.cleanRow(mappedTargetRow);
                    mTargetRow = mappedTargetRow;
                } else {
                    step(targetRow);
                }
            }
        } catch (Throwable e) {
            try {
                close();
            } catch (Throwable e2) {
                RowUtils.suppress(e, e2);
            }
            throw e;
        }
    }

    @Override
    public final T row() {
        return mTargetRow;
    }

    @Override
    public final T step(T targetRow) throws IOException {
        try {
            Scanner<S> source = mSource;
            S sourceRow = source.row();

            while (true) {
                sourceRow = source.step(sourceRow);
                if (sourceRow == null) {
                    mTargetRow = null;
                    return null;
                }
                targetRow = prepareTargetRow(targetRow);
                T mappedTargetRow = mMapper.map(sourceRow, targetRow);
                if (mappedTargetRow != null) {
                    mMappedTable.cleanRow(mappedTargetRow);
                    mTargetRow = mappedTargetRow;
                    return mappedTargetRow;
                }
            }
        } catch (Throwable e) {
            try {
                close();
            } catch (Throwable e2) {
                RowUtils.suppress(e, e2);
            }
            throw e;
        }
    }

    @Override
    public final void close() throws IOException {
        mTargetRow = null;
        mSource.close();
    }

    @Override
    public final long estimateSize() {
        return mSource.estimateSize();
    }

    @Override
    public final int characteristics() {
        return mSource.characteristics() & ~(SIZED | SUBSIZED);
    }

    protected final T prepareTargetRow(T targetRow) {
        if (targetRow == null) {
            targetRow = mMappedTable.newRow();
        } else {
            mMappedTable.unsetRow(targetRow);
        }
        return targetRow;
    }
}
