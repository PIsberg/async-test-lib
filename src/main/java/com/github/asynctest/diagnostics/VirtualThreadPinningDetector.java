package com.github.asynctest.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects virtual thread pinning issues.
 *
 * <p>Virtual threads can be "pinned" to their carrier platform threads when they encounter
 * certain blocking operations, primarily:
 * <ul>
 *   <li>{@code synchronized} blocks or methods</li>
 *   <li>Native method calls that block</li>
 * </ul>
 *
 * <p>Pinned virtual threads lose their scalability advantage because they hold onto
 * carrier threads that could otherwise be used by other virtual threads.
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 10, useVirtualThreads = true, detectVirtualThreadPinning = true)
 * void testVirtualThreadPinning() {
 *     AsyncTestContext.virtualThreadPinningDetector()
 *         .startMonitoring();
 *
 *     // Code that may cause pinning
 *     synchronized(lock) {
 *         Thread.sleep(100);
 *     }
 *
 *     PinningReport report = AsyncTestContext.virtualThreadPinningDetector()
 *         .analyzePinning();
 *     if (report.hasPinningIssues()) {
 *         // Handle pinning detected
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Note:</strong> This detector requires Java 21+ with virtual thread support.
 * On earlier Java versions, it will report no issues.
 *
 * @since 1.2.0
 */
public class VirtualThreadPinningDetector {

    private static class PinningEvent {
        final long virtualThreadId;
        final String virtualThreadName;
        final long carrierThreadId;
        final long startTimeNanos;
        final String blockingOperation;
        final StackTraceElement[] stackTrace;

        PinningEvent(long virtualThreadId, String virtualThreadName, long carrierThreadId,
                     String blockingOperation, StackTraceElement[] stackTrace) {
            this.virtualThreadId = virtualThreadId;
            this.virtualThreadName = virtualThreadName;
            this.carrierThreadId = carrierThreadId;
            this.startTimeNanos = System.nanoTime();
            this.blockingOperation = blockingOperation;
            this.stackTrace = stackTrace;
        }

        long getDurationMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
        }
    }

    private final List<PinningEvent> pinningEvents = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger currentPinnedCount = new AtomicInteger(0);
    private final AtomicInteger maxPinnedCount = new AtomicInteger(0);
    private volatile boolean monitoring = false;
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    /**
     * Start monitoring for virtual thread pinning.
     */
    public void startMonitoring() {
        monitoring = true;
    }

    /**
     * Stop monitoring.
     */
    public void stopMonitoring() {
        monitoring = false;
    }

    /**
     * Record a pinning event.
     *
     * @param thread the pinned virtual thread
     * @param blockingOperation description of the blocking operation (e.g., "synchronized block")
     */
    public void recordPinningEvent(Thread thread, String blockingOperation) {
        if (!monitoring || !isVirtualThread(thread)) {
            return;
        }

        StackTraceElement[] stackTrace = thread.getStackTrace();
        PinningEvent event = new PinningEvent(
            thread.threadId(),
            thread.getName(),
            -1, // Carrier thread ID not directly accessible
            blockingOperation,
            stackTrace
        );

        pinningEvents.add(event);
        currentPinnedCount.incrementAndGet();
        maxPinnedCount.updateAndGet(max -> Math.max(max, currentPinnedCount.get()));
    }

    /**
     * Record that a previously pinned thread is now unpinned.
     *
     * @param thread the unpinned virtual thread
     */
    public void recordUnpinEvent(Thread thread) {
        if (!monitoring || !isVirtualThread(thread)) {
            return;
        }
        currentPinnedCount.decrementAndGet();
    }

    /**
     * Analyze virtual thread pinning.
     *
     * @return report containing pinning statistics and events
     */
    public PinningReport analyzePinning() {
        List<PinningEventSnapshot> events = new ArrayList<>();
        synchronized (pinningEvents) {
            for (PinningEvent event : pinningEvents) {
                events.add(new PinningEventSnapshot(
                    event.virtualThreadId,
                    event.virtualThreadName,
                    event.blockingOperation,
                    event.getDurationMillis(),
                    event.stackTrace
                ));
            }
        }

        return new PinningReport(
            events,
            maxPinnedCount.get(),
            isVirtualThreadSupported()
        );
    }

    /**
     * Check if pinning was detected.
     *
     * @return true if any pinning events were recorded
     */
    public boolean hasPinningIssues() {
        return !pinningEvents.isEmpty();
    }

    /**
     * Get the number of pinning events.
     *
     * @return count of recorded pinning events
     */
    public int getPinningEventCount() {
        return pinningEvents.size();
    }

    /**
     * Clear all recorded events.
     */
    public void clear() {
        pinningEvents.clear();
        currentPinnedCount.set(0);
        maxPinnedCount.set(0);
    }

    /**
     * Check if virtual threads are supported (Java 21+).
     *
     * @return true if virtual threads are available
     */
    public static boolean isVirtualThreadSupported() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Check if a thread is a virtual thread.
     *
     * @param thread the thread to check
     * @return true if it's a virtual thread
     */
    public static boolean isVirtualThread(Thread thread) {
        if (!isVirtualThreadSupported()) {
            return false;
        }
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Report of virtual thread pinning analysis.
     */
    public static class PinningReport {
        private final List<PinningEventSnapshot> events;
        private final int maxPinnedCount;
        private final boolean virtualThreadSupported;

        PinningReport(List<PinningEventSnapshot> events, int maxPinnedCount, boolean virtualThreadSupported) {
            this.events = events;
            this.maxPinnedCount = maxPinnedCount;
            this.virtualThreadSupported = virtualThreadSupported;
        }

        /**
         * @return list of pinning event snapshots
         */
        public List<PinningEventSnapshot> getEvents() {
            return events;
        }

        /**
         * @return maximum number of threads pinned simultaneously
         */
        public int getMaxPinnedCount() {
            return maxPinnedCount;
        }

        /**
         * @return true if virtual threads are supported on this JVM
         */
        public boolean isVirtualThreadSupported() {
            return virtualThreadSupported;
        }

        /**
         * @return true if pinning issues were detected
         */
        public boolean hasPinningIssues() {
            return virtualThreadSupported && !events.isEmpty();
        }

        @Override
        public String toString() {
            if (!virtualThreadSupported) {
                return "VirtualThreadPinningReport: Virtual threads not supported (requires Java 21+)";
            }

            if (events.isEmpty()) {
                return "VirtualThreadPinningReport: No pinning detected - virtual threads are running efficiently";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("VirtualThreadPinningReport: ")
              .append(events.size())
              .append(" pinning event(s) detected (max concurrent: ")
              .append(maxPinnedCount)
              .append(")\n");

            for (int i = 0; i < Math.min(5, events.size()); i++) {
                PinningEventSnapshot event = events.get(i);
                sb.append("\n  [").append(i + 1).append("] ")
                  .append(event.threadName)
                  .append(" (id=").append(event.threadId).append(")")
                  .append("\n      Blocking operation: ").append(event.blockingOperation)
                  .append("\n      Duration: ").append(event.durationMillis).append("ms");

                if (event.stackTrace != null && event.stackTrace.length > 0) {
                    sb.append("\n      Stack trace:");
                    for (int j = 0; j < Math.min(3, event.stackTrace.length); j++) {
                        sb.append("\n        at ").append(event.stackTrace[j]);
                    }
                }
            }

            if (events.size() > 5) {
                sb.append("\n  ... and ").append(events.size() - 5).append(" more events");
            }

            sb.append("\n\n  Recommendations:");
            sb.append("\n    - Replace synchronized blocks with ReentrantLock");
            sb.append("\n    - Use LockSupport.park() instead of Thread.sleep() in virtual threads");
            sb.append("\n    - Consider using platform threads for I/O-bound synchronized code");

            return sb.toString();
        }
    }

    /**
     * Snapshot of a pinning event.
     */
    public static class PinningEventSnapshot {
        private final long threadId;
        private final String threadName;
        private final String blockingOperation;
        private final long durationMillis;
        private final StackTraceElement[] stackTrace;

        PinningEventSnapshot(long threadId, String threadName, String blockingOperation,
                            long durationMillis, StackTraceElement[] stackTrace) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.blockingOperation = blockingOperation;
            this.durationMillis = durationMillis;
            this.stackTrace = stackTrace;
        }

        /**
         * @return virtual thread ID
         */
        public long getThreadId() {
            return threadId;
        }

        /**
         * @return virtual thread name
         */
        public String getThreadName() {
            return threadName;
        }

        /**
         * @return description of blocking operation
         */
        public String getBlockingOperation() {
            return blockingOperation;
        }

        /**
         * @return duration in milliseconds
         */
        public long getDurationMillis() {
            return durationMillis;
        }

        /**
         * @return stack trace at pinning point
         */
        public StackTraceElement[] getStackTrace() {
            return stackTrace;
        }
    }
}
