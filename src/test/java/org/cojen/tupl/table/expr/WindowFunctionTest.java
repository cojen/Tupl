/*
 *  Copyright (C) 2024 Cojen.org
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

package org.cojen.tupl.table.expr;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.*;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public class WindowFunctionTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(WindowFunctionTest.class.getName());
    }

    @PrimaryKey("id")
    public static interface TestRow extends Row {
        long id();
        void id(long id);

        int a();
        void a(int a);

        int b();
        void b(int b);

        int c();
        void c(int c);

        int d();
        void d(int d);
    }

    private Database mDb;
    private Table<TestRow> mTable;

    @After
    public void teardown() throws Exception {
        if (mDb != null) {
            mDb.close();
        }
    }

    private void fill() throws Exception {
        mDb = Database.open(new DatabaseConfig().directPageAccess(false));
        mTable = mDb.openTable(TestRow.class);

        int[][] data = {
            {1,  1,  3,  1,  1},
            {2,  1, -3, -3,  0},
            {3,  1, -3,  2,  4},
            {4,  1,  0, -3, -3},
            {5,  1,  2, -3,  2},
            {6,  2,  4, -1,  1},
            {7,  2,  1,  0,  4},
            {8,  2,  1, -3,  0},
            {9,  2,  4, -2,  3},
            {10, 2, -4, -1,  0},
            {11, 3,  3,  1,  4},
            {12, 3,  2,  0,  4},
            {13, 3,  0, -3, -1},
            {14, 3,  0, -3,  3},
            {15, 3,  1, -4,  2},
            {16, 4,  3, -3, -1},
            {17, 4,  0, -4,  1},
            {18, 4, -4, -3,  2},
            {19, 4,  4, -4,  3},
            {20, 4,  4, -3, -1},
        };

        for (int[] dataRow : data) {
            TestRow row = mTable.newRow();
            row.id(dataRow[0]);
            row.a(dataRow[1]);
            row.b(dataRow[2]);
            row.c(dataRow[3]);
            row.d(dataRow[4]);
            mTable.insert(null, row);
        }
    }

    @Test
    public void variableRange() throws Exception {
        fill();

        try {
            mTable.derive("{a; c = count(b, rows:c..d), s = sum(b, rows:c..d)}");
            fail();
        } catch (QueryException e) {
            assertTrue(e.getMessage().contains
                       ("depends on an expression which accumulates group results"));
        }

        {
            Object[][] expect = {
                // a, min, max, sum, cnt, avg
                {1,   -3,   -3,  -3,  1, -3.0},
                {1,   -3,    3,   0,  2,  0.0},
                {1,    2,    2,   2,  1,  2.0},
                {1,    3,    3,   3,  1,  3.0},
                {1,   -3,    2,  -4,  4, -1.0},
                {2,    1,    4,   5,  2,  2.5},
                {2,   -4,    4,   2,  4,  0.5},
                {2,    1,    4,   6,  3,  2.0},
                {2,   -4,    4,   2,  4,  0.5},
                {2,   -4,    4,   0,  2,  0.0},
                {3,    0,    2,   3,  4,  0.75},
                {3,    0,    2,   3,  4,  0.75},
                {3,    2,    3,   5,  2,  2.5},
                {3,    0,    3,   6,  5,  1.2},
                {3,    0,    3,   6,  5,  1.2},
                {4, null, null,   0,  0, null},
                {4,   -4,    3,  -1,  3, -0.3333333333333333},
                {4,   -4,    4,   7,  5,  1.4},
                {4,   -4,    4,   7,  5,  1.4},
                {4,   -4,    4,   0,  3,  0.0},
            };

            String query = "{a; min = min(b, rows:c..d), max = max(b, rows:c..d), " +
                "sum = sum(b, rows:c..d), cnt = count(b, rows:c..d), avg = avg(b, rows:c..d)}";

            verify(expect, query);
        }

        {
            Object[][] expect = {
                // a, min, max, sum, cnt, avg
                {1,   -3,   3,   0,   2,  0.0},
                {1,   -3,  -3,  -3,   1, -3.0},
                {1,   -3,   2,  -1,   3, -0.3333333333333333},
                {1,    0,   2,   2,   2,  1.0},
                {1,    2,   2,   2,   1,  2.0},
                {2,    1,   4,   5,   2,  2.5},
                {2,   -4,   4,   2,   4,  0.5},
                {2,    1,   1,   1,   1,  1.0},
                {2,   -4,   4,   0,   2,  0.0},
                {2,   -4,  -4,  -4,   1, -4.0},
                {3,    0,   3,   6,   5,  1.2},
                {3,    0,   2,   3,   4,  0.75},
                {3,    0,   0,   0,   2,  0.0},
                {3,    0,   1,   1,   2,  0.5},
                {3,    1,   1,   1,   1,  1.0},
                {4,    0,   3,   3,   2,  1.5},
                {4,   -4,   0,  -4,   2, -2.0},
                {4,   -4,   4,   4,   3,  1.3333333333333333},
                {4,    4,   4,   8,   2,  4.0},
                {4,    4,   4,   4,   1,  4.0},
            };

            String query = "{a; ~e = iif(d >= 0, d, -d), " +
                "min = min(b, rows:iif(true, 0, 0)..e), max = max(b, rows:0..e), " +
                "sum = sum(b, rows:0..e), cnt = count(b, rows:0..e), avg = avg(b, rows:0..e)}";

            verify(expect, query);
        }

        {
            Object[][] expect = {
                // a, min, max, sum, cnt, avg
                {1,    3,   3,   3,   1,  3.0},
                {1,   -3,   3,   0,   2,  0.0},
                {1,   -3,   3,  -3,   3, -1.0},
                {1,   -3,   3,  -3,   4, -0.75},
                {1,   -3,   2,  -4,   4, -1.0},
                {2,    4,   4,   4,   1,  4.0},
                {2,    1,   1,   1,   1,  1.0},
                {2,    1,   4,   6,   3,  2.0},
                {2,    1,   4,   6,   3,  2.0},
                {2,   -4,   4,   0,   2,  0.0},
                {3,    3,   3,   3,   1,  3.0},
                {3,    2,   2,   2,   1,  2.0},
                {3,    0,   3,   5,   3,  1.6666666666666667},
                {3,    0,   3,   5,   4,  1.25},
                {3,    0,   3,   6,   5,  1.2},
                {4,    3,   3,   3,   1,  3.0},
                {4,    0,   3,   3,   2,  1.5},
                {4,   -4,   3,  -1,   3, -0.3333333333333333},
                {4,   -4,   4,   3,   4,  0.75},
                {4,   -4,   4,   4,   4,  1.0},
            };

            String query = "{a; ~s = iif(c <= 0, c, -c), " +
                "min = min(b, rows:s..iif(true, 0, 0)), max = max(b, rows:s..0), " +
                "sum = sum(b, rows:s..0), cnt = count(b, rows:s..0), avg = avg(b, rows:s..0)}";

            verify(expect, query);
        }

        {
            Object[][] expect = {
                // a, v1, v2, v3, v4,  v5
                {1,    1, -3,  0, -4,  0},
                {1,    0,  0, -2,  0,  0},
                {1,    0,  0, -6,  0,  4},
                {1,    2,  3,  0, -3,  0},
                {1,    3,  0,  0,  0,  0},
                {2,    8,  0,  0, -6,  0},
                {2,    4,  0, -6,  0,  0},
                {2,    8,  0,  0, -5,  0},
                {2,    7,  0,  0,  0,  0},
                {2,    0,  0, -7,  0,  8},
                {3,    6,  0, -7,  0,  0},
                {3,    6,  0, -7,  0,  0},
                {3,    7,  0,  0, -3,  0},
                {3,   10,  0, -7,  0,  0},
                {3,   12,  0,  0,  0,  0},
                {4,    5,  0,  0, -14, 0},
                {4,    0,  0, -7,  0,  0},
                {4,    0,  0, -17, 0,  0},
                {4,    4,  0,  0,  0,  0},
                {4,    5,  0,  0, -7,  0},
            };

            String query = "{a; v1 = sum(d, rows:c..b), v2 = sum(b, rows:d..c), " +
                "v3 = sum(c, rows:b..d), v4 = sum(c, rows:d..b), v5 = sum(d, rows:b..c)}";

            verify2(expect, query);
        }

        {
            Object[][] expect = {
                // a, v1, v2, v3, v4, v5
                {1, 0.3333333333333333, -3.0, null, -1.3333333333333333, null},
                {1, null, null, -1.0, null, null},
                {1, null, null, -1.2, null, 0.8},
                {1, 0.5, 3.0, null, -0.75, null},
                {1, 0.75, null, null, null, null},
                {2, 1.6, null, null, -1.5, null},
                {2, 2.0, null, -2.0, null, null},
                {2, 2.0, null, null, -2.5, null},
                {2, 1.75, null, null, null, null},
                {2, null, null, -1.4, null, 2.0},
                {3, 2.0, null, -3.5, null, null},
                {3, 2.0, null, -3.5, null, null},
                {3, 2.3333333333333335, null, null, -1.5, null},
                {3, 2.5, null, -3.5, null, null},
                {3, 2.4, null, null, null, null},
                {4, 1.25, null, null, -3.5, null},
                {4, 0.0, null, -3.5, null, null},
                {4, null, null, -3.4, null, null},
                {4, 0.8, null, null, null, null},
                {4, 1.25, null, null, -3.5, null},
            };

            String query = "{a; v1 = avg(d, rows:c..b), v2 = avg(b, rows:d..c), " +
                "v3 = avg(c, rows:b..d), v4 = avg(c, rows:d..b), v5 = avg(d, rows:b..c)}";

            verify3(expect, query);
        }
    }

    private void verify(Object[][] expect, String query) throws Exception {
        int i = 0;

        try (Scanner<Row> s = mTable.derive(query).newScanner(null)) {
            for (Row row = s.row(); row != null; row = s.step(row)) {
                Object[] expectRow = expect[i++];
                assertEquals(expectRow[0], row.get_int("a"));
                assertEquals(expectRow[1], row.getInteger("min"));
                assertEquals(expectRow[2], row.getInteger("max"));
                assertEquals(expectRow[3], row.get_int("sum"));
                assertEquals(expectRow[4], row.get_int("cnt"));
                assertEquals(expectRow[5], row.getDouble("avg"));
            }
        }

        assertEquals(i, expect.length);
    }

   private void verify2(Object[][] expect, String query) throws Exception {
        int i = 0;

        try (Scanner<Row> s = mTable.derive(query).newScanner(null)) {
            for (Row row = s.row(); row != null; row = s.step(row)) {
                Object[] expectRow = expect[i++];
                assertEquals(expectRow[0], row.get_int("a"));
                assertEquals(expectRow[1], row.get_int("v1"));
                assertEquals(expectRow[2], row.get_int("v2"));
                assertEquals(expectRow[3], row.get_int("v3"));
                assertEquals(expectRow[4], row.get_int("v4"));
                assertEquals(expectRow[5], row.get_int("v5"));
            }
        }

        assertEquals(i, expect.length);
    }

   private void verify3(Object[][] expect, String query) throws Exception {
        int i = 0;

        try (Scanner<Row> s = mTable.derive(query).newScanner(null)) {
            for (Row row = s.row(); row != null; row = s.step(row)) {
                Object[] expectRow = expect[i++];
                assertEquals(expectRow[0], row.get_int("a"));
                assertEquals(expectRow[1], row.getDouble("v1"));
                assertEquals(expectRow[2], row.getDouble("v2"));
                assertEquals(expectRow[3], row.getDouble("v3"));
                assertEquals(expectRow[4], row.getDouble("v4"));
                assertEquals(expectRow[5], row.getDouble("v5"));
            }
        }

        assertEquals(i, expect.length);
    }
}
