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

package org.cojen.tupl.rows.join;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import java.sql.Connection;
import java.sql.DriverManager;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.*;

import org.cojen.tupl.diag.QueryPlan;

import org.cojen.tupl.rows.RowInfo;

/**
 * Tests all possible well-specified unfiltered 3-way joins and checks the results. Anti-joins
 * aren't directly tested because checking them against another database is more difficult.
 *
 * @author Brian S O'Neill
 */
public class PermutationsTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(PermutationsTest.class.getName());
    }

    private static Database cDb;
    private static Table<Company> cCompany;
    private static Table<Department> cDepartment;
    private static Table<Employee> cEmployee;

    private static Connection cCon;

    private Table<EmployeeJoinDepartmentJoinCompany> mJoin;

    private static final String[] TYPES = {":", "::", ">:", ":<", ">:<"};

    @BeforeClass
    public static void setupAll() throws Exception {
        cDb = Database.open(new DatabaseConfig());
        cCompany = cDb.openTable(Company.class);
        cDepartment = cDb.openTable(Department.class);
        cEmployee = cDb.openTable(Employee.class);
        Filler.fillCompany(cCompany, cDepartment, cEmployee);

        cCon = DriverManager.getConnection("jdbc:hsqldb:mem:testdb", "sa", "");
        Filler.fillCompanySQL(cCon);
    }

    @AfterClass
    public static void teardownAll() throws Exception {
        if (cDb != null) {
            cDb.close();
            cDb = null;
        }

        cCompany = null;
        cDepartment = null;
        cEmployee = null;

        if (cCon != null) {
            Filler.dropSQL(cCon);
            cCon.close();
            cCon = null;
        }
    }

    @After
    public void teardown() {
        mJoin = null;
    }

    @Test
    public void permute() throws Exception {
        permute(new String[] {"employee", "department", "company"});
    }

    private void permute(String[] columns) throws Exception {
        permute(columns, columns.length);
    }

    private void permute(String[] columns, int k) throws Exception {
        if (--k != 0) {
            permute(columns, k);
            if ((k & 1) == 0) {
                for (int i=0; i<k; i++) {
                    swap(columns, 0, k);
                    permute(columns, k);
                }
            } else {
                for (int i=0; i<k; i++) {
                    swap(columns, i, k);
                    permute(columns, k);
                }
            }
            return;
        }

        var types = new int[columns.length - 1];

        outer: while (true) {
            var b = new StringBuilder(40);
            b.append(columns[0]);
            for (int i=1; i<columns.length; i++) {
                b.append(' ').append(TYPES[types[i - 1]]).append(' ').append(columns[i]);
            }

            testWith(b.toString());

            for (int end = types.length; --end >= 0; ) {
                int t = types[end] + 1;
                if (t < TYPES.length) {
                    types[end] = t;
                    continue outer;
                }
                types[end] = 0;
            }

            break;
        }
    }

    private static void swap(String[] strs, int a, int b) {
        String s = strs[a];
        strs[a] = strs[b];
        strs[b] = s;
    }

    private void testWith(String spec) throws Exception {
        //System.out.println(spec);

        mJoin = cDb.openJoinTable(EmployeeJoinDepartmentJoinCompany.class, spec);

        String query = "department.id == employee.departmentId && " +
            "department.companyId == company.id";

        QueryPlan plan = mJoin.scannerPlan(null, query);
        //System.out.println(plan);

        var rows = new TreeSet<String>();

        try (var scanner = mJoin.newScanner(null, query)) {
            for (var row = scanner.row(); row != null; row = scanner.step(row)) {
                String rowStr = row.toString();
                assertTrue(rows.add(rowStr));
            }
        }

        String sql = makeSQL(spec, query);
        //System.out.println(sql);

        var sqlRows = new TreeSet<String>();

        try (var st = cCon.prepareStatement(sql)) {
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    /*
                    var md = rs.getMetaData();
                    int numColumns = md.getColumnCount();
                    for (int i=1; i<=numColumns; i++) {
                        System.out.print(md.getColumnLabel(i) + "=" + rs.getObject(i) + ", ");
                    }
                    System.out.println();
                    */

                    Company company;
                    int c_id = rs.getInt("c_id");
                    if (rs.wasNull()) {
                        company = null;
                    } else {
                        company = cCompany.newRow();
                        company.id(c_id);
                        company.name(rs.getString("c_name"));
                    }

                    Department department;
                    int d_id = rs.getInt("d_id");
                    if (rs.wasNull()) {
                        department = null;
                    } else {
                        department = cDepartment.newRow();
                        department.id(d_id);
                        department.name(rs.getString("d_name"));
                        department.companyId((Integer) rs.getObject("d_companyId"));
                    }

                    Employee employee;
                    int e_id = rs.getInt("e_id");
                    if (rs.wasNull()) {
                        employee = null;
                    } else {
                        employee = cEmployee.newRow();
                        employee.id(e_id);
                        employee.lastName(rs.getString("e_lastName"));
                        employee.country(rs.getString("e_country"));
                        employee.departmentId((Integer) rs.getObject("e_departmentId"));
                    }

                    var row = mJoin.newRow();
                    row.company(company);
                    row.department(department);
                    row.employee(employee);

                    String rowStr = row.toString().replace("*", "");
                    assertTrue(sqlRows.add(rowStr));
                }
            }
        }

        if (false) {
            dump(rows);
            System.out.println();
            dump(sqlRows);
            System.out.println();
        }

        assertEquals(rows, sqlRows);
    }

    private String makeSQL(String specStr, String query) throws Exception {
        var b = new StringBuilder().append("select company.companyId as c_id, company.name as c_name, department.departmentId as d_id, department.name as d_name, department.companyId as d_companyId, employee.employeeId as e_id, employee.lastName as e_lastName, employee.country as e_country, employee.departmentId as e_departmentId from ");

        JoinSpec spec = JoinSpec.parse
            (RowInfo.find(EmployeeJoinDepartmentJoinCompany.class), specStr, cDb);


        var parts = new HashSet<String>();
        for (var part : query.split("&&")) {
            part = part.trim().replace("==", "=");
            part = part.replace("department.id", "department.departmentId");
            part = part.replace("company.id", "company.companyId");
            parts.add(part);
        }

        var available = new HashSet<String>();

        spec.root().accept(new JoinSpec.Visitor() {
            @Override
            public JoinSpec.Node visit(JoinSpec.Column node) {
                String name = node.name();
                b.append(name);
                available.add(name);
                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.JoinOp node) {
                node.leftChild().accept(this);

                switch (node.type()) {
                case JoinSpec.T_INNER, JoinSpec.T_STRAIGHT -> b.append(" inner join ");
                case JoinSpec.T_LEFT_OUTER -> b.append(" left outer join ");
                case JoinSpec.T_RIGHT_OUTER -> b.append(" right outer join ");
                case JoinSpec.T_FULL_OUTER -> b.append(" full outer join ");
                default -> b.append(" UNSUPPORTED ");
                }

                node.rightChild().accept(this);

                boolean any = false;

                Iterator<String> it = parts.iterator();
                while (it.hasNext()) {
                    String part = it.next();
                    int ix = part.indexOf('.');
                    if (available.contains(part.substring(0, ix))) {
                        int ix2 = part.indexOf('=', ix + 1);
                        int ix3 = part.indexOf('.', ix2 + 1);
                        if (available.contains(part.substring(ix2 + 1, ix3).trim())) {
                            b.append(any ? " and " : " on ").append(part);
                            it.remove();
                            any = true;
                        }
                    }
                }

                if (!any) {
                    b.append(" on 1 = 1");
                }

                return node;
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.InnerJoins node) {
                return node.toJoinOp().accept(this);
            }

            @Override
            public JoinSpec.Node visit(JoinSpec.FullJoin node) {
                node.toSpec().root().accept(this);
                return node;
            }
        });

        return b.toString();
    }

    private static void dump(Set<?> set) {
        for (var item : set) {
            System.out.println(item);
        }
    }
}
