package se.deversity.asynctest.diagnostics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.report.Violation;

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
 * <p><strong>Ordering.</strong> Each access is also stamped with the recording thread's
 * {@link HappensBefore} clock, and a round whose every conflicting pair is ordered by it produces
 * no finding either: an object handed through a queue, a field published by a volatile flag, a
 * child ordered by {@code Thread.start} and {@code join}. The edges come from the agent's woven
 * calls or from the test declaring them through {@link HappensBefore}; this detector adds one of
 * its own, for a field the tracked object's class declares {@code volatile}: a recorded write
 * releases that field of the object, a recorded read of the same field acquires it (a read of
 * another volatile field acquires nothing, #742), and two reads or a read and a write of that
 * field are never a race, since volatile accesses are synchronization. Two threads writing it
 * still are, which is what keeps {@code volatile count++} a finding. Record a volatile write
 * before making it and a volatile read after making it, so that the release precedes every read
 * that can see the value; recorded the other way round, a reader that sees the write before it
 * is recorded finds nothing to acquire and the fields it publishes keep their finding. An
 * ordering edge only ever removes a finding.
 *
 * <p><strong>Why findings are not graded.</strong> A per-finding grade would have to separate a
 * race the library can stand behind from one it cannot, and nothing this detector records can:
 * an access with no visible lock may still hold an undeclared one, and a hand-off that went
 * through a call neither the agent nor the test described looks like the race it is not. Every
 * finding is therefore the same kind of claim, which is what the detector's single {@code PROMPT}
 * tier already says.
 */
public class RaceConditionDetector {

    private static class FieldAccess implements HappensBefore.Access {
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
        /** The recording thread's clock at the access; shared with its neighbours, never copied. */
        final HappensBefore.Stamp stamp;

        FieldAccess(long threadId, boolean write, long epoch, int[] locks,
                    HappensBefore.Stamp stamp) {
            this.threadId = threadId;
            this.timestamp = System.nanoTime();
            this.write = write;
            this.epoch = epoch;
            this.locks = locks;
            this.stamp = stamp;
        }

        /** {@return whether some lock was held at both this access and {@code other}} */
        boolean sharesLocksWith(FieldAccess other) {
            return intersection(locks, other.locks).length > 0;
        }

        @Override
        public long orderThread() {
            return threadId;
        }

        @Override
        public boolean orderWrite() {
            return write;
        }

        @Override
        public HappensBefore.Stamp orderStamp() {
            return stamp;
        }
    }

    /**
     * {@return the lock hashes present in both sets}
     *
     * <p>The shared {@link Lockset#intersect} rather than a copy of it: an access holding a lock
     * reentrantly names it twice, and this copy overflowed on that shape (#605).
     */
    static int[] intersection(int[] left, int[] right) {
        return Lockset.intersect(left, right);
    }

    /** One field of one object: its accesses, whether it is volatile, and where it was first written. */
    private static final class FieldState {
        final Queue<FieldAccess> accesses = new ConcurrentLinkedQueue<>();
        /** Whether the tracked object's class declares a field of this name {@code volatile}. */
        final boolean volatileField;
        /** Set once, by the first write that tried; the capture is not repeated when it found nothing. */
        volatile boolean siteAttempted;
        volatile SiteCapture.@Nullable Site firstWrite;

        FieldState(boolean volatileField) {
            this.volatileField = volatileField;
        }
    }

    private static class ObjectFieldState {
        final String className;
        final int objectId;
        final Class<?> type;
        final Map<String, FieldState> fields = new ConcurrentHashMap<>();

        ObjectFieldState(Class<?> type, int objectId) {
            this.className = type.getSimpleName();
            this.objectId = objectId;
            this.type = type;
        }
    }

    /**
     * Declared field names per class, mapped to whether each is volatile, searched up the
     * hierarchy. Computed once per class, so a new tracked object costs a lookup.
     */
    private static final ClassValue<Map<String, Boolean>> VOLATILE_FIELDS = new ClassValue<>() {
        @Override
        protected Map<String, Boolean> computeValue(Class<?> type) {
            Map<String, Boolean> fields = new HashMap<>();
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    for (Field field : current.getDeclaredFields()) {
                        fields.putIfAbsent(field.getName(), Modifier.isVolatile(field.getModifiers()));
                    }
                } catch (SecurityException | LinkageError ignored) { // NOPMD EmptyCatchBlock - an unreadable class declares nothing we can use
                    // Fall through: the field is then treated as not volatile, the old answer.
                }
            }
            return fields;
        }
    };

    /** {@return whether {@code type} declares {@code fieldName} volatile; false when it has no such field} */
    private static boolean isVolatile(Class<?> type, String fieldName) {
        return Boolean.TRUE.equals(VOLATILE_FIELDS.get(type).get(fieldName));
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
     * <p>For a field declared {@code volatile}, call this before the write rather than after; see
     * the class javadoc.
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
            key -> new ObjectFieldState(object.getClass(), key.hashCode())
        );
        FieldState field = state.fields.computeIfAbsent(fieldName,
                name -> new FieldState(isVolatile(state.type, name)));

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
        if (write && !field.siteAttempted) {
            // Once per field: a stack walk per access would be a probe effect of its own.
            field.siteAttempted = true;
            field.firstWrite = SiteCapture.capture().orElse(null);
        }
        if (field.volatileField && !write) {
            // A volatile read receives what the writes of the same field before it published,
            // and nothing another volatile field's write did (#742). Recorded after the read it
            // describes, so the acquire comes after the value was actually seen.
            HappensBefore.acquireVolatile(object, fieldName);
        }
        // Stamp before enqueueing and before any release below: the record order is what the
        // analysis replays, and it must agree with the order the clocks describe.
        field.accesses.add(new FieldAccess(Thread.currentThread().threadId(), write,
                invocationEpoch.get(), locks, HappensBefore.current()));
        if (field.volatileField && write) {
            HappensBefore.releaseVolatile(object, fieldName);
        }
    }
    /**
     * Analyses what has been recorded about race conditions and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public RaceConditionReport analyzeRaceConditions() {
        RaceConditionReport report = new RaceConditionReport();

        for (ObjectFieldState state : objects.values()) {
            for (Map.Entry<String, FieldState> entry : state.fields.entrySet()) {
                String fieldName = entry.getKey();
                FieldState field = entry.getValue();
                // One weakly-consistent snapshot per field, taken up front: safe against
                // concurrent recordAccess (the runner's timeout path analyzes while
                // cancelled workers may still be unwinding), and every check below then
                // reasons about the same fixed data instead of a moving target. The snapshot
                // keeps the record order, which the ordering check replays.
                List<FieldAccess> snapshot = new ArrayList<>(field.accesses);
                if (snapshot.size() < 2) {
                    continue;
                }

                String fieldRef = String.format(Locale.ROOT, "%s@%x.%s",
                        state.className, state.objectId, fieldName);

                // Pair accesses only within their invocation round: the runner ends a round
                // by awaiting the worker latch and starts the next by submitting fresh
                // tasks, so every round-N access happens-before every round-N+1 access.
                // A cross-round pair is ordered by the harness itself and cannot race.
                Map<Long, List<FieldAccess>> byEpoch = new HashMap<>();
                for (FieldAccess access : snapshot) {
                    byEpoch.computeIfAbsent(access.epoch, ignored -> new ArrayList<>()).add(access);
                }
                for (List<FieldAccess> roundAccesses : byEpoch.values()) {
                    analyzeRound(report, fieldRef, roundAccesses, field);
                }
            }
        }

        return report;
    }

    /**
     * Race analysis for one field within one invocation round. Inside a round the harness
     * provides no ordering between worker threads, so cross-thread pairs here are suspects unless
     * a lock covers every access or the shared ordering model orders every conflicting pair.
     */
    private void analyzeRound(RaceConditionReport report, String fieldRef, List<FieldAccess> accesses,
                              FieldState field) {
        if (accesses.size() < 2) {
            return;
        }

        Set<Long> threads = new HashSet<>();
        Set<Long> writers = new HashSet<>();
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
                writers.add(access.threadId);
                writeCount++;
                commonWriteLocks = commonWriteLocks == null
                        ? access.locks : intersection(commonWriteLocks, access.locks);
            }
        }
        boolean allGuarded = commonLocks != null && commonLocks.length > 0;
        boolean allWritesGuarded = commonWriteLocks != null && commonWriteLocks.length > 0;

        if (threads.size() < 2 || writers.isEmpty()) {
            return;
        }

        // Some lock was held at every access in the round, so the accesses are mutually excluded
        // by it: synchronized(shared), a declared lock, or a woven one. A lock the library cannot
        // see still leaves the field reported; see the class javadoc.
        if (allGuarded) {
            return;
        }

        // A volatile field's reads are synchronization, not data races; only two writers conflict.
        boolean readsConflict = !field.volatileField;
        if (!readsConflict && writers.size() < 2) {
            return;
        }
        // Every conflicting pair ordered by the happens-before model: a hand-off, a publication,
        // a start or a join the lockset cannot see. An edge only ever removes a finding.
        if (HappensBefore.everyConflictOrdered(accesses, readsConflict)) {
            return;
        }

        SiteCapture.Site site = field.firstWrite;
        // Record events for deduplication. -1 is the event contract's "line unknown"; the report
        // text below never prints it.
        for (FieldAccess access : accesses) {
            if (access.write) {
                deduplicator.record(new RaceConditionEvent(
                    "RaceCondition",
                    fieldRef,
                    site == null ? -1 : site.lineNumber(),
                    access.threadId
                ));
            }
        }

        if (writers.size() > 1 && !allWritesGuarded
                && !HappensBefore.everyConflictOrdered(accesses, false)) {
            report.add(report.potentialRaces, "concurrentWrites", site, String.format(Locale.ROOT,
                "%s: written by %d threads, %d writes in all%s",
                fieldRef, writers.size(), writeCount,
                site == null ? "" : ", first at " + site.render()
            ));
        }

        FieldAccess[] pair = firstRacingPair(accesses, readsConflict);
        if (pair.length == 2) {
            report.add(report.unsafeAccesses, "unsynchronizedSequence", site, String.format(Locale.ROOT,
                "%s: thread %d %s followed by thread %d %s",
                fieldRef,
                pair[0].threadId,
                pair[0].write ? "write" : "read",
                pair[1].threadId,
                pair[1].write ? "write" : "read"
            ));
        }
    }

    /**
     * {@return the first pair in time that conflicts, shares no lock and is not ordered, or an
     * empty array}
     *
     * <p>Adjacent pairs first, which is what the sequence line has always described; when the
     * unordered pair is not adjacent (a read ordered after the write sits between them), the
     * earliest one further apart, so a reported round always names the pair that makes it one.
     */
    private static FieldAccess[] firstRacingPair(List<FieldAccess> accesses,
                                                 boolean readsConflict) {
        List<FieldAccess> ordered = new ArrayList<>(accesses);
        ordered.sort((left, right) -> Long.compare(left.timestamp, right.timestamp));
        for (int i = 1; i < ordered.size(); i++) {
            if (races(ordered.get(i - 1), ordered.get(i), readsConflict)) {
                return new FieldAccess[] {ordered.get(i - 1), ordered.get(i)};
            }
        }
        for (int i = 2; i < ordered.size(); i++) {
            for (int j = i - 2; j >= 0; j--) {
                if (races(ordered.get(j), ordered.get(i), readsConflict)) {
                    return new FieldAccess[] {ordered.get(j), ordered.get(i)};
                }
            }
        }
        return new FieldAccess[0];
    }

    private static boolean races(FieldAccess earlier, FieldAccess later, boolean readsConflict) {
        boolean conflicting = readsConflict ? earlier.write || later.write : earlier.write && later.write;
        return earlier.threadId != later.threadId && conflicting
                && !earlier.sharesLocksWith(later)
                && !HappensBefore.ordered(earlier.threadId, earlier.stamp, later.threadId, later.stamp);
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
         * The same findings as {@link Violation}s, each {@code HIGH}: the severity the text has
         * always marked, stated where the {@code failOn} gate reads first.
         */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a finding to its text set and, when it is new there, as a structured finding. */
        void add(Set<String> section, String kind, SiteCapture.@Nullable Site site, String message) {
            if (section.add(message)) {
                structuredViolations.add(new Violation("RaceConditions", IssueSeverity.HIGH, message,
                        site == null ? List.of() : List.of(site), Map.of("kind", kind), Instant.now()));
            }
        }

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
