/*
 *  Copyright 2013 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.IOException;

import java.util.concurrent.locks.Lock;

import static java.lang.System.arraycopy;

import static java.util.Arrays.fill;

import static org.cojen.tupl.Utils.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class TreeValueStream extends AbstractStream {
    // Op ordinals are relevant.
    private static final int OP_LENGTH = 0, OP_READ = 1, OP_SET_LENGTH = 2, OP_WRITE = 3;

    // Touches a fragment without extending the value length. Used for file compaction.
    static final byte[] TOUCH_VALUE = new byte[0];

    private final TreeCursor mCursor;
    private final Database mDb;

    /**
     * @param cursor positioned or unpositioned cursor, not autoloading
     */
    TreeValueStream(TreeCursor cursor) {
        mCursor = cursor;
        mDb = cursor.mTree.mDatabase;
    }

    @Override
    public LockResult open(Transaction txn, byte[] key) throws IOException {
        TreeCursor cursor = mCursor;
        if (cursor.key() != null) {
            close();
        }
        cursor.link(txn);
        try {
            return cursor.find(key);
        } catch (Throwable e) {
            mCursor.reset();
            throw e;
        }
    }

    @Override
    public Transaction link(Transaction txn) {
        return mCursor.link(txn);
    }

    @Override
    public long length() throws IOException {
        TreeCursorFrame frame;
        try {
            frame = mCursor.leafSharedNotSplit();
        } catch (IllegalStateException e) {
            checkOpen();
            throw e;
        }

        long result = action(frame, OP_LENGTH, 0, null, 0, 0);
        frame.mNode.releaseShared();
        return result;
    }

    @Override
    public void setLength(long length) throws IOException {
        // FIXME: txn undo/redo; be careful with large keys to avoid redo corruption
        try {
            if (length < 0) {
                mCursor.store(null);
                return;
            }

            final TreeCursorFrame leaf = mCursor.leafExclusiveNotSplit();

            final Lock sharedCommitLock = mCursor.sharedCommitLock(leaf);
            try {
                mCursor.notSplitDirty(leaf);
                action(leaf, OP_SET_LENGTH, length, EMPTY_BYTES, 0, 0);
                leaf.mNode.releaseExclusive();
            } finally {
                sharedCommitLock.unlock();
            }
        } catch (IllegalStateException e) {
            checkOpen();
            throw e;
        }
    }

    @Override
    int doRead(long pos, byte[] buf, int off, int len) throws IOException {
        TreeCursorFrame frame;
        try {
            frame = mCursor.leafSharedNotSplit();
        } catch (IllegalStateException e) {
            checkOpen();
            throw e;
        }

        int result = (int) action(frame, OP_READ, pos, buf, off, len);
        frame.mNode.releaseShared();
        return result;
    }

    @Override
    void doWrite(long pos, byte[] buf, int off, int len) throws IOException {
        // FIXME: txn undo/redo; be careful with large keys to avoid redo corruption
        try {
            final TreeCursorFrame leaf = mCursor.leafExclusiveNotSplit();

            final Lock sharedCommitLock = mCursor.sharedCommitLock(leaf);
            try {
                mCursor.notSplitDirty(leaf);
                action(leaf, OP_WRITE, pos, buf, off, len);
                leaf.mNode.releaseExclusive();
            } finally {
                sharedCommitLock.unlock();
            }
        } catch (IllegalStateException e) {
            checkOpen();
            throw e;
        }
    }

    @Override
    int selectBufferSize(int bufferSize) {
        if (bufferSize <= 1) {
            if (bufferSize < 0) {
                bufferSize = mDb.mPageSize;
            } else {
                bufferSize = 1;
            }
        } else if (bufferSize >= 65536) {
            bufferSize = 65536;
        }
        return bufferSize;
    }

    @Override
    void checkOpen() {
        if (mCursor.key() == null) {
            throw new IllegalStateException("Stream closed");
        }
    }

    @Override
    void doClose() {
        mCursor.reset();
    }

    /**
     * Determine if any fragment nodes at the given position are outside the compaction zone.
     *
     * @param frame latched leaf, not split, never released by this method
     * @param highestNodeId defines the highest node before the compaction zone; anything
     * higher is in the compaction zone
     * @return -1 if position is too high, 0 if no compaction required, or 1 if any nodes are
     * in the compaction zone
     */
    int compactCheck(final TreeCursorFrame frame, long pos, final long highestNodeId)
        throws IOException
    {
        final Node node = frame.mNode;

        int nodePos = frame.mNodePos;
        if (nodePos < 0) {
            // Value doesn't exist.
            return -1;
        }

        final byte[] page = node.mPage;
        int loc = decodeUnsignedShortLE(page, node.mSearchVecStart + nodePos);
        int header = page[loc++];
        loc += (header >= 0 ? header : (((header & 0x3f) << 8) | (page[loc] & 0xff))) + 1;

        header = page[loc++];
        if (header >= 0) {
            // Not fragmented.
            return pos >= header ? -1 : 0;
        }

        int len;
        if ((header & 0x20) == 0) {
            len = 1 + (((header & 0x1f) << 8) | (page[loc++] & 0xff));
        } else if (header != -1) {
            len = 1 + (((header & 0x0f) << 16)
                       | ((page[loc++] & 0xff) << 8) | (page[loc++] & 0xff));
        } else {
            // ghost
            return -1;
        }

        if ((header & Node.VALUE_FRAGMENTED) == 0) {
            // Not fragmented.
            return pos >= len ? -1 : 0;
        }

        // Read the fragment header, as described by the Database.fragment method.
        header = page[loc++];

        final long vLen = Database.decodeFullFragmentedValueLength(header, page, loc);

        if (pos >= vLen) {
            return -1;
        }

        // Advance past the value length field.
        loc += 2 + ((header >> 1) & 0x06);

        if ((header & 0x02) != 0) {
            // Inline content.
            final int inLen = decodeUnsignedShortLE(page, loc);
            if (pos < inLen) {
                // Positioned within inline content.
                return 0;
            }
            pos -= inLen;
            loc = loc + 2 + inLen;
        }

        if ((header & 0x01) == 0) {
            // Direct pointers.
            loc += (((int) pos) / page.length) * 6;
            final long fNodeId = decodeUnsignedInt48LE(page, loc);
            return fNodeId > highestNodeId ? 1 : 0; 
        }

        // Indirect pointers.

        final long inodeId = decodeUnsignedInt48LE(page, loc);
        if (inodeId == 0) {
            // Sparse value.
            return 0;
        }

        final FragmentCache fc = mDb.mFragmentCache;
        Node inode = fc.get(inodeId);
        int level = Database.calculateInodeLevels(vLen, page.length);

        while (true) {
            level--;
            long levelCap = mDb.levelCap(level);
            long childNodeId = decodeUnsignedInt48LE(inode.mPage, ((int) (pos / levelCap)) * 6);
            inode.releaseShared();
            if (childNodeId > highestNodeId) {
                return 1;
            }
            if (level <= 0 || childNodeId == 0) {
                return 0;
            }
            inode = fc.get(childNodeId);
            pos %= levelCap;
        }
    }

    /**
     * Caller must hold shared commit lock when using OP_SET_LENGTH or OP_WRITE.
     *
     * @param frame latched shared for read op, exclusive for write op; released only if an
     * exception is thrown
     * @param b ignored by OP_LENGTH; OP_SET_LENGTH must pass EMPTY_BYTES
     * @return applicable only to OP_LENGTH and OP_READ
     */
    private long action(final TreeCursorFrame frame,
                        final int op, long pos, final byte[] b, int bOff, int bLen)
        throws IOException
    {
        Node node = frame.mNode;

        int nodePos = frame.mNodePos;
        if (nodePos < 0) {
            // Value doesn't exist.

            if (op <= OP_READ) {
                // Handle OP_LENGTH and OP_READ.
                return -1;
            }

            // Handle OP_SET_LENGTH and OP_WRITE.

            if (b == TOUCH_VALUE) {
                return 0;
            }

            // Method releases latch if an exception is thrown.
            node = mCursor.insertBlank(frame, node, pos + bLen);

            if (bLen <= 0) {
                return 0;
            }

            // Fallthrough and complete the write operation. Need to re-assign nodePos, because
            // the insert operation changed it.
            nodePos = frame.mNodePos;
        }

        byte[] page = node.mPage;
        int loc = decodeUnsignedShortLE(page, node.mSearchVecStart + nodePos);
        // Skip the key.
        int header = page[loc++];
        loc += (header >= 0 ? header : (((header & 0x3f) << 8) | (page[loc] & 0xff))) + 1;

        int vHeaderLoc = loc;
        long vLen;

        header = page[loc++];
        if (header >= 0) {
            vLen = header;
        } else fragmented: {
            int len;
            if ((header & 0x20) == 0) {
                len = 1 + (((header & 0x1f) << 8) | (page[loc++] & 0xff));
            } else if (header != -1) {
                len = 1 + (((header & 0x0f) << 16)
                           | ((page[loc++] & 0xff) << 8) | (page[loc++] & 0xff));
            } else {
                // ghost
                if (op <= OP_READ) {
                    // Handle OP_LENGTH and OP_READ.
                    return -1;
                }

                if (b == TOUCH_VALUE) {
                    return 0;
                }

                // FIXME: write ops; create the value
                node.releaseExclusive();
                throw null;
            }

            if ((header & Node.VALUE_FRAGMENTED) == 0) {
                // Not really fragmented.
                vLen = len;
                break fragmented;
            }

            // Operate against a fragmented value. First read the fragment header, as described
            // by the Database.fragment method.
            header = page[loc++];

            switch ((header >> 2) & 0x03) {
            default:
                vLen = decodeUnsignedShortLE(page, loc);
                break;
            case 1:
                vLen = decodeIntLE(page, loc) & 0xffffffffL;
                break;
            case 2:
                vLen = decodeUnsignedInt48LE(page, loc);
                break;
            case 3:
                vLen = decodeLongLE(page, loc);
                if (vLen < 0) {
                    if (op <= OP_READ) {
                        node.releaseShared();
                    } else {
                        node.releaseExclusive();
                    }
                    throw new LargeValueException(vLen);
                }
                break;
            }

            // Advance past the value length field.
            loc += 2 + ((header >> 1) & 0x06);

            switch (op) {
            case OP_LENGTH: default:
                return vLen;

            case OP_READ: try {
                if (bLen <= 0 || pos >= vLen) {
                    return 0;
                }

                bLen = Math.min((int) (vLen - pos), bLen);
                final int total = bLen;

                if ((header & 0x02) != 0) {
                    // Inline content.
                    final int inLen = decodeUnsignedShortLE(page, loc);
                    loc += 2;
                    final int amt = (int) (inLen - pos);
                    if (amt <= 0) {
                        // Not reading any inline content.
                        pos -= inLen;
                    } else if (bLen <= amt) {
                        arraycopy(page, (int) (loc + pos), b, bOff, bLen);
                        return bLen;
                    } else {
                        arraycopy(page, (int) (loc + pos), b, bOff, amt);
                        bLen -= amt;
                        bOff += amt;
                        pos = 0;
                    }
                    loc += inLen;
                }

                final FragmentCache fc = mDb.mFragmentCache;

                if ((header & 0x01) == 0) {
                    // Direct pointers.
                    final int ipos = (int) pos;
                    loc += (ipos / page.length) * 6;
                    int fNodeOff = ipos % page.length;
                    while (true) {
                        final int amt = Math.min(bLen, page.length - fNodeOff);
                        final long fNodeId = decodeUnsignedInt48LE(page, loc);
                        if (fNodeId == 0) {
                            // Reading a sparse value.
                            fill(b, bOff, bOff + amt, (byte) 0);
                        } else {
                            final Node fNode = fc.get(fNodeId);
                            arraycopy(fNode.mPage, fNodeOff, b, bOff, amt);
                            fNode.releaseShared();
                        }
                        bLen -= amt;
                        if (bLen <= 0) {
                            return total;
                        }
                        bOff += amt;
                        loc += 6;
                        fNodeOff = 0;
                    }
                }

                // Indirect pointers.

                final long inodeId = decodeUnsignedInt48LE(page, loc);
                if (inodeId == 0) {
                    // Reading a sparse value.
                    fill(b, bOff, bOff + bLen, (byte) 0);
                } else {
                    final Node inode = fc.get(inodeId);
                    final int levels = Database.calculateInodeLevels(vLen, page.length);
                    readMultilevelFragments(pos, levels, inode, b, bOff, bLen);
                }

                return total;
            } catch (IOException e) {
                node.releaseShared();
                throw e;
            }

            case OP_SET_LENGTH:
                // FIXME: If same return, if shorter truncate, else fall through.
                node.releaseExclusive();
                throw null;

            case OP_WRITE: try {
                if (bLen == 0 & b != TOUCH_VALUE) {
                    return 0;
                }

                if ((pos + bLen) > vLen) {
                    if (b == TOUCH_VALUE) {
                        // Don't extend the value.
                        return 0;
                    }
                    // FIXME: extend length, accounting for length field growth
                }

                //bLen = Math.min((int) (vLen - pos), bLen);

                if ((header & 0x02) != 0) {
                    // Inline content.
                    final int inLen = decodeUnsignedShortLE(page, loc);
                    loc += 2;
                    final int amt = (int) (inLen - pos);
                    if (amt <= 0) {
                        // Not writing any inline content.
                        pos -= inLen;
                    } else if (bLen <= amt) {
                        arraycopy(b, bOff, page, (int) (loc + pos), bLen);
                        return 0;
                    } else {
                        arraycopy(b, bOff, page, (int) (loc + pos), amt);
                        bLen -= amt;
                        bOff += amt;
                        pos = 0;
                    }
                    loc += inLen;
                    vLen -= inLen;
                }

                final FragmentCache fc = mDb.mFragmentCache;

                if ((header & 0x01) == 0) {
                    // Direct pointers.
                    final int ipos = (int) pos;
                    loc += (ipos / page.length) * 6;
                    int fNodeOff = ipos % page.length;
                    while (true) {
                        final int amt = Math.min(bLen, page.length - fNodeOff);
                        final long fNodeId = decodeUnsignedInt48LE(page, loc);
                        if (fNodeId == 0) {
                            // Writing into a sparse value. Allocate a node and point to it.
                            final Node fNode = mDb.allocFragmentNode();
                            try {
                                encodeInt48LE(page, loc, fNode.mId);

                                // Now write to the new page, zero-filling the gaps.
                                byte[] fNodePage = fNode.mPage;
                                fill(fNodePage, 0, fNodeOff, (byte) 0);
                                arraycopy(b, bOff, fNodePage, fNodeOff, amt);
                                fill(fNodePage, fNodeOff + amt, fNodePage.length, (byte) 0);
                            } finally {
                                fNode.releaseExclusive();
                            }
                        } else {
                            // Obtain node from cache, or load it only for partial write.
                            final Node fNode = fc.getw(fNodeId, amt < page.length);
                            try {
                                if (mDb.markFragmentDirty(fNode)) {
                                    encodeInt48LE(page, loc, fNode.mId);
                                }
                                arraycopy(b, bOff, fNode.mPage, fNodeOff, amt);
                            } finally {
                                fNode.releaseExclusive();
                            }
                        }
                        bLen -= amt;
                        if (bLen <= 0) {
                            return 0;
                        }
                        bOff += amt;
                        loc += 6;
                        fNodeOff = 0;
                    }
                }

                // Indirect pointers.

                final Node inode;
                setPtr: {
                    final long inodeId = decodeUnsignedInt48LE(page, loc);

                    if (inodeId == 0) {
                        // Writing into a sparse value. Allocate a node and point to it.
                        inode = mDb.allocFragmentNode();
                        fill(inode.mPage, (byte) 0);
                    } else {
                        inode = fc.getw(inodeId, true);
                        try {
                            if (!mDb.markFragmentDirty(inode)) {
                                // Already dirty, so no need to update the pointer.
                                break setPtr;
                            }
                        } catch (Throwable e) {
                            inode.releaseExclusive();
                            throw e;
                        }
                    }

                    encodeInt48LE(page, loc, inode.mId);
                }

                final int levels = Database.calculateInodeLevels(vLen, page.length);
                writeMultilevelFragments(pos, levels, inode, b, bOff, bLen);

                return 0;
            } catch (IOException e) {
                node.releaseExclusive();
                throw e;
            }
            } // end switch(op)
        }

        // Operate against a non-fragmented value.

        switch (op) {
        case OP_LENGTH: default:
            return vLen;

        case OP_READ:
            if (bLen <= 0 || pos >= vLen) {
                bLen = 0;
            } else {
                bLen = Math.min((int) (vLen - pos), bLen);
                arraycopy(page, (int) (loc + pos), b, bOff, bLen);
            }
            return bLen;

        case OP_SET_LENGTH:
            if (pos <= vLen) {
                // Truncate length. 

                int newLen = (int) pos;
                int oldLen = (int) vLen;
                int garbageAccum = oldLen - newLen;

                shift: {
                    final int vLoc;
                    final int vShift;

                    if (newLen <= 127) {
                        page[vHeaderLoc] = (byte) newLen;
                        if (oldLen <= 127) {
                            break shift;
                        } else if (oldLen <= 8192) {
                            vLoc = vHeaderLoc + 2;
                            vShift = 1;
                        } else {
                            vLoc = vHeaderLoc + 3;
                            vShift = 2;
                        }
                    } else if (newLen <= 8192) {
                        page[vHeaderLoc] = (byte) (0x80 | ((newLen - 1) >> 8));
                        page[vHeaderLoc + 1] = (byte) (newLen - 1);
                        if (oldLen <= 8192) {
                            break shift;
                        } else {
                            vLoc = vHeaderLoc + 3;
                            vShift = 1;
                        }
                    } else {
                        page[vHeaderLoc] = (byte) (0xa0 | ((newLen - 1) >> 16));
                        page[vHeaderLoc + 1] = (byte) ((newLen - 1) >> 8);
                        page[vHeaderLoc + 2] = (byte) (newLen - 1);
                        break shift;
                    }

                    garbageAccum += vShift;
                    arraycopy(page, vLoc, page, vLoc - vShift, newLen);
                }

                node.mGarbage += garbageAccum;
                return 0;
            }

            // Break out for length increase, by appending an empty value.
            break;

        case OP_WRITE:
            if (b == TOUCH_VALUE) {
                return 0;
            }

            if (pos < vLen) {
                final long end = pos + bLen;
                if (end <= vLen) {
                    // Writing within existing value region.
                    arraycopy(b, bOff, page, (int) (loc + pos), bLen);
                    return 0;
                } else if (pos == 0 && bOff == 0 && bLen == b.length) {
                    // Writing over the entire value.
                    try {
                        node.updateLeafValue(mCursor.mTree, nodePos, 0, b);
                    } catch (IOException e) {
                        node.releaseExclusive();
                        throw e;
                    }
                    return 0;
                } else {
                    // Write the overlapping region, and then append the rest.
                    int len = (int) (vLen - pos);
                    arraycopy(b, bOff, page, (int) (loc + pos), len);
                    pos = vLen;
                    bOff += len;
                    bLen -= len;
                }
            }

            // Break out for append.
            break;
        }

        // This point is reached for appending to a non-fragmented value. There's all kinds
        // of optimizations that can be performed here, but keep things simple. Delete the
        // old value, insert a blank value, and then update it.

        byte[] oldValue = new byte[(int) vLen];
        System.arraycopy(page, loc, oldValue, 0, oldValue.length);

        node.deleteLeafEntry(mCursor.mTree, nodePos);
        frame.mNodePos = ~nodePos;

        // Method releases latch if an exception is thrown.
        mCursor.insertBlank(frame, node, pos + bLen);

        action(frame, OP_WRITE, 0, oldValue, 0, oldValue.length);

        if (bLen > 0) {
            action(frame, OP_WRITE, pos, b, bOff, bLen);
        }

        return 0;
    }

    /**
     * @param pos value position being read
     * @param level inode level; at least 1
     * @param inode shared latched parent inode; always released by this method
     * @param b slice of complete value being reconstructed
     */
    private void readMultilevelFragments(long pos, int level, Node inode,
                                         byte[] b, int bOff, int bLen)
        throws IOException
    {
        try {
            byte[] page = inode.mPage;
            level--;
            long levelCap = mDb.levelCap(level);

            int firstChild = (int) (pos / levelCap);
            int lastChild = (int) ((pos + bLen - 1) / levelCap);

            int childNodeCount = lastChild - firstChild + 1;

            final FragmentCache fc = mDb.mFragmentCache;

            // Handle a possible partial read from the first page.
            long ppos = pos % levelCap;

            for (int poffset = firstChild * 6, i=0; i<childNodeCount; poffset += 6, i++) {
                long childNodeId = decodeUnsignedInt48LE(page, poffset);
                int len = (int) Math.min(levelCap - ppos, bLen);

                if (childNodeId == 0) {
                    // Reading a sparse value.
                    fill(b, bOff, bOff + len, (byte) 0);
                } else {
                    Node childNode = fc.get(childNodeId);
                    if (level <= 0) {
                        arraycopy(childNode.mPage, (int) ppos, b, bOff, len);
                        childNode.releaseShared();
                    } else {
                        readMultilevelFragments(ppos, level, childNode, b, bOff, len);
                    }
                }
                bLen -= len;
                if (bLen <= 0) {
                    break;
                }
                bOff += len;
                // Remaining reads begin at the start of the page.
                ppos = 0;
            }
        } finally {
            inode.releaseShared();
        }
    }

    /**
     * @param pos value position being read
     * @param level inode level; at least 1
     * @param inode exclusively latched parent inode; always released by this method
     * @param value slice of complete value being written
     */
    private void writeMultilevelFragments(long pos, int level, Node inode,
                                          byte[] b, int bOff, int bLen)
        throws IOException
    {
        try {
            byte[] page = inode.mPage;
            level--;
            long levelCap = mDb.levelCap(level);

            int firstChild = (int) (pos / levelCap);
            int lastChild = bLen == 0 ? firstChild : ((int) ((pos + bLen - 1) / levelCap));

            int childNodeCount = lastChild - firstChild + 1;

            final FragmentCache fc = mDb.mFragmentCache;

            // Handle a possible partial write to the first page.
            long ppos = pos % levelCap;

            for (int poffset = firstChild * 6, i=0; i<childNodeCount; poffset += 6, i++) {
                int len = (int) Math.min(levelCap - ppos, bLen);
                int off = (int) ppos;

                final Node childNode;
                setPtr: {
                    long childNodeId = decodeUnsignedInt48LE(page, poffset);
                    boolean partial = level > 0 | off > 0 | len < page.length;

                    if (childNodeId == 0) {
                        childNode = mDb.allocFragmentNode();
                        if (partial) {
                            // New page must be zero-filled.
                            fill(childNode.mPage, (byte) 0);
                        }
                    } else {
                        // Obtain node from cache, or load it only for partial write.
                        childNode = fc.getw(childNodeId, partial);
                        try {
                            if (!mDb.markFragmentDirty(childNode)) {
                                // Already dirty, so no need to update the pointer.
                                break setPtr;
                            }
                        } catch (Throwable e) {
                            childNode.releaseExclusive();
                            throw e;
                        }
                    }

                    encodeInt48LE(page, poffset, childNode.mId);
                }

                if (level <= 0) {
                    arraycopy(b, bOff, childNode.mPage, off, len);
                    childNode.releaseExclusive();
                } else {
                    writeMultilevelFragments(ppos, level, childNode, b, bOff, len);
                }

                bLen -= len;
                if (bLen <= 0) {
                    break;
                }
                bOff += len;
                // Remaining writes begin at the start of the page.
                ppos = 0;
            }
        } catch (Throwable e) {
            // Panic.
            mDb.close(e);
            throw e;
        } finally {
            inode.releaseExclusive();
        }
    }
}
