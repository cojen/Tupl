/*
 *  Copyright (C) 2023 Cojen.org
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
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.remote;

import java.io.IOException;

import org.cojen.dirmi.Session;
import org.cojen.dirmi.SessionAware;

import org.cojen.tupl.Updater;

import org.cojen.tupl.io.Utils;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public final class ServerUpdater implements RemoteUpdater, SessionAware {
    public static Updater updater(RemoteUpdater remote) {
        return remote == null ? null : ((ServerUpdater) remote).mUpdater;
    }

    final Updater mUpdater;

    ServerUpdater(Updater updater) {
        mUpdater = updater;
    }

    @Override
    public void attached(Session<?> session) {
    }

    @Override
    public void detached(Session<?> session) {
        try {
            mUpdater.close();
        } catch (IOException e) {
            // Ignore.
        }
    }

    @Override
    public void close() throws IOException {
        mUpdater.close();
    }

    @Override
    public void dispose() {
        Utils.closeQuietly(this);
    }
}
