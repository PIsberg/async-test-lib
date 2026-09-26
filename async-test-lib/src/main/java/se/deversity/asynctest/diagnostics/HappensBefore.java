package se.deversity.asynctest.diagnostics;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

/**
 * The happens-before model the field-level detectors share: which recorded accesses are ordered
 * by synchronization, so that the two of them cannot race.
 *
 * <p><strong>Why this exists.</strong> {@code RaceConditionDetector}, {@code AtomicityValidator}
 * and {@code VisibilityMonitor} judge a pair of accesses from two threads by the locks held at
 * each, partitioned by the harness's own round boundaries. A lockset cannot see an ordering that
 * no lock provides, so a correct program that hands an object through a {@code BlockingQueue},
 * publishes it with a volatile flag or through a {@code ConcurrentHashMap}, or writes it before
 * {@code Thread.start} read as racing. Each detector used to grow its own excuse for one of these
 * shapes. This class is the one model they consult instead.
 *
 * <h2>The model</h2>
 *
 * <p>A vector clock per thread and per synchronization object, in the FastTrack style. A
 * <em>release</em> of an object merges the releasing thread's clock into the object's clock; an
 * <em>acquire</em> merges the object's clock into the acquiring thread's. A thread's own entry
 * advances before the first access it stamps after a release, so accesses before a release are
 * visible to whoever acquires it and accesses after it are not. {@link #fork} is a release to a
 * thread that has not started yet and {@link #join} an acquire of a thread that has finished.
 *
 * <p>An access is stamped with {@link #current()}, an immutable snapshot that changes only at a
 * synchronization event, so stamping is a thread-local read and allocates nothing. Access {@code a}
 * on thread {@code t} happens before access {@code b} when {@code b}'s stamp knows at least
 * {@code a}'s own entry for {@code t}.
 *
 * <h2>An edge only ever removes a finding</h2>
 *
 * <p>The detectors consult this model to excuse a pair, never to report one. A missing edge, from
 * an unwoven call, a dropped event or an access recorded without a stamp, leaves the detector with
 * the answer it had before the model existed. An edge this class invents where the program has
 * none would hide a real race, which is why every source below is one the Java memory model
 * itself names, and why each approximation is in the direction of fewer edges, with the two
 * exceptions listed under Limits.
 *
 * <h2>Where edges come from</h2>
 *
 * <ul>
 *   <li>The four methods here, for tests that record by hand: {@link #release}, {@link #acquire},
 *       {@link #fork} and {@link #join}.
 *   <li>With the agent attached, from the calls it already substitutes: {@code Thread.start} and
 *       {@code Thread.join}; an element offered to, and taken from, a concurrent queue or map;
 *       {@code CountDownLatch.countDown} and a successful {@code await};
 *       {@code Semaphore.release} and a successful acquire; and, with {@code fields=true}, a
 *       volatile write and a later access the weaver marks as following a volatile read of the
 *       same object.
 * </ul>
 *
 * <p>Plain lock and monitor hand-offs are deliberately not edges. The detectors judge lock
 * discipline with a lockset, which asks whether the accesses <em>could</em> race under another
 * schedule; ordering them by the one schedule the run happened to take would trade that
 * prediction for a coincidence.
 *
 * <h2>Limits</h2>
 *
 * <ul>
 *   <li>A volatile edge is per object, not per field: a write to one volatile field of an object
 *       orders later accesses that follow a read of another. The weaver only marks an access as
 *       following a volatile read, not which value the read returned, so an access after a read
 *       that returned an older value is ordered too.
 *   <li>A clock keeps at most 256 threads. Past that the entries of the lowest
 *       thread ids go, which loses edges and never adds one; a thread never loses its own.
 *   <li>Not yet observed: {@code CompletableFuture} completion, {@code Executor} submission and
 *       {@code Future.get}, {@code Exchanger}, {@code AtomicReference.get}, and a validated
 *       {@code StampedLock} optimistic read. Code relying on those needs the manual methods.
 * </ul>
 *
 * <p>The state is process-wide, because ordering is a property of the execution rather than of one
 * test, and it is kept weakly: a synchronization object, a thread and a pending fork each drop out
 * once nothing else references them.
 *
 * @since 1.12.3
 */
