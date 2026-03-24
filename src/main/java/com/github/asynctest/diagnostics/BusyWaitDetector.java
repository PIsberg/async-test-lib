package com.github.asynctest.diagnostics;

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

    private static class ThreadActivity {
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

    public BusyWaitReport analyzeBusyWaiting() {
        BusyWaitReport report = new BusyWaitReport();

        for (Map.Entry<Long, ThreadActivity> entry : threadActivities.entrySet()) {
            long threadId = entry.getKey();
            ThreadActivity activity = entry.getValue();
            synchronized (activity) {
                for (SpinEvent event : activity.spinEvents) {
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
            }
        }

        return report;
    }

    public void reset() {
        threadActivities.clear();
    }

    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
    }

    public static class BusyWaitReport {
        public final Set<String> busyWaitLoops = new HashSet<>();
        public final Set<String> tightLoops = new HashSet<>();
        public long cpuWasted;

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
            sb.append("  Fix: replace spin loops with wait/notify, latches, or futures");
            return sb.toString();
        }
    }
}
