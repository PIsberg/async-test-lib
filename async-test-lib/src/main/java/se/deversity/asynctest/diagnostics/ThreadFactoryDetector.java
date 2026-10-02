package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

import se.deversity.asynctest.AgentThreadHooks;

/**
 * Detects ThreadFactory misuse patterns:
 * - Missing uncaught exception handler
 * - Non-daemon threads in thread pools
 * - Missing thread naming convention
 * - Thread priority issues
 */
public class ThreadFactoryDetector {

    private final Map<ThreadFactory, FactoryInfo> factoryRegistry = new ConcurrentHashMap<>();
    private final Set<String> missingExceptionHandler = ConcurrentHashMap.newKeySet();
    private final Set<String> nonDaemonThreads = ConcurrentHashMap.newKeySet();
    private final Set<String> unnamedThreads = ConcurrentHashMap.newKeySet();

    /**
     * Register a ThreadFactory for monitoring.
     *
     * @param factory the thread factory being recorded, tracked by identity
     * @param name a label identifying the factory in the report
     */
    public void registerFactory(ThreadFactory factory, String name) {
        // First registration wins: re-registering a subject must not discard what has
        // been observed about it. An @AsyncTest body runs once per thread, so a consumer
        // registering inside it registers once per worker.
        factoryRegistry.putIfAbsent(factory, new FactoryInfo(name));
    }

    /**
     * Record a thread created by factory.
     *
     * @param factory the thread factory being recorded, tracked by identity
     * @param factoryName a label identifying the thread factory in the report
     * @param thread the thread performing the operation
     */
    public void recordThreadCreated(ThreadFactory factory, String factoryName, Thread thread) {
        FactoryInfo info = factoryRegistry.get(factory);
        if (info != null) {
            // Check for missing exception handler
            Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
            if (handler == null || handler instanceof ThreadGroup) {
                missingExceptionHandler.add(factoryName + ":" + thread.getName());
            }
            
            // Check for non-daemon thread
            if (!thread.isDaemon()) {
                nonDaemonThreads.add(factoryName + ":" + thread.getName());
            } else if (onlyInheritedDaemon(factory, thread)) {
                nonDaemonThreads.add(factoryName + ":" + thread.getName()
                        + " (daemon only by inheritance, no setDaemon observed)");
            }
            
            // Check for unnamed thread (Thread.getName() never returns null)
            if (thread.getName().startsWith("Thread-")) {
                unnamedThreads.add(factoryName + ":" + thread.getName());
            }
        }
    }

    /**
     * {@return whether a daemon {@code thread} got the flag from its creator rather than from
     * its factory}
     *
     * <p>A factory called from a body runs on one of the runner's workers, which are daemon
     * (#479), so {@code r -> new Thread(r)} hands back a daemon thread without deciding
     * anything (#731). Only the agent can tell the two apart, by weaving {@code setDaemon} and a
     * platform builder's {@code daemon}; until it has, the flag is taken at its word. So is the
     * flag of a thread the agent did not see constructed (#737): a factory outside
     * {@code includes=}, or a JDK one, decides where nothing is woven. And so is the flag of one
     * whose constructing thread was not daemon (#856): it inherited {@code false}, so something the
     * agent did not see made it daemon.
     *
     * @param factory the factory that created the thread
     * @param thread  a daemon thread it created
     */
    private static boolean onlyInheritedDaemon(ThreadFactory factory, Thread thread) {
        if (thread.isVirtual() || !AgentThreadHooks.isThreadWeavingInstalled()) {
            return false;
        }
        String factoryClass = factory.getClass().getName();
        if (factoryClass.startsWith("java.") || factoryClass.startsWith("jdk.")) {
            return false;
        }
        return Boolean.TRUE.equals(AgentThreadHooks.inheritedDaemon(thread))
                && AgentThreadHooks.explicitDaemonSetting(thread) == null;
    }

    /**
     * Analyze ThreadFactory usage and return report.
     *
     * @return the findings this detector collected during the run
     */
    public ThreadFactoryReport analyze() {
        ThreadFactoryReport report801 = new ThreadFactoryReport(
            missingExceptionHandler,
            nonDaemonThreads,
            unnamedThreads
        );
        report801.fillStructuredViolations();
        return DetectorFailurePolicy.checkedReport(this, report801);
    }

