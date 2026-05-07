package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects use of deprecated and unsafe {@link Thread} API methods:
 * {@code Thread.stop()}, {@code Thread.suspend()}, {@code Thread.resume()},
 * {@code Thread.destroy()}, and {@code Thread.countStackFrames()}.
 *
 * <p>These methods were deprecated (and later removed or made no-ops in Java 20+) because:
 * <ul>
 *   <li>{@code stop()} releases all monitors held by the thread, leaving shared state
 *       partially updated — invariants are silently broken.</li>
 *   <li>{@code suspend()} + {@code resume()} are inherently deadlock-prone: a thread
 *       suspended while holding a lock prevents any other thread from acquiring it.</li>
 *   <li>{@code destroy()} was never implemented.</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.deprecatedThreadApiDetector();
 * d.recordApiUse("Thread.stop", Thread.currentThread());
 * targetThread.stop();  // dangerous!
 * }</pre>
 *
 * @since 0.10.0
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/DeprecatedThreadApiDetectorTest.java"
)
public class DeprecatedThreadApiDetector {

    /** Names of deprecated {@link Thread} API methods tracked by this detector. */
    public static final Set<String> DEPRECATED_APIS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(
                    "Thread.stop", "Thread.suspend", "Thread.resume",
                    "Thread.destroy", "Thread.countStackFrames")));

    private static class ApiUseEvent {
        final String apiName;
        final long   threadId;
        final String threadName;

        ApiUseEvent(String apiName, long threadId, String threadName) {
            this.apiName    = apiName;
            this.threadId   = threadId;
            this.threadName = threadName;
        }
    }

    private final List<ApiUseEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Records a call to a deprecated {@link Thread} API method.
     *
     * @param apiName one of {@code "Thread.stop"}, {@code "Thread.suspend"},
     *                {@code "Thread.resume"}, {@code "Thread.destroy"},
     *                {@code "Thread.countStackFrames"} (null-safe)
     * @param thread  the calling thread (null-safe)
     */
    public void recordApiUse(String apiName, Thread thread) {
        if (apiName == null || thread == null) return;
        events.add(new ApiUseEvent(apiName, thread.getId(), thread.getName()));
    }

    /** @return report of deprecated Thread API usages */
    public DeprecatedThreadApiReport analyze() {
        DeprecatedThreadApiReport r = new DeprecatedThreadApiReport();
        for (ApiUseEvent e : events) {
            r.violations.add(String.format(
                    "Thread '%s' called deprecated API '%s' — "
                            + "this method is unsafe and was removed/deprecated in Java 20+",
                    e.threadName, e.apiName));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class DeprecatedThreadApiReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("DEPRECATED THREAD API USAGE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: Thread.stop(), Thread.suspend(), and Thread.resume() are permanently deprecated because they are\n" +
                       "       fundamentally unsafe. stop() kills the thread mid-execution, leaving shared objects in a\n" +
                       "       partially-updated, corrupt state. suspend() holds the thread's locks while suspended, causing\n" +
                       "       deadlocks. These APIs should never appear in production or test code.\n" +
                       "  Fix: Replace Thread.stop() with a cooperative shutdown: set a volatile boolean stop flag and have the\n" +
                       "       thread check it regularly: while (!stopped) { doWork(); }\n" +
                       "       Replace suspend()/resume() with wait()/notify() or Condition.await()/signalAll() for controlled pausing.");
            return sb.toString();
        }
    }
}