@API(status = Status.EXPERIMENTAL)
public final class HappensBefore {

    /** The most threads one clock remembers; see the class javadoc. */
    static final int MAX_ENTRIES = 256;

    /** Passed as the entry to keep when merging into a clock that belongs to no thread. */
    private static final long NO_OWNER = Long.MIN_VALUE;

    /** Each thread's clock, created at its first synchronization event or stamp. */
    private static final ThreadLocal<ThreadClock> CLOCKS = ThreadLocal.withInitial(HappensBefore::start);

    /** Every thread's clock, so {@link #join} can read a finished thread's final one. */
    private static final Map<Thread, ThreadClock> THREADS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Clocks forked to threads that have not taken them up yet. */
    private static final Map<Thread, Stamp> FORKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Clocks of synchronization objects, by identity and weakly held. */
    private static final Map<IdentityKey.Weak, AtomicReference<Stamp>> SYNC =
            new ConcurrentHashMap<>();

    /** Where {@link #SYNC} keys arrive once their object is collected. */
    private static final ReferenceQueue<Object> COLLECTED = new ReferenceQueue<>();

    /** The harness round token; see {@link #round()}. */
    private static final java.util.concurrent.atomic.AtomicLong ROUNDS =
            new java.util.concurrent.atomic.AtomicLong();

    private HappensBefore() {
    }

    /**
     * Declares that everything the calling thread did so far is published through {@code sync}.
     *
     * <p>Call it where the program makes a release: after writing a volatile field, before
     * offering to a concurrent queue, before {@code countDown}, before handing an object over by
     * any means the Java memory model orders. Whoever later calls {@link #acquire} on the same
     * object is ordered after every access this thread recorded before this call.
     *
     * @param sync the object the hand-off goes through, compared by identity; {@code null} is
     *             ignored
     */
    public static void release(@Nullable Object sync) {
        if (sync == null) {
            return;
        }
        ThreadClock me = CLOCKS.get();
        Stamp mine = me.current;
        AtomicReference<Stamp> clock = syncClock(sync);
        for (;;) {
            Stamp seen = clock.get();
            Stamp merged = seen == null ? mine : seen.join(mine, NO_OWNER);
            if (merged == seen || clock.compareAndSet(seen, merged)) { // NOPMD CompareObjectsWithEquals - join returns its receiver when nothing changed
                break;
            }
        }
        // The next access this thread stamps must not look published by this release.
        me.published = true;
    }

    /**
     * Declares that the calling thread has received what earlier {@link #release} calls on
     * {@code sync} published.
     *
     * <p>Call it where the program makes the matching acquire: after reading the volatile field
     * and seeing the value the writer stored, after a successful take, after {@code await}
     * returned. Calling it earlier, before the value was actually observed, can order accesses
     * the program does not order, and would hide a race.
     *
     * @param sync the object the hand-off goes through; {@code null} or an object nobody released
     *             is ignored
     */
    public static void acquire(@Nullable Object sync) {
        if (sync == null) {
            return;
        }
        AtomicReference<Stamp> clock = SYNC.get(new IdentityKey.Weak(sync, null));
        Stamp theirs = clock == null ? null : clock.get();
        if (theirs == null) {
            return;
        }
        ThreadClock me = CLOCKS.get();
        me.current = me.current.join(theirs, me.thread);
    }

