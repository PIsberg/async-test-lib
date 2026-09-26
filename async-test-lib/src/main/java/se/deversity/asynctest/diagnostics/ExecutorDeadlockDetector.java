package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects self-deadlock patterns in single-thread or bounded executors.
 *
 * <p>The finding is that every worker of the pool was waiting on a sibling task <em>at the same
 * moment</em> while work was still queued: nothing left to run the queue, so nothing the workers
 * wait for can start. Waits that took turns never add up to that, however many there were, so a
 * wait is counted only while it lasts. It ends at {@link #recordSiblingWaitEnded(Object)}, or when
 * the thread that recorded it records its task completed. A worker that never ends its wait, the
 * deadlock itself, keeps counting.
 *
 * <p>Reachable from a test via {@code AsyncTestContext.executorDeadlockDetector()} when
 * {@link se.deversity.asynctest.DetectorType#EXECUTOR_DEADLOCK} is enabled.
 */
public class ExecutorDeadlockDetector {

    private static class ExecutorState {
        final String name;
        final int maxThreads;
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        /** Workers waiting on a sibling right now, not waits ever recorded. */
        final AtomicInteger waitingNow = new AtomicInteger();
        /**
         * Open waits per thread, so a completion ends only a wait its own thread recorded. Only the
         * thread a key names ever changes its entry.
         */
        final Map<Thread, Integer> openWaits = new ConcurrentHashMap<>();
        /** The most workers seen waiting at once while work was queued; 0 until the pool saturates. */
        final AtomicInteger saturatedWaiters = new AtomicInteger();
        /** The queue depth at that moment, for the report. */
        volatile int queuedWhenSaturated;

        ExecutorState(String name, int maxThreads) {
            this.name = name;
            this.maxThreads = maxThreads;
        }

        int queued() {
            // Submitted minus running counted every completed task as still queued, so after the
            // first completion the finding degenerated to a lifetime count of sibling waits.
            return Math.max(0, submitted.get() - running.get() - completed.get());
        }

        /** Keeps the moment every worker was waiting with work queued, if this is one. */
        void noteIfSaturated() {
            int waiting = waitingNow.get();
            int queued = queued();
            if (waiting >= maxThreads && queued > 0
                    && saturatedWaiters.getAndAccumulate(waiting, Math::max) < waiting) {
                queuedWhenSaturated = queued;
            }
        }

        boolean endWaitOf(Thread thread) {
            boolean[] ended = {false};
            openWaits.computeIfPresent(thread, (waiter, open) -> {
                ended[0] = true;
                return open == 1 ? null : open - 1;
            });
            if (ended[0]) {
                waitingNow.decrementAndGet();
            }
            return ended[0];
        }
    }

    private final Map<IdentityKey, ExecutorState> executors = new ConcurrentHashMap<>();
    /**
     * Registers executor for tracking.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param name a label identifying the executor in the report
     * @param maxThreads the configured maximum thread count
     */
    public void registerExecutor(Object executor, String name, int maxThreads) {
        if (executor == null) {
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
            state.submitted.incrementAndGet();
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
            state.running.incrementAndGet();
        }
    }
    /**
     * Records that the calling thread, a worker of {@code executor}, now waits on a sibling task
     * submitted to the same executor. The wait counts until the same thread calls
     * {@link #recordSiblingWaitEnded(Object)} or {@link #recordTaskCompleted(Object)}.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordWaitingOnSibling(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.openWaits.merge(Thread.currentThread(), 1, Integer::sum);
            state.waitingNow.incrementAndGet();
            state.noteIfSaturated();
        }
    }
    /**
     * Records that the wait the calling thread recorded with
     * {@link #recordWaitingOnSibling(Object)} is over: the sibling finished, or the wait gave up.
     * Without it the wait lasts until this thread records its task completed. Does nothing when the
     * calling thread has no open wait on {@code executor}.
     *
     * @param executor the executor being recorded, tracked by identity
     * @since 1.12.3
     */
    public void recordSiblingWaitEnded(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.endWaitOf(Thread.currentThread());
        }
    }
    /**
     * Records task completed so it can be analysed at the end of the run. A task that completes is
     * no longer waiting, so this also ends a sibling wait the calling thread left open.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskCompleted(Object executor) {
        ExecutorState state = stateFor(executor);
        if (state != null) {
            state.endWaitOf(Thread.currentThread());
            state.running.updateAndGet(current -> Math.max(0, current - 1));
            state.completed.incrementAndGet();
        }
    }

    private @Nullable ExecutorState stateFor(Object executor) {
        return executor == null ? null : executors.get(new IdentityKey(executor));
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public ExecutorDeadlockReport analyze() {
        ExecutorDeadlockReport report = new ExecutorDeadlockReport();

        for (ExecutorState state : executors.values()) {
            // The state at analysis counts too: waits still open now are waits that never ended.
            state.noteIfSaturated();
            if (state.saturatedWaiters.get() > 0) {
                report.selfDeadlocks.add(String.format(Locale.ROOT,
                    "%s: all %d worker(s) are waiting on sibling tasks while %d task(s) remain queued",
                    state.name,
                    state.maxThreads,
                    state.queuedWhenSaturated
                ));
            }
        }

        return report;
    }

    public static class ExecutorDeadlockReport {
        /** Tasks that blocked waiting for another task on the same single-threaded executor. */
        public final Set<String> selfDeadlocks = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !selfDeadlocks.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No executor self-deadlocks detected.";
            }

            StringBuilder sb = new StringBuilder("EXECUTOR SELF-DEADLOCK DETECTED:\n");
            for (String issue : selfDeadlocks) {
                sb.append("  - ").append(issue).append('\n');
            }
            sb.append("  Fix: do not wait on sibling tasks from the same bounded executor");
            return sb.toString();
        }
    }
}
