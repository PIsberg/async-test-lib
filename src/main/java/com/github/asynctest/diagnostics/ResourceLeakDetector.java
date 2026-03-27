package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects resource leak patterns in concurrent code.
 * 
 * Common resource leak issues detected:
 * - AutoCloseable resources not closed (InputStream, OutputStream, Reader, Writer, Connection)
 * - Resources opened in try block but not closed in finally
 * - Resources acquired but exception prevents cleanup
 * - Thread-local resources not cleaned up
 * 
 * Note: Resources should always be closed using try-with-resources or in finally blocks.
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectResourceLeaks = true)
 * void testResourceUsage() throws IOException {
 *     FileInputStream fis = new FileInputStream("data.txt");
 *     AsyncTestContext.resourceLeakMonitor()
 *         .registerResource(fis, "file-input", "FileInputStream");
 *     
 *     try {
 *         // use resource
 *         fis.read();
 *     } finally {
 *         fis.close();
 *         AsyncTestContext.resourceLeakMonitor()
 *             .recordResourceClosed(fis, "file-input");
 *     }
 * }
 * }</pre>
 */
public class ResourceLeakDetector {

    private static class ResourceState {
        final String name;
        final String resourceType;
        final Object resource;
        final AtomicInteger openCount = new AtomicInteger(0);
        final AtomicInteger closeCount = new AtomicInteger(0);
        final Set<Long> openingThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> closingThreads = ConcurrentHashMap.newKeySet();
        volatile boolean currentlyOpen = false;
        volatile Long lastOpenTime = null;
        volatile Long lastCloseTime = null;

        ResourceState(Object resource, String name, String resourceType) {
            this.resource = resource;
            this.name = name != null ? name : resourceType + "@" + System.identityHashCode(resource);
            this.resourceType = resourceType != null ? resourceType : resource.getClass().getSimpleName();
        }
    }

    private final Map<Integer, ResourceState> resources = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a resource for monitoring.
     * 
     * @param resource the AutoCloseable resource to monitor
     * @param name a descriptive name for reporting
     * @param resourceType the type of resource (e.g., "FileInputStream", "Connection")
     */
    public void registerResource(Object resource, String name, String resourceType) {
        if (!enabled || resource == null) {
            return;
        }
        resources.put(System.identityHashCode(resource), new ResourceState(resource, name, resourceType));
    }

    /**
     * Record that a resource was opened/acquired.
     * 
     * @param resource the resource
     * @param name the resource name (should match registration)
     */
    public void recordResourceOpened(Object resource, String name) {
        if (!enabled || resource == null) {
            return;
        }
        ResourceState state = resources.get(System.identityHashCode(resource));
        if (state != null) {
            state.openCount.incrementAndGet();
            state.openingThreads.add(Thread.currentThread().threadId());
            state.currentlyOpen = true;
            state.lastOpenTime = System.currentTimeMillis();
        }
    }

    /**
     * Record that a resource was closed/released.
     * 
     * @param resource the resource
     * @param name the resource name (should match registration)
     */
    public void recordResourceClosed(Object resource, String name) {
        if (!enabled || resource == null) {
            return;
        }
        ResourceState state = resources.get(System.identityHashCode(resource));
        if (state != null) {
            state.closeCount.incrementAndGet();
            state.closingThreads.add(Thread.currentThread().threadId());
            state.currentlyOpen = false;
            state.lastCloseTime = System.currentTimeMillis();
        }
    }

    /**
     * Analyze resource usage for leaks.
     * 
     * @return a report of detected issues
     */
    public ResourceLeakReport analyze() {
        ResourceLeakReport report = new ResourceLeakReport();
        report.enabled = enabled;

        for (ResourceState state : resources.values()) {
            int opens = state.openCount.get();
            int closes = state.closeCount.get();

            // Check for resource leaks (more opens than closes)
            if (opens > closes) {
                report.resourceLeaks.add(String.format(
                    "%s (%s): opened %d times but closed only %d times (%d potential leaks)",
                    state.name, state.resourceType, opens, closes, opens - closes));
            }

            // Check for currently open resources at analysis time
            if (state.currentlyOpen) {
                long openTimeMs = state.lastOpenTime != null 
                    ? System.currentTimeMillis() - state.lastOpenTime 
                    : 0;
                report.openResources.add(String.format(
                    "%s (%s): resource still open (opened %dms ago)",
                    state.name, state.resourceType, openTimeMs));
            }

            // Track thread participation
            if (state.openingThreads.size() > 0) {
                report.threadActivity.put(state.name, String.format(
                    "%s: %d threads opened, %d threads closed, opens: %d, closes: %d",
                    state.resourceType,
                    state.openingThreads.size(),
                    state.closingThreads.size(),
                    opens, closes));
            }
        }

        return report;
    }

    /**
     * Report class for resource leak analysis.
     */
    public static class ResourceLeakReport {
        private boolean enabled = true;
        final java.util.List<String> resourceLeaks = new java.util.ArrayList<>();
        final java.util.List<String> openResources = new java.util.ArrayList<>();
        final Map<String, String> threadActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !resourceLeaks.isEmpty() || !openResources.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "ResourceLeakReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("RESOURCE LEAK ISSUES DETECTED:\n");

            if (!resourceLeaks.isEmpty()) {
                sb.append("  Resource Leaks:\n");
                for (String leak : resourceLeaks) {
                    sb.append("    - ").append(leak).append("\n");
                }
            }

            if (!openResources.isEmpty()) {
                sb.append("  Still Open Resources:\n");
                for (String open : openResources) {
                    sb.append("    - ").append(open).append("\n");
                }
            }

            if (!threadActivity.isEmpty()) {
                sb.append("  Thread Activity:\n");
                for (Map.Entry<String, String> entry : threadActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use try-with-resources or close in finally block");
            return sb.toString();
        }
    }
}
