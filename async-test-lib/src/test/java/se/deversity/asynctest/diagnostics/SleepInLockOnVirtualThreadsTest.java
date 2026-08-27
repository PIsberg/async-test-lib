package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

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
}
