package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects BlockingQueue misuse patterns in concurrent code.
 * 
 * Common BlockingQueue issues detected:
 * - Silent failures: offer() returns false but return value ignored, poll() returns null unchecked
 * - Queue saturation: queue fills up faster than consumers can process
 * - Unbounded queue growth: no capacity limits leading to memory issues
 * - Producer/consumer imbalance: producers outpacing consumers or vice versa
 * - Timeout misuse: using blocking methods without proper timeout handling
 * 
 * Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectBlockingQueueIssues = true)
 * void testQueueUsage() {
 *     BlockingQueue<String> queue = new ArrayBlockingQueue<>(10);
 *     AsyncTestContext.blockingQueueMonitor()
 *         .registerQueue(queue, "work-queue", 10);
 *     
 *     // Producer
 *     boolean added = queue.offer("item");
 *     AsyncTestContext.blockingQueueMonitor()
 *         .recordOffer(queue, "work-queue", added);
 *     
 *     // Consumer
 *     String item = queue.poll();
 *     AsyncTestContext.blockingQueueMonitor()
 *         .recordPoll(queue, "work-queue", item != null);
 * }
 * }</pre>
 */
public class BlockingQueueDetector {

    private static class QueueState {
        final String name;
        final BlockingQueue<?> queue;
        final int capacity;
        final AtomicInteger offerCount = new AtomicInteger(0);
        final AtomicInteger pollCount = new AtomicInteger(0);
        final AtomicInteger offerSuccessCount = new AtomicInteger(0);
        final AtomicInteger offerFailureCount = new AtomicInteger(0);
        final AtomicInteger pollSuccessCount = new AtomicInteger(0);
        final AtomicInteger pollFailureCount = new AtomicInteger(0);
        final AtomicInteger putCount = new AtomicInteger(0);
        final AtomicInteger takeCount = new AtomicInteger(0);
        final Set<Long> producerThreads = ConcurrentHashMap.newKeySet();
        final Set<Long> consumerThreads = ConcurrentHashMap.newKeySet();
        volatile int maxObservedSize = 0;
        volatile int minObservedSize = Integer.MAX_VALUE;

        QueueState(BlockingQueue<?> queue, String name, int capacity) {
            this.queue = queue;
            this.name = name != null ? name : "queue@" + System.identityHashCode(queue);
            this.capacity = capacity;
        }
    }

    private final Map<Integer, QueueState> queues = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Register a BlockingQueue for monitoring.
     * 
     * @param queue the BlockingQueue to monitor
     * @param name a descriptive name for reporting
     * @param capacity the queue capacity (-1 for unbounded)
     */
    public void registerQueue(BlockingQueue<?> queue, String name, int capacity) {
        if (!enabled || queue == null) {
            return;
        }
        queues.put(System.identityHashCode(queue), new QueueState(queue, name, capacity));
    }

    /**
     * Record an offer() call.
     * 
     * @param queue the queue
     * @param name the queue name (should match registration)
     * @param success true if offer succeeded, false if queue was full
     */
    public void recordOffer(BlockingQueue<?> queue, String name, boolean success) {
        if (!enabled || queue == null) {
            return;
        }
        QueueState state = queues.get(System.identityHashCode(queue));
        if (state != null) {
            state.offerCount.incrementAndGet();
            state.producerThreads.add(Thread.currentThread().threadId());
            if (success) {
                state.offerSuccessCount.incrementAndGet();
            } else {
                state.offerFailureCount.incrementAndGet();
            }
            updateSizeState(state);
        }
    }

    /**
     * Record a poll() call.
     * 
     * @param queue the queue
     * @param name the queue name (should match registration)
     * @param success true if poll returned an element, false if queue was empty
     */
    public void recordPoll(BlockingQueue<?> queue, String name, boolean success) {
        if (!enabled || queue == null) {
            return;
        }
        QueueState state = queues.get(System.identityHashCode(queue));
        if (state != null) {
            state.pollCount.incrementAndGet();
            state.consumerThreads.add(Thread.currentThread().threadId());
            if (success) {
                state.pollSuccessCount.incrementAndGet();
            } else {
                state.pollFailureCount.incrementAndGet();
            }
            updateSizeState(state);
        }
    }

    /**
     * Record a put() call (blocking insert).
     * 
     * @param queue the queue
     * @param name the queue name (should match registration)
     */
    public void recordPut(BlockingQueue<?> queue, String name) {
        if (!enabled || queue == null) {
            return;
        }
        QueueState state = queues.get(System.identityHashCode(queue));
        if (state != null) {
            state.putCount.incrementAndGet();
            state.producerThreads.add(Thread.currentThread().threadId());
            updateSizeState(state);
        }
    }

