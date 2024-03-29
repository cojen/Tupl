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

package org.cojen.tupl.io;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;

import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

import java.nio.channels.FileChannel;

import org.cojen.tupl.unsafe.DirectAccess;

import static java.nio.channels.FileChannel.MapMode.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class NioMapping extends Mapping {
    private final RandomAccessFile mRaf;
    private final FileChannel mChannel;
    private final MappedByteBuffer mBuffer;

    NioMapping(File file, boolean readOnly, long position, int size) throws IOException {
        mRaf = new RandomAccessFile(file, readOnly ? "r" : "rw");
        mChannel = mRaf.getChannel();
        mBuffer = mChannel.map(readOnly ? READ_ONLY : READ_WRITE, position, size);
    }

    @Override
    int size() {
        return mBuffer.capacity();
    }

    @Override
    void read(int start, byte[] b, int off, int len) {
        ByteBuffer src = mBuffer.slice();
        src.position(start);
        src.get(b, off, len);
    }

    @Override
    void read(int start, long ptr, int len) {
        ByteBuffer src = mBuffer.slice();
        src.limit(start + len);
        src.position(start);
        DirectAccess.ref(ptr, len).put(src);
    }

    @Override
    void write(int start, byte[] b, int off, int len) {
        ByteBuffer dst = mBuffer.slice();
        dst.position(start);
        dst.put(b, off, len);
    }

    @Override
    void write(int start, long ptr, int len) {
        ByteBuffer dst = mBuffer.slice();
        dst.position(start);
        dst.put(DirectAccess.ref(ptr, len));
    }

    @Override
    void sync(boolean metadata) throws IOException {
        try {
            mBuffer.force();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        mChannel.force(metadata);
    }

    @Override
    public void close() throws IOException {
        DirectAccess.delete(mBuffer);
        mRaf.close();
    }
}
