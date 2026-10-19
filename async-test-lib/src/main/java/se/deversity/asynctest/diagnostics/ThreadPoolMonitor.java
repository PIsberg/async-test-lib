package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitors thread pool / executor health and issues.
 * 
 * Problems detected:
 * - Queue saturation
 * - Task rejection
 * - Worker thread starvation
 * - Queue imbalance across workers
 * - Long-running tasks blocking others
 *
 * <p>With the agent attached, task rejection needs no recording call: the woven {@code submit} and
 * {@code execute} record a {@code RejectedExecutionException} as it passes through, before it is
 * rethrown, so a rejection the calling code drops is still reported (#1055). Such a pool's bounds
 * stay undeclared, so the rules that compare against a bound skip it (#501). The report grades
 * each finding by the path behind it (#1061): see {@link ThreadPoolReport#grades()}.
 */
public class ThreadPoolMonitor extends AbstractInstanceDetector<ThreadPoolMonitor.PoolState> {

    /** Longest task duration, in milliseconds, above which a pool is reported for blocking tasks (#756). */
    private static final long LONG_TASK_THRESHOLD_MS = 10_000;
    
    static final class PoolState {
        final String poolName;
        final int maxSize;
        final int queueCapacity;
        final AtomicInteger activeThreads = new AtomicInteger(0);
        final AtomicInteger completedTasks = new AtomicInteger(0);
        final AtomicInteger rejectedTasks = new AtomicInteger(0);
        /** The rejections the executor itself threw, of {@code rejectedTasks}; see {@link #recordRejectedExecution}. */
        final AtomicInteger thrownRejections = new AtomicInteger(0);
        final AtomicInteger queuedTasks = new AtomicInteger(0);
        volatile long peakQueueSize = 0;
        volatile long maxTaskDuration = 0;
        final List<String> rejections = Collections.synchronizedList(new ArrayList<>());
        /**
         * Whether {@code maxSize} and {@code queueCapacity} came from a caller.
         *
         * <p>False for a pool this monitor invented on a rejection it was told about without a
         * prior registration. Both bounds are 0 there, which is not "a pool of zero threads with
         * a queue of zero" - it is "nobody said". The rules that compare against a bound are
         * skipped for such a pool, because 0 >= 0 made every one of them report "All 0 threads
         * busy" on a pool whose real size nothing here knows (#501).
         */
        final boolean boundsDeclared;

        PoolState(String name, int max, int queue) {
            this(name, max, queue, true);
        }

        PoolState(String name, int max, int queue, boolean boundsDeclared) {
            this.poolName = name;
            this.maxSize = max;
            this.queueCapacity = queue;
            this.boundsDeclared = boundsDeclared;
        }
    }
    
    private volatile boolean enabled = true;
    
    /**
     * Register a thread pool for monitoring.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param name a label identifying the executor in the report
     * @param coreSize the configured core pool size
     * @param maxSize the configured maximum pool size
     * @param queueCapacity the configured work-queue capacity
     */
    public void registerPool(Object executor, String name, int coreSize, int maxSize, int queueCapacity) {
        if (!enabled || executor == null) return;
        
        stateFor(executor, name, "pool", label -> new PoolState(label, maxSize, queueCapacity));
    }
    
    /**
     * Record task submission.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskSubmitted(Object executor) {
        if (!enabled || executor == null) return;
        
        PoolState state = trackedState(executor);
        if (state == null) return;
        
        state.queuedTasks.incrementAndGet();
        state.peakQueueSize = Math.max(state.peakQueueSize, state.queuedTasks.get());
    }
    
    /**
     * Record task execution start.
     *
     * @param executor the executor being recorded, tracked by identity
     */
    public void recordTaskStarted(Object executor) {
        if (!enabled || executor == null) return;
        
        PoolState state = trackedState(executor);
        if (state == null) return;
        
        state.activeThreads.incrementAndGet();
        state.queuedTasks.decrementAndGet();
    }
    
    /**
     * Record task completion.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param durationMs the duration in milliseconds
     */
    public void recordTaskCompleted(Object executor, long durationMs) {
        if (!enabled || executor == null) return;
        
        PoolState state = trackedState(executor);
        if (state == null) return;
        
        state.activeThreads.decrementAndGet();
        state.completedTasks.incrementAndGet();
        state.maxTaskDuration = Math.max(state.maxTaskDuration, durationMs);
    }
    
    /**
     * Record task rejection.
     *
     * @param executor the executor being recorded, tracked by identity
     * @param reason why the event was recorded, shown in the report
     */
    public void recordTaskRejected(Object executor, String reason) {
        if (!enabled || executor == null) return;
        
        PoolState state = trackedState(executor);
        if (state == null) {
            state = stateFor(executor, "Unregistered pool", "pool", label -> new PoolState(label, 0, 0, false));
        }
        
        state.rejectedTasks.incrementAndGet();
        state.rejections.add(reason + " (queued: " + state.queuedTasks.get() + ")");
    }
    
    /**
     * Records a submission the executor refused, as the woven {@code submit} and {@code execute}
     * see it: the executor and the {@link RejectedExecutionException} it threw, recorded before
     * the hook rethrows it (#1055). Where {@link #recordTaskRejected} is the test saying a
     * rejection happened, this is the executor's own answer, so the report grades the finding on
     * {@link DetectorTrust.Evidence#OBSERVED} evidence (#1061). A pool first seen here is
     * labelled by its class and has no declared bounds.
     *
     * @param executor  the executor that threw, tracked by identity
     * @param rejection what it threw
     * @since 1.13.3
     */
    public void recordRejectedExecution(Object executor, RejectedExecutionException rejection) {
        if (!enabled || executor == null || rejection == null) return;

        PoolState state = trackedState(executor);
        if (state == null) {
            state = stateFor(executor, null, executor.getClass().getSimpleName(),
                    label -> new PoolState(label, 0, 0, false));
        }

        state.thrownRejections.incrementAndGet();
        state.rejectedTasks.incrementAndGet();
        state.rejections.add("the executor threw " + rejection.getClass().getSimpleName()
                + " (queued: " + state.queuedTasks.get() + ")");
    }

    /**
     * Analyze pool health.
     *
     * @return the findings this detector collected during the run
     */
    public ThreadPoolReport analyzePoolHealth() {
        ThreadPoolReport report = new ThreadPoolReport();
        
        for (PoolState state : states()) {
            if (state.rejectedTasks.get() > 0) {
                String finding = String.format("%s: %d tasks rejected",
                    state.poolName, state.rejectedTasks.get());
                report.poolsWithRejections.add(finding);
                if (state.thrownRejections.get() > 0) {
                    report.thrown.add(finding);
                }
            }
            
            if (state.boundsDeclared && state.peakQueueSize > state.queueCapacity * 0.8) {
                report.saturatedQueues.add(String.format(
                    "%s: Queue near capacity (peak: %d)",
                    state.poolName, state.peakQueueSize
                ));
            }
            
            if (state.maxTaskDuration > LONG_TASK_THRESHOLD_MS) {
                report.longRunningTasks.add(String.format(
                    "%s: Max task duration %dms (may block other tasks)",
                    state.poolName, state.maxTaskDuration
                ));
            }
            
            if (state.boundsDeclared && state.activeThreads.get() >= state.maxSize) {
                report.threadStarvation.add(String.format(
                    "%s: All %d threads busy (queue depth: %d)",
                    state.poolName, state.maxSize, state.queuedTasks.get()
                ));
            }
        }
        
        if (report.hasIssues()) {
            IssueSeverity severity = report.severity();
            for (String finding : report.poolsWithRejections) {
                report.structuredViolations.add(new Violation("ThreadPoolMonitor", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.saturatedQueues) {
                report.structuredViolations.add(new Violation("ThreadPoolMonitor", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.longRunningTasks) {
                report.structuredViolations.add(new Violation("ThreadPoolMonitor", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
            for (String finding : report.threadStarvation) {
                report.structuredViolations.add(new Violation("ThreadPoolMonitor", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    /**
     * Standardized alias for {@link #analyzePoolHealth()}.
     *
     * @return the findings this detector collected during the run
     */
    public ThreadPoolReport analyze() {
        return analyzePoolHealth();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        clearStates();
    }
    /**
     * Disable.
     */
    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */
    public void enable() {
        enabled = true;
    }
    
    public static class ThreadPoolReport implements GradedFindings {
        /** Pools that rejected at least one submission. */
        public final Set<String> poolsWithRejections = new HashSet<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        /** Work queues observed at their capacity. */
        public final Set<String> saturatedQueues = new HashSet<>();
        /** Tasks that ran past the reporting threshold. */
        public final Set<String> longRunningTasks = new HashSet<>();
        /** Pools where work waited because no worker was free. */
        public final Set<String> threadStarvation = new HashSet<>();
        /** The rejection findings at least one of which the executor itself threw; see {@link #grades()}. */
        final Set<String> thrown = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !poolsWithRejections.isEmpty() || !saturatedQueues.isEmpty() || 
                   !longRunningTasks.isEmpty() || !threadStarvation.isEmpty();
        }
        
        /**
         * The severity the {@code failOn} gate read from this text before #801: a marker in it,
         * else the value DetectorDefaultSeverity declared for the detector.
         */
        IssueSeverity severity() {
            return IssueSeverity.markedIn(toString()).orElse(IssueSeverity.MEDIUM);
        }

        /**
         * One grade per finding, set by the path that produced it (#1061).
         *
         * <p>A rejection the woven {@code submit} or {@code execute} saw the executor throw is the
         * executor's own answer, a {@link TrustTier#FACT} on
         * {@link DetectorTrust.Evidence#OBSERVED} evidence. It stops at FACT because a caller may
         * handle a rejection on purpose, with a retry or a fallback, which the executor cannot
         * tell. A rejection only {@code recordTaskRejected} reported is the test's word, a FACT on
         * {@link DetectorTrust.Evidence#ASSERTED}. A pool with both is graded by the thrown one.
         * The saturated queue, the busy pool and the long task compare recorded counts against a
         * bound or a threshold, so each is a {@link TrustTier#PROMPT} on
         * {@link DetectorTrust.Evidence#HEURISTIC}. Every grade keeps the severity the gate has
         * always read for this report.
         */
        @Override
        public List<GradedFindings.Grade> grades() {
            if (!hasIssues()) {
                return List.of();
            }
            IssueSeverity severity = severity();
            List<GradedFindings.Grade> out = new ArrayList<>();
            for (String rejected : poolsWithRejections) {
                out.add(thrown.contains(rejected)
                        ? new GradedFindings.Grade(severity, TrustTier.FACT, rejected, DetectorTrust.Evidence.OBSERVED)
                        : new GradedFindings.Grade(severity, TrustTier.FACT, rejected, DetectorTrust.Evidence.ASSERTED));
            }
            for (String finding : saturatedQueues) {
                out.add(new GradedFindings.Grade(severity, TrustTier.PROMPT, finding, DetectorTrust.Evidence.HEURISTIC));
            }
            for (String finding : longRunningTasks) {
                out.add(new GradedFindings.Grade(severity, TrustTier.PROMPT, finding, DetectorTrust.Evidence.HEURISTIC));
            }
            for (String finding : threadStarvation) {
                out.add(new GradedFindings.Grade(severity, TrustTier.PROMPT, finding, DetectorTrust.Evidence.HEURISTIC));
            }
            return List.copyOf(out);
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No thread pool issues detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("THREAD POOL ISSUES DETECTED:\n");
            
            if (!poolsWithRejections.isEmpty()) {
                sb.append("\nPools with task rejections:\n");
                for (String issue : poolsWithRejections) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Increase pool size or queue capacity\n");
            }
            
            if (!saturatedQueues.isEmpty()) {
                sb.append("\nSaturated queues:\n");
                for (String issue : saturatedQueues) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            if (!longRunningTasks.isEmpty()) {
                sb.append("\nLong-running tasks:\n");
                for (String issue : longRunningTasks) {
                    sb.append("  - ").append(issue).append("\n");
                }
                sb.append("  Fix: Consider dedicated threads or async/non-blocking patterns\n");
            }
            
            if (!threadStarvation.isEmpty()) {
                sb.append("\nThread starvation:\n");
                for (String issue : threadStarvation) {
                    sb.append("  - ").append(issue).append("\n");
                }
            }
            
            return sb.toString();
        }
    }
}
