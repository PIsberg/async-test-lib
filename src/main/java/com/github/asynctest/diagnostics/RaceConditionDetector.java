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
    }

    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
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

            StringBuilder sb = new StringBuilder("POTENTIAL RACE CONDITIONS DETECTED:\n");
            if (!unsafeAccesses.isEmpty()) {
                sb.append("\nUnsynchronized access sequences:\n");
                for (String access : unsafeAccesses) {
                    sb.append("  - ").append(access).append('\n');
                }
            }
            if (!potentialRaces.isEmpty()) {
                sb.append("\nConcurrent write hotspots:\n");
                for (String race : potentialRaces) {
                    sb.append("  - ").append(race).append('\n');
                }
            }
            sb.append("\nFix: guard shared state with synchronization or atomic types");
            return sb.toString();
        }
    }
}
