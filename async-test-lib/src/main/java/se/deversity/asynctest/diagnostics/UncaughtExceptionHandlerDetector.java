package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects threads that are started without a custom {@link Thread.UncaughtExceptionHandler}
 * and that subsequently throw an uncaught exception.
 *
 * <p>Without an explicit handler, uncaught exceptions are routed only to the thread group's
 * default handler (typically a stderr print). The submitting code has no way to detect
 * the failure, the exception is effectively swallowed from the perspective of the task
 * submitter, and the thread pool silently replaces the dead thread.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.uncaughtExceptionHandlerDetector();
 * Thread worker = new Thread(task);
 * d.recordThreadStart(worker);   // before worker.start()
 * worker.start();
 * // ... later, if worker throws:
 * d.recordUncaughtException(worker, throwable);
 * }</pre>
 *
 * @since 0.10.0
 */
public class UncaughtExceptionHandlerDetector {

    private static class ThreadRecord {
        final String  threadName;
        final boolean hasCustomHandler;
        volatile @Nullable Throwable uncaughtException;

        ThreadRecord(String tname, boolean hasCustomHandler) {
            this.threadName       = tname;
            this.hasCustomHandler = hasCustomHandler;
        }
    }

    private final Map<Long, ThreadRecord> threads = new ConcurrentHashMap<>();

    /**
     * Records that a thread is about to be started.
     *
     * <p>The detector checks whether the thread has a custom
     * {@link Thread.UncaughtExceptionHandler} (not the default thread-group handler).
     *
     * @param thread the thread being started (null-safe)
     */
    public void recordThreadStart(Thread thread) {
        if (thread == null) return;
        boolean hasCustom = thread.getUncaughtExceptionHandler() != null
                && !(thread.getUncaughtExceptionHandler() instanceof ThreadGroup);
        threads.put(thread.threadId(),
                new ThreadRecord(thread.getName(), hasCustom));
    }

    /**
     * Records that a thread terminated with an uncaught exception.
     *
     * @param thread    the thread that threw (null-safe)
     * @param throwable the uncaught exception
     */
    public void recordUncaughtException(Thread thread, Throwable throwable) {
        if (thread == null) return;
        ThreadRecord rec = threads.get(thread.threadId());
        if (rec != null) rec.uncaughtException = throwable;
    }

    /** {@return report of threads that threw without a custom UncaughtExceptionHandler} */
    public UncaughtExceptionHandlerReport analyze() {
        UncaughtExceptionHandlerReport r = new UncaughtExceptionHandlerReport();
        for (ThreadRecord rec : threads.values()) {
            if (!rec.hasCustomHandler && rec.uncaughtException != null) {
                r.violations.add(String.format(
                        "Thread '%s' threw '%s' but had no custom UncaughtExceptionHandler — "
                                + "the exception was only printed to stderr and ignored by the submitter",
                        rec.threadName, rec.uncaughtException.getClass().getSimpleName()));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class UncaughtExceptionHandlerReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("UNCAUGHT EXCEPTION HANDLER MISSING DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: An uncaught exception kills the thread silently. Without an uncaught exception handler, the failure\n" +
                       "       is never logged, the task is never retried, and the thread pool shrinks by one — often leading to\n" +
                       "       gradual starvation as more threads die without anyone noticing.\n" +
                       "  Fix: Set a handler on every thread or set a JVM-wide default:\n" +
                       "       thread.setUncaughtExceptionHandler((t, e) -> log.error(\"Thread {} died\", t.getName(), e));\n" +
                       "       // or globally:\n" +
                       "       Thread.setDefaultUncaughtExceptionHandler((t, e) -> log.error(\"Unhandled exception\", e));");
            return sb.toString();
        }
    }
}
