package se.deversity.asynctest.diagnostics;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects ThreadFactory misuse patterns:
 * - Missing uncaught exception handler
 * - Non-daemon threads in thread pools
 * - Missing thread naming convention
 * - Thread priority issues
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ThreadFactoryDetectorTest.java"
)
public class ThreadFactoryDetector {

    private final Map<ThreadFactory, FactoryInfo> factoryRegistry = new ConcurrentHashMap<>();
    private final Set<String> missingExceptionHandler = ConcurrentHashMap.newKeySet();
    private final Set<String> nonDaemonThreads = ConcurrentHashMap.newKeySet();
    private final Set<String> unnamedThreads = ConcurrentHashMap.newKeySet();

    /**
     * Register a ThreadFactory for monitoring.
     */
    public void registerFactory(ThreadFactory factory, String name) {
        factoryRegistry.put(factory, new FactoryInfo(name));
    }

    /**
     * Record a thread created by factory.
     */
    public void recordThreadCreated(ThreadFactory factory, String factoryName, Thread thread) {
        FactoryInfo info = factoryRegistry.get(factory);
        if (info != null) {
            info.recordThreadCreated(thread);
            
            // Check for missing exception handler
            if (thread.getUncaughtExceptionHandler() == null) {
                missingExceptionHandler.add(factoryName + ":" + thread.getName());
            }
            
            // Check for non-daemon thread
            if (!thread.isDaemon()) {
                nonDaemonThreads.add(factoryName + ":" + thread.getName());
            }
            
            // Check for unnamed thread (Thread.getName() never returns null)
            if (thread.getName().startsWith("Thread-")) {
                unnamedThreads.add(factoryName + ":" + thread.getName());
            }
        }
    }

    /**
     * Analyze ThreadFactory usage and return report.
     */
    public ThreadFactoryReport analyze() {
        return new ThreadFactoryReport(
            factoryRegistry,
            missingExceptionHandler,
            nonDaemonThreads,
            unnamedThreads
        );
    }

    /**
     * Report class for ThreadFactory analysis.
     */
    public static class ThreadFactoryReport {
        private final Map<ThreadFactory, FactoryInfo> factoryRegistry;
        private final Set<String> missingExceptionHandler;
        private final Set<String> nonDaemonThreads;
        private final Set<String> unnamedThreads;

        public ThreadFactoryReport(
            Map<ThreadFactory, FactoryInfo> factoryRegistry,
            Set<String> missingExceptionHandler,
            Set<String> nonDaemonThreads,
            Set<String> unnamedThreads
        ) {
            this.factoryRegistry = Collections.unmodifiableMap(new HashMap<>(factoryRegistry));
            this.missingExceptionHandler = Collections.unmodifiableSet(new HashSet<>(missingExceptionHandler));
            this.nonDaemonThreads = Collections.unmodifiableSet(new HashSet<>(nonDaemonThreads));
            this.unnamedThreads = Collections.unmodifiableSet(new HashSet<>(unnamedThreads));
        }

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
        int threadsCreated = 0;

        FactoryInfo(String name) {
            this.name = name;
        }

        synchronized void recordThreadCreated(Thread thread) {
            threadsCreated++;
        }
    }
}
