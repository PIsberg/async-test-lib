package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Detects lock leak patterns where locks are acquired but never released.
 * 
 * Common lock leak issues detected:
 * - Lock acquired but never released (missing unlock in finally block)
 * - Lock held across exception boundaries without proper cleanup
 * - ReentrantLock used without try-finally pattern
 * - Lock held for excessive duration (potential deadlock precursor)
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectLockLeaks = true)
 * void testLockUsage() {
 *     ReentrantLock lock = new ReentrantLock();
 *     AsyncTestContext.lockLeakMonitor()
 *         .registerLock(lock, "resource-lock");
 *     
 *     lock.lock();
 *     AsyncTestContext.lockLeakMonitor()
 *         .recordLockAcquired(lock, "resource-lock");
 *     try {
 *         // critical section
 *     } finally {
 *         lock.unlock();
 *         AsyncTestContext.lockLeakMonitor()
 *             .recordLockReleased(lock, "resource-lock");
 *     }
 * }
 * }</pre>
 */
public class LockLeakDetector {

    private static class LockState {
        final String name;
        final Lock lock;
        final AtomicInteger acquireCount = new AtomicInteger(0);
        final AtomicInteger releaseCount = new AtomicInteger(0);
        final Set<Long> acquiringThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> releasingThreads = ConcurrentHashMap.newKeySet();
        final Map<Long, Long> threadAcquireTime = new ConcurrentHashMap<>();
        final AtomicInteger maxHoldTimeMs = new AtomicInteger(0);
        volatile boolean currentlyHeld = false;
        volatile Long lastAcquireTime = null;
        volatile Long lastReleaseTime = null;

        LockState(Lock lock, String name) {
            this.lock = lock;
            this.name = name != null ? name : "lock@" + System.identityHashCode(lock);
        }
    }

    private final Map<Integer, LockState> locks = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a Lock for monitoring.
     * 
     * @param lock the Lock to monitor
     * @param name a descriptive name for reporting
     */
    public void registerLock(Lock lock, String name) {
        if (!enabled || lock == null) {
            return;
        }
        locks.put(System.identityHashCode(lock), new LockState(lock, name));
    }

    /**
     * Record that a lock was acquired.
     * 
     * @param lock the Lock
     * @param name the lock name (should match registration)
     */
    public void recordLockAcquired(Lock lock, String name) {
        if (!enabled || lock == null) {
            return;
        }
        LockState state = locks.get(System.identityHashCode(lock));
        if (state != null) {
            state.acquireCount.incrementAndGet();
            state.acquiringThreads.add(Thread.currentThread().threadId());
            state.currentlyHeld = true;
            long now = System.currentTimeMillis();
            state.lastAcquireTime = now;
            state.threadAcquireTime.put(Thread.currentThread().threadId(), now);
        }
    }

    /**
     * Record that a lock was released.
     * 
     * @param lock the Lock
     * @param name the lock name (should match registration)
     */
    public void recordLockReleased(Lock lock, String name) {
        if (!enabled || lock == null) {
            return;
        }
        LockState state = locks.get(System.identityHashCode(lock));
        if (state != null) {
            state.releaseCount.incrementAndGet();
            state.releasingThreads.add(Thread.currentThread().threadId());
            state.currentlyHeld = false;
            state.lastReleaseTime = System.currentTimeMillis();
            
            // Calculate hold time
            Long acquireTime = state.threadAcquireTime.remove(Thread.currentThread().threadId());
            if (acquireTime != null) {
                int holdTimeMs = (int) (System.currentTimeMillis() - acquireTime);
                state.maxHoldTimeMs.updateAndGet(max -> Math.max(max, holdTimeMs));
            }
        }
    }

    /**
     * Analyze lock usage for leak patterns.
     * 
     * @return a report of detected issues
     */
    public LockLeakReport analyze() {
        LockLeakReport report = new LockLeakReport();
        report.enabled = enabled;

        for (LockState state : locks.values()) {
            int acquires = state.acquireCount.get();
            int releases = state.releaseCount.get();

            // Check for lock leaks (more acquires than releases)
            if (acquires > releases) {
                report.lockLeaks.add(String.format(
                    "%s: acquired %d times but released only %d times (%d potential leaks)",
                    state.name, acquires, releases, acquires - releases));
            }

            // Check for currently held locks at analysis time
            if (state.currentlyHeld) {
                long holdTimeMs = state.lastAcquireTime != null 
                    ? System.currentTimeMillis() - state.lastAcquireTime 
                    : 0;
                report.heldLocks.add(String.format(
                    "%s: lock is currently held (last acquired %dms ago)",
                    state.name, holdTimeMs));
            }

            // Check for excessive hold times
            if (state.maxHoldTimeMs.get() > 5000) { // More than 5 seconds
                report.excessiveHoldTimes.add(String.format(
                    "%s: lock held for up to %dms (potential deadlock precursor)",
                    state.name, state.maxHoldTimeMs.get()));
            }

            // Track thread participation
            if (state.acquiringThreads.size() > 0) {
                report.threadActivity.put(state.name, String.format(
                    "%d threads acquired, %d threads released, max hold: %dms",
                    state.acquiringThreads.size(),
                    state.releasingThreads.size(),
                    state.maxHoldTimeMs.get()));
            }
        }

        return report;
    }

    /**
     * Report class for lock leak analysis.
     */
    public static class LockLeakReport {
        private boolean enabled = true;
        final java.util.List<String> lockLeaks = new java.util.ArrayList<>();
        final java.util.List<String> heldLocks = new java.util.ArrayList<>();
        final java.util.List<String> excessiveHoldTimes = new java.util.ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !lockLeaks.isEmpty() || !heldLocks.isEmpty() || !excessiveHoldTimes.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "LockLeakReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("LOCK LEAK ISSUES DETECTED:\n");

            if (!lockLeaks.isEmpty()) {
                sb.append("  Lock Leaks:\n");
                for (String leak : lockLeaks) {
                    sb.append("    - ").append(leak).append("\n");
                }
            }

            if (!heldLocks.isEmpty()) {
                sb.append("  Currently Held Locks:\n");
                for (String held : heldLocks) {
                    sb.append("    - ").append(held).append("\n");
                }
            }

            if (!excessiveHoldTimes.isEmpty()) {
                sb.append("  Excessive Hold Times:\n");
                for (String excessive : excessiveHoldTimes) {
                    sb.append("    - ").append(excessive).append("\n");
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

            sb.append("  Fix: always use try { lock.lock(); } finally { lock.unlock(); } pattern");
            return sb.toString();
        }
    }
}
