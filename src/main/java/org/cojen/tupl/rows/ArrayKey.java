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

import java.util.Arrays;

/**
 * If only arrays behaved like this already...
 *
 * @author Brian S O'Neill
 */
abstract class ArrayKey {
    static Object make(byte[] array) {
        return new Bytes(array);
    }

    static Object make(Object first, Object[] rest) {
        var array = new Object[1 + rest.length];
        array[0] = first;
        System.arraycopy(rest, 0, array, 1, rest.length);
        return new Obj(array);
    }

    private static final class Bytes {
        private final byte[] mArray;

        Bytes(byte[] array) {
            mArray = array;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(mArray);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof Bytes) {
                return Arrays.equals(mArray, ((Bytes) obj).mArray);
            }
            return false;
        }

        @Override
        public String toString() {
            return Arrays.toString(mArray);
        }
    }

    private static final class Obj {
        private final Object[] mArray;

        Obj(Object[] array) {
            mArray = array;
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(mArray);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof Obj) {
                return Arrays.deepEquals(mArray, ((Obj) obj).mArray);
            }
            return false;
        }

        @Override
        public String toString() {
            return Arrays.deepToString(mArray);
        }
    }
}