    /**
     * Report class for ThreadFactory analysis.
     */
    public static class ThreadFactoryReport {
        private final Set<String> missingExceptionHandler;
        private final Set<String> nonDaemonThreads;
        private final Set<String> unnamedThreads;
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a Violation per finding (#801); called once by the detector before it returns the report. */
        void fillStructuredViolations() {
            if (!hasIssues()) {
                return;
            }
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(toString()).orElse(IssueSeverity.HIGH);
                for (String finding : missingExceptionHandler) {
                    structuredViolations.add(new Violation("ThreadFactory", severity,
                            finding, List.of(), Map.of(), Instant.now()));
                }
                for (String finding : nonDaemonThreads) {
                    structuredViolations.add(new Violation("ThreadFactory", severity,
                            finding, List.of(), Map.of(), Instant.now()));
                }
                for (String finding : unnamedThreads) {
                    structuredViolations.add(new Violation("ThreadFactory", severity,
                            finding, List.of(), Map.of(), Instant.now()));
                }
        }

        /**
         * Creates a ThreadFactoryReport.
         *
         * @param missingExceptionHandler the threads created without an uncaught-exception handler
         * @param nonDaemonThreads the non-daemon threads created, which can keep the JVM alive
         * @param unnamedThreads the threads created without a name, which are hard to attribute in a dump
         */
        public ThreadFactoryReport(
            Set<String> missingExceptionHandler,
            Set<String> nonDaemonThreads,
            Set<String> unnamedThreads
        ) {
            this.missingExceptionHandler = Collections.unmodifiableSet(new HashSet<>(missingExceptionHandler));
            this.nonDaemonThreads = Collections.unmodifiableSet(new HashSet<>(nonDaemonThreads));
            this.unnamedThreads = Collections.unmodifiableSet(new HashSet<>(unnamedThreads));
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !missingExceptionHandler.isEmpty() 
                || !nonDaemonThreads.isEmpty()
                || !unnamedThreads.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("THREADFACTORY ISSUES DETECTED:\n");

            if (!missingExceptionHandler.isEmpty()) {
                sb.append("  Missing Uncaught Exception Handler:\n");
                for (String threadInfo : missingExceptionHandler) {
                    sb.append("    - ").append(threadInfo).append("\n");
                }
                sb.append("  Why: An uncaught exception in a thread kills that thread silently. Without a handler, the failure\n");
                sb.append("       is never logged, the work is never retried, and the thread pool shrinks without anyone noticing.\n");
                sb.append("  Fix: Set an uncaught exception handler on every created thread:\n");
                sb.append("    thread.setUncaughtExceptionHandler((t, e) -> log.error(\"Thread {} died\", t.getName(), e));\n");
                sb.append("  Or set a JVM-wide default: Thread.setDefaultUncaughtExceptionHandler(...)\n");
            }

            if (!nonDaemonThreads.isEmpty()) {
                sb.append("  Non-Daemon Threads Created:\n");
                for (String threadInfo : nonDaemonThreads) {
                    sb.append("    - ").append(threadInfo).append("\n");
                }
                sb.append("  Why: The JVM waits for all non-daemon threads to finish before exiting. A leaked non-daemon thread\n");
                sb.append("       prevents clean shutdown and may keep processes alive in production or cause test hangs.\n");
                sb.append("  Fix: Mark background threads as daemons so the JVM does not wait for them:\n");
                sb.append("    thread.setDaemon(true);  // must be called before thread.start()\n");
            }

            if (!unnamedThreads.isEmpty()) {
                sb.append("  Unnamed Threads (poor naming):\n");
                for (String threadInfo : unnamedThreads) {
                    sb.append("    - ").append(threadInfo).append("\n");
                }
                sb.append("  Why: Thread names appear in stack traces, thread dumps, and monitoring dashboards. Generic names like\n");
                sb.append("       \"Thread-42\" make it impossible to identify which component a blocked or crashing thread belongs to.\n");
                sb.append("  Fix: Assign descriptive names in the factory:\n");
                sb.append("    thread.setName(\"payment-worker-\" + threadCount.incrementAndGet());\n");
            }

            if (!hasIssues()) {
                sb.append("  No ThreadFactory issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal factory information.
     */
    static class FactoryInfo {
        final String name;
        FactoryInfo(String name) {
            this.name = name;
        }

    }
}
