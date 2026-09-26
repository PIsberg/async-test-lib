package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;

/**
 * Tracks compound operations that should behave atomically.
 *
 * <p>Besides its lockset and idiom rules, a per-round, per-instance group of accesses is excused
 * when the shared {@link HappensBefore} model orders every conflicting pair in it: a lock-free
 * single writer publishing through a volatile flag, an object built and handed over through a
 * concurrent map in the same round. Each access carries the accessing thread's clock, stamped on
 * that thread (by {@code TelemetryRegistry} for the agent's stream, or here when the recording
 * thread is the accessing one); an access without a stamp orders nothing, so the excuse only ever
 * removes a finding.
 */
public class AtomicityValidator {

    private static class CompoundOperation {
        final String operationName;
        final long threadId;
        final Map<String, Object> firstReads = new ConcurrentHashMap<>();

        CompoundOperation(String operationName, long threadId) {
            this.operationName = operationName;
            this.threadId = threadId;
        }
    }

    /**
     * One invocation round on one instance: the unit within which accesses can actually race.
     *
     * <p>A record rather than a packed key because packing an epoch and an identity hash into one
     * long collides, and a collision here merges the histories of two unrelated objects, which is
     * the same false positive this split exists to remove. For the same reason the identity hash
     * alone is not the instance: two live objects share one routinely once tens of thousands are
     * tracked, so an access whose receiver was handed over is grouped by {@code instance}, which
     * names the object itself, and the hash only separates accesses that came without one.
     */
    private record AccessGroup(long epoch, int identity, int instance) { }

    private static class FieldAccessRecord implements HappensBefore.Access {
        final long threadId;
        final boolean write;
        /** Invocation round this access belongs to — see {@link #markInvocationStart()}. */
        final long epoch;
        /** Whether an owner was supplied at all, which decides what the report may claim. */
        final boolean ownerKnown;

        /**
         * {@code System.identityHashCode} of the instance the field belongs to, 0 when unknown or
         * static. Accesses to different instances of the same field are different state and are
         * analysed apart: six threads each touching their own hasher is not six threads sharing
         * one.
         */
        final int identity;

        /**
         * The locks the accessing thread held, as its fingerprint; {@link #UNMODELLED} when the
         * caller carried no lock information. Kept per access so the analysis can ask, once the
         * write lockset is known, which reads it covered and in what order.
         */
        final long fingerprint;

        /** Identity hash of the receiver's monitor when held at the access, else 0. */
        final int ownMonitor;

        /** Identity hash of the enclosing synchronized method's monitor when held, else 0. */
        final int methodMonitor;

        /** Whether the access happened while the receiver was still exclusive to its builder. */
        final boolean exclusivePhase;

        /**
         * {@code System.identityHashCode} of the reference this write stored, 0 when it stored no
         * reference or the weaver could not reach it.
         *
         * <p>The only value evidence in an otherwise value-free stream, and it exists for one
         * question: did the thing that was published then go quiet. A 0 means "not known", never
         * "not immutable" - see {@link #settledSingleCheckCache}.
         */
        final int storedIdentity;

        /**
         * Which ownership generation of the receiver this access belongs to (#555): 0 from
         * construction until the first observed take, then one more for every take. Locks are
         * only required to agree within a generation.
         */
        final int generation;

        /**
         * The receiver's instance number from {@link #instanceOf}, unique per object for the run,
         * or 0 when the access came without a receiver.
         */
        final int instance;

        /**
         * What the per-instance analysis groups by: the instance when it is known, else the
         * identity hash. The two live in different halves of the long so an instance number can
         * never equal a hash.
         */
        final long instanceKey;

        /** The accessing thread's ordering clock at the access, {@code null} when none was taken. */
        final HappensBefore.@Nullable Stamp stamp;

        /**
         * The locks an owner-aware access held, the owner's own monitor included, {@code null}
         * for an access that named no owner. What lets the analysis intersect one round's
         * owner-aware accesses on their own, as {@link #fingerprint} does for the agent's.
         */
        final int @Nullable [] ownerLocks;

        FieldAccessRecord(long threadId, boolean write, long epoch, boolean ownerKnown,
                          int identity, long fingerprint, int ownMonitor, int methodMonitor,
                          boolean exclusivePhase, int storedIdentity, int generation,
                          int instance, HappensBefore.@Nullable Stamp stamp,
                          int @Nullable [] ownerLocks) {
            this.threadId = threadId;
            this.write = write;
            this.epoch = epoch;
            this.ownerKnown = ownerKnown;
            this.identity = identity;
            this.fingerprint = fingerprint;
            this.ownMonitor = ownMonitor;
            this.methodMonitor = methodMonitor;
            this.exclusivePhase = exclusivePhase;
            this.storedIdentity = storedIdentity;
            this.generation = generation;
            this.instance = instance;
            this.instanceKey = instance != 0
                    ? (1L << 32) | (instance & 0xFFFF_FFFFL)
                    : identity & 0xFFFF_FFFFL;
            this.stamp = stamp;
            this.ownerLocks = ownerLocks;
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
        public HappensBefore.@Nullable Stamp orderStamp() {
            return stamp;
        }
    }

    /**
     * What is known about the locks covering one field.
     *
     * <p>Two models, because the two recording paths can answer different questions. A
     * cooperative caller names the owning object, so the full Eraser intersection is available.
     * The agent-fed path cannot: its producer runs on the worker thread and its analysis on a
     * drain thread, with an allocation-free ring buffer in between, so all that fits through is a
     * fingerprint of the locks held, which {@link HeldLocks#members(long)} resolves back into a
     * set on the drain side, plus the monitors the event carries alongside it. A field is reported
     * unless every model that saw it says it was consistently covered.
     */
    private static final class FieldGuard {

        /** Eraser intersection, for accesses that named their owner. */
        private final Locks objectLocks = new Locks();

        /** The locks every agent-fed access held, as an intersection. */
        private final Lockset allAccesses = new Lockset();

        /** Set by a recording path that carries no lock information at all. */
        private volatile boolean sawUnmodelledAccess;

        /**
         * The locks every <em>write</em> held.
         *
         * <p>Tracked apart from {@link #allAccesses} because safe publication is asymmetric: what
         * makes double-checked locking correct is that the writes agreed on a lock, while the
         * reads deliberately took none. Intersecting reads and writes together collapses exactly
         * the case worth recognising.
         */
        private final Lockset writes = new Lockset();

        /** Whether the field is declared {@code volatile}, as resolved at weave time. */
        private volatile boolean volatileField;

        /** Whether a write carried the volatile bit; see {@link #hasVolatileWrites()}. */
        private volatile boolean volatileWrite;

        /**
         * The one constant every write stored, {@link #UNSET} before the first write, and
         * {@code Integer.MIN_VALUE} once a write stored something else.
         */
        private final java.util.concurrent.atomic.AtomicInteger writeConstant =
                new java.util.concurrent.atomic.AtomicInteger(NO_CONSTANT_YET);

        /** Concrete only so it can carry the shared lockset implementation. */
        private static final class Locks extends SelfGuard.TrackedInstance {
        }

        void noteOwner(@Nullable Object owner) {
            objectLocks.noteAccess(owner);
        }

        void noteWriteConstant(int tag) {
            if (tag == NOT_A_CONSTANT) {
                writeConstant.set(NOT_A_CONSTANT);
                return;
            }
            int current = writeConstant.get();
            while (current != NOT_A_CONSTANT && current != tag) {
                int next = current == NO_CONSTANT_YET ? tag : NOT_A_CONSTANT;
                if (writeConstant.compareAndSet(current, next)) {
                    return;
                }
                current = writeConstant.get();
            }
        }

        /**
         * {@return whether every write to this field stored the same constant}
         *
         * <p>The weaver only tags a write when the value came from a constant instruction and the
         * writing method had not read the field, so this cannot be the "act" half of a
         * check-then-act. A field all of whose writes store the same value settles at that value
         * however the threads interleave, which is what {@code isLocked = true} on every call to
         * {@code FixedOrderComparator.compare} does.
         */
        boolean writesOnlyOneConstant() {
            int constant = writeConstant.get();
            return constant != NO_CONSTANT_YET && constant != NOT_A_CONSTANT;
        }

        void noteVolatile() {
            volatileField = true;
        }

        void noteVolatileWrite() {
            volatileWrite = true;
        }

        /**
         * {@return whether a write to this field carried the volatile bit}
         *
         * <p>The one reading of the bit that means "declared {@code volatile}". The bridge also
         * sets it on a read of a plain field that follows a volatile read of the same object, its
         * safely-published mark, which {@link #isVolatileField()} cannot tell apart; that mark only
         * ever rides on a read.
         */
        boolean hasVolatileWrites() {
            return volatileWrite;
        }

        void noteWriteFingerprint(long observed) {
            writes.note(observed, 0, 0);
        }

        /**
         * Records what one agent-fed access held: the fingerprinted set plus the monitors that
         * travel outside it. A write also narrows the write-only intersection.
         */
        void noteAccess(long fingerprint, int ownMonitor, int methodMonitor, boolean isWrite) {
            allAccesses.note(fingerprint, ownMonitor, methodMonitor);
            if (isWrite) {
                writes.note(fingerprint, ownMonitor, methodMonitor);
            }
        }

        /**
         * {@return whether this field's accesses are safe publication rather than a race}
         *
         * <p>True when the field is {@code volatile} and some lock was held at every write to it.
         * That is the double-checked-locking shape: the reads are deliberately unguarded, and the
         * JMM makes them safe because the field is volatile and the mutation is serialised by a
         * lock. A volatile field written with no lock held stays reportable, which is what keeps
         * {@code volatile count++} a finding. Guava's cache writes an entry under its segment
         * lock and, on the load path, under the entry's monitor as well: the intersection is the
         * segment lock, and that is enough.
         *
         * <p>The streamed write lockset spans the whole run, so a different lock in each round
         * empties it although the harness orders the rounds; once it has, {@code round}'s own
         * writes decide, as {@link #noLockCoveredTheRound} does for the reporting lockset (#749).
         *
         * @param round the accesses of the one round group being judged
         */
        boolean isSafePublication(List<FieldAccessRecord> round) {
            return volatileField && (writes.guarded() || everyWriteOfTheRoundSharedALock(round));
        }

