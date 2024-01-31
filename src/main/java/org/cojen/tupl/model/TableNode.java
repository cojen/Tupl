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

package org.cojen.tupl.model;

import java.util.Map;

import org.cojen.tupl.Table;

import org.cojen.tupl.table.IdentityTable;

/**
 * Defines a node which accesses an ordinary table.
 *
 * @author Brian S. O'Neill
 */
public final class TableNode extends RelationNode {
    public static TableNode make(Table table) {
        return make(table, null);
    }

    public static TableNode make(Table table, String name) {
        var cardinality = table instanceof IdentityTable ? Cardinality.ONE : Cardinality.MANY;
        var type = RelationType.make(TupleType.make(table.rowType(), null), cardinality);
        return new TableNode(type, name, table);
    }

    private final Table<?> mTable;

    private TableNode(RelationType type, String name, Table table) {
        super(type, name);
        mTable = table;
    }

    @Override
    public TableNode withName(String name) {
        return name.equals(name()) ? this : new TableNode(type(), name, mTable);
    }

    @Override
    public int maxArgument() {
        return 0;
    }

    @Override
    public boolean isPureFunction() {
        return mTable instanceof IdentityTable;
    }

    @Override
    public TableProvider<?> makeTableProvider() {
        return TableProvider.make(mTable, null);
    }

    @Override
    public TableNode replaceConstants(Map<ConstantNode, FieldNode> map, String prefix) {
        return this;
    }

    public Table table() {
        return mTable;
    }

    @Override
    public int hashCode() {
        return mTable.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TableNode tn && mTable == tn.mTable;
    }
}
