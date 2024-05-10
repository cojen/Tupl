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

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.cojen.tupl.table.ColumnInfo;
import org.cojen.tupl.table.ColumnSet;
import org.cojen.tupl.table.RowGen;
import org.cojen.tupl.table.RowInfo;
import org.cojen.tupl.table.RowMethodsMaker;
import org.cojen.tupl.table.RowUtils;
import org.cojen.tupl.table.Unpersisted;
import org.cojen.tupl.table.WeakCache;

import org.cojen.maker.ClassMaker;
import org.cojen.maker.MethodMaker;

import org.cojen.tupl.Hidden;
import org.cojen.tupl.Nullable;
import org.cojen.tupl.Row;

/**
 * 
 *
 * @author Brian S. O'Neill
 */
public final class TupleType extends Type implements Iterable<Column> {
    /**
     * Makes a type which has a generated row type class. Columns aren't defined for excluded
     * projections.
     *
     * @throws IllegalArgumentException if any names are duplicated
     */
    public static TupleType make(Collection<ProjExpr> projection) {
        var columns = new TreeMap<String, Column>();

        for (ProjExpr pe : projection) {
            if (!pe.hasExclude()) {
                boolean hidden = (pe.wrapped() instanceof ColumnExpr ce)
                    ? ce.column().isHidden() : false;
                addColumn(columns, Column.make(pe.type(), pe.name(), hidden));
            }
        }

        // Temporarily use the generic Row class.
        TupleType tt = new TupleType(Row.class, columns);

        if (tt.numColumns() != 0) {
            tt = tt.withRowType(cCache.obtain(tt.makeKey(), tt));
        }

        return tt;
    }

    /**
     * Makes a type which uses the given row type class.
     *
     * @param projection consists of column names; can pass null to project all columns
     * @throws QueryException if projection refers to a non-existent column or if any
     * target column names are duplicated
     */
    public static TupleType make(Class rowType, Set<String> projection) {
        RowInfo info = RowInfo.find(rowType);

        var columns = new TreeMap<String, Column>();

        if (projection == null) {
            for (ColumnInfo ci : info.allColumns.values()) {
                addColumn(columns, Column.make(BasicType.make(ci), ci.name, ci.isHidden()));
            }
        } else {
            for (String name : projection) {
                ColumnInfo ci = ColumnSet.findColumn(info.allColumns, name);
                if (ci == null) {
                    name = RowMethodsMaker.unescape(name);
                    throw new IllegalArgumentException("Unknown column: " + name);
                }
                addColumn(columns, Column.make(BasicType.make(ci), name, ci.isHidden()));
            }
        }

        return new TupleType(rowType, columns);
    }

    private static void addColumn(TreeMap<String, Column> columns, Column column) {
        String name = column.name();
        if (columns.putIfAbsent(name, column) != null) {
            name = RowMethodsMaker.unescape(name);
            throw new IllegalArgumentException("Duplicate column: " + name);
        }
    }

    // Use an ordered map to ensure that the encodeKey method produces consistent results.
    private final TreeMap<String, Column> mColumns;

    private TupleType(Class clazz, TreeMap<String, Column> columns) {
        this(clazz, TYPE_REFERENCE, columns);
    }

    private TupleType(Class clazz, int typeCode, TreeMap<String, Column> columns) {
        super(clazz, typeCode);
        mColumns = columns;
    }

    @Override
    public TupleType nullable() {
        return isNullable() ? this
            : new TupleType(clazz(), TYPE_REFERENCE | TYPE_NULLABLE, mColumns);
    }

    private TupleType withRowType(Class<?> clazz) {
        if (clazz() == clazz) {
            return this;
        }
        return new TupleType(clazz, typeCode, mColumns);
    }

    private static final byte K_TYPE = KeyEncoder.allocType();

    @Override
    protected void encodeKey(KeyEncoder enc) {
        if (enc.encode(this, K_TYPE)) {
            enc.encodeUnsignedVarInt(mColumns.size());
            for (Column c : mColumns.values()) {
                c.encodeKey(enc);
            }
        }
    }