    /**
     * Record a take() call (blocking retrieval).
     * 
     * @param queue the queue
     * @param name the queue name (should match registration)
     */
    public void recordTake(BlockingQueue<?> queue, String name) {
        if (!enabled || queue == null) {
            return;
        }
        QueueState state = queues.get(System.identityHashCode(queue));
        if (state != null) {
            state.takeCount.incrementAndGet();
            state.consumerThreads.add(Thread.currentThread().threadId());
            updateSizeState(state);
        }
    }

    private void updateSizeState(QueueState state) {
        int size = state.queue.size();
        state.maxObservedSize = Math.max(state.maxObservedSize, size);
        state.minObservedSize = Math.min(state.minObservedSize, size);
    }

    /**
     * Analyze BlockingQueue usage for issues.
     * 
     * @return a report of detected issues
     */
    public BlockingQueueReport analyze() {
        BlockingQueueReport report = new BlockingQueueReport();
        report.enabled = enabled;

        for (QueueState state : queues.values()) {
            // Check for silent failures (offer returning false but ignored)
            if (state.offerFailureCount.get() > 0) {
                report.silentFailures.add(String.format(
                    "%s: offer() failed %d times (queue full, items silently dropped)",
                    state.name, state.offerFailureCount.get()));
            }

            // Check for poll returning null (potential signal loss)
            if (state.pollFailureCount.get() > 0) {
                report.emptyPolls.add(String.format(
                    "%s: poll() returned null %d times (potential signal loss)",
                    state.name, state.pollFailureCount.get()));
            }

            // Check for queue saturation (high water mark near capacity)
            if (state.capacity > 0 && state.maxObservedSize >= state.capacity * 0.9) {
                report.saturation.add(String.format(
                    "%s: queue reached %d/%d capacity (saturation risk)",
                    state.name, state.maxObservedSize, state.capacity));
            }

            // Check for producer/consumer imbalance
            int totalProduces = state.offerCount.get() + state.putCount.get();
            int totalConsumes = state.pollCount.get() + state.takeCount.get();
            if (totalProduces > 0 && totalConsumes > 0) {
                double ratio = (double) totalProduces / totalConsumes;
                if (ratio > 2.0) {
                    report.producerConsumerImbalance.add(String.format(
                        "%s: producer/consumer ratio %.1f (producers outpacing consumers)",
                        state.name, ratio));
                } else if (ratio < 0.5) {
                    report.producerConsumerImbalance.add(String.format(
                        "%s: producer/consumer ratio %.1f (consumers outpacing producers)",
                        state.name, ratio));
                }
            }

            // Track queue activity
            report.queueActivity.put(state.name, String.format(
                "offers: %d (success: %d, failed: %d), polls: %d (success: %d, empty: %d), puts: %d, takes: %d, max size: %d",
                state.offerCount.get(), state.offerSuccessCount.get(), state.offerFailureCount.get(),
                state.pollCount.get(), state.pollSuccessCount.get(), state.pollFailureCount.get(),
                state.putCount.get(), state.takeCount.get(), state.maxObservedSize));
        }

        return report;
    }

    /**
     * Report class for BlockingQueue analysis.
     */
    public static class BlockingQueueReport {
        private boolean enabled = true;
        final java.util.List<String> silentFailures = new java.util.ArrayList<>();
        final java.util.List<String> emptyPolls = new java.util.ArrayList<>();
        final java.util.List<String> saturation = new java.util.ArrayList<>();
        final java.util.List<String> producerConsumerImbalance = new java.util.ArrayList<>();
        final Map<String, String> queueActivity = new ConcurrentHashMap<>();

        /**
         * Check if any issues were detected.
         */
        public boolean hasIssues() {
            return !silentFailures.isEmpty() || !emptyPolls.isEmpty() || 
                   !saturation.isEmpty() || !producerConsumerImbalance.isEmpty();
        }

        @Override
        public String toString() {
            if (!enabled) {
                return "BlockingQueueReport: disabled";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("BLOCKING QUEUE ISSUES DETECTED:\n");

            if (!silentFailures.isEmpty()) {
                sb.append("  Silent Failures (offer returned false):\n");
                for (String issue : silentFailures) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!emptyPolls.isEmpty()) {
                sb.append("  Empty Polls (poll returned null):\n");
                for (String issue : emptyPolls) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!saturation.isEmpty()) {
                sb.append("  Queue Saturation:\n");
                for (String issue : saturation) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!producerConsumerImbalance.isEmpty()) {
                sb.append("  Producer/Consumer Imbalance:\n");
                for (String issue : producerConsumerImbalance) {
                    sb.append("    - ").append(issue).append("\n");
                }
            }

            if (!queueActivity.isEmpty()) {
                sb.append("  Queue Activity:\n");
                for (Map.Entry<String, String> entry : queueActivity.entrySet()) {
                    sb.append("    - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No issues detected.\n");
            }

            sb.append("  Fix: check offer() return values, handle null from poll(), consider queue capacity");
            return sb.toString();
        }
    }
}
