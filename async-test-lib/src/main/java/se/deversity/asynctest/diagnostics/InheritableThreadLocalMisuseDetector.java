package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects misuse of {@link InheritableThreadLocal} in thread-pool environments.
 *
 * <h2>The problem</h2>
 * {@code InheritableThreadLocal} copies parent-thread values into a child thread <em>at thread
 * creation time</em>, not at task-submission time.  In a thread pool the worker threads are
 * created once (from the pool-creator's context) and then reused for many unrelated tasks.
 * The result is that every task running on a pooled thread inherits the values that were set
 * when the pool was created — stale, wrong-user, or wrong-request context:
 *
 * <ul>
 *   <li>Request-scoped user ID leaks into a different user's task</li>
 *   <li>Transaction context from thread A contaminates thread B</li>
 *   <li>Locale / security context from the pool creator bleeds into every task</li>
 * </ul>
 *
 * <h2>Recommended fix</h2>
 * Prefer {@code ScopedValue} (Java 21+) for context propagation in virtual-thread and
 * structured-concurrency code.  For classic thread pools, pass context explicitly as method
 * parameters or wrap {@code Runnable}/{@code Callable} to capture and restore context.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * static final InheritableThreadLocal<String> USER = new InheritableThreadLocal<>();
 *
 * @AsyncTest(threads = 4, detectInheritableThreadLocalMisuse = true)
 * void testContextPropagation() {
 *     InheritableThreadLocalMisuseDetector mon =
 *         AsyncTestContext.inheritableThreadLocalMisuseMonitor();
 *     // Mark the thread pool's worker threads so the detector knows they are pooled
 *     mon.registerPoolThread(Thread.currentThread());
 *
 *     USER.set("user-42");
 *     mon.recordSet(USER, "USER", "user-42");
 *
 *     String value = USER.get();
 *     mon.recordGet(USER, "USER");  // detected if called from a pooled thread
 * }
 * }</pre>
 */
public class InheritableThreadLocalMisuseDetector {

    /** Thread IDs that belong to a thread pool (registered by test code). */
    private final Set<Long> knownPoolThreadIds = ConcurrentHashMap.newKeySet();

    private final List<String> pooledGetIssues = new CopyOnWriteArrayList<>();
    private final List<String> pooledSetIssues = new CopyOnWriteArrayList<>();

    /** Per-variable name → set of thread IDs that accessed it. */
    private final Map<String, Set<Long>> accessingThreads = new ConcurrentHashMap<>();

    /**
     * Register a thread as belonging to a thread pool.
     * The detector will flag any {@code InheritableThreadLocal} accesses from these threads.
     *
     * @param thread the pooled worker thread (null-safe)
     */
    public void registerPoolThread(Thread thread) {
        if (thread != null) knownPoolThreadIds.add(thread.threadId());
    }

    /**
     * Record a {@code get()} call on an {@link InheritableThreadLocal}.
     * If called from a registered pool thread, a staleness risk is flagged.
     *
     * @param itl          the {@code InheritableThreadLocal} (null-safe)
     * @param variableName descriptive name for reports
     */
    public void recordGet(InheritableThreadLocal<?> itl, String variableName) {
        if (itl == null) return;
        Thread t = Thread.currentThread();
        String name = resolved(variableName, itl);
        accessingThreads.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(t.threadId());

        if (knownPoolThreadIds.contains(t.threadId())) {
            pooledGetIssues.add(String.format(
                "InheritableThreadLocal '%s' read in pooled thread '%s' — "
                + "value was inherited at thread-creation time, not task-submission time (stale context risk)",
                name, t.getName()));
        }
    }

    /**
     * Record a {@code set()} call on an {@link InheritableThreadLocal}.
     * If called from a registered pool thread, cross-task contamination is flagged because any
     * child threads subsequently spawned from this pool thread will inherit the new value.
     *
     * @param itl          the {@code InheritableThreadLocal} (null-safe)
     * @param variableName descriptive name for reports
     * @param value        the value being set (used in the report message)
     */
    public void recordSet(InheritableThreadLocal<?> itl, String variableName, Object value) {
        if (itl == null) return;
        Thread t = Thread.currentThread();
        String name = resolved(variableName, itl);
        accessingThreads.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(t.threadId());

        if (knownPoolThreadIds.contains(t.threadId())) {
            pooledSetIssues.add(String.format(
                "InheritableThreadLocal '%s' set to '%s' in pooled thread '%s' — "
                + "child threads forked from this pool thread will inherit this value (cross-task contamination)",
                name, value, t.getName()));
        }
    }

    /**
     * Analyze for {@code InheritableThreadLocal} misuse.
     *
     * @return report describing detected issues
     */
    public InheritableThreadLocalReport analyze() {
        InheritableThreadLocalReport report = new InheritableThreadLocalReport();
        report.pooledGetIssues.addAll(pooledGetIssues);
        report.pooledSetIssues.addAll(pooledSetIssues);

        // Context, not a finding. An InheritableThreadLocal exists so that a child thread
        // inherits the parent's value, so several threads seeing one is the mechanism working
        // rather than evidence against it - and under @AsyncTest, where N workers share a static
        // holder, it was true in every run. The two findings above are the grounded ones: both
        // need the caller to have declared which threads are pooled, which is the situation this
        // detector's javadoc is actually about (#517).
        for (Map.Entry<String, Set<Long>> entry : accessingThreads.entrySet()) {
            if (entry.getValue().size() > 1) {
                report.threadActivity.put(entry.getKey(), String.format(
                    "accessed by %d threads", entry.getValue().size()));
            }
        }
        return report;
    }

    private static String resolved(String name, Object itl) {
        return name != null ? name : "itl@" + System.identityHashCode(itl);
    }

    /** Report produced by {@link #analyze()}. */
    public static class InheritableThreadLocalReport {
        final List<String> pooledGetIssues  = new ArrayList<>();
        final List<String> pooledSetIssues  = new ArrayList<>();
        /**
         * How many threads touched each variable. Reported for context and deliberately not part
         * of {@link #hasIssues()}: inheritance across threads is what an
         * {@code InheritableThreadLocal} is for (#517).
         */
        final Map<String, String> threadActivity = new java.util.LinkedHashMap<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !pooledGetIssues.isEmpty()
                || !pooledSetIssues.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("INHERITABLE THREAD LOCAL MISUSE DETECTED:\n");
            for (String issue : pooledGetIssues)   sb.append("  - ").append(issue).append("\n");
            for (String issue : pooledSetIssues)   sb.append("  - ").append(issue).append("\n");
            for (Map.Entry<String, String> a : threadActivity.entrySet()) {
                sb.append("  . ").append(a.getKey())
                  .append(": ").append(a.getValue()).append(System.lineSeparator());
            }
            sb.append("""
  Why: InheritableThreadLocal copies values at thread-creation time, not at task-submission time.
       In a thread pool the threads are created once at pool startup, so every task inherits the
       creator's context from that point — not the submitter's current context. This causes stale or
       cross-task context contamination that is extremely hard to reproduce.
  Fix:
    - Pass context explicitly as a method parameter or embed it in the task object
    - Use ScopedValue (Java 21+) with ScopedValue.where(KEY, value).run(task) — inherits correctly per call
    - If InheritableThreadLocal is unavoidable, wrap submissions to copy the value into the task closure\
""");
            return sb.toString();
        }
    }
}
