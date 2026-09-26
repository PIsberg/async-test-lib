package se.deversity.asynctest.diagnostics;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock awareness, shared by the detectors that watch a non-thread-safe instance.
 *
 * <p>Most of the {@code Shared*} family reduces its input to "how many distinct threads
 * touched this instance". A correctly guarded twin - the same code with every access inside
 * {@code synchronized (instance)} - records an identical event stream, so those detectors
 * reported the fix as loudly as the bug. {@code SharedTypeAccuracyEvalTest} measured that
 * directly: 19 of 19 fired on unguarded sharing, and 17 of 19 also fired on the guarded twin.
 *
 * <p>What closes that gap is the Eraser lockset, in {@code TrackedInstance}: per instance, the
 * intersection of the locks held at every recorded access. Non-empty means some lock protects
 * the instance consistently; empty means none does. The instance's own monitor is one member of
 * that set rather than a special case, so {@code synchronized (theInstance)} needs no
 * declaration, and {@link Thread#holdsLock(Object)} stays the cheap intrinsic that answers for
 * it - it takes no monitor, so asking cannot serialize the threads being observed.
 *
 * <h2>What it does not see</h2>
 *
 * <p>A lock the test never declared and the agent never saw. Without the agent, {@code
 * synchronized} on any object other than the tracked instance emits no callback the library can
 * observe, so such a lock enters the set only through {@link HeldLocks}; with it, woven monitor
 * instructions and woven {@code Lock} call sites feed that same set on their own. A lock acquired
 * only inside unwoven code stays invisible and the finding stands, which is the safe direction to
 * be wrong in; the detector wording says so, so such a finding stays a prompt to verify
 * synchronization rather than a verdict.
 *
 * <p>The probe reflects the thread that calls the record method. Detectors whose recording API
 * takes an explicit {@code Thread} parameter for attribution still probe the caller, which is
 * the thread actually inside (or outside) the guarded region.
 *
 * <h2>Sharing is judged within one round</h2>
 *
 * <p>The runner orders invocation rounds: every worker of one round has finished before the
 * next round's are submitted. Two threads that touched an instance in different rounds never
 * overlapped, and a lock that guarded every access of one round says nothing about the next. So
 * the verdict is taken per round: more than one thread, and no lock common to all of their
 * accesses, inside one round. Counting threads across the run reported an instance that one
 * thread per round used in turn; with virtual threads, where every body execution runs on a fresh
 * thread, that was any instance used in two rounds at all. The round comes from the
 * {@link Scope} the running context binds to its workers. A detector used with no context
 * installed sees one round, the whole run, which is what it saw before.
 *
 * <p>Within a round the verdict is also per owner. A pool that hands an instance out through a
 * queue gives it to one thread at a time, and a take is the edge between one owner's accesses
 * and the next's; {@link Scope#ownershipTaken(Object)} records it, from the agent's woven queue
 * takes and slot swaps or from {@code AsyncTestContext.ownershipTaken} by hand.
 *
 * <p>And within a window the verdict follows the {@link HappensBefore} model. Two threads whose
 * accesses the program orders never overlapped, however the scheduler ran them: one used the
 * instance and counted a latch down and the other used it once its {@code await} returned, or a
 * parent used it, started a child that used it and joined the child. Each access carries its
 * thread's clock, and a thread whose access is ordered after the window's latest one takes the
 * instance over instead of sharing it. An access the model does not order, because nothing
 * ordered it or because the edge was made in unwoven code and never declared, shares the
 * instance exactly as before: an edge only ever removes a finding, so two threads using the
 * instance at once still report.
 *
 * <p>A take-over also starts the lockset again. Nothing before it can overlap anything ordered
 * after it, so one thread setting an instance up unlocked and handing it to threads that then
 * always lock it is consistent locking (#746). That holds only while every later access is
 * ordered after the hand-off: one that is not may overlap the accesses before it, and from then
 * on the lockset is the whole window's again.
 *
 * <p>Public only so that {@code AsyncTestContext} can own and bind the {@link Scope}; everything
 * else here is package-private and belongs to the detectors.
 *
 * @since 1.12.3
 */
@API(status = Status.INTERNAL, since = "1.12.3")
public final class SelfGuard {

    /**
     * The wording every lock-aware detector appends to a finding, so the report says exactly
     * which synchronization the detector can and cannot see.
     */
    static final String REPORT_NOTE =
            " (accesses under the instance's own monitor count as guarded, as do accesses under a"
            + " lock declared with AsyncTestContext.holdingLock(...); a lock that was never"
            + " declared is not observed - verify external synchronization or use a per-thread"
            + " instance; a pool checkout the agent did not weave can be declared with"
            + " AsyncTestContext.ownershipTaken(...), and any other hand-off with"
            + " HappensBefore.release(...) and acquire(...))";

    private SelfGuard() {
    }

    /**
     * {@return whether the calling thread holds {@code instance}'s own monitor}
     *
     * @param instance the shared instance being accessed; {@code null} counts as unguarded
     */
    static boolean heldOn(@Nullable Object instance) {
        return instance != null && Thread.holdsLock(instance);
    }

    /**
     * The round clock one run's sharing verdicts are taken against.
     *
     * <p>{@code AsyncTestContext} owns one per run. It binds it to each worker in {@code install}
     * and unbinds it in {@code uninstall}, so the binding falls under the same ThreadLocal symmetry
     * rule as the context itself, and advances it in {@code markInvocationStart}, which the runner
     * calls after the previous round's workers have all finished. Kept here rather than on the
     * context for the reason {@link WorkerSlot} is: a detector reads it, and diagnostics do not
     * reach up into the context.
     *
     * @since 1.12.3
     */
    @API(status = Status.INTERNAL, since = "1.12.3")
    public static final class Scope {

        private static final ThreadLocal<Scope> BOUND = new ThreadLocal<>();

        /** Advanced by the runner thread between rounds, read by every recording worker. */
        private final AtomicInteger round = new AtomicInteger();

        /**
         * How many times each tracked instance has been taken, by identity. An entry exists only
         * for an instance some detector tracks, created on its first access, so a queue full of
         * untracked elements adds nothing here.
         */
        private final Map<IdentityKey, AtomicInteger> handOffs = new ConcurrentHashMap<>();

        /** Creates a scope in its first round; the context creates one per run. */
        public Scope() {
            // Nothing to set up: the round starts at 0 and advances from markInvocationStart.
        }

        /**
         * Binds {@code scope} to the calling thread. Called from {@code AsyncTestContext.install}.
         *
         * @param scope the running context's scope
         */
        public static void bind(Scope scope) {
            BOUND.set(scope);
        }

        /** Unbinds the calling thread's scope. Called from {@code AsyncTestContext.uninstall()}. */
        public static void unbind() {
            BOUND.remove();
        }

        /** {@return the calling thread's scope, or {@code null} outside a run} */
        static @Nullable Scope current() {
            return BOUND.get();
        }

        /** Starts the next round. Called from {@code AsyncTestContext.markInvocationStart()}. */
        public void markInvocationStart() {
            round.incrementAndGet();
        }

        /** {@return the round in progress} */
        int round() {
            return round.get();
        }

        /**
         * Records that the calling thread has just taken sole ownership of {@code instance}.
         *
         * <p>A take out of a queue or a swap out of an atomic slot hands the object to one thread:
         * the structure held the only shared reference, so the previous owner put it back before
         * this one could take it. Accesses by different threads separated by a take are
         * therefore a hand-off, and the verdict counts threads per owner, within a round, rather
         * than across owners. An access still in flight from the previous owner after the take
         * joins the new owner's window and is reported, which is the use-after-return defect.
         *
         * <p>Called by the agent's woven takes and by {@code AsyncTestContext.ownershipTaken}.
         * A no-op on a thread with no scope bound, and for an instance nothing tracks yet: before
         * its first recorded access there is nothing to separate it from. Allocates one
         * short-lived key per call only while this run tracks some instance.
         *
         * @param instance the instance the calling thread now owns; {@code null} is ignored
         */
        public static void ownershipTaken(@Nullable Object instance) {
            if (instance == null) {
                return;
            }
            Scope scope = BOUND.get();
            if (scope == null || scope.handOffs.isEmpty()) {
                return;
            }
            AtomicInteger taken = scope.handOffs.get(new IdentityKey(instance));
            if (taken != null) {
                taken.incrementAndGet();
            }
        }

        /** {@return the take counter for {@code instance}, registering it on first use} */
        AtomicInteger handOffsOf(Object instance) {
            return handOffs.computeIfAbsent(new IdentityKey(instance), ignored -> new AtomicInteger());
        }
    }

    /** One tracked instance's take counter, in the scope it was first accessed in. */
    private static final class Binding {

        final Scope scope;
        final AtomicInteger handOffs;

        Binding(Scope scope, AtomicInteger handOffs) {
            this.scope = scope;
            this.handOffs = handOffs;
        }
    }

    /**
     * What one round's accesses to an instance amount to.
     *
     * <p>Immutable and replaced by compare-and-set, so the record path takes no lock. An access
     * that changes nothing (the same thread again, with the same clock, under the same locks)
     * replaces nothing and allocates nothing; a new window costs one object per instance per
     * round, and so does an owner's access after a synchronization event changed its clock.
     */
    private static final class Window {

        /** The round and the ownership these accesses belong to; see {@code windowKey}. */
        final long key;

        /**
         * The thread that made this window's latest access in happens-before order: the first
         * thread seen, until an access by another thread that the {@link HappensBefore} model
         * orders after it hands the instance on. Every earlier access of an unshared window
         * happens before this owner's latest one.
         */
        final long owner;

        /** The owner's clock at its latest access, {@code null} when that access had none. */
        final HappensBefore.@Nullable Stamp ownerStamp;

        /** Whether an access no ordering explains, by another thread, was seen in this window. */
        final boolean shared;

        /**
         * The locks the verdict reads: held at every access since the latest ordered hand-off,
         * while every access since is ordered after it, and otherwise at every access in this
         * window. Empty once one of those accesses held none of them.
         */
        final int[] locks;

        /** The locks held at every access in this window, hand-offs or not. */
        final int[] windowLocks;

        /** The owner before the latest ordered hand-off; meaningful only with a {@code handOffStamp}. */
        final long handOffOwner;

        /**
         * That owner's clock at its last access before the hand-off, or {@code null} when
         * {@code locks} covers the whole window: before any hand-off, and once an access that is
         * not ordered after the hand-off brought the earlier accesses back into the set.
         */
        final HappensBefore.@Nullable Stamp handOffStamp;

        Window(long key, long owner, HappensBefore.@Nullable Stamp ownerStamp, boolean shared,
               int[] locks, int[] windowLocks, long handOffOwner,
               HappensBefore.@Nullable Stamp handOffStamp) {
            this.key = key;
            this.owner = owner;
            this.ownerStamp = ownerStamp;
            this.shared = shared;
            this.locks = locks;
            this.windowLocks = windowLocks;
            this.handOffOwner = handOffOwner;
            this.handOffStamp = handOffStamp;
        }
    }

    /**
     * Per-instance lock bookkeeping, extended by a detector's own state class.
     *
     * <p>Holds the Eraser candidate set: the locks that were held at <em>every</em> recorded
     * access to this instance, as an intersection that only ever shrinks. While it is non-empty
     * some lock consistently protects the instance and there is nothing to report. Once it is
     * empty no lock does, and the accesses were racing.
     *
     * <p>The instance's own monitor is one member of that set rather than a special case, so
     * {@code synchronized (theInstance)} keeps working with no declaration at all, and a
     * {@code ReentrantLock} or a private lock object works once the test declares it through
     * {@link HeldLocks}.
     */
    abstract static class TrackedInstance {

        /**
         * Locks common to every access so far. {@code null} until the first access; empty once
         * the intersection has collapsed, which is the reportable state.
         *
         * <p>An {@link java.util.concurrent.atomic.AtomicReference} rather than a monitor because
         * this runs on the record path of detectors whose whole job is observing contention:
         * taking a lock here could serialize the very threads being watched and hide the race.
         * The update is a CAS loop over a value that only shrinks, so it terminates.
         */
        private final AtomicReference<int[]> candidateLocks = new AtomicReference<>();

        /** The round in progress for this instance; {@code null} before its first access. */
        private final AtomicReference<@Nullable Window> window = new AtomicReference<>();

        /** Latched once one round saw two threads and no lock common to all their accesses. */
        private volatile boolean unguardedSharing;

        /** Latched once one round saw no lock common to all its accesses, however many threads. */
        private volatile boolean unguardedRound;

        /** This instance's take counter, bound on its first access inside a run. */
        private volatile @Nullable Binding binding;

        /**
         * Records one access to {@code instance}, intersecting the candidate set with the locks
         * the calling thread holds right now.
         *
         * <p>Call this from the record path, on the accessing thread, before any analysis
         * bookkeeping - the probe is only meaningful while the caller is still inside the region
         * it is being asked about.
         *
         * @param instance the shared instance being accessed
         */
        final void noteAccess(@Nullable Object instance) {
            noteAccess(instance, true);
        }

        /**
         * Records one access to {@code instance}, saying whether it is a write.
         *
         * <p>The distinction exists for locks held in shared mode: the read view of a
         * {@link java.util.concurrent.locks.ReentrantReadWriteLock} guards a read and nothing
         * else, so it stays in the set for a read and drops out for a write. A caller that does
         * not know treats the access as a write, which is the direction that can only add a
         * finding.
         *
         * @param instance the shared instance being accessed
         * @param forWrite whether the access mutates the instance
         */
        final void noteAccess(@Nullable Object instance, boolean forWrite) {
            noteAccess(instance, forWrite, Thread.currentThread().threadId());
        }

        /**
         * Records one access to {@code instance}, attributed to the thread {@code threadId}.
         *
         * <p>For the detectors whose recording API names the accessing thread: the round verdict
         * counts that thread, while the lock probe still asks the caller, as everywhere here.
         *
         * @param instance the shared instance being accessed
         * @param forWrite whether the access mutates the instance
         * @param threadId the thread the access is attributed to
         */
        final void noteAccess(@Nullable Object instance, boolean forWrite, long threadId) {
            noteRunLockset(instance, forWrite);
            noteRound(instance, forWrite, threadId);
        }

        private void noteRunLockset(@Nullable Object instance, boolean forWrite) {
            if (instance == null) {
                candidateLocks.set(HeldLocks.NONE);
                return;
            }
            int[] current = candidateLocks.get();
            // Fast path: already collapsed, and it can never grow again, so nothing to compute.
            if (current != null && current.length == 0) {
                return;
            }
            while (true) {
                // The compare-and-set is unconditional even when nothing dropped out: the write
                // is then the same reference back, which costs one uncontended CAS and saves
                // having to signal "unchanged" out of band.
                int[] next = HeldLocks.intersect(current, instance, forWrite);
                if (candidateLocks.compareAndSet(current, next)) {
                    return;
                }
                current = candidateLocks.get();
                if (current != null && current.length == 0) {
                    return;
                }
            }
        }

        private void noteRound(@Nullable Object instance, boolean forWrite, long threadId) {
            // Once one round has raced, the verdict cannot change back, so nothing to compute.
            if (unguardedSharing) {
                return;
            }
            long key = windowKey(instance);
            // The caller's clock, and only for an access it makes itself: a clock says nothing
            // about the thread an access is attributed to on its behalf, so such an access carries
            // none and orders nothing. The snapshot changes only at a synchronization event.
            HappensBefore.@Nullable Stamp stamp = threadId == Thread.currentThread().threadId()
                    ? HappensBefore.current()
                    : null;
            Window current = window.get();
            while (true) {
                Window next = advance(current, key, instance, forWrite, threadId, stamp);
                if (next == current // NOPMD CompareObjectsWithEquals - unchanged window, nothing to publish
                        || window.compareAndSet(current, next)) {
                    if (next.locks.length == 0) {
                        if (!unguardedRound) {
                            unguardedRound = true;
                        }
                        if (next.shared) {
                            unguardedSharing = true;
                        }
                    }
                    return;
                }
                current = window.get();
            }
        }

        /**
         * {@return the window the calling thread's access belongs to: the round in the high half,
         * how many times the instance has been taken in the low half, so a later round or a later
         * owner always compares greater}
         */
        private long windowKey(@Nullable Object instance) {
            Scope scope = Scope.current();
            if (scope == null) {
                return 0L;
            }
            long round = (long) scope.round() << 32;
            if (instance == null) {
                return round;
            }
            Binding bound = binding;
            if (bound == null
                    || bound.scope != scope) { // NOPMD CompareObjectsWithEquals - one scope per run, by identity
                // Two threads may both get here on a first access; computeIfAbsent hands both the
                // same counter, so either binding is the right one.
                bound = new Binding(scope, scope.handOffsOf(instance));
                binding = bound;
            }
            return round | (bound.handOffs.get() & 0xFFFF_FFFFL);
        }

        @SuppressWarnings("ReferenceEquality") // intersect returns its input array when nothing dropped
        private static Window advance(@Nullable Window current, long key, @Nullable Object instance,
                                      boolean forWrite, long threadId,
                                      HappensBefore.@Nullable Stamp stamp) {
            if (current == null || key > current.key) {
                // The first access of a round: nothing recorded earlier in the run overlapped it.
                int[] locks = probe(null, instance, forWrite);
                return new Window(key, threadId, stamp, false, locks, locks, 0L, null);
            }
            // The same window, or an access still in flight from an older round or owner while a
            // newer one has started. The latter joins the newer window, the direction that can
            // only add a finding: it is an old owner still using what it handed on.
            boolean shared = current.shared;
            long owner = current.owner;
            HappensBefore.@Nullable Stamp ownerStamp = current.ownerStamp;
            boolean handedOff = false;
            if (!shared) {
                if (threadId == owner) {
                    ownerStamp = stamp;
                } else if (HappensBefore.ordered(owner, ownerStamp, threadId, stamp)) {
                    // Ordered after the owner's latest access, and so, by transitivity, after
                    // every access of this window: a hand-off, not an overlap.
                    handedOff = true;
                    owner = threadId;
                    ownerStamp = stamp;
                } else {
                    shared = true;
                }
            }
            int[] windowLocks = current.windowLocks.length == 0
                    ? current.windowLocks
                    : probe(current.windowLocks, instance, forWrite);
            long handOffOwner = current.handOffOwner;
            HappensBefore.@Nullable Stamp handOffStamp = current.handOffStamp;
            int[] locks;
            if (handedOff) {
                // Every earlier access of the window happens before this one, so none of them can
                // overlap it or anything ordered after it: the lockset starts again here (#746),
                // for as long as every later access is ordered after the previous owner's last.
                handOffOwner = current.owner;
                handOffStamp = current.ownerStamp;
                locks = probe(null, instance, forWrite);
            } else if (handOffStamp != null
                    && !HappensBefore.ordered(handOffOwner, handOffStamp, threadId, stamp)) {
                // Not ordered after the hand-off, so it may overlap the accesses before it, which
                // count again: the lockset is the whole window's from here on.
                handOffStamp = null;
                locks = windowLocks;
            } else if (handOffStamp == null) {
                locks = windowLocks;
            } else {
                locks = current.locks.length == 0
                        ? current.locks
                        : probe(current.locks, instance, forWrite);
            }
            if (shared == current.shared
                    && owner == current.owner
                    && ownerStamp == current.ownerStamp // NOPMD CompareObjectsWithEquals - a clock is replaced, never mutated
                    && locks == current.locks // NOPMD CompareObjectsWithEquals - intersect returns its input when nothing dropped
                    && windowLocks == current.windowLocks // NOPMD CompareObjectsWithEquals - as above
                    && handOffStamp == current.handOffStamp) { // NOPMD CompareObjectsWithEquals - a clock is replaced, never mutated
                return current;
            }
            return new Window(current.key, owner, ownerStamp, shared, locks, windowLocks,
                    handOffOwner, handOffStamp);
        }

        private static int[] probe(int @Nullable [] candidate, @Nullable Object instance,
                                   boolean forWrite) {
            return instance == null ? HeldLocks.NONE : HeldLocks.intersect(candidate, instance, forWrite);
        }

        /**
         * {@return whether one round saw this instance accessed by more than one thread, with no
         * lock held at every one of that round's accesses}
         *
         * <p>This is the family's verdict. Two threads in different rounds never overlapped,
         * because the runner finishes one round before it starts the next, so neither the thread
         * count nor the lockset is carried across a round boundary. The same goes for a take of
         * the instance ({@link Scope#ownershipTaken(Object)}): the owner before it and the owner
         * after it are judged apart. A thread whose access the {@link HappensBefore} model orders
         * after every earlier access of its window is not a second thread either; it took the
         * instance over, and the lockset starts again at its access for as long as every later
         * access is ordered after the hand-off. Once true it stays true.
         */
        final boolean sawUnguardedSharing() {
            return unguardedSharing;
        }

        /**
         * {@return whether one round's accesses to this instance had no lock common to all of
         * them, however many threads made them}
         *
         * <p>For a detector whose finding does not need a second thread but whose lockset must
         * not span rounds: the runner finishes one round before it starts the next, so a
         * different lock in each round is consistent locking, and two locks inside one round
         * are not. Rounds and takes split windows exactly as for
         * {@link #sawUnguardedSharing()}. With no context installed the whole run is one round,
         * and this answers what {@link #sawUnguardedAccess()} does. Once true it stays true.
         */
        final boolean sawUnguardedRound() {
            return unguardedRound;
        }

        /**
         * {@return whether no single lock was held across every recorded access}
         *
         * <p>An instance that has never been accessed reads as guarded, which is correct: with
         * no access there is no hazard to report. The name is unchanged from when this was a
         * guard-on-self boolean, because that is still exactly what it answers for callers - the
         * question just got a better model behind it.
         *
         * <p>This intersection spans the whole run and says nothing about how many threads took
         * part, or about rounds. A detector that reports sharing asks
         * {@link #sawUnguardedSharing()} instead, and one whose finding does not need a second
         * thread asks {@link #sawUnguardedRound()}.
         */
        final boolean sawUnguardedAccess() {
            int[] current = candidateLocks.get();
            return current != null && current.length == 0;
        }

        /**
         * {@return how many locks were held across every recorded access}
         *
         * <p>For reports and tests that want to say which side of the line an instance fell on.
         */
        final int commonLockCount() {
            int[] current = candidateLocks.get();
            return current == null ? 0 : current.length;
        }
    }

    /**
     * The distinct threads a detector counts for its own condition or its report, kept one round
     * at a time.
     *
     * <p>The family verdict is per round, and a thread count a detector keeps beside it has to be
     * taken in the same frame. Kept across the run, two threads that each did their part in a
     * different round, which never overlapped, satisfy "more than one thread" together, and the
     * report prints a count that grows with the number of rounds: with virtual threads every body
     * execution is a fresh thread (#748). Rounds come from the same {@link Scope} the verdict
     * reads, so with none bound the whole run is one round, as before.
     *
     * <p>Lock-free, like the rest of the record path. The first thread of a later round replaces
     * the current round by compare-and-set, which costs one {@link Round} per instance per round;
     * a thread still recording from an older round joins the newer one, the direction that can
     * only add to a count. Only the round in progress, the busiest round and the round a finding
     * came from are kept, so memory does not grow with the rounds.
     */
    static final class RoundThreads {

        /** One round's threads. Its sets stop growing once the round is over. */
        static final class Round {

            final int number;
            private final Set<Long> ids = ConcurrentHashMap.newKeySet();
            private final Set<String> names = ConcurrentHashMap.newKeySet();

            Round(int number) {
                this.number = number;
            }

            /** {@return how many distinct threads this round recorded} */
            int size() {
                return ids.size();
            }

            boolean contains(long threadId) {
                return ids.contains(threadId);
            }

            /** {@return the live set of thread names, in the order a report has always listed them} */
            Set<String> names() {
                return names;
            }
        }

        private final AtomicReference<@Nullable Round> current = new AtomicReference<>();
        private final AtomicReference<@Nullable Round> busiest = new AtomicReference<>();
        private final AtomicReference<@Nullable Round> finding = new AtomicReference<>();

        /**
         * Records {@code thread} in the calling thread's round.
         *
         * @param thread the thread the access is attributed to
         * @return the round it was recorded in
         */
        Round add(Thread thread) {
            Round round = roundFor(roundNow());
            round.ids.add(thread.threadId());
            round.names.add(thread.getName());
            Round top = busiest.get();
            while (top != round // NOPMD CompareObjectsWithEquals - one Round per round, by identity
                    && (top == null || round.size() > top.size())) {
                if (busiest.compareAndSet(top, round)) {
                    break;
                }
                top = busiest.get();
            }
            return round;
        }

        /**
         * {@return the calling thread's round, or {@code null} when no thread has been recorded in
         * it yet}
         */
        @Nullable Round inCurrentRound() {
            Round round = current.get();
            return round != null && round.number >= roundNow() ? round : null;
        }

        /**
         * {@return the calling thread's round, started without recording a thread when none has
         * been recorded in it yet}
         *
         * <p>For an event a detector judges against the threads of its round without counting its
         * own thread among them: the threads recorded later in the round join the same
         * {@link Round}, and its sets are complete once the round is over (#784).
         */
        Round current() {
            return roundFor(roundNow());
        }

        /**
         * Marks {@code round} as the one a finding came from; the first mark wins, so the report
         * names the round that first raced.
         *
         * @param round the round to report
         */
        void markFinding(Round round) {
            if (finding.get() == null) {
                finding.compareAndSet(null, round);
            }
        }

        /**
         * {@return the round a report should count: the one marked by {@link #markFinding}, else
         * the round that saw the most threads; {@code null} before any thread was recorded}
         */
        @Nullable Round reported() {
            Round marked = finding.get();
            return marked != null ? marked : busiest.get();
        }

        /** {@return how many threads {@link #reported()} saw, 0 before any was recorded} */
        int reportedSize() {
            Round round = reported();
            return round == null ? 0 : round.size();
        }

        private Round roundFor(int number) {
            Round round = current.get();
            while (round == null || round.number < number) {
                Round next = new Round(number);
                if (current.compareAndSet(round, next)) {
                    return next;
                }
                round = current.get();
            }
            return round;
        }

        /**
         * {@return the calling thread's round on the bound {@link Scope}'s clock, or 0 with none
         * bound, so a detector driven without a context sees the whole run as one round}
         */
        static int roundNow() {
            Scope scope = Scope.current();
            return scope == null ? 0 : scope.round();
        }
    }

    /**
     * A tracked instance that also remembers which threads touched it, and owns the family's core
     * rule: a finding needs more than one thread <em>and</em> an access no lock covered, both
     * within one round (see {@link TrackedInstance#sawUnguardedSharing()}). The rule
     * was hand-written per detector in three spellings, and the lock-awareness rollout had to
     * visit every copy (#700).
     *
     * <p>The threads are kept per round ({@link RoundThreads}), and a report counts and names the
     * threads of the round the finding came from, not every thread of the run (#748).
     */
    abstract static class ThreadTrackedInstance extends TrackedInstance {

        private final RoundThreads threads = new RoundThreads();

        /**
         * Records one access to {@code instance} by {@code thread}: the lock probe and the round
         * verdict, as {@link #noteAccess(Object, boolean, long)} does, and the thread for the
         * report. Call it on the accessing thread; the probe asks the caller.
         *
         * @param instance the shared instance being accessed
         * @param thread   the thread the access is attributed to
         */
        final void noteAccess(@Nullable Object instance, Thread thread) {
            noteAccess(instance, true, thread);
        }

        /**
         * {@link #noteAccess(Object, Thread)}, saying whether the access is a write, for the
         * detectors whose reads a shared-mode lock can guard.
         *
         * @param instance the shared instance being accessed
         * @param forWrite whether the access mutates the instance
         * @param thread   the thread the access is attributed to
         */
        final void noteAccess(@Nullable Object instance, boolean forWrite, Thread thread) {
            noteAccess(instance, forWrite, thread.threadId());
            RoundThreads.Round round = threads.add(thread);
            // The access that latches the verdict is recorded in the round that raced, so the
            // first mark names it; later rounds cannot move it.
            if (sawUnguardedSharing()) {
                threads.markFinding(round);
            }
        }

        /**
         * {@return how many threads the round that produced the finding saw, or, with no finding,
         * the busiest round}
         */
        final int threadCount() {
            return threads.reportedSize();
        }

        /**
         * {@return the live set of names of the threads {@link #threadCount()} counts, in the order
         * a report has always listed them}
         */
        final Set<String> threadNames() {
            RoundThreads.Round round = threads.reported();
            return round == null ? Set.of() : round.names();
        }

        /**
         * {@return whether this instance is a finding: shared within one round, and not
         * serialised by any lock there}
         */
        final boolean sharedAndUnguarded() {
            return sawUnguardedSharing();
        }
    }
}