        /** {@return the locks held at every recorded write; empty when none survived or none seen} */
        int[] writeLockSurvivors() {
            return writes.survivors();
        }

        /** {@return whether the field is declared {@code volatile}} */
        boolean isVolatileField() {
            return volatileField;
        }

        void noteFingerprint(long observed) {
            allAccesses.note(observed, 0, 0);
        }

        void noteUnmodelled() {
            sawUnmodelledAccess = true;
        }

        boolean sawUnguardedAccess() {
            return sawUnmodelledAccess
                    || objectLocks.sawUnguardedAccess()
                    || allAccesses.collapsed();
        }
    }

    /**
     * {@return the key a field's lock model is tracked under}
     *
     * <p>Identity 0 means "not known", and every caller that predates the agent's identity events
     * uses it, so their accesses share one guard exactly as they always did.
     */
    private static String guardKey(String fieldName, int identity) {
        return identity == 0 ? fieldName : fieldName + '@' + identity;
    }

    /**
     * Publication state of one woven receiver: the first observed thread, and whether any
     * other thread has been seen since.
     *
     * <p>This is the Eraser initialization state, kept per receiver rather than per field: an
     * object is under construction until a second thread can reach it, and that boundary is a
     * property of the object, not of each field alone. {@code shared} flips once and never
     * back. Identity hashes can collide; a collision makes two objects look like one and flips
     * {@code shared} early, which can only withhold the excuse, never widen it.
     */
    private static final class ReceiverState {
        final long firstThread;
        volatile boolean shared;

        /** How many takes have been observed for this receiver; see {@link #recordOwnershipTaken}. */
        final int generation;

        ReceiverState(long firstThread, int generation) {
            this.firstThread = firstThread;
            this.generation = generation;
        }
    }

    /**
     * Instance numbers by receiver identity, weakly held; see {@link #instanceOf}.
     *
     * <p>Everything else in this class is keyed by identity hash, where a collision merges two
     * objects and can only withhold an excuse. The grouping into per-instance histories is the one
     * place a merge invents a finding, so it is keyed by the object itself.
     */
    private final Map<IdentityKey.Weak, Integer> instances = new ConcurrentHashMap<>();

    /** Where {@link #instances} keys arrive once their receiver is collected. */
    private final java.lang.ref.ReferenceQueue<Object> collectedInstances =
            new java.lang.ref.ReferenceQueue<>();

    /** The last instance number handed out; 0 is reserved for "no receiver". */
    private final java.util.concurrent.atomic.AtomicInteger lastInstance =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * {@return a number unique to {@code receiver} for this run, 0 for {@code null}}
     *
     * <p>Allocates a lookup key per call and one entry per new object, on the drain thread for the
     * agent path, never on the worker that made the access.
     */
    private int instanceOf(@Nullable Object receiver) {
        if (receiver == null) {
            return 0;
        }
        for (java.lang.ref.Reference<?> gone = collectedInstances.poll(); gone != null;
                gone = collectedInstances.poll()) {
            instances.remove(gone);
        }
        Integer known = instances.get(new IdentityKey.Weak(receiver, null));
        if (known != null) {
            return known;
        }
        return instances.computeIfAbsent(new IdentityKey.Weak(receiver, collectedInstances),
                ignored -> lastInstance.incrementAndGet());
    }

    /** Publication state per receiver identity; identity 0 (unknown or static) is not tracked. */
    private final Map<Integer, ReceiverState> receiverStates = new ConcurrentHashMap<>();

    /** Maps receiver identity to a map of (generation -> threadId of that generation's taker). */
    private final Map<Integer, Map<Integer, Long>> generationTakers = new ConcurrentHashMap<>();

    /** Who last offered an object with no recorded state yet, and to which container (#630). */
    private record Offer(long threadId, int container) {
    }

    /** Maps receiver identity to its most recent {@link Offer}; see {@link #recordOwnershipOffered}. */
    private final Map<Integer, Offer> offers = new ConcurrentHashMap<>();

    private void recordTaker(int identity, int generation, long threadId) {
        generationTakers.computeIfAbsent(identity, ignored -> new ConcurrentHashMap<>())
                .putIfAbsent(generation, threadId);
    }

    private @Nullable Long takerOf(int identity, int generation) {
        Map<Integer, Long> takers = generationTakers.get(identity);
        return takers == null ? null : takers.get(generation);
    }

    /**
     * {@return whether this access happens while {@code identity} is still exclusive to
     * {@code threadId}} Advances the state as a side effect: the first access from any other
     * thread publishes the receiver permanently. Events for one receiver are drained in the
     * order the workers published them, and a second thread can only learn of a receiver
     * through a publication that follows the builder's writes, so the flip cannot land before
     * the construction accesses it ends.
     */
    private boolean inExclusivePhase(int identity, long threadId) {
        if (identity == 0) {
            return false;
        }
        // Generation 0's owner is known only when an access, not a take, created the state. A
        // take drained ahead of the builder's accesses leaves it unknown on purpose, unless an
        // offer to the container it was taken from named it (recordOwnershipOffered): recording the
        // first thread seen afterwards would name the taker as its own predecessor. Recording here
        // also keeps the per-access path free of boxing (RunnerAllocationBudgetTest).
        ReceiverState state = receiverStates.computeIfAbsent(identity, ignored -> {
            recordTaker(identity, 0, threadId);
            return new ReceiverState(threadId, 0);
        });
        if (state.shared) {
            return false;
        }
        if (state.firstThread != threadId) {
            state.shared = true;
            return false;
        }
        return true;
    }

    /** Sentinel for "this caller carried no lock information at all". */
    private static final long UNMODELLED = Long.MIN_VALUE;

    /** Tag meaning the weaver could not read the written value as a constant. */
    private static final int NOT_A_CONSTANT = Integer.MIN_VALUE;

    /** Tag meaning no write has been recorded yet. Distinct from a real constant. */
    private static final int NO_CONSTANT_YET = Integer.MAX_VALUE;

    private final Map<String, CompoundOperation> activeOperations = new ConcurrentHashMap<>();
    private final Map<String, List<FieldAccessRecord>> fieldHistory = new ConcurrentHashMap<>();

    /**
     * The last round in which each object had a field of its own written.
     *
     * <p>Maintained as events arrive rather than scanned for at analysis time: the question
     * {@link #everyPublishedValueWentQuiet} asks is "did this published object then go quiet",
     * and answering it by walking every field's history per candidate would be quadratic in the
     * event count on a path that already handles millions.
     *
     * <p>Keyed by identity hash, with the collision hazard that carries: two objects sharing a
     * hash merge here, which can deny a settle excuse that was owed. That direction costs a
     * finding on correct code rather than silence on a defect, and it is the same keying every
     * other per-instance rule in this class already uses.
     */
    private final Map<Integer, Long> lastOwnWriteEpoch = new ConcurrentHashMap<>();
    /**
     * Per-field lockset: the locks held at every recorded access to that field.
     *
     * <p>Same Eraser model the {@code Shared*} family uses, keyed by field name rather than by
     * instance because that is the granularity this detector analyses at. A field whose
     * intersection is still non-empty has a lock protecting it consistently and is not reported.
     * An access recorded through an overload that names no owner collapses the set immediately,
     * so the agent-fed path keeps exactly the behaviour it had.
     */
    private final Map<String, FieldGuard> fieldLocks = new ConcurrentHashMap<>();
    /**
     * Both writers — {@code recordFieldAccess} and {@code detectCheckThenActViolation} — are
     * called straight from the user's concurrently running test body, so this collection is
     * mutated by N threads at once. A plain ArrayList loses elements under concurrent add (two
     * threads write the same index), silently dropping real violations before analysis reads
     * them, and can throw ArrayIndexOutOfBoundsException into the user's test body.
     */
    private final Queue<String> atomicityViolations = new ConcurrentLinkedQueue<>();

    /**
     * Current invocation round, bumped by {@link #markInvocationStart()}. Accesses from
     * different rounds are ordered by the runner's own happens-before edges (the round's
     * worker latch, then the next round's task submissions), so analysis only ever pairs
     * same-epoch accesses. Standalone use without round marks leaves every access in
     * epoch 0, which preserves the single-pool behavior.
     */
    private final java.util.concurrent.atomic.AtomicLong invocationEpoch =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The {@link HappensBefore#round()} token each of this validator's rounds started with:
     * element {@code k - 1} started round {@code k}. Replaced, never mutated, by the runner thread.
     *
     * <p>What lets an agent event carry its round rather than acquire one on arrival. The runner
     * flushes the ring before each round start, but the flush waits one second at most; a drain
     * slower than that delivered the round's remaining events after the next round had started,
     * and they were paired with it.
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "VO_VOLATILE_REFERENCE_TO_ARRAY",
            justification = "copy-on-write: a published array is never written again, so the "
                    + "volatile reference is the only publication its elements need")
    private volatile long[] roundTokens = new long[0];

    private volatile boolean enabled = true;

    /**
     * Marks the start of a new invocation round.
     *
     * <p>Called by {@code ConcurrencyRunner} before each round (after flushing pending
     * telemetry, so agent-captured accesses are attributed to the round that produced
     * them). Accesses recorded after this call belong to the new round and are never
     * paired with earlier rounds' accesses: the harness itself orders rounds.
     *
     * @since 1.7.3
     */
    public void markInvocationStart() {
        long[] started = roundTokens;
        long[] next = java.util.Arrays.copyOf(started, started.length + 1);
        next[started.length] = HappensBefore.nextRound();
        roundTokens = next;
        invocationEpoch.incrementAndGet();
    }

