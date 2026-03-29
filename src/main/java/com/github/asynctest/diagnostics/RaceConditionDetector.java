package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects potential race conditions by tracking cross-thread field accesses.
 */
public class RaceConditionDetector {

    private static class FieldAccess {
        final long threadId;
        final long timestamp;
        final boolean write;

        FieldAccess(long threadId, boolean write) {
            this.threadId = threadId;
            this.timestamp = System.nanoTime();
            this.write = write;
        }
    }

    private static class ObjectFieldState {
        final String className;
        final int objectId;
        final Map<String, List<FieldAccess>> fieldAccesses = new ConcurrentHashMap<>();

        ObjectFieldState(String className, int objectId) {
            this.className = className;
            this.objectId = objectId;
        }
    }

    private final Map<Integer, ObjectFieldState> objects = new ConcurrentHashMap<>();
    private final IssueDeduplicator<RaceConditionEvent> deduplicator = new IssueDeduplicator<>();
    private volatile boolean enabled = true;

    public void recordFieldRead(Object object, String fieldName) {
        if (!enabled || object == null || fieldName == null || fieldName.isBlank()) {
            return;
        }
        recordAccess(object, fieldName, false);
    }

    public void recordFieldWrite(Object object, String fieldName) {
        if (!enabled || object == null || fieldName == null || fieldName.isBlank()) {
            return;
        }
        recordAccess(object, fieldName, true);
    }

    private void recordAccess(Object object, String fieldName, boolean write) {
        int objectId = System.identityHashCode(object);
        ObjectFieldState state = objects.computeIfAbsent(
            objectId,
            ignored -> new ObjectFieldState(object.getClass().getSimpleName(), objectId)
        );

        state.fieldAccesses.computeIfAbsent(fieldName, ignored -> Collections.synchronizedList(new ArrayList<>()))
            .add(new FieldAccess(Thread.currentThread().threadId(), write));
    }

    public RaceConditionReport analyzeRaceConditions() {
        RaceConditionReport report = new RaceConditionReport();

        for (ObjectFieldState state : objects.values()) {
            for (Map.Entry<String, List<FieldAccess>> entry : state.fieldAccesses.entrySet()) {
                String fieldName = entry.getKey();
                List<FieldAccess> accesses = entry.getValue();
                if (accesses.size() < 2) {
                    continue;
                }

                Set<Long> threads = new HashSet<>();
                boolean hasWrite = false;
                int writeCount = 0;
                synchronized (accesses) {
                    for (FieldAccess access : accesses) {
                        threads.add(access.threadId);
                        if (access.write) {
                            hasWrite = true;
                            writeCount++;
                        }
                    }
                }

                if (threads.size() < 2 || !hasWrite) {
                    continue;
                }

                String fieldRef = String.format("%s@%x.%s", state.className, state.objectId, fieldName);
                
                // Record events for deduplication
                for (FieldAccess access : accesses) {
                    if (access.write) {
                        deduplicator.record(new RaceConditionEvent(
                            "RaceCondition",
                            fieldRef,
                            -1, // Line number unknown in this detector
                            access.threadId
                        ));
                    }
                }
                
                if (writeCount > 1) {
                    report.potentialRaces.add(String.format(
                        "%s: %d writes observed across %d threads",
                        fieldRef, writeCount, threads.size()
                    ));
                }

                List<FieldAccess> snapshot;
                synchronized (accesses) {
                    snapshot = new ArrayList<>(accesses);
                }
                snapshot.sort((left, right) -> Long.compare(left.timestamp, right.timestamp));

                for (int i = 1; i < snapshot.size(); i++) {
                    FieldAccess previous = snapshot.get(i - 1);
                    FieldAccess current = snapshot.get(i);
                    if (previous.threadId != current.threadId && (previous.write || current.write)) {
                        report.unsafeAccesses.add(String.format(
                            "%s: thread %d %s followed by thread %d %s",
                            fieldRef,
                            previous.threadId,
                            previous.write ? "write" : "read",
                            current.threadId,
                            current.write ? "write" : "read"
                        ));
                        break;
                    }
                }
            }
        }

        return report;
    }

    public void reset() {
        objects.clear();
        deduplicator.clear();
    }

    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
    }

    /**
     * Get the deduplicator for this detector.
     * @return the issue deduplicator
     */
    public IssueDeduplicator<RaceConditionEvent> getDeduplicator() {
        return deduplicator;
    }

    /**
     * Race condition event for deduplication.
     */
    public static class RaceConditionEvent implements DeduplicatableEvent {
        private final String type;
        private final String location;
        private final int lineNumber;
        private final long threadId;

        public RaceConditionEvent(String type, String location, int lineNumber, long threadId) {
            this.type = type;
            this.location = location;
            this.lineNumber = lineNumber;
            this.threadId = threadId;
        }

        @Override
        public String getFingerprint() {
            // Same location = same issue (regardless of thread)
            return type + ":" + location;
        }

        @Override
        public long getThreadId() {
            return threadId;
        }

        @Override
        public String getLocation() {
            return location;
        }

        @Override
        public int getLineNumber() {
            return lineNumber;
        }

        @Override
        public String getType() {
            return type;
        }
    }

    public static class RaceConditionReport {
        public final Set<String> unsafeAccesses = new HashSet<>();
        public final Set<String> potentialRaces = new HashSet<>();

        public boolean hasIssues() {
            return !unsafeAccesses.isEmpty() || !potentialRaces.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No race conditions detected.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(IssueSeverity.HIGH.format())
              .append(": Potential race conditions detected\n\n");

            if (!potentialRaces.isEmpty()) {
                sb.append("Concurrent write hotspots:\n");
                for (String race : potentialRaces) {
                    sb.append("  - ").append(race).append('\n');
                }
            }

            if (!unsafeAccesses.isEmpty()) {
                sb.append("\nUnsynchronized access sequences:\n");
                for (String access : unsafeAccesses) {
                    sb.append("  - ").append(access).append('\n');
                }
            }

            // Add deduplication summary
            sb.append("\n").append("=".repeat(60));
            sb.append("\n").append(LearningContent.getRaceConditionExplanation());
            sb.append(AutoFix.getRaceConditionFix());
            sb.append("=".repeat(60));

            return sb.toString();
        }
    }
}