    /**
     * Declares that {@code child} will start after everything the calling thread did so far, as
     * {@link Thread#start()} guarantees.
     *
     * <p>Call it before {@code child.start()}. Once the child runs, its accesses are ordered after
     * the parent's accesses recorded before this call, and not after the parent's later ones.
     *
     * @param child the thread about to be started; {@code null} is ignored
     */
    public static void fork(@Nullable Thread child) {
        if (child == null) {
            return;
        }
        ThreadClock me = CLOCKS.get();
        FORKS.merge(child, me.current, (earlier, later) -> earlier.join(later, NO_OWNER));
        me.published = true;
    }

    /**
     * Declares that {@code child} has finished and the calling thread has joined it, as a
     * returned {@link Thread#join()} guarantees.
     *
     * <p>Call it after {@code child.join()} returns. A thread that is still alive is ignored: its
     * later accesses would otherwise read as ordered before the caller's, which they are not.
     *
     * @param child the thread that was joined; {@code null} is ignored
     */
    public static void join(@Nullable Thread child) {
        if (child == null || child.isAlive()) {
            return;
        }
        ThreadClock theirs = THREADS.get(child);
        if (theirs == null) {
            return;
        }
        ThreadClock me = CLOCKS.get();
        me.current = me.current.join(theirs.current, me.thread);
    }

    /**
     * {@return the calling thread's clock, to stamp an access with}
     *
     * <p>For the library's record paths, not for tests: the snapshot is immutable and only changes
     * at a synchronization event, so stamping allocates nothing except on the first access after a
     * release.
     */
    @API(status = Status.INTERNAL)
    public static Stamp current() {
        ThreadClock me = CLOCKS.get();
        if (me.published) {
            me.current = me.current.tick(me.thread);
            me.published = false;
        }
        return me.current;
    }

    /**
     * {@return the current harness round token, for an event to carry from where it was published}
     *
     * <p>The harness orders its rounds: every access of one round happens before every access of
     * the next. A detector marks each round start with {@link #nextRound()}, and an access stamped
     * with the token current when it happened belongs to the round that token started, however
     * late a consumer on another thread gets to it. Tokens are process-wide and only grow; each
     * detector maps the ones it started to its own round numbers. A worker reads the token after
     * the runner's submission that started its round and before the latch that ends it, so it
     * always reads its own round's token or one another run started since.
     */
    @API(status = Status.INTERNAL)
    public static long round() {
        return ROUNDS.get();
    }

    /** {@return a new round token, strictly greater than every earlier one} */
    static long nextRound() {
        return ROUNDS.incrementAndGet();
    }

    /**
     * {@return whether handing an element through {@code container} orders the hand-off}
     *
     * <p>True for the containers whose contract says so: the {@code java.util.concurrent}
     * collections and atomics, including any implementation of {@code BlockingQueue} or
     * {@code ConcurrentMap}, whose interfaces promise the memory consistency effect, and the
     * synchronized wrappers and legacy synchronized collections. An {@code ArrayDeque} or a
     * {@code HashMap} promises nothing, and an element passed through one is not ordered.
     *
     * @param container the queue, map or slot the element went through
     */
    @API(status = Status.INTERNAL)
    public static boolean publishesElements(@Nullable Object container) {
        if (container == null) {
            return false;
        }
        if (container instanceof java.util.concurrent.BlockingQueue
                || container instanceof java.util.concurrent.ConcurrentMap
                || container instanceof java.util.Hashtable
                || container instanceof java.util.Vector) {
            return true;
        }
        String type = container.getClass().getName();
        return type.startsWith("java.util.concurrent.")
                || type.startsWith("java.util.Collections$Synchronized");
    }

    /**
     * {@return whether the access stamped {@code earlier} on {@code earlierThread} happens before
     * the one stamped {@code later} on {@code laterThread}}
     *
     * <p>Two accesses on one thread are ordered by program order. Across threads the answer needs
     * both stamps, and a stamp that does not know its own thread, which is what an access recorded
     * on another thread's behalf carries, orders nothing.
     */
    static boolean ordered(long earlierThread, @Nullable Stamp earlier,
                           long laterThread, @Nullable Stamp later) {
        if (earlierThread == laterThread) {
            return true;
        }
        if (earlier == null || later == null) {
            return false;
        }
        int own = earlier.countOf(earlierThread);
        return own > 0 && later.countOf(earlierThread) >= own;
    }

