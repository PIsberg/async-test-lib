package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that a sleep inside a monitor is reported on a virtual thread, which is the default runner.
 *
 * <p><strong>Why this exists.</strong> {@code recordSleep(long)} asks the JVM which monitors the
 * calling thread holds, through {@code ThreadMXBean.getThreadInfo(id)}. That call does not report
 * virtual threads, and {@code @AsyncTest} runs its workers on virtual threads by default, so the
 * detector saw no lock however deep inside a {@code synchronized} block the caller was. Measured
 * on {@code examples/74-sleep-in-lock}: silent by default, "SLEEP-IN-LOCK PATTERNS DETECTED" with
 * {@code useVirtualThreads = false}, same subject and same seam. Issues #367 and #373.
 *
 * <p>{@code Thread.holdsLock(Object)} answers the same question exactly, on any thread and any
 * JDK, for the price of the caller naming the monitor it is holding. That is more evidence than
 * the old path, not less: the JVM confirms the specific claim rather than the detector inferring
 * from whichever monitor {@code getLockedMonitors()} happens to return first.
 */
class SleepInLockOnVirtualThreadsTest {

    @Test
    @DisplayName("a sleep inside a monitor is reported from a virtual thread")
    void reportsASleepHeldUnderAMonitorOnAVirtualThread() throws Exception {
        SleepInLockDetector detector = new SleepInLockDetector();
        detector.startMonitoring();
        Object monitor = new Object();

        Thread worker = Thread.ofVirtual().name("virtual-sleeper").start(() -> {
            synchronized (monitor) {
                detector.recordSleep(50, monitor);
            }
        });
        worker.join(5_000);

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the thread genuinely held the monitor while recording the sleep, and it being a "
                        + "virtual thread is exactly the case this detector used to miss. Report: "
                        + report);
        assertTrue(report.toString().contains("virtual-sleeper"),
                "and the report names the thread a reader can go and look at: " + report);
    }

    @Test
    @DisplayName("a sleep outside every monitor is not reported")
    void staysSilentWhenTheCallerHoldsNothing() throws Exception {
        SleepInLockDetector detector = new SleepInLockDetector();
        detector.startMonitoring();
        Object monitor = new Object();

        Thread worker = Thread.ofVirtual().name("virtual-sleeper").start(() ->
                detector.recordSleep(50, monitor));      // deliberately not synchronized
        worker.join(5_000);

        assertFalse(detector.analyze().hasIssues(),
                "sleeping while holding nothing is not the anti-pattern, and reporting it would "
                        + "be a false positive on correct code");
    }

    @Test
    @DisplayName("the monitor the caller names is the one checked, not any monitor it happens to hold")
    void checksTheNamedMonitorRatherThanAnyMonitor() throws Exception {
        SleepInLockDetector detector = new SleepInLockDetector();
        detector.startMonitoring();
        Object held = new Object();
        Object notHeld = new Object();
        AtomicReference<Boolean> sanity = new AtomicReference<>();

        Thread worker = Thread.ofVirtual().start(() -> {
            synchronized (held) {
                sanity.set(Thread.holdsLock(held));
                detector.recordSleep(50, notHeld);
            }
        });
        worker.join(5_000);

        assertTrue(sanity.get(), "the fixture must really hold 'held', or this proves nothing");
        assertFalse(detector.analyze().hasIssues(),
                "the caller named a monitor it does not hold. Reporting that would make the "
                        + "overload a way to assert a finding into existence rather than a way to "
                        + "have the JVM confirm one");
    }

    // --- java.util.concurrent locks. The agent's lockset holds these as well as monitors, and it
    //     hands the top entry to recordSleep. Thread.holdsLock answers false for a ReentrantLock
    //     however long the thread has held it, so a sleep under lock() was dropped on the floor.
    //     Found by the corpus pair that sleeps in HikariCP while occupying a Guava Monitor.

    @Test
    @DisplayName("a sleep while holding a ReentrantLock is reported")
    void reportsASleepHeldUnderAReentrantLock() throws Exception {
        SleepInLockDetector detector = new SleepInLockDetector();
        detector.startMonitoring();
        ReentrantLock lock = new ReentrantLock();

        Thread worker = Thread.ofVirtual().name("lock-sleeper").start(() -> {
            lock.lock();
            try {
                detector.recordSleep(50, lock);
            } finally {
                lock.unlock();
            }
        });
        worker.join(5_000);

        SleepInLockDetector.SleepInLockReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the thread held the lock for the whole sleep, which queues every other caller "
                        + "behind a thread doing nothing - the same defect as under a monitor. "
                        + "Report: " + report);
        assertTrue(report.toString().contains("lock-sleeper"),
                "and the report names the sleeping thread: " + report);
    }

    @Test
    @DisplayName("a sleep while holding either side of a ReentrantReadWriteLock is reported")
    void reportsASleepHeldUnderEitherSideOfAReadWriteLock() throws Exception {
        for (boolean write : new boolean[] {true, false}) {
            SleepInLockDetector detector = new SleepInLockDetector();
            detector.startMonitoring();
            ReentrantReadWriteLock owner = new ReentrantReadWriteLock();
            java.util.concurrent.locks.Lock side = write ? owner.writeLock() : owner.readLock();

            // The agent's lockset records the owner for both views, so the owner is what arrives.
            Thread worker = Thread.ofVirtual().start(() -> {
                side.lock();
                try {
                    detector.recordSleep(50, owner);
                } finally {
                    side.unlock();
                }
            });
            worker.join(5_000);

            assertTrue(detector.analyze().hasIssues(),
                    "a sleeping " + (write ? "writer" : "reader") + " blocks every writer for "
                            + "as long as it sleeps");
        }
    }

    @Test
    @DisplayName("a ReentrantLock another thread holds is not evidence")
    void staysSilentWhenTheNamedLockIsHeldByAnotherThread() throws Exception {
        SleepInLockDetector detector = new SleepInLockDetector();
        detector.startMonitoring();
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            Thread worker = Thread.ofVirtual().start(() -> detector.recordSleep(50, lock));
            worker.join(5_000);
        } finally {
            lock.unlock();
        }

        assertFalse(detector.analyze().hasIssues(),
                "the lock is held, but not by the thread that slept. Accepting it would make the "
                        + "overload a way to assert a finding into existence, which the monitor "
                        + "path already refuses");
    }
}
