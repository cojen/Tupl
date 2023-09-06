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

import java.util.Comparator;

import org.cojen.tupl.Grouper;
import org.cojen.tupl.Scanner;

/**
 * 
 *
 * @author Brian S. O'Neill
 * @see GroupedTable
 */
public final class GroupedScanner<S, T> implements Scanner<T> {
    private final GroupedTable<S, T> mGroupedTable;
    private final Scanner<S> mSource;
    private final Comparator<T> mComparator;

    private Grouper<S, T> mGrouper;
    private S mHeader;

    private S mSourceRow;
    private T mTargetRow;

    /**
     * @param comparator defines the target ordering; is null if not applicable
     */
    public GroupedScanner(GroupedTable<S, T> groupedTable, Scanner<S> source,
                          Comparator<T> comparator, T targetRow, Grouper<S, T> grouper)
        throws IOException
    {
        mGroupedTable = groupedTable;
        mSource = source;
        mComparator = comparator;

        try {
            S sourceRow = source.row();

            if (sourceRow == null) {
                grouper.close();
                return;
            }

            mGrouper = grouper;
            mHeader = groupedTable.newSourceRow();

            groupedTable.copySourceRow(sourceRow, mHeader);
            mSourceRow = mGrouper.begin(sourceRow);
        } catch (Throwable e) {
            try {
                close();
            } catch (Throwable e2) {
                RowUtils.suppress(e, e2);
            }
            throw e;
        }

        step(targetRow);
    }

    @Override
    public T row() {
        return mTargetRow;
    }

    @Override
    public T step(T targetRow) throws IOException {
        Grouper<S, T> grouper = mGrouper;

        if (grouper == null) {
            mTargetRow = null;
            return null;
        }

        T finishedTargetRow;

        try {
            S sourceRow = mSourceRow;

            while (true) {
                if ((sourceRow = mSource.step(sourceRow)) != null
                    && mGroupedTable.compareSourceRows(mHeader, sourceRow) == 0)
                {
                    sourceRow = grouper.accumulate(sourceRow);
                    continue;
                }

                if (targetRow == null) {
                    targetRow = mGroupedTable.newRow();
                } else {
                    mGroupedTable.unsetRow(targetRow);
                }

                if ((finishedTargetRow = grouper.finish(targetRow)) != null) {
                    mGroupedTable.finishTarget(mHeader, finishedTargetRow);

                    if (sourceRow == null) {
                        break;
                    }

                    mTargetRow = finishedTargetRow;

                    mGroupedTable.copySourceRow(sourceRow, mHeader);
                    mSourceRow = grouper.begin(sourceRow);

                    return finishedTargetRow;
                }

                if (sourceRow == null) {
                    break;
                }

                mGroupedTable.copySourceRow(sourceRow, mHeader);
                sourceRow = grouper.begin(sourceRow);
            }
        } catch (Throwable e) {
            try {
                close();
            } catch (Throwable e2) {
                RowUtils.suppress(e, e2);
            }
            throw e;
        }

        mGrouper = null;
        mHeader = null;
        mSourceRow = null;
        mTargetRow = finishedTargetRow;

        try {
            grouper.close();
        } catch (Throwable e) {
            mTargetRow = null;
            throw e;
        }
        
        return finishedTargetRow;
    }

    @Override
    public void close() throws IOException {
        mHeader = null;
        mSourceRow = null;
        mTargetRow = null;

        // Use try-with-resources to close both and not lose any exceptions.
        try (mSource) {
            Grouper<S, T> grouper = mGrouper;
            if (grouper != null) {
                mGrouper = null;
                grouper.close();
            }
        }
    }

    @Override
    public long estimateSize() {
        return mGroupedTable.estimateSize();
    }

    @Override
    public int characteristics() {
        return mGroupedTable.characteristics(mSource);
    }

    @Override
    public Comparator<? super T> getComparator() {
        Comparator<T> cmp = mComparator;
        if (cmp == null) {
            throw new IllegalStateException();
        }
        return cmp;
    }
}
