package se.deversity.asynctest.diagnostics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
        final Map<String, Queue<FieldAccess>> fieldAccesses = new ConcurrentHashMap<>();

        ObjectFieldState(String className, int objectId) {
            this.className = className;
            this.objectId = objectId;
        }
    }

    private final Map<Integer, ObjectFieldState> objects = new ConcurrentHashMap<>();
    private final IssueDeduplicator<RaceConditionEvent> deduplicator = new IssueDeduplicator<>();
    private volatile boolean enabled = true;
    /**
     * Records field read so it can be analysed at the end of the run.
     *
     * @param object the object the access is on, tracked by identity
     * @param fieldName the field involved, as it should appear in the report
     */
    public void recordFieldRead(Object object, String fieldName) {
        if (!enabled || object == null || fieldName == null || fieldName.isBlank()) {
            return;
        }
        recordAccess(object, fieldName, false);
    }
    /**
     * Records field write so it can be analysed at the end of the run.
     *
     * @param object the object the access is on, tracked by identity
     * @param fieldName the field involved, as it should appear in the report
     */
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

        // ConcurrentLinkedQueue, deliberately not a synchronizedList: this method runs on
        // the racing threads themselves, between the very accesses being hunted. A shared
        // monitor here is a probe effect — it serializes the racing threads at every record
        // (and pins virtual threads to their carrier on JDK < 24), which can mask the race
        // this detector exists to find. A lock-free CAS enqueue keeps the cross-thread
        // rendezvous to a single cache line and never parks a recording thread.
        state.fieldAccesses.computeIfAbsent(fieldName, ignored -> new ConcurrentLinkedQueue<>())
            .add(new FieldAccess(Thread.currentThread().threadId(), write));
    }
    /**
     * Analyses what has been recorded about race conditions and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public RaceConditionReport analyzeRaceConditions() {
        RaceConditionReport report = new RaceConditionReport();

        for (ObjectFieldState state : objects.values()) {
            for (Map.Entry<String, Queue<FieldAccess>> entry : state.fieldAccesses.entrySet()) {
                String fieldName = entry.getKey();
                // One weakly-consistent snapshot per field, taken up front: safe against
                // concurrent recordAccess (the runner's timeout path analyzes while
                // cancelled workers may still be unwinding), and every check below then
                // reasons about the same fixed data instead of a moving target.
                List<FieldAccess> snapshot = new ArrayList<>(entry.getValue());
                if (snapshot.size() < 2) {
                    continue;
                }

                Set<Long> threads = new HashSet<>();
                boolean hasWrite = false;
                int writeCount = 0;
                for (FieldAccess access : snapshot) {
                    threads.add(access.threadId);
                    if (access.write) {
                        hasWrite = true;
                        writeCount++;
                    }
                }

                if (threads.size() < 2 || !hasWrite) {
                    continue;
                }

                String fieldRef = String.format("%s@%x.%s", state.className, state.objectId, fieldName);

                // Record events for deduplication
                for (FieldAccess access : snapshot) {
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

    /**
     * Standardized alias for {@link #analyzeRaceConditions()}.
     *
     * @return the findings this detector collected during the run
     */
    public RaceConditionReport analyze() {
        return analyzeRaceConditions();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        objects.clear();
        deduplicator.clear();
    }
    /**
     * Disable.
     */
    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */
    public void enable() {
        enabled = true;
    }

    /**
     * Get the deduplicator for this detector.
     * Intentionally returns the live deduplicator so callers can query and extend it.
     * @return the issue deduplicator
     */
    @SuppressFBWarnings("EI_EXPOSE_REP")
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
        /**
         * Creates a RaceConditionEvent.
         *
         * @param type the kind of event being recorded, shown in the report
         * @param location where in the code this happened, shown in the report
         * @param lineNumber the source line the access came from
         * @param threadId the id of the thread performing the operation
         */
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
        /** Individual accesses that took part in a suspected race. */
        public final Set<String> unsafeAccesses = new HashSet<>();
        /** Fields accessed from more than one thread without synchronization. */
        public final Set<String> potentialRaces = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
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
              .append(": Potential race conditions detected — unsynchronized writes to shared fields allow threads to overwrite each other's changes, producing lost updates, stale reads, and silently wrong results\n\n");

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
