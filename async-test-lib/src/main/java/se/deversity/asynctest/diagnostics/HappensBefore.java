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
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

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
 * shapes. This class is the one model they consult instead. {@code SelfGuard}'s per-round
 * sharing verdict, which the detectors watching one non-thread-safe instance share, consults it
 * too: a thread whose access it orders after the previous thread's took the instance over.
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
 * itself names, and why each approximation is in the direction of fewer edges, with the
 * exceptions listed under Limits.
 *
 * <h2>Where edges come from</h2>
 *
 * <ul>
 *   <li>The four methods here, for tests that record by hand: {@link #release}, {@link #acquire},
 *       {@link #fork} and {@link #join}.
 *   <li>With the agent attached, from the calls it already substitutes: {@code Thread.start} and
 *       {@code Thread.join}; an element offered to, and taken from, a concurrent queue, map or
 *       {@code AtomicReference}, where an offer the container refused or a failed
 *       {@code compareAndSet} is withdrawn with {@link #retract} and publishes nothing;
 *       {@code CountDownLatch.countDown} and a successful {@code await};
 *       {@code Semaphore.release} and a successful acquire; and, with {@code fields=true}, a
 *       volatile write of one field of an object and a later access to the same object that the
 *       weaver marks as following a volatile read, when the accessing thread's recorded volatile
 *       reads of that object include the same field. A volatile clock is kept per object and
 *       field, the field compared by its simple name (#742).
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
 *   <li>The weaver reports a volatile read before the read instruction and not the value it
 *       returned, so the acquire is taken at the next access it marks, and it merges the field's
 *       clock as it is then: an access after a read that returned an older value is ordered too,
 *       as is one after a write another thread made between the read and that access. Each
 *       thread remembers its last {@value #VOLATILE_READS} volatile fields read, so a read that
 *       old is forgotten and orders nothing, and one of the remembered reads still orders an
 *       access in a later method that the weaver marks for a read of another field of the object.
 *       Two fields of one object sharing a simple name, a field and the one it shadows, share a
 *       clock.
 *   <li>A withdrawn release was visible for the length of the refused call, and one another
 *       thread folded into its own release in that time stays. {@code addAll} into a queue,
 *       which can accept some elements and refuse the rest, withdraws nothing.
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

    /** How many volatile fields a thread remembers having read; see the class javadoc. */
    static final int VOLATILE_READS = 8;

    /** Each thread's clock, created at its first synchronization event or stamp. */
    private static final ThreadLocal<ThreadClock> CLOCKS = ThreadLocal.withInitial(HappensBefore::start);

    /** Every thread's clock, so {@link #join} can read a finished thread's final one. */
    private static final Map<Thread, ThreadClock> THREADS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Clocks forked to threads that have not taken them up yet. */
    private static final Map<Thread, Stamp> FORKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Clocks of synchronization objects, by identity and weakly held. */
    private static final Map<IdentityKey.Weak, SyncClock> SYNC = new ConcurrentHashMap<>();

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
        releaseInto(CLOCKS.get(), syncClock(sync));
    }

    /** Merges {@code me} into {@code clock}, remembering the release for {@link #retract}. */
    private static void releaseInto(ThreadClock me, Clock clock) {
        Stamp mine = me.current;
        for (;;) {
            Stamp seen = clock.get();
            Stamp merged = seen == null ? mine : seen.join(mine, NO_OWNER);
            if (merged == seen) { // NOPMD CompareObjectsWithEquals - join returns its receiver when nothing changed
                me.forgetRelease();
                break;
            }
            if (clock.compareAndSet(seen, merged)) {
                me.lastRelease = clock;
                me.beforeLastRelease = seen;
                me.lastReleased = merged;
                break;
            }
        }
        // The next access this thread stamps must not look published by this release.
        me.published = true;
    }

    /**
     * The release half of a volatile field: everything the calling thread did so far is published
     * to whoever later acquires the same field of the same object (#742).
     *
     * <p>Per field, where {@link #release} is per object: a read of one volatile field does not
     * receive what a write of another published. The field is compared by its simple name, the
     * part after the last dot, so {@code com.example.Holder.ready} and {@code ready} name one
     * field; the agent qualifies a field with the class the instruction named, which for an
     * inherited field depends on the static type at the call site.
     *
     * @param owner the object the field belongs to, the declaring class for a static field;
     *              {@code null} is ignored
     * @param field the field's name, qualified or simple
     */
    @API(status = Status.INTERNAL)
    public static void releaseVolatile(@Nullable Object owner, String field) {
        if (owner == null) {
            return;
        }
        releaseInto(CLOCKS.get(), syncClock(owner).field(field));
    }

    /**
     * The acquire half of a volatile field, for a caller that records a volatile read after it
     * was made: the calling thread receives what earlier {@link #releaseVolatile} calls on the same
     * field of the same object published, and nothing another field's write published.
     *
     * @param owner the object the field belongs to; {@code null} or one never released is ignored
     * @param field the field's name, qualified or simple
     */
    @API(status = Status.INTERNAL)
    public static void acquireVolatile(@Nullable Object owner, String field) {
        if (owner == null) {
            return;
        }
        SyncClock clock = SYNC.get(new IdentityKey.Weak(owner, null));
        FieldClock fieldClock = clock == null ? null : clock.find(field);
        if (fieldClock != null) {
            acquireFrom(CLOCKS.get(), fieldClock);
        }
    }

    /**
     * Notes that the calling thread is about to read a volatile field, for a caller that learns of
     * the read before it is made, as the agent's hook does.
     *
     * <p>Nothing is acquired here: the value has not been seen yet. The field is remembered, and
     * {@link #acquireVolatileReads} takes the acquire at the access the weaver marks as following
     * the read. The field's clock is created now, even when nobody has written the field yet, so
     * that a write landing between this call and the read is still found. A field the thread
     * already remembers costs a scan of {@value #VOLATILE_READS} entries and allocates nothing.
     *
     * @param owner the object the field belongs to, the declaring class for a static field;
     *              {@code null} is ignored
     * @param field the field's name, qualified or simple
     */
    @API(status = Status.INTERNAL)
    public static void volatileRead(@Nullable Object owner, String field) {
        if (owner == null) {
            return;
        }
        ThreadClock me = CLOCKS.get();
        FieldClock[] reads = me.volatileReads;
        if (reads == null) {
            reads = new FieldClock[VOLATILE_READS];
            me.volatileReads = reads;
        }
        for (FieldClock read : reads) {
            if (read != null && read.is(owner, field)) {
                return;
            }
        }
        reads[me.nextVolatileRead] = syncClock(owner).field(field);
        me.nextVolatileRead = (me.nextVolatileRead + 1) % VOLATILE_READS;
    }

    /**
     * Orders the calling thread after the writes of every volatile field of {@code owner} it has
     * read, as far as {@link #volatileRead} remembers them.
     *
     * <p>For the access the weaver marks as following a volatile read of the same object. Fields
     * of {@code owner} the thread has not read acquire nothing, which is the difference from an
     * acquire of the whole object (#742).
     *
     * @param owner the object the marked access is on; {@code null} is ignored
     */
    @API(status = Status.INTERNAL)
    public static void acquireVolatileReads(@Nullable Object owner) {
        if (owner == null) {
            return;
        }
        ThreadClock me = CLOCKS.get();
        FieldClock[] reads = me.volatileReads;
        if (reads == null) {
            return;
        }
        for (FieldClock read : reads) {
            if (read != null && read.belongsTo(owner)) {
                acquireFrom(me, read);
            }
        }
    }

    /** Merges {@code clock} into {@code me}, when anything has been released to it. */
    private static void acquireFrom(ThreadClock me, Clock clock) {
        Stamp theirs = clock.get();
        if (theirs != null) {
            me.current = me.current.join(theirs, me.thread);
        }
    }

    /**
     * Withdraws the calling thread's last {@link #release} of {@code sync}, for a hand-off that
     * turned out not to happen.
     *
     * <p>The hooks release before the operation, because the release must precede every take that
     * can return the element, and learn only afterwards whether the queue refused the offer or the
     * {@code compareAndSet} failed. A refused offer publishes nothing, so leaving its release in
     * place would order whoever later acquires the element after this thread by a hand-off that
     * never took place, and hide a race (#742).
     *
     * <p>The withdrawal is exact or it does nothing: it restores the clock {@code sync} had before
     * the release only while the clock still holds what the release installed, and only when this
     * thread has neither released again nor stamped an access since. A release another thread
     * made in between has folded this one in, and it stays, which is the old answer. So does an
     * acquire another thread made in the window between the release and the withdrawal, of an
     * element it received some other way; the window is the length of one refused call.
     *
     * @param sync the object the refused hand-off went through; {@code null} is ignored
     */
    @API(status = Status.INTERNAL)
    @SuppressWarnings("ReferenceEquality") // the same clock object, not an equal one
    public static void retract(@Nullable Object sync) {
        if (sync == null) {
            return;
        }
        ThreadClock me = CLOCKS.get();
        Clock clock = me.lastRelease;
        Stamp before = me.beforeLastRelease;
        Stamp released = me.lastReleased;
        me.forgetRelease();
        // Only an object's own clock matches here: a volatile field's release is never withdrawn.
        if (clock != null && clock == SYNC.get(new IdentityKey.Weak(sync, null))) { // NOPMD CompareObjectsWithEquals - the same clock, not an equal one
            clock.compareAndSet(released, before);
        }
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
        SyncClock clock = SYNC.get(new IdentityKey.Weak(sync, null));
        if (clock != null) {
            acquireFrom(CLOCKS.get(), clock);
        }
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
            // An access after the release: the release can no longer be withdrawn.
            me.forgetRelease();
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
    private static SyncClock syncClock(Object sync) {
        for (Reference<?> gone = COLLECTED.poll(); gone != null; gone = COLLECTED.poll()) {
            SYNC.remove(gone);
        }
        SyncClock clock = SYNC.get(new IdentityKey.Weak(sync, null));
        if (clock != null) {
            return clock;
        }
        return SYNC.computeIfAbsent(new IdentityKey.Weak(sync, COLLECTED), SyncClock::new);
    }

    /**
     * {@return whether two field names name the same field of one object}: equal simple names,
     * the part after the last dot. Allocation-free.
     */
    static boolean sameField(String one, String other) {
        if (one.equals(other)) {
            return true;
        }
        int from = one.lastIndexOf('.') + 1;
        int otherFrom = other.lastIndexOf('.') + 1;
        int length = one.length() - from;
        return length == other.length() - otherFrom
                && one.regionMatches(from, other, otherFrom, length);
    }

    /** A vector clock that releases merge into and acquires read, replaced on every change. */
    private abstract static class Clock {

        private static final AtomicReferenceFieldUpdater<Clock, Stamp> STAMP =
                AtomicReferenceFieldUpdater.newUpdater(Clock.class, Stamp.class, "stamp");

        /** What has been released to this clock, {@code null} before the first release. */
        private volatile @Nullable Stamp stamp;

        final @Nullable Stamp get() {
            return stamp;
        }

        final boolean compareAndSet(@Nullable Stamp expected, @Nullable Stamp update) {
            return STAMP.compareAndSet(this, expected, update);
        }
    }

    /**
     * A synchronization object's clock, which {@link #release} and {@link #acquire} use, and the
     * separate clocks of its volatile fields, which {@link #releaseVolatile} and the volatile
     * acquires use (#742).
     */
    private static final class SyncClock extends Clock {

        private static final AtomicReferenceFieldUpdater<SyncClock, FieldClock> FIELDS =
                AtomicReferenceFieldUpdater.newUpdater(SyncClock.class, FieldClock.class, "fields");

        /** The key this clock is stored under, which knows the object while it lives. */
        final IdentityKey.Weak key;

        /** The object's volatile fields used so far, newest first; only ever prepended to. */
        private volatile @Nullable FieldClock fields;

        SyncClock(IdentityKey.Weak key) {
            this.key = key;
        }

        /** {@return the clock of {@code field}, or {@code null} while nobody has used it} */
        @Nullable FieldClock find(String field) {
            return find(fields, field);
        }

        private static @Nullable FieldClock find(@Nullable FieldClock from, String field) {
            for (FieldClock clock = from; clock != null; clock = clock.next) {
                if (sameField(clock.field, field)) {
                    return clock;
                }
            }
            return null;
        }

        /**
         * {@return the clock of {@code field}, created on first use}
         *
         * <p>Lock-free: the recording threads are the racing threads, and a monitor here would
         * serialize them.
         */
        FieldClock field(String field) {
            for (;;) {
                FieldClock seen = fields;
                FieldClock found = find(seen, field);
                if (found != null) {
                    return found;
                }
                FieldClock created = new FieldClock(this, field, seen);
                if (FIELDS.compareAndSet(this, seen, created)) {
                    return created;
                }
            }
        }
    }

    /** The clock of one volatile field of one object. */
    private static final class FieldClock extends Clock {

        final SyncClock owner;

        final String field;

        /** The field its object had seen before this one, or {@code null}. */
        final @Nullable FieldClock next;

        FieldClock(SyncClock owner, String field, @Nullable FieldClock next) {
            this.owner = owner;
            this.field = field;
            this.next = next;
        }

        /** {@return whether this is the clock of {@code object}'s field {@code name}} */
        boolean is(Object object, String name) {
            return belongsTo(object) && sameField(field, name);
        }

        /** {@return whether this clock is of a field of {@code object}, compared by identity} */
        @SuppressWarnings("ReferenceEquality") // identity is the point
        boolean belongsTo(Object object) {
            return owner.key.get() == object; // NOPMD CompareObjectsWithEquals - identity is the point
        }
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

        /**
         * The synchronization clock this thread's last {@link #release} changed, while
         * {@link #retract} can still withdraw it, with the value it replaced and the value it
         * installed. Read and written by the owning thread only.
         */
        @Nullable Clock lastRelease;

        @Nullable Stamp beforeLastRelease;

        @Nullable Stamp lastReleased;

        /**
         * The volatile fields this thread last read, oldest overwritten first, created at its first
         * volatile read. Read and written by the owning thread only.
         */
        FieldClock @Nullable [] volatileReads;

        /** Where the next volatile read goes in {@link #volatileReads}. */
        int nextVolatileRead;

        ThreadClock(long thread, Stamp current) {
            this.thread = thread;
            this.current = current;
        }

        void forgetRelease() {
            lastRelease = null;
            beforeLastRelease = null;
            lastReleased = null;
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
                    count = counts[left];
                    left++;
                } else if (left >= threads.length || other.threads[right] < threads[left]) {
                    next = other.threads[right];
                    count = other.counts[right];
                    right++;
                } else {
                    next = threads[left];
                    count = Math.max(counts[left], other.counts[right]);
                    left++;
                    right++;
                }
                mergedThreads[size] = next;
                mergedCounts[size] = count;
                size++;
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
                resultCounts[out] = mergedCounts[keepAt];
                out++;
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
