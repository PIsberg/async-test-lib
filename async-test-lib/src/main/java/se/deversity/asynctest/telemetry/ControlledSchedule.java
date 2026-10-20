package se.deversity.asynctest.telemetry;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * One round's controlled schedule: the round's workers run one at a time, and the one that runs
 * next is chosen only at a scheduling point, from the round's replay seed alone. Same seed, same
 * points, same order; that is what makes a seed replay an interleaving.
 *
 * <p>The choice is PCT's (Burckhardt, Kothari, Musuvathi, Nagarakatte, ASPLOS 2010). Each worker
 * gets a distinct random priority, and the highest-priority worker that is ready runs. At
 * {@link #DEPTH} - 1 random steps the worker running drops below every initial priority, which is
 * what lets a lower one in between two of its accesses. PCT draws those steps from the total
 * number of steps, which a round only knows once it is over, so the bound is drawn from the seed
 * too, between 4 and 4,096 on a log scale: rounds with a few points and rounds with thousands
 * both get change points that land.
 *
 * <p>The schedule knows which worker holds which exclusive lock (#1022). Entering a monitor or taking
 * a {@code ReentrantLock} is a point that names the lock ({@link #acquiring}); a worker granted the
 * round with such a step pending is recorded as its holder, {@link #released} drops the hold, and
 * {@link #acquired} records one taken without waiting ({@code tryLock}, a {@code synchronized}
 * method's monitor). {@link #grant()} passes over a ready worker whose pending lock another worker
 * holds, so a worker paused inside a critical section is not handed a peer that would only block on
 * it. When every ready worker waits on a lock another holds, that is a real deadlock, and the round
 * is handed out anyway so it happens and is reported.
 *
 * <p>Threads the body starts join the round too (#1024). A woven {@code Thread.start} adopts the
 * child into the starting worker's round under the next slot ({@link #starting}), which the
 * schedule assigns in the order the starts happen, so the seed decides the child's priority as it
 * does a worker's. The child joins at its first scheduling point: it touches nothing woven before
 * then, since every woven access is a point. An untimed {@code join} on a child is a point that
 * waits for it ({@link #joining}), not a block outside the schedule, and a child whose thread has
 * ended is done rather than escaped.
 *
 * <p>A worker waiting at {@code AsyncTestContext.rendezvous()} steps outside the round instead of
 * blocking in it (#1021): {@link #stepOutside} hands the turn on without counting an escape, and
 * {@link #rejoin} brings the worker back as ready once the rendezvous releases it. No grant is made
 * while a worker is outside on its way back, or when the last worker steps out, so the order the
 * rendezvous happens to release the workers in never decides who runs next.
 *
 * <p>A worker can still stop running without reaching a point: it blocks on something the schedule
 * does not track, sleeps, waits on a future, or computes for longer than {@link #STALL_NANOS}. The waiting workers
 * notice, mark it escaped and hand the round to the next; it rejoins at its next point. That keeps
 * a round live, but an escaped worker runs alongside the next one until then, so the round is no
 * longer guaranteed to replay. Each escape is counted, and {@link Summary#escapes()} is how the
 * runner knows whether a seed it prints replays exactly.
 *
 * <p>Points come from the agent's field-access hooks, from every hook that replaces a collection,
 * concurrency-utility, shared-instance or atomic call (#1023, kept true by the agent module's
 * {@code SubstitutionHooksArePointsTest}), and from {@code AsyncTestContext.schedulePoint()}. A point costs one volatile read on a thread outside a
 * controlled round, so the hooks can call it unconditionally. The scheduler's own lock is never
 * woven: the agent does not instrument this library.
 *
 * <p>Internal: public because the runner and {@code AsyncTestContext} are in other packages.
 *
 * @since 1.13.2
 */
@AICore(
    sensitivity = "High",
    note = "A seed replays a round only while every decision of who runs next depends on state changed at scheduling points: the statuses, priorities and step count that grant() and step() read, and the lock holds and pending acquisitions grant() consults (#1022), which change only at acquiring(), itself a point, and in acquired() and released(), which a worker calls in its own program order; an escaped worker's acquire or release lands outside a turn, but its round has already counted the escape. grant() must keep handing the round out when every ready worker waits on a held lock: the hold may be stale (a monitor released inside Object.wait is not seen), and withholding the round then stalls correct wait/notify code until the round times out (ControlledScheduleLockTest); each such grant is counted as a fallback. To keep holds from going stale, a woven Object.wait or Condition.await hands its holds back before waiting and takes them again after (releasingForWait, reacquiredAfterWait; lockOf finds a condition's ReentrantLock by asking it), and a worker re-confirms its own recorded holds with Thread.holdsLock or isHeldByCurrentThread at each of its points and at leave(), on its own thread, so the answer follows its program order and grant() still reads no thread state (#1048). Only watch() may read the clock or Thread.getState(), and it reads the state before asking whether the running worker is queued on the schedule's own lock, which is the scheduler's wait and never an escape (#1065); each decision it makes is counted as an escape, which is what tells the runner the seed no longer replays exactly, with one exception: an adopted child whose thread has terminated is marked done, not escaped (#1024), because while it ran to its end every peer was waiting, so the state the next grant reads is the same whenever watch() notices. Adopted children are ready from the moment they are adopted, so a grant never depends on how long the OS takes to start a thread. A worker waiting at a rendezvous is OUTSIDE, not escaped (#1021), and no grant is made while one is outside on its way back or when the last worker steps out, so the order a rendezvous releases workers in never decides who runs next. A grant() that read time or thread state, or an escape left uncounted, still passes every run and makes the printed replay line a false promise."
)
public final class ControlledSchedule {

    /** PCT's bug depth: {@code DEPTH - 1} priority change points per round. */
    static final int DEPTH = 3;

    /** How often a waiting worker checks whether the running one has stopped running. */
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /** How long a running worker may go without reaching a point before it counts as escaped. */
    static final long STALL_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    /**
     * Consecutive steps after which a worker that keeps being chosen drops to the lowest priority.
     * Without it a worker spinning on a flag only a lower-priority worker can set would win every
     * step, PCT's known livelock.
     */
    static final int SPIN_LIMIT = 1_000;

    /** Controlled rounds open in this JVM: zero keeps {@link #point()} to one volatile read. */
    private static final AtomicInteger OPEN = new AtomicInteger();

    /** The calling thread's place in the round it joined, between {@link #enter} and {@link #leave}. */
    private static final ThreadLocal<Worker> CURRENT = new ThreadLocal<>();

    /** Threads started by a controlled round's workers, before their first point: thread to round. */
    private static final Map<Thread, Adoption> ADOPTED = new ConcurrentHashMap<>();

    private record Adoption(ControlledSchedule schedule, int slot) {}

    /** OUTSIDE: waiting in a wait the library runs, such as a rendezvous, and not escaped (#1021). */
    private enum Status { ABSENT, READY, RUNNING, ESCAPED, OUTSIDE, DONE }

    private static final class Worker {
        final ControlledSchedule schedule;
        final int slot;
        final Thread thread;
        /** In this class's own code, where waiting is the scheduler's and not a blocked body. */
        volatile boolean inside;
        /** Interrupted while waiting for a turn: runs uncontrolled for the rest of the body. */
        boolean free;

        Worker(ControlledSchedule schedule, int slot, Thread thread) {
            this.schedule = schedule;
            this.slot = slot;
            this.thread = thread;
        }
    }

    /**
     * What one round's schedule did.
     *
     * @param seed     the round's replay seed
     * @param steps    scheduling points the workers reached while running
     * @param switches times the round changed hands
     * @param escapes  times a worker stopped running without reaching a point; zero means the seed
     *                 replays this round's interleaving exactly
     * @param fallbacks times the round went to a worker waiting on a lock the schedule saw held,
     *                 because every ready worker was (#1048); the schedule's record may be stale,
     *                 or the body deadlocked
     */
    public record Summary(long seed, long steps, int switches, int escapes, int fallbacks) {

        /**
         * A summary with no fallbacks, the shape before #1048.
         *
         * @param seed     the round's replay seed
         * @param steps    scheduling points the workers reached while running
         * @param switches times the round changed hands
         * @param escapes  times a worker stopped running without reaching a point
         */
        public Summary(long seed, long steps, int switches, int escapes) {
            this(seed, steps, switches, escapes, 0);
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition turn = lock.newCondition();
    private final long seed;
    /** The runner's workers: the round starts once this many have joined. */
    private final int workers;
    /** Slots in use: the workers, then each adopted child in the order it was started. */
    private int count;
    private @Nullable Worker[] slots;
    private Status[] status;
    private int[] priority;
    /** The exclusive lock each worker's pending step acquires, or {@code null}. */
    private @Nullable Object[] pending;
    /** The child slot each worker's pending step joins, or -1. */
    private int[] joining;
    /** The thread of each adopted child, so a child that ends before its first point is seen. */
    private @Nullable Thread[] children;
    /** Exclusive locks the round's workers hold: lock to {holding slot, hold count}. */
    private final Map<Object, int[]> owners = new IdentityHashMap<>();
    private final long[] changePoints;
    private int nextChange;
    private int lowest;
    private int arrived;
    private boolean started;
    private int running = -1;
    private boolean acknowledged;
    private long acknowledgedAt;
    private long lastProgress = System.nanoTime();
    private int last = -1;
    private int streak;
    private long steps;
    private int switches;
    private int escapes;
    private int fallbacks;
    private boolean closed;

    private ControlledSchedule(int workers, long seed) {
        this.seed = seed;
        this.workers = workers;
        this.count = workers;
        this.slots = new Worker[workers];
        this.status = new Status[workers];
        Arrays.fill(status, Status.ABSENT);
        this.pending = new Object[workers];
        this.joining = new int[workers];
        Arrays.fill(joining, -1);
        this.children = new Thread[workers];
        SplittableRandom random = new SplittableRandom(seed ^ 0x5DEECE66DL);
        int[] order = new int[workers];
        for (int i = 0; i < workers; i++) {
            order[i] = i;
        }
        for (int i = workers - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        this.priority = new int[workers];
        for (int i = 0; i < workers; i++) {
            priority[i] = DEPTH + order[i];
        }
        int exponent = 2 + random.nextInt(11);
        long bound = 1L << exponent;
        this.changePoints = new long[DEPTH - 1];
        for (int i = 0; i < changePoints.length; i++) {
            changePoints[i] = 1 + random.nextLong(bound);
        }
        Arrays.sort(changePoints);
    }

    /**
     * Opens the schedule for one round; the runner closes it with {@link #close()} once the round
     * is over, whatever happened in it.
     *
     * @param workers the round's worker count
     * @param seed    the round's replay seed, the only input the order depends on
     * @return the round's schedule
     */
    public static ControlledSchedule open(int workers, long seed) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be positive, was " + workers);
        }
        ControlledSchedule schedule = new ControlledSchedule(workers, seed);
        OPEN.incrementAndGet();
        return schedule;
    }

    /**
     * A scheduling point: the running worker may hand the round to another here. Returns at once on
     * a thread that is not a worker of a controlled round.
     */
    public static void point() {
        if (OPEN.get() == 0) {
            return;
        }
        Worker worker = CURRENT.get();
        if (worker == null) {
            joinIfAdopted();
            return;
        }
        if (worker.inside || worker.free) {
            return;
        }
        worker.schedule.step(worker, null);
    }

    /**
     * Steps the calling worker out of the round for a wait the library runs itself, a rendezvous:
     * the turn goes on to another worker without the wait counting as an escape. Pair with
     * {@link #rejoin} in a {@code finally}.
     *
     * @return whether the worker stepped out, and so must rejoin
     */
    public static boolean stepOutside() {
        if (OPEN.get() == 0) {
            return false;
        }
        Worker worker = CURRENT.get();
        if (worker == null || worker.inside || worker.free) {
            return false;
        }
        worker.schedule.goOutside(worker);
        return true;
    }

    /** Brings a worker that {@link #stepOutside stepped outside} back, and waits for its turn. */
    public static void rejoin() {
        Worker worker = CURRENT.get();
        if (worker == null || worker.inside || worker.free) {
            return;
        }
        worker.schedule.comeBack(worker);
    }

    /** A child's first point: it joins its round and waits for a turn like any worker. */
    private static void joinIfAdopted() {
        Adoption adoption = ADOPTED.remove(Thread.currentThread());
        if (adoption != null) {
            adoption.schedule().join(adoption.slot());
        }
    }

    /**
     * Adopts {@code child}, about to be started by the calling worker, into the worker's round
     * under the next slot. Not a point. Does nothing outside a controlled round.
     *
     * @param child the thread about to start
     */
    public static void starting(Thread child) {
        if (OPEN.get() == 0) {
            return;
        }
        Worker worker = CURRENT.get();
        if (worker == null || worker.inside || worker.free) {
            return;
        }
        worker.schedule.adopt(worker, child);
    }

    /**
     * A scheduling point before the calling worker joins {@code target}: when the target is a child
     * of this round, the worker gets the round back only once the child is done, so the join never
     * blocks outside the schedule. Any other target is a plain point.
     *
     * @param target the thread about to be joined
     */
    public static void joining(Thread target) {
        if (OPEN.get() == 0) {
            return;
        }
        Worker worker = CURRENT.get();
        if (worker == null) {
            joinIfAdopted();
            return;
        }
        if (worker.inside || worker.free) {
            return;
        }
        worker.schedule.stepJoining(worker, target);
    }

    /**
     * A scheduling point just before the calling worker takes the exclusive {@code lock}: entering
     * a monitor, or {@code ReentrantLock.lock()}. The round goes to this worker only once no other
     * worker holds the lock, unless every ready worker is waiting on one (a deadlock).
     *
     * @param lock the monitor or lock about to be taken
     */
    public static void acquiring(@Nullable Object lock) {
        if (OPEN.get() == 0) {
            return;
        }
        Worker worker = CURRENT.get();
        if (worker == null) {
            joinIfAdopted();
            worker = CURRENT.get();
            if (worker == null) {
                return;
            }
        }
        if (worker.inside || worker.free) {
            return;
        }
        // A null monitor makes MONITORENTER throw; there is nothing to hold, only a point.
        worker.schedule.step(worker, lock);
    }

    /**
     * Records that the calling worker now holds {@code lock}, taken without waiting at a point: a
     * successful {@code tryLock}, or the monitor of a {@code synchronized} method it has entered.
     *
     * @param lock the monitor or lock now held
     */
    public static void acquired(Object lock) {
        if (OPEN.get() == 0) {
            return;
        }
        Worker worker = CURRENT.get();
        if (worker == null || worker.inside) {
            return;
        }
        worker.schedule.hold(worker, lock);
    }

    /**
     * Records that the calling worker is giving up one hold of {@code lock}. Not a point: the worker
     * keeps running, and the peers waiting on the lock become eligible at the next grant.
     *
     * @param lock the monitor or lock being released
     */
    public static void released(Object lock) {
        if (OPEN.get() == 0) {
            return;
        }
        Worker worker = CURRENT.get();
        if (worker == null || worker.inside) {
            return;
        }
        worker.schedule.release(worker, lock);
    }

    /**
     * Records that the calling worker is about to wait on {@code monitor}, which {@code wait()}
     * releases however many times it is held, and returns that count for
     * {@link #reacquiredAfterWait}. Not a point: the worker goes on into the wait (#1048).
     *
     * @param monitor the monitor being waited on
     * @return the holds the schedule recorded for the calling worker, 0 when it recorded none
     */
    public static int releasingForWait(Object monitor) {
        if (OPEN.get() == 0) {
            return 0;
        }
        Worker worker = CURRENT.get();
        if (worker == null || worker.inside) {
            return 0;
        }
        return worker.schedule.releaseAll(worker, monitor);
    }

    /**
     * {@return the lock {@code condition} belongs to among those the schedule records the calling
     * worker holding, or {@code null}}
     *
     * <p>For a woven {@code Condition.await}, which lets that lock go for the wait as
     * {@code Object.wait} lets a monitor go (#1048). A {@code ReentrantLock} answers
     * {@code hasWaiters} for its own conditions and throws for any other, so the owner is found
     * without weaving {@code newCondition}. Not a point.
     *
     * @param condition the condition about to be awaited
     */
    public static @Nullable Object lockOf(Condition condition) {
        if (OPEN.get() == 0) {
            return null;
        }
        Worker worker = CURRENT.get();
        if (worker == null || worker.inside) {
            return null;
        }
        return worker.schedule.ownerOf(worker, condition);
    }

    /**
     * Records that the calling worker holds {@code monitor} again, {@code holds} times, after a
     * wait {@link #releasingForWait} recorded. Not a point.
     *
     * @param monitor the monitor waited on
     * @param holds   what {@link #releasingForWait} returned
     */
    public static void reacquiredAfterWait(Object monitor, int holds) {
        if (holds <= 0 || OPEN.get() == 0) {
            return;
        }
        Worker worker = CURRENT.get();
        if (worker == null || worker.inside) {
            return;
        }
        worker.schedule.restore(worker, monitor, holds);
    }

    /**
     * Joins the round as worker {@code slot} and waits for its first turn, which comes once every
     * worker has joined. Called by the worker itself, after the round's start barrier.
     *
     * @param slot the worker's slot, 0 to workers - 1; the order depends on slots, not threads
     */
    public void enter(int slot) {
        Worker worker = new Worker(this, slot, Thread.currentThread());
        CURRENT.set(worker);
        worker.inside = true;
        lock.lock();
        try {
            slots[slot] = worker;
            status[slot] = Status.READY;
            arrived++;
            if (started) {
                if (running < 0) {
                    grant();
                }
            } else if (arrived == workers) {
                started = true;
                grant();
            }
            awaitTurn(worker);
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    /** Leaves the round; the calling worker's body is over. Pairs with {@link #enter}. */
    public void leave() {
        Worker worker = CURRENT.get();
        if (worker == null || worker.schedule != this) { // NOPMD CompareObjectsWithEquals - identity
            return;
        }
        CURRENT.remove();
        worker.inside = true;
        lock.lock();
        try {
            dropHoldsNotHeld(worker.slot);
            status[worker.slot] = Status.DONE;
            pending[worker.slot] = null;
            if (running == worker.slot) {
                running = -1;
                grant();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Ends the round's schedule and releases any worker still waiting for a turn. Idempotent. */
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            turn.signalAll();
        } finally {
            lock.unlock();
        }
        ADOPTED.values().removeIf(adoption -> adoption.schedule() == this); // NOPMD - identity
        OPEN.decrementAndGet();
    }

    /** {@return what the round's schedule did so far} */
    public Summary summary() {
        lock.lock();
        try {
            return new Summary(seed, steps, switches, escapes, fallbacks);
        } finally {
            lock.unlock();
        }
    }

    private void step(Worker worker, @Nullable Object acquiring) {
        worker.inside = true;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            int slot = worker.slot;
            dropHoldsNotHeld(slot);
            pending[slot] = acquiring;
            joining[slot] = -1;
            if (running == slot) {
                steps++;
                streak++;
                while (nextChange < changePoints.length && changePoints[nextChange] <= steps) {
                    priority[slot] = DEPTH - 1 - nextChange;
                    nextChange++;
                }
                if (streak >= SPIN_LIMIT) {
                    priority[slot] = lowest;
                    lowest--;
                    streak = 0;
                }
                status[slot] = Status.READY;
                running = -1;
                grant();
            } else {
                // Back from an escape: wait for a turn like everyone else.
                status[slot] = Status.READY;
                if (running < 0) {
                    grant();
                }
            }
            awaitTurn(worker);
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    private void goOutside(Worker worker) {
        worker.inside = true;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            int slot = worker.slot;
            boolean wasRunning = running == slot;
            status[slot] = Status.OUTSIDE;
            pending[slot] = null;
            joining[slot] = -1;
            if (wasRunning) {
                running = -1;
                // The last worker out grants nothing: the rendezvous is about to release everyone,
                // and the grant waits for them all to be back.
                if (!everyWorkerOutsideOrDone()) {
                    grant();
                }
            }
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    private void comeBack(Worker worker) {
        worker.inside = true;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            status[worker.slot] = Status.READY;
            if (running < 0 && !anyOutside()) {
                grant();
            }
            awaitTurn(worker);
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    /** Lock held. */
    private boolean everyWorkerOutsideOrDone() {
        for (int i = 0; i < workers; i++) {
            if (status[i] != Status.OUTSIDE && status[i] != Status.DONE) {
                return false;
            }
        }
        return true;
    }

    /** Lock held. */
    private boolean anyOutside() {
        for (int i = 0; i < count; i++) {
            if (status[i] == Status.OUTSIDE) {
                return true;
            }
        }
        return false;
    }

    /** Joins the round as adopted child {@code slot}, from the child's own thread. */
    private void join(int slot) {
        Worker worker = new Worker(this, slot, Thread.currentThread());
        CURRENT.set(worker);
        worker.inside = true;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            slots[slot] = worker;
            // Adopted as ready, the child may already hold the turn (granted before it arrived) or
            // still wait for one; only a child escaped before arriving rejoins like a worker.
            if (status[slot] == Status.ESCAPED) {
                status[slot] = Status.READY;
                if (running < 0) {
                    grant();
                }
            }
            awaitTurn(worker);
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    private void adopt(Worker worker, Thread child) {
        worker.inside = true;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            int slot = count;
            grow(slot + 1);
            count = slot + 1;
            children[slot] = child;
            // A rank among the slots already in the round, drawn from the seed and the slot, which
            // the order of the starts decides: PCT's random priority, given later. The ranks above
            // it move up one, so the unlowered priorities stay DEPTH, DEPTH + 1, ... with no tie,
            // and a lowered priority (below DEPTH) stays below every rank.
            int rank = new SplittableRandom(seed ^ (0x9E3779B97F4A7C15L * (slot + 1))).nextInt(slot + 1);
            for (int i = 0; i < slot; i++) {
                if (priority[i] >= DEPTH + rank) {
                    priority[i]++;
                }
            }
            priority[slot] = DEPTH + rank;
            // A candidate from the start, so who runs next never depends on how long the OS takes
            // to start the thread: a child granted before it arrives is waited for.
            status[slot] = Status.READY;
            ADOPTED.put(child, new Adoption(this, slot));
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    private void stepJoining(Worker worker, Thread target) {
        int child = -1;
        lock.lock();
        try {
            for (int i = workers; i < count; i++) {
                if (target.equals(children[i])) { // Thread keeps Object.equals: identity
                    child = i;
                }
            }
        } finally {
            lock.unlock();
        }
        step(worker, null);
        if (child < 0) {
            return;
        }
        // The point above may have handed the round on; now wait until the child is done.
        worker.inside = true;
        lock.lock();
        try {
            if (closed || status[child] == Status.DONE) {
                return;
            }
            int slot = worker.slot;
            status[slot] = Status.READY;
            joining[slot] = child;
            running = -1;
            grant();
            awaitTurn(worker);
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    /** Makes room for {@code size} slots. Lock held. */
    private void grow(int size) {
        if (size <= slots.length) {
            return;
        }
        int from = slots.length;
        int capacity = Math.max(size, from * 2);
        slots = Arrays.copyOf(slots, capacity);
        status = Arrays.copyOf(status, capacity);
        Arrays.fill(status, from, capacity, Status.ABSENT);
        priority = Arrays.copyOf(priority, capacity);
        pending = Arrays.copyOf(pending, capacity);
        joining = Arrays.copyOf(joining, capacity);
        Arrays.fill(joining, from, capacity, -1);
        children = Arrays.copyOf(children, capacity);
    }

    /**
     * Marks done every adopted child that is not running and whose thread has ended, so a worker
     * joining it is not left waiting. Run by a waiting worker, lock held. Not an escape: an ended
     * child changes no state any later decision reads but its own status.
     *
     * @return whether a child was marked done
     */
    private boolean childEnded() {
        boolean ended = false;
        for (int i = workers; i < count; i++) {
            Thread child = children[i];
            if (status[i] != Status.DONE && status[i] != Status.RUNNING && child != null
                    && child.getState() == Thread.State.TERMINATED) {
                status[i] = Status.DONE;
                ADOPTED.remove(child);
                ended = true;
            }
        }
        return ended;
    }

    /**
     * Watches a child granted the round before its thread reached its first point. Lock held. It
     * ends there (done), or stops on something before it (escaped, as a worker would be), or simply
     * has not arrived yet (wait).
     */
    private void watchArriving(long now) {
        Thread child = children[running];
        if (child == null) {
            return;
        }
        Thread.State state = child.getState();
        if (state == Thread.State.TERMINATED) {
            status[running] = Status.DONE;
            ADOPTED.remove(child);
            running = -1;
            grant();
        } else if (state == Thread.State.BLOCKED || state == Thread.State.WAITING
                || state == Thread.State.TIMED_WAITING || now - lastProgress > STALL_NANOS) {
            status[running] = Status.ESCAPED;
            escapes++;
            running = -1;
            grant();
        }
    }

    private void hold(Worker worker, Object held) {
        worker.inside = true;
        lock.lock();
        try {
            take(worker.slot, held);
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    private void release(Worker worker, Object held) {
        worker.inside = true;
        lock.lock();
        try {
            int[] owner = owners.get(held);
            if (owner != null && owner[0] == worker.slot) {
                owner[1]--;
                if (owner[1] <= 0) {
                    owners.remove(held);
                }
            }
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    private @Nullable Object ownerOf(Worker worker, Condition condition) {
        worker.inside = true;
        lock.lock();
        try {
            for (Map.Entry<Object, int[]> hold : owners.entrySet()) {
                if (hold.getValue()[0] == worker.slot && hold.getKey() instanceof ReentrantLock held
                        && held.isHeldByCurrentThread()) {
                    try {
                        held.hasWaiters(condition);
                        return held;
                    } catch (IllegalArgumentException notItsOwn) { // NOPMD - the probe
                        // another lock's condition
                    }
                }
            }
            return null;
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    private int releaseAll(Worker worker, Object held) {
        worker.inside = true;
        lock.lock();
        try {
            int[] owner = owners.get(held);
            if (owner == null || owner[0] != worker.slot) {
                return 0;
            }
            owners.remove(held);
            return owner[1];
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    private void restore(Worker worker, Object held, int holds) {
        worker.inside = true;
        lock.lock();
        try {
            // The wait returned, so the JVM gave this worker the monitor: any other record is stale.
            owners.put(held, new int[] {worker.slot, holds});
        } finally {
            lock.unlock();
            worker.inside = false;
        }
    }

    /**
     * Forgets the holds the table lists for {@code slot} that its thread, the calling one, no
     * longer has: a {@code synchronized} method left by an exception passes no woven exit (#1048).
     * Asked of the worker's own thread at its own point, so the answer follows its program order
     * like an {@code acquired} or {@code released} call would. A {@code Lock} other than the two
     * exclusive kinds the table holds cannot be asked and is kept. Lock held.
     */
    private void dropHoldsNotHeld(int slot) {
        Iterator<Map.Entry<Object, int[]>> holds = owners.entrySet().iterator();
        while (holds.hasNext()) {
            Map.Entry<Object, int[]> hold = holds.next();
            if (hold.getValue()[0] == slot && !heldByCallingThread(hold.getKey())) {
                holds.remove();
            }
        }
    }

    private static boolean heldByCallingThread(Object held) {
        if (held instanceof ReentrantLock reentrant) {
            return reentrant.isHeldByCurrentThread();
        }
        if (held instanceof ReentrantReadWriteLock.WriteLock write) {
            return write.isHeldByCurrentThread();
        }
        return held instanceof Lock || Thread.holdsLock(held);
    }

    /** Records one more hold of {@code held} by {@code slot}. Lock held. */
    private void take(int slot, Object held) {
        int[] owner = owners.get(held);
        if (owner == null) {
            owners.put(held, new int[] {slot, 1});
        } else if (owner[0] == slot) {
            owner[1]++;
        } else {
            // The table says another worker holds it, yet this one has it: that worker's release
            // was never seen (an exception out of a synchronized method is not woven). The JVM is
            // the authority on who holds a lock, so the record follows it.
            owner[0] = slot;
            owner[1] = 1;
        }
    }

    /** Whether worker {@code slot}'s pending step can proceed now. Lock held. */
    private boolean canProceed(int slot) {
        int child = joining[slot];
        if (child >= 0 && status[child] != Status.DONE) {
            return false;
        }
        Object wanted = pending[slot];
        if (wanted == null) {
            return true;
        }
        int[] owner = owners.get(wanted);
        return owner == null || owner[0] == slot;
    }

    /**
     * Hands the round to the highest-priority ready worker that can proceed, or, when every ready
     * worker waits on a lock another holds, to the highest-priority ready worker regardless, so a
     * deadlock in the code under test happens rather than stalling the scheduler. Lock held.
     */
    private void grant() {
        int best = -1;
        int anyReady = -1;
        for (int i = 0; i < count; i++) {
            if (status[i] != Status.READY) {
                continue;
            }
            // A worker joining a child that is not done waits for it, fallback or not: the child is
            // either running or will join at its first point, and watch() sees one that ends first.
            if (joining[i] < 0 && (anyReady < 0 || priority[i] > priority[anyReady])) {
                anyReady = i;
            }
            if (canProceed(i) && (best < 0 || priority[i] > priority[best])) {
                best = i;
            }
        }
        if (best < 0) {
            best = anyReady;
            if (best >= 0) {
                fallbacks++;
            }
        }
        running = best;
        acknowledged = false;
        if (best >= 0) {
            Object wanted = pending[best];
            if (wanted != null) {
                if (canProceed(best)) {
                    take(best, wanted);
                }
                pending[best] = null;
            }
            joining[best] = -1;
            status[best] = Status.RUNNING;
            if (best != last) {
                if (last >= 0) {
                    switches++;
                }
                last = best;
                streak = 0;
            }
        }
        lastProgress = System.nanoTime();
        turn.signalAll();
    }

    /** Waits, lock held, until the round is this worker's; watches the running worker meanwhile. */
    private void awaitTurn(Worker worker) {
        while (running != worker.slot && !closed) {
            try {
                // Signalled means the round changed hands just now, to a worker that has not
                // started yet; only a poll that timed out has anything to watch.
                if (turn.awaitNanos(POLL_NANOS) <= 0L) {
                    watch();
                }
            } catch (InterruptedException e) {
                // The round is being cancelled: run on uncontrolled rather than wait for a turn
                // that may never come, and keep the interrupt for the body to see.
                Thread.currentThread().interrupt();
                worker.free = true;
                status[worker.slot] = Status.ESCAPED;
                escapes++;
                return;
            }
        }
        if (running == worker.slot) {
            acknowledged = true;
            acknowledgedAt = System.nanoTime();
        }
    }

    /**
     * Run by a waiting worker, lock held: moves the round on when the running worker has stopped
     * running without reaching a point, or when a worker never joined.
     */
    private void watch() {
        long now = System.nanoTime();
        if (running >= 0) {
            Worker current = slots[running];
            if (current == null && running >= workers) {
                watchArriving(now);
                return;
            }
            if (!acknowledged || current == null || current.inside) {
                return;
            }
            Thread.State state = current.thread.getState();
            if (lock.hasQueuedThread(current.thread)) {
                // It entered this class's code after inside was read above and waits for the lock
                // this watcher holds: the scheduler's wait, not a blocked body (#1065). Asked after
                // the state, so a worker the state saw parked here is already in the queue.
                return;
            }
            if (state == Thread.State.TERMINATED) {
                // A child has no leave(): its thread ending is its leave. Not an escape: nothing ran
                // between its last point and its end but its own code, while every peer waited.
                status[running] = Status.DONE;
                running = -1;
                grant();
                return;
            }
            boolean blocked = state == Thread.State.BLOCKED || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING;
            if (blocked || now - acknowledgedAt > STALL_NANOS) {
                status[running] = Status.ESCAPED;
                escapes++;
                running = -1;
                grant();
            }
        } else if (started && childEnded()) {
            grant();
        } else if (!started && now - lastProgress > STALL_NANOS) {
            // A worker that never joined, most likely one that failed before the body: start
            // without it rather than wait out the round.
            started = true;
            escapes++;
            grant();
        }
    }
}
