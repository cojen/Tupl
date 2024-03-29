/*
 *  Copyright (C) 2011-2017 Cojen.org
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

package org.cojen.tupl.core;

import java.lang.reflect.Method;

import org.cojen.tupl.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ValueAccessorDirectTest extends ValueAccessorTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(ValueAccessorDirectTest.class.getName());
    }

    @Override
    protected DatabaseConfig decorate(DatabaseConfig config) {
        return config.directPageAccess(true);
    }

    @Override
    protected void doValueModify(Cursor c, int op, long pos, byte[] buf, int off, long len)
        throws Exception
    {
        Method m = c.getClass().getDeclaredMethod
            ("doValueModify", int.class, long.class, byte[].class, int.class, long.class);
        m.invoke(c, op, pos, buf, off, len);
    }
}
