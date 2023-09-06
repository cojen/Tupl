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

import java.io.IOException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import java.lang.ref.WeakReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.cojen.tupl.CorruptDatabaseException;
import org.cojen.tupl.Cursor;
import org.cojen.tupl.DeletedIndexException;
import org.cojen.tupl.DurabilityMode;
import org.cojen.tupl.Entry;
import org.cojen.tupl.Index;
import org.cojen.tupl.LockFailureException;
import org.cojen.tupl.LockMode;
import org.cojen.tupl.SchemaChangeException;
import org.cojen.tupl.Table;
import org.cojen.tupl.Transaction;
import org.cojen.tupl.UniqueConstraintException;
import org.cojen.tupl.View;

import org.cojen.tupl.core.CoreDatabase;
import org.cojen.tupl.core.LHashTable;
import org.cojen.tupl.core.RowPredicateLock;
import org.cojen.tupl.core.ScanVisitor;

import org.cojen.tupl.diag.EventListener;
import org.cojen.tupl.diag.EventType;

import org.cojen.tupl.util.Runner;

import static org.cojen.tupl.rows.RowUtils.*;

/**
 * Main class for managing row persistence via tables.
 *
 * @author Brian S O'Neill
 */
public final class RowStore {
    private final WeakReference<RowStore> mSelfRef;
    final CoreDatabase mDatabase;

    /* Schema metadata for all types.

       (indexId) ->
         int current schemaVersion,
         long tableVersion, (non-zero, incremented each time the table definition changes)
         ColumnSet[] alternateKeys, (a type of secondary index)
         ColumnSet[] secondaryIndexes

       (indexId, schemaVersion) -> primary ColumnSet

       (indexId, hash(primary ColumnSet)) -> schemaVersion[]    // hash collision chain

       (indexId, 0, K_SECONDARY, descriptor) -> secondaryIndexId, state

       (indexId, 0, K_TYPE_NAME) -> current type name (UTF-8)

       (secondaryIndexId, 0, K_DROPPED) -> primaryIndexId, descriptor

       (0L, indexId, taskType) -> ...  workflow task against an index

       The schemaVersion is limited to 2^31, and the hash is encoded with bit 31 set,
       preventing collisions. In addition, schemaVersion cannot be 0, and so the extended keys
       won't collide, although the longer overall key length prevents collisions as well.

       Because indexId 0 is reserved (for the registry), it won't be used for row storage, and
       so it can be used for tracking workflow tasks.
     */
    private final Index mSchemata;

    private final WeakCache<Index, TableManager<?>, Object> mTableManagers;

    private final LHashTable.Obj<RowPredicateLock<?>> mIndexLocks;

