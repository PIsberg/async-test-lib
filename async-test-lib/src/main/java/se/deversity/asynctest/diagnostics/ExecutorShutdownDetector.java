package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects an {@link ExecutorService} that the code under test <em>created</em> and then failed to
 * shut down, or shut down without a subsequent {@code awaitTermination()}.
 *
 * <p><strong>Ownership is the whole rule, and you declare it.</strong> This detector reports only
 * executors passed to {@link #recordExecutorCreated}, and that call means "this scope created this
 * executor and is responsible for closing it". {@link #recordTaskSubmitted} on an executor that was
 * never declared is a no-op by design, so a shared static pool, an injected executor owned by the
 * caller, or a framework-managed one draws nothing.
 *
 * <p>That matters because the rule cannot tell the two apart on its own. Not shutting down an
 * executor you were handed is correct, and shutting one down is a bug; nothing in the event stream
 * distinguishes them, so the declaration is the only ownership signal there is. Declaring an
 * executor you did not create makes this detector report correct code.
 *
 * <p><strong>Why it is not fed by the agent.</strong> Weaving would declare every executor in the
 * observed program, which is exactly the case above at scale: the common correct pattern would
 * report, and a tool that reports correct code gets switched off. It was considered for agent
 * feeding alongside the coordination primitives and deliberately left out. The reasoning is in
 * <a href="https://github.com/PIsberg/async-test-lib/issues/387">#387</a>, and the factory methods
 * an agent would have to intercept are {@code invokestatic}, which the weaver does not rewrite.
 *
 * <p><strong>Why it stays {@code PROMPT}.</strong> A finding is only as good as the declaration
 * behind it. Where a detector at {@code VERDICT} says the code is wrong, this one says the author
 * said they owned this executor and did not close it, which is worth reading and not worth failing
 * a merge on by itself.
 *
 * <p>Issues detected:
 * <ul>
 *   <li>a declared executor had tasks submitted and {@code shutdown()} was never called, so its
 *       threads outlive the test</li>
 *   <li>a declared executor was shut down without {@code awaitTermination()}, so submitted tasks
 *       may be abandoned or still running when the test finishes</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectExecutorShutdown = true)
 * void testExecutorLifecycle() {
 *     ExecutorService ex = Executors.newFixedThreadPool(2);   // created here, so owned here
 *     AsyncTestContext.executorShutdownMonitor().recordExecutorCreated(ex, "my-pool");
 *     ex.submit(() -> doWork());
 *     AsyncTestContext.executorShutdownMonitor().recordTaskSubmitted(ex);
 *     // Missing: ex.shutdown() + awaitTermination -> will be detected
 * }
 * }</pre>
 */
public class ExecutorShutdownDetector {

    private static class ExecutorState {
        final String name;
        final AtomicInteger tasksSubmitted = new AtomicInteger(0);
        volatile boolean shutdownCalled = false;
        volatile boolean awaitTerminationCalled = false;

        ExecutorState(String name) {
            this.name = name;
        }
    }

    private final Map<Integer, ExecutorState> executors = new ConcurrentHashMap<>();

    /**
     * Declare that this scope created {@code executor} and owns shutting it down.
     *
     * <p>This is the detector's only ownership signal, and everything it reports is gated on it:
     * an executor never passed here is not tracked, and {@link #recordTaskSubmitted} for it does
     * nothing. Pass an executor you were handed rather than created and this detector will report
     * you for not closing something that is not yours to close.
     *
     * @param executor the executor this scope created (null-safe)
     * @param name     a descriptive label for reports; if null uses identity hash
     */
    public void recordExecutorCreated(ExecutorService executor, String name) {
        if (executor == null) return;
        String resolved = name != null ? name : "executor@" + System.identityHashCode(executor);
        // computeIfAbsent, not put: re-declaring an executor already tracked would reset
        // tasksSubmitted and shutdownCalled, and analyze() gates on both.
        executors.computeIfAbsent(System.identityHashCode(executor), k -> new ExecutorState(resolved));
    }

    /**
     * Record a task submission ({@code submit}, {@code execute}, {@code invokeAll}, etc.).
     *
     * @param executor the executor tasks were submitted to
     */
    public void recordTaskSubmitted(ExecutorService executor) {
        if (executor == null) return;
        ExecutorState state = executors.get(System.identityHashCode(executor));
        if (state != null) state.tasksSubmitted.incrementAndGet();
    }

    /**
     * Record that {@code shutdown()} or {@code shutdownNow()} was called.
     *
     * @param executor               the executor being shut down
     * @param withAwaitTermination   {@code true} if {@code awaitTermination()} is called
     *                               immediately after in the same block (convenience shortcut)
     */
    public void recordShutdownCalled(ExecutorService executor, boolean withAwaitTermination) {
        if (executor == null) return;
        ExecutorState state = executors.get(System.identityHashCode(executor));
        if (state != null) {
            state.shutdownCalled = true;
            if (withAwaitTermination) state.awaitTerminationCalled = true;
        }
    }

    /**
     * Record that {@code awaitTermination()} was called on the executor.
     *
     * @param executor the executor being awaited
     */
    public void recordAwaitTerminationCalled(ExecutorService executor) {
        if (executor == null) return;
        ExecutorState state = executors.get(System.identityHashCode(executor));
        if (state != null) state.awaitTerminationCalled = true;
    }

    /**
     * Analyze registered executors for shutdown issues.
     *
     * @return report describing any issues found
     */
    public ExecutorShutdownReport analyze() {
        ExecutorShutdownReport report = new ExecutorShutdownReport();
        for (ExecutorState state : executors.values()) {
            if (state.tasksSubmitted.get() > 0 && !state.shutdownCalled) {
                report.notShutDown.add(String.format(
                    "%s: %d task(s) submitted but shutdown() never called — threads will leak",
                    state.name, state.tasksSubmitted.get()));
            } else if (state.shutdownCalled && !state.awaitTerminationCalled) {
                report.noAwaitTermination.add(String.format(
                    "%s: shutdown() called but awaitTermination() never called — "
                    + "in-flight tasks may be abandoned when the test ends",
                    state.name));
            }
        }
        return report;
    }

    /** Report produced by {@link #analyze()}. */
    public static class ExecutorShutdownReport {
        final List<String> notShutDown       = new ArrayList<>();
        final List<String> noAwaitTermination = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !notShutDown.isEmpty() || !noAwaitTermination.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("EXECUTOR SHUTDOWN ISSUES DETECTED:\n");
            for (String issue : notShutDown)        sb.append("  - ").append(issue).append("\n");
            for (String issue : noAwaitTermination) sb.append("  - ").append(issue).append("\n");
            sb.append("""
  Why: An executor that is never shut down keeps its worker threads alive indefinitely. Those threads
       are non-daemon by default, so they prevent JVM exit and consume OS thread resources. Without
       awaitTermination(), in-flight tasks may be interrupted mid-execution when the test ends, causing
       partial writes, corrupted state, or misleading test failures.
  Fix:
    - Always call shutdown() then awaitTermination(timeout, unit) in a finally block
    - Java 19+: ExecutorService implements AutoCloseable — use try-with-resources for automatic shutdown
    - Use shutdownNow() only when you need to cancel in-flight tasks; handle the returned pending-task list\
""");
            return sb.toString();
        }
    }
}
