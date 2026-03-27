package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects semaphore misuse patterns in concurrent code.
 * 
 * Common semaphore issues detected:
 * - Permit leaks: acquire() without matching release()
 * - Over-release: release() called more times than acquire()
 * - Unreleased permits at test completion
 * - Concurrent access patterns that may cause starvation
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, monitorSemaphore = true)
 * void testSemaphore() throws InterruptedException {
 *     semaphore.acquire();
 *     AsyncTestContext.semaphoreMonitor()
 *         .recordAcquire(semaphore, "resource-pool");
 *     try {
 *         // work
 *     } finally {
 *         semaphore.release();
 *         AsyncTestContext.semaphoreMonitor()
 *             .recordRelease(semaphore, "resource-pool");
 *     }
 * }
 * }</pre>
 */
public class SemaphoreMisuseDetector {

    private static class SemaphoreState {
        final String name;
        final Semaphore semaphore;
        final AtomicInteger initialPermits;
        final AtomicInteger acquireCount = new AtomicInteger(0);
        final AtomicInteger releaseCount = new AtomicInteger(0);
        final Set<Long> acquiringThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> releasingThreads = ConcurrentHashMap.newKeySet();
        final AtomicInteger maxConcurrentAcquires = new AtomicInteger(0);
        final AtomicInteger currentAcquires = new AtomicInteger(0);

        SemaphoreState(Semaphore semaphore, String name, int permits) {
            this.semaphore = semaphore;
            this.name = name;
            this.initialPermits = new AtomicInteger(permits);
        }
    }

    private final Map<Integer, SemaphoreState> semaphores = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a semaphore for monitoring.
     * 
     * @param semaphore the semaphore to monitor
     * @param name a descriptive name for reporting
     * @param initialPermits the initial permit count
     */
    public void registerSemaphore(Semaphore semaphore, String name, int initialPermits) {
        if (!enabled || semaphore == null) {
            return;
        }
        semaphores.put(System.identityHashCode(semaphore), 
            new SemaphoreState(semaphore, name, initialPermits));
    }

    /**
     * Record a permit acquisition.
     * 
     * @param semaphore the semaphore
     * @param name the semaphore name (should match registration)
     */
    public void recordAcquire(Semaphore semaphore, String name) {
        if (!enabled || semaphore == null) {
            return;
        }
        SemaphoreState state = semaphores.get(System.identityHashCode(semaphore));
        if (state == null) {
            // Auto-register with unknown permits
            state = new SemaphoreState(semaphore, 
                name != null ? name : "semaphore@" + System.identityHashCode(semaphore), -1);
            semaphores.put(System.identityHashCode(semaphore), state);
        }
        state.acquireCount.incrementAndGet();
        state.acquiringThreads.add(Thread.currentThread().threadId());
        int current = state.currentAcquires.incrementAndGet();
        state.maxConcurrentAcquires.updateAndGet(max -> Math.max(max, current));
    }

    /**
     * Record a permit release.
     * 
     * @param semaphore the semaphore
     * @param name the semaphore name (should match registration)
     */
    public void recordRelease(Semaphore semaphore, String name) {
        if (!enabled || semaphore == null) {
            return;
        }
        SemaphoreState state = semaphores.get(System.identityHashCode(semaphore));
        if (state != null) {
            state.releaseCount.incrementAndGet();
            state.releasingThreads.add(Thread.currentThread().threadId());
            state.currentAcquires.decrementAndGet();
        }
    }

    /**
     * Analyze semaphore usage for issues.
     * 
     * @return a report of detected issues
     */
    public SemaphoreMisuseReport analyze() {
        SemaphoreMisuseReport report = new SemaphoreMisuseReport();
        report.enabled = enabled;

        for (SemaphoreState state : semaphores.values()) {
            int acquires = state.acquireCount.get();
            int releases = state.releaseCount.get();
            int available = state.semaphore.availablePermits();
            int initialPermits = state.initialPermits.get();

            // Check for permit leaks (more acquires than releases)
            if (acquires > releases) {
                report.permitLeaks.add(String.format(
                    "%s: acquired %d times but released only %d times (%d permits potentially leaked)",
                    state.name, acquires, releases, acquires - releases));
            }

            // Check for over-release (more releases than acquires)
            if (releases > acquires) {
                report.overReleases.add(String.format(
                    "%s: released %d times but acquired only %d times (%d extra releases)",
                    state.name, releases, acquires, releases - acquires));
            }

            // Check for unreleased permits at completion
            if (initialPermits >= 0 && available != initialPermits) {
                report.unreleasedPermits.add(String.format(
                    "%s: expected %d available permits but found %d",
                    state.name, initialPermits, available));
            }

            // Track thread participation
            if (state.acquiringThreads.size() > 0) {
                report.threadActivity.put(state.name, String.format(
                    "%d threads acquired, %d threads released, max concurrent: %d",
                    state.acquiringThreads.size(),
                    state.releasingThreads.size(),
                    state.maxConcurrentAcquires.get()));
            }
        }

        return report;
    }

    /**
     * Report class for semaphore misuse analysis.
     */
    public static class SemaphoreMisuseReport {
        private boolean enabled = true;
        final java.util.List<String> permitLeaks = new java.util.ArrayList<>();
        final java.util.List<String> overReleases = new java.util.ArrayList<>();
        final java.util.List<String> unreleasedPermits = new java.util.ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !permitLeaks.isEmpty() || !overReleases.isEmpty() || !unreleasedPermits.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "SemaphoreMisuseReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("SEMAPHORE MISUSE DETECTED:\n");

            if (!permitLeaks.isEmpty()) {
                sb.append("  Permit Leaks:\n");
                for (String leak : permitLeaks) {
                    sb.append("    - ").append(leak).append("\n");
                }
            }

            if (!overReleases.isEmpty()) {
                sb.append("  Over-Release:\n");
                for (String over : overReleases) {
                    sb.append("    - ").append(over).append("\n");
                }
            }

            if (!unreleasedPermits.isEmpty()) {
                sb.append("  Unreleased Permits:\n");
                for (String unreleased : unreleasedPermits) {
                    sb.append("    - ").append(unreleased).append("\n");
                }
            }

            if (!threadActivity.isEmpty()) {
                sb.append("  Thread Activity:\n");
                for (Map.Entry<String, String> entry : threadActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: ensure every acquire() has a matching release() in a finally block");
            return sb.toString();
        }
    }
}
