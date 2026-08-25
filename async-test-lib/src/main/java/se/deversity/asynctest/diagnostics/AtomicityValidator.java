package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Arrays;
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
     * the same false positive this split exists to remove.
     */
    private record AccessGroup(long epoch, int identity) { }

    private static class FieldAccessRecord {
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

        FieldAccessRecord(long threadId, boolean write, long epoch, boolean ownerKnown,
                          int identity, long fingerprint, int ownMonitor, int methodMonitor,
                          boolean exclusivePhase) {
            this.threadId = threadId;
            this.write = write;
            this.epoch = epoch;
            this.ownerKnown = ownerKnown;
            this.identity = identity;
            this.fingerprint = fingerprint;
            this.ownMonitor = ownMonitor;
            this.methodMonitor = methodMonitor;
            this.exclusivePhase = exclusivePhase;
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
         */
        boolean isSafePublication() {
            return volatileField && writes.guarded();
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

        /**
         * The locks common to every access recorded on the agent path, as an intersection.
         *
         * <p>An event carries a fingerprint, which {@link HeldLocks#members(long)} turns back into
         * the locks it stands for, plus up to two monitors the fingerprint could not hold: the
         * receiver's own, when the access happened inside one of its {@code synchronized}
         * methods or under {@code synchronized (this)} further up the stack, and the monitor of
         * the enclosing {@code synchronized} method. Intersecting is what the owner-aware path
         * always did; the agent path compared digests for equality, which reported a field
         * guarded by {@code A} as soon as one path touched it under {@code {A, B}}.
         *
         * <p>A fingerprint with no registered members is treated as one opaque lock, so a caller
         * that only ever passed a digest keeps the equality model it had: the same digest twice
         * is consistent, two different digests collapse.
         */
        private static final class Lockset {

            /**
             * {@code null} before the first access; empty once no lock survived. Guarded by this
             * object's monitor, readers included: the producer is the single drain thread and the
             * readers run after analysis quiesces, so the monitor is never contended.
             */
            private int @Nullable [] common;

            synchronized void note(long fingerprint, int ownMonitor, int methodMonitor) {
                int[] current = common;
                if (current != null && current.length == 0) {
                    return;
                }
                int[] members = HeldLocks.members(fingerprint);
                if (members == null) {
                    members = new int[] {opaque(fingerprint)};
                }
                if (current == null) {
                    common = union(members, ownMonitor, methodMonitor);
                    return;
                }
                int kept = 0;
                int[] survivors = null;
                for (int hash : current) {
                    boolean held = (hash == ownMonitor && ownMonitor != 0)
                            || (hash == methodMonitor && methodMonitor != 0)
                            || contains(members, hash);
                    if (held) {
                        if (survivors != null) {
                            survivors[kept] = hash;
                        }
                        kept++;
                    } else if (survivors == null) {
                        survivors = new int[current.length - 1];
                        System.arraycopy(current, 0, survivors, 0, kept);
                    }
                }
                if (survivors == null) {
                    return;
                }
                common = kept == 0 ? HeldLocks.NONE : Arrays.copyOf(survivors, kept);
            }

            /** {@return whether some access was recorded and no lock covered all of them} */
            synchronized boolean collapsed() {
                return common != null && common.length == 0;
            }

            /** {@return whether at least one access was recorded and some lock covered all of them} */
            synchronized boolean guarded() {
                return common != null && common.length > 0;
            }

            /** {@return a copy of the surviving intersection; empty before any note or after collapse} */
            synchronized int[] survivors() {
                return common == null || common.length == 0 ? HeldLocks.NONE : common.clone();
            }

            private static int[] union(int[] members, int ownMonitor, int methodMonitor) {
                int extra = (ownMonitor != 0 && !contains(members, ownMonitor) ? 1 : 0)
                        + (methodMonitor != 0 && methodMonitor != ownMonitor
                                && !contains(members, methodMonitor) ? 1 : 0);
                if (members.length + extra == 0) {
                    return HeldLocks.NONE;
                }
                int[] out = Arrays.copyOf(members, members.length + extra);
                int at = members.length;
                if (ownMonitor != 0 && !contains(members, ownMonitor)) {
                    out[at] = ownMonitor;
                    at++;
                }
                if (methodMonitor != 0 && methodMonitor != ownMonitor
                        && !contains(members, methodMonitor)) {
                    out[at] = methodMonitor;
                }
                return out;
            }

            private static boolean contains(int[] hashes, int hash) {
                for (int candidate : hashes) {
                    if (candidate == hash) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * {@return a lock id for a digest nobody registered, in a range no identity hash uses}
             *
             * <p>Identity hashes are non-negative, so the sign bit marks an opaque id and the two
             * can never be confused for one another.
             */
            private static int opaque(long fingerprint) {
                return (int) (fingerprint ^ (fingerprint >>> 32)) | Integer.MIN_VALUE;
            }
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

        ReceiverState(long firstThread) {
            this.firstThread = firstThread;
        }
    }

    /** Publication state per receiver identity; identity 0 (unknown or static) is not tracked. */
    private final Map<Integer, ReceiverState> receiverStates = new ConcurrentHashMap<>();

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
        ReceiverState state = receiverStates.computeIfAbsent(identity,
                ignored -> new ReceiverState(threadId));
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
        invocationEpoch.incrementAndGet();
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
     * @since 1.10.0
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId,
                                            long lockFingerprint, boolean volatileField) {
        if (volatileField && fieldName != null && !fieldName.isBlank()) {
            FieldGuard guard = fieldLocks.computeIfAbsent(fieldName, ignored -> new FieldGuard());
            guard.noteVolatile();
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
     * @since 1.10.0
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
     * @since 1.10.0
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
     * @since 1.10.0
     */
    public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                            boolean isWrite, long threadId, long lockFingerprint,
                                            int ownMonitor, int methodMonitor,
                                            boolean volatileField, int constantTag, int identity) {
        boolean exclusive = false;
        if (fieldName != null && !fieldName.isBlank()) {
            // Whether the receiver is still exclusive to the thread building it travels on the
            // record (#312). The locksets are fed unconditionally: whether construction
            // accesses are excused is decided at analysis time, where the corroboration the
            // excuse needs — the receiver staying shared across later rounds — is visible.
            exclusive = inExclusivePhase(identity, threadId);
            // Per instance, not just per field. One WeakEntry in a striped cache is written under
            // its own segment's lock every time; merging the entries makes those locks disagree
            // and collapses an intersection that is consistent for every object taken alone.
            FieldGuard guard = fieldLocks.computeIfAbsent(guardKey(fieldName, identity),
                    ignored -> new FieldGuard());
            if (volatileField) {
                guard.noteVolatile();
            }
            if (isWrite) {
                guard.noteWriteConstant(constantTag);
            }
            guard.noteAccess(lockFingerprint, ownMonitor, methodMonitor, isWrite);
        }
        record(fieldName, value, isWrite, threadId, null, false, lockFingerprint, identity,
                ownMonitor, methodMonitor, exclusive);
    }

    private void record(String fieldName, @Nullable Object value, boolean isWrite, long threadId,
                        @Nullable Object owner, boolean ownerKnown, long lockFingerprint) {
        record(fieldName, value, isWrite, threadId, owner, ownerKnown, lockFingerprint, 0, 0, 0,
                false);
    }

    private void record(String fieldName, @Nullable Object value, boolean isWrite, long threadId,
                        @Nullable Object owner, boolean ownerKnown, long lockFingerprint,
                        int identity, int ownMonitor, int methodMonitor, boolean exclusivePhase) {
        if (!enabled || fieldName == null || fieldName.isBlank()) {
            return;
        }

        // Record what is known about locks before the bookkeeping below: the question is only
        // meaningful while the caller is still inside whatever region it is being asked about.
        FieldGuard guard = fieldLocks.computeIfAbsent(fieldName, ignored -> new FieldGuard());
        if (ownerKnown) {
            guard.noteOwner(owner);
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
            history.add(new FieldAccessRecord(threadId, isWrite, invocationEpoch.get(),
                    ownerKnown, identity, lockFingerprint, ownMonitor, methodMonitor,
                    exclusivePhase));
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
     * @since 1.10.0
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
            List<FieldAccessRecord> copy;
            synchronized (entry.getValue()) {
                copy = new ArrayList<>(entry.getValue());
            }
            // Split by instance before anything else. Two threads touching the same field of two
            // different objects share nothing, and merging them is how a per-call object reads as
            // contended. Identity 0 means "not known", which keeps every pre-agent caller's
            // accesses in one group exactly as before.
            //
            // Construction accesses (#312) leave the contention stats only when the excuse is
            // corroborated: the receiver's post-publication accesses must span more than one
            // harness-ordered round. A builder that wrote and a second thread that read once,
            // all inside one round, is exactly what a two-thread race over an inconsistent
            // lockset looks like, and it keeps reporting; a receiver built once and then read
            // round after round is the hand-off the rule exists for.
            Map<Integer, Boolean> corroborated = new HashMap<>();
            for (FieldAccessRecord access : copy) {
                if (access.exclusivePhase) {
                    corroborated.computeIfAbsent(access.identity,
                            identity -> spansLaterRounds(copy, identity));
                }
            }
            Map<AccessGroup, List<FieldAccessRecord>> byEpoch = new HashMap<>();
            for (FieldAccessRecord access : copy) {
                if (access.exclusivePhase
                        && Boolean.TRUE.equals(corroborated.get(access.identity))) {
                    continue;
                }
                byEpoch.computeIfAbsent(new AccessGroup(access.epoch, access.identity),
                        ignored -> new ArrayList<>()).add(access);
            }

            // Per-instance excuses (#311, #312, #313), computed at most once per identity and
            // only when the lockset alone would have reported. All need ordered history, which
            // the per-round groups no longer carry.
            Map<Integer, Boolean> excusedIdentities = new HashMap<>();

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
                // The lockset is per field rather than per round, and deliberately so: a field
                // guarded consistently in one round and raced in another has an empty
                // intersection overall, and the round that raced is a real finding. Accesses
                // recorded without an owner collapse it, so every caller that predates
                // recordFieldAccessOn keeps the behaviour it had.
                int groupIdentity = roundAccesses.isEmpty() ? 0 : roundAccesses.get(0).identity;
                FieldGuard locks = fieldLocks.get(guardKey(entry.getKey(), groupIdentity));
                // Safe publication is not an unguarded access. A volatile field whose every write
                // held the same lock is double-checked locking, where the reads take no lock on
                // purpose and the JMM makes that correct. Without this the idiom reports as a
                // check-then-act violation on every correct implementation of it, which is what
                // commons-lang's LazyInitializer and Guava's memoizing supplier both are.
                boolean sawUnguarded = locks == null
                        || (locks.sawUnguardedAccess()
                            && !locks.isSafePublication()
                            && !locks.writesOnlyOneConstant());
                if (sawUnguarded && groupIdentity != 0 && locks != null) {
                    FieldGuard guard = locks;
                    boolean handOff = Boolean.TRUE.equals(corroborated.get(groupIdentity));
                    boolean excused = excusedIdentities.computeIfAbsent(groupIdentity,
                            identity -> (handOff && postShareAccessesShareALock(copy, identity))
                                    || hintReadsConfirmedUnderTheWriteLock(copy, identity, guard,
                                            handOff)
                                    || settledSingleCheckCache(copy, identity, guard, handOff));
                    sawUnguarded = !excused;
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

    /**
     * {@return whether {@code identity}'s post-publication accesses span more than one round}
     *
     * <p>The corroboration the construction excuse (#312) needs before it may touch anything.
     * Rounds are ordered by the harness, so a receiver that keeps being accessed in rounds after
     * the one that built it is demonstrably a publication that held. Everything inside a single
     * round could equally be two threads racing over an inconsistent lockset, and stays judged
     * exactly as it always was.
     */
    private static boolean spansLaterRounds(List<FieldAccessRecord> history, int identity) {
        long firstEpoch = Long.MIN_VALUE;
        for (FieldAccessRecord access : history) {
            if (access.identity != identity || access.exclusivePhase) {
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
     * {@return whether some lock was held at every post-publication access to {@code identity}}
     *
     * <p>The streamed intersection cannot answer this: it folded the construction accesses in as
     * they arrived. Recomputed here from the records, with the same resolution the streamed set
     * uses, this is what lets netty build a chunk's metadata under the arena lock and serve it
     * under the chunk's own {@code runsAvailLock} — two locksets that never intersect, and no
     * race, because the construction half is excused (#312) and the serving half agrees with
     * itself.
     */
    private static boolean postShareAccessesShareALock(List<FieldAccessRecord> history,
                                                       int identity) {
        int[] common = null;
        for (FieldAccessRecord access : history) {
            if (access.identity != identity || access.exclusivePhase) {
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
            common = intersectLocks(common, held);
            if (common.length == 0) {
                return false;
            }
        }
        return common != null && common.length > 0;
    }

    /** {@return the resolved lock ids this access held, the carried monitors included} */
    private static int[] heldLocksOf(FieldAccessRecord access) {
        int[] members;
        if (access.fingerprint == 0L || access.fingerprint == UNMODELLED) {
            members = HeldLocks.NONE;
        } else {
            int[] registered = HeldLocks.members(access.fingerprint);
            members = registered != null ? registered
                    : new int[] {FieldGuard.Lockset.opaque(access.fingerprint)};
        }
        int extra = (access.ownMonitor != 0 ? 1 : 0) + (access.methodMonitor != 0 ? 1 : 0);
        if (extra == 0) {
            return members;
        }
        int[] out = Arrays.copyOf(members, members.length + extra);
        int at = members.length;
        if (access.ownMonitor != 0) {
            out[at] = access.ownMonitor;
            at++;
        }
        if (access.methodMonitor != 0) {
            out[at] = access.methodMonitor;
        }
        return out;
    }

    private static int[] intersectLocks(int[] left, int[] right) {
        int kept = 0;
        int[] out = new int[Math.min(left.length, right.length)];
        for (int hash : left) {
            if (FieldGuard.Lockset.contains(right, hash)) {
                out[kept] = hash;
                kept++;
            }
        }
        return kept == out.length ? out : Arrays.copyOf(out, kept);
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
     */
    private static boolean hintReadsConfirmedUnderTheWriteLock(List<FieldAccessRecord> history,
                                                               int identity, FieldGuard locks,
                                                               boolean constructionExcused) {
        int[] writeLocks = locks.writeLockSurvivors();
        if (writeLocks.length == 0) {
            return false;
        }
        List<Integer> uncoveredReads = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            FieldAccessRecord access = history.get(i);
            if (access.identity != identity
                    || (access.exclusivePhase && constructionExcused)) {
                continue;
            }
            if (access.fingerprint == UNMODELLED) {
                return false;
            }
            if (access.write || heldOneOf(access, writeLocks)) {
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
            if (confirmedLater(history, at, identity, writeLocks, constructionExcused)) {
                return true;
            }
        }
        return false;
    }

    /** {@return whether a later read on the same thread and round held one of the write locks} */
    private static boolean confirmedLater(List<FieldAccessRecord> history, int at, int identity,
                                          int[] writeLocks, boolean constructionExcused) {
        FieldAccessRecord hint = history.get(at);
        for (int i = at + 1; i < history.size(); i++) {
            FieldAccessRecord later = history.get(i);
            if (later.identity != identity || later.write
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
        if (access.ownMonitor != 0 && FieldGuard.Lockset.contains(writeLocks, access.ownMonitor)) {
            return true;
        }
        if (access.methodMonitor != 0
                && FieldGuard.Lockset.contains(writeLocks, access.methodMonitor)) {
            return true;
        }
        if (access.fingerprint == 0L || access.fingerprint == UNMODELLED) {
            return false;
        }
        int[] members = HeldLocks.members(access.fingerprint);
        if (members == null) {
            return FieldGuard.Lockset.contains(writeLocks,
                    FieldGuard.Lockset.opaque(access.fingerprint));
        }
        for (int hash : members) {
            if (FieldGuard.Lockset.contains(writeLocks, hash)) {
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
    private boolean settledSingleCheckCache(List<FieldAccessRecord> history, int identity,
                                            FieldGuard locks,
                                            boolean constructionExcused) {
        if (locks.isVolatileField()) {
            return false;
        }
        long lastWriteEpoch = Long.MIN_VALUE;
        Set<Long> warmingRounds = new HashSet<>();
        Set<Long> writers = new HashSet<>();
        for (FieldAccessRecord access : history) {
            if (access.identity != identity
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
            if (access.identity != identity) {
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
        return fieldShowsSettledReads
                || invocationEpoch.get() - lastWriteEpoch >= quietRoundsNeeded;
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
        atomicityViolations.clear();
        receiverStates.clear();
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
