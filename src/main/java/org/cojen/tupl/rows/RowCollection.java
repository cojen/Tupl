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

import java.util.AbstractCollection;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.cojen.tupl.Sorter;

/**
 * Implements a growable collection of objects which grows by accumulating blocks of objects.
 *
 * @author Brian S. O'Neill
 * @see RowSorter
 */
final class RowCollection<R> extends AbstractCollection<R> {
    private Block<R> mFirstBlock;
    private Block<R> mLastBlock;

    RowCollection() {
        clear();
    }

    @Override
    public void clear() {
        mFirstBlock = mLastBlock = new Block<R>();
    }

    @Override
    public boolean add(R row) {
        Block<R> block = mLastBlock;

        R[] rows = block.mRows;
        int size = block.mSize;

        if (size >= rows.length) {
            block = block.extend();
            mLastBlock = block;
            rows = block.mRows;
            size = 0;
        }

        rows[size++] = row;
        block.mSize = size;

        return true;
    }

    @Override
    public int size() {
        int size = 0;
        for (Block block = mFirstBlock; block != null; block = block.mNext) {
            size += block.mSize;
            if (size < 0) {
                return Integer.MAX_VALUE;
            }
        }
        return size;
    }

    @Override
    public Iterator<R> iterator() {
        return new Iterator<R>() {
            private Block<R> mBlock = mFirstBlock;
            private int mPos;

            @Override
            public boolean hasNext() {
                Block<R> block = mBlock;
                if (mPos >= block.mSize) {
                    block = block.mNext;
                    if (block == null) {
                        return false;
                    }
                    mBlock = block;
                    mPos = 0;
                }
                return true;
            }

            @Override
            public R next() {
                Block<R> block = mBlock;
                int pos = mPos;
                if (pos >= block.mSize) {
                    block = block.mNext;
                    if (block == null) {
                        throw new NoSuchElementException();
                    }
                    mBlock = block;
                    mPos = pos = 0;
                }
                R row = block.mRows[pos];
                mPos = pos + 1;
                return row;
            }
        };
    }

    @SuppressWarnings("unchecked")
    public R[] toArray(int size) {
        var array = new Object[size];
        int pos = 0;
        for (Block block = mFirstBlock; block != null; block = block.mNext) {
            int blockSize = block.mSize;
            System.arraycopy(block.mRows, 0, array, pos, blockSize);
            pos += blockSize;
        }
        return (R[]) array;
    }

    /**
     * @return number of rows
     */
    long transferAndDiscard(Sorter sorter, SortRowCodec<R> codec, byte[][] kvPairs)
        throws IOException
    {
        Block<R> block = mFirstBlock;
        mFirstBlock = null;
        mLastBlock = null;

        int offset = 0;
        long numRows = 0;

        while (block != null) {
            for (int i=0; i<block.mSize; i++) {
                codec.encode(block.mRows[i], numRows++, kvPairs, offset);
                offset += 2;
                if (offset >= kvPairs.length) {
                    sorter.addBatch(kvPairs, 0, offset >> 1);
                    offset = 0;
                }
            }
            block = block.mNext;
        }

        sorter.addBatch(kvPairs, 0, offset >> 1);

        return numRows;
    }

    static final class Block<R> {
        private static final int FIRST_BLOCK_CAPACITY = 16;
        private static final int HIGHEST_BLOCK_CAPACITY = 1024;

        private final R[] mRows;

        private int mSize;

        private Block<R> mNext;

        Block() {
            this(FIRST_BLOCK_CAPACITY);
        }

        @SuppressWarnings("unchecked")
        Block(int capacity) {
            mRows = (R[]) new Object[capacity];
        }

        Block<R> extend() {
            var block = new Block<R>(Math.min(mRows.length << 1, HIGHEST_BLOCK_CAPACITY));
            mNext = block;
            return block;
        }
    }
}
