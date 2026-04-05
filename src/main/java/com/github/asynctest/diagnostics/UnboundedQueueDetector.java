package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects unbounded queue usage in concurrent code.
 *
 * Unbounded queues (e.g., {@code LinkedBlockingQueue} without capacity,
 * {@code Executors.newCachedThreadPool()}) can lead to:
 * - OutOfMemoryError: unbounded growth under high load
 * - Performance degradation: excessive memory allocation and GC pressure
 * - Silent failures: producer/consumer imbalance goes undetected
 *
 * <p>The detector identifies:
 * - BlockingQueue instances created without capacity bounds
 * - Queue size growth beyond reasonable thresholds
 * - Executors created with unbounded thread pools
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectUnboundedQueue = true)
 * void testUnboundedQueue() {
 *     BlockingQueue<String> queue = new LinkedBlockingQueue<>();  // Unbounded!
 *     AsyncTestContext.unboundedQueueDetector()
 *         .recordQueueCreation(queue, "task-queue", -1);  // -1 = unbounded
 *
 *     // Or with bounded queue:
 *     BlockingQueue<String> bounded = new LinkedBlockingQueue<>(100);
 *     AsyncTestContext.unboundedQueueDetector()
 *         .recordQueueCreation(bounded, "bounded-queue", 100);
 * }
 * }</pre>
 */
public class UnboundedQueueDetector {

    private static class QueueState {
        final String name;
        final BlockingQueue<?> queue;
        final int capacity; // -1 for unbounded
        final AtomicInteger peakSize = new AtomicInteger(0);
        final AtomicInteger enqueueCount = new AtomicInteger(0);
        final AtomicInteger dequeueCount = new AtomicInteger(0);
        final StackTraceElement[] creationStack;

        QueueState(BlockingQueue<?> queue, String name, int capacity) {
            this.queue = queue;
            this.name = name;
            this.capacity = capacity;
            this.creationStack = Thread.currentThread().getStackTrace();
        }
    }

    private final Map<Integer, QueueState> trackedQueues = new ConcurrentHashMap<>();
    private final List<UnboundedQueueEvent> events = new ArrayList<>();
    private volatile boolean enabled = true;
    private volatile int warningThreshold = 1000; // Warn if queue grows beyond this

    /**
     * Record a queue creation for monitoring.
     *
     * @param queue the queue instance
     * @param name a descriptive name
     * @param capacity the capacity (-1 for unbounded, Integer.MAX_VALUE for effectively unbounded)
     */
    public void recordQueueCreation(BlockingQueue<?> queue, String name, int capacity) {
        if (!enabled || queue == null) {
            return;
        }

        boolean isUnbounded = capacity < 0 || capacity == Integer.MAX_VALUE;
        QueueState state = new QueueState(queue, name, capacity);
        trackedQueues.put(System.identityHashCode(queue), state);

        if (isUnbounded) {
            UnboundedQueueEvent event = new UnboundedQueueEvent(
                name,
                "Unbounded queue created",
                capacity,
                state.creationStack,
                "Use bounded queues with rejection policies to prevent OOM"
            );
            synchronized (events) {
                events.add(event);
            }
        }
    }

    /**
     * Record an enqueue operation.
     *
     * @param queue the queue
     */
    public void recordEnqueue(BlockingQueue<?> queue) {
        if (!enabled || queue == null) return;

        QueueState state = trackedQueues.get(System.identityHashCode(queue));
        if (state != null) {
            state.enqueueCount.incrementAndGet();
            int currentSize = queue.size();
            int peak = state.peakSize.get();
            while (currentSize > peak) {
                if (state.peakSize.compareAndSet(peak, currentSize)) {
                    break;
                }
                peak = state.peakSize.get();
            }

            // Check if queue exceeded warning threshold
            if (currentSize > warningThreshold && state.capacity < 0) {
                // Only warn once per queue
                if (currentSize == warningThreshold + 1) {
                    UnboundedQueueEvent event = new UnboundedQueueEvent(
                        state.name,
                        "Queue size exceeded warning threshold (" + warningThreshold + ")",
                        state.capacity,
                        null,
                        "Consider using bounded queue with rejection policy"
                    );
                    synchronized (events) {
                        events.add(event);
                    }
                }
            }
        }
    }

