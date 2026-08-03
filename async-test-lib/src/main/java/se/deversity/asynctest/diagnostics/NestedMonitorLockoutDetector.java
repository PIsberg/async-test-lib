package se.deversity.asynctest.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects the <em>nested monitor lockout</em> anti-pattern: performing a blocking operation
 * (e.g. {@code Object.wait()}, {@code Future.get()}, {@code Lock.lock()}) while holding
 * a monitor lock on a <em>different</em> object.
 *
 * <h2>Why this causes deadlock</h2>
 * When thread A holds monitor M1 and calls {@code wait()} on M2, it releases M2 but keeps
 * M1 held. If the thread that eventually calls {@code notifyAll()} on M2 also needs to
 * acquire M1 first, both threads will wait forever — a deadlock that is impossible to detect
 * from a thread dump because neither thread shows as "waiting to acquire" M1.
 *
 * <p>Even without a true deadlock, holding a coarse-grained monitor while blocking on a
 * fine-grained one severely degrades throughput.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectNestedMonitorLockout = true)
 * void testNestedLock() {
 *     NestedMonitorLockoutDetector mon = AsyncTestContext.nestedMonitorLockoutMonitor();
 *     synchronized (lockA) {
 *         mon.recordMonitorAcquired(lockA);
 *         // --- BUG: blocking on a Future while holding lockA ---
 *         mon.recordBlockingOperationAttempted("future.get()");
 *         result = future.get();
 *         mon.recordMonitorReleased(lockA);
 *     }
 * }
 * }</pre>
 */
public class NestedMonitorLockoutDetector {

    /** Per-thread set of currently held monitor identity hashes. */
    private final Map<Long, Deque<Integer>> heldMonitors = new ConcurrentHashMap<>();

    /** All captured nested-monitor-lockout events. */
    private final List<String> issues = new CopyOnWriteArrayList<>();

    private Deque<Integer> monitorsFor(Thread t) {
        return heldMonitors.computeIfAbsent(t.threadId(), id -> new ArrayDeque<>());
    }

    /**
     * Record that the current thread acquired a monitor (entered a {@code synchronized} block).
     *
     * @param monitor the object whose monitor was acquired (null-safe)
     */
    public void recordMonitorAcquired(Object monitor) {
        if (monitor == null) return;
        monitorsFor(Thread.currentThread()).push(System.identityHashCode(monitor));
    }

    /**
     * Record that the current thread released a monitor (exited a {@code synchronized} block).
     *
     * @param monitor the object whose monitor was released (null-safe)
     */
    public void recordMonitorReleased(Object monitor) {
        if (monitor == null) return;
        Deque<Integer> held = heldMonitors.get(Thread.currentThread().threadId());
        if (held != null) held.remove(System.identityHashCode(monitor));
    }

    /**
     * Record that the current thread is about to perform a blocking operation.
     * If the thread currently holds one or more monitors, a lockout risk is recorded.
     *
     * @param operation human-readable name of the blocking operation (e.g. {@code "future.get()"})
     */
    public void recordBlockingOperationAttempted(String operation) {
        Thread t = Thread.currentThread();
        Deque<Integer> held = heldMonitors.get(t.threadId());
        if (held == null || held.isEmpty()) return;

        issues.add(String.format(
            "Thread '%s' attempted blocking operation '%s' while holding %d monitor(s) — nested monitor lockout risk",
            t.getName(), operation, held.size()));
    }

    /**
     * Analyze for nested monitor lockout incidents.
     *
     * @return report of all detected incidents
     */
    public NestedMonitorLockoutReport analyze() {
        NestedMonitorLockoutReport report = new NestedMonitorLockoutReport();
        report.incidents.addAll(issues);
        return report;
    }

    /** Report produced by {@link #analyze()}. */
    public static class NestedMonitorLockoutReport {
        final List<String> incidents = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !incidents.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("NESTED MONITOR LOCKOUT ISSUES DETECTED:\n");
            Set<String> deduped = new LinkedHashSet<>(incidents);
            for (String incident : deduped) sb.append("  - ").append(incident).append("\n");
            sb.append("  Why: When a thread holds Monitor A and then calls a blocking operation that waits for Monitor B,\n")
              .append("       every thread that needs Monitor A is forced to wait for the unrelated blocking call to complete —\n")
              .append("       even though the blocking call has nothing to do with the state protected by Monitor A.\n")
              .append("       If Monitor B is also contested, this creates a layered wait chain that degrades to a near-deadlock.\n")
              .append("  Fix:\n")
              .append("    - Release the monitor before any blocking call: copy the data you need under the lock, exit the\n")
              .append("      synchronized block, then perform the blocking operation outside\n")
              .append("    - Use Condition.await() (from ReentrantLock) instead of Object.wait() — it releases the associated\n")
              .append("      lock atomically while waiting, so other threads can enter the critical section");
            return sb.toString();
        }
    }
}
