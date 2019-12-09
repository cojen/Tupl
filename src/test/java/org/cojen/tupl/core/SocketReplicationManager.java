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

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.net.Socket;
import java.net.ServerSocket;

import java.util.Arrays;

import org.cojen.tupl.*;

import org.cojen.tupl.ext.ReplicationManager;

/**
 * 
 *
 * @author Brian S O'Neill
 */
class SocketReplicationManager implements ReplicationManager {
    private final ServerSocket mServerSocket;
    private final String mReplicaHost;
    private final int mPort;

    private volatile InputStream mReader;
    private volatile StreamWriter mWriter;

    private volatile Accessor mAccessor;

    private volatile long mPos;

    private byte[] mControlMessage;
    private long mControlPos;

    /**
     * @param replicaHost replica to connect to; pass null if local host is the replica
     * @param port replica port for connecting or listening
     */
    public SocketReplicationManager(String replicaHost, int port) throws IOException {
        if (replicaHost != null) {
            mServerSocket = null;
        } else {
            mServerSocket = new ServerSocket(port);
            port = mServerSocket.getLocalPort();
        }

        mReplicaHost = replicaHost;
        mPort = port;
    }

    int getPort() {
        return mPort;
    }

    @Override
    public long encoding() {
        return 2267011754526215480L;
    }

    @Override
    public void start(long position) throws IOException {
        mPos = position;
        if (mServerSocket != null) {
            // Local host is the replica. Wait for leader to connect.
            Socket s = mServerSocket.accept();
            mReader = s.getInputStream();
        } else {
            // Local host is the leader. Wait to connect to replica.
            var s = new Socket(mReplicaHost, mPort);
            mWriter = new StreamWriter(s.getOutputStream());
        }
    }

    @Override
    public boolean ready(Accessor accessor) throws IOException {
        mAccessor = accessor;
        return mReader == null;
    }

    @Override
    public long readPosition() {
        return mPos;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        InputStream in = mReader;
        if (in == null) {
            return -1;
        }
        int amt = in.read(b, off, len);
        if (amt > 0) {
            mPos += amt;
        } else {
            // Socket closed, but a leader writer is required. Write to dev/null.
            mWriter = new StreamWriter(null);
            mWriter.mDisabled = true;
        }
        return amt;
    }

    @Override
    public Writer writer() throws IOException {
        return mWriter;
    }

    @Override
    public void sync() throws IOException {
    }

    @Override
    public void syncConfirm(long position, long timeoutNanos) throws IOException {
    }

    @Override
    public boolean isLeader() {
        return mWriter != null;
    }

    @Override
    public void uponLeader(Runnable task) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean failover() throws IOException {
        return mReader != null;
    }

    @Override
    public void checkpointed(long position) {
    }

    @Override
    public synchronized void control(long position, byte[] message) throws IOException {
        mControlMessage = message;
        mControlPos = position;
        notifyAll();
    }

    @Override
    public void close() throws IOException {
        if (mReader != null) {
            mReader.close();
        }
        if (mWriter != null && mWriter.mOut != null) {
            mWriter.mOut.close();
        }
    }

    public synchronized void waitForLeadership() throws InterruptedException {
        StreamWriter writer = mWriter;
        if (writer == null) {
            throw new IllegalStateException();
        }
        while (!writer.mNotified) {
            wait();
        }
    }

    public void disableWrites() {
        // Create a dummy replica stream that simply blocks until closed.
        mReader = new InputStream() {
            private boolean mClosed;

            @Override
            public synchronized int read() throws IOException {
                while (!mClosed) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
                throw new IOException("Closed");
            }

            @Override
            public synchronized void close() {
                mClosed = true;
                notify();
            }
        };

        mWriter.mDisabled = true;
    }

    public void suspendConfirmation(boolean b) {
        mWriter.suspendConfirmation(b);
    }

    public long writeControl(byte[] message) throws IOException {
        return mAccessor.control(message);
    }

    public synchronized long waitForControl(long position, byte[] message)
        throws InterruptedException
    {
        while (true) {
            long current = mControlPos;
            if (current >= position) {
                if (current == position && !Arrays.equals(mControlMessage, message)) {
                    throw new IllegalStateException("Wrong message");
                }
                return current;
            }
            wait();
        }
    }

    private class StreamWriter implements Writer {
        private final OutputStream mOut;
        private boolean mNotified;
        private volatile boolean mDisabled;
        private volatile boolean mSuspendConfirmation;

        StreamWriter(OutputStream out) {
            mOut = out;
        }

        synchronized void suspendConfirmation(boolean b) {
            if (b) {
                mSuspendConfirmation = true;
            } else {
                mSuspendConfirmation = false;
                notifyAll();
            }
        }

        @Override
        public long position() {
            return mPos;
        }

        @Override
        public long confirmedPosition() {
            return mPos;
        }

        @Override
        public boolean leaderNotify(Runnable callback) {
            // Leadership is never lost, so no need to register the callback.
            mNotified = true;
            synchronized (SocketReplicationManager.this) {
                SocketReplicationManager.this.notifyAll();
            }
            return true;
        }

        @Override
        public boolean write(byte[] b, int off, int len, long commitPos) {
            try {
                if (mDisabled) {
                    return false;
                }
                mOut.write(b, off, len);
                mPos += len;
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public boolean confirm(long position, long timeoutNanos) throws InterruptedIOException {
            if (mDisabled) {
                return false;
            }

            try {
                if (mSuspendConfirmation) {
                    synchronized (this) {
                        while (mSuspendConfirmation) {
                            wait();
                        }
                        if (mDisabled) {
                            return false;
                        }
                    }
                }
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }

            return true;
        }

        @Override
        public long confirmEnd(long timeoutNanos) throws ConfirmationFailureException {
            if (!mDisabled) {
                throw new ConfirmationFailureException("Not disabled");
            }
            return mPos;
        }
    }
}
