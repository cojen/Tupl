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

import org.cojen.tupl.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class CompactDirectTest extends CompactTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(CompactDirectTest.class.getName());
    }

    @Override
    protected DatabaseConfig decorate(DatabaseConfig config) throws Exception {
        config.directPageAccess(true);
        return config;
    }

    @Override
    protected Database newTempDb(boolean autoCheckpoints) throws Exception {
        var config = new DatabaseConfig().durabilityMode(DurabilityMode.NO_SYNC);
        config.directPageAccess(true);
        if (!autoCheckpoints) {
            config.checkpointRate(-1, null);
        }
        return TestUtils.newTempDatabase(getClass(), config);
    }
}