    /** An access as the model sees it: who made it, whether it wrote, and its stamp. */
    interface Access {

        /** {@return the thread that made the access} */
        long orderThread();

        /** {@return whether the access was a write} */
        boolean orderWrite();

        /** {@return the stamp taken on the accessing thread, {@code null} when none was} */
        @Nullable Stamp orderStamp();
    }

    /**
     * {@return whether every conflicting pair of accesses in {@code trace} is ordered}
     *
     * <p>{@code trace} must be in an order consistent with happens-before, which the record paths
     * give: an access is appended before its thread's next release and after its thread's last
     * acquire. Two accesses conflict when they come from different threads and at least one
     * writes; with {@code readsConflict} false only two writes do, which is the rule for a volatile
     * field, whose reads are synchronization rather than data races.
     *
     * <p>The FastTrack check: each access against the last write and each write against the latest
     * read per thread since that write. Transitivity of the clocks makes that enough to find an
     * unordered pair whenever one exists, in time linear in the trace.
     *
     * @param trace         one field's accesses, in record order
     * @param readsConflict whether a read and a write conflict
     */
    static boolean everyConflictOrdered(List<? extends Access> trace, boolean readsConflict) {
        Access lastWrite = null;
        Map<Long, Access> readsSinceWrite = new HashMap<>();
        for (Access access : trace) {
            boolean write = access.orderWrite();
            if ((write || readsConflict) && lastWrite != null && !ordered(lastWrite, access)) {
                return false;
            }
            if (write) {
                for (Access read : readsSinceWrite.values()) {
                    if (!ordered(read, access)) {
                        return false;
                    }
                }
                lastWrite = access;
                readsSinceWrite.clear();
            } else if (readsConflict) {
                readsSinceWrite.put(access.orderThread(), access);
            }
        }
        return true;
    }

    private static boolean ordered(Access earlier, Access later) {
        return ordered(earlier.orderThread(), earlier.orderStamp(),
                later.orderThread(), later.orderStamp());
    }

    /** {@return the clock of {@code sync}, created empty on first use} */
    private static AtomicReference<Stamp> syncClock(Object sync) {
        for (Reference<?> gone = COLLECTED.poll(); gone != null; gone = COLLECTED.poll()) {
            SYNC.remove(gone);
        }
        AtomicReference<Stamp> clock = SYNC.get(new IdentityKey.Weak(sync, null));
        if (clock != null) {
            return clock;
        }
        return SYNC.computeIfAbsent(new IdentityKey.Weak(sync, COLLECTED),
                ignored -> new AtomicReference<>());
    }

    /** Creates the calling thread's clock, taking up a clock forked to it if there is one. */
    private static ThreadClock start() {
        Thread me = Thread.currentThread();
        long id = me.threadId();
        Stamp stamp = Stamp.of(id);
        Stamp forked = FORKS.remove(me);
        if (forked != null) {
            stamp = stamp.join(forked, id);
        }
        ThreadClock clock = new ThreadClock(id, stamp);
        THREADS.put(me, clock);
        return clock;
    }

    /** One thread's clock. Only its own thread writes it; {@link #join} reads it from another. */
    private static final class ThreadClock {

        final long thread;

        /** The stamp accesses get; replaced, never mutated. */
        volatile Stamp current;

        /**
         * Whether {@link #current} has been handed to a synchronization object or a child since the
         * last tick. Read and written by the owning thread only.
         */
        boolean published;

        ThreadClock(long thread, Stamp current) {
            this.thread = thread;
            this.current = current;
        }
    }

    /**
     * An immutable vector clock: for each thread, how far into its history the holder is ordered.
     *
     * <p>Public only so the telemetry path can carry one from the accessing thread to the drain;
     * nothing outside the library constructs or reads one.
     */
    @API(status = Status.INTERNAL)
    public static final class Stamp {

