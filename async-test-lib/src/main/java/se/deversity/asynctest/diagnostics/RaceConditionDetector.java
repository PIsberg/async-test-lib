package se.deversity.asynctest.diagnostics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects potential race conditions by tracking cross-thread field accesses.
 *
 * <p>Synchronization awareness is partial and deliberate: at record time the detector probes
 * {@link Thread#holdsLock(Object)} on the tracked object itself, so accesses serialized by the
 * object's own monitor — {@code synchronized (shared)} blocks and synchronized methods of the
 * shared object — count as guarded, and a round whose every access was guarded produces no
 * finding. A guard on any <em>other</em> lock object is invisible and still fires; the report
 * wording and {@code DetectorAccuracyEvalTest} pin that asymmetry in both directions.
 */
public class RaceConditionDetector {

    private static class FieldAccess {
        final long threadId;
        final long timestamp;
        final boolean write;
        /** Invocation round this access belongs to — see {@link #markInvocationStart()}. */
        final long epoch;
        /**
         * Identifies the locks the accessing thread held at record time, 0 for none.
         *
         * <p>Was a boolean answering only "was the tracked object's own monitor held", which made
         * every other guard - a {@code ReentrantLock}, a private lock object - indistinguishable
         * from no guard. The fingerprint covers the instance's own monitor and any lock declared
         * through {@link HeldLocks}, so two accesses can be compared by what actually covered
         * them: the same non-zero value means one set of locks serialised both, and there is no
         * race between them to report.
         */
        final long lockFingerprint;

        FieldAccess(long threadId, boolean write, long epoch, long lockFingerprint) {
            this.threadId = threadId;
            this.timestamp = System.nanoTime();
            this.write = write;
            this.epoch = epoch;
            this.lockFingerprint = lockFingerprint;
        }

        /** {@return whether {@code other} was covered by exactly the same locks as this} */
        boolean sharesLocksWith(FieldAccess other) {
            return lockFingerprint != 0L && lockFingerprint == other.lockFingerprint;
        }
    }

    /**
     * Identity key for tracked objects. Keying by bare {@code System.identityHashCode}
     * merged two distinct objects whenever their hashes collided (about a 50% chance once
     * ~54k recorded objects are live, by the birthday bound), silently attributing one
     * object's accesses to another. This key caches the identity hash but compares
     * referents by {@code ==}, so a collision only costs a hash-bucket neighbor, never a
     * merge. The reference is strong on purpose: detector state is scoped to a single test
     * run, {@link #reset()} releases it, and the per-access records already dwarf the
     * object references themselves.
     */
    private static final class TrackedObject {
        final Object referent;
        final int identityHash;

        TrackedObject(Object referent) {
            this.referent = referent;
            this.identityHash = System.identityHashCode(referent);
        }

        @Override
        @SuppressWarnings("ReferenceEquality") // referent identity is the point — see the class javadoc
        public boolean equals(Object other) {
            return other instanceof TrackedObject that && that.referent == this.referent;
        }

        @Override
        public int hashCode() {
            return identityHash;
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

    private final Map<TrackedObject, ObjectFieldState> objects = new ConcurrentHashMap<>();
    private final IssueDeduplicator<RaceConditionEvent> deduplicator = new IssueDeduplicator<>();

    /**
     * Current invocation round, bumped by {@link #markInvocationStart()}. Accesses from
     * different rounds are ordered by the runner's own happens-before edges (the round's
     * worker latch, then the next round's task submissions), so analysis only ever pairs
     * same-epoch accesses. Standalone use without round marks leaves every access in
     * epoch 0, which preserves the single-pool behavior.
     */
    private final AtomicLong invocationEpoch = new AtomicLong();
    private volatile boolean enabled = true;

    /**
     * Marks the start of a new invocation round.
     *
     * <p>Called by {@code ConcurrencyRunner} before each round. Accesses recorded after
     * this call belong to the new round and are never paired with earlier rounds'
     * accesses: the harness itself orders rounds (worker latch, then fresh submissions),
     * so a cross-round pair has a happens-before edge and cannot race.
     *
     * @since 1.7.3
     */
    public void markInvocationStart() {
        invocationEpoch.incrementAndGet();
    }
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
        // TrackedObject compares referents by identity — see its Javadoc for why bare
        // identityHashCode keying merged distinct objects on hash collision.
        ObjectFieldState state = objects.computeIfAbsent(
            new TrackedObject(object),
            key -> new ObjectFieldState(object.getClass().getSimpleName(), key.identityHash)
        );

        // ConcurrentLinkedQueue, deliberately not a synchronizedList: this method runs on
        // the racing threads themselves, between the very accesses being hunted. A shared
        // monitor here is a probe effect — it serializes the racing threads at every record
        // (and pins virtual threads to their carrier on JDK < 24), which can mask the race
        // this detector exists to find. A lock-free CAS enqueue keeps the cross-thread
        // rendezvous to a single cache line and never parks a recording thread.
        // Guard-on-self probe, evaluated on the accessing thread at access time. holdsLock is
        // an intrinsic over the current thread's own lock records: no monitor is taken, so the
        // probe cannot serialize the racing threads (the same reason the queue below is lock-free).
        // Mode-aware, the way AtomicityValidator has always computed it. Folding a read-mode
        // lock in as if exclusive made two threads writing under the same readLock() look
        // consistently guarded, which is a false negative and a divergence between two detectors
        // that claim the same model (#500).
        long lockFingerprint = HeldLocks.lockFingerprint(object, write);
        state.fieldAccesses.computeIfAbsent(fieldName, ignored -> new ConcurrentLinkedQueue<>())
            .add(new FieldAccess(Thread.currentThread().threadId(), write, invocationEpoch.get(), lockFingerprint));
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

                String fieldRef = String.format("%s@%x.%s", state.className, state.objectId, fieldName);

                // Pair accesses only within their invocation round: the runner ends a round
                // by awaiting the worker latch and starts the next by submitting fresh
                // tasks, so every round-N access happens-before every round-N+1 access.
                // A cross-round pair is ordered by the harness itself and cannot race.
                Map<Long, List<FieldAccess>> byEpoch = new HashMap<>();
                for (FieldAccess access : snapshot) {
                    byEpoch.computeIfAbsent(access.epoch, ignored -> new ArrayList<>()).add(access);
                }
                for (List<FieldAccess> roundAccesses : byEpoch.values()) {
                    analyzeRound(report, fieldRef, roundAccesses);
                }
            }
        }

        return report;
    }

    /**
     * Race analysis for one field within one invocation round. Inside a round the harness
     * provides no ordering between worker threads, so cross-thread pairs here are genuine
     * suspects (user-level synchronization is still invisible — see the class Javadoc).
     */
    private void analyzeRound(RaceConditionReport report, String fieldRef, List<FieldAccess> accesses) {
        if (accesses.size() < 2) {
            return;
        }

        Set<Long> threads = new HashSet<>();
        boolean hasWrite = false;
        int writeCount = 0;
        // "Guarded" now means every access was covered by the same set of locks, not merely that
        // each held something: two threads taking different locks exclude nothing from each other
        // and race exactly as they would with no locks at all.
        long commonLocks = 0L;
        boolean firstAccess = true;
        long commonWriteLocks = 0L;
        boolean firstWrite = true;
        for (FieldAccess access : accesses) {
            threads.add(access.threadId);
            if (firstAccess) {
                commonLocks = access.lockFingerprint;
                firstAccess = false;
            } else if (commonLocks != access.lockFingerprint) {
                commonLocks = 0L;
            }
            if (access.write) {
                hasWrite = true;
                writeCount++;
                if (firstWrite) {
                    commonWriteLocks = access.lockFingerprint;
                    firstWrite = false;
                } else if (commonWriteLocks != access.lockFingerprint) {
                    commonWriteLocks = 0L;
                }
            }
        }
        boolean allGuarded = commonLocks != 0L;
        boolean allWritesGuarded = commonWriteLocks != 0L;

        if (threads.size() < 2 || !hasWrite) {
            return;
        }

        // Every access in the round held the tracked object's own monitor: they are mutually
        // excluded and ordered by that monitor, which is the synchronized(shared) idiom working
        // correctly. Guards on other lock objects remain invisible — see the class javadoc.
        if (allGuarded) {
            return;
        }

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

        if (writeCount > 1 && !allWritesGuarded) {
            report.potentialRaces.add(String.format(
                "%s: %d writes observed across %d threads",
                fieldRef, writeCount, threads.size()
            ));
        }

        List<FieldAccess> ordered = new ArrayList<>(accesses);
        ordered.sort((left, right) -> Long.compare(left.timestamp, right.timestamp));

        for (int i = 1; i < ordered.size(); i++) {
            FieldAccess previous = ordered.get(i - 1);
            FieldAccess current = ordered.get(i);
            if (previous.threadId != current.threadId && (previous.write || current.write)
                    && !previous.sharesLocksWith(current)) {
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
        invocationEpoch.set(0);
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
