package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Detects {@link InterruptedException} catches where the interrupt flag is silently swallowed.
 *
 * <p>A {@code catch (InterruptedException e)} block that neither calls
 * {@code Thread.currentThread().interrupt()} nor rethrows the exception suppresses the
 * cooperative-cancellation signal permanently. Upper-level code (executors, blocking
 * operations) can no longer observe the interrupted state, leading to threads that
 * ignore shutdown requests or loop forever on interrupted queues.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.INTERRUPT_SWALLOWING)}:
 * <pre>{@code
 * var d = AsyncTestContext.interruptSwallowingDetector();
 * try {
 *     Thread.sleep(100);
 * } catch (InterruptedException e) {
 *     // bad — swallowing:
 *     d.recordCatch(Thread.currentThread(), "MyService.doWork:42", false);
 *     // good — restoring:
 *     // Thread.currentThread().interrupt();
 *     // d.recordCatch(Thread.currentThread(), "MyService.doWork:42", true);
 * }
 * }</pre>
 *
 * @since 0.10.0
 */
public class InterruptSwallowingDetector {

    private static class CatchEvent {
        final String threadName;
        final String location;
        final boolean restored;
        /** Whether the agent judged the handler from its code rather than a body recording a catch. */
        final boolean woven;

        CatchEvent(String threadName, String location, boolean restored, boolean woven) {
            this.threadName = threadName;
            this.location   = location;
            this.restored   = restored;
            this.woven      = woven;
        }
    }

    private final List<CatchEvent> events = new CopyOnWriteArrayList<>();

    /** The location strings the agent has handed over, so a method's every entry after the first costs one lookup. */
    private final Set<String> wovenLocations = ConcurrentHashMap.newKeySet();

    /**
     * Records swallowing handlers the agent found in a method that ran during the run: catches of
     * {@code InterruptedException} that neither rethrow nor restore the flag, judged from the
     * bytecode. Each location is reported once per run, against the first thread that ran it.
     *
     * @param locations the handlers, {@code Owner.method:line} joined by commas (null-safe)
     * @param thread    the thread that entered the method (null-safe)
     * @since 1.13.2
     */
    public void recordSwallowingHandlers(String locations, Thread thread) {
        if (locations == null || thread == null || !wovenLocations.add(locations)) return;
        for (String location : locations.split(",", -1)) {
            events.add(new CatchEvent(thread.getName(), location, false, true));
        }
    }

    /**
     * Records how an {@code InterruptedException} was handled.
     *
     * @param thread   the catching thread (null-safe)
     * @param location human-readable location string, e.g. {@code "ClassName.method:lineNum"}
     * @param restored {@code true} if {@code Thread.currentThread().interrupt()} was called
     *                 or the exception was rethrown; {@code false} if the interrupt was swallowed
     */
    public void recordCatch(Thread thread, String location, boolean restored) {
        if (thread == null) return;
        events.add(new CatchEvent(thread.getName(),
                location != null ? location : "unknown", restored, false));
    }

    /**
     * {@return report of threads that swallowed an InterruptedException}
     */
    public InterruptSwallowingReport analyze() {
        InterruptSwallowingReport r = new InterruptSwallowingReport();
        for (CatchEvent e : events) {
            if (!e.restored) {
                String finding = e.woven
                        ? String.format(
                                "The catch of InterruptedException at [%s] neither rethrows it "
                                        + "nor restores the interrupt flag, and its method ran on "
                                        + "thread '%s' during the run — call "
                                        + "Thread.currentThread().interrupt() or rethrow the "
                                        + "exception",
                                e.location, e.threadName)
                        : String.format(
                        "Thread '%s' caught InterruptedException at [%s] without restoring "
                                + "the interrupt flag — call Thread.currentThread().interrupt() "
                                + "or rethrow the exception",
                        e.threadName, e.location);
                r.violations.add(finding);
                r.structuredViolations.add(new Violation("InterruptSwallowing", IssueSeverity.HIGH,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class InterruptSwallowingReport {
        final List<String> violations = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("INTERRUPT SWALLOWING DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("""
  Why: Catching InterruptedException and not restoring the interrupt flag clears the thread's interrupted
       status permanently. Any code up the call stack that checks Thread.interrupted() or calls another
       blocking method expecting to be interruptible will never see the interrupt — the shutdown signal
       is silently swallowed, preventing graceful termination.
  Fix: Always restore the interrupt flag when catching InterruptedException:
       catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
       Or rethrow InterruptedException directly if the method signature allows it.\
""");
            return sb.toString();
        }
    }
}
