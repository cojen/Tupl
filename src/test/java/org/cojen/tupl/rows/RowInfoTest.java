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

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RowInfoTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(RowInfoTest.class.getName());
    }

    @Test
    public void broken() throws Exception {
        try {
            RowInfo.find(String.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("must be an interface"));
        }

        try {
            RowInfo.find(Test1.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("doesn't match type"));
            assertTrue(e.getMessage().contains("no accessor method"));
            assertTrue(e.getMessage().contains("unsupported method"));
            assertTrue(e.getMessage().contains("cannot be nullable"));
            assertTrue(e.getMessage().contains("cannot be unsigned"));
            assertTrue(e.getMessage().contains("no mutator method"));
            assertTrue(e.getMessage().contains("has an unsupported type"));
        }

        try {
            RowInfo.find(Test3.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("primary key doesn't specify any columns"));
        }

        try {
            RowInfo.find(Test4.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("primary key refers to a column that doesn't"));
        }

        try {
            RowInfo.find(Test5.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("primary key refers to a column more than once"));
        }

        try {
            RowInfo.find(Test6.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("alternate key doesn't specify any columns"));
            assertTrue(e.getMessage().contains("secondary index refers to a column that doesn't"));
        }

        try {
            RowInfo.find(Test7.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("secondary index doesn't specify any columns"));
            assertTrue(e.getMessage().contains("alternate key refers to a column that doesn't"));
        }

        try {
            RowInfo.find(Test8.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("alternate key refers to a column more than once"));
            assertTrue(e.getMessage().contains("secondary index refers to a column more than"));
        }

        try {
            RowInfo.find(Test9.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("alternate key contains all columns of the prim"));
        }

        try {
            RowInfo.find(Test10.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("no mutator method"));
            assertTrue(e.getMessage().contains("cannot be unsigned"));
        }
    }

    @Test
    public void lenient() throws Exception {
        // Test2 doesn't have a primary key. RowInfo is lenient, but a table cannot be opened.

        RowInfo info = RowInfo.find(Test2.class);

        assertTrue(info.keyColumns.isEmpty());
        assertEquals(1, info.valueColumns.size());
        assertEquals(1, info.allColumns.size());

        var db = Database.open(new DatabaseConfig());

        try {
            db.openTable(Test2.class);
            fail();
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("No primary key"));
        }

        db.close();
    }

    public interface Test1 {
        void a(short x);
        void a(int x);

        int b(int x);

        @Nullable
        int c();
        void c(int x);

        @Unsigned
        String d();
        void d(String x);

        Short e();

        String[][] f();
        void f(String[][] x);
    }

    public interface Test2 {
        int a();
        void a(int x);
    }

    @PrimaryKey({})
    public interface Test3 {
        int a();
        void a(int x);
    }

    @PrimaryKey("x")
    public interface Test4 {
        int a();
        void a(int x);
    }

    @PrimaryKey({"a", "a"})
    public interface Test5 {
        int a();
        void a(int x);
    }

    @PrimaryKey("a")
    @AlternateKey({})
    @SecondaryIndex("x")
    public interface Test6 {
        int a();
        void a(int x);
    }

    @PrimaryKey("a")
    @AlternateKey("x")
    @SecondaryIndex({})
    public interface Test7 {
        int a();
        void a(int x);
    }

    @PrimaryKey("a")
    @AlternateKey({"a", "a"})
    @SecondaryIndex({"a", "a"})
    public interface Test8 {
        int a();
        void a(int x);
    }

    @PrimaryKey("a")
    @AlternateKey("a")
    public interface Test9 {
        int a();
        void a(int x);
    }

    @PrimaryKey("a")
    @AlternateKey("a")
    public interface Test10 {
        int a();
        void a(int x);

        String clone();

        @Unsigned
        boolean x();
        void x(boolean x);
    }

    @Test
    public void ignoreDefaults() throws Exception {
        RowInfo info = RowInfo.find(Test100.class);

        Database db = Database.open(new DatabaseConfig());

        Test100 row = db.openTable(Test100.class).newRow();
        assertEquals(123, row.compareTo(""));
        assertEquals(234, row.compareTo(row));
    }

    @PrimaryKey("a")
    public interface Test100 extends Comparable {
        int a();
        void a(int x);

        default String astr() {
            return String.valueOf(a());
        }

        static void foo() {
        }

        int hashCode();

        String toString();

        boolean equals(Object obj);

        Test100 clone();

        default int compareTo(Object obj) {
            return 123;
        }

        default int compareTo(Test100 obj) {
            return 234;
        }
    }

    @Test
    public void coveringIndex() throws Exception {
        RowInfo info = RowInfo.find(Test200.class);

        {
            assertEquals(2, info.keyColumns.size());
            var it = info.keyColumns.values().iterator();
            assertFalse(it.next().isDescending());
            assertTrue(it.next().isDescending());
        }

        {
            var set = info.secondaryIndexes;
            assertEquals(2, set.size());
            var it = set.iterator();
            ColumnSet cs = it.next();
            assertEquals(3, cs.keyColumns.size());
            assertEquals(0, cs.valueColumns.size());
            cs = it.next();
            assertEquals(3, cs.keyColumns.size());
            assertEquals(1, cs.valueColumns.size());
            assertEquals("d", cs.valueColumns.values().iterator().next().name);
        }
    }

    @PrimaryKey({"+a", "-b"})
    @SecondaryIndex({"c", "-a", "+b"})
    @SecondaryIndex({"c", "b", "a", "d"})
    public interface Test200 {
        int a();
        void a(int x);

        int b();
        void b(int x);

        String c();
        void c(String c);

        String d();
        void d(String d);

        String e();
        void e(String e);
    }
}