    @Override
    public int hashCode() {
        int hash = clazz().hashCode();
        hash = hash * 31 + mColumns.hashCode();
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || obj instanceof TupleType tt
            && clazz() == tt.clazz() && mColumns.equals(tt.mColumns);
    }

    @Override
    public String toString() {
        return defaultToString();
    }

    @Override
    protected void appendTo(StringBuilder b) {
        b.append('{');

        int i = 0;
        for (Column column : this) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(column.type()).append(' ').append(column.name());
            i++;
        }

        b.append('}');
    }

    public int numColumns() {
        return mColumns.size();
    }

    public Iterator<Column> iterator() {
        return mColumns.values().iterator();
    }

    /**
     * Returns the column which has the given name.
     *
     * @throws IllegalArgumentException if not found
     */
    public Column columnFor(String name) {
        Column c = tryColumnFor(name);
        if (c == null) {
            throw new IllegalArgumentException("Unknown column: " + name);
        }
        return c;
    }

    /**
     * Returns the column which has the given name.
     *
     * @return null if not found
     */
    public Column tryColumnFor(String name) {
        return mColumns.get(name);
    }

    /**
     * Find a column which matches the given name. The name of the returned column is fully
     * qualified, which means that for joins, it has dotted names.
     *
     * @param name qualified or unqualified column name to find
     * @return null if not found
     */
    public Column tryFindColumn(String name) {
        Map<String, Column> map = mColumns;
        Column column = map.get(name);

        if (column != null) {
            return column;
        }

        int dotIx = name.indexOf('.');

        if (dotIx >= 0 && (column = map.get(name.substring(0, dotIx))) != null) {
            if (column.type() instanceof TupleType tt) {
                Column sub = tt.tryFindColumn(name.substring(dotIx + 1));
                if (sub != null) {
                    return sub.withName(name);
                }
            }
        }

        return null;
    }

    /**
     * Returns true if the given projection has no ordered columns and exactly consists of the
     * columns of this tuple.
     */
    public boolean matches(Collection<ProjExpr> projection) {
        if (projection.size() != mColumns.size()) {
            return false;
        }
        for (ProjExpr pe : projection) {
            if (pe.hasExclude() || pe.hasOrderBy()) {
                return false;
            }
            Expr e = pe.wrapped();
            if (!(e instanceof ColumnExpr ce)) {
                return false;
            }
            Column column = ce.column();
            if (!column.equals(mColumns.get(column.name()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if the given projection only consists of columns which are found in this
     * tuple.
     */
    public boolean canRepresent(Collection<ProjExpr> projection) {
        for (ProjExpr pe : projection) {
            if (!pe.hasExclude()) {
                Column column = mColumns.get(pe.name());
                if (column == null || !column.type().equals(pe.type())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final WeakCache<Object, Class<?>, TupleType> cCache;

    static {
        cCache = new WeakCache<>() {
            @Override
            public Class<?> newValue(Object key, TupleType tt) {
                return tt.makeRowTypeClass();
            }
        };
    }

    private Class<?> makeRowTypeClass() {
        ClassMaker cm = RowGen.beginClassMakerForRowType(TupleType.class.getPackageName(), "Type");
        cm.implement(Row.class);
        cm.sourceFile(getClass().getSimpleName()).addAnnotation(Unpersisted.class, true);

        for (Column c : mColumns.values()) {
            Type type = c.type();
            Class<?> clazz = type.clazz();
            String name = c.name();

            MethodMaker mm = cm.addMethod(clazz, name).public_().abstract_();

            if (c.type().isNullable()) {
                mm.addAnnotation(Nullable.class, true);
            }

            if (c.isHidden()) {
                mm.addAnnotation(Hidden.class, true);
            }

            cm.addMethod(null, name, clazz).public_().abstract_();
        }

        return cm.finish();
    }
}
