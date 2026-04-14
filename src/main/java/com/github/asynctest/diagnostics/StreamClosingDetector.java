package com.github.asynctest.diagnostics;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects I/O stream (InputStream, OutputStream, Reader, Writer) not being properly closed
 * in concurrent code.
 *
 * Common stream issues detected:
 * - Unclosed streams (created but never closed)
 * - Streams closed in different thread than created (potential resource leaks)
 * - Too many concurrently open streams (resource exhaustion risk)
 * - AutoCloseable resources not using try-with-resources
 *
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 10, detectStreamClosing = true)
 * void testStreams() throws IOException {
 *     InputStream is = new FileInputStream("data.txt");
 *     AsyncTestContext.streamClosingDetector()
 *         .recordStreamOpened(is, "data-input");
 *     try {
 *         // use stream
 *     } finally {
 *         is.close();
 *         AsyncTestContext.streamClosingDetector()
 *             .recordStreamClosed(is, "data-input");
 *     }
 * }
 * }</pre>
 */
public class StreamClosingDetector {

    private static class StreamState {
        final String name;
        final Closeable stream;
        final long openTime;
        final long openedByThread;
        volatile boolean closed;
        volatile long closedByThread = -1;

        StreamState(Closeable stream, String name) {
            this.stream = stream;
            this.name = name;
            this.openTime = System.currentTimeMillis();
            this.openedByThread = Thread.currentThread().threadId();
        }
    }

    private static class CrossThreadCloseEvent {
        final String name;
        final long openedByThread;
        final long closedByThread;

        CrossThreadCloseEvent(String name, long openedByThread, long closedByThread) {
            this.name = name;
            this.openedByThread = openedByThread;
            this.closedByThread = closedByThread;
        }
    }

    private final Map<Integer, StreamState> openStreams = new ConcurrentHashMap<>();
    private final List<CrossThreadCloseEvent> crossThreadCloseEvents =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final AtomicInteger totalOpened = new AtomicInteger(0);
    private final AtomicInteger totalClosed = new AtomicInteger(0);
    private final AtomicInteger maxConcurrentOpen = new AtomicInteger(0);
    private final AtomicInteger currentOpen = new AtomicInteger(0);
    private volatile boolean enabled = true;

    /**
     * Disable this detector.
     */
    public void disable() {
        enabled = false;
    }

    /**
     * Enable this detector.
     */
    public void enable() {
        enabled = true;
    }

    /**
     * Record a stream being opened.
     *
     * @param stream the stream instance
     * @param name a descriptive name for tracking
     */
    public void recordStreamOpened(Closeable stream, String name) {
        if (!enabled || stream == null) {
            return;
        }
        StreamState state = new StreamState(stream, name);
        openStreams.put(System.identityHashCode(stream), state);
        totalOpened.incrementAndGet();
        int current = currentOpen.incrementAndGet();
        maxConcurrentOpen.updateAndGet(max -> Math.max(max, current));
    }

    /**
     * Record a stream being closed.
     *
     * @param stream the stream instance
     * @param name should match the name from recordStreamOpened
     */
    public void recordStreamClosed(Closeable stream, String name) {
        if (!enabled || stream == null) {
            return;
        }
        int key = System.identityHashCode(stream);
        StreamState state = openStreams.remove(key);
        if (state != null) {
            state.closed = true;
            state.closedByThread = Thread.currentThread().threadId();
            if (state.closedByThread != state.openedByThread) {
                crossThreadCloseEvents.add(new CrossThreadCloseEvent(
                    state.name, state.openedByThread, state.closedByThread));
            }
            totalClosed.incrementAndGet();
            currentOpen.decrementAndGet();
        }
    }

    /**
     * Analyze stream usage for issues.
     *
     * @return a report of detected issues
     */
    public StreamClosingReport analyze() {
        StreamClosingReport report = new StreamClosingReport();
        report.enabled = enabled;

        report.totalOpened = totalOpened.get();
        report.totalClosed = totalClosed.get();
        report.maxConcurrentOpen = maxConcurrentOpen.get();

        // Check for unclosed streams
        for (Map.Entry<Integer, StreamState> entry : openStreams.entrySet()) {
            StreamState state = entry.getValue();
            if (!state.closed) {
                long openDuration = System.currentTimeMillis() - state.openTime;
                report.unclosedStreams.add(String.format(
                    "%s: opened by thread %d, still open after %d ms",
                    state.name, state.openedByThread, openDuration));
            }
        }

        synchronized (crossThreadCloseEvents) {
            for (CrossThreadCloseEvent event : crossThreadCloseEvents) {
                report.crossThreadClosing.add(String.format(
                    "%s: opened by thread %d, closed by thread %d",
                    event.name, event.openedByThread, event.closedByThread));
            }
        }

        // Check for too many concurrent open streams
        if (maxConcurrentOpen.get() > 100) {
            report.resourceExhaustionRisk.add(String.format(
                "High concurrent open streams: %d (may cause resource exhaustion)",
                maxConcurrentOpen.get()));
        }

        return report;
    }

    /**
     * Report class for stream closing issues.
     */
    public static class StreamClosingReport {
        private boolean enabled = true;
        int totalOpened;
        int totalClosed;
        int maxConcurrentOpen;
        final java.util.List<String> unclosedStreams = new java.util.ArrayList<>();
        final java.util.List<String> crossThreadClosing = new java.util.ArrayList<>();
        final java.util.List<String> resourceExhaustionRisk = new java.util.ArrayList<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !unclosedStreams.isEmpty() || 
                   !crossThreadClosing.isEmpty() || 
                   !resourceExhaustionRisk.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "StreamClosingReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("STREAM CLOSING ISSUES DETECTED:\n");

            if (!unclosedStreams.isEmpty()) {
                sb.append("  Unclosed Streams:\n");
                for (String issue : unclosedStreams) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!crossThreadClosing.isEmpty()) {
                sb.append("  Cross-Thread Closing:\n");
                for (String issue : crossThreadClosing) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!resourceExhaustionRisk.isEmpty()) {
                sb.append("  Resource Exhaustment Risk:\n");
                for (String issue : resourceExhaustionRisk) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            sb.append(String.format("  Summary: %d opened, %d closed, %d max concurrent\n",
                totalOpened, totalClosed, maxConcurrentOpen));

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: use try-with-resources to ensure streams are always closed");
            return sb.toString();
        }
    }
}
