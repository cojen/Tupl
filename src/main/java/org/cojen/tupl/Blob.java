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

package org.cojen.tupl;

import java.io.Closeable;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Provides access to database values without requiring that they be fully loaded or stored in
 * a single operation. A "BLOB" is a "Binary Large OBject", and this interface permits values
 * to be much larger than what can fit in main memory.
 *
 * <p>Blob instances can only be safely used by one thread at a time, and they must be {@link
 * #close closed} when no longer needed. Instances can be exchanged by threads, as long as a
 * happens-before relationship is established.  Without proper exclusion, multiple threads
 * interacting with a Blob instance may cause database corruption.
 *
 * @author Brian S O'Neill
 * @see View#openBlob View.openBlob
 * @see Cursor#openBlob Cursor.openBlob
 */
public interface Blob extends Closeable {
    /**
     * Returns the total length of the value accessed by the Blob.
     *
     * @return value length or -1 if it doesn't exist
     * @throws IllegalStateException if closed
     */
    public long length() throws IOException;

    /**
     * Extends or truncates the value accessed by the Blob. When extended, the new portion of
     * the value is zero-filled.
     *
     * @param length new value length; negative length deletes the value
     * @throws IllegalArgumentException if length is too large
     * @throws IllegalStateException if closed
     */
    public void setLength(long length) throws IOException;

    /**
     * Read from the value, starting from any position. The full requested amount of bytes are
     * read, unless the end is reached. A return value of -1 indicates that the value doesn't
     * exist at all, even when the requested amount is zero.
     *
     * @param pos start position to read from
     * @param buf buffer to read into
     * @param off buffer start offset
     * @param len requested amount to read
     * @return actual amount read, which is less than requested only if the end was reached, or
     * -1 if the value doesn't exist
     * @throws IllegalArgumentException if position is negative
     * @throws IndexOutOfBoundsException
     * @throws IllegalStateException if closed
     */
    public int read(long pos, byte[] buf, int off, int len) throws IOException;

    /**
     * Write into the value, starting from any position. Value is extended when writing past
     * the end, even if the written amount is zero.
     *
     * @param pos start position to write to
     * @param buf buffer to write from
     * @param off buffer start offset
     * @param len amount to write
     * @throws IllegalArgumentException if position is negative
     * @throws IndexOutOfBoundsException
     * @throws IllegalStateException if closed
     * @throws IllegalUpgradeException if not locked for writing
     */
    public void write(long pos, byte[] buf, int off, int len) throws IOException;

    /**
     * Returns a new buffered InputStream instance, which reads from this Blob. When the
     * InputStream is closed, it closes the Blob too. The InputStream is bound to the Blob, and
     * so only one thread can access either at a time.
     *
     * <p>Reading past the end of the stream returns -1 (EOF), as per the InputStream contract.
     * Reading from a value which doesn't exist causes a {@link NoSuchValueException} to be
     * thrown.
     *
     * @param pos start position to read from
     * @return buffered unsynchronized InputStream
     * @throws IllegalArgumentException if position is negative
     */
    public InputStream newInputStream(long pos) throws IOException;

    /**
     * Returns a new buffered InputStream instance, which reads from this Blob. When the
     * InputStream is closed, it closes the Blob too. The InputStream is bound to the Blob, and
     * so only one thread can access either at a time.
     *
     * <p>Reading past the end of the stream returns -1 (EOF), as per the InputStream contract.
     * Reading from a value which doesn't exist causes a {@link NoSuchValueException} to be
     * thrown.
     *
     * @param pos start position to read from
     * @param bufferSize requested buffer size; actual size may differ
     * @return buffered unsynchronized InputStream
     * @throws IllegalArgumentException if position is negative
     * @throws IllegalStateException if closed
     */
    public InputStream newInputStream(long pos, int bufferSize) throws IOException;

    /**
     * Returns a new buffered OutputStream instance, which writes to this Blob. When the
     * OutputStream is closed, it closes the Blob too. The OutputStream is bound to the Blob,
     * and so only one thread can access either at a time.
     *
     * @param pos start position to write to
     * @return buffered unsynchronized OutputStream
     * @throws IllegalArgumentException if position is negative
     * @throws IllegalStateException if closed
     */
    public OutputStream newOutputStream(long pos) throws IOException;

    /**
     * Returns a new buffered OutputStream instance, which writes to this Blob. When the
     * OutputStream is closed, it closes the Blob too. The OutputStream is bound to the Blob,
     * and so only one thread can access either at a time.
     *
     * @param pos start position to write to
     * @param bufferSize requested buffer size; actual size may differ
     * @return buffered unsynchronized OutputStream
     * @throws IllegalArgumentException if position is negative
     * @throws IllegalStateException if closed
     */
    public OutputStream newOutputStream(long pos, int bufferSize) throws IOException;

    /**
     * Closes the Blob, but does not flush any OutputStream instances.
     */
    @Override
    public void close();
}