    private WeakCache<TranscoderKey, Transcoder, SecondaryInfo> mSortTranscoderCache;
    private static final VarHandle cSortTranscoderCacheHandle;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            cSortTranscoderCacheHandle = lookup.findVarHandle
                (RowStore.class, "mSortTranscoderCache", WeakCache.class);
        } catch (Throwable e) {
            throw RowUtils.rethrow(e);
        }
    }

    // Used by tests.
    volatile boolean mStallTasks;

    // Extended key for referencing secondary indexes.
    private static final int K_SECONDARY = 1;

    // Extended key to store the fully qualified type name.
    private static final int K_TYPE_NAME = 2;

    // Extended key to track secondary indexes which are being dropped.
    private static final int K_DROPPED = 3;

    private static final int TASK_DELETE_SCHEMA = 1, TASK_NOTIFY_SCHEMA = 2;

    public RowStore(CoreDatabase db, Index schemata) throws IOException {
        mSelfRef = new WeakReference<>(this);
        mDatabase = db;
        mSchemata = schemata;
        mTableManagers = new WeakCache<>();
        mIndexLocks = new LHashTable.Obj<>(8);

        registerToUpdateSchemata();

        // Finish any tasks left over from when the RowStore was last used. Call this from a
        // separate thread to unblock LocalDatabase, which is holding mOpenTreesLatch when
        // constructing this RowStore instance.
        Runner.start(() -> {
            try {
                finishAllWorkflowTasks();
            } catch (Throwable e) {
                uncaught(e);
            }
        });
    }

    WeakReference<RowStore> ref() {
        return mSelfRef;
    }

    public Index schemata() {
        return mSchemata;
    }

    /**
     * Scans the schemata and all table secondary indexes. Doesn't scan table primary indexes.
     */
    public void scanAllIndexes(ScanVisitor visitor) throws IOException {
        visitor.apply(mSchemata);

        try (Cursor c = mSchemata.newCursor(null)) {
            byte[] key;
            for (c.first(); (key = c.key()) != null; ) {
                if (key.length <= 8) {
                    c.next();
                    continue;
                }
                long indexId = decodeLongBE(key, 0);
                if (indexId == 0 ||
                    decodeIntBE(key, 8) != 0 || decodeIntBE(key, 8 + 4) != K_SECONDARY)
                {
                    if (++indexId == 0) {
                        break;
                    }
                    c.findNearbyGe(key(indexId));
                } else {
                    Index secondaryIndex = mDatabase.indexById(decodeLongLE(c.value(), 0));
                    if (secondaryIndex != null) {
                        visitor.apply(secondaryIndex);
                    }
                    c.next();
                }
            }
        }
    }

    /**
     * Try to find a table which is bound to the given index, and return a partially decoded
     * row. Returns null if not found.
     */
    public Object toRow(Index ix, byte[] key) {
        var manager = (TableManager<?>) mTableManagers.get(ix);
        if (manager != null) {
            BaseTable<?> table = manager.mostRecentTable();
            if (table != null) {
                try {
                    return table.toRow(key);
                } catch (Throwable e) {
                    // Ignore any decoding errors.
                }
            }
        }
        return null;
    }

    private TableManager<?> tableManager(Index ix) {
        var manager = (TableManager<?>) mTableManagers.get(ix);

        if (manager == null) {
            synchronized (mTableManagers) {
                manager = mTableManagers.get(ix);
                if (manager == null) {
                    manager = new TableManager<>(this, ix);
                    mTableManagers.put(ix, manager);
                }
            }
        }

        return manager;
    }

    <R> RowPredicateLock<R> indexLock(Index index) {
        return indexLock(index.id());
    }

    @SuppressWarnings("unchecked")
    <R> RowPredicateLock<R> indexLock(long indexId) {
        var lock = mIndexLocks.getValue(indexId);
        if (lock == null) {
            lock = makeIndexLock(indexId);
        }
        return (RowPredicateLock<R>) lock;
    }

    private RowPredicateLock<?> makeIndexLock(long indexId) {
        synchronized (mIndexLocks) {
            var lock = mIndexLocks.getValue(indexId);
            if (lock == null) {
                lock = mDatabase.newRowPredicateLock(indexId);
                VarHandle.storeStoreFence();
                mIndexLocks.insert(indexId).value = lock;
            }
            return lock;
        }
    }

    private void removeIndexLock(long indexId) {
        synchronized (mIndexLocks) {
            mIndexLocks.remove(indexId);
        }
    }

    public <R> Table<R> asTable(Index ix, Class<R> type) throws IOException {
        return openTable(ix, type);
    }

    @SuppressWarnings("unchecked")
    private <R> BaseTable<R> openTable(Index ix, Class<R> type) throws IOException {
        return ((TableManager<R>) tableManager(ix)).asTable(this, ix, type);
    }

    /**
     * @throws DeletedIndexException if not found
     */
    <R> BaseTable<R> findTable(long indexId, Class<R> type) throws IOException {
        Index ix = mDatabase.indexById(indexId);
        if (ix == null) {
            throw new DeletedIndexException();
        }
        return openTable(ix, type);
    }

    /**
     * Called by TableManager via asTable.
     *
     * @param doubleCheck invoked with schema lock held, to double check before making the table
     * @param consumer called with lock held, to accept the newly made table
     */
    @SuppressWarnings("unchecked")
    <R> BaseTable<R> makeTable(TableManager<R> manager, Index ix, Class<R> type,
                               Supplier<BaseTable<R>> doubleCheck,
                               Consumer<BaseTable<R>> consumer)
        throws IOException
    {
        // Throws an exception if type is malformed.
        RowInfo info = RowInfo.find(type);

        if (info.keyColumns.isEmpty()) {
            throw new IllegalArgumentException("No primary key is defined: " + type.getName());
        }

        boolean evolvable = type != Entry.class;

        BaseTable<R> table;

        // Can use NO_FLUSH because transaction will be only used for reading data.
        Transaction txn = mSchemata.newTransaction(DurabilityMode.NO_FLUSH);
        try {
            txn.lockMode(LockMode.REPEATABLE_READ);

            // Acquire the lock, but don't check for schema changes just yet.
            RowInfo currentInfo = decodeExisting(txn, null, ix.id());

            if (doubleCheck != null && (table = doubleCheck.get()) != null) {
                // Found the table, so just use that.
                return table;
            }

            // With a txn lock held, check if the schema has changed incompatibly.
            if (currentInfo != null) {
                checkSchema(type.getName(), currentInfo, info);
            }

            RowPredicateLock<R> indexLock = indexLock(ix);

            try {
                if (evolvable) {
                    var mh = new DynamicTableMaker(type, info.rowGen(), this, ix.id()).finish();
                    table = (BaseTable) mh.invoke(manager, ix, indexLock);
                } else {
                    Class tableClass = StaticTableMaker.obtain(type, RowInfo.find(type).rowGen());
                    table = (BaseTable) tableClass.getConstructor
                        (TableManager.class, Index.class, RowPredicateLock.class)
                        .newInstance(manager, ix, indexLock);
                }
            } catch (Throwable e) {
                throw rethrow(e);
            }

            if (consumer != null) {
                consumer.accept(table);
            }
        } finally {
            txn.reset();
        }

        if (!evolvable) {
            // Not evolvable, so don't persist any metadata.
            return table;
        }

        // Attempt to eagerly update schema metadata and secondary indexes.
        try {
            // Pass false for notify because examineSecondaries will be invoked by caller.
            schemaVersion(info, true, ix.id(), false);
        } catch (IOException e) {
            // Ignore and try again when storing rows or when the leadership changes.
        }

        return table;
    }

    /**
     * Checks if the schema has changed incompatibly.
     *
     * @return true if schema is exactly the same; false if changed compatibly
     * @throws IllegalStateException if incompatible change is detected
     */
    private static boolean checkSchema(String typeName, RowInfo oldInfo, RowInfo newInfo) {
        if (oldInfo.matches(newInfo)
            && matches(oldInfo.alternateKeys, newInfo.alternateKeys)
            && matches(oldInfo.secondaryIndexes, newInfo.secondaryIndexes))
        {
            return true;
        }

        if (!oldInfo.keyColumns.equals(newInfo.keyColumns)) {
            throw new SchemaChangeException("Cannot alter primary key: " + typeName);
        }

        // Checks for alternate keys and secondary indexes is much more strict. Any change to
        // a column used by one is considered incompatible.

        checkIndexes(typeName, "alternate keys",
                     oldInfo.alternateKeys, newInfo.alternateKeys);

        checkIndexes(typeName, "secondary indexes",
                     oldInfo.secondaryIndexes, newInfo.secondaryIndexes);

        return false;
    }

    /**
     * Checks if the set of indexes has changed incompatibly.
     */
    private static void checkIndexes(String typeName, String which,
                                     NavigableSet<ColumnSet> oldSet,
                                     NavigableSet<ColumnSet> newSet)
    {
        // Quick check.
        if (oldSet.isEmpty() || newSet.isEmpty() || matches(oldSet, newSet)) {
            return;
        }

        // Create mappings keyed by index descriptor, which isn't the most efficient approach,
        // but it matches how secondaries are persisted. So it's safe and correct. The type
        // descriptor prefix is fake, but that's okay. This isn't actually persisted.

        var encoder = new Encoder(64);
        NavigableMap<byte[], ColumnSet> oldMap = indexMap('_', encoder, oldSet);
        NavigableMap<byte[], ColumnSet> newMap = indexMap('_', encoder, newSet);

        Iterator<Map.Entry<byte[], ColumnSet>> oldIt = oldMap.entrySet().iterator();
        Iterator<Map.Entry<byte[], ColumnSet>> newIt = newMap.entrySet().iterator();

        Map.Entry<byte[], ColumnSet> oldEntry = null, newEntry = null;

        while (true) {
            if (oldEntry == null) {
                if (!oldIt.hasNext()) {
                    break;
                }
                oldEntry = oldIt.next();
            }
            if (newEntry == null) {
                if (!newIt.hasNext()) {
                    break;
                }
                newEntry = newIt.next();
            }

            int cmp = Arrays.compareUnsigned(oldEntry.getKey(), newEntry.getKey());

            if (cmp == 0) {
                // Same descriptor, so check if the index has changed.
                if (!oldEntry.getValue().matches(newEntry.getValue())) {
                    throw new SchemaChangeException("Cannot alter " + which + ": " + typeName);
                }
                oldEntry = null;
                newEntry = null;
            } else if (cmp < 0) {
                // This index will be dropped, so not an incompatibility.
                oldEntry = null;
            } else {
                // This index will be added, so not an incompatibility.
                newEntry = null;
            }
        }
    }

    private static boolean matches(NavigableSet<ColumnSet> a, NavigableSet<ColumnSet> b) {
        var ia = a.iterator();
        var ib = b.iterator();
        while (ia.hasNext()) {
            if (!ib.hasNext() || !ia.next().matches(ib.next())) {
                return false;
            }
        }
        return !ib.hasNext();
    }

    private static NavigableMap<byte[], ColumnSet> indexMap(char type, Encoder encoder,
                                                            NavigableSet<ColumnSet> set)
    {
        var map = new TreeMap<byte[], ColumnSet>(KEY_COMPARATOR);
        for (ColumnSet cs : set) {
            map.put(EncodedRowInfo.encodeDescriptor(type, encoder, cs), cs);
        }
        return map;
    }

    /**
     * @throws NoSuchIndexException if not found or isn't available
     */
    public <R> BaseTableIndex<R> indexTable
        (BaseTable<R> primaryTable, boolean alt, String... columns)
        throws IOException
    {
        Object key = ArrayKey.make(alt, columns);
        WeakCache<Object, BaseTableIndex<R>, Object> indexTables =
            primaryTable.mTableManager.indexTables();

        BaseTableIndex<R> table = indexTables.get(key);

        if (table == null) {
            synchronized (indexTables) {
                table = indexTables.get(key);
                if (table == null) {
                    table = makeIndexTable(indexTables, primaryTable, alt, columns);
                    if (table == null) {
                        throw new NoSuchIndexException
                            ((alt ? "Alternate key" : "Secondary index") + " not found: " +
                             Arrays.toString(columns));
                    }
                    indexTables.put(key, table);
                }
            }
        }

        return table;
    }

    /**
     * @param indexTables check and store in this cache, which is synchronized by the caller
     * @return null if not found
     */
    @SuppressWarnings("unchecked")
    private <R> BaseTableIndex<R> makeIndexTable
        (WeakCache<Object, BaseTableIndex<R>, Object> indexTables,
         BaseTable<R> primaryTable,
         boolean alt, String... columns)
        throws IOException
    {
        Class<R> rowType = primaryTable.rowType();
        RowInfo rowInfo = RowInfo.find(rowType);
        ColumnSet cs = rowInfo.examineIndex(null, columns, alt);

        if (cs == null) {
            return null;
        }

        var encoder = new Encoder(columns.length * 16);
        char type = alt ? 'A' : 'I';
        byte[] search = EncodedRowInfo.encodeDescriptor(type, encoder, cs);

        View secondariesView = viewExtended(primaryTable.mSource.id(), K_SECONDARY);
        secondariesView = secondariesView.viewPrefix(new byte[] {(byte) type}, 0);

        // Identify the first match. Ascending order is encoded with bit 7 clear (see
        // ColumnInfo), and so matches for ascending order are generally found first when an
        // unspecified order was given.
        long indexId;
        RowInfo indexRowInfo;
        byte[] descriptor;
        find: {
            try (Cursor c = secondariesView.newCursor(null)) {
                for (c.first(); c.key() != null; c.next()) {
                    if (!descriptorMatches(search, c.key())) {
                        continue;
                    }
                    if (c.value()[8] != 'A') {
                        // Not active.
                        continue;
                    }
                    indexId = decodeLongLE(c.value(), 0);
                    indexRowInfo = secondaryRowInfo(RowInfo.find(rowType), c.key());
                    descriptor = c.key();
                    break find;
                }
            }
            return null;
        }

        Object key = ArrayKey.make(descriptor);

        BaseTableIndex<R> table = indexTables.get(key);

        if (table != null) {
            return table;
        }

        Index ix = mDatabase.indexById(indexId);

        if (ix == null) {
            return null;
        }

        // Indexes don't have indexes.
        indexRowInfo.alternateKeys = Collections.emptyNavigableSet();
        indexRowInfo.secondaryIndexes = Collections.emptyNavigableSet();

        RowPredicateLock<R> indexLock = indexLock(ix);

        try {
            var maker = new DynamicTableMaker
                (rowType, rowInfo.rowGen(), indexRowInfo.rowGen(), descriptor,
                 this, primaryTable.mSource.id());
            var mh = maker.finish();
            var unjoined = (BaseTable<R>) mh.invoke(primaryTable.mTableManager, ix, indexLock);

            var maker2 = new JoinedTableMaker
                (rowType, rowInfo.rowGen(), indexRowInfo.rowGen(), descriptor,
                 primaryTable.getClass(), unjoined.getClass());
            mh = maker2.finish();
            table = (BaseTableIndex<R>) mh.invoke(ix, indexLock, primaryTable, unjoined);
        } catch (Throwable e) {
            throw rethrow(e);
        }

        indexTables.put(key, table);

        return table;
    }

    /**
     * Compares descriptors as encoded by EncodedRowInfo.encodeDescriptor. Column types are
     * ignored except for comparing key column ordering.
     *
     * @param search typeCode can be -1 for unspecified orderings
     * @param found should not have any unspecified orderings
     */
    private static boolean descriptorMatches(byte[] search, byte[] found) {
        if (search.length != found.length) {
            return false;
        }

        int offset = 1; // skip the type prefix

        for (int part=1; part<=2; part++) { // part 1 is keys section, part 2 is values section
            int numColumns = decodePrefixPF(search, offset);
            if (numColumns != decodePrefixPF(found, offset)) {
                return false;
            }
            offset += lengthPrefixPF(numColumns);

            for (int i=0; i<numColumns; i++) {
                if (part == 1) {
                    // Only examine the key column ordering.
                    int searchTypeCode = decodeIntBE(search, offset) ^ (1 << 31);
                    if (searchTypeCode != -1) { // is -1 when unspecified
                        int foundTypeCode = decodeIntBE(found, offset) ^ (1 << 31);
                        if ((searchTypeCode & ColumnInfo.TYPE_DESCENDING) != 
                            (foundTypeCode & ColumnInfo.TYPE_DESCENDING))
                        {
                            // Ordering doesn't match.
                            return false;
                        }
                    }
                } else {
                    // Ignore value column type codes.
                }

                offset += 4;

                // Now compare the column name.

                int nameLength = decodePrefixPF(search, offset);
                if (nameLength != decodePrefixPF(found, offset)) {
                    return false;
                }

                offset += lengthPrefixPF(nameLength);

                if (!Arrays.equals(search, offset, offset + nameLength,
                                   found, offset, offset + nameLength))
                {
                    return false;
                }

                offset += nameLength;
            }
        }

        return offset == search.length; // should always be true unless descriptor is malformed
    }

    private void registerToUpdateSchemata() {
        // The second task is invoked when leadership is lost, which sets things up for when
        // leadership is acquired again.
        mDatabase.uponLeader(this::updateSchemata, this::registerToUpdateSchemata);
    }

    /**
     * Called when database has become the leader, providing an opportunity to update the
     * schema for all tables currently in use.
     */
    private void updateSchemata() {
        List<TableManager<?>> managers = mTableManagers.copyValues();

        if (managers != null) {
            try {
                for (var manager : managers) {
                    BaseTable<?> table = manager.mostRecentTable();
                    if (table != null) {
                        RowInfo info = RowInfo.find(table.rowType());
                        long indexId = manager.mPrimaryIndex.id();
                        schemaVersion(info, true, indexId, true); // notify = true
                    }
                }
            } catch (IOException e) {
                // Ignore and try again when storing rows or when the leadership changes.
            } catch (Throwable e) {
                uncaught(e);
            }
        }
    }

    private void uncaught(Throwable e) {
        if (!mDatabase.isClosed()) {
            RowUtils.uncaught(e);
        }
    }

    /**
     * Called in response to a redo log message. Implementation should examine the set of
     * secondary indexes associated with the table and perform actions to build or drop them.
     * When called by ReplEngine, all incoming redo message processing is suspended until this
     * method returns.
     */
    public void notifySchema(long indexId) throws IOException {
        try {
            byte[] taskKey = newTaskKey(TASK_NOTIFY_SCHEMA, indexId);
            Transaction txn = beginWorkflowTask(taskKey);

            // Test mode only.
            if (mStallTasks) {
                txn.reset();
                return;
            }

            // TODO: Should the call to TableManager.update wait for scans to complete if an
            // index is to be deleted? It's safe, but it's not an ideal solution.

            try {
                doNotifySchema(null, indexId);
                mSchemata.delete(txn, taskKey);
            } finally {
                txn.reset();
            }
        } catch (Throwable e) {
            uncaught(e);
        }
    }

    /**
     * @param taskKey is non-null if this is a recovered workflow task
     * @return true if workflow task (if any) can be immediately deleted
     */
    private boolean doNotifySchema(byte[] taskKey, long indexId) throws IOException {
        Index ix = mDatabase.indexById(indexId);

        // Index won't be found if it was concurrently dropped.
        if (ix != null) {
            examineSecondaries(tableManager(ix));
        }

        return true;
    }

    /**
     * Also called from TableManager.asTable.
     */
    void examineSecondaries(TableManager<?> manager) throws IOException {
        long indexId = manager.mPrimaryIndex.id();

        // Can use NO_FLUSH because transaction will be only used for reading data.
        Transaction txn = mDatabase.newTransaction(DurabilityMode.NO_FLUSH);
        try {
            txn.lockTimeout(-1, null);

            // Obtaining the current table version acquires an upgradable lock, which
            // prevents changes and allows only one thread to call TableManager.update.
            long tableVersion;
            {
                byte[] value = mSchemata.load(txn, key(indexId));
                if (value == null) {
                    return;
                }
                tableVersion = decodeLongLE(value, 4);
            }

            txn.lockMode(LockMode.READ_COMMITTED);

            manager.update(tableVersion, this, txn, viewExtended(indexId, K_SECONDARY));
        } finally {
            txn.reset();
        }
    }

    /**
     * Called in response to a redo log message.
     *
     * @return null or a Lock, or an array of Locks
     * @see RowPredicateLock#acquireLocksNoPush
     */
    public Object acquireLocksNoPush(Transaction txn, long indexId, byte[] key, byte[] value)
        throws LockFailureException
    {
        return indexLock(indexId).acquireLocksNoPush(txn, key, value);
    }

    /**
     * Called when an index is deleted. If the indexId refers to a known secondary index, then
     * it gets deleted by the returned Runnable. Otherwise, null is returned and the caller
     * should perform the delete.
     *
     * @param taskFactory returns a task which performs the actual delete
     */
    public Runnable redoDeleteIndex(long indexId, Supplier<Runnable> taskFactory)
        throws IOException
    {
        byte[] value = viewExtended(indexId, K_DROPPED).load(Transaction.BOGUS, EMPTY_BYTES);

        if (value == null) {
            return null;
        }

        long primaryIndexId = decodeLongLE(value, 0);
        byte[] descriptor = Arrays.copyOfRange(value, 8, value.length);

        return deleteIndex(primaryIndexId, indexId, descriptor, null, taskFactory);
    }

    /**
     * Called by IndexBackfill when an index backfill has finished.
     */
    void activateSecondaryIndex(IndexBackfill backfill, boolean success) throws IOException {
        long indexId = backfill.mManager.mPrimaryIndex.id();
        long secondaryId = backfill.mSecondaryIndex.id();

        boolean activated = false;

        // Use NO_REDO it because this method can be called by a replica.
        Transaction txn = mDatabase.newTransaction(DurabilityMode.NO_REDO);
        try {
            txn.lockTimeout(-1, null);

            // Changing the current table version acquires an exclusive lock, which prevents
            // other changes and allows only one thread to call TableManager.update.
            long tableVersion;
            try (Cursor c = mSchemata.newCursor(txn)) {
                c.find(key(indexId));
                byte[] value = c.value();
                if (value == null) {
                    return;
                }
                tableVersion = decodeLongLE(value, 4);
                encodeLongLE(value, 4, ++tableVersion);
                c.store(value);
            }

            View secondariesView = viewExtended(indexId, K_SECONDARY);

            if (success) {
                try (Cursor c = secondariesView.newCursor(txn)) {
                    c.find(backfill.mSecondaryDescriptor);
                    byte[] value = c.value();
                    if (value != null && value[8] == 'B' && decodeLongLE(value, 0) == secondaryId) {
                        // Switch to "active" state.
                        value[8] = 'A';
                        c.store(value);
                        activated = true;
                    }
                }
            }

            backfill.mManager.update(tableVersion, this, txn, secondariesView);

            txn.commit();
        } finally {
            txn.reset();
        }

        if (activated) {
            // With NO_REDO, need a checkpoint to ensure durability. If the database is closed
            // before it finishes, the whole backfill process starts over when the database is
            // later reopened.
            mDatabase.checkpoint();
        }
    }

    /**
     * Should be called when the index is being dropped. Does nothing if index wasn't used for
     * storing rows.
     *
     * This method should be called with the shared commit lock held, and it
     * non-transactionally stores task metadata which indicates that the schema should be
     * deleted. The caller should run the returned object without holding the commit lock.
     *
     * If no checkpoint occurs, then the expectation is that the deleteSchema method is called
     * again, which allows the deletion to run again. This means that deleteSchema should
     * only really be called from removeFromTrash, which automatically runs again if no
     * checkpoint occurs.
     *
     * @param indexKey long index id, big-endian encoded
     * @return an optional task to run without commit lock held
     */
    public Runnable deleteSchema(byte[] indexKey) throws IOException {
        try (Cursor c = mSchemata.viewPrefix(indexKey, 0).newCursor(Transaction.BOGUS)) {
            c.autoload(false);
            c.first();
            if (c.key() == null) {
                return null;
            }
        }

        byte[] taskKey = newTaskKey(TASK_DELETE_SCHEMA, indexKey);
        Transaction txn = beginWorkflowTask(taskKey);

        // Test mode only.
        if (mStallTasks) {
            txn.reset();
            return null;
        }

        return () -> {
            try {
                try {
                    doDeleteSchema(null, null, indexKey);
                    mSchemata.delete(txn, taskKey);
                } finally {
                    txn.reset();
                }
            } catch (IOException e) {
                throw rethrow(e);
            }
        };
    }

    /**
     * @param txn if non-null, pass to mSchemata.delete when false is returned
     * @param taskKey is non-null if this is a recovered workflow task
     * @param indexKey long index id, big-endian encoded
     * @return true if workflow task (if any) can be immediately deleted
     */
    private boolean doDeleteSchema(Transaction txn, byte[] taskKey, byte[] indexKey)
        throws IOException
    {
        long primaryIndexId = decodeLongBE(indexKey, 0);

        removeIndexLock(primaryIndexId);

        List<Runnable> deleteTasks = null;

        try (Cursor c = mSchemata.viewPrefix(indexKey, 0).newCursor(Transaction.BOGUS)) {
            c.autoload(false);
            byte[] key;
            for (c.first(); (key = c.key()) != null; c.next()) {
                if (key.length >= (8 + 4 + 4)
                    && decodeIntBE(key, 8) == 0 && decodeIntBE(key, 8 + 4) == K_SECONDARY)
                {
                    // Delete a secondary index.
                    c.load(); // autoload is false, so load the value now
                    byte[] value = c.value();
                    if (value != null && value.length >= 8) {
                        Index secondaryIndex = mDatabase.indexById(decodeLongLE(c.value(), 0));
                        if (secondaryIndex != null) {
                            byte[] descriptor = Arrays.copyOfRange(key, 8 + 4 + 4, key.length);
                            Runnable task = deleteIndex
                                (primaryIndexId, 0, descriptor, secondaryIndex, null);
                            if (deleteTasks == null) {
                                deleteTasks = new ArrayList<>();
                            }
                            deleteTasks.add(task);
                        }
                    }
                }

                c.delete();
            }
        }

        if (deleteTasks == null) {
            return true;
        }

        final var tasks = deleteTasks;

        Runner.start(() -> {
            try {
                try {
                    for (Runnable task : tasks) {
                        task.run();
                    }
                    if (taskKey != null) {
                        mSchemata.delete(txn, taskKey);
                    }
                    if (txn != null) {
                        txn.commit();
                    }
                } finally {
                    if (txn != null) {
                        txn.reset();
                    }
                }
            } catch (Throwable e) {
                uncaught(e);
            }
        });

        return false;
    }

    /**
     * @param indexKey long index id, big-endian encoded
     */
    private byte[] newTaskKey(int taskType, byte[] indexKey) {
        var taskKey = new byte[8 + 8 + 4];
        System.arraycopy(indexKey, 0, taskKey, 8, 8);
        encodeIntBE(taskKey, 8 + 8, taskType);
        return taskKey;
    }

    private byte[] newTaskKey(int taskType, long indexKey) {
        var taskKey = new byte[8 + 8 + 4];
        encodeLongBE(taskKey, 8, indexKey);
        encodeIntBE(taskKey, 8 + 8, taskType);
        return taskKey;
    }

    /**
     * @return a transaction to reset when task is done
     */
    private Transaction beginWorkflowTask(byte[] taskKey) throws IOException {
        // Use a transaction to lock the task, and so finishAllWorkflowTasks will skip it.
        Transaction txn = mDatabase.newTransaction(DurabilityMode.NO_REDO);
        try {
            mSchemata.lockUpgradable(txn, taskKey);
            mSchemata.store(Transaction.BOGUS, taskKey, EMPTY_BYTES);
        } catch (Throwable e) {
            txn.reset(e);
            throw e;
        }
        return txn;
    }

    // Is package-private to be accessible for testing.
    void finishAllWorkflowTasks() throws IOException {
        // Use transaction locks to identity tasks which should be skipped.
        Transaction txn = mDatabase.newTransaction(DurabilityMode.NO_REDO);
        try {
            txn.lockMode(LockMode.UNSAFE); // don't auto acquire locks; is auto-commit

            var prefix = new byte[8]; // 0L is the prefix for all workflow tasks

            try (Cursor c = mSchemata.viewPrefix(prefix, 0).newCursor(txn)) {
                c.autoload(false);

                for (c.first(); c.key() != null; c.next()) {
                    if (!txn.tryLockUpgradable(mSchemata.id(), c.key(), 0).isHeld()) {
                        // Skip it.
                        continue;
                    }

                    // Load and check the value again, in case another thread deleted it
                    // before the lock was acquired.
                    c.load();
                    byte[] taskValue = c.value();

                    if (taskValue != null) {
                        if (runRecoveredWorkflowTask(txn, c.key(), taskValue)) {
                            c.delete();
                        } else {
                            // Need a replacement transaction.
                            txn = mDatabase.newTransaction(DurabilityMode.NO_REDO);
                            txn.lockMode(LockMode.UNSAFE);
                            c.link(txn);
                            continue;
                        }
                    }

                    txn.unlock();
                }
            }
        } finally {
            txn.reset();
        }
    }

    /**
     * @param txn must pass to mSchemata.delete when false is returned
     * @param taskKey (0L, indexId, taskType)
     * @return true if task can be immediately deleted
     */
    private boolean runRecoveredWorkflowTask(Transaction txn, byte[] taskKey, byte[] taskValue)
        throws IOException
    {
        var indexKey = new byte[8];
        System.arraycopy(taskKey, 8, indexKey, 0, 8);
        int taskType = decodeIntBE(taskKey, 8 + 8);

        return switch (taskType) {
        default -> throw new CorruptDatabaseException("Unknown task: " + taskType);
        case TASK_DELETE_SCHEMA -> doDeleteSchema(txn, taskKey, indexKey);
        case TASK_NOTIFY_SCHEMA -> doNotifySchema(taskKey, decodeLongBE(indexKey, 0));
        };
    }

    /**
     * @param secondaryIndexId ignored when secondaryIndex is provided
     * @param secondaryIndex required when no taskFactory is provided
     * @param taskFactory can be null when a secondaryIndex is provided
     */
    private Runnable deleteIndex(long primaryIndexId, long secondaryIndexId, byte[] descriptor,
                                 Index secondaryIndex, Supplier<Runnable> taskFactory)
        throws IOException
    {
        if (secondaryIndex != null) {
            secondaryIndexId = secondaryIndex.id();
        }

        // Remove it from the cache.
        Index primaryIndex = mDatabase.indexById(primaryIndexId);
        if (primaryIndex != null) {
            tableManager(primaryIndex).removeFromIndexTables(secondaryIndexId);
        }

        EventListener listener = mDatabase.eventListener();

        String eventStr;
        if (listener == null) {
            eventStr = null;
        } else {
            SecondaryInfo secondaryInfo = null;
            try {
                RowInfo primaryInfo = decodeExisting(null, null, primaryIndexId);
                secondaryInfo = secondaryRowInfo(primaryInfo, descriptor);
            } catch (Exception e) {
            }

            if (secondaryInfo == null) {
                eventStr = String.valueOf(secondaryIndexId);
            } else {
                eventStr = secondaryInfo.eventString();
            }
        }

        RowPredicateLock<?> lock = indexLock(secondaryIndexId);

        if (lock == null) {
            if (listener != null) {
                listener.notify(EventType.TABLE_INDEX_INFO, "Dropping %1$s", eventStr);
            }

            Runnable task;
            if (taskFactory == null) {
                task = mDatabase.deleteIndex(secondaryIndex);
            } else {
                task = taskFactory.get();
            }

            if (listener == null) {
                return task;
            }

            return () -> {
                task.run();
                listener.notify(EventType.TABLE_INDEX_INFO, "Finished dropping %1$s", eventStr);
            };
        }

        final long fSecondaryIndexId = secondaryIndexId;

        return () -> {
            try {
                Transaction txn = mDatabase.newTransaction();

                try {
                    // Acquire the predicate lock to wait for all scans to complete, and to
                    // prevent new ones from starting.

                    txn.lockTimeout(-1, null);

                    Runnable mustWait = null;

                    if (listener != null) {
                        mustWait = () -> listener.notify
                            (EventType.TABLE_INDEX_INFO, "Waiting to drop %1$s", eventStr);
                    }

                    lock.withExclusiveNoRedo(txn, mustWait, () -> {
                        try {
                            if (listener != null) {
                                listener.notify(EventType.TABLE_INDEX_INFO,
                                                "Dropping %1$s", eventStr);
                            }

                            Runnable task;
                            if (taskFactory == null) {
                                task = mDatabase.deleteIndex(secondaryIndex);
                            } else {
                                task = taskFactory.get();
                            }

                            task.run();

                            if (listener != null) {
                                listener.notify(EventType.TABLE_INDEX_INFO,
                                                "Finished dropping %1$s", eventStr);
                            }
                        } catch (Throwable e) {
                            uncaught(e);
                        }
                    });

                    txn.commit();
                } finally {
                    txn.exit();
                }

                removeIndexLock(fSecondaryIndexId);
            } catch (Throwable e) {
                uncaught(e);
            }
        };
    }

    /**
     * Returns the schema version for the given row info, creating a new version if necessary.
     *
     * @param mostRecent true if the given info is known to be the most recent definition
     * @param notify true to call notifySchema if anything changed
     */
    int schemaVersion(RowInfo info, boolean mostRecent, long indexId, boolean notify)
        throws IOException
    {
        if (mDatabase.indexById(indexId) == null) {
            throw new DeletedIndexException();
        }

        int schemaVersion;
        long tableVersion;

        Map<Index, byte[]> secondariesToDelete = null;

        Transaction txn = mSchemata.newTransaction(DurabilityMode.SYNC);
        doSchemaVersion: try (Cursor current = mSchemata.newCursor(txn)) {
            txn.lockTimeout(-1, null);

            current.find(key(indexId));

            if (current.value() == null) {
                tableVersion = 0;
            } else {
                // Check if the schema has changed.
                schemaVersion = decodeIntLE(current.value(), 0);

                // FIXME: Sometimes currentInfo is null, which causes NPE in checkSchema.
                RowInfo currentInfo = decodeExisting
                    (txn, info.name, indexId, current.value(), schemaVersion);

                if (checkSchema(info.name, currentInfo, info)) {
                    // Exactly the same, so don't create a new version.
                    return schemaVersion;
                }

                if (!mostRecent) {
                    RowInfo info2 = info.withIndexes(currentInfo);
                    if (info2 != info) {
                        // Don't change the indexes.
                        info = info2;
                        if (checkSchema(info.name, currentInfo, info)) {
                            // Don't create a new version.
                            return schemaVersion;
                        }
                    }
                }

                tableVersion = decodeLongLE(current.value(), 4);
            }

            // Find an existing schemaVersion or create a new one.

            final boolean isTempIndex = mDatabase.isInTrash(txn, indexId);

            if (isTempIndex) {
                // Temporary trees are always in the trash, and they don't replicate. For this
                // reason, don't attempt to replicate schema metadata either.
                txn.durabilityMode(DurabilityMode.NO_REDO);
            }

            final var encoded = new EncodedRowInfo(info);

            assignVersion: try (Cursor byHash = mSchemata.newCursor(txn)) {
                byHash.find(key(indexId, encoded.primaryHash | (1 << 31)));

                byte[] schemaVersions = byHash.value();
                if (schemaVersions != null) {
                    for (int pos=0; pos<schemaVersions.length; pos+=4) {
                        schemaVersion = decodeIntLE(schemaVersions, pos);
                        RowInfo existing = decodeExisting
                            (txn, info.name, indexId, null, schemaVersion);
                        if (info.matches(existing)) {
                            break assignVersion;
                        }
                    }
                }

                // Create a new schema version.

                View versionView = mSchemata.viewGt(key(indexId)).viewLt(key(indexId, 1 << 31));
                try (Cursor highest = versionView.newCursor(txn)) {
                    highest.autoload(false);
                    highest.last();

                    if (highest.value() == null) {
                        // First version.
                        schemaVersion = 1;
                    } else {
                        byte[] key = highest.key();
                        schemaVersion = decodeIntBE(key, key.length - 4) + 1;
                    }

                    highest.findNearby(key(indexId, schemaVersion));
                    highest.store(encoded.primaryData);
                }

                if (schemaVersions == null) {
                    schemaVersions = new byte[4];
                } else {
                    schemaVersions = Arrays.copyOfRange
                        (schemaVersions, 0, schemaVersions.length + 4);
                }

                encodeIntLE(schemaVersions, schemaVersions.length - 4, schemaVersion);
                byHash.store(schemaVersions);
            }

            encodeIntLE(encoded.currentData, 0, schemaVersion);
            encodeLongLE(encoded.currentData, 4, ++tableVersion);

            current.store(encoded.currentData);

            // Store the type name, which is usually the same as the class name. In case the
            // table isn't currently open, this name can be used for reporting background
            // status updates. Although the index name could be used, it's not required to be
            // the same as the class name.
            View nameView = viewExtended(indexId, K_TYPE_NAME);
            try (Cursor c = nameView.newCursor(txn)) {
                c.find(EMPTY_BYTES);
                byte[] nameBytes = encodeStringUTF(info.name);
                if (!Arrays.equals(nameBytes, c.value())) {
                    c.store(nameBytes);
                }
            }

            if (!mostRecent) {
                // Cannot delete or create indexes.
                txn.commit();
                break doSchemaVersion;
            }

            // The rest of the code in this block does index deletion and creation stuff.

            // Start with the full set of secondary descriptors and later prune it down to
            // those that need to be created.
            TreeSet<byte[]> secondaries = encoded.secondaries;

            // Access a view of persisted secondary descriptors to index ids and states.
            View secondariesView = viewExtended(indexId, K_SECONDARY);

            // Find and update secondary indexes that should be deleted.
            TableManager<?> manager = null;
            try (Cursor c = secondariesView.newCursor(txn)) {
                byte[] key;
                for (c.first(); (key = c.key()) != null; c.next()) {
                    byte[] value = c.value();
                    Index secondaryIndex = mDatabase.indexById(decodeLongLE(value, 0));
                    if (secondaryIndex == null) {
                        c.store(null); // already deleted, so remove the entry
                    } else if (!secondaries.contains(key)) {
                        if (value[8] != 'D') {
                            // TODO: The 'D' state can linger after the secondary has been
                            // deleted. It eventually gets deleted when the index set changes.
                            value[8] = 'D'; // "deleting" state
                            c.store(value);
                        }

                        // Encode primaryIndexId and secondary descriptor. This entry is only
                        // required with replication, allowing it to quickly identify a deleted
                        // index as being a secondary index. This entry is deleted when
                        // doDeleteSchema is called.
                        var droppedEntry = new byte[8 + key.length];
                        encodeLongLE(droppedEntry, 0, indexId);
                        System.arraycopy(key, 0, droppedEntry, 8, key.length);
                        View droppedView = viewExtended(secondaryIndex.id(), K_DROPPED);
                        droppedView.store(txn, EMPTY_BYTES, droppedEntry);

                        if (manager == null) {
                            manager = tableManager(mDatabase.indexById(indexId));
                        }

                        // This removes entries from a cache, so not harmful if txn fails.
                        manager.removeFromIndexTables(secondaryIndex.id());

                        if (secondariesToDelete == null) {
                            secondariesToDelete = new LinkedHashMap<>();
                        }

                        secondariesToDelete.put(secondaryIndex, c.key());
                    }
                }
            }

            // Find and update secondary indexes to create.
            try (Cursor c = secondariesView.newCursor(txn)) {
                Iterator<byte[]> it = secondaries.iterator();
                while (it.hasNext()) {
                    c.findNearby(it.next());
                    byte[] value = c.value();
                    if (value != null) {
                        // Secondary index already exists.
                        it.remove();
                        // If state is "deleting", switch it to "backfill".
                        if (value[8] == 'D') {
                            value[8] = 'B';
                            c.store(value);
                        }
                    } else if (isTempIndex) {
                        // Secondary index doesn't exist, but temporary ones can be
                        // immediately created because they're not replicated.
                        it.remove();
                        value = new byte[8 + 1];
                        encodeLongLE(value, 0, mDatabase.newTemporaryIndex().id());
                        value[8] = 'B'; // "backfill" state
                        c.store(value);
                    }
                }
            }

            // The newly created index ids are copied into the array. Note that the array can
            // be empty, in which case calling createSecondaryIndexes is still necessary for
            // informing any replicas that potential changes have been made. The state of some
            // secondary indexes might have changed from "backfill" to "deleting", or vice
            // versa. If nothing changed, there's no harm in sending the notification anyhow.
            var ids = new long[secondaries.size()];

            // Transaction is committed as a side-effect.
            mDatabase.createSecondaryIndexes(txn, indexId, ids, () -> {
                try {
                    int i = 0;
                    for (byte[] desc : secondaries) {
                        byte[] value = new byte[8 + 1];
                        encodeLongLE(value, 0, ids[i++]);
                        value[8] = 'B'; // "backfill" state
                        if (!secondariesView.insert(txn, desc, value)) {
                            // Not expected.
                            throw new UniqueConstraintException();
                        }
                    }
                } catch (IOException e) {
                    rethrow(e);
                }
            });
        } finally {
            txn.reset();
        }

        if (secondariesToDelete != null) {
            for (var entry : secondariesToDelete.entrySet()) {
                try {
                    Index secondaryIndex = entry.getKey();
                    byte[] descriptor = entry.getValue();
                    Runner.start(deleteIndex(indexId, 0, descriptor, secondaryIndex, null));
                } catch (IOException e) {
                    // Assume leadership was lost.
                }
            }
        }

        if (notify) {
            // We're currently the leader, and so this method must be invoked directly.
            notifySchema(indexId);
        }

        return schemaVersion;
    }

    /**
     * Finds a RowInfo for a specific schemaVersion. If not the same as the current version,
     * the alternateKeys and secondaryIndexes will be null (not just empty sets).
     *
     * If the given schemaVersion is 0, then the returned RowInfo only consists a primary key
     * and no value columns.
     *
     * @param rowType can pass null if not available
     * @throws CorruptDatabaseException if not found
     */
    RowInfo rowInfo(Class<?> rowType, long indexId, int schemaVersion)
        throws IOException, CorruptDatabaseException
    {
        RowInfo info;

        if (schemaVersion == 0) {
            // No value columns to decode, and the primary key cannot change.
            if (rowType == null) {
                info = decodePrimaryKey(null, null, indexId);
            } else {
                RowInfo fullInfo = RowInfo.find(rowType);
                if (fullInfo.valueColumns.isEmpty()) {
                    return fullInfo;
                }
                info = new RowInfo(fullInfo.name);
                info.keyColumns = fullInfo.keyColumns;
                info.valueColumns = Collections.emptyNavigableMap();
                info.allColumns = new TreeMap<>(info.keyColumns);
                return info;
            }
        } else {
            // Can use NO_FLUSH because transaction will be only used for reading data.
            Transaction txn = mSchemata.newTransaction(DurabilityMode.NO_FLUSH);
            txn.lockMode(LockMode.REPEATABLE_READ);
            try (Cursor c = mSchemata.newCursor(txn)) {
                // Check if the indexId matches and the schemaVersion is the current one.
                c.autoload(false);
                c.find(key(indexId));
                RowInfo current = null;
                if (c.value() != null && rowType != null) {
                    var buf = new byte[4];
                    if (decodeIntLE(buf, 0) == schemaVersion) {
                        // Matches, but don't simply return it. The current one might not have
                        // been updated yet.
                        current = RowInfo.find(rowType);
                    }
                }

                c.autoload(true);
                c.findNearby(key(indexId, schemaVersion));

                String typeName = rowType == null ? null : rowType.getName();
                info = decodeExisting(typeName, null, c.value());

                if (info != null && current != null && current.allColumns.equals(info.allColumns)) {
                    // Current one matches, so use the canonical RowInfo instance.
                    return current;
                }
            } finally {
                txn.reset();
            }
        }

        if (info == null) {
            throw new CorruptDatabaseException
                ("Schema version not found: " + schemaVersion + ", indexId=" + indexId);
        }

        return info;
    }

    static byte[] secondaryDescriptor(SecondaryInfo info) {
        return secondaryDescriptor(info, info.isAltKey());
    }

    static byte[] secondaryDescriptor(ColumnSet cs, boolean isAltKey) {
        var encoder = new Encoder(cs.allColumns.size() * 16); // with initial capacity guess
        return EncodedRowInfo.encodeDescriptor(isAltKey ? 'A' : 'I', encoder, cs);
    }

    /**
     * Decodes a RowInfo object for a secondary index, by parsing a binary descriptor which was
     * created by EncodedRowInfo.encodeDescriptor or secondaryDescriptor.
     */
    static SecondaryInfo secondaryRowInfo(RowInfo primaryInfo, byte[] desc) {
        byte type = desc[0];
        int offset = 1;

        var info = new SecondaryInfo(primaryInfo, type == 'A');

        int numKeys = decodePrefixPF(desc, offset);
        offset += lengthPrefixPF(numKeys);
        info.keyColumns = new LinkedHashMap<>(numKeys);

        for (int i=0; i<numKeys; i++) {
            offset = decodeIndexColumn(primaryInfo, desc, offset, info.keyColumns);
        }

        int numValues = decodePrefixPF(desc, offset);
        if (numValues == 0) {
            info.valueColumns = Collections.emptyNavigableMap();
        } else {
            offset += lengthPrefixPF(numValues);
            info.valueColumns = new TreeMap<>();
            do {
                offset = decodeIndexColumn(primaryInfo, desc, offset, info.valueColumns);
            } while (--numValues > 0);
        }

        info.allColumns = new TreeMap<>(info.keyColumns);
        info.allColumns.putAll(info.valueColumns);

        return info;
    }

    /**
     * @param columns decoded column goes here
     * @return updated offset
     */
    private static int decodeIndexColumn(RowInfo primaryInfo, byte[] desc, int offset,
                                         Map<String, ColumnInfo> columns)
    {
        int typeCode = decodeIntBE(desc, offset) ^ (1 << 31); offset += 4;
        int nameLength = decodePrefixPF(desc, offset);
        offset += lengthPrefixPF(nameLength);
        String name = decodeStringUTF(desc, offset, nameLength);
        offset += nameLength;

        ColumnInfo column = primaryInfo.allColumns.get(name);

        makeColumn: {
            if (column == null) {
                name = name.intern();
                column = new ColumnInfo();
                column.name = name;
            } else if (column.typeCode != typeCode) {
                column = column.copy();
            } else {
                break makeColumn;
            }
            column.typeCode = typeCode;
            column.assignType();
        }

        columns.put(column.name, column);

        return offset;
    }

    /**
     * Decodes a set of secondary index RowInfo objects.
     */
    static SecondaryInfo[] secondaryRowInfos(RowInfo primaryInfo, byte[][] descriptors) {
        var infos = new SecondaryInfo[descriptors.length];
        for (int i=0; i<descriptors.length; i++) {
            infos[i] = secondaryRowInfo(primaryInfo, descriptors[i]);
        }
        return infos;
    }

    /**
     * Returns a primary RowInfo or a secondary RowInfo, for the current schema version. Only
     * key columns are stable across schema versions.
     *
     * @param indexId can be the primaryIndexId or a secondary index id
     * @return RowInfo or SecondaryInfo, or null if not found
     */
    RowInfo currentRowInfo(Class<?> rowType, long primaryIndexId, long indexId) throws IOException {
        RowInfo primaryInfo = RowInfo.find(rowType);

        if (primaryIndexId == indexId) {
            return primaryInfo;
        }

        Index primaryIndex = mDatabase.indexById(primaryIndexId);

        if (primaryIndex == null) {
            return null;
        }

        TableManager<?> manager = tableManager(primaryIndex);

        // Scan the set of secondary indexes to find it. Not the most efficient approach, but
        // tables aren't expected to have tons of secondaries.

        View secondariesView = viewExtended(primaryIndexId, K_SECONDARY);

        try (Cursor c = secondariesView.newCursor(null)) {
            for (c.first(); c.key() != null; c.next()) {
                if (indexId == decodeLongLE(c.value(), 0)) {
                    return manager.secondaryInfo(primaryInfo, c.key());
                }
            }
        }

        return null;
    }

    /**
     * @param key K_SECONDARY, etc
     */
    private View viewExtended(long indexId, int key) {
        var prefix = new byte[8 + 4 + 4];
        encodeLongBE(prefix, 0, indexId);
        encodeIntBE(prefix, 8 + 4, key);
        return mSchemata.viewPrefix(prefix, prefix.length);
    }

    /**
     * Decode the known primary key, which can't change.
     *
     * @param typeName pass null to decode the current type name
     * @return null if not found
     */
    RowInfo decodePrimaryKey(Transaction txn, String typeName, long indexId) throws IOException {
        // Select the smallest primary ColumnSet, although any should work fine.
        byte[] primaryData = null;
        View allVersions = mSchemata.viewGe(key(indexId, 1)).viewLe(key(indexId, 0x7fff_ffff));
        try (Cursor c = allVersions.newCursor(txn)) {
            for (c.first(); c.key() != null; c.next()) {
                byte[] value = c.value();
                if (primaryData == null || value.length < primaryData.length) {
                    primaryData = value;
                }
            }
        }

        if (primaryData == null) {
            return null;
        }

        RowInfo info = decodeExisting(currentName(txn, indexId), null, primaryData);

        if (!info.valueColumns.isEmpty()) {
            info.valueColumns = Collections.emptyNavigableMap();
            info.allColumns = new TreeMap<>(info.keyColumns);
        }

        return info;
    }

    /**
     * Decode the existing RowInfo for the current schema version.
     *
     * @param typeName pass null to decode the current type name
     * @return null if not found
     */
    RowInfo decodeExisting(Transaction txn, String typeName, long indexId) throws IOException {
        byte[] currentData = mSchemata.load(txn, key(indexId));
        if (currentData == null) {
            return null;
        }
        return decodeExisting(txn, typeName, indexId, currentData, decodeIntLE(currentData, 0));
    }

    /**
     * Decode the existing RowInfo for the given schema version.
     *
     * @param typeName pass null to decode the current type name
     * @param currentData can be null if not the current schema
     * @return null if not found
     */
    private RowInfo decodeExisting(Transaction txn, String typeName, long indexId,
                                   byte[] currentData, int schemaVersion)
        throws IOException
    {
        byte[] primaryData = mSchemata.load(txn, key(indexId, schemaVersion));
        if (typeName == null) {
            typeName = currentName(txn, indexId);
        }
        return decodeExisting(typeName, currentData, primaryData);
    }

    private String currentName(Transaction txn, long indexId) throws IOException {
        byte[] currentName = viewExtended(indexId, K_TYPE_NAME).load(txn, EMPTY_BYTES);
        if (currentName == null) {
            return String.valueOf(indexId);
        } else {
            return decodeStringUTF(currentName, 0, currentName.length);
        }
    }

    /**
     * @param currentData if null, then alternateKeys and secondaryIndexes won't be decoded
     * @param primaryData if null, then null is returned
     * @return null only if primaryData is null
     */
    private static RowInfo decodeExisting(String typeName, byte[] currentData, byte[] primaryData)
        throws CorruptDatabaseException
    {
        if (primaryData == null) {
            return null;
        }

        int pos = 0;
        int encodingVersion = primaryData[pos++] & 0xff;

        if (encodingVersion != 1) {
            throw new CorruptDatabaseException("Unknown encoding version: " + encodingVersion);
        }

        var info = new RowInfo(typeName);
        info.allColumns = new TreeMap<>();

        var names = new String[decodePrefixPF(primaryData, pos)];
        pos += lengthPrefixPF(names.length);

        for (int i=0; i<names.length; i++) {
            int nameLen = decodePrefixPF(primaryData, pos);
            pos += lengthPrefixPF(nameLen);
            String name = decodeStringUTF(primaryData, pos, nameLen).intern();
            pos += nameLen;
            names[i] = name;
            var ci = new ColumnInfo();
            ci.name = name;
            ci.typeCode = decodeIntLE(primaryData, pos); pos += 4;
            info.allColumns.put(name, ci);
        }

        info.keyColumns = new LinkedHashMap<>();
        pos = decodeColumns(primaryData, pos, names, info.keyColumns);

        info.valueColumns = new TreeMap<>();
        pos = decodeColumns(primaryData, pos, names, info.valueColumns);
        if (info.valueColumns.isEmpty()) {
            info.valueColumns = Collections.emptyNavigableMap();
        }

        if (pos < primaryData.length) {
            throw new CorruptDatabaseException
                ("Trailing primary data: " + pos + " < " + primaryData.length);
        }

        if (currentData != null) {
            info.alternateKeys = new TreeSet<>(ColumnSetComparator.THE);
            pos = decodeColumnSets(currentData, 4 + 8, names, info.alternateKeys);
            if (info.alternateKeys.isEmpty()) {
                info.alternateKeys = Collections.emptyNavigableSet();
            }

            info.secondaryIndexes = new TreeSet<>(ColumnSetComparator.THE);
            pos = decodeColumnSets(currentData, pos, names, info.secondaryIndexes);
            if (info.secondaryIndexes.isEmpty()) {
                info.secondaryIndexes = Collections.emptyNavigableSet();
            }

            if (pos < currentData.length) {
                throw new CorruptDatabaseException
                    ("Trailing current data: " + pos + " < " + currentData.length);
            }
        }

        return info;
    }

    /**
     * @param columns to be filled in
     * @return updated position
     */
    private static int decodeColumns(byte[] data, int pos, String[] names,
                                     Map<String, ColumnInfo> columns)
    {
        int num = decodePrefixPF(data, pos);
        pos += lengthPrefixPF(num);
        for (int i=0; i<num; i++) {
            int columnNumber = decodePrefixPF(data, pos);
            pos += lengthPrefixPF(columnNumber);
            var ci = new ColumnInfo();
            ci.name = names[columnNumber];
            ci.typeCode = decodeIntLE(data, pos); pos += 4;
            ci.assignType();
            columns.put(ci.name, ci);
        }

        return pos;
    }

    /**
     * @param columnSets to be filled in
     * @return updated position
     */
    private static int decodeColumnSets(byte[] data, int pos, String[] names,
                                        Set<ColumnSet> columnSets)
    {
        int size = decodePrefixPF(data, pos);
        pos += lengthPrefixPF(size);
        for (int i=0; i<size; i++) {
            var cs = new ColumnSet();
            cs.allColumns = new TreeMap<>();
            pos = decodeColumns(data, pos, names, cs.allColumns);
            cs.keyColumns = new LinkedHashMap<>();
            pos = decodeColumns(data, pos, names, cs.keyColumns);
            cs.valueColumns = new TreeMap<>();
            pos = decodeColumns(data, pos, names, cs.valueColumns);
            if (cs.valueColumns.isEmpty()) {
                cs.valueColumns = Collections.emptyNavigableMap();
            }
            columnSets.add(cs);
        }
        return pos;
    }

    private static byte[] key(long indexId) {
        var key = new byte[8];
        encodeLongBE(key, 0, indexId);
        return key;
    }

    private static byte[] key(long indexId, int suffix) {
        var key = new byte[8 + 4];
        encodeLongBE(key, 0, indexId);
        encodeIntBE(key, 8, suffix);
        return key;
    }

    <R> Transcoder findSortTranscoder(Class<?> rowType, RowEvaluator<R> evaluator,
                                      SecondaryInfo sortedInfo)
    {
        WeakCache<TranscoderKey, Transcoder, SecondaryInfo> cache = mSortTranscoderCache;

        if (cache == null) {
            cache = new WeakCache<TranscoderKey, Transcoder, SecondaryInfo>() {
                @Override
                protected Transcoder newValue(TranscoderKey key, SecondaryInfo sortedInfo) {
                    Class<?> rowType = key.mRowType;
                    RowInfo rowInfo = RowInfo.find(rowType);

                    if (key.mSecondaryDesc != null) {
                        rowInfo = secondaryRowInfo(rowInfo, key.mSecondaryDesc);
                    }

                    return SortTranscoderMaker.makeTranscoder
                        (RowStore.this, rowType, rowInfo, key.mTableId, sortedInfo);
                }
            };

            var existing = (WeakCache<TranscoderKey, Transcoder, SecondaryInfo>)
                cSortTranscoderCacheHandle.compareAndExchange(this, null, cache);

            if (existing != null) {
                cache = existing;
            }
        }

        var key = new TranscoderKey(rowType, evaluator, sortedInfo.indexSpec());

        return cache.obtain(key, sortedInfo);
    }

    private static final class TranscoderKey {
        final Class<?> mRowType;
        final long mTableId;
        final byte[] mSecondaryDesc;
        final String mSortedInfoSpec;

        TranscoderKey(Class<?> rowType, RowEvaluator<?> evaluator, String sortedInfoSpec) {
            mRowType = rowType;
            mTableId = evaluator.evolvableTableId();
            mSecondaryDesc = evaluator.secondaryDescriptor();
            mSortedInfoSpec = sortedInfoSpec;
        }

        @Override
        public int hashCode() {
            int hash = mRowType.hashCode();
            hash = hash * 31 + (int) mTableId;
            hash = hash * 31 + Arrays.hashCode(mSecondaryDesc);
            hash = hash * 31 + mSortedInfoSpec.hashCode();
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || obj instanceof TranscoderKey other
                && mRowType == other.mRowType && mTableId == other.mTableId
                && Arrays.equals(mSecondaryDesc, other.mSecondaryDesc)
                && mSortedInfoSpec.equals(other.mSortedInfoSpec);
        }
    }

    private static class EncodedRowInfo {
        // All the column names, in lexicographical order.
        final String[] names;

        // Primary ColumnSet.
        final byte[] primaryData;

        // Hash code over the primary data.
        final int primaryHash;

        // Current schemaVersion (initially zero), alternateKeys, and secondaryIndexes.
        final byte[] currentData;

        // Set of descriptors.
        final TreeSet<byte[]> secondaries;

        /**
         * Constructor for encoding and writing.
         */
        EncodedRowInfo(RowInfo info) {
            names = new String[info.allColumns.size()];
            var columnNameMap = new HashMap<String, Integer>();
            var encoder = new Encoder(names.length * 16); // with initial capacity guess
            encoder.writeByte(1); // encoding version

            encoder.writePrefixPF(names.length);

            int columnNumber = 0;
            for (ColumnInfo column : info.allColumns.values()) {
                String name = column.name;
                columnNameMap.put(name, columnNumber);
                names[columnNumber++] = name;
                encoder.writeStringUTF(name);
                encoder.writeIntLE(column.typeCode);
            }

            encodeColumns(encoder, info.keyColumns.values(), columnNameMap);
            encodeColumns(encoder, info.valueColumns.values(), columnNameMap);

            primaryData = encoder.toByteArray();
            primaryHash = Arrays.hashCode(primaryData);

            encoder.reset(0);
            encoder.writeIntLE(0); // slot for current schemaVersion
            encoder.writeLongLE(0); // slot for table version
            encodeColumnSets(encoder, info.alternateKeys, columnNameMap);
            encodeColumnSets(encoder, info.secondaryIndexes, columnNameMap);

            currentData = encoder.toByteArray();

            secondaries = new TreeSet<>(KEY_COMPARATOR);
            info.alternateKeys.forEach(cs -> secondaries.add(encodeDescriptor('A', encoder, cs)));
            info.secondaryIndexes.forEach(cs -> secondaries.add(encodeDescriptor('I', encoder,cs)));
        }

        /**
         * Encode columns using name indexes instead of strings.
         */
        private static void encodeColumns(Encoder encoder,
                                          Collection<ColumnInfo> columns,
                                          Map<String, Integer> columnNameMap)
        {
            encoder.writePrefixPF(columns.size());
            for (ColumnInfo column : columns) {
                encoder.writePrefixPF(columnNameMap.get(column.name));
                encoder.writeIntLE(column.typeCode);
            }
        }

        /**
         * Encode column sets using name indexes instead of strings.
         */
        private static void encodeColumnSets(Encoder encoder,
                                             Collection<ColumnSet> columnSets,
                                             Map<String, Integer> columnNameMap)
        {
            encoder.writePrefixPF(columnSets.size());
            for (ColumnSet cs : columnSets) {
                encodeColumns(encoder, cs.allColumns.values(), columnNameMap);
                encodeColumns(encoder, cs.keyColumns.values(), columnNameMap);
                encodeColumns(encoder, cs.valueColumns.values(), columnNameMap);
            }
        }

        /**
         * Encode a secondary index descriptor.
         *
         * @param type 'A' or 'I'; alternate key descriptors are ordered first
         */
        private static byte[] encodeDescriptor(char type, Encoder encoder, ColumnSet cs) {
            encoder.reset(0);
            encoder.writeByte((byte) type);
            encodeColumns(encoder, cs.keyColumns);
            encodeColumns(encoder, cs.valueColumns);
            return encoder.toByteArray();
        }

        private static void encodeColumns(Encoder encoder, Map<String, ColumnInfo> columns) {
            encoder.writePrefixPF(columns.size());
            for (ColumnInfo column : columns.values()) {
                encoder.writeIntBE(column.typeCode ^ (1 << 31));
                encoder.writeStringUTF(column.name);
            }
        }
    }
}
