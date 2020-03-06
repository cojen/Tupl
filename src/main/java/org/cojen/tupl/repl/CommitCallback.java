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

/**
 * Callback which is invoked when the log commit position reaches a requested position.
 *
 * @author Brian S O'Neill
 */
abstract class CommitCallback implements Comparable<CommitCallback> {
    long mPosition;

    CommitCallback(long position) {
        mPosition = position;
    }

    @Override
    public int compareTo(CommitCallback other) {
        return Long.signum(mPosition - other.mPosition);
    }

    /**
     * Called when the log commit position has reached the requested position. The current
     * commit position is passed to this method, which is -1 if the term ended before the
     * position could be reached.
     */
    abstract void reached(long position);
}
