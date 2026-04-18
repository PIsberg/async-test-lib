package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects high lock contention — monitors where many threads compete to acquire
 * the same lock, causing threads to spend significant time in BLOCKED state.
 *
 * <p>High contention degrades throughput and scalability. A monitor with a
 * contention ratio above 20% (i.e., more than 1 in 5 acquire attempts had to
 * wait) is reported as a hot-lock hotspot.
 *
 * <p>Instrumentation points:
 * <ul>
 *   <li>{@link #recordAcquireAttempt} — call immediately before entering a
 *       {@code synchronized} block or calling {@code Lock.lock()}</li>
 *   <li>{@link #recordContention} — call when a thread discovers the monitor is
 *       already held (e.g. after tryLock returns false, or before blocking)</li>
 *   <li>{@link #recordAcquired} — call once the lock has been successfully
 *       acquired</li>
 *   <li>{@link #recordReleased} — call once the lock is released</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 8, detectLockContention = true)
 * void testHighContention() {
 *     AsyncTestContext.lockContentionDetector()
 *         .recordAcquireAttempt(sharedLock, "sharedLock");
 *     synchronized (sharedLock) {
 *         AsyncTestContext.lockContentionDetector()
 *             .recordAcquired(sharedLock, "sharedLock");
 *         // critical section
 *     }
 *     AsyncTestContext.lockContentionDetector()
 *         .recordReleased(sharedLock, "sharedLock");
 * }
 * }</pre>
 */
public class LockContentionDetector {

    /** Contention ratio threshold above which a monitor is reported as hot. */
    private static final double CONTENTION_THRESHOLD = 0.20;

    private static final class MonitorState {
        final String name;
        final AtomicInteger acquireAttempts = new AtomicInteger();
        final AtomicInteger contentionEvents = new AtomicInteger();
        final AtomicInteger acquireSuccesses = new AtomicInteger();
        final AtomicInteger currentHolders   = new AtomicInteger();

        MonitorState(String name) {
            this.name = name;
        }
    }

    private final Map<Integer, MonitorState> monitors = new ConcurrentHashMap<>();

    // ---- Public API --------------------------------------------------------

    /**
     * Records an attempt to acquire {@code monitor}.
     * Call this immediately before entering the critical section.
     *
     * @param monitor the lock or synchronized object
     * @param name    a descriptive label (used in reports)
     */
    public void recordAcquireAttempt(Object monitor, String name) {
        if (monitor == null) return;
        resolve(monitor, name).acquireAttempts.incrementAndGet();
    }

    /**
     * Records that the calling thread had to wait because {@code monitor} was
     * already held by another thread.  Call this when contention is observed
     * (e.g. tryLock returned {@code false}, or after BLOCKED state measurement).
     *
     * @param monitor the lock or synchronized object
     * @param name    a descriptive label (used in reports)
     */
    public void recordContention(Object monitor, String name) {
        if (monitor == null) return;
        resolve(monitor, name).contentionEvents.incrementAndGet();
    }

    /**
     * Records a successful lock acquisition.
     * Call this once the calling thread holds the lock.
     *
     * @param monitor the lock or synchronized object
     * @param name    a descriptive label (used in reports)
     */
    public void recordAcquired(Object monitor, String name) {
        if (monitor == null) return;
        MonitorState state = resolve(monitor, name);
        state.acquireSuccesses.incrementAndGet();
        state.currentHolders.incrementAndGet();
    }

    /**
     * Records a lock release.
     * Call this immediately after exiting the critical section.
     *
     * @param monitor the lock or synchronized object
     * @param name    a descriptive label (used in reports)
     */
    public void recordReleased(Object monitor, String name) {
        if (monitor == null) return;
        MonitorState state = resolve(monitor, name);
        int h = state.currentHolders.decrementAndGet();
        if (h < 0) state.currentHolders.set(0);
    }

    // ---- Analysis ----------------------------------------------------------

    /**
     * Analyses recorded data and returns a contention report.
     */
    public LockContentionReport analyze() {
        LockContentionReport report = new LockContentionReport();

        for (MonitorState state : monitors.values()) {
            int attempts = state.acquireAttempts.get();
            int contended = state.contentionEvents.get();
            if (attempts == 0) continue;

            double ratio = (double) contended / attempts;
            if (ratio >= CONTENTION_THRESHOLD || contended >= 5) {
                report.hotLocks.add(String.format(
                        "%s: %d acquire attempt(s), %d contention event(s) (%.0f%% contention ratio) — HIGH CONTENTION",
                        state.name, attempts, contended, ratio * 100));
            }
        }

        return report;
    }

    // ---- Internal ----------------------------------------------------------

    private MonitorState resolve(Object monitor, String name) {
        int key = System.identityHashCode(monitor);
        return monitors.computeIfAbsent(key, k -> {
            String label = (name != null) ? name : monitor.getClass().getSimpleName() + "@" + k;
            return new MonitorState(label);
        });
    }

    // ---- Report ------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class LockContentionReport {

        final List<String> hotLocks = new ArrayList<>();

        /** Returns {@code true} when any monitor exceeds the contention threshold. */
        public boolean hasIssues() {
            return !hotLocks.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("LOCK CONTENTION ISSUES DETECTED:\n");

            if (!hotLocks.isEmpty()) {
                sb.append("  Hot Monitors (high thread contention):\n");
                for (String entry : hotLocks) {
                    sb.append("    - ").append(entry).append("\n");
                }
            } else {
                sb.append("  No contention issues detected.\n");
            }

            sb.append("  Fix: reduce critical-section size, use finer-grained locks,")
              .append(" lock striping, or lock-free data structures (AtomicXxx, ConcurrentHashMap).");
            return sb.toString();
        }
    }
}
