package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects blocking waits on sibling futures inside bounded executors.
 *
 * <p>The finding is that every worker of the pool was blocked on a future <em>at the same
 * moment</em> while tasks were still queued. Blocking waits that took turns never add up to that,
 * so a wait is counted only while it lasts: it ends at {@link #recordBlockingWaitEnded(Object)},
 * or when the thread that recorded it records its task completed.
 *
 * <p>Reachable from a test via {@code AsyncTestContext.futureBlockingDetector()} when
 * {@link se.deversity.asynctest.DetectorType#FUTURE_BLOCKING} is enabled.
 */
public class FutureBlockingDetector {

    private static class ExecutorState {
        final String name;
        final int maxThreads;
        final AtomicInteger submittedTasks = new AtomicInteger();
        final AtomicInteger runningTasks = new AtomicInteger();
        final AtomicInteger completedTasks = new AtomicInteger();
        /** Workers blocked on a future right now, not blocking waits ever recorded. */
        final AtomicInteger blockedNow = new AtomicInteger();
        /** Open waits per thread; only the thread a key names ever changes its entry. */
        final Map<Thread, Integer> openWaits = new ConcurrentHashMap<>();
        /** The most workers seen blocked at once while tasks were queued; 0 until then. */
        final AtomicInteger saturatedBlocked = new AtomicInteger();
        /** The queue depth at that moment, for the report. */
        volatile int queuedWhenSaturated;

        ExecutorState(String name, int maxThreads) {
            this.name = name;
            this.maxThreads = maxThreads;
        }

        int queued() {
            // Completed tasks are not queued; see ExecutorDeadlockDetector for the same defect.
            return Math.max(0, submittedTasks.get() - runningTasks.get() - completedTasks.get());
        }

        /** Keeps the moment every worker was blocked with tasks queued, if this is one. */
        void noteIfSaturated() {
            int blocked = blockedNow.get();
            int queued = queued();
            if (blocked >= maxThreads && queued > 0
                    && saturatedBlocked.getAndAccumulate(blocked, Math::max) < blocked) {
                queuedWhenSaturated = queued;
            }
        }

        void endWaitOf(Thread thread) {
            boolean[] ended = {false};
            openWaits.computeIfPresent(thread, (waiter, open) -> {
                ended[0] = true;
                return open == 1 ? null : open - 1;
            });
            if (ended[0]) {
                blockedNow.decrementAndGet();
            }
        }
    }

    private final Map<IdentityKey, ExecutorState> executors = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    /**
     * Disable.
     */
    public void disable() { enabled = false; }
    /**
     * Enable.
     */
    public void enable()  { enabled = true;  }
    /**
     * Registers executor for tracking.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param name a label identifying the executor in the report
     * @param maxThreads the configured maximum thread count
     */
    public void registerExecutor(Object executor, String name, int maxThreads) {
        if (!enabled || executor == null) {
            return;
        }
        executors.putIfAbsent(new IdentityKey(executor),
            new ExecutorState(name == null || name.isBlank() ? "Executor" : name, maxThreads));
    }
    /**
     * Records task submitted so it can be analysed at the end of the run.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskSubmitted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.submittedTasks.incrementAndGet();
            state.noteIfSaturated();
        }
    }
    /**
     * Records task started so it can be analysed at the end of the run.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskStarted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.runningTasks.incrementAndGet();
        }
    }
    /**
     * Records that the calling thread, a worker of {@code executor}, now blocks on a future whose
     * task runs on the same executor. The wait counts until the same thread calls
     * {@link #recordBlockingWaitEnded(Object)} or {@link #recordTaskCompleted(Object)}.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordBlockingWait(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.openWaits.merge(Thread.currentThread(), 1, Integer::sum);
            state.blockedNow.incrementAndGet();
            state.noteIfSaturated();
        }
    }
    /**
     * Records that the blocking wait the calling thread recorded with
     * {@link #recordBlockingWait(Object)} is over: the future completed, or the wait timed out.
     * Without it the wait lasts until this thread records its task completed. Does nothing when the
     * calling thread has no open wait on {@code executor}.
     *
     * @param executor the executor being recorded, tracked by identity
     * @since 1.12.3
     */
    public void recordBlockingWaitEnded(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.endWaitOf(Thread.currentThread());
        }
    }
    /**
     * Records task completed so it can be analysed at the end of the run. A task that completes is
     * no longer blocked, so this also ends a blocking wait the calling thread left open.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskCompleted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.endWaitOf(Thread.currentThread());
            state.runningTasks.updateAndGet(current -> Math.max(0, current - 1));
            state.completedTasks.incrementAndGet();
        }
    }

    private @Nullable ExecutorState stateFor(Object executor) {
        if (!enabled || executor == null) {
            return null;
        }
        return executors.get(new IdentityKey(executor));
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public FutureBlockingReport analyze() {
        FutureBlockingReport report = new FutureBlockingReport();

        for (ExecutorState state : executors.values()) {
            // The state at analysis counts too: waits still open now are waits that never ended.
            state.noteIfSaturated();
            // Waits recorded off the pool's own threads can outnumber it; the pool has maxThreads.
            int blocked = Math.min(state.saturatedBlocked.get(), state.maxThreads);
            if (blocked > 0) {
                report.starvationRisks.add(String.format(Locale.ROOT,
                    "%s: %d/%d workers blocked waiting on futures while %d task(s) remain queued",
                    state.name,
                    blocked,
                    state.maxThreads,
                    state.queuedWhenSaturated
                ));
            }
        }

        return report;
    }

    public static class FutureBlockingReport {
        /** Blocking calls made from a pool thread, which can exhaust the pool. */
        public final Set<String> starvationRisks = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !starvationRisks.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No future blocking starvation detected.";
            }

            StringBuilder sb = new StringBuilder("FUTURE BLOCKING ISSUES DETECTED:\n");
            for (String issue : starvationRisks) {
                sb.append("  - ").append(issue).append('\n');
            }
            sb.append("""
  Why: A thread calling Future.get() or CompletableFuture.join() blocks until the task completes.
       If the task was submitted to the same bounded executor whose thread is now blocked, the
       executor has one fewer available thread. When all threads are blocked waiting for queued
       tasks, those tasks can never run — the executor is deadlocked.
  Fix:
    - Submit blocking-wait tasks to a different (unbounded or larger) executor
    - Use non-blocking composition instead: thenApply/thenCompose instead of get()/join()
    - For virtual threads: blocking inside a virtual thread is safe — there is no pool exhaustion\
""");
            return sb.toString();
        }
    }
}
