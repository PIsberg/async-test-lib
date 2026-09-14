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
 * <p>Synchronization awareness is a lockset, the intersection of the locks held at every access to a
 * field in a round (#570). A lock is visible when it is the tracked object's own monitor, probed
 * with {@link Thread#holdsLock(Object)} so {@code synchronized (shared)} needs nothing; when the
 * test declares it through {@code AsyncTestContext.holdingLock}; or when the agent sees it taken. A
 * round in which some lock was held at every access produces no finding, whatever else any one
 * access also held. An undeclared lock in unwoven code is invisible and the finding stands, which
 * {@code DetectorAccuracyEvalTest} pins.
 *
 * <p><strong>Why findings are not graded.</strong> A per-finding grade would have to separate a
 * race the library can stand behind from one it cannot, and nothing this detector records can:
 * an access with no visible lock may still hold an undeclared one, and the recording API carries no
 * volatile or hand-off ordering, so a lock-free read of a volatile field and a confined hand-off
 * look like the race they are not. Every finding is therefore the same kind of claim, which is what
 * the detector's single {@code PROMPT} tier already says.
 */
public class RaceConditionDetector {

    private static class FieldAccess {
        final long threadId;
        final long timestamp;
        final boolean write;
        /** Invocation round this access belongs to — see {@link #markInvocationStart()}. */
        final long epoch;
        /**
         * The identity hashes of the locks the accessing thread held at record time that guard an
         * access of this kind, empty for none.
         *
         * <p>Covers the instance's own monitor and any lock declared through {@link HeldLocks} or
         * seen by the agent. It used to be a digest of that set, compared for equality, which
         * asked whether two accesses held the <em>same</em> locks rather than whether some lock
         * was held at both: a thread under {@code synchronized (shared)} and another under
         * {@code synchronized (shared)} plus a lock of its own are excluded by the shared monitor,
         * and were reported (#570). Empty for an unguarded access, which allocates nothing.
         */
        final int[] locks;

        FieldAccess(long threadId, boolean write, long epoch, int[] locks) {
            this.threadId = threadId;
            this.timestamp = System.nanoTime();
            this.write = write;
            this.epoch = epoch;
            this.locks = locks;
        }

        /** {@return whether some lock was held at both this access and {@code other}} */
        boolean sharesLocksWith(FieldAccess other) {
            return intersection(locks, other.locks).length > 0;
        }
    }

    /** {@return the lock hashes present in both sets} */
    static int[] intersection(int[] left, int[] right) {
        if (left.length == 0 || right.length == 0) {
            return EMPTY;
        }
        int[] kept = new int[Math.min(left.length, right.length)];
        int count = 0;
        for (int candidate : left) {
            for (int other : right) {
                if (candidate == other) {
                    kept[count] = candidate;
                    count++;
                    break;
                }
            }
        }
        return count == 0 ? EMPTY : java.util.Arrays.copyOf(kept, count);
    }

    private static final int[] EMPTY = new int[0];
    private static class ObjectFieldState {
        final String className;
        final int objectId;
        final Map<String, Queue<FieldAccess>> fieldAccesses = new ConcurrentHashMap<>();

        ObjectFieldState(String className, int objectId) {
            this.className = className;
            this.objectId = objectId;
        }
    }

    private final Map<IdentityKey, ObjectFieldState> objects = new ConcurrentHashMap<>();
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
        // IdentityKey compares referents by identity; see its javadoc for why bare
        // identityHashCode keying merged distinct objects on hash collision.
        ObjectFieldState state = objects.computeIfAbsent(
            new IdentityKey(object),
            key -> new ObjectFieldState(object.getClass().getSimpleName(), key.hashCode())
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
        int[] locks = HeldLocks.intersect(null, object, write);
        state.fieldAccesses.computeIfAbsent(fieldName, ignored -> new ConcurrentLinkedQueue<>())
            .add(new FieldAccess(Thread.currentThread().threadId(), write, invocationEpoch.get(), locks));
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
        // "Guarded" means some lock was held at every access: the Eraser lockset, the intersection
        // of the locks held at each. Two threads taking different locks share none and race
        // exactly as they would with no locks at all; two threads that both hold the shared
        // monitor are excluded by it whatever else either of them holds.
        int[] commonLocks = null;
        int[] commonWriteLocks = null;
        for (FieldAccess access : accesses) {
            threads.add(access.threadId);
            commonLocks = commonLocks == null ? access.locks : intersection(commonLocks, access.locks);
            if (access.write) {
                hasWrite = true;
                writeCount++;
                commonWriteLocks = commonWriteLocks == null
                        ? access.locks : intersection(commonWriteLocks, access.locks);
            }
        }
        boolean allGuarded = commonLocks != null && commonLocks.length > 0;
        boolean allWritesGuarded = commonWriteLocks != null && commonWriteLocks.length > 0;

        if (threads.size() < 2 || !hasWrite) {
            return;
        }

        // Some lock was held at every access in the round, so the accesses are mutually excluded
        // by it: synchronized(shared), a declared lock, or a woven one. A lock the library cannot
        // see still leaves the field reported; see the class javadoc.
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