    /**
     * {@return this validator's round for an access published under {@code round}}
     *
     * <p>The number of this validator's round starts at or before the token. Tokens only grow and a
     * worker reads its token after the start of its round and before the start of the next, so
     * tokens other runs started in between still count as this validator's current round. A token
     * of 0 is an access that carried none, which keeps the round current now, as always.
     */
    private long epochOf(long round) {
        if (round <= 0L) {
            return invocationEpoch.get();
        }
        long[] started = roundTokens;
        int at = java.util.Arrays.binarySearch(started, round);
        return at >= 0 ? at + 1L : -(at + 1L);
    }
    /**
     * Records compound operation start so it can be analysed at the end of the run.
     *
     * @param operationName a label identifying the operation in the report
     */
    public void recordCompoundOperationStart(String operationName) {
        if (!enabled || operationName == null || operationName.isBlank()) {
            return;
        }

        activeOperations.put(operationKey(operationName),
            new CompoundOperation(operationName, Thread.currentThread().threadId()));
    }
    /**
     * Records compound operation end so it can be analysed at the end of the run.
     *
     * @param operationName a label identifying the operation in the report
     */
    public void recordCompoundOperationEnd(String operationName) {
        if (!enabled || operationName == null || operationName.isBlank()) {
            return;
        }

        activeOperations.remove(operationKey(operationName));
    }
    /**
     * Records field access so it can be analysed at the end of the run.
     *
     * @param fieldName the field involved, as it should appear in the report
     * @param value the value read or written
     * @param isWrite {@code true} for a write, {@code false} for a read
     */
    public void recordFieldAccess(String fieldName, @Nullable Object value, boolean isWrite) {
        recordFieldAccess(fieldName, value, isWrite, Thread.currentThread().threadId());
    }

    /**
     * Records a field access attributed to an explicit thread id, rather than
     * {@code Thread.currentThread()}.
     *
     * <p>This overload exists for callers that observe an access from a thread other than
     * the one performing the recording — most notably the telemetry drain thread, which
     * replays field-access events captured on the stress-test worker threads (see
     * {@code se.deversity.asynctest.telemetry.TelemetryBridge}). Passing the originating
     * {@code threadId} keeps cross-thread atomicity analysis correct; the three-argument
     * {@link #recordFieldAccess(String, Object, boolean)} overload simply forwards
     * {@code Thread.currentThread().threadId()} here.
     *
     * <p>The {@code value} may be {@code null}: {@code null} values are tolerated (the
     * cross-thread mixed read/write analysis relies only on {@code threadId} and
     * {@code isWrite}, and the compound-operation first-read tracking skips {@code null}
     * reads), so access-pattern-only sources such as the agent — which has method-name
     * granularity but no field value — can feed this detector meaningfully.
     *
     * @param fieldName the qualified field/accessor identifier; {@code null}/blank is ignored
     * @param value     the observed value, or {@code null} when unavailable
     * @param isWrite   {@code true} for a write access, {@code false} for a read
     * @param threadId  the id of the thread the access is attributed to
     * @since 1.7.0
     */
    public void recordFieldAccess(String fieldName, @Nullable Object value, boolean isWrite,
                                  long threadId) {
        record(fieldName, value, isWrite, threadId, null, false, UNMODELLED);
    }

    /**
     * Records a field access together with the object that owns the field, so that a guard held
     * on that object can be recognised.
     *
     * <p>The overloads above see a field name and nothing else. That is enough to say "more than
     * one thread touched this field, and at least one wrote", which is what makes this detector
     * fire on correctly synchronized code as loudly as on a race: with no object in hand there is
     * nothing to ask about a lock. Passing the owner closes that gap for the common case. The
     * probe is {@link Thread#holdsLock(Object)} on the calling thread at record time, so call
     * this from inside whatever region guards the access, not afterwards.
     *
     * <p>A field whose every recorded access held the owner's own monitor produces no finding. A
     * guard on any other lock object is invisible to this overload and still produces one, as is
     * an access recorded through an overload that carries no lock information at all. The agent
     * does better on its own path: it passes a fingerprint of the woven locks plus the receiver's
     * monitor, resolved back into a set and intersected on the drain side.
     *
     * <p>Accesses are analysed per owner: the same field of two different objects is two
     * histories, so objects that each stay on one thread are not reported as shared (#750).
     *
     * @param owner     the object whose field is being accessed; {@code null} counts as unguarded
     * @param fieldName the qualified field/accessor identifier; {@code null}/blank is ignored
     * @param value     the observed value, or {@code null} when unavailable
     * @param isWrite   {@code true} for a write access, {@code false} for a read
     * @since 1.9.6
     */
    public void recordFieldAccessOn(@Nullable Object owner, String fieldName,
                                    @Nullable Object value, boolean isWrite) {
        record(fieldName, value, isWrite, Thread.currentThread().threadId(),
                owner, owner != null, UNMODELLED);
    }

