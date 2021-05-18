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

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.VarHandle;

import java.util.function.IntFunction;

import org.cojen.maker.Label;
import org.cojen.maker.MethodMaker;

/**
 * A SwitchCallSite delegates to MethodHandles selected by an int key.
 *
 * @author Brian S O'Neill
 */
class SwitchCallSite extends MutableCallSite {
    private final IntFunction<Object> mGenerator;

    // Hashtable which maps int keys to MethodHandles.
    private Entry[] mEntries;
    private int mSize;

    /**
     * The first parameter of the given MethodType must be an int key. The remaining parameters
     * and return type can be anything. The generator must make MethodHandles which match the
     * given MethodType except without the key parameter.
     *
     * @param mt must be: <ret> (int key, <remaining>)
     * @param generator supplies cases for keys; supplies MethodHandle or
     * ExceptionCallSite.Failed. MethodType must omit the key: <ret> (<remaining>)
     */
    SwitchCallSite(MethodHandles.Lookup lookup, MethodType mt, IntFunction<Object> generator) {
        super(mt);
        mGenerator = generator;
        makeDelegator(lookup, mt.dropParameterTypes(0, 1));
    }

    /**
     * Makes the delegator and sets it as the current target.
     *
     * @param mt must not have the key parameter
     * @return the delegator (usually a big switch statement)
     */
    synchronized MethodHandle makeDelegator(MethodHandles.Lookup lookup, MethodType mt) {
        // Insert the key as the first parameter.
        MethodMaker mm = MethodMaker.begin(lookup, "_", mt.insertParameterTypes(0, int.class));

        var keyVar = mm.param(0);

        if (mSize != 0) {
            var remainingParams = new Object[mt.parameterCount()];
            for (int i=0; i<remainingParams.length; i++) {
                remainingParams[i] = mm.param(i + 1);
            }

            if (mSize > 100) {
                // The switch statement is getting big, and rebuilding it each time gets more
                // expensive. Generate a final delegator which accesses the hashtable.
                var dcsVar = mm.var(SwitchCallSite.class).setExact(this);
                var caseVar = dcsVar.invoke("getCase", keyVar);
                Label found = mm.label();
                caseVar.ifNe(null, found);
                var lookupVar = mm.var(MethodHandles.Lookup.class).setExact(lookup);
                caseVar.set(dcsVar.invoke("newCaseDirect", lookupVar, keyVar, mt));
                found.here();
                mm.return_(caseVar.invoke(mt.returnType(), "invokeExact", null, remainingParams));
                var mh = mm.finish();
                setTarget(mh);
                return mh;
            }

            var cases = new int[mSize];
            var labels = new Label[cases.length];
            var defLabel = mm.label();

            int num = 0;
            for (Entry e : mEntries) {
                while (e != null) {
                    cases[num] = e.key;
                    labels[num++] = mm.label();
                    e = e.next;
                }
            }

            keyVar.switch_(defLabel, cases, labels);

            for (int i=0; i<cases.length; i++) {
                labels[i].here();
                var result = mm.invoke(getCase(cases[i]), remainingParams);
                if (result == null) {
                    mm.return_();
                } else {
                    mm.return_(result);
                }
            }

            defLabel.here();
        }

        var dcsVar = mm.var(SwitchCallSite.class).setExact(this);
        var lookupVar = mm.var(MethodHandles.Lookup.class).setExact(lookup);
        var newCaseVar = dcsVar.invoke("newCase", lookupVar, keyVar, mt);
        var allParams = new Object[1 + mt.parameterCount()];
        for (int i=0; i<allParams.length; i++) {
            allParams[i] = mm.param(i);
        }
        var result = newCaseVar.invoke(mt.returnType(), "invokeExact", null, allParams);
        if (result == null) {
            mm.return_();
        } else {
            mm.return_(result);
        }

        var mh = mm.finish();
        setTarget(mh);
        return mh;
    }

    /**
     * @param mt must not have the key parameter
     * @return the delegator (usually a big switch statement)
     */
    synchronized MethodHandle newCase(MethodHandles.Lookup lookup, int key, MethodType mt) {
        if (mEntries != null && getCase(key) != null) {
            return getTarget();
        } else {
            CallSite cs = ExceptionCallSite.make(() -> mGenerator.apply(key));
            putCase(key, cs.dynamicInvoker());
            return makeDelegator(lookup, mt);
        }
    }

    /**
     * @param mt must not have the key parameter
     * @return the case itself
     */
    synchronized MethodHandle newCaseDirect(MethodHandles.Lookup lookup, int key, MethodType mt) {
        MethodHandle caseHandle = getCase(key);
        if (caseHandle == null) {
            CallSite cs = ExceptionCallSite.make(() -> mGenerator.apply(key));
            caseHandle = cs.dynamicInvoker();
            putCase(key, caseHandle);
        }
        return caseHandle;
    }

    /** 
     * Is called by the generated delegator when the switch isn't used anymore. Note that the
     * call isn't synchronized. If the case isn't found due to a race condition, the delegator
     * calls newCaseDirect, which is synchronized and does a double check first.
     */
    MethodHandle getCase(int key) {
        Entry[] entries = mEntries;
        for (Entry e = entries[key & (entries.length - 1)]; e != null; e = e.next) {
            if (e.key == key) {
                return e.mh;
            }
        }
        return null;
    }

    /**
     * Caller must be certain that a matching entry doesn't already exist.
     */
    private void putCase(int key, MethodHandle mh) {
        Entry[] entries = mEntries;
        if (entries == null) {
            mEntries = entries = new Entry[4]; // must be power of 2 size
        } else if (mSize >= entries.length) {
            // rehash
            Entry[] newEntries = new Entry[entries.length << 1];
            for (int i=entries.length; --i>=0 ;) {
                for (Entry e = entries[i]; e != null; ) {
                    Entry next = e.next;
                    int index = e.key & (newEntries.length - 1);
                    e.next = newEntries[index];
                    newEntries[index] = e;
                    e = next;
                }
            }
            mEntries = entries = newEntries;
        }

        int index = key & (entries.length - 1);
        Entry e = new Entry(key, mh);
        e.next = entries[index];
        VarHandle.storeStoreFence(); // reduce likelihood of observing a broken chain
        entries[index] = e;
        mSize++;
    }

    private static class Entry {
        final int key;
        final MethodHandle mh;
        Entry next;

        Entry(int key, MethodHandle mh) {
            this.key = key;
            this.mh = mh;
        }
    }
}
