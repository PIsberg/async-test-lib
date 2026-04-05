package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Automatic detection mode also monitors {@code Thread.getAllThreads()}
 * growth across invocations to detect leaked threads.
 */
public class ThreadLeakDetector {

    private static class ThreadState {
        final String name;
        final Thread thread;
        final long startTime;
        final long startThreadId;
        final StackTraceElement[] creationStack;
        volatile long endTime = 0;
        volatile boolean terminated = false;

        ThreadState(Thread thread, String name) {
            this.thread = thread;
            this.name = name;
            this.startTime = System.currentTimeMillis();
            this.startThreadId = thread.getId();
            this.creationStack = Thread.currentThread().getStackTrace();
        }
    }

    private final Map<Integer, ThreadState> trackedThreads = new ConcurrentHashMap<>();
    private final AtomicInteger initialThreadCount = new AtomicInteger(0);
    private volatile int maxThreadCount = 0;
    private volatile boolean enabled = true;
    private volatile boolean autoMode = false;

    /**
     * Enable automatic thread counting mode.
     * Monitors Thread.getAllThreads() growth across invocations.
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
            state.endTime = System.currentTimeMillis();
            state.terminated = true;
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
                boolean isAlive = state.thread.isAlive();
                if (isAlive) {
                    leaks.add(new ThreadLeakEvent(
                        state.name,
                        state.thread,
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
            if (currentCount > initialThreadCount.get() + 2) { // Allow some variance
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
     * Clear all tracked thread data.
     */
    public void clear() {
        trackedThreads.clear();
        maxThreadCount = 0;
    }

    public void disable() {
        this.enabled = false;
    }

    /**
     * A thread leak event.
     */
    public static class ThreadLeakEvent {
        public final String threadName;
        public final Thread thread;
        public final long startTime;
        public final StackTraceElement[] creationStack;
        public final String reason;

        ThreadLeakEvent(String threadName, Thread thread, long startTime,
                       StackTraceElement[] creationStack, String reason) {
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

        public boolean hasIssues() {
            return !leaks.isEmpty();
        }

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
                    sb.append("      Fix: Ensure thread is properly terminated with join() or executor.shutdown()\n");
                }
            }
            return sb.toString();
        }
    }
}