    /**
     * Records a field access observed elsewhere, carrying the locks the accessing thread held.
     *
     * <p>For replaying callers: the access happened on one thread and is being recorded on
     * another, so neither the owning object nor {@link Thread#holdsLock(Object)} can answer the
     * lock question here. The agent's telemetry path is the case this exists for. What it takes
     * instead is a fingerprint captured on the accessing thread at access time
     * ({@code HeldLocks.lockFingerprint()}), which the ring buffer between the two threads can
     * carry without allocating.
     *
     * <p>The model this supports is weaker than the owner-aware one: it can tell whether every
     * access to a field held the <em>same</em> set of locks, not which lock is common to them.
     * A field where one thread holds {@code {A, B}} and another holds {@code {A}} is genuinely
     * protected by {@code A}, and this reports it anyway. Erring toward a finding is the right
     * direction for a detector, and the report says which model produced it.
     *
     * @param fieldName       the qualified field identifier; {@code null}/blank is ignored
     * @param value           the observed value, or {@code null} when unavailable
     * @param isWrite         {@code true} for a write access, {@code false} for a read
     * @param threadId        the id of the thread the access is attributed to
     * @param lockFingerprint the locks that thread held at the access, 0 for none
     * @since 1.9.6
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId,
                                            long lockFingerprint) {
        record(fieldName, value, isWrite, threadId, null, false, lockFingerprint);
    }

    /**
     * Records an agent-fed access that also says whether the field is declared {@code volatile}.
     *
     * <p>Volatility alone never excuses anything: it is combined with the locks the writes held.
     * A volatile field written under one consistent lock and read without it is safe publication,
     * the double-checked-locking idiom; a volatile field written with no lock held is still a
     * finding, which is what keeps {@code volatile count++} reportable.
     *
     * @param fieldName       the field, as it should appear in the report
     * @param value           the value read or written, may be {@code null}
     * @param isWrite         {@code true} for a write
     * @param threadId        the thread that made the access
     * @param lockFingerprint the locks that thread held at the access, 0 for none
     * @param volatileField   whether the field is declared {@code volatile}
     * @since 1.9.8
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId,
                                            long lockFingerprint, boolean volatileField) {
        if (volatileField && fieldName != null && !fieldName.isBlank()) {
            FieldGuard guard = fieldLocks.computeIfAbsent(fieldName, ignored -> new FieldGuard());
            guard.noteVolatile();
            if (isWrite) {
                guard.noteVolatileWrite();
            }
        }
        record(fieldName, value, isWrite, threadId, null, false, lockFingerprint);
    }

    /**
     * Records an agent-fed access carrying both the field's volatility and any constant it stored.
     *
     * @param fieldName       the field, as it should appear in the report
     * @param value           the value read or written, may be {@code null}
     * @param isWrite         {@code true} for a write
     * @param threadId        the thread that made the access
     * @param lockFingerprint the locks that thread held at the access, 0 for none
     * @param volatileField   whether the field is declared {@code volatile}
     * @param constantTag     the constant this write stored, {@code Integer.MIN_VALUE} for none
     * @since 1.9.8
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId, long lockFingerprint,
                                            boolean volatileField, int constantTag) {
        recordFieldAccessUnderLocks(fieldName, value, isWrite, threadId, lockFingerprint,
                volatileField, constantTag, 0);
    }

    /**
     * Records an agent-fed access, naming the instance the field belongs to.
     *
     * @param fieldName       the field, as it should appear in the report
     * @param value           the value read or written, may be {@code null}
     * @param isWrite         {@code true} for a write
     * @param threadId        the thread that made the access
     * @param lockFingerprint the locks that thread held at the access, 0 for none
     * @param volatileField   whether the field is declared {@code volatile}
     * @param constantTag     the constant this write stored, {@code Integer.MIN_VALUE} for none
     * @param identity        {@code System.identityHashCode} of the owner, 0 for statics
     * @since 1.9.8
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId, long lockFingerprint,
                                            boolean volatileField, int constantTag, int identity) {
        recordFieldAccessUnderLocks(fieldName, value, isWrite, threadId, lockFingerprint, 0, 0,
                volatileField, constantTag, identity);
    }

    /**
     * Records an agent-fed access together with the monitors its lockset could not see.
     *
     * <p>The fingerprint names the locks the weaver observed being taken. Two monitors never
     * reach it: the receiver's own, which a {@code synchronized} method holds without any
     * instruction the weaver could record, and the monitor of that method itself. Both arrive
     * here as identity hashes, or 0 when not held, and join the intersection alongside the
     * fingerprint's members. An access whose every lock is one of these is as guarded as one
     * under an explicit {@code synchronized} block, which is what makes a class built on
     * {@code synchronized} methods stop reading as a race.
     *
     * @param fieldName       the field, as it should appear in the report
     * @param value           the value read or written, may be {@code null}
     * @param isWrite         {@code true} for a write
     * @param threadId        the thread that made the access
     * @param lockFingerprint the locks that thread held at the access, 0 for none
     * @param ownMonitor      identity hash of the receiver when its monitor was held, else 0
     * @param methodMonitor   identity hash of the enclosing synchronized method's monitor, else 0
     * @param volatileField   whether the field is declared {@code volatile}
     * @param constantTag     the constant this write stored, {@code Integer.MIN_VALUE} for none
     * @param identity        {@code System.identityHashCode} of the owner, 0 for statics
     * @since 1.9.8
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId, long lockFingerprint,
                                            int ownMonitor, int methodMonitor,
                                            boolean volatileField, int constantTag, int identity) {
        recordFieldAccessUnderLocks(fieldName, value, isWrite, threadId, lockFingerprint,
                ownMonitor, methodMonitor, volatileField, constantTag, identity, 0);
    }

    /**
     * Feeds the per-instance lockset for one access.
     *
     * <p>Extracted so the two public overloads share it verbatim rather than by copy: the newer
     * one differs only in carrying the stored value, and a second copy of this bookkeeping would
     * be a twin waiting to drift.
     *
     * @param fieldName       the field
     * @param isWrite         {@code true} for a write
     * @param lockFingerprint the locks that thread held at the access
     * @param ownMonitor      identity hash of the receiver when its monitor was held, else 0
     * @param methodMonitor   identity hash of the enclosing synchronized method's monitor, else 0
     * @param volatileField   whether the field is declared {@code volatile}
     * @param constantTag     the constant this write stored
     * @param identity        identity hash of the owner, 0 for statics
     * @param threadId        the accessing thread
     * @return whether the receiver was still exclusive to the thread building it
     */
    private boolean noteGuard(String fieldName, boolean isWrite, long lockFingerprint,
                              int ownMonitor, int methodMonitor, boolean volatileField,
                              int constantTag, int identity, long threadId) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        // Whether the receiver is still exclusive to the thread building it travels on the
        // record (#312). The locksets are fed unconditionally: whether construction
        // accesses are excused is decided at analysis time, where the corroboration the
        // excuse needs - the receiver staying shared across later rounds - is visible.
        boolean exclusive = inExclusivePhase(identity, threadId);
        // Per instance, not just per field. One WeakEntry in a striped cache is written under
        // its own segment's lock every time; merging the entries makes those locks disagree
        // and collapses an intersection that is consistent for every object taken alone.
        FieldGuard guard = fieldLocks.computeIfAbsent(guardKey(fieldName, identity),
                ignored -> new FieldGuard());
        if (volatileField) {
            guard.noteVolatile();
            if (isWrite) {
                guard.noteVolatileWrite();
            }
        }
        if (isWrite) {
            guard.noteWriteConstant(constantTag);
        }
        guard.noteAccess(lockFingerprint, ownMonitor, methodMonitor, isWrite);
        return exclusive;
    }
    /**
     * Records a field access, with the identity of the reference the write stored.
     *
     * <p>The evidence the settled-cache excuse was missing. Convergence is a property of the
     * field, and the defect a double-submit hides is a property of the payload, so an access
     * stream that carries no values cannot tell {@code if (view == null) view = new View(this)}
     * from {@code if (job == null) job = submit()}. Both miss-check, both race, both settle. What
     * separates them is what happens to the stored object afterwards: an idempotent value goes
     * quiet, and a live job does not.
     *
     * @param fieldName       the field, as it should appear in the report
     * @param value           the value read or written, may be {@code null}
     * @param isWrite         {@code true} for a write
     * @param threadId        the thread that made the access
     * @param lockFingerprint the locks that thread held at the access, 0 for none
     * @param ownMonitor      identity hash of the receiver when its monitor was held, else 0
     * @param methodMonitor   identity hash of the enclosing synchronized method's monitor, else 0
     * @param volatileField   whether the field is declared {@code volatile}
     * @param constantTag     the constant this write stored, {@code Integer.MIN_VALUE} for none
     * @param identity        {@code System.identityHashCode} of the owner, 0 for statics
     * @param storedIdentity  {@code System.identityHashCode} of the reference this write stored,
     *                        0 when it stored no reference or the weaver could not reach it
     * @since 1.9.8
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId, long lockFingerprint,
                                            int ownMonitor, int methodMonitor,
                                            boolean volatileField, int constantTag, int identity,
                                            int storedIdentity) {
        recordFieldAccessUnderLocks(fieldName, value, isWrite, threadId, lockFingerprint,
                ownMonitor, methodMonitor, volatileField, constantTag, identity, storedIdentity,
                null, null, 0L);
    }

    /**
     * Records an agent-fed access together with the object its field belongs to.
     *
     * @param fieldName       the field, as it should appear in the report
     * @param value           the value read or written, may be {@code null}
     * @param isWrite         {@code true} for a write
     * @param threadId        the thread that made the access
     * @param lockFingerprint the locks that thread held at the access, 0 for none
     * @param ownMonitor      identity hash of the receiver when its monitor was held, else 0
     * @param methodMonitor   identity hash of the enclosing synchronized method's monitor, else 0
     * @param volatileField   whether the field is declared {@code volatile}
     * @param constantTag     the constant this write stored, {@code Integer.MIN_VALUE} for none
     * @param identity        {@code System.identityHashCode} of the owner, 0 for statics
     * @param storedIdentity  {@code System.identityHashCode} of the reference this write stored,
     *                        0 when it stored no reference or the weaver could not reach it
     * @param receiver        the owner itself, {@code null} for a static field or when unknown
     * @param stamp           the accessing thread's {@link HappensBefore} clock at the access,
     *                        {@code null} when none was taken; when {@code threadId} is the
     *                        calling thread's own, its clock is taken here instead
     * @param round           {@link HappensBefore#round()} when the access happened, 0 when
     *                        unknown; it decides the access's round, which is otherwise the round
     *                        current when this call is made
     * @since 1.12.3
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId, long lockFingerprint,
                                            int ownMonitor, int methodMonitor,
                                            boolean volatileField, int constantTag, int identity,
                                            int storedIdentity, @Nullable Object receiver,
                                            HappensBefore.@Nullable Stamp stamp, long round) {
        boolean exclusive = noteGuard(fieldName, isWrite, lockFingerprint, ownMonitor,
                methodMonitor, volatileField, constantTag, identity, threadId);
        record(fieldName, value, isWrite, threadId, null, false, lockFingerprint, identity,
                ownMonitor, methodMonitor, exclusive, storedIdentity, generationOf(identity),
                identity == 0 ? 0 : instanceOf(receiver),
                stamp != null ? stamp : stampIfOwn(threadId), epochOf(round));
    }

    /**
     * {@return the calling thread's clock when {@code threadId} is its own, else {@code null}}
     *
     * <p>A caller recording on another thread's behalf, the telemetry drain replaying a worker's
     * access, holds a clock that says nothing about that worker, so it contributes none.
     */
    private static HappensBefore.@Nullable Stamp stampIfOwn(long threadId) {
        return threadId == Thread.currentThread().threadId() ? HappensBefore.current() : null;
    }

    private void record(String fieldName, @Nullable Object value, boolean isWrite, long threadId,
                        @Nullable Object owner, boolean ownerKnown, long lockFingerprint) {
        // The owner, when named, is the instance its accesses are grouped by: without it two
        // objects that each stay on one thread merge into one history under the field name
        // (#750). The identity hash stays 0, so the lock model is still the field's own guard.
        record(fieldName, value, isWrite, threadId, owner, ownerKnown, lockFingerprint, 0, 0, 0,
                false, 0, 0, instanceOf(owner), stampIfOwn(threadId), invocationEpoch.get());
    }

