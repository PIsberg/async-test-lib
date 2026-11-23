package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link DeadlockDetector#printThreadDump()} prints, the dump a timed-out run shows. Pinned
 * from the outside, by the sections and lines a reader looks for, so the method can change shape
 * without changing what it says.
 */
class DeadlockDetectorThreadDumpTest {

    private static String dump() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            DeadlockDetector.printThreadDump();
        } finally {
            System.setErr(previous);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a timeout with no deadlock lists the blocked and waiting threads")
    void aTimeoutWithoutADeadlockListsTheWaitingThreads() throws Exception {
        CountDownLatch never = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "dump-probe-waiter");
        waiter.setDaemon(true);
        waiter.start();
        try {
            while (waiter.getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
            }
            String dump = dump();

            assertTrue(dump.contains("ASYNC-TEST DEADLOCK / TIMEOUT DETECTED"), dump);
            assertTrue(dump.contains("=== RAW THREAD DUMP ==="), dump);
            assertTrue(dump.contains("=== LOCK ANALYSIS ==="), dump);
            assertTrue(dump.contains("No circular deadlocks detected, but test timed out."), dump);
            assertTrue(dump.contains("--- Blocked/Waiting Threads ---"), dump);
            assertTrue(dump.contains("dump-probe-waiter (WAITING)"), dump);
            assertTrue(dump.indexOf("No circular deadlocks detected")
                    < dump.indexOf("dump-probe-waiter (WAITING)"), "listed after the verdict: " + dump);
        } finally {
            waiter.interrupt();
            waiter.join(5_000);
        }
    }

    @Test
    @DisplayName("a real lock cycle is drawn as a graph, then each thread's chain")
    void aDeadlockIsDrawnAsACycle() throws Exception {
        ReentrantLock first = new ReentrantLock();
        ReentrantLock second = new ReentrantLock();
        CyclicBarrier bothHold = new CyclicBarrier(2);
        Thread a = deadlocker("dump-probe-a", first, second, bothHold);
        Thread b = deadlocker("dump-probe-b", second, first, bothHold);
        a.start();
        b.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!first.hasQueuedThreads() || !second.hasQueuedThreads()) {
                assertTrue(System.nanoTime() < deadline, "the two threads never deadlocked");
                Thread.onSpinWait();
            }
            String dump = dump();

            assertTrue(dump.contains("*** CIRCULAR DEADLOCK DETECTED ***"), dump);
            assertTrue(dump.contains("CIRCULAR DEADLOCK GRAPH"), dump);
            assertTrue(dump.contains("Cycle #1:"), dump);
            assertTrue(dump.contains("\"dump-probe-a\"") && dump.contains("\"dump-probe-b\""), dump);
            assertTrue(dump.contains("(CYCLE START)"), dump);
            assertTrue(dump.contains("=== DETAILED DEADLOCKED THREAD INFOS ==="), dump);
            assertTrue(dump.contains("Waiting for lock: ") && dump.contains("Lock held by: Thread-"), dump);
            assertTrue(!dump.contains("No circular deadlocks detected"), dump);
        } finally {
            a.interrupt();
            b.interrupt();
            a.join(5_000);
            b.join(5_000);
        }
    }

    private static Thread deadlocker(String name, ReentrantLock mine, ReentrantLock theirs, CyclicBarrier bothHold) {
        Thread thread = new Thread(() -> {
            try {
                mine.lockInterruptibly();
                try {
                    bothHold.await();
                    theirs.lockInterruptibly();
                    theirs.unlock();
                } finally {
                    mine.unlock();
                }
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }
}
