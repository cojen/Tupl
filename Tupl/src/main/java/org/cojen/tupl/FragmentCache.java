/*
 *  Copyright 2012 Brian S O'Neill
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

import static org.cojen.tupl.Node.*;

/**
 * Special cache implementation for fragment nodes, as used by fragmented
 * values.
 *
 * @author Brian S O'Neill
 */
class FragmentCache {
    private final LHT[] mHashTables;
    private final int mHashTableShift;

    FragmentCache(Database db, int maxCapacity) {
        this(db, maxCapacity, Runtime.getRuntime().availableProcessors() * 16);
    }

    private FragmentCache(Database db, int maxCapacity, int numHashTables) {
        numHashTables = Utils.roundUpPower2(numHashTables);
        maxCapacity = (maxCapacity + numHashTables - 1) / numHashTables;
        mHashTables = new LHT[numHashTables];
        for (int i=0; i<numHashTables; i++) {
            mHashTables[i] = new LHT(db, maxCapacity);
        }
        mHashTableShift = Integer.numberOfLeadingZeros(numHashTables - 1);
    }

    protected FragmentCache() {
        mHashTables = null;
        mHashTableShift = 0;
    }

    /**
     * Returns the node with the given id, possibly loading it and evicting
     * another.
     *
     * @param caller optional tree node which is latched and calling this method
     * @return node with shared latch held
     */
    Node get(Node caller, long nodeId) throws IOException {
        int hash = hash(nodeId);
        return mHashTables[hash >>> mHashTableShift].get(caller, nodeId, hash);
    }

    /**
     * Stores the node, and possibly evicts another. As a side-effect, node
     * type is set to TYPE_FRAGMENT. Node latch is not released, even if an
     * exception is thrown.
     *
     * @param caller optional tree node which is latched and calling this method
     * @param node exclusively latched node
     */
    void put(Node caller, Node node) throws IOException {
        int hash = hash(node.mId);
        mHashTables[hash >>> mHashTableShift].put(caller, node, hash);
    }

    /**
     * @param caller optional tree node which is latched and calling this method
     * @return exclusively latched node if found; null if not found
     */
    Node remove(Node caller, long nodeId) {
        int hash = hash(nodeId);
        return mHashTables[hash >>> mHashTableShift].remove(caller, nodeId, hash);
    }

    static int hash(long nodeId) {
        int hash = ((int) nodeId) ^ ((int) (nodeId >>> 32));
        // Scramble the hashcode a bit, just like ConcurrentHashMap does.
        hash += (hash <<  15) ^ 0xffffcd7d;
        hash ^= (hash >>> 10);
        hash += (hash <<   3);
        hash ^= (hash >>>  6);
        hash += (hash <<   2) + (hash << 14);
        return hash ^ (hash >>> 16);
    }

    /**
     * Simple "lossy" hashtable of Nodes. When a collision is found, the
     * existing entry (if TYPE_FRAGMENT) might simply be evicted.
     */
    static final class LHT {
        private static final float LOAD_FACTOR = 0.75f;

        private final Database mDatabase;
        private final int mMaxCapacity;
        private final Latch mLatch;

        private Node[] mEntries;
        private int mSize;
        private int mGrowThreshold;

        LHT(Database db, int maxCapacity) {
            // Initial capacity of must be a power of 2.
            mEntries = new Node[Utils.roundUpPower2(Math.min(16, maxCapacity))];
            mGrowThreshold = (int) (mEntries.length * LOAD_FACTOR);
            mDatabase = db;
            mMaxCapacity = maxCapacity;
            mLatch = new Latch();
        }