    private void record(String fieldName, @Nullable Object value, boolean isWrite, long threadId,
                        @Nullable Object owner, boolean ownerKnown, long lockFingerprint,
                        int identity, int ownMonitor, int methodMonitor, boolean exclusivePhase,
                        int storedIdentity, int generation, int instance,
                        HappensBefore.@Nullable Stamp stamp, long epoch) {
        if (!enabled || fieldName == null || fieldName.isBlank()) {
            return;
        }

        // Record what is known about locks before the bookkeeping below: the question is only
        // meaningful while the caller is still inside whatever region it is being asked about.
        FieldGuard guard = fieldLocks.computeIfAbsent(fieldName, ignored -> new FieldGuard());
        int[] ownerLocks = null;
        if (ownerKnown) {
            guard.noteOwner(owner);
            // The same probe the streamed intersection just took, kept for the per-round one.
            ownerLocks = owner == null ? HeldLocks.NONE : HeldLocks.intersect(null, owner, true);
        } else if (lockFingerprint != UNMODELLED) {
            if (identity == 0) {
                // This is the guard the analysis consults for this access; with an identity the
                // per-instance guard above already holds the fuller answer.
                guard.noteAccess(lockFingerprint, ownMonitor, methodMonitor, isWrite);
            }
        } else {
            // No owner and no fingerprint: the caller has told us nothing about locks, which is
            // what the original overloads do and what they have always effectively meant.
            guard.noteUnmodelled();
        }

        List<FieldAccessRecord> history = fieldHistory.computeIfAbsent(fieldName, ignored -> new ArrayList<>());
        synchronized (history) {
            history.add(new FieldAccessRecord(threadId, isWrite, epoch,
                    ownerKnown, identity, lockFingerprint, ownMonitor, methodMonitor,
                    exclusivePhase, isWrite ? storedIdentity : 0, generation, instance, stamp,
                    ownerLocks));
            // Index the owner's own writes as they arrive, so asking "did this published object
            // then go quiet" later costs a map lookup rather than a scan of every history.
            if (isWrite && identity != 0) {
                lastOwnWriteEpoch.merge(identity, epoch, Math::max);
            }
        }

        for (CompoundOperation operation : activeOperations.values()) {
            if (operation.threadId != threadId) {
                continue;
            }

            if (isWrite) {
                Object initialRead = operation.firstReads.get(fieldName);
                if (initialRead != null && !initialRead.equals(value)) {
                    atomicityViolations.add(String.format(
                        "%s on %s: read %s and later wrote %s",
                        operation.operationName, fieldName, initialRead, value
                    ));
                }
            } else {
                operation.firstReads.computeIfAbsent(fieldName, k -> value);
            }
        }
    }
    /**
     * Detect check then act violation.
     *
     * @param fieldName the field involved, as it should appear in the report
     * @param checkValue the value observed by the check
     * @param expectedValue the value the caller expected to find
     * @param wouldAct {@code true} when the caller would have acted on the checked value
     * @return {@code true} when a check-then-act sequence was observed on that field
     */
    public boolean detectCheckThenActViolation(String fieldName, Object checkValue,
                                               Object expectedValue, boolean wouldAct) {
        if (!enabled || !wouldAct) {
            return false;
        }

        boolean violation = checkValue != null ? !checkValue.equals(expectedValue) : expectedValue != null;
        if (violation) {
            atomicityViolations.add(String.format(
                "Check-then-act violation on %s: checked %s but observed %s",
                fieldName, checkValue, expectedValue
            ));
        }
        return violation;
    }
    /**
     * Discards everything recorded about {@code fieldName}, whatever the instance.
     *
     * <p>For a fact learned late. A field mutated through a {@code VarHandle} or an atomic updater
     * belongs to a lock-free protocol that no lockset can judge, but the binding that proves it
     * sits in a static initializer which may run after the first accesses have already been
     * recorded. Filtering at record time therefore cannot be enough: the early accesses are already
     * in, and they are the ones that produce the finding. Forgetting the field the moment the fact
     * arrives is what makes the answer independent of that ordering.
     *
     * @param fieldName the field to forget, as it appears in reports
     * @since 1.9.8
     */
    public void forgetField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return;
        }
        fieldHistory.remove(fieldName);
        // Lock state is keyed per instance as "field@identity", so the field's own key is not the
        // only one to clear.
        fieldLocks.keySet().removeIf(key ->
                key.equals(fieldName) || key.startsWith(fieldName + '@'));
    }

    /**
     * Records that {@code threadId} took the object with identity {@code identity} out of a queue
     * or an atomic slot, which makes it exclusive to that thread again.
     *
     * <p>The construction phase (#312) ends for good at the first access from a second thread,
     * because nothing in an access stream says when an object stops being shared. A take does. An
     * object polled from a queue or swapped out of a slot with {@code getAndSet(x, null)} is no
     * longer reachable through that queue or slot, so the thread that took it is the only one the
     * structure hands it to. netty's adaptive allocator moves a chunk between magazines exactly so:
     * under one magazine's lock, then taken, then under another magazine's lock or none (#555).
     * Without this the two locks never intersect and the chunk reads as racing.
     *
     * <p>What a take starts is a new ownership generation, exclusive to the taker until any other
     * thread touches the object, exactly as construction is. It only ever excuses: a thread that
     * kept a reference from before the take and uses it anyway ends the exclusion at its first
     * access, and locks must still agree within each generation. In the generation the object is
     * still in, such an access withdraws the taker's exclusion for the whole generation, earlier
     * accesses included (#559). Identity 0 is not an object and is ignored.
     *
     * @param identity {@code System.identityHashCode} of the object taken
     * @param threadId the thread that took it
     * @since 1.12.1
     */
    public void recordOwnershipTaken(int identity, long threadId) {
        recordOwnershipTaken(identity, 0, threadId);
    }

    /**
     * Records that {@code threadId} took the object with identity {@code identity} out of the
     * container with identity {@code container}, which makes it exclusive to that thread again.
     *
     * <p>{@link #recordOwnershipTaken(int, long)} with the structure the object left. Knowing it
     * lets a take that is the first recorded event for the object name the thread that last
     * offered the object to that same container as generation 0's owner (#630); see
     * {@link #recordOwnershipOffered}. A container of 0 means not known, which is what the
     * two-argument form passes, and then no offer is consulted.
     *
     * @param identity  {@code System.identityHashCode} of the object taken
     * @param container {@code System.identityHashCode} of the queue it was taken from, 0 when unknown
     * @param threadId  the thread that took it
     * @since 1.12.1
     */
    public void recordOwnershipTaken(int identity, int container, long threadId) {
        if (!enabled || identity == 0) {
            return;
        }
        ReceiverState state = receiverStates.compute(identity, (ignored, previous) ->
                new ReceiverState(threadId, previous == null ? 1 : previous.generation + 1));
        // Consumed by the first take either way: once a generation exists, the takers name every
        // later previous owner, and a stale offer must not outlive the hand-off it described.
        Offer offer = offers.isEmpty() ? null : offers.remove(identity);
        // Only when the take is the first event for the object: an access that created the state
        // already named generation 0's owner, and that owner stands.
        if (state.generation == 1 && offer != null && container != 0
                && offer.container == container && takerOf(identity, 0) == null) {
            recordTaker(identity, 0, offer.threadId);
        }
        recordTaker(identity, state.generation, threadId);
    }

    /**
     * Records that {@code threadId} offered the object with identity {@code identity} to the
     * container with identity {@code container}, a queue it is about to be taken out of.
     *
     * <p>The missing fact behind #630. When a take is the first event recorded for an object, no
     * access has shown who owned it before, so an access in generation 1 by any thread other than
     * the taker had to be excused: it might be the offerer's own late write (#557). An offer
     * recorded before that take, into the container the take came out of, names that owner, and
     * then only the offerer is excused. Offers are published before the structure accepts the
     * element, so in drain order they always precede the take that removes it.
     *
     * <p>Only an object with no recorded state yet is remembered, and only the most recent offer:
     * an object some access or take already described has an owner the stream showed. The offer
     * is dropped at the next take of the object, whatever container that take names, and when its
     * container is drained ({@link #recordContainerDrained}, #664). Identity or container 0
     * records nothing.
     *
     * @param identity  {@code System.identityHashCode} of the object offered
     * @param container {@code System.identityHashCode} of the queue it was offered to
     * @param threadId  the thread that offered it
     * @since 1.12.1
     */
    public void recordOwnershipOffered(int identity, int container, long threadId) {
        if (!enabled || identity == 0 || container == 0 || receiverStates.containsKey(identity)) {
            return;
        }
        offers.put(identity, new Offer(threadId, container));
    }

    /**
     * Records that {@code threadId} drained the container with identity {@code container}, a
     * {@code BlockingQueue.drainTo} whose elements the stream does not name one by one.
     *
     * <p>Drops every offer recorded into that container (#664). Without this an element offered,
     * drained, and put back by another thread through a call nothing observes keeps its first
     * offer, and a take-first generation then names the first offerer as owner, so the re-adder's
     * own late write reads as an alias. Dropping an offer only falls back to the #557 excuse, which
     * can withhold a finding and never adds one. Container 0 records nothing.
     *
     * @param container {@code System.identityHashCode} of the drained queue
     * @param threadId  the thread that drained it
     * @since 1.12.2
     */
    public void recordContainerDrained(int container, long threadId) {
        if (!enabled || container == 0 || offers.isEmpty()) {
            return;
        }
        offers.values().removeIf(offer -> offer.container == container);
    }

    /** {@return the ownership generation {@code identity} is currently in, 0 before any take} */
    private int generationOf(int identity) {
        if (identity == 0) {
            return 0;
        }
        ReceiverState state = receiverStates.get(identity);
        return state == null ? 0 : state.generation;
    }

    /**
     * {@return {@code history} with the taker's exclusivity withdrawn wherever an ownership
     * generation was also touched by an alias thread} (#559, #630)
     *
     * <p>A take makes the object exclusive to the taker until any other thread touches it, and the
     * first foreign access ends the exclusion from then on. In drain order that cannot catch an
     * alias holder whose only access comes after every access the taker made: each taker access
     * is still marked exclusive, so the taker drops out of the lockset question, and the alias
     * access is left to agree with nothing but itself. The race is real whenever the alias kept
     * its reference from before the take.
     *
     * <p>In a generation a later take closed, an access from the previous owner (the thread that
     * took the previous generation, or built it in generation 0) is a late-published handoff
     * access and does not withdraw exclusivity (#557). When the take drained before any access to
     * the object, generation 0's owner is the thread that offered it to the container the take
     * came out of, if that offer was recorded ({@link #recordOwnershipOffered}). When it was not,
     * every foreign thread is given the benefit: the offerer's own write can drain after the take,
     * and nothing in the stream tells it from an alias. But an access from a thread that was
     * neither this generation's taker nor the previous owner is an alias access, and withdraws
     * the taker's exclusivity even though a later take closed the generation (#630).
     */
    private List<FieldAccessRecord> withdrawExclusivityFromContestedGenerations(
            List<FieldAccessRecord> history) {
        Map<Integer, Set<Integer>> contested = new HashMap<>();
        for (FieldAccessRecord access : history) {
            if (access.generation > 0 && !access.exclusivePhase && access.identity != 0) {
                int currentGen = generationOf(access.identity);
                if (access.generation == currentGen) {
                    contested.computeIfAbsent(access.identity, k -> new HashSet<>()).add(access.generation);
                } else if (access.generation < currentGen) {
                    Long takerG = takerOf(access.identity, access.generation);
                    Long takerPrev = takerOf(access.identity, access.generation - 1);
                    boolean isTaker = takerG != null && access.threadId == takerG;
                    boolean isPreviousOwner = takerPrev == null || access.threadId == takerPrev;
                    if (!isTaker && !isPreviousOwner) {
                        contested.computeIfAbsent(access.identity, k -> new HashSet<>()).add(access.generation);
                    }
                }
            }
        }
        if (contested.isEmpty()) {
            return history;
        }
        List<FieldAccessRecord> judged = new ArrayList<>(history.size());
        for (FieldAccessRecord access : history) {
            Set<Integer> contestedGens = contested.get(access.identity);
            if (access.exclusivePhase && contestedGens != null && contestedGens.contains(access.generation)) {
                judged.add(new FieldAccessRecord(access.threadId, access.write, access.epoch,
                        access.ownerKnown, access.identity, access.fingerprint, access.ownMonitor,
                        access.methodMonitor, false, access.storedIdentity, access.generation,
                        access.instance, access.stamp, access.ownerLocks));
            } else {
                judged.add(access);
            }
        }
        return judged;
    }

    /**
     * Analyses what has been recorded about atomicity and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public AtomicityReport analyzeAtomicity() {
        AtomicityReport report = new AtomicityReport();
        report.checkThenActViolations.addAll(atomicityViolations);

        for (Map.Entry<String, List<FieldAccessRecord>> entry : fieldHistory.entrySet()) {
            // Copy under the list's lock, then analyze per invocation round: rounds are
            // ordered by the runner (worker latch, then the next round's submissions), so
            // only same-round accesses can lack a happens-before edge. Without round marks
            // (standalone use) every record is in epoch 0 and behavior is unchanged.
            List<FieldAccessRecord> snapshot;
            synchronized (entry.getValue()) {
                snapshot = new ArrayList<>(entry.getValue());
            }
            List<FieldAccessRecord> copy = withdrawExclusivityFromContestedGenerations(snapshot);
            // Split by instance before anything else. Two threads touching the same field of two
            // different objects share nothing, and merging them is how a per-call object reads as
            // contended. Identity 0 means "not known", which keeps every pre-agent caller's
            // accesses in one group exactly as before, except that an owner-aware access carries
            // its owner's instance and is grouped by it (#750).
            //
            // Construction accesses (#312) leave the contention stats only when the excuse is
            // corroborated: the receiver's post-publication accesses must span more than one
            // harness-ordered round. A builder that wrote and a second thread that read once,
            // all inside one round, is exactly what a two-thread race over an inconsistent
            // lockset looks like, and it keeps reporting; a receiver built once and then read
            // round after round is the hand-off the rule exists for.
            Map<Long, Boolean> corroborated = new HashMap<>();
            for (FieldAccessRecord access : copy) {
                if (access.exclusivePhase) {
                    corroborated.computeIfAbsent(access.instanceKey,
                            instance -> spansLaterRounds(copy, instance));
                }
            }
            Map<AccessGroup, List<FieldAccessRecord>> byEpoch = new HashMap<>();
            for (FieldAccessRecord access : copy) {
                if (access.exclusivePhase
                        && Boolean.TRUE.equals(corroborated.get(access.instanceKey))) {
                    continue;
                }
                byEpoch.computeIfAbsent(
                        new AccessGroup(access.epoch, access.identity, access.instance),
                        ignored -> new ArrayList<>()).add(access);
            }

            // Per-instance excuses (#311, #312, #313), computed at most once per instance and
            // only when the lockset alone would have reported. All need ordered history, which
            // the per-round groups no longer carry.
            Map<Long, Boolean> excusedInstances = new HashMap<>();

            for (List<FieldAccessRecord> roundAccesses : byEpoch.values()) {
                Set<Long> threads = new HashSet<>();
                boolean hasRead = false;
                boolean hasWrite = false;
                boolean anyOwnerKnown = false;
                for (FieldAccessRecord access : roundAccesses) {
                    threads.add(access.threadId);
                    hasRead |= !access.write;
                    hasWrite |= access.write;
                    anyOwnerKnown |= access.ownerKnown;
                }
                // The streamed lockset spans the field's whole run, which makes it a cheap first
                // answer and a wrong last one: a different lock in each round empties it, although
                // the harness orders the rounds and each one was consistently locked. So once it
                // has collapsed, this round's own accesses decide. A round that raced still has an
                // empty intersection of its own and still reports. Accesses recorded without an
                // owner or a fingerprint collapse both, so every caller that predates
                // recordFieldAccessOn keeps the behaviour it had.
                int groupIdentity = roundAccesses.isEmpty() ? 0 : roundAccesses.get(0).identity;
                long groupInstance = roundAccesses.isEmpty() ? 0L : roundAccesses.get(0).instanceKey;
                FieldGuard locks = fieldLocks.get(guardKey(entry.getKey(), groupIdentity));
                // Safe publication is not an unguarded access. A volatile field whose every write
                // held the same lock is double-checked locking, where the reads take no lock on
                // purpose and the JMM makes that correct. Without this the idiom reports as a
                // check-then-act violation on every correct implementation of it, which is what
                // commons-lang's LazyInitializer and Guava's memoizing supplier both are.
                boolean sawUnguarded = locks == null
                        || (locks.sawUnguardedAccess()
                            && noLockCoveredTheRound(roundAccesses)
                            && !locks.isSafePublication(roundAccesses)
                            && !locks.writesOnlyOneConstant());
                // The per-instance excuses need a group that is one object's accesses. Identity 0
                // normally is not: it is the "not known" bucket, where every pre-agent caller's
                // accesses pool together, and excusing a pool of unrelated receivers because the
                // pool settled would be unsound. A static field is the exception (#337). Its
                // identity is 0 by construction rather than by ignorance - there is no receiver
                // to hash, so the declaring class stands in - and the group really is one field
                // of one class. What tells the two apart in the record is the stored value: only
                // the weaver reports a non-zero stored identity, and for an identity-0 group only
                // a static reference store can carry one. A pre-agent caller, an older agent, a
                // primitive or a shape the weaver cannot reach all report 0 and keep exactly the
                // answer they had.
                if (sawUnguarded && locks != null
                        && (groupIdentity != 0 || carriesPublishedValueEvidence(copy))) {
                    FieldGuard guard = locks;
                    boolean handOff = Boolean.TRUE.equals(corroborated.get(groupInstance));
                    boolean excused = excusedInstances.computeIfAbsent(groupInstance,
                            instance -> (handOff && postShareAccessesShareALock(copy, instance))
                                    || hintReadsConfirmedUnderTheWriteLock(copy, instance, guard,
                                            handOff)
                                    || settledSingleCheckCache(copy, instance, guard, handOff)
                                    || everyOwnershipGenerationAgreesOnALock(copy, instance));
                    sawUnguarded = !excused;
                }
                // Ordered by the shared happens-before model: every conflicting pair in this group
                // has an edge the lockset cannot see, a queue or map hand-off, a volatile
                // publication, a start or a join. Like the lock rule this judges each pair, not
                // the interleaving of a compound operation, and like every excuse here it only
                // ever removes a finding: a group whose accesses carry no stamps is not ordered.
                if (sawUnguarded && threads.size() > 1
                        && HappensBefore.everyConflictOrdered(roundAccesses, true)) {
                    sawUnguarded = false;
                }
                // A volatile field written by one thread in the round: that thread's
                // read-then-write cannot lose an update, and the other threads' reads of a
                // volatile are synchronization rather than data races, which is how
                // RaceConditionDetector judges the same field. Two writers keep the finding,
                // which is what keeps volatile count++ reportable.
                if (sawUnguarded && locks != null && locks.hasVolatileWrites()
                        && writerThreads(roundAccesses) == 1) {
                    sawUnguarded = false;
                }
                // Only claim to have looked at locks when an owner was actually supplied.
                String note = anyOwnerKnown ? SelfGuard.REPORT_NOTE : "";

                if (threads.size() > 1 && hasRead && hasWrite && sawUnguarded) {
                    report.unsafeFieldAccesses.add(String.format(
                        "%s: mixed read/write compound access across %d threads%s",
                        entry.getKey(),
                        threads.size(),
                        note
                    ));
                }
                if (threads.size() > 1 && hasWrite && sawUnguarded) {
                    report.totcouRaces.add(String.format(
                        "%s: state changed between check/use windows on %d threads%s",
                        entry.getKey(),
                        threads.size(),
                        note
                    ));
                }
            }
        }

        return report;
    }

    /** {@return how many distinct threads wrote in {@code accesses}} */
    private static int writerThreads(List<FieldAccessRecord> accesses) {
        Set<Long> writers = new HashSet<>();
        for (FieldAccessRecord access : accesses) {
            if (access.write) {
                writers.add(access.threadId);
            }
        }
        return writers.size();
    }

    /**
     * {@return whether {@code instance}'s post-publication accesses span more than one round}
     *
     * <p>The corroboration the construction excuse (#312) needs before it may touch anything.
     * Rounds are ordered by the harness, so a receiver that keeps being accessed in rounds after
     * the one that built it is demonstrably a publication that held. Everything inside a single
     * round could equally be two threads racing over an inconsistent lockset, and stays judged
     * exactly as it always was.
     */
    private static boolean spansLaterRounds(List<FieldAccessRecord> history, long instance) {
        long firstEpoch = Long.MIN_VALUE;
        for (FieldAccessRecord access : history) {
            if (access.instanceKey != instance || access.exclusivePhase) {
                continue;
            }
            if (firstEpoch == Long.MIN_VALUE) {
                firstEpoch = access.epoch;
            } else if (access.epoch != firstEpoch) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return whether some lock was held at every post-publication access to {@code instance}}
     *
     * <p>The streamed intersection cannot answer this: it folded the construction accesses in as
     * they arrived. Recomputed here from the records, with the same resolution the streamed set
     * uses, this is what lets netty build a chunk's metadata under the arena lock and serve it
     * under the chunk's own {@code runsAvailLock} — two locksets that never intersect, and no
     * race, because the construction half is excused (#312) and the serving half agrees with
     * itself.
     */
    private static boolean postShareAccessesShareALock(List<FieldAccessRecord> history,
                                                       long instance) {
        int[] common = null;
        for (FieldAccessRecord access : history) {
            if (access.instanceKey != instance || access.exclusivePhase) {
                continue;
            }
            if (access.fingerprint == UNMODELLED) {
                return false;
            }
            int[] held = heldLocksOf(access);
            if (held.length == 0) {
                return false;
            }
            if (common == null) {
                common = held;
                continue;
            }
            common = Lockset.intersect(common, held);
            if (common.length == 0) {
                return false;
            }
        }
        return common != null && common.length > 0;
    }

    /**
     * {@return whether the receiver changed hands through observed takes, and each owner guarded
     * it consistently} (#555)
     *
     * <p>{@link #postShareAccessesShareALock} asks for one lock across every post-publication
     * access, which an object that moves between owners cannot satisfy: each owner brings its own
     * lock. This asks for the same thing per ownership generation instead, and only once at least
     * one take has been seen, so a lock that simply changes with no take in between keeps
     * reporting. Accesses a taker made while the object was still exclusive to it are exclusion by
     * the take and need no lock, and so do the builder's construction accesses, because the take
     * that follows them corroborates the hand-off the construction phase assumed.
     */
    private static boolean everyOwnershipGenerationAgreesOnALock(List<FieldAccessRecord> history,
                                                                 long instance) {
        boolean taken = false;
        for (FieldAccessRecord access : history) {
            if (access.instanceKey == instance && access.generation > 0) {
                taken = true;
                break;
            }
        }
        if (!taken) {
            return false;
        }
        Map<Integer, int[]> commonPerGeneration = new HashMap<>();
        for (FieldAccessRecord access : history) {
            if (access.instanceKey != instance) {
                continue;
            }
            // Exclusive accesses need no lock. A taker's are exclusive by the take; the builder's
            // are exclusive by construction, and an observed take afterwards corroborates that the
            // object left the builder through a hand-off, as later rounds do for #312.
            if (access.exclusivePhase) {
                continue;
            }
            if (access.fingerprint == UNMODELLED) {
                return false;
            }
            int[] held = heldLocksOf(access);
            if (held.length == 0) {
                return false;
            }
            int[] previous = commonPerGeneration.get(access.generation);
            int[] common = previous == null ? held : Lockset.intersect(previous, held);
            if (common.length == 0) {
                return false;
            }
            commonPerGeneration.put(access.generation, common);
        }
        return true;
    }

    /**
     * {@return whether no lock was held at every access of one round's group, in either model}
     *
     * <p>The per-round counterpart of {@link FieldGuard#sawUnguardedAccess()}, recomputed from the
     * records: owner-aware accesses intersect the locks probed at the access, agent-fed ones the
     * locks their fingerprint and carried monitors resolve to, each model apart as the streamed
     * sets keep them. An access that carried no lock information at all is unguarded.
     */
    private static boolean noLockCoveredTheRound(List<FieldAccessRecord> round) {
        int[] ownerCommon = null;
        int[] fingerprintCommon = null;
        for (FieldAccessRecord access : round) {
            if (access.ownerKnown) {
                int[] held = access.ownerLocks != null ? access.ownerLocks : HeldLocks.NONE;
                ownerCommon = ownerCommon == null ? held : Lockset.intersect(ownerCommon, held);
                if (ownerCommon.length == 0) {
                    return true;
                }
            } else if (access.fingerprint == UNMODELLED) {
                return true;
            } else {
                int[] held = heldLocksOf(access);
                fingerprintCommon = fingerprintCommon == null
                        ? held
                        : Lockset.intersect(fingerprintCommon, held);
                if (fingerprintCommon.length == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return whether some lock was held at every write of one round's group}
     *
     * <p>The per-round counterpart of the write lockset {@link FieldGuard#isSafePublication}
     * streams, recomputed from the records with the same resolution. A write that carried no
     * fingerprint, owner-aware or unmodelled, says nothing about the locks it held, so it ends
     * the answer here rather than being skipped. A group with no write has nothing to publish.
     */
    private static boolean everyWriteOfTheRoundSharedALock(List<FieldAccessRecord> round) {
        int[] common = null;
        for (FieldAccessRecord access : round) {
            if (!access.write) {
                continue;
            }
            if (access.fingerprint == UNMODELLED) {
                return false;
            }
            int[] held = heldLocksOf(access);
            common = common == null ? held : Lockset.intersect(common, held);
            if (common.length == 0) {
                return false;
            }
        }
        return common != null;
    }

    /** {@return the resolved lock ids this access held, the carried monitors included} */
    private static int[] heldLocksOf(FieldAccessRecord access) {
        int[] members;
        if (access.fingerprint == 0L || access.fingerprint == UNMODELLED) {
            members = HeldLocks.NONE;
        } else {
            int[] registered = HeldLocks.members(access.fingerprint);
            members = registered != null ? registered
                    : new int[] {Lockset.opaque(access.fingerprint)};
        }
        if (access.ownMonitor == 0 && access.methodMonitor == 0) {
            return members;
        }
        // The same union the streamed lockset takes, so a monitor the members already name, or a
        // method monitor that is the receiver's own, is counted once here as it is there (#605).
        return Lockset.union(members, access.ownMonitor, access.methodMonitor);
    }

    /**
     * The safe half of double-checked locking, recognised per instance (#311): every write to
     * the field held a consistent lock, and the field demonstrated the confirming shape — an
     * unlocked read followed, on the same thread in the same round, by a read under one of the
     * locks the writes agree on. Spring's {@code ConcurrentReferenceHashMap} reads a segment's
     * {@code resizeThreshold} without the lock as a hint of whether restructuring is worth it,
     * and every path that acts re-reads it under the lock first; the paths that decide "do
     * nothing" leave no trace, which is why the confirmation is asked for once per instance
     * rather than after every hint.
     *
     * <p>Two directions deliberately stay findings: a field whose unlocked read is never
     * re-established under the write lock (the hint is the decision), and a field whose later
     * access under the lock is a write rather than a read (act-on-hint without re-checking).
     * One more case is excused with no confirmation needed: every read held at least one of
     * the locks every write held. Writers all holding {@code {A, B}} while one reader holds
     * {@code A} and another {@code B} collapses the plain intersection, yet every pairing is
     * mutually excluded.
     *
     * <p>The streamed write lockset spans the whole run, so a different lock in each round
     * empties it although the harness orders the rounds; once it has, each round's own writes
     * name the locks that round's reads are judged against, as {@link FieldGuard#isSafePublication}
     * does for the safe-publication excuse (#781). A round whose writes share no lock ends the
     * answer, and a round with no write has no writer for its reads to race.
     */
    private static boolean hintReadsConfirmedUnderTheWriteLock(List<FieldAccessRecord> history,
                                                               long instance, FieldGuard locks,
                                                               boolean constructionExcused) {
        int[] runWide = locks.writeLockSurvivors();
        Map<Long, int[]> perRound = runWide.length == 0
                ? writeLocksPerRound(history, instance, constructionExcused)
                : null;
        if (perRound != null && perRound.isEmpty()) {
            return false;
        }
        List<Integer> uncoveredReads = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            FieldAccessRecord access = history.get(i);
            if (access.instanceKey != instance
                    || (access.exclusivePhase && constructionExcused)) {
                continue;
            }
            if (access.fingerprint == UNMODELLED) {
                return false;
            }
            int[] writeLocks = perRound == null ? runWide : perRound.get(access.epoch);
            if (access.write || writeLocks == null || heldOneOf(access, writeLocks)) {
                continue;
            }
            uncoveredReads.add(i);
        }
        if (uncoveredReads.isEmpty()) {
            // Every access held one of the locks every write held; the intersection collapsed
            // only because different accesses chose different members of the write set.
            return true;
        }
        for (int at : uncoveredReads) {
            int[] writeLocks = perRound == null ? runWide : perRound.get(history.get(at).epoch);
            if (writeLocks != null
                    && confirmedLater(history, at, instance, writeLocks, constructionExcused)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return the locks every write of {@code instance} held, per round; empty when some round's
     * writes share none or there is no write at all}
     *
     * <p>The per-round counterpart of {@link FieldGuard#writeLockSurvivors()}, with the same
     * resolution and the same rule as {@link #everyWriteOfTheRoundSharedALock}: a write that
     * carried no fingerprint says nothing about its locks and ends the answer.
     */
    private static Map<Long, int[]> writeLocksPerRound(List<FieldAccessRecord> history,
                                                       long instance, boolean constructionExcused) {
        Map<Long, int[]> perRound = new HashMap<>();
        for (FieldAccessRecord access : history) {
            if (access.instanceKey != instance || !access.write
                    || (access.exclusivePhase && constructionExcused)) {
                continue;
            }
            if (access.fingerprint == UNMODELLED) {
                return Map.of();
            }
            int[] held = heldLocksOf(access);
            int[] previous = perRound.get(access.epoch);
            int[] common = previous == null ? held : Lockset.intersect(previous, held);
            if (common.length == 0) {
                return Map.of();
            }
            perRound.put(access.epoch, common);
        }
        return perRound;
    }

    /** {@return whether a later read on the same thread and round held one of the write locks} */
    private static boolean confirmedLater(List<FieldAccessRecord> history, int at, long instance,
                                          int[] writeLocks, boolean constructionExcused) {
        FieldAccessRecord hint = history.get(at);
        for (int i = at + 1; i < history.size(); i++) {
            FieldAccessRecord later = history.get(i);
            if (later.instanceKey != instance || later.write
                    || (later.exclusivePhase && constructionExcused)
                    || later.threadId != hint.threadId || later.epoch != hint.epoch) {
                continue;
            }
            if (heldOneOf(later, writeLocks)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return whether this access held at least one of {@code writeLocks}}
     *
     * <p>One is enough: {@code writeLocks} is the intersection over every write, so each member
     * was held at each write, and an access holding any member is mutually excluded against all
     * of them.
     */
    private static boolean heldOneOf(FieldAccessRecord access, int[] writeLocks) {
        if (access.ownMonitor != 0 && Lockset.contains(writeLocks, access.ownMonitor)) {
            return true;
        }
        if (access.methodMonitor != 0
                && Lockset.contains(writeLocks, access.methodMonitor)) {
            return true;
        }
        if (access.fingerprint == 0L || access.fingerprint == UNMODELLED) {
            return false;
        }
        int[] members = HeldLocks.members(access.fingerprint);
        if (members == null) {
            return Lockset.contains(writeLocks,
                    Lockset.opaque(access.fingerprint));
        }
        for (int hash : members) {
            if (Lockset.contains(writeLocks, hash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The racy single-check cache, recognised by how it settles (#313).
     *
     * <p>Jackson's serializer caches are the canonical case: read a non-volatile reference and,
     * on a miss, compute a replacement and store it. Two threads can miss together and one
     * write can be lost, which is exactly what the analysis sees. What tells the idiom apart
     * from a genuine lost update is not the access pattern but what happens afterwards: a cache
     * converges. Its writes are confined to a warming prefix of rounds — a lost fill surfaces
     * as the next round's re-miss and re-write, so on a slow scheduler the prefix is more than
     * one round — every writer read the field before writing (the miss check its store depends
     * on), and the run then stays settled, reading from at least two threads across at least as
     * many rounds as the warming took and never fewer than two, without another write. A
     * counter or a copy-on-write structure that loses updates keeps writing every round and can
     * never out-settle its own warming, and a warming longer than the writer count is not
     * warming at all; a run too short to
     * show convergence keeps its finding, which is the conservative direction. A field raced
     * once and then never touched again — jackson's lazily created map views — cannot show
     * settled reads of its own; there the run answers instead: rounds are harness-ordered, so a
     * run that kept executing for the required rounds after the field's last write, with the
     * field demonstrably never raced again, is the same convergence on the only clock left,
     * while a race in the closing rounds earns nothing.
     *
     * <p>What this deliberately does not judge is whether the stored value was safe to publish
     * unsafely. A torn or stale value is visibility, not atomicity, and stays
     * {@code ConstructorSafetyValidator} and {@code VisibilityMonitor} business.
     */
    private boolean settledSingleCheckCache(List<FieldAccessRecord> history, long instance,
                                            FieldGuard locks,
                                            boolean constructionExcused) {
        if (locks.isVolatileField()) {
            return false;
        }
        long lastWriteEpoch = Long.MIN_VALUE;
        Set<Long> warmingRounds = new HashSet<>();
        Set<Long> writers = new HashSet<>();
        for (FieldAccessRecord access : history) {
            if (access.instanceKey != instance
                    || (access.exclusivePhase && constructionExcused)) {
                continue;
            }
            if (access.fingerprint == UNMODELLED) {
                return false;
            }
            if (access.write) {
                warmingRounds.add(access.epoch);
                writers.add(access.threadId);
                if (access.epoch > lastWriteEpoch) {
                    lastWriteEpoch = access.epoch;
                }
            }
        }
        if (lastWriteEpoch == Long.MIN_VALUE) {
            return false;
        }
        // Each warm round beyond the first exists because some loser's fill was overwritten and
        // it re-missed, so a genuine cache cannot warm for more rounds than it has writers. A
        // field that keeps being written past that is not converging, however quiet it goes
        // afterwards.
        if (warmingRounds.size() > writers.size()) {
            return false;
        }
        Set<Long> settledRounds = new HashSet<>();
        Set<Long> settledReaders = new HashSet<>();
        Set<Long> readBeforeWriting = new HashSet<>();
        for (FieldAccessRecord access : history) {
            if (access.instanceKey != instance) {
                continue;
            }
            if (access.exclusivePhase && constructionExcused) {
                // A construction read is still that thread's miss check. The builder reads its
                // own fresh field before anything is published, the flip can land between its
                // read and its store, and without this the store would look blind.
                if (!access.write) {
                    readBeforeWriting.add(access.threadId);
                }
                continue;
            }
            if (access.epoch > lastWriteEpoch) {
                settledRounds.add(access.epoch);
                settledReaders.add(access.threadId);
                continue;
            }
            // Inside the warm round: the single-check shape requires every writer to have read
            // the field first. A store with no preceding read is initialization, not a cache.
            if (access.write) {
                if (!readBeforeWriting.contains(access.threadId)) {
                    return false;
                }
            } else {
                readBeforeWriting.add(access.threadId);
            }
        }
        int quietRoundsNeeded = Math.max(2, warmingRounds.size());
        boolean fieldShowsSettledReads = settledRounds.size() >= quietRoundsNeeded
                && settledReaders.size() >= 2;
        // A one-shot view field — jackson's PrivateMaxEntriesMap.entrySet — is raced once during
        // warmup and then goes dark together with its receiver (the serializer cache rebuilds a
        // read-only snapshot and the backing map sleeps), so neither the field nor the object
        // can show settled reads. The run itself still can: rounds are harness-ordered, so a run
        // that kept executing for the required rounds after the field's last write, during which
        // the field was demonstrably never raced again, is the same convergence measured on the
        // only clock left. A race in the closing rounds earns nothing and keeps its finding.
        boolean settled = fieldShowsSettledReads
                || invocationEpoch.get() - lastWriteEpoch >= quietRoundsNeeded;
        // Convergence alone is not enough (#326): it is a property of the field, and a
        // double-submit's defect is a property of what was stored.
        return settled && everyPublishedValueWentQuiet(history, instance);
    }
    /**
     * The value-level half of the settle rule (#326).
     *
     * <p>{@return whether every reference this field published then went quiet}
     *
     * <p>Convergence is a property of the field; the defect a double-submit hides is a property
     * of the payload. {@code if (view == null) view = new View(this)} and
     * {@code if (job == null) job = submit()} produce the same access stream - both miss-check,
     * both race, both settle - and the second one submitted the work twice. What separates them
     * is what the published object does next: an effectively immutable value is written once and
     * read from then on, which is what the JMM's final-field guarantee promises statically, while
     * a live job keeps mutating.
     *
     * <p>So the excuse additionally requires that no field of a published value was written after
     * the round that published it. The evidence is the woven stream itself: every write already
     * carries the identity of the object it belongs to, and this asks whether that object is one
     * this field published.
     *
     * <p><strong>Absence of evidence keeps the previous answer.</strong> A stored identity of 0 -
     * a primitive write, a shape the weaver could not reach, an older agent, or a payload of a
     * type the agent does not weave, which includes every JDK class - means nothing is known, and
     * nothing known must not become a finding. The rule only ever narrows, and only where there
     * is something to narrow with.
     *
     * @param history  every access recorded for the field
     * @param instance the instance being judged, as {@link FieldAccessRecord#instanceKey}
     */
    private boolean everyPublishedValueWentQuiet(List<FieldAccessRecord> history, long instance) {
        for (FieldAccessRecord access : history) {
            if (!access.write || access.instanceKey != instance || access.storedIdentity == 0) {
                continue;
            }
            Long lastWrite = lastOwnWriteEpoch.get(access.storedIdentity);
            if (lastWrite != null && lastWrite > access.epoch) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return whether any write in this field's history carried the identity of what it stored}
     *
     * <p>The discriminator that lets the identity-0 group reach the per-instance excuses at all
     * (#337). Identity 0 is two different situations wearing the same number: a static field,
     * whose receiver does not exist, and an access recorded without a receiver, whose receiver is
     * merely unknown. Only the first can produce a non-zero stored identity, because only the
     * weaver reports one and the weaver always supplies a real receiver for an instance access.
     *
     * <p>Being evidence-gated is what keeps this conservative in the direction that matters. The
     * excuses can now silence a static single-check cache, but only for a run where the agent
     * actually observed what was published; with nothing observed, the group keeps reporting
     * exactly as it did before, which is what every caller that predates the value evidence gets.
     *
     * @param history every access recorded for the field
     */
    private static boolean carriesPublishedValueEvidence(List<FieldAccessRecord> history) {
        for (FieldAccessRecord access : history) {
            if (access.write && access.identity == 0 && access.storedIdentity != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Standardized alias for {@link #analyzeAtomicity()}.
     *
     * @return the findings this detector collected during the run
     */
    public AtomicityReport analyze() {
        return analyzeAtomicity();
    }

    private String operationKey(String operationName) {
        return Thread.currentThread().threadId() + ":" + operationName;
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        activeOperations.clear();
        fieldHistory.clear();
        fieldLocks.clear();
        // Per run, like everything else here. A stale entry would answer "this object was still
        // being written" about an object from a previous invocation, denying a settle excuse the
        // current run earned.
        lastOwnWriteEpoch.clear();
        atomicityViolations.clear();
        receiverStates.clear();
        generationTakers.clear();
        offers.clear();
        instances.clear();
        roundTokens = new long[0];
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

    public static class AtomicityReport {
        /** Fields checked and then acted on without holding a lock across both. */
        public final Set<String> checkThenActViolations = new HashSet<>();
        /** Fields with mixed reads and writes from more than one thread. */
        public final Set<String> unsafeFieldAccesses = new HashSet<>();
        /** Fields whose state changed between the check and the use (TOCTOU). The field name misspells the acronym; it is public API and kept as-is for compatibility. */
        public final Set<String> totcouRaces = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !checkThenActViolations.isEmpty()
                || !unsafeFieldAccesses.isEmpty()
                || !totcouRaces.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No atomicity violations detected.";
            }

            StringBuilder sb = new StringBuilder("ATOMICITY VIOLATIONS DETECTED:\n");
            if (!checkThenActViolations.isEmpty()) {
                sb.append("\nCheck-then-act issues:\n");
                for (String violation : checkThenActViolations) {
                    sb.append("  - ").append(violation).append('\n');
                }
            }
            if (!unsafeFieldAccesses.isEmpty()) {
                sb.append("\nUnsafe compound field accesses:\n");
                for (String access : unsafeFieldAccesses) {
                    sb.append("  - ").append(access).append('\n');
                }
            }
            if (!totcouRaces.isEmpty()) {
                sb.append("\nTOCTOU windows:\n");
                for (String race : totcouRaces) {
                    sb.append("  - ").append(race).append('\n');
                }
            }
            sb.append("\nFix: synchronize the full compound operation or use CAS-based primitives");
            return sb.toString();
        }
    }
}