        /** Thread ids, ascending. Never mutated once the stamp exists. */
        private final long[] threads;

        /** The count known for the thread at the same index. */
        private final int[] counts;

        private Stamp(long[] threads, int[] counts) {
            this.threads = threads;
            this.counts = counts;
        }

        /** {@return the clock a thread starts with: its own first entry and nothing else} */
        static Stamp of(long thread) {
            return new Stamp(new long[] {thread}, new int[] {1});
        }

        /** {@return how much of {@code thread}'s history this stamp is ordered after, 0 for none} */
        int countOf(long thread) {
            int at = Arrays.binarySearch(threads, thread);
            return at < 0 ? 0 : counts[at];
        }

        /** {@return this stamp with {@code thread}'s own entry advanced by one} */
        Stamp tick(long thread) {
            int at = Arrays.binarySearch(threads, thread);
            if (at < 0) {
                return join(of(thread), thread);
            }
            int[] next = counts.clone();
            next[at]++;
            return new Stamp(threads, next);
        }

        /** {@return whether this stamp already knows everything {@code other} knows} */
        boolean covers(Stamp other) {
            for (int i = 0; i < other.threads.length; i++) {
                if (countOf(other.threads[i]) < other.counts[i]) {
                    return false;
                }
            }
            return true;
        }

        /**
         * {@return the pointwise maximum of this stamp and {@code other}}
         *
         * <p>Returns this stamp itself when it already covers {@code other}, which is what keeps a
         * repeated acquire of the same clock allocation-free. Past {@link #MAX_ENTRIES} threads
         * the lowest ids are dropped, except {@code keep}.
         *
         * @param other the clock to merge in
         * @param keep  the thread whose entry must survive the cap, {@code Long.MIN_VALUE} for none
         */
        Stamp join(Stamp other, long keep) {
            if (other == this || covers(other)) { // NOPMD CompareObjectsWithEquals - identity shortcut
                return this;
            }
            if (other.covers(this)) {
                return other;
            }
            long[] mergedThreads = new long[threads.length + other.threads.length];
            int[] mergedCounts = new int[mergedThreads.length];
            int size = 0;
            int left = 0;
            int right = 0;
            while (left < threads.length || right < other.threads.length) {
                long next;
                int count;
                if (right >= other.threads.length
                        || left < threads.length && threads[left] < other.threads[right]) {
                    next = threads[left];
                    count = counts[left++];
                } else if (left >= threads.length || other.threads[right] < threads[left]) {
                    next = other.threads[right];
                    count = other.counts[right++];
                } else {
                    next = threads[left];
                    count = Math.max(counts[left++], other.counts[right++]);
                }
                mergedThreads[size] = next;
                mergedCounts[size++] = count;
            }
            int from = Math.max(0, size - MAX_ENTRIES);
            int keepAt = -1;
            if (from > 0 && keep != NO_OWNER) {
                int at = Arrays.binarySearch(mergedThreads, 0, size, keep);
                if (at >= 0 && at < from) {
                    keepAt = at;
                    from++;
                }
            }
            int length = size - from + (keepAt >= 0 ? 1 : 0);
            long[] resultThreads = new long[length];
            int[] resultCounts = new int[length];
            int out = 0;
            if (keepAt >= 0) {
                resultThreads[out] = mergedThreads[keepAt];
                resultCounts[out++] = mergedCounts[keepAt];
            }
            System.arraycopy(mergedThreads, from, resultThreads, out, size - from);
            System.arraycopy(mergedCounts, from, resultCounts, out, size - from);
            return new Stamp(resultThreads, resultCounts);
        }

        /** {@return the number of threads this stamp knows about} */
        int size() {
            return threads.length;
        }

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < threads.length; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(threads[i]).append(':').append(counts[i]);
            }
            return text.append(']').toString();
        }
    }
}
