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

import java.io.EOFException;
import java.io.File;
import java.io.IOException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;

import java.lang.invoke.MethodHandle;

import java.nio.channels.ClosedChannelException;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import org.cojen.tupl.core.SysInfo;

import org.cojen.tupl.util.LocalPool;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class PosixFileIO extends AbstractFileIO {
    private static final MethodHandle __errno_location;
    private static final MethodHandle strerror_r;
    private static final MethodHandle open;
    private static final MethodHandle close;
    private static final MethodHandle lseek;
    private static final MethodHandle pread;
    private static final MethodHandle pwrite;
    private static final MethodHandle ftruncate;
    private static final MethodHandle fcntl;
    private static final MethodHandle fsync;
    private static final MethodHandle fdatasync;
    private static final MethodHandle mmap;
    private static final MethodHandle msync;
    private static final MethodHandle munmap;
    private static final MethodHandle posix_madvise;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = linker.defaultLookup();

        __errno_location = linker.downcallHandle
            (lookup.find("__errno_location").get(),
             FunctionDescriptor.of(ValueLayout.ADDRESS.withTargetLayout(ValueLayout.JAVA_INT)));

        strerror_r = linker.downcallHandle
            (lookup.find("strerror_r").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_LONG,
              ValueLayout.JAVA_INT, // errnum
              ValueLayout.ADDRESS,  // bufPtr
              ValueLayout.JAVA_INT) // bufLen
             );

        open = linker.downcallHandle
            (lookup.find("open").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_INT,
              ValueLayout.ADDRESS,  // pathPtr
              ValueLayout.JAVA_INT) // oflags
             );

        close = linker.downcallHandle
            (lookup.find("close").get(),
             FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        lseek = linker.downcallHandle
            (lookup.find("lseek").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_LONG,
              ValueLayout.JAVA_INT,  // fd
              ValueLayout.JAVA_LONG, // offset
              ValueLayout.JAVA_INT)  // whence
             );

        pread = linker.downcallHandle
            (lookup.find("pread").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT,  // fd
              ValueLayout.JAVA_LONG, // bufPtr
              ValueLayout.JAVA_INT,  // count
              ValueLayout.JAVA_LONG) // offset
             );

        pwrite = linker.downcallHandle
            (lookup.find("pwrite").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT,  // fd
              ValueLayout.JAVA_LONG, // bufPtr
              ValueLayout.JAVA_INT,  // count
              ValueLayout.JAVA_LONG) // offset
             );

        ftruncate = linker.downcallHandle
            (lookup.find("ftruncate").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT,  // fd
              ValueLayout.JAVA_LONG) // length
             );

        fcntl = linker.downcallHandle
            (lookup.find("fcntl").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_INT,
              ValueLayout.JAVA_INT, // fd
              ValueLayout.JAVA_INT) // cmd
             );

        fsync = linker.downcallHandle
            (lookup.find("fsync").get(),
             FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        fdatasync = linker.downcallHandle
            (lookup.find("fdatasync").get(),
             FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

        mmap = linker.downcallHandle
            (lookup.find("mmap").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_LONG,
              ValueLayout.JAVA_LONG, // addr
              ValueLayout.JAVA_LONG, // length
              ValueLayout.JAVA_INT,  // prot
              ValueLayout.JAVA_INT,  // flags
              ValueLayout.JAVA_INT,  // fd
              ValueLayout.JAVA_LONG) // offset
             );

        msync = linker.downcallHandle
            (lookup.find("msync").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_INT,
              ValueLayout.JAVA_LONG, // addr
              ValueLayout.JAVA_LONG, // length
              ValueLayout.JAVA_INT)  // flags
             );

        munmap = linker.downcallHandle
            (lookup.find("munmap").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_INT,
              ValueLayout.JAVA_LONG, // addr
              ValueLayout.JAVA_LONG) // length
             );

        posix_madvise = linker.downcallHandle
            (lookup.find("posix_madvise").get(),
             FunctionDescriptor.of
             (ValueLayout.JAVA_INT,
              ValueLayout.JAVA_LONG, // addr
              ValueLayout.JAVA_LONG, // length
              ValueLayout.JAVA_INT)  // advice
             );

        // Invoke this early in case additional classes need to be loaded. The error is
        // clobbered when the JVM makes additional system calls.
        errno();
    }

    private static final int REOPEN_NON_DURABLE = 1, REOPEN_SYNC_IO = 2, REOPEN_DIRECT_IO = 4;

    private static final int MAX_POOL_SIZE = -4; // 4 * number of available processors

    static final boolean OSX;

    static {
        String osName = System.getProperty("os.name");
        OSX = osName.startsWith("Mac") || osName.startsWith("Darwin");
    }

    private final File mFile;
    private final int mReopenOptions;

    private final LocalPool<MsRef> mMsRefPool;

    private final boolean mReadahead;
    private final boolean mCloseDontNeed;

    private int mFileDescriptor;

    PosixFileIO(File file, EnumSet<OpenOption> options) throws IOException {
        super(options);

        mFile = file;

        if (options.contains(OpenOption.NON_DURABLE)) {
            mReopenOptions = REOPEN_NON_DURABLE;
        } else {
            int reopenOpts = 0;
            if (options.contains(OpenOption.SYNC_IO)) {
                reopenOpts |= REOPEN_SYNC_IO;
            }
            if (options.contains(OpenOption.DIRECT_IO)) {
                reopenOpts |= REOPEN_DIRECT_IO;
            }
            mReopenOptions = reopenOpts;
            if (options.contains(OpenOption.CREATE)) {
                new JavaFileIO(file, options, 1, false).close();
            }
        }
        mReadahead = options.contains(OpenOption.READAHEAD);
        mCloseDontNeed = options.contains(OpenOption.CLOSE_DONTNEED);

        mAccessLock.acquireExclusive();
        try {
            mFileDescriptor = openFd(file, options);
        } finally {
            mAccessLock.releaseExclusive();
        }

        mMsRefPool = new LocalPool<>(null, MAX_POOL_SIZE);

        if (options.contains(OpenOption.MAPPED)) {
            map();
        }

        if (options.contains(OpenOption.CREATE)) {
            dirSync(file);
        }
    }

    @Override
    public boolean isDirectIO() {
        return (mReopenOptions & REOPEN_DIRECT_IO) != 0;
    }

    @Override
    protected final File file() {
        return mFile;
    }

    @Override
    protected long doLength() throws IOException {
        return lseekEndFd(fd(), 0);
    }

    @Override
    protected void doSetLength(long length) throws IOException {
        ftruncateFd(fd(), length);
    }

    @Override
    protected void doRead(long pos, byte[] buf, int offset, int length) throws IOException {
        LocalPool.Entry<MsRef> e = msRefEntry(length);
        try {
            MemorySegment ms = e.get().mMemorySegment;
            preadFd(fd(), ms.address(), length, pos);
            MemorySegment.copy(ms, ValueLayout.JAVA_BYTE, 0, buf, offset, length);
        } finally {
            e.release();
        }
    }

    @Override
    protected void doRead(long pos, long ptr, int length) throws IOException {
        preadFd(fd(), ptr, length, pos);
    }

    @Override
    protected void doWrite(long pos, byte[] buf, int offset, int length) throws IOException {
        LocalPool.Entry<MsRef> e = msRefEntry(length);
        try {
            MemorySegment ms = e.get().mMemorySegment;
            MemorySegment.copy(buf, offset, ms, ValueLayout.JAVA_BYTE, 0, length);
            pwriteFd(fd(), ms.address(), length, pos);
        } finally {
            e.release();
        }
    }

    @Override
    protected void doWrite(long pos, long ptr, int length) throws IOException {
        pwriteFd(fd(), ptr, length, pos);
    }

    @Override
    protected Mapping openMapping(boolean readOnly, long pos, int size) throws IOException {
        if (mReadahead) {
            // Apply readahead only when this file is mapped to prevent unnecessary memory churn.
            fadvise(mFileDescriptor, pos, size, 3); // 3 = POSIX_FADV_WILLNEED
        }
        return new PosixMapping(mFileDescriptor, readOnly, pos, size);
    }

    @Override
    protected void reopen() throws IOException {
        closeFd(fd());

        EnumSet<OpenOption> options = EnumSet.noneOf(OpenOption.class);
        if (isReadOnly()) {
            options.add(OpenOption.READ_ONLY);
        }
        if ((mReopenOptions & REOPEN_SYNC_IO) != 0) {
            options.add(OpenOption.SYNC_IO);
        }
        if ((mReopenOptions & REOPEN_NON_DURABLE) != 0) {
            options.add(OpenOption.NON_DURABLE);
        }
        if ((mReopenOptions & REOPEN_DIRECT_IO) != 0) {
            options.add(OpenOption.DIRECT_IO);
        }

        mFileDescriptor = openFd(mFile, options);
    }

    @Override
    protected void doSync(boolean metadata) throws IOException {
        int fd = fd();
        if (metadata) {
            fsyncFd(fd);
        } else {
            fdatasyncFd(fd);
        }
    }

    @Override
    public void close(Throwable cause) throws IOException {
        int fd;

        mAccessLock.acquireExclusive();
        try {
            fd = mFileDescriptor;
            if (fd == 0) {
                return;
            }
            mCause = cause;
            mFileDescriptor = 0;
        } finally {
            mAccessLock.releaseExclusive();
        }

        IOException ex = null;
        try {
            unmap(false);
        } catch (IOException e) {
            ex = e;
        }

        if (mCloseDontNeed) {
            // Hint to the kernel that it can release pages associated with this
            // file. It is free to ignore our advice, but generally helps
            // prevent filling up the page cache with useless data. On numa
            // machines page cache pollution can cause unnecessary trashing.
            
            // Using length of 0 means to apply the hint from the offset to EOF.
            fadvise(fd, 0, 0, 4); // 4 = POSIX_FADV_DONTNEED
        }

        try {
            closeFd(fd);
        } catch (IOException e) {
            Utils.suppress(e, ex);
            throw e;
        }

        clearMsRefPool(mMsRefPool);
    }

    private LocalPool.Entry<MsRef> msRefEntry(int size) {
        return msRefEntry(mMsRefPool, size);
    }

    private static LocalPool.Entry<MsRef> msRefEntry(LocalPool<MsRef> pool, int size) {
        LocalPool.Entry<MsRef> e = pool.access();
        try {
            MsRef ref = e.get();
            if (ref == null) {
                e.replace(new MsRef(size));
            } else {
                MemorySegment ms = ref.mMemorySegment;
                if (ms.byteSize() < size) {
                    e.replace(new MsRef(size));
                    ref.mArena.close();
                }
            }
            return e;
        } catch (Throwable ex) {
            e.release();
            throw ex;
        }
    }

    private static void clearMsRefPool(LocalPool<MsRef> pool) {
        pool.clear(ref -> ref.mArena.close());
    }

    static class MsRef {
        final Arena mArena;
        final MemorySegment mMemorySegment;

        MsRef(int size) {
            mArena = Arena.ofShared();
            mMemorySegment = mArena.allocate(size, SysInfo.pageSize());
        }
    }

    // Caller must hold mAccessLock.
    private int fd() throws IOException {
        int fd = mFileDescriptor;
        if (fd == 0) {
            var ex = new ClosedChannelException();
            ex.initCause(mCause);
            throw ex;
        }
        return fd;
    }

    static IOException lastErrorToException() {
        return new IOException(errorMessage(errno()));
    }

    static IOException lastErrorToException(long offset) {
        return new IOException(errorMessage(errno()) + ": offset=" + offset);
    }

    static int errno() {
        MemorySegment addr;
        try {
            addr = (MemorySegment) __errno_location.invokeExact();
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        return addr.get(ValueLayout.JAVA_INT, 0);
    }
 
    static String errorMessage(int errorId) {
        try (Arena a = Arena.ofConfined()) {
            int bufLen = 200;
            MemorySegment bufPtr = a.allocate(bufLen);

            long result = (long) strerror_r.invokeExact(errorId, bufPtr, bufLen);
            if (result != -1 && result != 22 && result != 34) { // !EINVAL && !ERANGE
                MemorySegment resultPtr = result == 0 ? bufPtr
                    : MemorySegment.ofAddress(result).reinterpret(bufLen);
                return resultPtr.getUtf8String(0);
            }

            return "Error " + errorId;
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
    }

    /**
     * @return fd
     */
    static int openFd(File file, EnumSet<OpenOption> options) throws IOException {
        String path = file.getPath();

        // Select O_RDONLY or O_RDWR.
        int flags = 0;
        if (!options.contains(OpenOption.READ_ONLY)) {
            flags |= 2;
        }

        int fd;

        if (options.contains(OpenOption.NON_DURABLE)) {
            if (options.contains(OpenOption.CREATE)) {
                flags |= 0100; // O_CREAT
            }
            int mode = 0600;
            fd = RT.shm_open(path, flags, mode);
        } else {
            if (options.contains(OpenOption.SYNC_IO)) {
                flags |= 010000;
            }
            if (options.contains(OpenOption.DIRECT_IO)) {
                flags |= 040000;
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment pathPtr = a.allocateUtf8String(path);
                fd = (int) open.invokeExact(pathPtr, flags);
            } catch (Throwable e) {
                throw Utils.rethrow(e);
            }
            if (fd == -1) {
                throw lastErrorToException();
            }
        }

        if (options.contains(OpenOption.RANDOM_ACCESS)) {
            try {
                fadvise(fd, 0, 0, 1); // 1 = POSIX_FADV_RANDOM
            } catch (Throwable e) {
                try {
                    closeFd(fd);
                } catch (IOException e2) {
                    Utils.suppress(e, e2);
                }
                throw e;
            }
        }

        return fd;
    }

    static void closeFd(int fd) throws IOException {
        int result;
        try {
            result = (int) close.invokeExact(fd);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (result == -1) {
            throw lastErrorToException();
        }
    }

    static long lseekSetFd(int fd, long fileOffset) throws IOException {
        return lseekFd(fd, fileOffset, 0); // SEEK_SET
    }

    static long lseekCurFd(int fd, long fileOffset) throws IOException {
        return lseekFd(fd, fileOffset, 1); // SEEK_CUR
    }

    static long lseekEndFd(int fd, long fileOffset) throws IOException {
        return lseekFd(fd, fileOffset, 2); // SEEK_END
    }

    static long lseekFd(int fd, long fileOffset, int whence) throws IOException {
        long result;
        try {
            result = (long) lseek.invokeExact(fd, fileOffset, whence);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (result == -1) {
            throw lastErrorToException(fileOffset);
        }
        return result;
    }

    static void preadFd(int fd, long bufPtr, int length, long fileOffset) throws IOException {
        while (true) {
            int amt;
            try {
                amt = (int) pread.invokeExact(fd, bufPtr, length, fileOffset);
            } catch (Throwable e) {
                throw Utils.rethrow(e);
            }
            if (amt <= 0) {
                if (amt < 0) {
                    throw lastErrorToException(fileOffset);
                }
                if (length > 0) {
                    throw new EOFException("Attempt to read past end of file: " + fileOffset);
                }
                return;
            }
            length -= amt;
            if (length <= 0) {
                return;
            }
            bufPtr += amt;
            fileOffset += amt;
        }
    }

    static void pwriteFd(int fd, long bufPtr, int length, long fileOffset) throws IOException {
        while (true) {
            int amt;
            try {
                amt = (int) pwrite.invokeExact(fd, bufPtr, length, fileOffset);
            } catch (Throwable e) {
                throw Utils.rethrow(e);
            }
            if (amt < 0) {
                throw lastErrorToException(fileOffset);
            }
            length -= amt;
            if (length <= 0) {
                return;
            }
            bufPtr += amt;
            fileOffset += amt;
        }
    }

    static void ftruncateFd(int fd, long length) throws IOException {
        int result;
        try {
            result = (int) ftruncate.invokeExact(fd, length);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (result == -1) {
            throw lastErrorToException();
        }
    }

    static void fsyncFd(int fd) throws IOException {
        int result;
        try {
            if (OSX) {
                result = (int) fcntl.invokeExact(fd, 51); // F_FULLFSYNC
            } else {
                result = (int) fsync.invokeExact(fd);
            }
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (result == -1) {
            throw lastErrorToException();
        }
    }

    static void fdatasyncFd(int fd) throws IOException {
        int result;
        try {
            if (OSX) {
                result = (int) fcntl.invokeExact(fd, 51); // F_FULLFSYNC
            } else {
                result = (int) fdatasync.invokeExact(fd);
            }
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (result == -1) {
            throw lastErrorToException();
        }
    }

    static void fadvise(int fd, long offset, long length, int advice) throws IOException {
        platform().fadvise(fd, offset, length, advice);
    }

    static long mmapFd(long length, int prot, int flags, int fd, long offset) throws IOException {
        long ptr;
        try {
            ptr = (long) mmap.invokeExact(0L, length, prot, flags, fd, offset);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (ptr == -1) {
            throw lastErrorToException(offset);
        }
        return ptr;
    }

    static void msyncPtr(long ptr, long length) throws IOException {
        long endPtr = ptr + length;
        ptr = (ptr / SysInfo.pageSize()) * SysInfo.pageSize();
        int result;
        try {
            result = (int) msync.invokeExact(ptr, endPtr - ptr, 4); // flags = MS_SYNC
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (result == -1) {
            throw lastErrorToException();
        }
    }

    static void munmapPtr(long ptr, long length) throws IOException {
        int result;
        try {
            result = (int) munmap.invokeExact(ptr, length);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (result == -1) {
            throw lastErrorToException();
        }
    }

    static void madvisePtr(long ptr, long length, int advice) throws IOException {
        int result;
        try {
            result = (int) posix_madvise.invokeExact(ptr, length, advice);
        } catch (Throwable e) {
            throw Utils.rethrow(e);
        }
        if (result != 0) {
            throw new IOException(errorMessage(result));
        }
    }

    @Override
    protected boolean shouldPreallocate(LengthOption option) {
        return option == LengthOption.PREALLOCATE_ALWAYS
            || (option == LengthOption.PREALLOCATE_OPTIONAL && platform() != NullIO.INSTANCE);
    }

    @Override
    protected void doPreallocate(long pos, long length) throws IOException {
        PlatformIO platform = platform();
        if (platform == NullIO.INSTANCE) {
            // Don't have fallocate (or equivalent). Use default non-destructive zero-fill behavior.
            super.doPreallocate(pos, length);
            return;
        } 

        // Uses posix_fallocate call to quickly allocate blocks and mark them as uninitialized. 
        // If the filesystem supports fallocate then this requires no I/O to the data blocks, 
        // and is much faster than zero-filling the file, which is the fallback behavior. 
        // Since linux 2.6.31 fallocate is supported by at least btrfs, ext4, ocfs2, and 
        // xfs filesystems. Ext4 on Linux 4.2.0 takes ~30 microseconds to fallocate 64MB,
        // compared to 27 milliseconds to zero-fill that same amount.
        platform.fallocate(fd(), pos, length);
    }

    /** Platform specific helper. */
    private static abstract class PlatformIO {
        abstract void fallocate(int fd, long pos, long length) throws IOException;

        abstract void fadvise(int fd, long offset, long length, int advice) throws IOException;
    }

    /** No-op helper. */
    private static class NullIO extends PlatformIO {
        static final NullIO INSTANCE = new NullIO();

        @Override
        void fallocate(int fd, long pos, long length) throws IOException {
        }

        @Override
        void fadvise(int fd, long offset, long length, int advice) throws IOException {
        }
    }

    /** Default POSIX I/O calls. */
    private static class DefaultIO extends PlatformIO {
        private static final MethodHandle posix_fallocate;
        private static final MethodHandle posix_fadvise;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = linker.defaultLookup();

            posix_fallocate = linker.downcallHandle
                (lookup.find("posix_fallocate").get(),
                 FunctionDescriptor.of
                 (ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_INT,  // fd
                  ValueLayout.JAVA_LONG, // offset
                  ValueLayout.JAVA_LONG) // len
                 );

            posix_fadvise = linker.downcallHandle
                (lookup.find("posix_fadvise").get(),
                 FunctionDescriptor.of
                 (ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_INT,  // fd
                  ValueLayout.JAVA_LONG, // offset
                  ValueLayout.JAVA_LONG, // len
                  ValueLayout.JAVA_INT)  // advice
                 );
        }

        @Override
        void fallocate(int fd, long offset, long len) throws IOException {
            int result;
            try {
                result = (int) posix_fallocate.invokeExact(fd, offset, len);
            } catch (Throwable e) {
                throw Utils.rethrow(e);
            }
            if (result != 0) {
                // Note: the system call above does not set errno.
                throw new IOException(errorMessage(result));
            }
        }

        @Override
        void fadvise(int fd, long offset, long len, int advice) throws IOException {
            int result;
            try {
                result = (int) posix_fadvise.invokeExact(fd, offset, len, advice);
            } catch (Throwable e) {
                throw Utils.rethrow(e);
            }
            if (result != 0) {
                throw new IOException(errorMessage(result));
            }
        }
    }

    /** Accounts for OSX not supporting some I/O operations. */
    public static class PlatformHolder {
        public static final PlatformIO INSTANCE;
        static {
            PlatformIO inst;
            if (OSX) {
                inst = NullIO.INSTANCE;
            } else {
                try {
                    inst = new DefaultIO();
                } catch (UnsatisfiedLinkError e) {
                    inst = NullIO.INSTANCE;
                }
            }
            INSTANCE = inst;
        }
    }

    private static PlatformIO platform() {
        return PlatformHolder.INSTANCE;
    }

    static class RT {
        private static final MethodHandle shm_open;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lookup = SymbolLookup.libraryLookup("librt.so", Arena.global());

            shm_open = linker.downcallHandle
                (lookup.find("shm_open").get(),
                 FunctionDescriptor.of
                 (ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,  // pathPtr
                  ValueLayout.JAVA_INT, // oflags
                  ValueLayout.JAVA_INT) // mode
                 );
        }

        static int shm_open(String path, int oflag, int mode) throws IOException {
            int fd;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment pathPtr = a.allocateUtf8String(path);
                fd = (int) shm_open.invokeExact(pathPtr, oflag, mode);
            } catch (Throwable e) {
                throw Utils.rethrow(e);
            }
            if (fd == -1) {
                throw lastErrorToException();
            }
            return fd;
        }
    }
}
