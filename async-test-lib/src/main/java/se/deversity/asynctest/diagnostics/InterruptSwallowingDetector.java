package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
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
 * <p>Usage inside {@code @AsyncTest}:
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

        CatchEvent(String threadName, String location, boolean restored) {
            this.threadName = threadName;
            this.location   = location;
            this.restored   = restored;
        }
    }

    private final List<CatchEvent> events = new CopyOnWriteArrayList<>();

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
                location != null ? location : "unknown", restored));
    }

    /**
     * {@return report of threads that swallowed an InterruptedException}
     */
    public InterruptSwallowingReport analyze() {
        InterruptSwallowingReport r = new InterruptSwallowingReport();
        for (CatchEvent e : events) {
            if (!e.restored) {
                r.violations.add(String.format(
                        "Thread '%s' caught InterruptedException at [%s] without restoring "
                                + "the interrupt flag — call Thread.currentThread().interrupt() "
                                + "or rethrow the exception",
                        e.threadName, e.location));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class InterruptSwallowingReport {
        final List<String> violations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("INTERRUPT SWALLOWING DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: Catching InterruptedException and not restoring the interrupt flag clears the thread's interrupted\n" +
                       "       status permanently. Any code up the call stack that checks Thread.interrupted() or calls another\n" +
                       "       blocking method expecting to be interruptible will never see the interrupt — the shutdown signal\n" +
                       "       is silently swallowed, preventing graceful termination.\n" +
                       "  Fix: Always restore the interrupt flag when catching InterruptedException:\n" +
                       "       catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }\n" +
                       "       Or rethrow InterruptedException directly if the method signature allows it.");
            return sb.toString();
        }
    }
}