    /**
     * Record a dequeue operation.
     *
     * @param queue the queue
     */
    public void recordDequeue(BlockingQueue<?> queue) {
        if (!enabled || queue == null) return;

        QueueState state = trackedQueues.get(System.identityHashCode(queue));
        if (state != null) {
            state.dequeueCount.incrementAndGet();
        }
    }

    /**
     * Analyze queue usage and detect issues.
     *
     * @return analysis report
     */
    public UnboundedQueueReport analyze() {
        if (!enabled) {
            return new UnboundedQueueReport(List.of(), 0, 0, false);
        }

        List<UnboundedQueueEvent> allEvents;
        int unboundedCount = 0;
        int totalTracked = trackedQueues.size();

        synchronized (events) {
            allEvents = new ArrayList<>(events);
        }

        // Check current queue states
        for (QueueState state : trackedQueues.values()) {
            boolean isUnbounded = state.capacity < 0 || state.capacity == Integer.MAX_VALUE;
            if (isUnbounded) {
                unboundedCount++;
            }

            // Check for producer/consumer imbalance
            int enqueue = state.enqueueCount.get();
            int dequeue = state.dequeueCount.get();
            int imbalance = enqueue - dequeue;
            if (imbalance > warningThreshold && isUnbounded) {
                allEvents.add(new UnboundedQueueEvent(
                    state.name,
                    String.format("Producer/consumer imbalance: %d enqueued, %d dequeued (imbalance: %d)",
                        enqueue, dequeue, imbalance),
                    state.capacity,
                    null,
                    "Consumers may not be keeping up with producers"
                ));
            }
        }

        return new UnboundedQueueReport(allEvents, unboundedCount, totalTracked, true);
    }

    /**
     * Clear all tracked data.
     */
    public void clear() {
        trackedQueues.clear();
        synchronized (events) {
            events.clear();
        }
    }

    public void disable() {
        this.enabled = false;
    }

    /**
     * Set the warning threshold for queue size.
     */
    public void setWarningThreshold(int threshold) {
        this.warningThreshold = threshold;
    }

    /**
     * An unbounded queue event.
     */
    public static class UnboundedQueueEvent {
        public final String queueName;
        public final String description;
        public final int capacity;
        public final StackTraceElement[] creationStack;
        public final String fixSuggestion;

        UnboundedQueueEvent(String queueName, String description, int capacity,
                           StackTraceElement[] creationStack, String fixSuggestion) {
            this.queueName = queueName;
            this.description = description;
            this.capacity = capacity;
            this.creationStack = creationStack;
            this.fixSuggestion = fixSuggestion;
        }
    }

    /**
     * Report of unbounded queue analysis.
     */
    public static class UnboundedQueueReport {
        private final List<UnboundedQueueEvent> events;
        private final int unboundedCount;
        private final int totalTracked;
        private final boolean enabled;

        UnboundedQueueReport(List<UnboundedQueueEvent> events, int unboundedCount,
                            int totalTracked, boolean enabled) {
            this.events = events;
            this.unboundedCount = unboundedCount;
            this.totalTracked = totalTracked;
            this.enabled = enabled;
        }

        public boolean hasIssues() {
            return !events.isEmpty();
        }

        public List<UnboundedQueueEvent> getEvents() {
            return List.copyOf(events);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("UnboundedQueueReport:\n");
            sb.append("  Total tracked: ").append(totalTracked).append("\n");
            sb.append("  Unbounded: ").append(unboundedCount).append("\n");

            if (events.isEmpty()) {
                sb.append("  Status: No unbounded queue issues detected ✓\n");
            } else {
                sb.append("  UNBOUNDED QUEUE ISSUES DETECTED:\n");
                for (int i = 0; i < events.size(); i++) {
                    UnboundedQueueEvent event = events.get(i);
                    sb.append("  [").append(i + 1).append("] ").append(event.queueName).append("\n");
                    sb.append("      Issue: ").append(event.description).append("\n");
                    if (event.capacity < 0) {
                        sb.append("      Capacity: Unbounded\n");
                    } else {
                        sb.append("      Capacity: ").append(event.capacity).append("\n");
                    }
                    sb.append("      Fix: ").append(event.fixSuggestion).append("\n");
                    if (event.creationStack != null && event.creationStack.length > 3) {
                        sb.append("      Created at:\n");
                        for (int j = 3; j < Math.min(6, event.creationStack.length); j++) {
                            sb.append("        at ").append(event.creationStack[j]).append("\n");
                        }
                    }
                }
            }
            return sb.toString();
        }
    }
}
