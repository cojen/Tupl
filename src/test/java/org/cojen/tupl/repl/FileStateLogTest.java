/*
 *  Copyright (C) 2017 Cojen.org
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
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.repl;

import java.io.EOFException;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.TestUtils;

import org.cojen.tupl.io.Utils;

import static org.cojen.tupl.repl.FileTermLogTest.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class FileStateLogTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(FileStateLogTest.class.getName());
    }

    @Before
    public void setup() throws Exception {
        mBase = TestUtils.newTempBaseFile(getClass());
        mLog = FileStateLog.open(mBase);
    }

    @After
    public void teardown() throws Exception {
        if (mLog != null) {
            mLog.close();
        }

        for (StateLog log : mMoreLogs) {
            log.close();
        }

        TestUtils.deleteTempFiles(getClass());
    }

    private File mBase;
    private FileStateLog mLog;
    private List<StateLog> mMoreLogs = new ArrayList<>();

    private StateLog newTempLog() throws Exception {
        StateLog log = FileStateLog.open(TestUtils.newTempBaseFile(getClass()));
        mMoreLogs.add(log);
        return log;
    }

    @Test
    public void defineTerms() throws Exception {
        // Tests various ways in which terms can be defined (or not).

        // No such previous term exists.
        assertFalse(mLog.defineTerm(10, 11, 1000));

        // Allow term definition with no previous term at the start.
        mLog.truncateAll(0, 0, 1000);
        assertTrue(mLog.defineTerm(0, 10, 1000));
        assertTrue(mLog.defineTerm(0, 10, 1000));

        assertFalse(mLog.defineTerm(0, 11, 2000));
        assertTrue(mLog.defineTerm(10, 11, 2000));

        // Write contiguous data to prevent rollback.
        {
            LogWriter writer = mLog.openWriter(0, 10, 1000);
            writer.write(new byte[1000]);
            writer.release();

            writer = mLog.openWriter(10, 11, 2000);
            writer.write(new byte[1]);
            writer.release();
        }

        // Allow follower to define a higher term.
        TermLog term = mLog.defineTermLog(11, 15, 3000);
        assertNotNull(term);
        assertSame(term, mLog.defineTermLog(11, 15, 3000));

        // Validate the term.
        {
            LogWriter writer = mLog.openWriter(11, 15, 3000);
            writer.write(new byte[1]);
            writer.release();
            mLog.commit(writer.position());
        }

        // Previous term conflict.
        assertFalse(mLog.defineTerm(10, 16, 4000));

        // Position in the middle of an existing term.
        assertTrue(mLog.defineTerm(15, 15, 4000));

        // Position in the middle of an existing term, but position is out of bounds.
        try {
            assertFalse(mLog.defineTerm(11, 11, 1000)); // < commit position
            fail();
        } catch (CommitConflictException e) {
            assertFalse(e.isFatal());
        }
        try {
            assertFalse(mLog.defineTerm(11, 11, 1500)); // < commit position
            fail();
        } catch (CommitConflictException e) {
            assertFalse(e.isFatal());
        }
        try {
            assertFalse(mLog.defineTerm(11, 11, 2000)); // < commit position
            fail();
        } catch (CommitConflictException e) {
            assertFalse(e.isFatal());
        }
        assertFalse(mLog.defineTerm(11, 11, 3000)); // == commit position
        assertFalse(mLog.defineTerm(11, 11, 5000)); // > commit position
        assertTrue(mLog.defineTerm(11, 11, 2500)); // actually in bounds

        // Mustn't define a term if position as the highest, although it's not usable.
        term = mLog.defineTermLog(15, 15, 10000);
        assertNotNull(term);
        assertSame(term, mLog.defineTermLog(15, 15, Long.MAX_VALUE));

        int[] countRef = {0};

        mLog.queryTerms(0, 20000, (prevTerm, trm, startPosition) -> {
            countRef[0]++;
            switch ((int) trm) {
                default -> fail("unknown term: " + trm);
                case 10 -> {
                    assertEquals(0, prevTerm);
                    assertEquals(1000, startPosition);
                }
                case 11 -> {
                    assertEquals(10, prevTerm);
                    assertEquals(2000, startPosition);
                }
                case 15 -> {
                    assertEquals(11, prevTerm);
                    assertEquals(3000, startPosition);
                }
            }
        });

        assertEquals(3, countRef[0]);

        // Find terms when the position range doesn't touch a boundary.

        countRef[0] = 0;
        mLog.queryTerms(1100, 1200, (prevTerm, trm, startPosition) -> {
            countRef[0]++;
            assertEquals(0, prevTerm);
            assertEquals(10, trm);
            assertEquals(1000, startPosition);
        });

        assertEquals(1, countRef[0]);

        countRef[0] = 0;
        mLog.queryTerms(1100, 2000, (prevTerm, trm, startPosition) -> {
            countRef[0]++;
            assertEquals(0, prevTerm);
            assertEquals(10, trm);
            assertEquals(1000, startPosition);
        });

        assertEquals(1, countRef[0]);

        countRef[0] = 0;
        mLog.queryTerms(2000, 2100, (prevTerm, trm, startPosition) -> {
            countRef[0]++;
            assertEquals(10, prevTerm);
            assertEquals(11, trm);
            assertEquals(2000, startPosition);
        });

        assertEquals(1, countRef[0]);

        countRef[0] = 0;
        mLog.queryTerms(2100, 2100, (prevTerm, trm, startPosition) -> {
            countRef[0]++;
        });

        assertEquals(0, countRef[0]);
    }

    @Test
    public void missingRanges() throws Exception {
        // Verify that missing ranges can be queried.

        var result = new RangeResult();
        assertEquals(0, mLog.checkForMissingData(Long.MAX_VALUE, result));
        assertEquals(0, result.mRanges.size());

        LogWriter writer = mLog.openWriter(0, 1, 0);
        write(writer, new byte[100]);
        writer.release();

        result = new RangeResult();
        assertEquals(100, mLog.checkForMissingData(0, result));
        assertEquals(0, result.mRanges.size());
        assertEquals(100, mLog.checkForMissingData(100, result));

        // Define a new term before the previous one is filled in.
        mLog.defineTerm(1, 2, 500);
        result = new RangeResult();
        assertEquals(100, mLog.checkForMissingData(100, result));
        assertEquals(1, result.mRanges.size());
        assertEquals(new Range(100, 500), result.mRanges.get(0));

        // Write some data into the new term.
        writer = mLog.openWriter(1, 2, 500);
        write(writer, new byte[10]);
        writer.release();
        result = new RangeResult();
        assertEquals(100, mLog.checkForMissingData(100, result));
        assertEquals(1, result.mRanges.size());
        assertEquals(new Range(100, 500), result.mRanges.get(0));

        // Create a missing range in the new term.
        writer = mLog.openWriter(2, 2, 600);
        write(writer, new byte[10]);
        writer.release();
        result = new RangeResult();
        assertEquals(100, mLog.checkForMissingData(100, result));
        assertEquals(2, result.mRanges.size());
        assertEquals(new Range(100, 500), result.mRanges.get(0));
        assertEquals(new Range(510, 600), result.mRanges.get(1));

        // Go back to the previous term and fill in some of the missing range, creating another
        // missing range.
        writer = mLog.openWriter(1, 1, 200);
        write(writer, new byte[50]);
        writer.release();
        result = new RangeResult();
        assertEquals(100, mLog.checkForMissingData(100, result));
        assertEquals(3, result.mRanges.size());
        assertEquals(new Range(100, 200), result.mRanges.get(0));
        assertEquals(new Range(250, 500), result.mRanges.get(1));
        assertEquals(new Range(510, 600), result.mRanges.get(2));

        // Fill in the lowest missing range.
        writer = mLog.openWriter(1, 1, 100);
        write(writer, new byte[100]);
        writer.release();
        result = new RangeResult();
        assertEquals(250, mLog.checkForMissingData(100, result));
        assertEquals(0, result.mRanges.size());
        result = new RangeResult();
        assertEquals(250, mLog.checkForMissingData(250, result));
        assertEquals(2, result.mRanges.size());
        assertEquals(new Range(250, 500), result.mRanges.get(0));
        assertEquals(new Range(510, 600), result.mRanges.get(1));

        // Partially fill in the highest missing range.
        writer = mLog.openWriter(2, 2, 510);
        write(writer, new byte[20]);
        writer.release();
        result = new RangeResult();
        assertEquals(250, mLog.checkForMissingData(250, result));
        assertEquals(2, result.mRanges.size());
        assertEquals(new Range(250, 500), result.mRanges.get(0));
        assertEquals(new Range(530, 600), result.mRanges.get(1));

        // Fill in the next lowest missing range.
        writer = mLog.openWriter(1, 1, 250);
        write(writer, new byte[250]);
        writer.release();
        result = new RangeResult();
        assertEquals(530, mLog.checkForMissingData(250, result));
        assertEquals(0, result.mRanges.size());
        result = new RangeResult();
        assertEquals(530, mLog.checkForMissingData(530, result));
        assertEquals(1, result.mRanges.size());
        assertEquals(new Range(530, 600), result.mRanges.get(0));

        // Fill in the last missing range.
        writer = mLog.openWriter(2, 2, 530);
        write(writer, new byte[70]);
        writer.release();
        result = new RangeResult();
        assertEquals(610, mLog.checkForMissingData(530, result));
        assertEquals(0, result.mRanges.size());
        result = new RangeResult();
        assertEquals(610, mLog.checkForMissingData(610, result));
        assertEquals(0, result.mRanges.size());
    }

    @Test
    public void missingRangesHighStart() throws Exception {
        // Verify missing ranges when log starts higher than position zero.

        // Start at position 1000.
        mLog.truncateAll(0, 0, 1000);
        mLog.defineTerm(0, 10, 1000);

        var result = new RangeResult();
        assertEquals(1000, mLog.checkForMissingData(Long.MAX_VALUE, result));
        assertEquals(0, result.mRanges.size());

        LogWriter writer = mLog.openWriter(0, 10, 1000);
        write(writer, new byte[100]);
        writer.release();

        result = new RangeResult();
        assertEquals(1100, mLog.checkForMissingData(Long.MAX_VALUE, result));
        assertEquals(0, result.mRanges.size());
        result = new RangeResult();
        assertEquals(1100, mLog.checkForMissingData(1000, result));
        assertEquals(0, result.mRanges.size());

        // Define a new term before the previous one is filled in.
        mLog.defineTerm(10, 11, 2000);
        result = new RangeResult();
        assertEquals(1100, mLog.checkForMissingData(Long.MAX_VALUE, result));
        assertEquals(0, result.mRanges.size());
        result = new RangeResult();
        assertEquals(1100, mLog.checkForMissingData(1100, result));
        assertEquals(1, result.mRanges.size());
        assertEquals(new Range(1100, 2000), result.mRanges.get(0));

        // Write some data into the new term.
        writer = mLog.openWriter(10, 11, 2000);
        write(writer, new byte[100]);
        writer.release();
        result = new RangeResult();
        assertEquals(1100, mLog.checkForMissingData(Long.MAX_VALUE, result));
        assertEquals(0, result.mRanges.size());
        result = new RangeResult();
        assertEquals(1100, mLog.checkForMissingData(1100, result));
        assertEquals(1, result.mRanges.size());
        assertEquals(new Range(1100, 2000), result.mRanges.get(0));

        // Fill in the missing range.
        writer = mLog.openWriter(10, 10, 1100);
        write(writer, new byte[900]);
        writer.release();
        result = new RangeResult();
        assertEquals(2100, mLog.checkForMissingData(Long.MAX_VALUE, result));
        assertEquals(0, result.mRanges.size());
        result = new RangeResult();
        assertEquals(2100, mLog.checkForMissingData(2100, result));
        assertEquals(0, result.mRanges.size());
    }

    @Test
    public void primordialTerm() throws Exception {
        LogReader reader = mLog.openReader(0);

        var buf = new byte[10];
        assertEquals(0, reader.tryReadAny(buf, 0, buf.length));

        new Thread(() -> {
            try {
                TestUtils.sleep(500);
                mLog.defineTerm(0, 2, 0);
            } catch (Exception e) {
                Utils.uncaught(e);
            }
        }).start();

        assertEquals(-1, reader.read(buf, 0, buf.length));
    }

    @Test
    public void highestPosition() throws Exception {
        // Verify highest position and commit position behavior.

        var info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(0, info.mTerm);
        assertEquals(0, info.mHighestPosition);
        assertEquals(0, info.mCommitPosition);

        mLog.commit(0);

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(0, info.mTerm);
        assertEquals(0, info.mHighestPosition);
        assertEquals(0, info.mCommitPosition);

        LogWriter writer = mLog.openWriter(0, 1, 0);
        write(writer, new byte[100]);
        writer.release();

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(1, info.mTerm);
        assertEquals(100, info.mHighestPosition);
        assertEquals(0, info.mCommitPosition);

        mLog.commit(50);
        
        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(1, info.mTerm);
        assertEquals(100, info.mHighestPosition);
        assertEquals(50, info.mCommitPosition);

        writer = mLog.openWriter(1, 2, 500);
        write(writer, new byte[100]);
        writer.release();

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(1, info.mTerm);
        assertEquals(100, info.mHighestPosition);
        assertEquals(50, info.mCommitPosition);

        mLog.commit(800);

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(1, info.mTerm);
        assertEquals(100, info.mHighestPosition);
        assertEquals(100, info.mCommitPosition);

        writer = mLog.openWriter(1, 1, 100);
        write(writer, new byte[300]);
        writer.release();

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(1, info.mTerm);
        assertEquals(400, info.mHighestPosition);
        assertEquals(400, info.mCommitPosition);

        writer = mLog.openWriter(1, 1, 400);
        write(writer, new byte[100]);
        writer.release();

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(2, info.mTerm);
        assertEquals(600, info.mHighestPosition);
        assertEquals(600, info.mCommitPosition);

        writer = mLog.openWriter(2, 2, 600);
        write(writer, new byte[100]);
        writer.release();

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(2, info.mTerm);
        assertEquals(700, info.mHighestPosition);
        assertEquals(700, info.mCommitPosition);

        writer = mLog.openWriter(2, 2, 700);
        write(writer, new byte[200]);
        writer.release();

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(2, info.mTerm);
        assertEquals(900, info.mHighestPosition);
        assertEquals(800, info.mCommitPosition);

        writer = mLog.openWriter(2, 2, 900);
        write(writer, new byte[50]);
        writer.release();
        mLog.commit(950);

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(2, info.mTerm);
        assertEquals(950, info.mHighestPosition);
        assertEquals(950, info.mCommitPosition);

        writer = mLog.openWriter(2, 2, 950);
        write(writer, new byte[50]);
        writer.release();
        mLog.commit(1000);

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(2, info.mTerm);
        assertEquals(1000, info.mHighestPosition);
        assertEquals(1000, info.mCommitPosition);
    }

    @Test
    public void currentTerm() throws Exception {
        assertEquals(0, mLog.checkCurrentTerm(-1));
        assertEquals(0, mLog.checkCurrentTerm(0));
        assertEquals(1, mLog.checkCurrentTerm(1));

        mLog.close();
        mLog = FileStateLog.open(mBase);

        assertEquals(1, mLog.checkCurrentTerm(0));
        assertEquals(5, mLog.incrementCurrentTerm(4, 0));

        mLog.close();
        mLog = FileStateLog.open(mBase);

        assertEquals(5, mLog.checkCurrentTerm(0));

        try {
            mLog.incrementCurrentTerm(0, 0);
            fail();
        } catch (IllegalArgumentException e) {
        }

        assertTrue(mLog.checkCandidate(123));
        assertFalse(mLog.checkCandidate(234));
        assertEquals(6, mLog.checkCurrentTerm(6));
        assertTrue(mLog.checkCandidate(234));
    }

    @Test
    public void recoverState() throws Exception {
        var rnd = new Random(7435847);

        long prevTerm = mLog.checkCurrentTerm(0);
        long term = mLog.incrementCurrentTerm(1, 0);
        LogWriter writer = mLog.openWriter(prevTerm, term, 0);
        var msg1 = new byte[1000];
        rnd.nextBytes(msg1);
        write(writer, msg1);
        writer.release();

        prevTerm = mLog.checkCurrentTerm(0);
        term = mLog.incrementCurrentTerm(1, 123);
        writer = mLog.openWriter(prevTerm, term, 1000);
        var msg2 = new byte[2000];
        rnd.nextBytes(msg2);
        write(writer, msg2);
        writer.release();

        // Reopen without sync. All data for second term is gone, but the first term remains.
        // The second term definition will be recovered, because defineTermLog syncs metadata.
        mLog.close();
        mLog = FileStateLog.open(mBase);

        var info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(term, info.mTerm);
        assertEquals(1000, info.mHighestPosition);
        assertEquals(0, info.mCommitPosition);
        assertFalse(mLog.checkCandidate(1)); // doesn't match 123
        assertTrue(mLog.checkCandidate(123));

        // Reading from first term reaches EOF, because the second term exists.
        verifyLog(mLog, 0, msg1, -1);

        term = mLog.incrementCurrentTerm(1, 0);
        writer = mLog.openWriter(prevTerm, term, 1000);
        var msg3 = new byte[3500];
        rnd.nextBytes(msg3);
        write(writer, msg3);
        writer.release();

        // Reopen with sync. All data is preserved, but not committed.
        mLog.sync();
        mLog.close();
        mLog = FileStateLog.open(mBase);

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(term, info.mTerm);
        assertEquals(4500, info.mHighestPosition);
        assertEquals(0, info.mCommitPosition);

        // Partially commit and reopen.
        mLog.commit(4000);
        assertEquals(4000, mLog.syncCommit(term, term, 4000));
        mLog.commitDurable(4000);
        mLog.close();
        mLog = FileStateLog.open(mBase);

        info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(term, info.mTerm);
        assertEquals(4500, info.mHighestPosition);
        assertEquals(4000, info.mCommitPosition);

        // Verify data.
        verifyLog(mLog, 0, msg1, -1); // end of term
        verifyLog(mLog, 1000, msg3, 0);

        // Cannot read past commit.

        Reader reader = TestUtils.startAndWaitUntilBlocked(new Reader(0));
        assertEquals(4000, reader.mTotal);

        reader.interrupt();
        reader.join();
    }

    class Reader extends Thread {
        final long mStartPosition;
        volatile long mTotal;

        Reader(long start) {
            mStartPosition = start;
        }

        @Override
        public void run() {
            try {
                LogReader reader = mLog.openReader(mStartPosition);
                var buf = new byte[1000];
                while (true) {
                    int amt = reader.read(buf, 0, buf.length);
                    if (amt < 0) {
                        reader = mLog.openReader(reader.position());
                    } else {
                        mTotal += amt;
                    }
                }
            } catch (InterruptedIOException e) {
                // Stop.
            } catch (Throwable e) {
                Utils.uncaught(e);
            }
        }
    }

    @Test
    public void recoverState2() throws Exception {
        // Write data over multiple segments.

        long seed = 1334535;
        var rnd = new Random(seed);
        var buf = new byte[1000];

        long prevTerm = mLog.checkCurrentTerm(0);
        long term = mLog.incrementCurrentTerm(1, 0);
        LogWriter writer = mLog.openWriter(prevTerm, term, 0);

        for (int i=0; i<10_000; i++) {
            rnd.nextBytes(buf);
            write(writer, buf);
        }
        writer.release();

        prevTerm = mLog.checkCurrentTerm(0);
        term = mLog.incrementCurrentTerm(1, 0);
        writer = mLog.openWriter(prevTerm, term, writer.position());

        for (int i=0; i<5_000; i++) {
            rnd.nextBytes(buf);
            write(writer, buf);
        }
        writer.release();

        final long highestPosition = writer.position();

        mLog.commit(highestPosition);
        mLog.sync();

        // Generate data over more terms, none of which will be committed.

        for (int x=0; x<2; x++) {
            prevTerm = mLog.checkCurrentTerm(0);
            term = mLog.incrementCurrentTerm(1, 0);
            writer = mLog.openWriter(prevTerm, term, writer.position());
            for (int i=0; i<5_000; i++) {
                rnd.nextBytes(buf);
                writer.write(buf, 0, buf.length, 0); // don't advance highest position
            }
            writer.release();
        }

        mLog.close();
        mLog = FileStateLog.open(mBase);

        var info = new LogInfo();
        mLog.captureHighest(info);
        assertEquals(prevTerm, info.mTerm);
        assertEquals(highestPosition, info.mHighestPosition);
        // Didn't call syncCommit.
        assertEquals(0, info.mCommitPosition);

        mLog.commit(highestPosition);

        // Verify data.

        rnd = new Random(seed);
        var buf2 = new byte[buf.length];

        LogReader r = mLog.openReader(0);
        for (int i=0; i<10_000; i++) {
            rnd.nextBytes(buf);
            readFully(r, buf2);
            TestUtils.fastAssertArrayEquals(buf, buf2);
        }

        assertEquals(-1, r.read(buf2, 0, 1)); // end of term

        r = mLog.openReader(r.position());
        for (int i=0; i<5_000; i++) {
            rnd.nextBytes(buf);
            readFully(r, buf2);
            TestUtils.fastAssertArrayEquals(buf, buf2);
        }

        assertEquals(-1, r.read(buf2, 0, 1)); // end of term

        Reader reader = TestUtils.startAndWaitUntilBlocked(new Reader(r.position()));
        assertEquals(0, reader.mTotal);

        reader.interrupt();
        reader.join();
    }

    @Test
    public void recoverState3() throws Exception {
        // Test recovery of the start position and term, after truncating the log.

        var rnd = new Random(64926492);

        long prevTerm = mLog.checkCurrentTerm(0);
        long term = mLog.incrementCurrentTerm(1, 0);
        LogWriter writer = mLog.openWriter(prevTerm, term, 0);
        var msg1 = new byte[1000];
        rnd.nextBytes(msg1);
        write(writer, msg1);
        writer.release();

        prevTerm = mLog.checkCurrentTerm(0);
        term = mLog.incrementCurrentTerm(1, 0);
        writer = mLog.openWriter(prevTerm, term, 1000);
        var msg2 = new byte[2_000_000];
        rnd.nextBytes(msg2);
        write(writer, msg2);
        writer.release();

        mLog.commit(writer.position());
        mLog.sync();
        mLog.commitDurable(writer.position());
        mLog.compact(1_500_000);

        mLog.close();
        mLog = FileStateLog.open(mBase);

        TermLog termLog = mLog.termLogAt(1_500_000);
        assertEquals(1000 + 1024 * 1024, termLog.startPosition());
        assertEquals(2, termLog.prevTermAt(termLog.startPosition()));
        assertEquals(2, termLog.term());
    }

    @Test
    public void compactAll() throws Exception {
        long term = mLog.incrementCurrentTerm(1, 0);
        LogWriter writer = mLog.openWriter(0, term, 0);
        var b = new byte[1024];
        for (int i=0; i<1024; i++) {
            writer.write(b);
        }
        long commitPosition = writer.position();
        mLog.commit(commitPosition);
        writer.release();

        var expect = new File(mBase.getPath() + ".1.0.0");
        assertTrue(expect.exists());
        assertEquals(commitPosition, expect.length());

        mLog.sync();
        mLog.commitDurable(commitPosition);

        mLog.compact(commitPosition);

        assertFalse(expect.exists());

        // Close and re-open with no segment files.
        mLog.close();
        mLog = FileStateLog.open(mBase);

        // Continue writing the same term.
        writer = mLog.openWriter(term, term, commitPosition);
        writer.write(new byte[100]);
        writer.release();

        expect = new File(mBase.getPath() + ".1." + commitPosition);
        assertTrue(expect.exists());
        assertEquals(1024 * 1024, expect.length());
    }

    @Test
    public void doubleOpen() throws Exception {
        try {
            FileStateLog.open(mBase);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().indexOf("open") > 0);
        }
    }

    @Test
    public void replaceEmptyTerm() throws Exception {
        // An empty term shouldn't prevent a new term from replacing it.

        LogWriter w1 = mLog.openWriter(0, 1, 0);
        assertTrue(w1.write(new byte[100]) > 0);

        // Define an empty term 2.
        assertTrue(mLog.defineTerm(1, 2, 100));

        // Define term 3, with the expectation that term 1 will be extended.
        assertTrue(mLog.defineTerm(1, 3, 200));

        // Continue with term 1.
        assertTrue(w1.write(new byte[100]) > 0);
        w1.release();

        LogWriter w3 = mLog.openWriter(1, 3, 200);
        assertTrue(w3.write(new byte[100]) > 0);

        mLog.commit(300);

        LogInfo info = mLog.captureHighest();
        assertEquals(3, info.mTerm);
        assertEquals(300, info.mHighestPosition);
        assertEquals(300, info.mCommitPosition);

        // Now create multiple empty terms.
        assertTrue(mLog.defineTerm(3, 4, 400));
        assertTrue(mLog.defineTerm(4, 5, 500));

        // Define term 6, with the expectation that term 3 will be extended.
        assertTrue(mLog.defineTerm(3, 6, 600));

        // Continue with term 3 (note that the write cannot extend past the end)
        assertFalse(w3.write(new byte[400]) > 0);
        w3.release();

        LogWriter w6 = mLog.openWriter(3, 6, 600);
        assertTrue(w6.write(new byte[100]) > 0);

        mLog.commit(700);

        info = mLog.captureHighest();
        assertEquals(6, info.mTerm);
        assertEquals(700, info.mHighestPosition);
        assertEquals(700, info.mCommitPosition);
    }

    @Test
    public void extendOldTerm() throws Exception {
        // Searching for missing data should resume on an older term which was extended.

        // Term 1 starts at 0.
        LogWriter w1 = mLog.openWriter(0, 1, 0);
        assertTrue(w1.write(new byte[100]) > 0);
        w1.release();

        // Term 2 starts at 100, but is empty so far.
        LogWriter w2 = mLog.openWriter(1, 2, 100);
        assertTrue(w2.write(new byte[0]) > 0);
        w2.release();

        LogInfo info = mLog.captureHighest();
        assertEquals(2, info.mTerm);
        assertEquals(100, info.mHighestPosition);

        var result = new RangeResult();
        long contigPosition = mLog.checkForMissingData(Long.MAX_VALUE, result);
        assertEquals(100, contigPosition);
        assertEquals(0, result.mRanges.size());

        // Term 3 starts at 150, and forces term 1 to extend.
        LogWriter w3 = mLog.openWriter(1, 3, 150);
        assertTrue(w3.write(new byte[100]) > 0);
        w3.release();

        info = mLog.captureHighest();
        assertEquals(1, info.mTerm);
        assertEquals(100, info.mHighestPosition);

        result = new RangeResult();
        contigPosition = mLog.checkForMissingData(contigPosition, result);
        assertEquals(100, contigPosition);
        assertEquals(1, result.mRanges.size());
        assertEquals(new Range(100, 150), result.mRanges.get(0));
    }

    @Test
    public void replaceNonCommitted() throws Exception {
        // Allow new terms to replace conflicting terms which aren't committed.

        // Term 1 starts at 0.
        LogWriter w1 = mLog.openWriter(0, 1, 0);
        assertTrue(w1.write(new byte[100]) > 0);
        w1.release();

        // Term 2 starts at 100.
        LogWriter w2 = mLog.openWriter(1, 2, 100);
        assertTrue(w2.write(new byte[100]) > 0);
        w2.release();

        // Term 3 starts at 200.
        LogWriter w3 = mLog.openWriter(2, 3, 200);
        assertTrue(w3.write(new byte[100]) > 0);
        w3.release();

        LogInfo info = mLog.captureHighest();
        assertEquals(3, info.mTerm);
        assertEquals(300, info.mHighestPosition);

        // Term 4 replaces term 2 and 3, and it also extends term 1.
        LogWriter w4 = mLog.openWriter(1, 4, 200);
        assertTrue(w4.write(new byte[100]) > 0);
        w4.release();

        info = mLog.captureHighest();
        assertEquals(4, info.mTerm);
        assertEquals(300, info.mHighestPosition);

        // Commit term 4, and now it cannot be replaced.
        mLog.commit(250);

        info = mLog.captureHighest();
        assertEquals(4, info.mTerm);
        assertEquals(300, info.mHighestPosition);
        assertEquals(250, info.mCommitPosition);

        try {
            assertNull(mLog.openWriter(1, 5, 200)); // < commit position
            fail();
        } catch (CommitConflictException e) {
            assertFalse(e.isFatal());
        }

        assertNull(mLog.openWriter(1, 5, 250)); // == commit position
    }

    @Test
    public void rollbackHighest() throws Exception {
        // When a conflicting term rolls back the highest position, this should be reflected in
        // the metadata as well.

        // Term 1 starts at 0.
        long term = mLog.incrementCurrentTerm(1, 123);
        LogWriter w1 = mLog.openWriter(0, term, 0);
        assertTrue(w1.write(new byte[100]) > 0);
        w1.release();

        // Term 2 starts at 100.
        term = mLog.incrementCurrentTerm(1, 123);
        LogWriter w2 = mLog.openWriter(w1.term(), term, 100);
        assertTrue(w2.write(new byte[100]) > 0);
        w2.release();

        // Sync the highest position.
        assertEquals(200, mLog.captureHighest().mHighestPosition);
        mLog.sync();

        // Term 3 replaces term 2 and extends term 1.
        term = mLog.incrementCurrentTerm(1, 123);
        LogWriter w3 = mLog.openWriter(w1.term(), term, 150);
        assertTrue(w3.write(new byte[0]) > 0);
        w3.release();

        mLog.close();
        mLog = FileStateLog.open(mBase);

        LogInfo info = mLog.captureHighest();
        assertEquals(w1.term(), info.mTerm);
        assertEquals(100, info.mHighestPosition);
    }

    @Test
    public void raftFig7() throws Exception {
        // Tests the scenarios shown in figure 7 of the Raft paper.

        // Leader.
        writeTerm(mLog,  0,  '1', 1 - 1, 3);
        writeTerm(mLog, '1', '4', 4 - 1, 2);
        writeTerm(mLog, '4', '5', 6 - 1, 2);
        writeTerm(mLog, '5', '6', 8 - 1, 3);
        verifyLog(mLog, 0, "1114455666".getBytes(), 0);
        writeTerm(mLog, '6', '8', 11 - 1, 1);
        verifyLog(mLog, 0, "11144556668".getBytes(), 0);

        StateLog logA = newTempLog();
        writeTerm(logA,  0,  '1', 1 - 1, 3);
        writeTerm(logA, '1', '4', 4 - 1, 2);
        writeTerm(logA, '4', '5', 6 - 1, 2);
        writeTerm(logA, '5', '6', 8 - 1, 2);
        verifyLog(logA, 0, "111445566".getBytes(), 0);

        replicate(mLog, 11 - 1, logA);
        verifyLog(logA, 0, "11144556668".getBytes(), 0);

        StateLog logB = newTempLog();
        writeTerm(logB,  0,  '1', 1 - 1, 3);
        writeTerm(logB, '1', '4', 4 - 1, 1);
        verifyLog(logB, 0, "1114".getBytes(), 0);

        replicate(mLog, 11 - 1, logB);
        verifyLog(logB, 0, "11144556668".getBytes(), 0);

        StateLog logC = newTempLog();
        writeTerm(logC,  0,  '1', 1 - 1, 3);
        writeTerm(logC, '1', '4', 4 - 1, 2);
        writeTerm(logC, '4', '5', 6 - 1, 2);
        writeTerm(logC, '5', '6', 8 - 1, 4);
        verifyLog(logC, 0, "11144556666".getBytes(), 0);

        replicate(mLog, 11 - 1, logC);
        verifyLog(logC, 0, "11144556668".getBytes(), 0);

        StateLog logD = newTempLog();
        writeTerm(logD,  0,  '1', 1 - 1, 3);
        writeTerm(logD, '1', '4', 4 - 1, 2);
        writeTerm(logD, '4', '5', 6 - 1, 2);
        writeTerm(logD, '5', '6', 8 - 1, 3);
        writeTerm(logD, '6', '7', 11 - 1, 2);
        verifyLog(logD, 0, "111445566677".getBytes(), 0);

        replicate(mLog, 11 - 1, logD);
        verifyLog(logD, 0, "11144556668".getBytes(), 0);

        StateLog logE = newTempLog();
        writeTerm(logE,  0,  '1', 1 - 1, 3);
        writeTerm(logE, '1', '4', 4 - 1, 4);
        verifyLog(logE, 0, "1114444".getBytes(), 0);

        replicate(mLog, 11 - 1, logE);
        verifyLog(logE, 0, "11144556668".getBytes(), 0);

        StateLog logF = newTempLog();
        writeTerm(logF,  0,  '1', 1 - 1, 3);
        writeTerm(logF, '1', '2', 4 - 1, 3);
        writeTerm(logF, '2', '3', 7 - 1, 5);
        verifyLog(logF, 0, "11122233333".getBytes(), 0);

        replicate(mLog, 11 - 1, logF);
        verifyLog(logF, 0, "11144556668".getBytes(), 0);
    }

    private static void replicate(StateLog from, long position, StateLog to) throws IOException {
        var info = new LogInfo();
        to.captureHighest(info);

        if (info.mHighestPosition < position) {
            position = info.mHighestPosition;
        }

        LogReader reader;
        LogWriter writer;

        while (true) {
            reader = from.openReader(position);
            writer = to.openWriter(reader.prevTerm(), reader.term(), position);
            if (writer != null) {
                break;
            }
            reader.release();
            position--;
        }

        var buf = new byte[100];

        while (true) {
            int amt = reader.tryReadAny(buf, 0, buf.length);
            if (amt <= 0) {
                reader.release();
                writer.release();
                if (amt == 0) {
                    break;
                }
                reader = from.openReader(position);
                writer = to.openWriter(reader.prevTerm(), reader.term(), position);
            } else {
                write(writer, buf, 0, amt);
                position += amt;
            }
        }
    }

    private static void verifyLog(StateLog log, int position, byte[] expect, int finalAmt)
        throws IOException
    {
        LogReader reader = log.openReader(position);

        var buf = new byte[expect.length];
        int offset = 0;

        while (offset < buf.length) {
            int amt = reader.tryReadAny(buf, offset, buf.length - offset);
            if (amt <= 0) {
                reader.release();
                if (amt == 0) {
                    fail("nothing read");
                }
                reader = log.openReader(position);
            } else {
                position += amt;
                offset += amt;
            }
        }

        TestUtils.fastAssertArrayEquals(expect, buf);

        assertEquals(finalAmt, reader.tryReadAny(buf, 0, 1));
        reader.release();
    }

    private static void writeTerm(StateLog log, int prevTerm, int term, int position, int len)
        throws IOException
    {
        var data = new byte[len];
        Arrays.fill(data, (byte) term);
        LogWriter writer = log.openWriter(prevTerm, term, position);
        write(writer, data);
        writer.release();
    }

    private static void write(LogWriter writer, byte[] data) throws IOException {
        write(writer, data, 0, data.length);
    }

    private static void write(LogWriter writer, byte[] data, int off, int len) throws IOException {
        assertTrue(writer.write(data, off, len, writer.position() + len) > 0);
    }

    static void readFully(StreamReplicator.Reader reader, byte[] buf) throws IOException {
        int off = 0;
        int len = buf.length;
        while (len > 0) {
            int amt = reader.read(buf, off, len);
            if (amt < 0) {
                throw new EOFException();
            }
            off += amt;
            len -= amt;
        }
    }
}
