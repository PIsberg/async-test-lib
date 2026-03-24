package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks caught interrupts and whether they were restored or ignored.
 */
public class InterruptMonitor {

    private static class InterruptEvent {
        final long threadId;
        final String threadName;
        final String location;
        volatile boolean restored;

        InterruptEvent(long threadId, String threadName, String location, boolean restored) {
            this.threadId = threadId;
            this.threadName = threadName;
            this.location = location;
            this.restored = restored;
        }
    }

    private final List<InterruptEvent> interruptEvents = new ArrayList<>();
    private final Map<Long, Integer> threadInterruptCounts = new ConcurrentHashMap<>();
    private final Set<String> ignoredDescriptions = ConcurrentHashMap.newKeySet();
    private final Set<String> blockingWithoutHandling = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;

    public void recordInterruptException(InterruptedException ex) {
        if (!enabled) {
            return;
        }

        long threadId = Thread.currentThread().threadId();
        InterruptEvent event = new InterruptEvent(
            threadId,
            Thread.currentThread().getName(),
            inferCallSite(),
            Thread.currentThread().isInterrupted()
        );

        synchronized (interruptEvents) {
            interruptEvents.add(event);
        }
        threadInterruptCounts.merge(threadId, 1, Integer::sum);
    }

    public void recordInterruptRestored() {
        if (!enabled) {
            return;
        }

        long threadId = Thread.currentThread().threadId();
        synchronized (interruptEvents) {
            for (int i = interruptEvents.size() - 1; i >= 0; i--) {
                InterruptEvent event = interruptEvents.get(i);
                if (event.threadId == threadId) {
                    event.restored = true;
                    break;
                }
            }
        }
    }

    public void recordIgnoredException(String description) {
        if (!enabled) {
            return;
        }

        ignoredDescriptions.add(String.format(
            "Thread %s (%d): ignored InterruptedException - %s",
            Thread.currentThread().getName(),
            Thread.currentThread().threadId(),
            description
        ));
    }

    public void recordBlockingOperationWithoutInterruptHandling(String operationName) {
        if (!enabled) {
            return;
        }

        blockingWithoutHandling.add(String.format(
            "Thread %s (%d): blocking operation '%s' without interrupt handling",
            Thread.currentThread().getName(),
            Thread.currentThread().threadId(),
            operationName
        ));
    }

    private String inferCallSite() {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        return trace.length > 3 ? trace[3].toString() : "unknown";
    }

    public InterruptReport analyzeInterruptHandling() {
        InterruptReport report = new InterruptReport();

        synchronized (interruptEvents) {
            for (InterruptEvent event : interruptEvents) {
                if (!event.restored) {
                    report.ignoredInterrupts.add(String.format(
                        "Thread %s (%d): interrupt caught but not restored at %s",
                        event.threadName, event.threadId, event.location
                    ));
                }
            }
        }

        for (Map.Entry<Long, Integer> entry : threadInterruptCounts.entrySet()) {
            if (entry.getValue() > 1) {
                report.repeatedIgnoredInterrupts.add(String.format(
                    "Thread %d: caught InterruptedException %d times",
                    entry.getKey(),
                    entry.getValue()
                ));
            }
        }

        report.ignoredInterrupts.addAll(ignoredDescriptions);
        report.blockingWithoutHandling.addAll(blockingWithoutHandling);
        return report;
    }

    public void reset() {
        synchronized (interruptEvents) {
            interruptEvents.clear();
        }
        threadInterruptCounts.clear();
        ignoredDescriptions.clear();
        blockingWithoutHandling.clear();
    }

    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
    }

    public static class InterruptReport {
        public final Set<String> ignoredInterrupts = new HashSet<>();
        public final Set<String> repeatedIgnoredInterrupts = new HashSet<>();
        public final Set<String> blockingWithoutHandling = new HashSet<>();

        public boolean hasIssues() {
            return !ignoredInterrupts.isEmpty()
                || !repeatedIgnoredInterrupts.isEmpty()
                || !blockingWithoutHandling.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No interrupt handling issues detected.";
            }

            StringBuilder sb = new StringBuilder("INTERRUPT HANDLING ISSUES DETECTED:\n");
            if (!repeatedIgnoredInterrupts.isEmpty()) {
                sb.append("\nRepeated ignored interrupts:\n");
                for (String interrupt : repeatedIgnoredInterrupts) {
                    sb.append("  - ").append(interrupt).append('\n');
                }
            }
            if (!ignoredInterrupts.isEmpty()) {
                sb.append("\nIgnored interrupts:\n");
                for (String interrupt : ignoredInterrupts) {
                    sb.append("  - ").append(interrupt).append('\n');
                }
            }
            if (!blockingWithoutHandling.isEmpty()) {
                sb.append("\nBlocking calls without interrupt handling:\n");
                for (String blocking : blockingWithoutHandling) {
                    sb.append("  - ").append(blocking).append('\n');
                }
            }
            sb.append("\nFix: propagate InterruptedException or restore status with Thread.currentThread().interrupt()");
            return sb.toString();
        }
    }
}