        /**
         * Returns the node with the given id, possibly loading it and evicting
         * another.
         *
         * @param caller optional tree node which is latched and calling this method
         * @return node with shared latch held
         */
        Node get(final Node caller, final long nodeId, final int hash) throws IOException {
            Latch latch = mLatch;
            latch.acquireShared();
            boolean htEx = false;
            boolean nEx = false;

            while (true) {
                final Node[] entries = mEntries;
                final int index = hash & (entries.length - 1);
                Node existing = entries[index];
                if (existing == null) {
                    mSize++;
                } else {
                    if (existing == caller || existing.mType != TYPE_FRAGMENT) {
                        existing = null;
                    } else {
                        if (nEx) {
                            existing.acquireExclusive();
                        } else {
                            existing.acquireShared();
                        }
                        if (existing.mId == nodeId) {
                            latch.release(htEx);
                            mDatabase.used(existing);
                            if (nEx) {
                                existing.downgrade();
                            }
                            return existing;
                        }
                    }
                }

                // Need to have an exclusive lock before making
                // modifications to hashtable.
                if (!htEx) {
                    htEx = true;
                    if (!latch.tryUpgrade()) {
                        if (existing != null) {
                            existing.release(nEx);
                        }
                        latch.releaseShared();
                        latch.acquireExclusive();
                        continue;
                    }
                }

                if (existing != null) {
                    if (existing.mType != TYPE_FRAGMENT) {
                        // Hashtable slot can be used without evicting anything.
                        existing.release(nEx);
                        existing = null;
                    } else if (rehash(caller, existing)) {
                        // See if rehash eliminates collision.
                        existing.release(nEx);
                        continue;
                    } else if (!nEx && !existing.tryUpgrade()) {
                        // Exclusive latch is required for eviction.
                        existing.releaseShared();
                        nEx = true;
                        continue;
                    }
                }

                // Allocate node and reserve slot.
                final Node node = mDatabase.allocLatchedNode();
                node.mId = nodeId;
                node.mType = TYPE_FRAGMENT;
                entries[index] = node;

                // Evict and load without ht latch held.
                latch.releaseExclusive();

                if (existing != null) {
                    try {
                        existing.doEvict(mDatabase);
                        existing.releaseExclusive();
                    } catch (IOException e) {
                        node.mId = 0;
                        node.releaseExclusive();
                        throw e;
                    }
                }

                mDatabase.readPage(nodeId, node.mPage);

                node.downgrade();
                return node;
            }
        }

        /**
         * Stores the node, and possibly evicts another. Node latch is not
         * released, even if an exception is thrown.
         *
         * @param caller optional tree node which is latched and calling this method
         * @param node latched node
         */
        void put(final Node caller, final Node node, final int hash) throws IOException {
            Latch latch = mLatch;
            latch.acquireExclusive();

            while (true) {
                final Node[] entries = mEntries;
                final int index = hash & (entries.length - 1);
                Node existing = entries[index];
                if (existing == null) {
                    mSize++;
                } else {
                    if (existing == caller || existing == node
                        || existing.mType != TYPE_FRAGMENT)
                    {
                        existing = null;
                    } else {
                        existing.acquireExclusive();
                        if (existing.mType != TYPE_FRAGMENT) {
                            // Hashtable slot can be used without evicting anything.
                            existing.releaseExclusive();
                            existing = null;
                        } else if (rehash(caller, existing)) {
                            // See if rehash eliminates collision.
                            existing.releaseExclusive();
                            continue;
                        }
                    }
                }

                node.mType = TYPE_FRAGMENT;
                entries[index] = node;

                // Evict without ht latch held.
                latch.releaseExclusive();

                if (existing != null) {
                    existing.doEvict(mDatabase);
                    existing.releaseExclusive();
                }

                return;
            }
        }

        Node remove(final Node caller, final long nodeId, final int hash) {
            Latch latch = mLatch;
            latch.acquireExclusive();

            Node[] entries = mEntries;
            int index = hash & (entries.length - 1);
            Node existing = entries[index];
            if (existing != null && existing != caller && existing.mId == nodeId) {
                existing.acquireExclusive();
                if (existing.mId == nodeId) {
                    entries[index] = null;
                    mSize--;
                    latch.releaseExclusive();
                    return existing;
                }
                existing.releaseExclusive();
            }

            latch.releaseExclusive();
            return null;
        }

        /**
         * Caller must hold exclusive latch.
         *
         * @param n latched node
         * @return false if not rehashed
         */
        private boolean rehash(Node caller, Node n) {
            Node[] entries;
            int capacity;
            if (mSize < mGrowThreshold ||
                (capacity = (entries = mEntries).length) >= mMaxCapacity)
            {
                return false;
            }

            capacity <<= 1;
            Node[] newEntries = new Node[capacity];
            int newSize = 0;
            int newMask = capacity - 1;

            for (int i=entries.length; --i>=0 ;) {
                Node e = entries[i];
                if (e != null && e != caller) {
                    long id;
                    if (e == n) {
                        if (e.mType != TYPE_FRAGMENT) {
                            continue;
                        }
                        id = e.mId;
                    } else {
                        e.acquireShared();
                        id = e.mId;
                        if (e.mType != TYPE_FRAGMENT) {
                            e.releaseShared();
                            continue;
                        }
                        e.releaseShared();
                    }
                    newEntries[hash(id) & newMask] = e;
                    newSize++;
                }
            }

            mEntries = newEntries;
            mSize = newSize;
            mGrowThreshold = (int) (capacity * LOAD_FACTOR);

            return true;
        }
    }
}
