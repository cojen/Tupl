/*
 *  Copyright (C) 2017 Cojen.org
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

package org.cojen.tupl.repl;

import java.io.File;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.OutputStream;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import java.util.function.Consumer;
import java.util.function.LongConsumer;

import org.cojen.tupl.util.Latch;
import org.cojen.tupl.util.LatchCondition;

import static org.cojen.tupl.io.Utils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class Controller extends Latch implements StreamReplicator, Channel {
    private static final int MODE_FOLLOWER = 0, MODE_CANDIDATE = 1, MODE_LEADER = 2;
    private static final int ELECTION_DELAY_LOW_MILLIS = 200, ELECTION_DELAY_HIGH_MILLIS = 300;
    private static final int QUERY_TERMS_RATE_MILLIS = 1;
    private static final int MISSING_DELAY_LOW_MILLIS = 400, MISSING_DELAY_HIGH_MILLIS = 600;
    private static final int SYNC_COMMIT_RETRY_MILLIS = 100;
    private static final int CONNECT_TIMEOUT_MILLIS = 500;
    private static final int SNAPSHOT_REPLY_TIMEOUT_MILLIS = 2000;
    private static final int JOIN_TIMEOUT_MILLIS = 2000;
    private static final int MISSING_DATA_REQUEST_SIZE = 100_000;

    private static final byte CONTROL_OP_JOIN = 1, CONTROL_OP_UPDATE_ROLE = 2;

    private final Scheduler mScheduler;
    private final ChannelManager mChanMan;
    private final StateLog mStateLog;

    private final LatchCondition mSyncCommitCondition;

    private GroupFile mGroupFile;

    // Local role as desired, which might not initially match what the GroupFile says.
    private Role mLocalRole;

    // Normal and standby members are required to participate in consensus.
    private Peer[] mConsensusPeers;
    private Channel[] mConsensusChannels;
    // All channels includes observers, which cannot participate in consensus.
    private Channel[] mAllChannels;

    // Reference to leader's reply channel, only used for accessing the peer object.
    private Channel mLeaderReplyChannel;
    private Channel mLeaderRequestChannel;

    private int mLocalMode;

    private long mCurrentTerm;
    private long mVotedFor;
    private int mGrantsRemaining;
    private int mElectionValidated;

    private LogWriter mLeaderLogWriter;
    private ReplWriter mLeaderReplWriter;

    // Index used to check for missing data.
    private long mMissingContigIndex = Long.MAX_VALUE; // unknown initially
    private boolean mSkipMissingDataTask;
    private volatile boolean mReceivingMissingData;

    // Limit the rate at which missing terms are queried.
    private volatile long mNextQueryTermTime = Long.MIN_VALUE;

    private volatile Consumer<byte[]> mControlMessageAcceptor;

    /**
    * @param localSocket optional; used for testing
     */
    static Controller open(StateLog log, long groupToken, File groupFile,
                           SocketAddress localAddress, SocketAddress listenAddress,
                           Role localRole, Set<SocketAddress> seeds, ServerSocket localSocket)
        throws IOException
    {
        GroupFile gf = GroupFile.open(groupFile, localAddress, seeds.isEmpty());
        Controller con = new Controller(log, groupToken, gf);
        con.init(groupFile, localAddress, listenAddress, localRole, seeds, localSocket);
        return con;
    }

    private Controller(StateLog log, long groupToken, GroupFile gf) throws IOException {
        mStateLog = log;
        mScheduler = new Scheduler();
        mChanMan = new ChannelManager(mScheduler, groupToken, gf == null ? 0 : gf.groupId());
        mGroupFile = gf;
        mSyncCommitCondition = new LatchCondition();
    }

    /**
     * @param localSocket optional; used for testing
     */
    private void init(File groupFile,
                      SocketAddress localAddress, SocketAddress listenAddress,
                      Role localRole, Set<SocketAddress> seeds, ServerSocket localSocket)
        throws IOException
    {
        acquireExclusive();
        try {
            mLocalRole = localRole;

            if (mGroupFile == null) {
                // Need to join the group.

                GroupJoiner joiner = new GroupJoiner
                    (groupFile, mChanMan.getGroupToken(), localAddress, listenAddress);

                joiner.join(seeds, JOIN_TIMEOUT_MILLIS);

                mGroupFile = joiner.mGroupFile;
                mChanMan.setGroupId(mGroupFile.groupId());
            }

            long localMemberId = mGroupFile.localMemberId();

            if (localSocket == null) {
                mChanMan.setLocalMemberId(localMemberId, listenAddress);
            } else {
                mChanMan.setLocalMemberId(localMemberId, localSocket);
            }

            refreshPeerSet();
        } finally {
            releaseExclusive();
        }
    }

    // Caller must hold exclusive latch.
    private void refreshPeerSet() {
        Map<Long, Channel> currentPeerChannels = new HashMap<>();

        if (mAllChannels != null) {
            for (Channel channel : mAllChannels) {
                currentPeerChannels.put(channel.peer().mMemberId, channel);
            }
        }

        List<Peer> consensusPeers = new ArrayList<>();
        List<Channel> consensusChannels = new ArrayList<>();
        List<Channel> allChannels = new ArrayList<>();

        for (Peer peer : mGroupFile.allPeers()) {
            Channel channel = currentPeerChannels.remove(peer.mMemberId);

            if (channel == null) {
                channel = mChanMan.connect(peer, this);
            }

            allChannels.add(channel);

            if (peer.mRole == Role.NORMAL || peer.mRole == Role.STANDBY) {
                consensusPeers.add(peer);
                consensusChannels.add(channel);
            }
        }

        for (long toRemove : currentPeerChannels.keySet()) {
            mChanMan.disconnect(toRemove);
        }

        mConsensusPeers = consensusPeers.toArray(new Peer[0]);
        mConsensusChannels = consensusChannels.toArray(new Channel[0]);
        mAllChannels = allChannels.toArray(new Channel[0]);

        if (mLeaderReplWriter != null) {
            mLeaderReplWriter.update(mLeaderLogWriter, mAllChannels, consensusChannels.isEmpty());
        }

        if (mLocalMode != MODE_FOLLOWER && mGroupFile.localMemberRole() != Role.NORMAL) {
            // Step down as leader or candidate.
            toFollower();
        }

        // Wake up syncCommit waiters which are stuck because removed peers aren't responding.
        mSyncCommitCondition.signalAll();
    }

    @Override
    public boolean start() throws IOException {
        return start(null);
    }

    @Override
    public SnapshotReceiver restore(Map<String, String> options) throws IOException {
        if (mChanMan.isStarted()) {
            throw new IllegalStateException("Already started");
        }

        SocketSnapshotReceiver receiver = requestSnapshot(options);

        if (receiver == null) {
            return null;
        }

        try {
            start(receiver);
        } catch (Throwable e) {
            closeQuietly(receiver);
            throw e;
        }

        return receiver;
    }

    private boolean start(SocketSnapshotReceiver receiver) throws IOException {
        if (mChanMan.isStarted()) {
            if (receiver != null) {
                throw new IllegalStateException("Already started");
            }
            return false;
        }

        if (receiver != null) {
            mStateLog.truncateAll(receiver.prevTerm(), receiver.term(), receiver.index());
        }

        mChanMan.start(this);

        scheduleElectionTask();
        scheduleMissingDataTask();

        mChanMan.joinAcceptor(this::requestJoin);

        acquireShared();
        boolean roleChange = mGroupFile.localMemberRole() != mLocalRole;
        releaseShared();

        if (roleChange) {
            mScheduler.execute(this::roleChangeTask);
        }

        return true;
    }

    @Override
    public Reader newReader(long index, boolean follow) {
        if (follow) {
            return mStateLog.openReader(index);
        }

        acquireShared();
        try {
            Reader reader;
            if (mLeaderLogWriter != null
                && index >= mLeaderLogWriter.termStartIndex()
                && index < mLeaderLogWriter.termEndIndex())
            {
                reader = null;
            } else {
                reader = mStateLog.openReader(index);
            }
            return reader;
        } finally {
            releaseShared();
        }
    }

    @Override
    public Writer newWriter() {
        return createWriter(-1);
    }

    @Override
    public Writer newWriter(long index) {
        if (index < 0) {
            throw new IllegalArgumentException();
        }
        return createWriter(index);
    }

    private Writer createWriter(long index) {
        acquireExclusive();
        try {
            if (mLeaderReplWriter != null) {
                throw new IllegalStateException("Writer already exists");
            }
            if (mLeaderLogWriter == null || (index >= 0 && index != mLeaderLogWriter.index())) {
                return null;
            }
            ReplWriter writer = new ReplWriter
                (mLeaderLogWriter, mAllChannels, mConsensusPeers.length == 0);
            mLeaderReplWriter = writer;
            return writer;
        } finally {
            releaseExclusive();
        }
    }

    void writerClosed(ReplWriter writer) {
        acquireExclusive();
        if (mLeaderReplWriter == writer) {
            mLeaderReplWriter = null;
        }
        releaseExclusive();
    }

    @Override
    public void sync() throws IOException {
        mStateLog.sync();
    }

    @Override
    public boolean syncCommit(long index, long nanosTimeout) throws IOException {
        long nanosEnd = nanosTimeout <= 0 ? 0 : System.nanoTime() + nanosTimeout;

        while (true) {
            if (mStateLog.isDurable(index)) {
                return true;
            }

            if (nanosTimeout == 0) {
                return false;
            }

            TermLog termLog = mStateLog.termLogAt(index);
            long prevTerm = termLog.prevTermAt(index);
            long term = termLog.term();

            // Sync locally before remotely, avoiding race conditions when the peers reply back
            // quickly. The syncCommitReply method would end up calling commitDurable too soon.
            long commitIndex = mStateLog.syncCommit(prevTerm, term, index);

            if (index > commitIndex) {
                // If syncCommit returns -1, assume that the leadership changed and try again.
                if (commitIndex >= 0) {
                    throw new IllegalStateException
                        ("Invalid commit index: " + index + " > " + commitIndex);
                }
            } else {
                acquireShared();
                Channel[] channels = mConsensusChannels;
                releaseShared();

                if (channels.length == 0) {
                    // Already have consensus.
                    mStateLog.commitDurable(index);
                    return true;
                }

                for (Channel channel : channels) {
                    channel.syncCommit(this, prevTerm, term, index);
                }

                // Don't wait on the condition for too long. The remote calls to the peers
                // might have failed, and so retries are necessary.
                long actualTimeout = SYNC_COMMIT_RETRY_MILLIS * 1_000_000L;
                if (nanosTimeout >= 0) {
                    actualTimeout = Math.min(nanosTimeout, actualTimeout);
                }

                acquireExclusive();
                if (mStateLog.isDurable(index)) {
                    releaseExclusive();
                    return true;
                }
                int result = mSyncCommitCondition.await(this, actualTimeout, nanosEnd);
                releaseExclusive();

                if (result < 0) {
                    throw new InterruptedIOException();
                }
            }

            if (nanosTimeout > 0 && (nanosTimeout -= nanosEnd - System.nanoTime()) < 0) {
                nanosTimeout = 0;
            }
        }
    }

    @Override
    public long getLocalMemberId() {
        return mChanMan.getLocalMemberId();
    }

    @Override
    public SocketAddress getLocalAddress() {
        acquireShared();
        SocketAddress addr = mGroupFile.localMemberAddress();
        releaseShared();
        return addr;
    }

    @Override
    public Role getLocalRole() {
        acquireShared();
        Role role = mGroupFile.localMemberRole();
        releaseShared();
        return role;
    }

    @Override
    public Socket connect(SocketAddress addr) throws IOException {
        return mChanMan.connectPlain(addr);
    }

    @Override
    public void socketAcceptor(Consumer<Socket> acceptor) {
        mChanMan.socketAcceptor(acceptor);
    }

    @Override
    public void controlMessageReceived(long index, byte[] message) throws IOException {
        boolean quickCommit = false;

        acquireExclusive();
        try {
            boolean refresh;

            switch (message[0]) {
            default:
                // Unknown message type.
                return;
            case CONTROL_OP_JOIN:
                refresh = mGroupFile.applyJoin(index, message) != null;
                break;
            case CONTROL_OP_UPDATE_ROLE:
                refresh = mGroupFile.applyUpdateRole(message);
                break;
            }

            if (refresh) {
                refreshPeerSet();

                if (mLocalMode == MODE_LEADER) {
                    // Ensure that all replicas see the commit index, allowing them to receive
                    // and process the control message as soon as possible.
                    // TODO: Not required when quick commit reply is implemented for everything.
                    quickCommit = true;
                }
            }
        } finally {
            releaseExclusive();
        }

        if (quickCommit) {
            mScheduler.execute(this::affirmLeadership);
        }
    }

    @Override
    public void controlMessageAcceptor(Consumer<byte[]> acceptor) {
        mControlMessageAcceptor = acceptor;
    }

    @Override
    public SocketSnapshotReceiver requestSnapshot(Map<String, String> options) throws IOException {
        acquireShared();
        Channel[] channels = mAllChannels;
        releaseShared();

        if (channels.length == 0) {
            // No peers.
            return null;
        }

        // Snapshots are requested early, so wait for connections to be established.
        waitForConnections(channels);

        final Object requestedBy = Thread.currentThread();
        for (Channel channel : channels) {
            channel.peer().resetSnapshotScore(new SnapshotScore(requestedBy, channel));
            channel.snapshotScore(this);
        }

        long timeoutMillis = SNAPSHOT_REPLY_TIMEOUT_MILLIS;
        long end = System.currentTimeMillis() + timeoutMillis;

        List<SnapshotScore> results = new ArrayList<>(channels.length);

        for (int i=0; i<channels.length; ) {
            Channel channel = channels[i];
            SnapshotScore score = channel.peer().awaitSnapshotScore(requestedBy, timeoutMillis);
            if (score != null) {
                results.add(score);
            }
            if (++i >= channels.length) {
                break;
            }
            timeoutMillis = end - System.currentTimeMillis();
            if (timeoutMillis <= 0) {
                break;
            }
        }

        if (results.isEmpty()) {
            throw new ConnectException("Unable to obtain a snapshot from a peer (timed out)");
        }

        Collections.shuffle(results); // random selection in case of ties
        Collections.sort(results); // stable sort

        Socket sock = mChanMan.connectSnapshot(results.get(0).mChannel.peer().mAddress);

        try {
            return new SocketSnapshotReceiver(mGroupFile, sock, options);
        } catch (IOException e) {
            closeQuietly(sock);
            throw e;
        }
    }

    @Override
    public void snapshotRequestAcceptor(Consumer<SnapshotSender> acceptor) {
        final class Sender extends SocketSnapshotSender {
            Sender(Socket socket) throws IOException {
                super(mGroupFile, socket);
            }

            @Override
            TermLog termLogAt(long index) {
                return mStateLog.termLogAt(index);
            }
        }

        mChanMan.snapshotRequestAcceptor(sock -> {
            SnapshotSender sender;
            try {
                sender = new Sender(sock);
            } catch (IOException e) {
                closeQuietly(sock);
                return;
            }

            // FIXME: register the sender

            mScheduler.execute(() -> acceptor.accept(sender));
        });
    }

    final class ReplWriter implements Writer {
        private final LogWriter mWriter;
        private Channel[] mPeerChannels;
        private boolean mSelfCommit;

        ReplWriter(LogWriter writer, Channel[] peerChannels, boolean selfCommit) {
            mWriter = writer;
            mPeerChannels = peerChannels;
            mSelfCommit = selfCommit;
        }

        @Override
        public long term() {
            return mWriter.term();
        }

        @Override
        public long termStartIndex() {
            return mWriter.termStartIndex();
        }

        @Override
        public long termEndIndex() {
            return mWriter.termEndIndex();
        }

        @Override
        public long index() {
            return mWriter.index();
        }

        @Override
        public int write(byte[] data, int offset, int length, long highestIndex)
            throws IOException
        {
            Channel[] peerChannels;
            long prevTerm, term, index, commitIndex;
            int amt;

            synchronized (this) {
                peerChannels = mPeerChannels;

                if (peerChannels == null) {
                    // Deactivated.
                    return -1;
                }

                LogWriter writer = mWriter;
                index = writer.index();

                amt = writer.write(data, offset, length, highestIndex);

                if (amt <= 0) {
                    if (length > 0) {
                        mPeerChannels = null;
                    }
                    return amt;
                }

                mStateLog.captureHighest(writer);
                highestIndex = writer.mHighestIndex;

                if (mSelfCommit) {
                    // Only a consensus group of one, so commit changes immediately.
                    mStateLog.commit(highestIndex);
                }

                if (peerChannels.length == 0) {
                    return amt;
                }

                prevTerm = writer.prevTerm();
                term = writer.term();
                commitIndex = writer.mCommitIndex;
            }

            // FIXME: stream it
            data = Arrays.copyOfRange(data, offset, offset + length);

            for (Channel peerChan : peerChannels) {
                peerChan.writeData(null, prevTerm, term, index, highestIndex, commitIndex, data);
            }

            return amt;
        }

        @Override
        public long waitForCommit(long index, long nanosTimeout) throws InterruptedIOException {
            return mWriter.waitForCommit(index, nanosTimeout);
        }

        @Override
        public void uponCommit(long index, LongConsumer task) {
            mWriter.uponCommit(index, task);
        }

        @Override
        public void close() {
            mWriter.release();
            writerClosed(this);
        }

        synchronized void update(LogWriter writer, Channel[] peerChannels, boolean selfCommit) {
            if (mWriter == writer && mPeerChannels != null) {
                mPeerChannels = peerChannels;
                mSelfCommit = selfCommit;
            }
        }

        synchronized void deactivate() {
            mPeerChannels = null;
            mSelfCommit = false;
        }
    }

    @Override
    public void close() throws IOException {
        mChanMan.stop();
        mScheduler.shutdown();
        mStateLog.close();
    }

    /**
     * Enable or disable partitioned mode, which simulates a network partition. New connections
     * are rejected and existing connections are closed.
     */
    void partitioned(boolean enable) {
        mChanMan.partitioned(enable);
    }

    private static void waitForConnections(Channel[] channels) throws InterruptedIOException {
        int timeoutMillis = CONNECT_TIMEOUT_MILLIS;
        for (Channel channel : channels) {
            timeoutMillis = channel.waitForConnection(timeoutMillis);
        }
    }

    private void scheduleMissingDataTask() {
        int delayMillis = ThreadLocalRandom.current()
            .nextInt(MISSING_DELAY_LOW_MILLIS, MISSING_DELAY_HIGH_MILLIS);
        mScheduler.schedule(this::missingDataTask, delayMillis);
    }

    private void missingDataTask() {
        if (tryAcquireShared()) {
            if (mLocalMode == MODE_LEADER) {
                // Leader doesn't need to check for missing data.
                mMissingContigIndex = Long.MAX_VALUE; // reset to unknown
                mSkipMissingDataTask = true;
                releaseShared();
                return;
            }
            releaseShared();
        }

        if (mReceivingMissingData) {
            // Avoid overlapping requests for missing data if results are flowing in.
            mReceivingMissingData = false;
        }

        class Collector implements IndexRange {
            long[] mRanges;
            int mSize;

            @Override
            public void range(long startIndex, long endIndex) {
                if (mRanges == null) {
                    mRanges = new long[16];
                } else if (mSize >= mRanges.length) {
                    mRanges = Arrays.copyOf(mRanges, mRanges.length << 1);
                }
                mRanges[mSize++] = startIndex;
                mRanges[mSize++] = endIndex;
            }
        };

        Collector collector = new Collector();
        mMissingContigIndex = mStateLog.checkForMissingData(mMissingContigIndex, collector);

        for (int i=0; i<collector.mSize; ) {
            long startIndex = collector.mRanges[i++];
            long endIndex = collector.mRanges[i++];
            requestMissingData(startIndex, endIndex);
        }

        scheduleMissingDataTask();
    }

    private void requestMissingData(long startIndex, long endIndex) {
        // FIXME: Need a way to abort outstanding requests.
        System.out.println("must call queryData! " + startIndex + ".." + endIndex);

        long remaining = endIndex - startIndex;

        // Distribute the request among the channels at random.

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        acquireShared();
        // Only query normal and standby members. Observers aren't first-class and might not
        // have the data.
        Channel[] channels = mConsensusChannels;
        releaseShared();

        doRequestData: while (remaining > 0) {
            long amt = Math.min(remaining, MISSING_DATA_REQUEST_SIZE);

            int selected = rnd.nextInt(channels.length);
            int attempts = 0;

            while (true) {
                Channel channel = channels[selected];

                if (channel.queryData(this, startIndex, startIndex + amt)) {
                    break;
                }

                // Peer not available, so try another.

                if (++attempts >= channels.length) {
                    // Attempted all peers, and none are available.
                    break doRequestData;
                }

                selected++;
                if (selected >= channels.length) {
                    selected = 0;
                }
            }

            startIndex += amt;
            remaining -= amt;
        }
    }

    private void scheduleElectionTask() {
        int delayMillis = ThreadLocalRandom.current()
            .nextInt(ELECTION_DELAY_LOW_MILLIS, ELECTION_DELAY_HIGH_MILLIS);
        mScheduler.schedule(this::electionTask, delayMillis);
    }

    private void electionTask() {
        try {
            doElectionTask();
        } finally {
            scheduleElectionTask();
        }
    }

    private void doElectionTask() {
        Channel[] peerChannels;
        long term, candidateId;
        LogInfo info = new LogInfo();

        acquireExclusive();
        try {
            if (mLocalMode == MODE_LEADER) {
                // FIXME: If commit index is lagging behind and not moving, switch to follower.
                // This isn't in the Raft algorithm, but it really should be.
                doAffirmLeadership();
                return;
            }

            if (mElectionValidated >= 0) {
                // Current leader or candidate is still active, so don't start an election yet.
                mElectionValidated--;
                releaseExclusive();
                return;
            }

            if (mLocalMode == MODE_CANDIDATE) {
                // Abort current election and start a new one.
                toFollower();
            }

            mLeaderReplyChannel = null;
            mLeaderRequestChannel = null;

            if (mGroupFile.localMemberRole() != Role.NORMAL) {
                // Only NORMAL members can become candidates.
                releaseExclusive();
                return;
            }

            // Convert to candidate.
            // FIXME: Don't permit rogue members to steal leadership if process is
            // suspended. Need to double check against local clock to detect this. Or perhaps
            // check with peers to see if leader is up.
            mLocalMode = MODE_CANDIDATE;

            peerChannels = mConsensusChannels;

            mStateLog.captureHighest(info);

            try {
                mCurrentTerm = term = mStateLog.incrementCurrentTerm(1);
            } catch (IOException e) {
                releaseExclusive();
                uncaught(e);
                return;
            }

            candidateId = mChanMan.getLocalMemberId();
            mVotedFor = candidateId;

            // Only need a majority of vote grants (already voted for self).
            mGrantsRemaining = (peerChannels.length + 1) / 2;

            // Don't give up candidacy too soon.
            mElectionValidated = 1;
        } catch (Throwable e) {
            releaseExclusive();
            throw e;
        }

        if (mGrantsRemaining == 0) {
            // Only a group of one, so become leader immediately.
            toLeader(term, info.mHighestIndex);
        } else {
            releaseExclusive();

            for (Channel peerChan : peerChannels) {
                peerChan.requestVote(null, term, candidateId, info.mTerm, info.mHighestIndex);
            }
        }
    }

    private void affirmLeadership() {
        acquireExclusive();
        doAffirmLeadership();
    }

    // Caller must acquire exclusive latch, which is released by this method.
    private void doAffirmLeadership() {
        Channel[] peerChannels = mAllChannels;
        LogWriter writer = mLeaderLogWriter;
        releaseExclusive();

        if (writer == null) {
            // Not leader anymore.
            return;
        }

        long prevTerm = writer.prevTerm();
        long term = writer.term();
        long index = writer.index();

        mStateLog.captureHighest(writer);
        long highestIndex = writer.mHighestIndex;
        long commitIndex = writer.mCommitIndex;

        // FIXME: use a custom command
        // FIXME: ... or use standard client write method
        byte[] EMPTY_DATA = new byte[0];

        for (Channel peerChan : peerChannels) {
            peerChan.writeData(null, prevTerm, term, index, highestIndex, commitIndex, EMPTY_DATA);
        }
    }

    // Caller must hold exclusive latch.
    private void toFollower() {
        final int originalMode = mLocalMode;
        if (originalMode != MODE_FOLLOWER) {
            System.out.println("follower: " + mCurrentTerm);

            mLocalMode = MODE_FOLLOWER;
            mVotedFor = 0;
            mGrantsRemaining = 0;

            if (mLeaderLogWriter != null) {
                mLeaderLogWriter.release();
                mLeaderLogWriter = null;
            }

            if (mLeaderReplWriter != null) {
                mLeaderReplWriter.deactivate();
            }

            if (originalMode == MODE_LEADER && mSkipMissingDataTask) {
                mSkipMissingDataTask = false;
                scheduleMissingDataTask();
            }
        }
    }

    private void requestJoin(Socket s) {
        try {
            if (doRequestJoin(s)) {
                return;
            }
        } catch (Throwable e) {
            closeQuietly(s);
            rethrow(e);
        }

        closeQuietly(s);
    }

    /**
     * @return false if socket should be closed
     */
    private boolean doRequestJoin(Socket s) throws IOException {
        ChannelInputStream in = new ChannelInputStream(s.getInputStream(), 100);

        int op = in.read();

        if (op != GroupJoiner.OP_ADDRESS) {
            return joinFailure(s, ErrorCodes.UNKNOWN_OPERATION);
        }

        SocketAddress addr = GroupFile.parseSocketAddress(in.readStr(in.readIntLE()));

        if (!validateJoinAddress(s, addr)) {
            return joinFailure(s, ErrorCodes.INVALID_ADDRESS);
        }

        OutputStream out = s.getOutputStream();

        acquireShared();
        boolean isLeader = mLocalMode == MODE_LEADER;
        Channel leaderReplyChannel = mLeaderReplyChannel;
        releaseShared();

        if (!isLeader) {
            Peer leaderPeer;
            if (leaderReplyChannel == null || (leaderPeer = leaderReplyChannel.peer()) == null) {
                return joinFailure(s, ErrorCodes.NO_LEADER);
            }
            EncodingOutputStream eout = new EncodingOutputStream();
            eout.write(GroupJoiner.OP_ADDRESS);
            eout.encodeStr(leaderPeer.mAddress.toString());
            out.write(eout.toByteArray());
            return false;
        }

        Consumer<byte[]> acceptor = mControlMessageAcceptor;

        if (acceptor == null) {
            return joinFailure(s, ErrorCodes.NO_ACCEPTOR);
        }

        byte[] message = mGroupFile.proposeJoin(CONTROL_OP_JOIN, addr, (gfIn, index) -> {
            // Join is accepted, so reply with the log index info and the group file. The index
            // is immediately after the control message.

            try {
                TermLog termLog = mStateLog.termLogAt(index);

                byte[] buf = new byte[1000];
                int off = 0;
                buf[off++] = GroupJoiner.OP_JOINED;
                encodeLongLE(buf, off, termLog.prevTermAt(index)); off += 8;
                encodeLongLE(buf, off, termLog.term()); off += 8;
                encodeLongLE(buf, off, index); off += 8;

                while (true) {
                    int amt;
                    try {
                        amt = gfIn.read(buf, off, buf.length - off);
                    } catch (IOException e) {
                        // Report any GroupFile corruption.
                        uncaught(e);
                        return;
                    }
                    if (amt < 0) {
                        break;
                    }
                    out.write(buf, 0, off + amt);
                    off = 0;
                }
            } catch (IOException e) {
                // Ignore.
            } finally {
                closeQuietly(out);
            }
        });

        mScheduler.schedule(() -> {
            if (mGroupFile.discardJoinConsumer(message)) {
                closeQuietly(s);
            }
        }, JOIN_TIMEOUT_MILLIS);

        acceptor.accept(message);
        return true;
    }

    private static boolean joinFailure(Socket s, byte errorCode) throws IOException {
        s.getOutputStream().write(new byte[] {GroupJoiner.OP_ERROR, errorCode});
        return false;
    }

    private static boolean validateJoinAddress(Socket s, SocketAddress addr) {
        if (!(addr instanceof InetSocketAddress)) {
            return false;
        }

        SocketAddress socketAddr = s.getRemoteSocketAddress();

        if (!(socketAddr instanceof InetSocketAddress)) {
            return false;
        }

        return ((InetSocketAddress) addr).getAddress()
            .equals(((InetSocketAddress) socketAddr).getAddress());
    }

    private void roleChangeTask() {
        acquireShared();
        Role desiredRole = mLocalRole;
        if (desiredRole == mGroupFile.localMemberRole()) {
            releaseShared();
            return;
        }
        long groupVersion = mGroupFile.version();
        long localMemberId = mGroupFile.localMemberId();
        releaseShared();

        Channel requestChannel = leaderRequestChannel();

        if (requestChannel != null) {
            requestChannel.updateRole(this, groupVersion, localMemberId, desiredRole);
        }

        // Check again later.
        mScheduler.schedule(this::roleChangeTask, ELECTION_DELAY_LOW_MILLIS);
    }

    /**
     * @return this Controller instance if local member is the leader, or non-null if the
     * leader is remote, or null if the group has no leader
     */
    private Channel leaderRequestChannel() {
        Channel requestChannel;
        Channel replyChannel;

        acquireShared();
        boolean exclusive = false;

        while (true) {
            if (mLocalMode == MODE_LEADER) {
                release(exclusive);
                return this;
            }
            requestChannel = mLeaderRequestChannel;
            if (requestChannel != null || (replyChannel = mLeaderReplyChannel) == null) {
                release(exclusive);
                return requestChannel;
            }
            if (tryUpgrade()) {
                break;
            }
            releaseShared();
            acquireExclusive();
            exclusive = true;
        }

        // Find the request channel to the leader.

        Peer leader = replyChannel.peer();

        for (Channel channel : mAllChannels) {
            if (leader.equals(channel.peer())) {
                mLeaderRequestChannel = requestChannel = channel;
                break;
            }
        }

        releaseExclusive();

        return requestChannel;
    }

    @Override
    public boolean nop(Channel from) {
        return true;
    }

    @Override
    public boolean requestVote(Channel from, long term, long candidateId,
                               long highestTerm, long highestIndex)
    {
        long currentTerm;
        acquireExclusive();
        try {
            final long originalTerm = mCurrentTerm;

            try {
                mCurrentTerm = currentTerm = mStateLog.checkCurrentTerm(term);
            } catch (IOException e) {
                uncaught(e);
                return false;
            }

            if (currentTerm > originalTerm) {
                toFollower();
            }

            if (currentTerm >= originalTerm
                && ((mVotedFor == 0 || mVotedFor == candidateId)
                    && !isBehind(highestTerm, highestIndex)))
            {
                mVotedFor = candidateId;
                // Set voteGranted result bit to true.
                currentTerm |= 1L << 63;
                // Treat new candidate as active, so don't start a new election too soon.
                mElectionValidated = 1;
            }
        } finally {
            releaseExclusive();
        }

        from.requestVoteReply(null, currentTerm);
        return true;
    }

    private boolean isBehind(long term, long index) {
        LogInfo info = mStateLog.captureHighest();
        return term < info.mTerm || (term == info.mTerm && index < info.mHighestIndex);
    }

    @Override
    public boolean requestVoteReply(Channel from, long term) {
        acquireExclusive();

        final long originalTerm = mCurrentTerm;

        if (term < 0 && (term &= ~(1L << 63)) == originalTerm) {
            // Vote granted.

            if (--mGrantsRemaining > 0 || mLocalMode != MODE_CANDIDATE) {
                // Majority not received yet, or voting is over.
                releaseExclusive();
                return true;
            }

            if (mLocalMode == MODE_CANDIDATE) {
                LogInfo info = mStateLog.captureHighest();
                toLeader(term, info.mHighestIndex);
                return true;
            }
        } else {
            // Vote denied.

            try {
                mCurrentTerm = mStateLog.checkCurrentTerm(term);
            } catch (IOException e) {
                releaseExclusive();
                uncaught(e);
                return false;
            }

            if (mCurrentTerm <= originalTerm) {
                // Remain candidate for now, waiting for more votes.
                releaseExclusive();
                return true;
            }
        }

        toFollower();
        releaseExclusive();
        return true;
    }

    // Caller must acquire exclusive latch, which is released by this method.
    private void toLeader(long term, long index) {
        try {
            long prevTerm = mStateLog.termLogAt(index).prevTermAt(index);
            System.out.println("leader: " + prevTerm + " -> " + term + " @" + index);
            mLeaderLogWriter = mStateLog.openWriter(prevTerm, term, index);
            mLocalMode = MODE_LEADER;
            for (Channel channel : mAllChannels) {
                channel.peer().mMatchIndex = 0;
            }
        } catch (Throwable e) {
            releaseExclusive();
            uncaught(e);
        }

        doAffirmLeadership();
    }

    @Override
    public boolean queryTerms(Channel from, long startIndex, long endIndex) {
        mStateLog.queryTerms(startIndex, endIndex, (prevTerm, term, index) -> {
            from.queryTermsReply(null, prevTerm, term, index);
        });

        return true;
    }

    @Override
    public boolean queryTermsReply(Channel from, long prevTerm, long term, long startIndex) {
        try {
            queryReplyTermCheck(term);

            mStateLog.defineTerm(prevTerm, term, startIndex);
        } catch (IOException e) {
            uncaught(e);
        }

        return true;
    }

    /**
     * Term check to apply when receiving results from a query. Forces a conversion to follower
     * if necessary, although leaders don't issue queries.
     */
    private void queryReplyTermCheck(long term) throws IOException {
        acquireShared();
        long originalTerm = mCurrentTerm;

        if (term < originalTerm) {
            releaseShared();
            return;
        }

        if (!tryUpgrade()) {
            releaseShared();
            acquireExclusive();
            originalTerm = mCurrentTerm;
        }

        try {
            if (term > originalTerm) {
                mCurrentTerm = mStateLog.checkCurrentTerm(term);
                if (mCurrentTerm > originalTerm) {
                    toFollower();
                }
            }
        } finally {
            releaseExclusive();
        }
    }

    @Override
    public boolean queryData(Channel from, long startIndex, long endIndex) {
        if (endIndex <= startIndex) {
            return true;
        }

        try {
            LogReader reader = mStateLog.openReader(startIndex);

            try {
                long remaining = endIndex - startIndex;
                byte[] buf = new byte[(int) Math.min(9000, remaining)];

                while (true) {
                    long prevTerm = reader.prevTerm();
                    long index = reader.index();
                    long term = reader.term();

                    int amt = reader.readAny(buf, 0, (int) Math.min(buf.length, remaining));

                    if (amt <= 0) {
                        if (amt < 0) {
                            // Move to next term.
                            reader.release();
                            reader = mStateLog.openReader(startIndex);
                        }
                        break;
                    }

                    // FIXME: stream it
                    byte[] data = new byte[amt];
                    System.arraycopy(buf, 0, data, 0, amt);

                    from.queryDataReply(null, prevTerm, term, index, data);

                    startIndex += amt;
                    remaining -= amt;

                    if (remaining <= 0) {
                        break;
                    }
                }
            } finally {
                reader.release();
            }
        } catch (IOException e) {
            uncaught(e);
        }

        return true;
    }

    @Override
    public boolean queryDataReply(Channel from, long prevTerm, long term,
                                  long index, byte[] data)
    {
        mReceivingMissingData = true;

        try {
            queryReplyTermCheck(term);

            LogWriter writer = mStateLog.openWriter(prevTerm, term, index);
            if (writer != null) {
                try {
                    writer.write(data, 0, data.length, 0);
                } finally {
                    writer.release();
                }
            }
        } catch (IOException e) {
            uncaught(e);
        }

        return true;
    }

    @Override
    public boolean writeData(Channel from, long prevTerm, long term, long index,
                             long highestIndex, long commitIndex, byte[] data)
    {
        try {
            validateLeaderTerm(from, term);
        } catch (IOException e) {
            uncaught(e);
            return false;
        }

        try {
            LogWriter writer = mStateLog.openWriter(prevTerm, term, index);

            if (writer == null) {
                // FIXME: stash the write for later
                // Cannot write because terms are missing, so request them.
                long now = System.currentTimeMillis();
                if (now >= mNextQueryTermTime) {
                    LogInfo info = mStateLog.captureHighest();
                    if (highestIndex > info.mCommitIndex && index > info.mCommitIndex) {
                        from.queryTerms(null, info.mCommitIndex, index);
                    }
                    mNextQueryTermTime = now + QUERY_TERMS_RATE_MILLIS;
                }

                return true;
            }

            try {
                writer.write(data, 0, data.length, highestIndex);
                mStateLog.commit(commitIndex);
                mStateLog.captureHighest(writer);
                long highestTerm = writer.mTerm;
                if (highestTerm < term) {
                    // If the highest term is lower than the leader's, don't bother reporting
                    // it. The leader will just reject the reply.
                    return true;
                }
                term = highestTerm;
                highestIndex = writer.mHighestIndex;
            } finally {
                writer.release();
            }
        } catch (IOException e) {
            uncaught(e);
        }

        // TODO: Can skip reply if successful and highest didn't change.
        // TODO: Can skip reply if local role is OBSERVER.
        from.writeDataReply(null, term, highestIndex);

        return true;
    }

    /**
     * Called when receiving data from the leader.
     *
     * @throws IOException if check failed and caller should abort
     */
    private void validateLeaderTerm(Channel from, long term) throws IOException {
        acquireShared();
        long originalTerm = mCurrentTerm;

        if (term == originalTerm) {
            if (mElectionValidated > 0) {
                releaseShared();
                return;
            } else if (tryUpgrade()) {
                mLeaderReplyChannel = from;
                mElectionValidated = 1;
                mVotedFor = 0; // election is over
                releaseExclusive();
                return;
            }
        }

        if (!tryUpgrade()) {
            releaseShared();
            acquireExclusive();
            originalTerm = mCurrentTerm;
        }

        try {
            if (term != originalTerm) {
                mCurrentTerm = mStateLog.checkCurrentTerm(term);
                if (mCurrentTerm <= originalTerm) {
                    return;
                }
                toFollower();
            }

            mLeaderReplyChannel = from;
            mElectionValidated = 1;
            mVotedFor = 0; // election is over
        } finally {
            releaseExclusive();
        }
    }

    @Override
    public boolean writeDataReply(Channel from, long term, long highestIndex) {
        long commitIndex;

        acquireExclusive();
        try {
            if (mLocalMode != MODE_LEADER) {
                return true;
            }

            final long originalTerm = mCurrentTerm;

            if (term != originalTerm) {
                try {
                    mCurrentTerm = mStateLog.checkCurrentTerm(term);
                } catch (IOException e) {
                    uncaught(e);
                    return false;
                }

                if (mCurrentTerm > originalTerm) {
                    toFollower();
                } else {
                    // Cannot commit on behalf of older terms.
                }
                return true;
            }

            Peer peer = from.peer();
            long matchIndex = peer.mMatchIndex;

            if (highestIndex <= matchIndex || mConsensusPeers.length == 0) {
                return true;
            }

            // Updating and sorting the peers by match index is simple, but for large groups
            // (>10?), a tree data structure should be used instead.
            peer.mMatchIndex = highestIndex;
            Arrays.sort(mConsensusPeers);

            commitIndex = mConsensusPeers[mConsensusPeers.length >> 1].mMatchIndex;
        } finally {
            releaseExclusive();
        }

        mStateLog.commit(commitIndex);
        return true;
    }

    @Override
    public boolean syncCommit(Channel from, long prevTerm, long term, long index) {
        try {
            if (mStateLog.syncCommit(prevTerm, term, index) >= index) {
                from.syncCommitReply(null, mGroupFile.version(), term, index);
            }
            return true;
        } catch (IOException e) {
            uncaught(e);
            return false;
        }
    }

    @Override
    public boolean syncCommitReply(Channel from, long groupVersion, long term, long index) {
        if (groupVersion > mGroupFile.version()) {
            // FIXME: exec task to sync the group
            System.out.println("group mismatch");
        }

        long durableIndex;

        acquireExclusive();
        try {
            if (mConsensusPeers.length == 0 || term != mStateLog.termLogAt(index).term()) {
                // Received a stale reply.
                System.out.println("stale reply");
                return true;
            }

            Peer peer = from.peer();
            long syncMatchIndex = peer.mSyncMatchIndex;

            if (index > syncMatchIndex) {
                peer.mSyncMatchIndex = index;
            }

            // Updating and sorting the peers by match index is simple, but for large groups
            // (>10?), a tree data structure should be used instead.
            Arrays.sort(mConsensusPeers, (a, b) ->
                        Long.compare(a.mSyncMatchIndex, b.mSyncMatchIndex));
            
            durableIndex = mConsensusPeers[mConsensusPeers.length >> 1].mSyncMatchIndex;
        } finally {
            releaseExclusive();
        }

        try {
            if (mStateLog.commitDurable(durableIndex)) {
                acquireExclusive();
                try {
                    mSyncCommitCondition.signalAll();
                } finally {
                    releaseExclusive();
                }
            }
        } catch (IOException e) {
            uncaught(e);
        }

        return true;
    }

    @Override
    public boolean snapshotScore(Channel from) {
        // FIXME: reply with high session count and weight if no acceptor is installed

        acquireShared();
        int mode = mLocalMode;
        releaseShared();

        // FIXME: provide active session count
        from.snapshotScoreReply(null, 0, mode == MODE_LEADER ? 1 : -1);

        return true;
    }

    @Override
    public boolean snapshotScoreReply(Channel from, int activeSessions, float weight) {
        from.peer().snapshotScoreReply(activeSessions, weight);
        return true;
    }

    @Override
    public boolean updateRole(Channel from, long groupVersion, long memberId, Role role) {
        Consumer<byte[]> acceptor;
        byte[] message = null;
        byte result;

        acquireShared();
        tryUpdateRole: try {
            final long givenVersion = groupVersion;
            groupVersion = mGroupFile.version();

            acceptor = mControlMessageAcceptor;

            if (acceptor == null) {
                result = ErrorCodes.NO_ACCEPTOR;
                break tryUpdateRole;
            }

            if (mLocalMode != MODE_LEADER) {
                // Only the leader can update the role.
                result = ErrorCodes.NOT_LEADER;
                break tryUpdateRole;
            }

            if (givenVersion != groupVersion) {
                result = ErrorCodes.VERSION_MISMATCH;
                break tryUpdateRole;
            }

            Peer key = new Peer(memberId);
            Peer peer = mGroupFile.allPeers().ceiling(key); // findGe

            if (peer == null || peer.mMemberId != memberId) {
                // Member doesn't exist.
                // FIXME: Permit leader to change itself.
                result = ErrorCodes.UNKNOWN_MEMBER;
                break tryUpdateRole;
            }

            if (peer.mRole == role) {
                // Role already matches.
                result = ErrorCodes.SUCCESS;
                break tryUpdateRole;
            }

            message = mGroupFile.proposeUpdateRole(CONTROL_OP_UPDATE_ROLE, memberId, role);

            if (peer.mRole == Role.OBSERVER || role == Role.OBSERVER) {
                // When changing the consensus, restrict to one change at a time (by the
                // majority). Ensure that all consensus peers are caught up.

                int count = 0;
                for (Peer cp : mConsensusPeers) {
                    if (cp.mGroupVersion == groupVersion) {
                        count++;
                    }
                }

                if (count < ((mConsensusPeers.length + 1) >> 1)) {
                    // Majority of versions don't match.
                    message = null;
                    result = ErrorCodes.NO_CONSENSUS;

                    // FIXME: Can speed things up if followers inform the leader very early of
                    // their current group version, and if they inform the leader again
                    // whenever it changes.

                    // Request updated versions. Caller must retry.
                    for (Channel channel : mConsensusChannels) {
                        channel.groupVersion(this, groupVersion);
                    }

                    break tryUpdateRole;
                }
            }

            result = 0;
        } finally {
            releaseShared();
        }

        if (message != null) {
            acceptor.accept(message);
        }

        from.updateRoleReply(null, groupVersion, memberId, result);

        return true;
    }

    @Override
    public boolean updateRoleReply(Channel from, long groupVersion, long memberId, byte result) {
        /*
        System.out.println("updateRoleReply: " + from + ", " + groupVersion + ", " +
                           memberId + ", " + ErrorCodes.toString(result) + ", " +
                           mGroupFile.version());
        */

        // FIXME: log or report an event
        //System.out.println("updateRoleReply level: " + ErrorCodes.levelFor(result));

        // FIXME: If version mismatch schedule a task to sync the group file, but only if the
        // version truly doesn't match.

        return true;
    }

    @Override
    public boolean groupVersion(Channel from, long groupVersion) {
        // Note: If peer group version is higher than local version, can probably resync local
        // group file right away.
        from.peer().updateGroupVersion(groupVersion);
        from.groupVersionReply(null, mGroupFile.version());
        return true;
    }

    @Override
    public boolean groupVersionReply(Channel from, long groupVersion) {
        from.peer().updateGroupVersion(groupVersion);
        return true;
    }
}
