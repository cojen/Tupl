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

import java.lang.reflect.Method;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.util.Map;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class ColumnInfo implements Cloneable {

    /*
      Type code format:

      0b...00000: u1 (boolean)
      0b...00001: u2
      0b...00010: u4
      0b...00011: u8
      0b...00100: u16
      0b...00101: u32
      0b...00110: u64
      0b...00111: u128
      0b...01000: i1
      0b...01001: i2
      0b...01010: i4
      0b...01011: i8  (byte)
      0b...01100: i16 (short)
      0b...01101: i32 (int)
      0b...01110: i64 (long)
      0b...01111: i128

      0b...10000: float16
      0b...10001: float32 (float)
      0b...10010: float64 (double)
      0b...10011: float128

      0b...10100: char16 (char)
      0b...10101: unused
      0b...10110: unused
      0b...10111: unused

      0b...11000: utf8 string
      0b...11001: unused
      0b...11010: unused
      0b...11011: unused

      0b...11100: big integer
      0b...11101: big decimal
      0b...11110: unused (document?)
      0b...11111: join to another row

      Modifiers:

      0b000.....: plain
      0b1.......: descending order
      0b.1......: nullable
      0b..1.....: array
    */

    public static final int
        TYPE_BOOLEAN     = 0b00000,
        TYPE_UBYTE       = 0b00011,
        TYPE_USHORT      = 0b00100,
        TYPE_UINT        = 0b00101,
        TYPE_ULONG       = 0b00110,
        TYPE_BYTE        = 0b01011,
        TYPE_SHORT       = 0b01100,
        TYPE_INT         = 0b01101,
        TYPE_LONG        = 0b01110,
        TYPE_FLOAT       = 0b10001,
        TYPE_DOUBLE      = 0b10010,
        TYPE_CHAR        = 0b10100,
        TYPE_UTF8        = 0b11000,
        TYPE_BIG_INTEGER = 0b11100,
        TYPE_BIG_DECIMAL = 0b11101,
        TYPE_JOIN        = 0b11111;

    public static final int
        TYPE_NULL_LOW    = 0b1000_00000,
        TYPE_DESCENDING  = 0b0100_00000,
        TYPE_NULLABLE    = 0b0010_00000,
        TYPE_ARRAY       = 0b0001_00000;

    public String name;
    public Class<?> type;
    public int typeCode;

    public Method accessor;
    public Method mutator;

    public boolean hidden;

    long autoMin, autoMax;

    private String mPrefix;
    private ColumnInfo mTail;

    boolean isAutomatic() {
        return autoMin != autoMax;
    }

    public int plainTypeCode() {
        return plainTypeCode(typeCode);
    }

    public static int plainTypeCode(int typeCode) {
        return typeCode & 0b11111;
    }

    public int unorderedTypeCode() {
        return unorderedTypeCode(typeCode);
    }

    public static int unorderedTypeCode(int typeCode) {
        return typeCode & ~TYPE_DESCENDING;
    }

    public boolean isUnsigned() {
        return isUnsigned(typeCode);
    }

    static boolean isUnsigned(int typeCode) {
        return plainTypeCode(typeCode) < 0b01000;
    }

    boolean isUnsignedInteger() {
        return isUnsignedInteger(typeCode);
    }

    static boolean isUnsignedInteger(int typeCode) {
        return isUnsigned(typeCode) && plainTypeCode(typeCode) != TYPE_BOOLEAN;
    }

    public boolean isNullLow() {
        return isNullLow(typeCode);
    }

    static boolean isNullLow(int typeCode) {
        return (typeCode & TYPE_NULL_LOW) != 0;
    }

    public boolean isDescending() {
        return isDescending(typeCode);
    }

    static boolean isDescending(int typeCode) {
        return (typeCode & TYPE_DESCENDING) != 0;
    }

    public boolean isNullable() {
        return isNullable(typeCode);
    }

    static boolean isNullable(int typeCode) {
        return (typeCode & TYPE_NULLABLE) != 0;
    }

    boolean isArray() {
        return isArray(typeCode);
    }

    public static boolean isArray(int typeCode) {
        return (typeCode & TYPE_ARRAY) != 0;
    }

    /**
     * @return true if type is primitive and not nullable
     */
    boolean isPrimitive() {
        return isPrimitive(typeCode);
    }

    /**
     * @return true if type is primitive and not nullable
     */
    public static boolean isPrimitive(int typeCode) {
        return (typeCode & ~TYPE_DESCENDING) < 0b11000;
    }

    /**
     * @param typeCode must be plain
     */
    public static boolean isFloat(int typeCode) {
        return 0b10000 <= typeCode && typeCode <= 0b10011;
    }

    /**
     * Assigns the type by examining the typeCode.
     */
    public void assignType() {
        if (!isArray()) {
            this.type = isNullable() ? boxedType() : unboxedType();
        } else {
            this.type = unboxedType().arrayType();
        }
    }

    /**
     * Return the primitive boxed type or the regular type if not primitive.
     */
    public Class<?> boxedType() {
        return switch (plainTypeCode()) {
            case TYPE_BOOLEAN            -> Boolean.class;
            case TYPE_BYTE,  TYPE_UBYTE  -> Byte.class;
            case TYPE_SHORT, TYPE_USHORT -> Short.class;
            case TYPE_INT,   TYPE_UINT   -> Integer.class;
            case TYPE_LONG,  TYPE_ULONG  -> Long.class;
            case TYPE_FLOAT              -> Float.class;
            case TYPE_DOUBLE             -> Double.class;
            case TYPE_UTF8               -> String.class;
            case TYPE_BIG_INTEGER        -> BigInteger.class;
            case TYPE_BIG_DECIMAL        -> BigDecimal.class;
            case TYPE_CHAR               -> Character.class;
            default                      -> Object.class;
        };
    }

    /**
     * Return the primitive unboxed type or the regular type if not primitive.
     */
    public Class<?> unboxedType() {
        return switch (plainTypeCode()) {
            case TYPE_BOOLEAN            -> boolean.class;
            case TYPE_BYTE,  TYPE_UBYTE  -> byte.class;
            case TYPE_SHORT, TYPE_USHORT -> short.class;
            case TYPE_INT,   TYPE_UINT   -> int.class;
            case TYPE_LONG,  TYPE_ULONG  -> long.class;
            case TYPE_FLOAT              -> float.class;
            case TYPE_DOUBLE             -> double.class;
            case TYPE_UTF8               -> String.class;
            case TYPE_BIG_INTEGER        -> BigInteger.class;
            case TYPE_BIG_DECIMAL        -> BigDecimal.class;
            case TYPE_CHAR               -> char.class;
            default                      -> Object.class;
        };
    }

    /**
     * Returns true if the given type is the same as this, ignoring the ordering specification.
     */
    boolean isCompatibleWith(ColumnInfo other) {
        return (this.typeCode & 0b0011_11111) == (other.typeCode & 0b0011_11111);
    }

    /**
     * Returns true if the given type is the same as this one or can be primitively widened.
     */
    boolean isAssignableFrom(ColumnInfo other) {
        if (this.typeCode == other.typeCode) {
            return true;
        }

        if (other.isArray() && !this.isArray()) {
            return false;
        }

        if (other.isNullable() && !this.isNullable()) {
            return false;
        }

        return isAssignableFrom(other.plainTypeCode());
    }

    boolean isAssignableFrom(int otherPlainTypeCode) {
        int thisPlainTypeCode = this.plainTypeCode();

        if (thisPlainTypeCode == otherPlainTypeCode) {
            return true;
        }

        return switch (thisPlainTypeCode) {
            case TYPE_SHORT -> otherPlainTypeCode == TYPE_BYTE;
            case TYPE_INT, TYPE_FLOAT -> switch (otherPlainTypeCode) {
                case TYPE_BYTE, TYPE_SHORT, TYPE_CHAR -> true;
                default -> false;
            };
            case TYPE_LONG -> switch (otherPlainTypeCode) {
                case TYPE_BYTE, TYPE_SHORT, TYPE_CHAR, TYPE_INT -> true;
                default -> false;
            };
            case TYPE_DOUBLE -> switch (otherPlainTypeCode) {
                case TYPE_BYTE, TYPE_SHORT, TYPE_CHAR, TYPE_INT, TYPE_FLOAT -> true;
                default -> false;
            };
            default -> false;
        };
    }

    public boolean isScalarType() {
        return typeCode != TYPE_JOIN;
    }

    /**
     * @return null if not found
     */
    public ColumnInfo subColumn(String name) {
        return isScalarType() ? null : RowInfo.find(type).allColumns.get(name);
    }

    /**
     * Recursively puts all the scalar columns into the given map with their fully qualified
     * names.
     */
    public void gatherScalarColumns(Map<String, ColumnInfo> dst) {
        gatherScalarColumns(name, dst);
    }

    private void gatherScalarColumns(String path, Map<String, ColumnInfo> dst) {
        if (isScalarType()) {
            dst.put(path, this);
        } else {
            for (ColumnInfo info : RowInfo.find(type).allColumns.values()) {
                String newPath = path + '.' + info.name;
                info = info.copy();
                info.name = newPath;
                info.gatherScalarColumns(newPath, dst);
            }
        }
    }

    /**
     * If the column name is a path (has a '.' in it), this method returns the first path
     * component. Otherwise, null is returned.
     */
    public String prefix() {
        String prefix = mPrefix;
        if (prefix == null) {
            int ix = name.indexOf('.');
            if (ix >= 0) {
                mPrefix = prefix = name.substring(0, ix).intern();
            }
        }
        return prefix;
    }

    /**
     * If this column name is a path (has a '.' in it), this method returns a column named by
     * the path after the first component. Otherwise, null is returned.
     */
    public ColumnInfo tail() {
        ColumnInfo tail = mTail;
        if (tail == null) {
            String prefix = prefix();
            if (prefix != null) {
                tail = copy();
                tail.name = name.substring(prefix.length() + 1).intern();
                tail.mPrefix = null;
                mTail = tail;
            }
        }
        return tail;
    }

    public ColumnInfo copy() {
        try {
            return (ColumnInfo) clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }

    ColumnInfo nonArray() {
        var info = new ColumnInfo();
        info.name = name;

        // Array elements aren't nullable.
        info.typeCode = typeCode & ~(TYPE_ARRAY | TYPE_NULLABLE);

        if (type == null) {
            info.assignType();
        } else {
            info.type = type.componentType();
        }

        return info;
    }

    public ColumnInfo asArray(boolean nullable) {
        var info = new ColumnInfo();
        info.name = name;

        info.typeCode = typeCode | TYPE_ARRAY;

        if (nullable) {
            info.typeCode |= TYPE_NULLABLE;
        } else {
            info.typeCode &= ~TYPE_NULLABLE;
        }

        if (type == null) {
            info.assignType();
        } else {
            info.type = type.arrayType();
        }

        return info;
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + typeCode;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof ColumnInfo other
            && typeCode == other.typeCode && name.equals(other.name);
    }

    @Override
    public String toString() {
        String typeName = type == null ? null : type.getSimpleName();
        return ((typeCode != -1 && isDescending()) ? ('-' + name) : name) +
            '(' + typeName + ',' + typeCode + ')';
    }
}
