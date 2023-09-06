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

import java.util.Comparator;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.*;

import org.cojen.tupl.diag.QueryPlan;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public class GroupedTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(GroupedTest.class.getName());
    }

    @Before
    public void setup() throws Exception {
        setup(Database.open(new DatabaseConfig()));
    }

    private void setup(Database db) throws Exception {
        mDb = db;
        mTable = db.openTable(TestRow.class);
    }

    @After
    public void teardown() throws Exception {
        mDb.close();
        mDb = null;
        TestUtils.deleteTempDatabases(getClass());
    }

    protected Database mDb;
    protected Table<TestRow> mTable;

    @PrimaryKey("id")
    @SecondaryIndex("name")
    public interface TestRow {
        long id();
        void id(long id);

        String name();
        void name(String name);

        int num();
        void num(int num);
    }

    public interface TestRowAgg {
        long count();
        void count(long count);

        long minNum();
        void minNum(long num);

        long maxNum();
        void maxNum(long num);

        long totalNum();
        void totalNum(long num);

        double avgNum();
        void avgNum(double num);
    }

    @PrimaryKey("name")
    public interface TestRowAggByName extends TestRowAgg {
        String name();
        void name(String name);
    }

    public static class Grouper1<T extends TestRowAgg> implements Grouper<TestRow, T> {
        private long count, minNum, maxNum, totalNum;

        @Override
        public TestRow begin(TestRow source) {
            count = 1;
            long num = source.num();
            totalNum = maxNum = minNum = num <= 0 ? 0 : num;
            return source;
        }

        @Override
        public TestRow accumulate(TestRow source) {
            count++;
            long num = source.num();
            if (num >= 0) {
                minNum = Math.min(minNum, num);
                maxNum = Math.max(maxNum, num);
                totalNum += num;
            }
            return source;
        }

        @Override
        public T finish(T target) {
            target.count(count);
            target.minNum(minNum);
            target.maxNum(maxNum);
            target.totalNum(totalNum);
            target.avgNum(((double) totalNum) / count);
            return target;
        }

        @Override
        public String sourceProjection() {
            return "num";
        }

        @Override
        public String toString() {
            return getClass().getSimpleName();
        }
    }

    public static class Grouper2<T extends TestRowAgg> extends Grouper1<T> {
        final String mExclude;
        String mCurrent;

        Grouper2(String exclude) {
            mExclude = exclude;
        }

        @Override
        public TestRow begin(TestRow source) {
            mCurrent = source.name();
            return super.begin(source);
        }

        @Override
        public T finish(T target) {
            if (mExclude.equals(mCurrent)) {
                return null;
            }
            return super.finish(target);
        }
    }

    @Test
    public void toOneRow() throws Exception {
        Table<TestRowAgg> grouped = mTable.group(TestRowAgg.class, Grouper1::new);

        assertTrue(grouped.isEmpty());
        assertFalse(grouped.anyRows(null));
        assertFalse(grouped.anyRows(null, "count != ?", 999));

        var row = grouped.newRow();
        row.count(10);
        assertFalse(grouped.load(null, row));
        assertEquals("{}", row.toString());
        row.count(10);
        assertFalse(grouped.exists(null, row));
        assertEquals("{*count=10}", row.toString());

        fill();

        assertFalse(grouped.isEmpty());
        assertTrue(grouped.anyRows(null));
        assertTrue(grouped.anyRows(null, "count != ?", 999));

        row = grouped.newRow();
        row.count(10);
        assertTrue(grouped.load(null, row));
        assertEquals("{avgNum=6.0, count=6, maxNum=21, minNum=1, totalNum=36}", row.toString());
        row = grouped.newRow();
        row.count(10);
        assertTrue(grouped.exists(null, row));
        assertEquals("{*count=10}", row.toString());

        QueryPlan plan = grouped.scannerPlan(null, null);
        assertEquals("""
- group: org.cojen.tupl.rows.GroupedTest$TestRowAgg
  using: Grouper1
  - full scan over primary key: org.cojen.tupl.rows.GroupedTest$TestRow
    key columns: +id
                     """,
                     plan.toString());

        try (var scanner = grouped.newScanner(null)) {
            row = scanner.row();
            assertEquals("{avgNum=6.0, count=6, maxNum=21, minNum=1, totalNum=36}", row.toString());
            assertNull(scanner.step());
        }

        String query = "{avgNum, +count}";

        plan = grouped.scannerPlan(null, query);
        assertEquals("""
- group: org.cojen.tupl.rows.GroupedTest$TestRowAgg
  using: Grouper1
  - full scan over primary key: org.cojen.tupl.rows.GroupedTest$TestRow
    key columns: +id
                     """,
                     plan.toString());

        try (var scanner = grouped.newScanner(null, query)) {
            row = scanner.row();
            assertEquals("{avgNum=6.0, count=6}", row.toString());
            assertNull(scanner.step());
        }

        query = "{avgNum, +count} minNum == ?";

        plan = grouped.scannerPlan(null, query);
        assertEquals("""
- filter: minNum == ?1
  - group: org.cojen.tupl.rows.GroupedTest$TestRowAgg
    using: Grouper1
    - full scan over primary key: org.cojen.tupl.rows.GroupedTest$TestRow
      key columns: +id
                     """,
                     plan.toString());

        try (var scanner = grouped.newScanner(null, query, 1)) {
            row = scanner.row();
            assertEquals("{avgNum=6.0, count=6}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = grouped.newScanner(null, query, 100)) {
            assertNull(scanner.row());
        }

        query = "minNum == ?";

        plan = grouped.scannerPlan(null, query);
        assertEquals("""
- filter: minNum == ?1
  - group: org.cojen.tupl.rows.GroupedTest$TestRowAgg
    using: Grouper1
    - full scan over primary key: org.cojen.tupl.rows.GroupedTest$TestRow
      key columns: +id
                     """,
                     plan.toString());

        try (var scanner = grouped.newScanner(null, query, 1)) {
            row = scanner.row();
            assertEquals("{avgNum=6.0, count=6, maxNum=21, minNum=1, totalNum=36}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = grouped.newScanner(null, query, 100)) {
            assertNull(scanner.row());
        }
    }

    @Test
    public void byName() throws Exception {
        Table<TestRowAggByName> grouped = mTable.group(TestRowAggByName.class, Grouper1::new);

        assertTrue(grouped.isEmpty());
        assertFalse(grouped.anyRows(null));
        assertFalse(grouped.anyRows(null, "count != ?", 999));

        var row = grouped.newRow();
        row.count(10);
        try {
            grouped.load(null, row);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Primary key"));
        }

        row.name("hello");
        assertFalse(grouped.load(null, row));
        assertEquals("{*name=hello}", row.toString());

        fill();

        assertFalse(grouped.isEmpty());
        assertTrue(grouped.anyRows(null));
        assertTrue(grouped.anyRows(null, "count != ?", 999));
        assertTrue(grouped.anyRows(null, "count == ?", 2));

        QueryPlan plan = grouped.scannerPlan(null, null);
        assertEquals("""
- group: org.cojen.tupl.rows.GroupedTest$TestRowAggByName
  using: Grouper1
  columns: name
  - sort: +name
    - full scan over primary key: org.cojen.tupl.rows.GroupedTest$TestRow
      key columns: +id
                     """,
                     plan.toString());

        TestRowAggByName lastRow = null;

        try (var scanner = grouped.newScanner(null)) {
            Comparator<? super TestRowAggByName> cmp = scanner.getComparator();

            row = scanner.row();
            expect("{name=hello, avgNum=1.5, count=2, maxNum=2, minNum=1, totalNum=3}", row);
            assertEquals(0, cmp.compare(row, row));
            lastRow = grouped.cloneRow(row);

            row = scanner.step(row);
            expect("{name=name, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);
            assertTrue(cmp.compare(row, lastRow) > 0);
            lastRow = grouped.cloneRow(row);

            row = scanner.step();
            expect("{name=readme, avgNum=15.5, count=2, maxNum=21, minNum=10, totalNum=31}", row);
            assertTrue(cmp.compare(row, lastRow) > 0);
            lastRow = grouped.cloneRow(row);

            row = scanner.step();
            expect("{name=world, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);
            assertTrue(cmp.compare(row, lastRow) > 0);

            assertNull(scanner.step());
        }

        String query = "name >= ? && name < ?";

        plan = grouped.scannerPlan(null, query);
        assertEquals("""
- group: org.cojen.tupl.rows.GroupedTest$TestRowAggByName
  using: Grouper1
  columns: name
  - primary join: org.cojen.tupl.rows.GroupedTest$TestRow
    key columns: +id
    - range scan over secondary index: org.cojen.tupl.rows.GroupedTest$TestRow
      key columns: +name, +id
      range: name >= ?1 .. name < ?2
                     """,
                     plan.toString());

        try (var scanner = grouped.newScanner(null, query, "readme", "readmf")) {
            row = scanner.row();
            expect("{name=readme, avgNum=15.5, count=2, maxNum=21, minNum=10, totalNum=31}", row);

            assertNull(scanner.step());
        }

        query = "maxNum >= ? && maxNum <= ?";

        plan = grouped.scannerPlan(null, query);
        assertEquals("""
- filter: maxNum >= ?1 && maxNum <= ?2
  - group: org.cojen.tupl.rows.GroupedTest$TestRowAggByName
    using: Grouper1
    columns: name
    - sort: +name
      - full scan over primary key: org.cojen.tupl.rows.GroupedTest$TestRow
        key columns: +id
                     """,
                     plan.toString());

        try (var scanner = grouped.newScanner(null, query, 1, 2)) {
            row = scanner.row();
            expect("{name=hello, avgNum=1.5, count=2, maxNum=2, minNum=1, totalNum=3}", row);

            row = scanner.step(row);
            expect("{name=name, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);

            row = scanner.step();
            expect("{name=world, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);

            assertNull(scanner.step());
        }

        query = "{+avgNum, *} name >= ? && name < ? && maxNum >= ? && maxNum <= ?";

        plan = grouped.scannerPlan(null, query);
        assertEquals("""
- sort: +avgNum
  - filter: maxNum >= ?3 && maxNum <= ?4
    - group: org.cojen.tupl.rows.GroupedTest$TestRowAggByName
      using: Grouper1
      columns: name
      - primary join: org.cojen.tupl.rows.GroupedTest$TestRow
        key columns: +id
        - range scan over secondary index: org.cojen.tupl.rows.GroupedTest$TestRow
          key columns: +name, +id
          range: name >= ?1 .. name < ?2
                     """,
                     plan.toString());

        try (var scanner = grouped.newScanner(null, query, "a", "z", 1, 2)) {
            row = scanner.row();
            expect("{name=name, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);

            row = scanner.step(row);
            expect("{name=world, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);

            row = scanner.step();
            expect("{name=hello, avgNum=1.5, count=2, maxNum=2, minNum=1, totalNum=3}", row);

            assertNull(scanner.step());
        }

        try (var scanner = grouped.newScanner(null, query, "readme", "readmef", 1, 2)) {
            assertNull(scanner.row());
        }

        query = "{-avgNum, *, ~totalNum} count >= ? && avgNum >= ?";

        plan = grouped.scannerPlan(null, query);
        assertEquals("""
- sort: -avgNum
  - filter: count >= ?1 && avgNum >= ?2
    - group: org.cojen.tupl.rows.GroupedTest$TestRowAggByName
      using: Grouper1
      columns: name
      - sort: +name
        - full scan over primary key: org.cojen.tupl.rows.GroupedTest$TestRow
          key columns: +id
                     """,
                     plan.toString());

        try (var scanner = grouped.newScanner(null, query, 1, 1.5)) {
            row = scanner.row();
            expect("{name=readme, avgNum=15.5, count=2, maxNum=21, minNum=10}", row);

            row = scanner.step();
            expect("{name=hello, avgNum=1.5, count=2, maxNum=2, minNum=1}", row);

            assertNull(scanner.step());
        }
    }

    private static void expect(String expect, Object obj) {
        assertEquals(expect, obj.toString());
    }

    @Test
    public void byNameFiltered() throws Exception {
        Table<TestRowAggByName> grouped =
            mTable.group(TestRowAggByName.class, () -> new Grouper2<>("readme"));

        fill();

        try (var scanner = grouped.newScanner(null)) {
            var row = scanner.row();
            expect("{name=hello, avgNum=1.5, count=2, maxNum=2, minNum=1, totalNum=3}", row);

            row = scanner.step(row);
            expect("{name=name, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);

            row = scanner.step();
            expect("{name=world, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);

            assertNull(scanner.step());
        }

        grouped = mTable.group(TestRowAggByName.class, () -> new Grouper2<>("world"));

        try (var scanner = grouped.newScanner(null)) {
            var row = scanner.row();
            expect("{name=hello, avgNum=1.5, count=2, maxNum=2, minNum=1, totalNum=3}", row);

            row = scanner.step(row);
            expect("{name=name, avgNum=1.0, count=1, maxNum=1, minNum=1, totalNum=1}", row);

            row = scanner.step();
            expect("{name=readme, avgNum=15.5, count=2, maxNum=21, minNum=10, totalNum=31}", row);

            assertNull(scanner.step());
        }
    }

    private void fill() throws Exception {
        Object[][] data = {
            {1, "hello", 1},
            {2, "world", 1},
            {3, "readme", 10},
            {4, "hello", 2},
            {5, "readme", 21},
            {6, "name", 1},
        };

        for (Object[] r : data) {
            var row = mTable.newRow();
            row.id((int) r[0]);
            row.name((String) r[1]);
            row.num((int) r[2]);
            assertTrue(mTable.insert(null, row));
        }
    }
}
