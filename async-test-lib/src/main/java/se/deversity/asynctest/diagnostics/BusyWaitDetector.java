package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects spin loops that perform excessive work before yielding or blocking.
 */
public class BusyWaitDetector {

    private static final long SPIN_THRESHOLD_ITERATIONS = 10_000;

    private static final class ThreadActivity {
        long loopIterations;
        long spinStartTime;
        boolean inSpinLoop;
        final List<SpinEvent> spinEvents = new ArrayList<>();
    }

    private static class SpinEvent {
        final long durationMs;
        final long iterations;
        final String location;

        SpinEvent(long durationMs, long iterations, String location) {
            this.durationMs = durationMs;
            this.iterations = iterations;
            this.location = location;
        }

        double iterationsPerMs() {
            return iterations / (double) Math.max(1L, durationMs);
        }
    }

    private final Map<Long, ThreadActivity> threadActivities = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    /**
     * Records loop iteration so it can be analysed at the end of the run.
     */
    public void recordLoopIteration() {
        if (!enabled) {
            return;
        }

        ThreadActivity activity = threadActivities.computeIfAbsent(
            Thread.currentThread().threadId(),
            ignored -> new ThreadActivity()
        );

        synchronized (activity) {
            activity.loopIterations++;
            if (!activity.inSpinLoop && activity.loopIterations >= SPIN_THRESHOLD_ITERATIONS) {
                activity.inSpinLoop = true;
                activity.spinStartTime = System.nanoTime();
            }
        }
    }
    /**
     * Closes the round in progress: a bounded loop that ran in one round must not be summed with
     * the next round's on a reused pool thread and reported as a spin that never yielded. Spin
     * events already recorded are kept.
     *
     * @since 1.11.2
     */
    public void markInvocationStart() {
        for (ThreadActivity activity : threadActivities.values()) {
            synchronized (activity) {
                activity.loopIterations = 0;
                activity.inSpinLoop = false;
            }
        }
    }

    /**
     * Records yield so it can be analysed at the end of the run.
     */
    public void recordYield() {
        if (!enabled) {
            return;
        }

        ThreadActivity activity = threadActivities.computeIfAbsent(
            Thread.currentThread().threadId(),
            ignored -> new ThreadActivity()
        );

        synchronized (activity) {
            long durationMs = activity.inSpinLoop
                ? Math.max(1L, (System.nanoTime() - activity.spinStartTime) / 1_000_000)
                : 0L;

            if (activity.loopIterations >= SPIN_THRESHOLD_ITERATIONS) {
                activity.spinEvents.add(new SpinEvent(durationMs, activity.loopIterations, inferCallSite()));
            }

            activity.loopIterations = 0;
            activity.inSpinLoop = false;
            activity.spinStartTime = 0;
        }
    }
    /**
     * Report spin loop.
     *
     * @param description free text describing the event, shown in the report
     * @param iterations how many iterations the spin ran for
     */
    public void reportSpinLoop(String description, long iterations) {
        if (!enabled) {
            return;
        }

        ThreadActivity activity = threadActivities.computeIfAbsent(
            Thread.currentThread().threadId(),
            ignored -> new ThreadActivity()
        );

        synchronized (activity) {
            activity.spinEvents.add(new SpinEvent(1L, iterations, description));
        }
    }

    private String inferCallSite() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        return trace.length > 3 ? trace[3].toString() : "unknown";
    }
    /**
     * Analyses what has been recorded about busy waiting and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public BusyWaitReport analyzeBusyWaiting() {
        BusyWaitReport report = new BusyWaitReport();

        for (Map.Entry<Long, ThreadActivity> entry : threadActivities.entrySet()) {
            long threadId = entry.getKey();
            ThreadActivity activity = entry.getValue();
            synchronized (activity) {
                for (SpinEvent event : activity.spinEvents) {
                    addToReport(report, threadId, event);
                }

                // A spin loop that never yields or blocks (the worst busy-wait of
                // all, e.g. while(!flag){}) never reaches recordYield(), so its
                // evidence only exists in the live counters. Flush it into the
                // report here — without mutating the activity, so repeated
                // analyze() calls stay idempotent.
                if (activity.loopIterations >= SPIN_THRESHOLD_ITERATIONS) {
                    long durationMs = activity.inSpinLoop
                        ? Math.max(1L, (System.nanoTime() - activity.spinStartTime) / 1_000_000)
                        : 1L;
                    addToReport(report, threadId, new SpinEvent(
                        durationMs, activity.loopIterations,
                        "still spinning at analysis time (never yielded or blocked)"));
                }
            }
        }

        return report;
    }

    private static void addToReport(BusyWaitReport report, long threadId, SpinEvent event) {
        report.busyWaitLoops.add(String.format(
            "Thread %d: spun %,d iterations over %dms at %s",
            threadId,
            event.iterations,
            event.durationMs,
            event.location
        ));
        report.cpuWasted += event.durationMs;

        if (event.iterationsPerMs() > 50_000d) {
            report.tightLoops.add(String.format(
                "Thread %d: tight loop %.0f iterations/ms at %s",
                threadId,
                event.iterationsPerMs(),
                event.location
            ));
        }
    }

    /**
     * Standardized alias for {@link #analyzeBusyWaiting()}.
     *
     * @return the findings this detector collected during the run
     */
    public BusyWaitReport analyze() {
        return analyzeBusyWaiting();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        threadActivities.clear();
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

    public static class BusyWaitReport {
        /** Loops that spun waiting for a condition instead of blocking. */
        public final Set<String> busyWaitLoops = new HashSet<>();
        /** Loops that spun with no back-off at all. */
        public final Set<String> tightLoops = new HashSet<>();
        /** Nanoseconds of CPU time spent spinning rather than blocking. */
        public long cpuWasted;

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !busyWaitLoops.isEmpty() || !tightLoops.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No busy-waiting detected.";
            }

            StringBuilder sb = new StringBuilder("BUSY-WAITING DETECTED:\n");
            if (!busyWaitLoops.isEmpty()) {
                sb.append("\nSpin loops:\n");
                for (String loop : busyWaitLoops) {
                    sb.append("  - ").append(loop).append('\n');
                }
                sb.append("  Total CPU time spent spinning: ").append(cpuWasted).append(" ms\n");
            }
            if (!tightLoops.isEmpty()) {
                sb.append("\nVery tight polling loops:\n");
                for (String loop : tightLoops) {
                    sb.append("  - ").append(loop).append('\n');
                }
            }
            sb.append("\nWhy: A spin loop keeps a CPU core at 100% utilization for the entire wait duration, preventing other threads from being scheduled on that core. Under load, each spinning thread blocks a pool thread from doing real work, cascading latency across the whole system.\n");
            sb.append("Fix: replace spin loops with blocking primitives that park the thread at zero CPU cost:\n");
            sb.append("  - wait()/notify() inside a synchronized block (check condition in a while loop)\n");
            sb.append("  - CountDownLatch.await() for one-time handoff\n");
            sb.append("  - CompletableFuture.join() or Future.get() for async results\n");
            sb.append("  - LockSupport.park() for low-level unparking by another thread");
            return sb.toString();
        }
    }
}
