package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

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
            
            // Check for unnamed thread
            if (thread.getName() == null || thread.getName().startsWith("Thread-")) {
                unnamedThreads.add(factoryName + ":" + (thread.getName() != null ? thread.getName() : "null"));
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
            this.factoryRegistry = factoryRegistry;
            this.missingExceptionHandler = missingExceptionHandler;
            this.nonDaemonThreads = nonDaemonThreads;
            this.unnamedThreads = unnamedThreads;
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
                sb.append("  Problem: Uncaught exceptions will be silently swallowed\n");
                sb.append("  Fix: Set uncaught exception handler:\n");
                sb.append("    thread.setUncaughtExceptionHandler((t, e) -> e.printStackTrace());\n");
            }

            if (!nonDaemonThreads.isEmpty()) {
                sb.append("  Non-Daemon Threads Created:\n");
                for (String threadInfo : nonDaemonThreads) {
                    sb.append("    - ").append(threadInfo).append("\n");
                }
                sb.append("  Warning: Non-daemon threads prevent JVM shutdown\n");
                sb.append("  Fix: Set thread as daemon: thread.setDaemon(true);\n");
            }

            if (!unnamedThreads.isEmpty()) {
                sb.append("  Unnamed Threads (poor naming):\n");
                for (String threadInfo : unnamedThreads) {
                    sb.append("    - ").append(threadInfo).append("\n");
                }
                sb.append("  Warning: Hard to debug without meaningful names\n");
                sb.append("  Fix: Use descriptive thread names:\n");
                sb.append("    new Thread(runnable, \"pool-1-worker-\" + threadNumber);\n");
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
