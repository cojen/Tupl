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

import java.io.IOException;

import java.lang.invoke.MethodHandle;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;

import org.cojen.tupl.DatabaseException;
import org.cojen.tupl.Entry;
import org.cojen.tupl.Scanner;
import org.cojen.tupl.Sorter;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;

/**
 * 
 *
 * @author Brian S O'Neill
 * @see SortTranscoderMaker
 */
final class RowSorter<R> extends ScanBatch<R> implements RowConsumer<R> {
    // FIXME: Make configurable and/or "smart".
    private static final int EXTERNAL_THRESHOLD = 1_000_000;

    private ScanBatch<R> mFirstBatch, mLastBatch;

    /**
     * Sorts rows while still in binary format. When the amount of rows exceeds a threshold,
     * rows are fed into a Sorter, which implements an external sort algorithm. A Transcoder is
     * used to convert the binary format into something that's naturally ordered.
     */
    @SuppressWarnings("unchecked")
    static <R> Scanner<R> sort(SortedQueryLauncher<R> launcher, Transaction txn, Object... args)
        throws IOException
    {
        var sorter = new RowSorter<R>();

        // Pass sorter as if it's a row, but it's actually a RowConsumer.
        Scanner source = launcher.mSource.newScannerWith(txn, (R) sorter, args);

        int numRows = 0;
        for (Object c = source.row(); c != null; c = source.step(c)) {
            if (++numRows >= EXTERNAL_THRESHOLD) {
                return sorter.finishExternal(launcher, source);
            }
        }

        Comparator<R> comparator = launcher.mComparator;

        if (numRows == 0) {
            return new ARS<>(comparator);
        }

        var rows = (R[]) new Object[numRows];
        ScanBatch first = sorter.mFirstBatch;
        sorter.mFirstBatch = null;
        sorter.mLastBatch = null;
        first.decodeAllRows(rows, 0);

        Arrays.parallelSort(rows, comparator);

        return new ARS<>(launcher.mTable, rows, comparator);
    }

