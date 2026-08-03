package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Detects thread leaks in concurrent code.
 *
 * Thread leaks occur when threads are created but never terminated, leading to
 * resource exhaustion and memory leaks. This is common with:
 * - {@code new Thread().start()} without corresponding join()
 * - Executor services not properly shut down
 * - Background threads that outlive their intended lifecycle
 *
 * <p>The detector tracks thread creation and termination events, then reports
 * threads that were started but never joined or terminated at test completion.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectThreadLeaks = true)
 * void testThreadLeak() {
 *     Thread backgroundThread = new Thread(() -> {
 *         while (!Thread.interrupted()) {
 *             // work
 *         }
 *     });
 *     AsyncTestContext.threadLeakDetector()
 *         .recordThreadStart(backgroundThread, "background-worker");
 *     backgroundThread.start();
 *
 *     // ... test logic ...
 *
 *     backgroundThread.interrupt();
 *     AsyncTestContext.threadLeakDetector()
 *         .recordThreadEnd(backgroundThread);
 * }
 * }</pre>
 *
 * <p>Automatic detection mode also monitors {@code Thread.activeCount()}
 * growth across invocations to detect leaked threads.
 */
public class ThreadLeakDetector {

    private static final class ThreadState {
        final String name;
        final long startTime;
        final StackTraceElement[] creationStack;
        // Descriptive name (above) is captured at registration and is all the report
        // needs once terminated, so the Thread itself can be dropped below without
        // losing any information reports rely on.
        volatile @Nullable Thread thread;
        volatile boolean terminated = false;

        ThreadState(Thread thread, String name) {
            this.thread = thread;
            this.name = name;
            this.startTime = System.currentTimeMillis();
            this.creationStack = Thread.currentThread().getStackTrace();
        }
    }

    /**
     * Allowed slack between the initial and current active thread count before
     * auto mode reports a leak. JVM housekeeping threads (GC, JIT compiler, etc.)
     * can transiently come and go, so a small variance is tolerated to avoid
     * false positives.
     */
    private static final int THREAD_COUNT_VARIANCE_ALLOWANCE = 2;

    private final Map<Integer, ThreadState> trackedThreads = new ConcurrentHashMap<>();
    private final AtomicInteger initialThreadCount = new AtomicInteger(0);
    private volatile int maxThreadCount = 0;
    private volatile boolean enabled = true;
    private volatile boolean autoMode = false;

    /**
     * Enable automatic thread counting mode.
     * Monitors Thread.activeCount() growth across invocations.
     */
    public void enableAutoMode() {
        this.autoMode = true;
        initialThreadCount.set(Thread.activeCount());
        maxThreadCount = Thread.activeCount();
    }

    /**
     * Record a thread start for tracking.
     *
     * @param thread the thread that was started
     * @param name a descriptive name for reporting
     */
    public void recordThreadStart(Thread thread, String name) {
        if (!enabled || thread == null) {
            return;
        }
        trackedThreads.put(System.identityHashCode(thread), new ThreadState(thread, name));
        int currentCount = Thread.activeCount();
        if (currentCount > maxThreadCount) {
            maxThreadCount = currentCount;
        }
    }

    /**
     * Record a thread ending normally.
     *
     * @param thread the thread that is ending
     */
    public void recordThreadEnd(Thread thread) {
        if (!enabled || thread == null) {
            return;
        }
        ThreadState state = trackedThreads.get(System.identityHashCode(thread));
        if (state != null) {
            state.terminated = true;
            // Drop the strong Thread reference now that it's accounted for; the
            // descriptive name was captured at registration time so reports remain accurate.
            state.thread = null;
        }
    }

    /**
     * Analyze thread usage and detect leaks.
     *
     * @return a report of thread leaks detected
     */
    public ThreadLeakReport analyzeLeaks() {
        if (!enabled) {
            return new ThreadLeakReport(List.of(), 0, 0, 0, false);
        }

        List<ThreadLeakEvent> leaks = new ArrayList<>();

        // Check tracked threads
        for (ThreadState state : trackedThreads.values()) {
            if (!state.terminated) {
                // Snapshot the volatile reference once: a concurrent recordThreadEnd()
                // could otherwise null it out between the terminated check and this read.
                Thread trackedThread = state.thread;
                if (trackedThread != null && trackedThread.isAlive()) {
                    leaks.add(new ThreadLeakEvent(
                        state.name,
                        trackedThread,
                        state.startTime,
                        state.creationStack,
                        "Thread started but never terminated (still alive)"
                    ));
                }
            }
        }

        // Auto mode: check thread count growth
        if (autoMode) {
            int currentCount = Thread.activeCount();
            if (currentCount > initialThreadCount.get() + THREAD_COUNT_VARIANCE_ALLOWANCE) {
                leaks.add(new ThreadLeakEvent(
                    "global-thread-count",
                    null,
                    System.currentTimeMillis(),
                    null,
                    String.format("Active thread count grew from %d to %d (possible thread leak)",
                        initialThreadCount.get(), currentCount)
                ));
            }
        }

        return new ThreadLeakReport(
            leaks,
            trackedThreads.size(),
            (int) trackedThreads.values().stream().filter(s -> s.terminated).count(),
            maxThreadCount,
            autoMode
        );
    }

