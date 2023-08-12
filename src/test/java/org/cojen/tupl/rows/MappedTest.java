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

import java.util.Arrays;
import java.util.Random;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.*;

import org.cojen.tupl.diag.QueryPlan;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public class MappedTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(MappedTest.class.getName());
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
    @SecondaryIndex("str")
    public interface TestRow {
        long id();
        void id(long id);

        String str();
        void str(String str);

        int num();
        void num(int num);
    }

    @Test
    public void noInverse() throws Exception {
        // Swap the str and num columns.
        Table<TestRow> mapped = mTable.map(TestRow.class, (source, target) -> {
            assertEquals("{}", target.toString());
            target.id(source.id());
            target.str(String.valueOf(source.num()));
            try {
                target.num(Integer.parseInt(source.str()));
            } catch (NumberFormatException e) {
                return null;
            }
            return target;
        });

        assertTrue(mapped.isEmpty());

        TestRow row = mTable.newRow();
        row.id(1);
        row.str("hello");
        row.num(123);
        mTable.store(null, row);

        // String cannot be parsed as an integer.
        assertTrue(mapped.isEmpty());

        row.id(2);
        row.str("234");
        mTable.store(null, row);

        assertFalse(mapped.isEmpty());

        try {
            mapped.load(null, row);
            fail();
        } catch (ViewConstraintException e) {
        }

        try {
            mapped.exists(null, row);
            fail();
        } catch (ViewConstraintException e) {
        }

        try {
            mapped.store(null, row);
            fail();
        } catch (UnmodifiableViewException e) {
        }

        try {
            mapped.exchange(null, row);
            fail();
        } catch (UnmodifiableViewException e) {
        }

        try {
            mapped.insert(null, row);
            fail();
        } catch (UnmodifiableViewException e) {
        }

        try {
            mapped.replace(null, row);
            fail();
        } catch (UnmodifiableViewException e) {
        }

        try {
            mapped.update(null, row);
            fail();
        } catch (UnmodifiableViewException e) {
        }

        try {
            mapped.merge(null, row);
            fail();
        } catch (UnmodifiableViewException e) {
        }

        try {
            mapped.delete(null, row);
            fail();
        } catch (UnmodifiableViewException e) {
        }

        QueryPlan.Mapper plan = (QueryPlan.Mapper) mapped.scannerPlan(null, null);
        assertEquals(TestRow.class.getName(), plan.target);
        assertTrue(plan.using.contains("org.cojen.tupl.rows.MappedTest"));
        assertEquals(mTable.scannerPlan(null, null), plan.source);

        QueryPlan plan2 = mapped.scannerPlan(null, "{*}");
        assertEquals(plan, plan2);
        assertEquals(plan.hashCode(), plan2.hashCode());
        assertEquals(plan.toString(), plan2.toString());

        row = mTable.newRow();
        row.id(3);
        row.str("33");
        row.num(333);
        mTable.store(null, row);

        try {
            try (var updater = mapped.newUpdater(null)) {
                for (row = updater.row(); row != null; ) {
                    row = updater.delete(row);
                }
            }
            fail();
        } catch (UnmodifiableViewException e) {
        }

        try {
            try (var updater = mapped.newUpdater(null, "{*}")) {
                for (row = updater.row(); row != null; ) {
                    row = updater.delete(row);
                }
            }
            fail();
        } catch (UnmodifiableViewException e) {
        }

        try (var scanner = mapped.newScanner(null)) {
            row = scanner.row();
            assertEquals("{id=2, num=234, str=123}", row.toString());
            row = scanner.step(row);
            assertEquals("{id=3, num=33, str=333}", row.toString());
            assertNull(scanner.step(row));
        }

        try (var scanner = mapped.newScanner(null, "{*}")) {
            row = scanner.row();
            assertEquals("{id=2, num=234, str=123}", row.toString());
            row = scanner.step();
            assertEquals("{id=3, num=33, str=333}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = mapped.newScanner(null, "{id, num}")) {
            row = scanner.row();
            assertEquals("{id=2, num=234}", row.toString());
            row = scanner.step();
            assertEquals("{id=3, num=33}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = mapped.newScannerWith(null, mapped.newRow())) {
            row = scanner.row();
            assertEquals("{id=2, num=234, str=123}", row.toString());
            row = scanner.step(row);
            assertEquals("{id=3, num=33, str=333}", row.toString());
            assertNull(scanner.step(row));
        }

        try (var scanner = mapped.newScannerWith(null, mapped.newRow(), "{*}")) {
            row = scanner.row();
            assertEquals("{id=2, num=234, str=123}", row.toString());
            row = scanner.step();
            assertEquals("{id=3, num=33, str=333}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = mapped.newScanner(null, "num >= ?", 100)) {
            row = scanner.row();
            assertEquals("{id=2, num=234, str=123}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = mapped.newScanner(null, "{id, str} num >= ?", 100)) {
            row = scanner.row();
            assertEquals("{id=2, str=123}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = mapped.newUpdater(null, "num < ?", 100)) {
            row = scanner.row();
            assertEquals("{id=3, num=33, str=333}", row.toString());
            assertNull(scanner.step());
        }

        var plan3 = (QueryPlan.Sort) mapped.scannerPlan(null, "{-str, id}");
        assertEquals("[-str]", Arrays.toString(plan3.sortColumns));
        assertEquals(mTable.scannerPlan(null, null), ((QueryPlan.Mapper) plan3.source).source);

        try (var scanner = mapped.newScanner(null, "{-str, id}")) {
            row = scanner.row();
            assertEquals("{id=3, str=333}", row.toString());
            row = scanner.step();
            assertEquals("{id=2, str=123}", row.toString());
            assertNull(scanner.step());
        }

        mapped.close();
        assertFalse(mapped.isClosed());
    }

    @Test
    public void withInverse() throws Exception {
        // Swap the str and num columns.
        Table<TestRow> mapped = mTable.map(TestRow.class, new Swapper());
        
        assertTrue(mapped.isEmpty());

        TestRow row = mTable.newRow();
        row.id(1);
        row.str("hello");
        row.num(123);
        mTable.store(null, row);

        // String cannot be parsed as an integer.
        assertTrue(mapped.isEmpty());

        assertFalse(mapped.exists(null, row));
        assertEquals("{id=1, num=123, str=hello}", row.toString()); // no side effect

        assertFalse(mapped.load(null, row));
        // When the load fails, only the target columns which map to source primary key columns
        // should still be set. This guards against accidentally using a row when the caller
        // failed to check the load return value. Loading against a plain table behaves in a
        // similar fashion -- it only keeps the primary key columns.
        assertEquals("{id=1}", row.toString());

        row = mTable.newRow();
        row.id(2);
        row.str("234");
        row.num(123);
        mTable.store(null, row);

        assertFalse(mapped.isEmpty());

        assertTrue(mapped.load(null, row));
        assertEquals("{id=2, num=234, str=123}", row.toString());

        assertTrue(mapped.exists(null, row));

        assertFalse(mapped.insert(null, row));

        try {
            row.str("xxx");
            mapped.store(null, row);
            fail();
        } catch (NumberFormatException e) {
        }

        row.str("2");
        mapped.store(null, row);
        assertTrue(mapped.load(null, row));
        assertEquals("{id=2, num=234, str=2}", row.toString());

        row.num(111);
        TestRow old = mapped.exchange(null, row);
        assertEquals("{id=2, num=111, str=2}", row.toString());
        assertEquals("{id=2, num=234, str=2}", old.toString());
        assertTrue(mapped.load(null, row));
        assertEquals("{id=2, num=111, str=2}", row.toString());

        row = mapped.newRow();
        row.id(999);
        try {
            mapped.exchange(null, row);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("unset: num, str"));
        }
        assertEquals("{*id=999}", row.toString());

        row.num(999);
        row.str("9999");
        assertNull(mapped.exchange(null, row));
        assertEquals("{id=999, num=999, str=9999}", row.toString());
        assertTrue(mapped.load(null, row));
        assertEquals("{id=999, num=999, str=9999}", row.toString());

        row.str("hello");
        mTable.store(null, row);
        row.str("8");
        assertNull(mapped.exchange(null, row));
        assertTrue(mapped.load(null, row));
        assertEquals("{id=999, num=999, str=8}", row.toString());

        row = mapped.newRow();
        row.id(1000);
        row.num(1000);
        row.str("1000");
        assertTrue(mapped.insert(null, row));
        assertEquals("{id=1000, num=1000, str=1000}", row.toString());
        assertTrue(mapped.load(null, row));
        assertEquals("{id=1000, num=1000, str=1000}", row.toString());

        row.num(1);
        assertTrue(mapped.replace(null, row));
        assertEquals("{id=1000, num=1, str=1000}", row.toString());
        assertTrue(mapped.load(null, row));
        assertEquals("{id=1000, num=1, str=1000}", row.toString());

        row.id(0);
        assertFalse(mapped.replace(null, row));
        assertEquals("{*id=0, num=1, str=1000}", row.toString());

        row.id(1000);
        row.num(2);
        assertTrue(mapped.update(null, row));
        assertEquals("{id=1000, num=2, str=1000}", row.toString());
        assertTrue(mapped.load(null, row));
        assertEquals("{id=1000, num=2, str=1000}", row.toString());

        row.id(0);
        row.num(9);
        assertFalse(mapped.update(null, row));
        assertEquals("{*id=0, *num=9, str=1000}", row.toString());

        row = mapped.newRow();
        row.id(1000);
        row.num(99);
        assertTrue(mapped.merge(null, row));
        assertEquals("{id=1000, num=99, str=1000}", row.toString());
        assertTrue(mapped.load(null, row));
        assertEquals("{id=1000, num=99, str=1000}", row.toString());

        row.id(0);
        row.num(91);
        assertFalse(mapped.merge(null, row));
        assertEquals("{*id=0, *num=91, str=1000}", row.toString());

        row.id(1000);
        row.str("hello"); // invalid num
        mTable.store(null, row);

        row.str("111");
        assertTrue(mapped.merge(null, row));
        assertEquals("{}", row.toString());
        row.id(1000);
        assertFalse(mapped.load(null, row));

        assertTrue(mapped.delete(null, row));
        assertFalse(mapped.delete(null, row));

        try (var scanner = mapped.newScanner(null)) {
            row = scanner.row();
            assertEquals("{id=2, num=111, str=2}", row.toString());
            row = scanner.step(row);
            assertEquals("{id=999, num=999, str=8}", row.toString());
            assertNull(scanner.step(row));
        }

        var plan = (QueryPlan.Mapper) mapped.scannerPlan(null, "num == ? && str == ?");
        var sub1 = (QueryPlan.Filter) plan.source;
        assertEquals("num == ?4", sub1.expression);
        var sub2 = (QueryPlan.PrimaryJoin) sub1.source;
        var sub3 = (QueryPlan.RangeScan) sub2.source;
        assertEquals("str >= ?3", sub3.low);
        assertEquals("str <= ?3", sub3.high);

        try (var scanner = mapped.newScanner(null, "num == ? && str == ?", 111, 2)) {
            row = scanner.row();
            assertEquals("{id=2, num=111, str=2}", row.toString());
            assertNull(scanner.step(row));
        }

        try (var updater = mapped.newUpdater(null)) {
            for (row = updater.row(); row != null; ) {
                row.num(row.num() - 1);
                row = updater.update(row);
            }
            try {
                updater.update(row);
                fail();
            } catch (IllegalStateException e) {
            }
        }

        try (var scanner = mapped.newScanner(null)) {
            row = scanner.row();
            assertEquals("{id=2, num=110, str=2}", row.toString());
            row = scanner.step(row);
            assertEquals("{id=999, num=998, str=8}", row.toString());
            assertNull(scanner.step(row));
        }

        var plan2 = (QueryPlan.Sort) mapped.scannerPlan(null, "{-str, id}");
        assertEquals("[-str]", Arrays.toString(plan2.sortColumns));
        assertEquals(mTable.scannerPlan(null, null), ((QueryPlan.Mapper) plan2.source).source);

        try (var scanner = mapped.newScanner(null, "{-str, id}")) {
            row = scanner.row();
            assertEquals("{id=999, str=8}", row.toString());
            row = scanner.step(row);
            assertEquals("{id=2, str=2}", row.toString());
            assertNull(scanner.step(row));
        }

        plan = (QueryPlan.Mapper) mapped.scannerPlan(null, "{-id}");
        assertTrue(((QueryPlan.FullScan) plan.source).reverse);

        try (var scanner = mapped.newScanner(null, "{-id, *}")) {
            row = scanner.row();
            assertEquals("{id=999, num=998, str=8}", row.toString());
            row = scanner.step(row);
            assertEquals("{id=2, num=110, str=2}", row.toString());
            assertNull(scanner.step(row));
        }

        try (var updater = mapped.newUpdater(Transaction.BOGUS, "num == ?", 998)) {
            row = updater.row();
            row.str("888");
            assertNull(updater.update(row));
        }

        try (var scanner = mapped.newScanner(null)) {
            row = scanner.row();
            assertEquals("{id=2, num=110, str=2}", row.toString());
            row = scanner.step(row);
            assertEquals("{id=999, num=998, str=888}", row.toString());
            assertNull(scanner.step(row));
        }

        try (var updater = mapped.newUpdater(null, "{-num, *}")) {
            row = updater.row();
            assertEquals("{id=999, num=998, str=888}", row.toString());
            row = updater.step(row);
            assertEquals("{id=2, num=110, str=2}", row.toString());
            assertNull(updater.step(row));
        }

        Transaction txn = mapped.newTransaction(null);
        try {
            try (var updater = mapped.newUpdater(null, "{-num, *}")) {
                for (row = updater.row(); row != null; ) {
                    row.num(row.num() - 1);
                    row = updater.update(row);
                }
                try {
                    updater.update(row);
                    fail();
                } catch (IllegalStateException e) {
                }
            }

            try (var scanner = mapped.newScanner(null)) {
                row = scanner.row();
                assertEquals("{id=2, num=109, str=2}", row.toString());
                row = scanner.step(row);
                assertEquals("{id=999, num=997, str=888}", row.toString());
                assertNull(scanner.step(row));
            }
        } finally {
            txn.reset();
        }

        row = mTable.newRow();
        row.id(999);
        row.str("hello");
        assertTrue(mTable.update(null, row));

        try (var updater = mapped.newUpdater(null)) {
            row = updater.row();
            row.num(row.num() - 1);
            assertNull(updater.update(row));
        }

        try (var updater = mapped.newUpdater(null)) {
            for (row = updater.row(); row != null; row = updater.delete());
        }

        assertTrue(mapped.isEmpty());
        assertFalse(mTable.isEmpty());

        try (var scanner = mTable.newScanner(null)) {
            row = scanner.row();
            assertEquals("{id=1, num=123, str=hello}", row.toString());
            row = scanner.step(row);
            assertEquals("{id=999, num=888, str=hello}", row.toString());
            assertNull(scanner.step(row));
        }
    }

    /**
     * Swaps the str and num columns.
     */
    public static class Swapper implements Mapper.Identity<TestRow, TestRow> {
        @Override
        public TestRow map(TestRow source, TestRow target) {
            assertEquals("{}", target.toString());
            target.id(source.id());
            target.str(String.valueOf(source.num()));
            try {
                target.num(Integer.parseInt(source.str()));
            } catch (NumberFormatException e) {
                return null;
            }
            return target;
        }

        public static String num_to_str(int num) {
            return String.valueOf(num);
        }

        public static int str_to_num(String str) {
            return Integer.parseInt(str);
        }
    }

    @Test
    public void rename() throws Exception {
        Table<Renamed> mapped = mTable.map(Renamed.class, new Renamer());

        Renamed row = mapped.newRow();
        row.identifier(1);
        row.string("hello");
        row.number(123);
        mapped.store(null, row);

        row = mapped.newRow();
        row.identifier(2);
        row.string("world");
        row.number(456);
        mapped.store(null, row);

        try (var scanner = mapped.newScanner(null)) {
            row = scanner.row();
            assertEquals("{identifier=1, number=123, string=hello}", row.toString());
            row = scanner.step();
            assertEquals("{identifier=2, number=456, string=world}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = mTable.newScanner(null)) {
            var sourceRow = scanner.row();
            assertEquals("{id=1, num=123, str=hello}", sourceRow.toString());
            sourceRow = scanner.step();
            assertEquals("{id=2, num=456, str=world}", sourceRow.toString());
            assertNull(scanner.step());
        }

        var plan = (QueryPlan.Mapper) mapped.scannerPlan(null, "string > ? && number != ?");
        var sub1 = (QueryPlan.Filter) plan.source;
        assertEquals("str > ?3 && num != ?4", sub1.expression);

        try (var scanner = mapped.newScanner(null, "string > ? && number != ?", "hello", 0)) {
            row = scanner.row();
            assertEquals("{identifier=2, number=456, string=world}", row.toString());
            assertNull(scanner.step());
        }

        try (var scanner = mapped.newScanner(null, "{identifier, number}")) {
            row = scanner.row();
            assertEquals("{identifier=1, number=123}", row.toString());
            row = scanner.step(row);
            assertEquals("{identifier=2, number=456}", row.toString());
            assertNull(scanner.step());
        }

        String query = "{-string, identifier} string >= ?";

        var plan2 = (QueryPlan.Sort) mapped.scannerPlan(null, query);
        assertEquals("[-string]", Arrays.toString(plan2.sortColumns));
        assertEquals(mTable.scannerPlan(null, "str >= ?2"),
                     ((QueryPlan.Mapper) plan2.source).source);

        try (var scanner = mapped.newScanner(null, query, "a")) {
            row = scanner.row();
            assertEquals("{identifier=2, string=world}", row.toString());
            row = scanner.step(row);
            assertEquals("{identifier=1, string=hello}", row.toString());
            assertNull(scanner.step());
        }
    }

    public static interface Renamed {
        long identifier();
        void identifier(long id);

        String string();
        void string(String str);

        int number();
        void number(int num);
    }

    public static class Renamer implements Mapper<TestRow, Renamed> {
        @Override
        public Renamed map(TestRow source, Renamed target) {
            assertEquals("{}", target.toString());
            target.identifier(source.id());
            target.string(source.str());
            target.number(source.num());
            return target;
        }

        public static long identifier_to_id(long id) {
            return id;
        }

        public static String string_to_str(String str) {
            return str;
        }

        public static int number_to_num(int num) {
            return num;
        }
    }

    @Test
    public void sortMany() throws Exception {
        sortMany(5000);
    }

    @Test
    public void sortManyMore() throws Exception {
        teardown();
        setup(TestUtils.newTempDatabase(getClass(), 10_000_000));
        sortMany(1_500_000);
    }

    private void sortMany(int amount) throws Exception {
        Table<Renamed> mapped = mTable.map(Renamed.class, new Renamer());

        var plan = (QueryPlan.Sort) mapped.scannerPlan(null, "{+number, *}");
        assertEquals("[+number]", Arrays.toString(plan.sortColumns));

        var rnd = new Random(amount);

        long checksum = 0;

        for (int i = 1; i <= amount; i++) {
            Renamed row = mapped.newRow();
            row.identifier(i);
            row.string("hello-" + i);
            int num = rnd.nextInt();
            row.number(num);
            checksum += num;
            mapped.store(null, row);
        }

        int num = 0;
        int last = Integer.MIN_VALUE;
        long actualChecksum = 0;

        try (var scanner = mapped.newScanner(null, "{+number, *}")) {
            for (var row = scanner.row(); row != null; row = scanner.step(row)) {
                num++;
                assertTrue(row.number() >= last);
                actualChecksum += row.number();
                last = row.number();
            }
        }

        assertEquals(amount, num);
        assertEquals(checksum, actualChecksum);
    }
}
