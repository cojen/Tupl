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

import java.util.Random;

import java.util.concurrent.Executors;

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.TestUtils;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class SchedulerTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(SchedulerTest.class.getName());
    }

    @Before
    public void setup() {
        mScheduler = new Scheduler(Executors.newCachedThreadPool(r -> {
            var t = new Thread(r);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        }));
    }

    @After
    public void teardown() {
        mScheduler.shutdown();
    }

    private Scheduler mScheduler;

    @Test
    public void basic() {
        try {
            doBasic();
        } catch (AssertionError e) {
            // Try again.
            doBasic();
        }
    }

    private void doBasic() {
        var task = new Task();
        assertTrue(mScheduler.execute(task));
        task.runCheck();

        task = new Task();
        long start = System.currentTimeMillis();
        assertTrue(mScheduler.schedule(task, 100));
        task.runCheck();
        long delay = System.currentTimeMillis() - start;
        assertTrue("" + delay, delay >= 100 && delay <= 500);

        task = new Task();
        assertTrue(mScheduler.schedule(task, 100));
        mScheduler.shutdown();
        try {
            task.runCheck();
        } catch (AssertionError e) {
            // Expected.
        }

        assertFalse(mScheduler.execute(new Task()));
    }

    @Test
    public void fuzz() {
        try {
            doFuzz();
        } catch (AssertionError e) {
            // Try again.
            doFuzz();
        }
    }

    private void doFuzz() {
        var tasks = new Task[1000];
        for (int i=0; i<tasks.length; i++) {
            tasks[i] = new Task();
        }

        var rnd = new Random(309458);

        for (int i=0; i<tasks.length; i++) {
            long delay = rnd.nextInt(1000);
            tasks[i].expectedTime = System.currentTimeMillis() + delay;
            mScheduler.schedule(tasks[i], delay);
            TestUtils.sleep(rnd.nextInt(10));
        }

        for (Task task : tasks) {
            task.runCheck();
            long deviation = task.actualTime - task.expectedTime;
            assertTrue("" + deviation, deviation >= 0 && deviation <= 500); 
        }
    }

    static class Task implements Runnable {
        long expectedTime;
        volatile long actualTime;
        volatile boolean ran;

        @Override
        public void run() {
            actualTime = System.currentTimeMillis();
            ran = true;
        }

        void runCheck() {
            for (int i=0; i<2000; i++) {
                if (ran) {
                    return;
                }
                TestUtils.sleep(1);
            }
            fail();
        }
    };
}
