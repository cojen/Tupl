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
import org.cojen.tupl.Updater;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public final class MappedUpdater<S, T> extends MappedScanner<S, T> implements Updater<T> {
    public MappedUpdater(MappedTable<S, T> mappedTable, Updater<S> source, T targetRow,
                         Mapper<S, T> mapper)
        throws IOException
    {
        super(mappedTable, source, targetRow, mapper);
    }

    @Override
    public T update(T row) throws IOException {
        return action(row, false);
    }

    @Override
    public T delete(T row) throws IOException {
        return action(row, true);
    }

    private T action(T row, boolean forDelete) throws IOException {
        var source = (Updater<S>) mSource;

        S sourceRow = source.row();
        if (sourceRow == null) {
            throw new IllegalStateException();
        }

        T targetRow = row();
        assert targetRow != null;

        if (forDelete) {
            mMappedTable.inversePk().inverseMap(sourceRow, targetRow);
            sourceRow = source.delete(sourceRow);
        } else {
            mMappedTable.inverseUpdate().inverseMap(sourceRow, targetRow);
            sourceRow = source.update(sourceRow);
        }

        if (sourceRow == null) {
            mTargetRow = null;
            return null;
        }

        row = prepareTargetRow(row);
        T mappedTargetRow = mMapper.map(sourceRow, row);

        if (mappedTargetRow != null) {
            mMappedTable.markAllUndirty(mappedTargetRow);
            mTargetRow = mappedTargetRow;
            return mappedTargetRow;
        } else {
            return step(row);
        }
    }
}