    /**
     * Sorts binary rows and writes the results to a remote endpoint.
     */
    @SuppressWarnings("unchecked")
    static <R> void sortWrite(SortedQueryLauncher<R> launcher, RowWriter writer,
                              Transaction txn, Object... args)
        throws IOException
    {
        var ext = new External<R>(launcher);

        Scanner<Entry> sorted;

        try {
            // Pass ext as if it's a row, but it's actually a RowConsumer.
            ext.transferAll(launcher.mSource.newScannerWith(txn, (R) ext, args));
            sorted = ext.finishScan();
        } catch (Throwable e) {
            throw ext.failed(e);
        }

        MethodHandle mh = launcher.mWriteRow;

        if (mh == null) {
            SecondaryInfo info = ext.mSortedInfo;
            RowGen rowGen = info.rowGen();
            // This is a bit ugly -- creating a projection specification only to immediately
            // crack it open.
            byte[] spec = DecodePartialMaker.makeFullSpec(rowGen, null, launcher.mProjection);
            launcher.mWriteRow = mh = WriteRowMaker.makeWriteRowHandle(info, spec);
        }

        try (sorted) {
            for (Entry e = sorted.row(); e != null; e = sorted.step(e)) {
                mh.invokeExact(writer, e.key(), e.value());
            }
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    /**
     * Sorts materialized row objects. When the amount of rows exceeds a threshold, rows are
     * fed into a Sorter, which implements an external sort algorithm. This adds overhead in
     * the form of extra serialization, and so the binary sort is generally preferred.
     */
    static <R> Scanner<R> sort(Table<R> table, Scanner<R> source, Comparator<R> comparator,
                               Set<String> projection, String orderBySpec)
        throws IOException
    {
        var collection = new RowCollection<R>();

        int numRows = 0;
        for (R row = source.row(); row != null; row = source.step()) {
            collection.add(row);
            if (++numRows == EXTERNAL_THRESHOLD) {
                Sorter sorter = newSorter(table);
                if (sorter != null) {
                    return finishExternal(table, source, comparator, projection, orderBySpec,
                                          collection, sorter);
                }
            }
        }

        if (numRows == 0) {
            return new ARS<>(comparator);
        }

        R[] rows = collection.toArray(numRows);
        collection = null; // help GC

        Arrays.parallelSort(rows, comparator);

        return new ARS<R>(table, rows, comparator);
    }

    /**
     * @return null if not supported
     */
    private static Sorter newSorter(Table<?> table) throws DatabaseException {
        while (true) {
            if (table instanceof MappedTable mapped) {
                table = mapped.source();
            } else if (table instanceof BaseTable base) {
                return base.rowStore().mDatabase.newSorter();
            } else {
                return null;
            }
        }
    }

    private static <R> Scanner<R> finishExternal
        (Table<R> table, Scanner<R> source, Comparator<R> comparator,
         Set<String> projection, String orderBySpec,
         RowCollection<R> collection, Sorter sorter)
        throws IOException
    {
        SortRowCodec<R> codec = SortRowCodec.find(table.rowType(), projection, orderBySpec);

        // Transfer the existing rows into the Sorter.

        var kvPairs = new byte[1000][];
        long numRows = collection.transferAndDiscard(sorter, codec, kvPairs);

        // Feed the remaining rows into the Sorter.

        int offset = 0;

        // Note that the first row is obtained by calling step, to skip over the last row which
        // was added to the collection.
        for (R row = source.step(); row != null; row = source.step()) {
            codec.encode(row, numRows++, kvPairs, offset);
            offset += 2;
            if (offset >= kvPairs.length) {
                sorter.addBatch(kvPairs, 0, offset >> 1);
                offset = 0;
            }
        }

        sorter.addBatch(kvPairs, 0, offset >> 1);

        return new SRS<>(sorter.finishScan(), codec, comparator);
    }

    @Override
    public void beginBatch(Scanner scanner, RowEvaluator<R> evaluator) {
        ScanBatch<R> batch;
        if (mLastBatch == null) {
            mFirstBatch = batch = this;
        } else {
            batch = new ScanBatch<R>();
            mLastBatch.appendNext(batch);
        }
        mLastBatch = batch;
        batch.mEvaluator = evaluator;
    }

    @Override
    public void accept(byte[] key, byte[] value) throws IOException {
        mLastBatch.addEntry(key, value);
    }

    private Scanner<R> finishExternal(SortedQueryLauncher<R> launcher, Scanner source)
        throws IOException
    {
        var ext = new External<R>(launcher);

        try {
            RowDecoder<R> decoder = launcher.mDecoder;

            if (decoder == null) {
                launcher.mDecoder = decoder = SortDecoderMaker
                    .findDecoder(ext.mRowType, ext.mSortedInfo, launcher.mProjection);
            }

            // Transfer all the undecoded rows into the sorter.

            ScanBatch<R> batch = mFirstBatch;

            mFirstBatch = null;
            mLastBatch = null;

            byte[][] kvPairs = null;

            do {
                ext.assignTranscoder(batch.mEvaluator);
                kvPairs = batch.transcode(ext.mTranscoder, ext.mSorter, kvPairs);
            } while ((batch = batch.detachNext()) != null);

            // Transfer all the rest.
            ext.transferAll(source);

            return new SRS<>(ext.finishScan(), decoder, launcher.mComparator);
        } catch (Throwable e) {
            throw ext.failed(e);
        }
    }

    private static class ARS<R> extends ArrayScanner<R> {
        private final Comparator<R> mComparator;

        ARS(Comparator<R> comparator) {
            mComparator = comparator;
        }

        ARS(Table<R> table, R[] rows, Comparator<R> comparator) {
            super(table, rows);
            mComparator = comparator;
        }

        @Override
        public Comparator<R> getComparator() {
            return mComparator;
        }
    }

    private static class SRS<R> extends ScannerScanner<R> {
        private final Comparator<R> mComparator;

        SRS(Scanner<Entry> scanner, RowDecoder<R> decoder, Comparator<R> comparator)
            throws IOException
        {
            super(scanner, decoder);
            mComparator = comparator;
        }

        @Override
        public int characteristics() {
            return NONNULL | ORDERED | IMMUTABLE | SORTED;
        }

        @Override
        public Comparator<R> getComparator() {
            return mComparator;
        }
    }

    /**
     * Performs an external merge sort.
     */
    private static final class External<R> implements RowConsumer<R> {
        final RowStore mRowStore;
        final Sorter mSorter;
        final Class<?> mRowType;
        final SecondaryInfo mSortedInfo;

        Transcoder mTranscoder;

        private byte[][] mBatch;
        private int mBatchSize;

        External(SortedQueryLauncher<R> launcher) throws IOException {
            mRowStore = launcher.mTable.rowStore();
            mSorter = mRowStore.mDatabase.newSorter();
            mRowType = launcher.mTable.rowType();

            SecondaryInfo sortedInfo = launcher.mSortedInfo;

            if (sortedInfo == null) {
                launcher.mSortedInfo = sortedInfo = SortDecoderMaker.findSortedInfo
                    (mRowType, launcher.mSpec, launcher.mProjection, true);
            }

            mSortedInfo = sortedInfo;

            mBatch = new byte[100][];
        }

        void assignTranscoder(RowEvaluator<R> evaluator) {
            mTranscoder = mRowStore.findSortTranscoder(mRowType, evaluator, mSortedInfo);
        }

        /**
         * Transfers all remaining undecoded rows from the source into the sorter.
         */
        @SuppressWarnings("unchecked")
        void transferAll(Scanner source) throws IOException {
            // Pass `this` as if it's a row, but it's actually a RowConsumer.
            while (source.step(this) != null);
        }

        Scanner<Entry> finishScan() throws IOException {
            flush();
            mBatch = null;
            return mSorter.finishScan();
        }

        RuntimeException failed(Throwable e) {
            try {
                mSorter.reset();
            } catch (Throwable e2) {
                RowUtils.suppress(e, e2);
            }
            throw RowUtils.rethrow(e);
        }

        @Override
        public void beginBatch(Scanner scanner, RowEvaluator<R> evaluator) throws IOException {
            flush();
            assignTranscoder(evaluator);
        }

        @Override
        public void accept(byte[] key, byte[] value) throws IOException {
            byte[][] batch = mBatch;
            int size = mBatchSize;
            mTranscoder.transcode(key, value, batch, size);
            size += 2;
            if (size < batch.length) {
                mBatchSize = size;
            } else {
                mSorter.addBatch(batch, 0, size >> 1);
                mBatchSize = 0;
            }
        }

        private void flush() throws IOException {
            if (mBatchSize > 0) {
                mSorter.addBatch(mBatch, 0, mBatchSize >> 1);
                mBatchSize = 0;
            }
        }
    }
}