    /**
     * Analyze thread usage and detect leaks.
     *
     * @return a report of thread leaks detected
     */
    public ThreadLeakReport analyze() {
        return analyzeLeaks();
    }

    /**
     * Clear all tracked thread data.
     */
    public void clear() {
        trackedThreads.clear();
        maxThreadCount = 0;
    }
    /**
     * Disable.
     */
    public void disable() {
        this.enabled = false;
    }

    /**
     * A thread leak event.
     */
    public static class ThreadLeakEvent {
        /** Label identifying the sleeping thread in the report. */
        public final String threadName;
        /** The thread being tracked; cleared once it terminates. */
        public final @Nullable Thread thread;
        /** When the thread was started, in nanoseconds. */
        public final long startTime;
        public final StackTraceElement @Nullable [] creationStack;
        /** Why the thread was flagged, shown in the report. */
        public final String reason;

        ThreadLeakEvent(String threadName, @Nullable Thread thread, long startTime,
                       StackTraceElement @Nullable [] creationStack, String reason) {
            this.threadName = threadName;
            this.thread = thread;
            this.startTime = startTime;
            this.creationStack = creationStack;
            this.reason = reason;
        }
    }

    /**
     * Report of thread leak analysis.
     */
    public static class ThreadLeakReport {
        private final List<ThreadLeakEvent> leaks;
        private final int totalTracked;
        private final int terminated;
        private final int maxThreadCount;
        private final boolean autoMode;

        ThreadLeakReport(List<ThreadLeakEvent> leaks, int totalTracked, int terminated,
                        int maxThreadCount, boolean autoMode) {
            this.leaks = leaks;
            this.totalTracked = totalTracked;
            this.terminated = terminated;
            this.maxThreadCount = maxThreadCount;
            this.autoMode = autoMode;
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !leaks.isEmpty();
        }

        /**
         * {@return the leaks}
         */
        public List<ThreadLeakEvent> getLeaks() {
            return List.copyOf(leaks);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ThreadLeakReport:\n");
            sb.append("  Mode: ").append(autoMode ? "Automatic" : "Manual").append("\n");
            sb.append("  Total tracked: ").append(totalTracked).append("\n");
            sb.append("  Terminated: ").append(terminated).append("\n");
            sb.append("  Max thread count: ").append(maxThreadCount).append("\n");

            if (leaks.isEmpty()) {
                sb.append("  Status: No thread leaks detected ✓\n");
            } else {
                sb.append("  THREAD LEAKS DETECTED:\n");
                for (int i = 0; i < leaks.size(); i++) {
                    ThreadLeakEvent leak = leaks.get(i);
                    sb.append("  [").append(i + 1).append("] ").append(leak.threadName).append("\n");
                    sb.append("      Reason: ").append(leak.reason).append("\n");
                    if (leak.thread != null) {
                        sb.append("      State: ").append(leak.thread.getState()).append("\n");
                        sb.append("      ID: ").append(leak.thread.threadId()).append("\n");
                    }
                    if (leak.creationStack != null && leak.creationStack.length > 3) {
                        sb.append("      Created at:\n");
                        for (int j = 3; j < Math.min(6, leak.creationStack.length); j++) {
                            sb.append("        at ").append(leak.creationStack[j]).append("\n");
                        }
                    }
                    sb.append("      Why: Threads that outlive the test consume OS resources (stack memory, file descriptors) and may\n");
                    sb.append("           interfere with subsequent tests by writing to shared state, holding locks, or preventing JVM exit.\n");
                    sb.append("      Fix:\n");
                    sb.append("        - Daemon threads: set thread.setDaemon(true) so the JVM terminates them automatically on exit\n");
                    sb.append("        - Managed threads: call thread.join() or executor.shutdown() + awaitTermination() in a finally block\n");
                    sb.append("        - Use try-with-resources if the executor implements AutoCloseable (Java 19+ ExecutorService)\n");
                    sb.append("        - Pass a stop signal via a volatile boolean or interrupt: thread.interrupt() + check Thread.interrupted()\n");
                }
            }
            return sb.toString();
        }
    }
}
