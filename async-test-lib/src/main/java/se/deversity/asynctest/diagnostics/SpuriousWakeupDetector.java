package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects wait() or Condition.await() calls invoked outside of a while loop condition check,
 * exposing the thread to spurious wakeups.
 *
 * <p><strong>Recorded.</strong> {@link #recordWait} takes the caller's word for whether the wait
 * sits inside a loop.
 *
 * <p><strong>Or attach the agent (#1255).</strong> With {@code collections=true} the agent weaves
 * {@code Object.wait} and marks every backward jump that encloses a woven wait in the same method,
 * which is the loop's back-edge. A woven wait that returns normally is pending until its thread
 * reaches that back-edge, which makes it a loop's; one whose thread waits again, or whose round
 * ends, with no back-edge in between is an {@code if}'s and is reported. A wait that ends in
 * {@code InterruptedException} is not judged: a loop that leaves on interrupt never reaches its
 * back-edge, and the thread did not proceed on a wakeup. What the weave cannot see, and reports:
 * a loop in one method around a bare {@code wait()} in another, and a timed {@code wait} used as a
 * delay with no predicate at all, which {@code Object.wait}'s own contract still puts in a loop.
 * What it reads as a loop and misses: an {@code if}-guarded wait inside any loop of the same method.
 * {@code Condition.await} carries no back-edge and is not observed.
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "ConcurrentHashMap stores state per monitor instance.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SpuriousWakeupDetectorTest.java"
)
public final class SpuriousWakeupDetector extends AbstractInstanceDetector<SpuriousWakeupDetector.State> {

    static final class State {
        final String monitorName;
        final Set<String> threadsOutsideLoop = ConcurrentHashMap.newKeySet();

        State(String monitorName) {
            this.monitorName = monitorName;
        }
    }

    /** A woven wait that returned and whose thread has not yet reached a back-edge. */
    private static final class Woken {
        final Object monitor;
        final String threadName;
        final long epoch;
        /** Whether the thread is one the run handed work to, which a round's end does not stop. */
        final boolean helper;

        Woken(Object monitor, String threadName, long epoch, boolean helper) {
            this.monitor = monitor;
            this.threadName = threadName;
            this.epoch = epoch;
            this.helper = helper;
        }
    }

    /**
     * At most one per thread: its latest observed wakeup, kept until its back-edge, its next
     * wakeup, or the end of its round settles it. Compared by identity in {@code remove(k, v)}.
     */
    private final Map<Thread, Woken> woken = new ConcurrentHashMap<>();
    /** Bumped at the start of every invocation round. */
    private final AtomicLong invocationEpoch = new AtomicLong();

    @Override
    State newState(Object instance, String label) {
        return new State(label);
    }

    /**
     * Internal: called at the start of each invocation round. A back-edge confirms only a wakeup
     * from its own round, so every wakeup of the closed round that reached none is settled as an
     * {@code if}'s here, and the map does not grow with the number of waits.
     *
     * @since 1.14.3
     */
    public void markInvocationStart() {
        long epoch = invocationEpoch.incrementAndGet();
        if (woken.isEmpty()) {
            return;
        }
        woken.forEach((thread, wait) -> {
            if (wait.epoch < epoch && leftBehind(thread, wait) && woken.remove(thread, wait)) {
                settleOutsideLoop(wait);
            }
        });
    }

    /**
     * {@return whether {@code thread} can no longer reach a back-edge for {@code wait}} A worker
     * has finished its round when this is asked. A thread the run handed work to (#1339) may
     * still be between its wakeup and its loop's back-edge, so only one that has ended counts.
     */
    private static boolean leftBehind(Thread thread, Woken wait) {
        return !wait.helper || !thread.isAlive();
    }

    /**
     * Internal: the agent saw the calling thread return normally from {@code wait} on
     * {@code monitor}, which it still holds (#1255). A previous wakeup of the same thread with no
     * back-edge since is settled as an {@code if}'s: two waits in a row are two {@code if}s.
     *
     * @param monitor the monitor waited on
     * @since 1.14.3
     */
    public void recordObservedWakeup(Object monitor) {
        recordObservedWakeup(monitor, false);
    }

    /**
     * Internal: {@link #recordObservedWakeup(Object)} for a caller that may be a thread the run
     * handed work to and not one of its workers (#1339). Such a thread is not stopped by the end
     * of a round or of the run, so a wakeup of its own that reached no back-edge is settled as an
     * {@code if}'s by its next wakeup, or once the thread has ended, and dropped unjudged when
     * the thread is still alive at analysis.
     *
     * @param monitor the monitor waited on
     * @param helper  whether the calling thread is not a worker of the run
     * @since 1.14.3
     */
    public void recordObservedWakeup(Object monitor, boolean helper) {
        Thread thread = Thread.currentThread();
        Woken previous = woken.put(thread,
                new Woken(monitor, thread.getName(), invocationEpoch.get(), helper));
        if (previous != null) {
            settleOutsideLoop(previous);
        }
    }

    /**
     * Internal: the calling thread reached a backward jump enclosing a woven wait (#1255), so its
     * pending wakeup was a loop's.
     *
     * @since 1.14.3
     */
    public void recordObservedLoopBackEdge() {
        if (!woken.isEmpty()) {
            woken.remove(Thread.currentThread());
        }
    }

    private void settleOutsideLoop(Woken wait) {
        stateFor(wait.monitor, null, "Monitor").threadsOutsideLoop.add(wait.threadName);
    }

    /**
     * Record a wait/await operation.
     *
     * @param monitor the object being used as a monitor, tracked by identity
     * @param monitorName a label identifying the monitor in the report
     * @param insideLoop the {@code insideLoop} flag
     * @param thread the thread performing the operation
     */
    public void recordWait(Object monitor, String monitorName, boolean insideLoop, Thread thread) {
        if (monitor == null || thread == null) return;
        if (insideLoop) return; // Safely inside loop, do not track as violation
        
        State s = stateFor(monitor, monitorName, "Monitor");
        s.threadsOutsideLoop.add(thread.getName());
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        // The run has quiesced: a wakeup no back-edge followed was left by an if.
        woken.forEach((thread, wait) -> {
            if (leftBehind(thread, wait) && woken.remove(thread, wait)) {
                settleOutsideLoop(wait);
            }
        });
        Report r = new Report();
        for (State s : states()) {
            if (s.threadsOutsideLoop.isEmpty()) continue;
            
            String msg = String.format(
                "Monitor '%s' wait() called outside a condition-checking loop by threads %s. This exposes the application to spurious wakeups where a thread wakes up and proceeds without the target condition being satisfied.",
                s.monitorName, String.join(", ", s.threadsOutsideLoop)
            );
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                "SpuriousWakeup",
                IssueSeverity.HIGH,
                msg,
                List.of(),
                Map.of(
                    "monitorName", s.monitorName,
                    "threadsOutsideLoop", List.copyOf(s.threadsOutsideLoop)
                ),
                Instant.now()
            ));
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link se.deversity.asynctest.report.Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SPURIOUS WAKEUP HAZARD — clean";
            StringBuilder sb = new StringBuilder("SPURIOUS WAKEUP HAZARD DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Always invoke wait() or Condition.await() inside a while loop checking the predicate:\n")
              .append("      synchronized(lock) { while (!condition) { lock.wait(); } }\n");
            return sb.toString();
        }
    }
}
