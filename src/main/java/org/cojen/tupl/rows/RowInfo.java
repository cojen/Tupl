/*
 *  Copyright 2021 Cojen.org
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

package org.cojen.tupl.rows;

import java.lang.invoke.VarHandle;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.util.Collections;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.cojen.tupl.AlternateKey;
import org.cojen.tupl.Automatic;
import org.cojen.tupl.Hidden;
import org.cojen.tupl.Nullable;
import org.cojen.tupl.PrimaryKey;
import org.cojen.tupl.SecondaryIndex;
import org.cojen.tupl.Unsigned;

import static org.cojen.tupl.rows.ColumnInfo.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class RowInfo extends ColumnSet {
    private static final WeakClassCache<RowInfo> cache = new WeakClassCache<>();

    private static Set<Class<?>> examining;

    /**
     * Returns a new or cached instance.
     *
     * @throws IllegalArgumentException if row type is malformed
     */
    public static RowInfo find(Class<?> rowType) {
        RowInfo info = cache.get(rowType);

        if (info == null) {
            synchronized (cache) {
                info = cache.get(rowType);
                if (info == null) {
                    if (examining == null) {
                        examining = new HashSet<>();
                    }
                    if (!examining.add(rowType)) {
                        throw new IllegalArgumentException("Recursive join");
                    }
                    try {
                        info = examine(rowType);
                        cache.put(rowType, info);
                    } finally {
                        examining.remove(rowType);
                        if (examining.isEmpty()) {
                            examining = null;
                        }
                    }
                }
            }
        }

        return info;
    }

    /**
     * @throws IllegalArgumentException if row type is malformed
     */
    private static RowInfo examine(Class<?> rowType) {
        var messages = new LinkedHashSet<String>(1);

        if (!rowType.isInterface()) {
            messages.add("must be an interface");
            errorCheck(rowType, messages);
        }

        var info = new RowInfo(rowType.getName());

        ColumnInfo autoColumn = info.examineAllColumns(rowType, messages);
        errorCheck(rowType, messages);

        info.examinePrimaryKey(rowType, messages);
        errorCheck(rowType, messages);

        if (autoColumn != null) {
            Iterator<ColumnInfo> it = info.keyColumns.values().iterator();
            while (true) {
                ColumnInfo column = it.next();
                if (!it.hasNext()) {
                    if (column != autoColumn) {
                        messages.add("automatic column must be the last in the primary key");
                    }
                    break;
                }
            }
        }

        AlternateKey altKey = rowType.getAnnotation(AlternateKey.class);
        AlternateKey.Set altKeySet = rowType.getAnnotation(AlternateKey.Set.class);
        if (altKey == null && altKeySet == null) {
            info.alternateKeys = Collections.emptyNavigableSet();
        } else {
            info.alternateKeys = new TreeSet<>(ColumnSetComparator.THE);
            if (altKey != null) {
                info.examineIndex(messages, info.alternateKeys, altKey.value(), true);
            }
            if (altKeySet != null) {
                for (AlternateKey alt : altKeySet.value()) {
                    info.examineIndex(messages, info.alternateKeys, alt.value(), true);
                }
            }
        }

        SecondaryIndex index = rowType.getAnnotation(SecondaryIndex.class);
        SecondaryIndex.Set indexSet = rowType.getAnnotation(SecondaryIndex.Set.class);
        if (index == null && indexSet == null) {
            info.secondaryIndexes = Collections.emptyNavigableSet();
        } else {
            info.secondaryIndexes = new TreeSet<>(ColumnSetComparator.THE);
            // Add the primary key initially, which will be removed by reduction.
            info.secondaryIndexes.add(info);
            if (index != null) {
                info.examineIndex(messages, info.secondaryIndexes, index.value(), false);
            }
            if (indexSet != null) {
                for (SecondaryIndex ix : indexSet.value()) {
                    info.examineIndex(messages, info.secondaryIndexes, ix.value(), false);
                }
            }
        }

        errorCheck(rowType, messages);

        info.alternateKeys = info.finishIndexSet(info.alternateKeys);
        info.secondaryIndexes = info.finishIndexSet(info.secondaryIndexes);

        if (CompareUtils.needsCompareTo(rowType)) {
            StringBuilder b = null;

            for (ColumnInfo column : info.allColumns.values()) {
                if (column.typeCode == TYPE_JOIN &&
                    !Comparable.class.isAssignableFrom(column.type))
                {
                    if (b == null) {
                        b = new StringBuilder();
                        b.append("extends Comparable but not all columns are Comparable: ");
                        b.append(column.name);
                    } else {
                        b.append(", ").append(column.name);
                    }
                }
            }

            if (b != null) {
                messages.add(b.toString());
                errorCheck(rowType, messages);
            }
        }

        // This is necessary because not all fields are final, and the cache is initially
        // accessed without being synchronized.
        VarHandle.storeStoreFence();

        return info;
    }

    // Fully qualified row type name.
    public final String name;

    // Sets are reduced and contain all the necessary columns to retrieve by primary key.
    public NavigableSet<ColumnSet> alternateKeys;

    // Sets are reduced and contain all the necessary columns to retrieve by primary key.
    public NavigableSet<ColumnSet> secondaryIndexes;

    private volatile RowGen mRowGen;

    RowInfo(String name) {
        this.name = name;
    }

    boolean isAltKey() {
        return false;
    }

    /**
     * Returns true if the projection includes all columns of a primary or alternate key.
     */
    boolean isDistinct(Set<String> projection) {
        if (projection.containsAll(keyColumns.keySet())) {
            return true;
        }
        if (alternateKeys != null) {
            for (ColumnSet alt : alternateKeys) {
                if (projection.containsAll(alt.keyColumns.keySet())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns a new or cached RowGen instance.
     */
    RowGen rowGen() {
        RowGen gen = mRowGen;
        if (gen == null) {
            synchronized (this) {
                gen = mRowGen;
                if (gen == null) {
                    mRowGen = gen = new RowGen(this);
                }
            }
        }
        return gen;
    }

    /**
     * Returns a copy of this RowInfo but with the alternateKeys and secondaryIndexes from the
     * given RowInfo. If any alternateKeys or secondaryIndexes refer to columns in this RowInfo
     * which don't exactly match, they're excluded. If the RowInfo would be unchanged, then
     * this original RowInfo is returned as-is.
     */
    RowInfo withIndexes(RowInfo current) {
        NavigableSet<ColumnSet> withAlternateKeys = pruneIndexes(current.alternateKeys);
        NavigableSet<ColumnSet> withSecondaryIndexes = pruneIndexes(current.secondaryIndexes);

        if (Objects.equals(alternateKeys, withAlternateKeys) &&
            Objects.equals(secondaryIndexes, withSecondaryIndexes))
        {
            return this;
        }

        var copy = new RowInfo(name);
        copy.allColumns = allColumns;
        copy.valueColumns = valueColumns;
        copy.keyColumns = keyColumns;
        copy.alternateKeys = withAlternateKeys;
        copy.secondaryIndexes = withSecondaryIndexes;

        return copy;
    }

    private NavigableSet<ColumnSet> pruneIndexes(NavigableSet<ColumnSet> set) {
        NavigableSet<ColumnSet> copy = null;

        for (ColumnSet cs : set) {
            for (ColumnInfo column : cs.allColumns.values()) {
                if (!column.equals(allColumns.get(column.name))) {
                    if (copy == null) {
                        copy = new TreeSet<>(set);
                    }
                    copy.remove(cs);
                    break;
                }
            }
        }

        return copy == null ? set : copy;
    }

    /**
     * Returns a string suitable for EventListener messages when building or dropping indexes.
     */
    String eventString() {
        var bob = new StringBuilder();
        if (this instanceof SecondaryInfo) {
            bob.append(isAltKey() ? "alternate key" : "secondary index").append(' ');
        }
        bob.append(name).append('(');
        return appendIndexSpec(bob).append(')').toString();
    }

    @Override
    public String toString() {
        return "name: " + name + ", " + super.toString() +
            ", alternateKeys: " + alternateKeys + ", secondaryIndexes: " + secondaryIndexes;
    }

    private static void errorCheck(Class<?> rowType, Set<String> messages) {
        if (!messages.isEmpty()) {
            var bob = new StringBuilder().append("Row type").append(' ').append('"');
            bob.append(rowType.getSimpleName()).append("\" is malformed: ");
            final int length = bob.length();
            for (String message : messages) {
                if (bob.length() > length) {
                    bob.append("; ");
                }
                bob.append(message);
            }
            throw new IllegalArgumentException(bob.toString());
        }
    }

    /**
     * @return automatic column, if one is defined
     */
    private ColumnInfo examineAllColumns(Class<?> rowType, Set<String> messages) {
        allColumns = new TreeMap<>();
        ColumnInfo autoColumn = null;

        for (Method method : rowType.getMethods()) {
            if (method.isDefault()) {
                continue;
            }
            int modifiers = method.getModifiers();
            if (Modifier.isStatic(modifiers) || !Modifier.isAbstract(modifiers)) {
                continue;
            }

            String name = method.getName();
            Class<?> type = method.getReturnType();
            Class<?>[] params = method.getParameterTypes();

            ColumnInfo info;

            lookup: {
                if (type != void.class) {
                    if (params.length == 0) {
                        if (name.equals("hashCode") || name.equals("toString")) {
                            // Inherited non-final method declared in Object.
                            continue;
                        }
                        if (name.equals("clone") && type.isAssignableFrom(rowType)) {
                            // Inherited non-final method declared in Object.
                            continue;
                        }
                        info = addColumn(messages, name, type);
                        if (info == null) {
                            continue;
                        }
                        if (info.accessor != null) {
                            messages.add("duplicate accessor method \"" + name + '"');
                            continue;
                        }
                        info.accessor = method;
                        break lookup;
                    } else if (params.length == 1) {
                        if (name.equals("equals") && params[0] == Object.class) {
                            // Inherited non-final method declared in Object.
                            continue;
                        }
                        if (name.equals("compareTo") && type == int.class &&
                            (params[0] == Object.class || params[0] == rowType) &&
                            Comparable.class.isAssignableFrom(rowType))
                        {
                            // Inherited method from Comparable interface.
                            continue;
                        }
                    }
                } else {
                    if (params.length == 1) {
                        type = params[0];
                        info = addColumn(messages, name, type);
                        if (info == null) {
                            continue;
                        }
                        if (info.mutator != null) {
                            messages.add("duplicate mutator method \"" + name + '"');
                            continue;
                        }
                        info.mutator = method;
                        break lookup;
                    }
                }

                messages.add("unsupported method \"" + name + '"');
                continue;
            }

            if (method.isAnnotationPresent(Nullable.class)) {
                if (info.type.isPrimitive()) {
                    messages.add("column \"" + info.type.getSimpleName() + ' ' + info.name +
                                 "\" cannot be nullable");
                } else {
                    info.typeCode |= TYPE_NULLABLE;
                }
            }

            if (method.isAnnotationPresent(Unsigned.class)) {
                int typeCode = info.plainTypeCode();
                if (typeCode >= 0b10000 || typeCode == TYPE_BOOLEAN) {
                    messages.add("column \"" + info.type.getSimpleName() + ' ' + info.name +
                                 "\" cannot be unsigned");
                } else {
                    info.typeCode &= ~0b01000;
                }
            }

            Automatic auto = method.getAnnotation(Automatic.class);
            if (auto != null) {
                long min = auto.min();
                long max = auto.max();

                if (min >= max) {
                    messages.add("illegal automatic range [" + min + ", " + max + ']');
                }

                switch (info.unorderedTypeCode()) {
                case TYPE_UINT: case TYPE_ULONG: case TYPE_INT: case TYPE_LONG:
                    if (!info.isAutomatic()) {
                        if (autoColumn == null) {
                            info.autoMin = min;
                            info.autoMax = max;
                            autoColumn = info;
                        } else {
                            messages.add("at most one column can be automatic");
                        }
                    } else if (info.autoMin != min || info.autoMax != max) {
                        messages.add("inconsistent automatic range");
                    }
                    break;
                default:
                    messages.add("column \"" + info.type.getSimpleName() + ' ' + info.name +
                                 "\" cannot be automatic");
                }
            }

            info.hidden |= method.isAnnotationPresent(Hidden.class);
        }

        for (ColumnInfo info : allColumns.values()) {
            if (info.accessor == null) {
                messages.add("no accessor method for column \"" + info.name + '"');
            } else if (info.mutator == null) {
                messages.add("no mutator method for column \"" + info.name + '"');
            }
        }

        return autoColumn;
    }

    /**
     * @return null if illegal
     */
    private ColumnInfo addColumn(Set<String> messages, String name, Class<?> type) {
        int typeCode = selectTypeCode(messages, name, type);

        ColumnInfo info = allColumns.get(name);

        if (info == null) {
            name = name.intern();
            info = new ColumnInfo();
            info.name = name;
            info.type = type;
            info.typeCode = typeCode;
            allColumns.put(name, info);
        } else if (info.type != type) {
            messages.add("column \"" + info.type.getSimpleName() + ' ' + info.name +
                         "\" doesn't match type \"" + type.getSimpleName() + '"');
            info = null;
        }

        return info;
    }

    /**
     * @return -1 if unsupported
     */
    private static int selectTypeCode(Set<String> messages, String name, Class<?> type) {
        String msg = null;

        if (type.isPrimitive()) {
            if (type == int.class) {
                return TYPE_INT;
            } else if (type == long.class) {
                return TYPE_LONG;
            } else if (type == boolean.class) {
                return TYPE_BOOLEAN;
            } else if (type == double.class) {
                return TYPE_DOUBLE;
            } else if (type == float.class) {
                return TYPE_FLOAT;
            } else if (type == byte.class) {
                return TYPE_BYTE;
            } else if (type == char.class) {
                return TYPE_CHAR;
            } else if (type == short.class) {
                return TYPE_SHORT;
            }
        } else if (type.isInterface()) {
            // Assume column is joining to another row.
            try {
                RowInfo.find(type);
                return TYPE_JOIN;
            } catch (IllegalArgumentException e) {
                msg = e.getMessage();
                if (msg == null || msg.isEmpty()) {
                    msg = e.toString();
                }
            }
        } else if (type.isArray()) {
            Class<?> subType = type.getComponentType();
            if (subType.isPrimitive()) {
                int typeCode = selectTypeCode(null, name, subType);
                if (typeCode != -1) {
                    return typeCode | TYPE_ARRAY;
                }
            }
        } else if (type == String.class) {
            return TYPE_UTF8;
        } else if (type == BigInteger.class) {
            return TYPE_BIG_INTEGER;
        } else if (type == BigDecimal.class) {
            return TYPE_BIG_DECIMAL;
        } else if (type == Integer.class) {
            return TYPE_INT;
        } else if (type == Long.class) {
            return TYPE_LONG;
        } else if (type == Boolean.class) {
            return TYPE_BOOLEAN;
        } else if (type == Double.class) {
            return TYPE_DOUBLE;
        } else if (type == Float.class) {
            return TYPE_FLOAT;
        } else if (type == Byte.class) {
            return TYPE_BYTE;
        } else if (type == Character.class) {
            return TYPE_CHAR;
        } else if (type == Short.class) {
            return TYPE_SHORT;
        }

        if (messages != null) {
            var message = new StringBuilder().append("column \"").append(type.getSimpleName())
                .append(' ').append(name).append("\" has an unsupported type");

            if (msg != null) {
                message.append(": ").append(msg);
            }

            messages.add(message.toString());
        }

        return -1;
    }

    private void examinePrimaryKey(Class<?> rowType, Set<String> messages) {
        PrimaryKey pk = rowType.getAnnotation(PrimaryKey.class);

        if (pk == null) {
            keyColumns = Collections.emptyMap();
        } else {
            String[] columnNames = pk.value();

            if (columnNames.length == 0) {
                messages.add(noColumns("primary key"));
                return;
            }

            if (columnNames.length > 1) {
                keyColumns = new LinkedHashMap<>(columnNames.length << 1);
            }

            for (String name : columnNames) {
                int flags = 0;

                ordered: {
                    if (name.startsWith("+")) {
                        name = name.substring(1);
                    } else if (name.startsWith("-")) {
                        name = name.substring(1);
                        flags |= TYPE_DESCENDING;
                    } else {
                        break ordered;
                    }
                    if (name.startsWith("!")) {
                        name = name.substring(1);
                        flags |= TYPE_NULL_LOW;
                    }
                }

                ColumnInfo info = allColumns.get(name);

                if (info == null) {
                    messages.add(notExist("primary key", name));
                    return;
                }

                if ((flags & TYPE_NULL_LOW) != 0 && !info.isNullable()) {
                    flags &= ~TYPE_NULL_LOW;
                }

                info.typeCode |= flags;

                // Use intern'd string.
                name = info.name;

                if (keyColumns == null) {
                    keyColumns = Map.of(name, info);
                } else if (keyColumns.put(name, info) != null) {
                    messages.add(duplicate("primary key", name));
                    return;
                }
            }
        }

        valueColumns = new TreeMap<>();

        for (var entry : allColumns.entrySet()) {
            String name = entry.getKey();
            if (!keyColumns.containsKey(name)) {
                valueColumns.put(name, entry.getValue());
            }
        }
    }

    /**
     * Examines an alternate key or secondary index and fills in the key and data columns.
     *
     * @param fullSet result is added here
     */
    private void examineIndex(Set<String> messages, NavigableSet<ColumnSet> fullSet,
                              String[] columnNames, boolean forAltKey)
    {
        ColumnSet set = examineIndex(messages, columnNames, forAltKey);
        if (set != null) {
            fullSet.add(set);
        }
    }

    /**
     * Examines an alternate key or secondary index and fills in the key and data columns.
     * The "allColumns" map isn't filled in, and some column types might be unspecified.
     *
     * @param messages error messages are added to this set (optional)
     * @return null if there was an error
     */
    ColumnSet examineIndex(Set<String> messages, String[] columnNames, boolean forAltKey) {
        if (columnNames.length == 0) {
            if (messages != null) {
                messages.add(noColumns(forAltKey ? "alternate key" : "secondary index"));
            }
            return null;
        }

        var set = new ColumnSet();

        if (!forAltKey || columnNames.length > 1) {
            set.keyColumns = new LinkedHashMap<>(columnNames.length << 1);
        }

        for (String name : columnNames) {
            Boolean descending = null;
            int flags = 0;

            ordered: {
                if (name.startsWith("+")) {
                    name = name.substring(1);
                    descending = false;
                } else if (name.startsWith("-")) {
                    name = name.substring(1);
                    descending = true;
                } else {
                    break ordered;
                }
                if (name.startsWith("!")) {
                    name = name.substring(1);
                    flags |= TYPE_NULL_LOW;
                }
            }

            ColumnInfo info = allColumns.get(name);

            if (info == null) {
                if (messages != null) {
                    messages.add(notExist(forAltKey ? "alternate key" : "secondary index", name));
                }
                return null;
            }

            if ((flags & TYPE_NULL_LOW) != 0 && !info.isNullable()) {
                flags &= ~TYPE_NULL_LOW;
            }

            // Use intern'd string.
            name = info.name;

            if (descending == null) {
                // Initially use an unspecified type to aid with index set reduction.
                info = withUnspecifiedType(info);
            } else {
                int typeCode = info.typeCode;

                if (descending) {
                    if (!info.isDescending()) {
                        typeCode |= TYPE_DESCENDING;
                    }
                } else {
                    if (info.isDescending()) {
                        typeCode &= ~TYPE_DESCENDING;
                    }
                }

                typeCode |= flags;

                if (typeCode != info.typeCode) {
                    info = info.copy();
                    info.typeCode = typeCode;
                }
            }

            if (set.keyColumns == null) {
                set.keyColumns = new HashMap<>(1); // might be modified during index set reduction
                set.keyColumns.put(name, info);
            } else if (set.keyColumns.put(name, info) != null) {
                if (messages != null) {
                    messages.add(duplicate(forAltKey ? "alternate key" : "secondary index", name));
                }
                return null;
            }
        }

        boolean hasFullPrimaryKey = set.keyColumns.keySet().containsAll(keyColumns.keySet());

        if (forAltKey) {
            if (hasFullPrimaryKey) {
                // Alternate key is effectively a secondary index and doesn't affect uniqueness.
                if (messages != null) {
                    messages.add("alternate key contains all columns of the primary key");
                }
                return null;
            }

            // Add the remaining primary key columns as data columns.

            set.valueColumns = new TreeMap<>();

            for (ColumnInfo pkColumn : keyColumns.values()) {
                if (!set.keyColumns.containsKey(pkColumn.name)) {
                    // Initially use an unspecified type to aid with index set reduction.
                    set.valueColumns.put(pkColumn.name, withUnspecifiedType(pkColumn));
                }
            }
        } else {
            if (hasFullPrimaryKey) {
                // Move columns after the last primary key column to the data column set. This
                // helps optimize covering indexes because it doesn't use key encoding format
                // for everything.

                int remaining = keyColumns.size();
                Iterator<Map.Entry<String, ColumnInfo>> it = set.keyColumns.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    if (remaining > 0) {
                        if (keyColumns.containsKey(entry.getKey())) {
                            remaining--;
                        }
                    } else {
                        it.remove();
                        if (set.valueColumns == null) {
                            set.valueColumns = new TreeMap<>();
                        }
                        set.valueColumns.put(entry.getKey(), entry.getValue());
                    }
                }
            } else {
                // Add the remaining primary key columns as index key columns.

                for (ColumnInfo pkColumn : keyColumns.values()) {
                    if (!set.keyColumns.containsKey(pkColumn.name)) {
                        // Initially use an unspecified type to aid with index set reduction.
                        set.keyColumns.put(pkColumn.name, withUnspecifiedType(pkColumn));
                    }
                }
            }

            if (set.valueColumns == null) {
                set.valueColumns = Collections.emptyNavigableMap();
            }
        }

        return set;
    }

    private static String notExist(String prefix, String name) {
        return prefix + " refers to a column that doesn't exist: " + name;
    }

    private static String duplicate(String prefix, String name) {
        return prefix + " refers to a column more than once: " + name;
    }

    private static String noColumns(String prefix) {
        return prefix + " doesn't specify any columns";
    }

    private static ColumnInfo withUnspecifiedType(ColumnInfo info) {
        info = info.copy();
        info.typeCode = -1;
        return info;
    }

    /**
     * Reduces a set of alternate keys or secondary indexes by examining explicit column sort
     * directions. Initially, primary key columns should have been added with an unspecified
     * type and direction. The given set must be ordered with ColumnSetComparator.THE.
     */
    private NavigableSet<ColumnSet> finishIndexSet(NavigableSet<ColumnSet> initialSet) {
        // All unspecified types are currently ordered lower. By iterating in reverse order
        // into a new set which treats unspecified types as equal, unspecified ones are less
        // favored and will be effectively discarded if they're redundant.

        var nextSet = new TreeSet<ColumnSet>(new ColumnSetComparator(true));
        nextSet.addAll(initialSet.descendingSet());

        // Add the results into a final set, with specified orderings.

        var finalSet = new TreeSet<ColumnSet>(ColumnSetComparator.THE);
        Iterator<ColumnSet> it = nextSet.iterator();
        while (it.hasNext()) {
            ColumnSet set = it.next();
            it.remove(); // remove it before fixing the types and messing things up

            fixTypes(set.keyColumns);
            fixTypes(set.valueColumns);

            set.allColumns = new TreeMap<>();
            set.allColumns.putAll(set.keyColumns);
            set.allColumns.putAll(set.valueColumns);

            finalSet.add(set);
        }

        // Remove the primary key, in case it was in the initial set (secondary indexes do this).
        finalSet.remove(this);

        return finalSet;
    }

    private void fixTypes(Map<String, ColumnInfo> columns) {
        for (Map.Entry<String, ColumnInfo> entry : columns.entrySet()) {
            ColumnInfo info = entry.getValue();
            if (info.typeCode == -1) {
                entry.setValue(allColumns.get(info.name));
            }
        }
    }
}
