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

import java.lang.reflect.Method;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.Database;
import org.cojen.tupl.DatabaseConfig;
import org.cojen.tupl.RowIndex;
import org.cojen.tupl.RowScanner;

/**
 * Schema evolution tests.
 *
 * @author Brian S O'Neill
 */
@SuppressWarnings("unchecked")
public class EvolutionTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(EvolutionTest.class.getName());
    }

    @Test
    public void addColumns() throws Exception {
        // When adding new columns, columns for old rows should be null, 0, "", etc. When
        // loading new rows with old schema, new columns should be dropped.

        Database db = Database.open(new DatabaseConfig());

        Object[] toAdd = {
            boolean.class, "bo",
            byte.class, "by",
            char.class, "ch",
            double.class, "do",
            float.class, "fl",
            int.class, "in",
            long.class, "lo",
            short.class, "sh",

            Boolean.class, "boo",
            Byte.class, "byt",
            Character.class, "cha",
            Double.class, "dou",
            Float.class, "flo",
            Integer.class, "int",
            Long.class, "lon",
            Short.class, "sho",

            Boolean.class, "bool?",
            Byte.class, "byte?",
            Character.class, "char?",
            Double.class, "doub?",
            Float.class, "floa?",
            Integer.class, "inte?",
            Long.class, "long?",
            Short.class, "shor?",

            String.class, "st",
            BigInteger.class, "bi",
            BigDecimal.class, "bd",

            String.class, "str?",
            BigInteger.class, "bint?",
            BigDecimal.class, "bdec?",
        };

        var specs = new ArrayList<Object[]>();
        var indexes = new ArrayList<RowIndex<?>>();

        for (int i = 0; i <= toAdd.length; i += 2) {
            var spec = new Object[2 + i];
            spec[0] = long.class;
            spec[1] = "+key";

            for (int j=0; j<i; j++) {
                spec[2 + j] = toAdd[j];
            }

            specs.add(spec);

            Class<?> type = RowTestUtils.newRowType("test.evolve.MyStuff", spec);
            RowIndex<?> ix = db.openRowIndex(type);
            indexes.add(ix);

            insertRandom(RowUtils.scramble(8675309 + i), spec, ix, 10);
        }

        TreeMap<Object, List<TreeMap<String, Object>>> extracted = new TreeMap<>();

        for (int i=0; i<specs.size(); i++) {
            Object[] spec = specs.get(i);
            RowIndex<?> ix = indexes.get(i);

            Method[] getters = RowTestUtils.access(spec, ix.rowType())[0];

            RowScanner scanner = ix.newScanner(null);
            for (Object row = scanner.row(); row != null; row = scanner.step(row)) {
                TreeMap<String, Object> columns = extractColumns(getters, row);
                Object key = columns.remove("key");
                List<TreeMap<String, Object>> list = extracted.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    extracted.put(key, list);
                }
                list.add(columns);
            }
        }

        for (List<TreeMap<String, Object>> list : extracted.values()) {
            for (int i=0; i<list.size(); i++) {
                for (int j=0; j<list.size(); j++) {
                    if (i != j) {
                        compare(list.get(i), list.get(j));
                    }
                }
            }
        }
    }

    private static void compare(TreeMap<String, Object> aCols, TreeMap<String, Object> bCols) {
        for (Map.Entry<String, Object> e : aCols.entrySet()) {
            Object other = bCols.get(e.getKey());
            if (other != null || bCols.containsKey(e.getKey())) {
                assertEquals(e.getValue(), other);
            }
        }

        for (Map.Entry<String, Object> e : bCols.entrySet()) {
            Object other = aCols.get(e.getKey());
            if (other != null || aCols.containsKey(e.getKey())) {
                assertEquals(e.getValue(), other);
            }
        }
    }

    private static TreeMap<String, Object> extractColumns(Method[] getters, Object row)
        throws Exception
    {
        var map = new TreeMap<String, Object>();
        for (Method m : getters) {
            map.put(m.getName(), m.invoke(row));
        }
        return map;
    }

    private static void insertRandom(long seed, Object[] spec, RowIndex ix, int amt)
        throws Exception
    {
        Method[][] access = RowTestUtils.access(spec, ix.rowType());
        Method[] getters = access[0];
        Method[] setters = access[1];

        Random rnd = new Random(seed);

        var inserted = new Object[amt];

        for (int i=0; i<amt; i++) {
            var row = ix.newRow();
            for (int j=0; j<setters.length; j++) {
                setters[j].invoke(row, RowTestUtils.randomValue(rnd, spec, j));
            }
            // Collision is possible here, although unlikely.
            assertTrue(ix.insert(null, row));
            inserted[i] = row;
        }

        // Verify by loading everything back. Assume first column is the primary key.

        rnd = new Random(seed);

        for (int i=0; i<inserted.length; i++) {
            var row = ix.newRow();
            for (int j=0; j<setters.length; j++) {
                Object value = RowTestUtils.randomValue(rnd, spec, j);
                if (j == 0) {
                    setters[j].invoke(row, value);
                }
            }
            ix.load(null, row);
            assertEquals(inserted[i], row);
        }
    }
}