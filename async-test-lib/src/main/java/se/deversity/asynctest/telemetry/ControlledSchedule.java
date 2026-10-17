package se.deversity.asynctest.telemetry;

import org.jspecify.annotations.Nullable;
import se.deversity.vibetags.annotations.AICore;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

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
 * <p>A worker can stop running without reaching a point: it blocks on a lock a paused worker holds,
 * sleeps, waits on a future, or computes for longer than {@link #STALL_NANOS}. The waiting workers
 * notice, mark it escaped and hand the round to the next; it rejoins at its next point. That keeps
 * a round live, but an escaped worker runs alongside the next one until then, so the round is no
 * longer guaranteed to replay. Each escape is counted, and {@link Summary#escapes()} is how the
 * runner knows whether a seed it prints replays exactly.
 *
 * <p>Points come from the agent's field-access hooks and from
 * {@code AsyncTestContext.schedulePoint()}. A point costs one volatile read on a thread outside a
 * controlled round, so the hooks can call it unconditionally. The scheduler's own lock is never
 * woven: the agent does not instrument this library.
 *
 * <p>Internal: public because the runner and {@code AsyncTestContext} are in other packages.
 *
 * @since 1.13.2
 */
@AICore(
    sensitivity = "High",
    note = "A seed replays a round only while every decision of who runs next depends on state changed at scheduling points: the statuses, priorities and step count that grant() and step() read. Only watch() may read the clock or Thread.getState(), and each decision it makes is counted as an escape, which is what tells the runner the seed no longer replays exactly. A grant() that read time or thread state, or an escape left uncounted, still passes every run and makes the printed replay line a false promise."
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

    private enum Status { ABSENT, READY, RUNNING, ESCAPED, DONE }

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
     */
    public record Summary(long seed, long steps, int switches, int escapes) {}

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition turn = lock.newCondition();
    private final long seed;
    private final int workers;
    private final @Nullable Worker[] slots;
    private final Status[] status;
    private final int[] priority;
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
    private boolean closed;

    private ControlledSchedule(int workers, long seed) {
        this.seed = seed;
        this.workers = workers;
        this.slots = new Worker[workers];
        this.status = new Status[workers];
        Arrays.fill(status, Status.ABSENT);
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
        if (worker == null || worker.inside || worker.free) {
            return;
        }
        worker.schedule.step(worker);
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
            status[worker.slot] = Status.DONE;
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
        OPEN.decrementAndGet();
    }

    /** {@return what the round's schedule did so far} */
    public Summary summary() {
        lock.lock();
        try {
            return new Summary(seed, steps, switches, escapes);
        } finally {
            lock.unlock();
        }
    }

    private void step(Worker worker) {
        worker.inside = true;
        lock.lock();
        try {
            if (closed) {
                return;
            }
            int slot = worker.slot;
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

    /** Hands the round to the highest-priority ready worker, or to nobody. Lock held. */
    private void grant() {
        int best = -1;
        for (int i = 0; i < workers; i++) {
            if (status[i] == Status.READY && (best < 0 || priority[i] > priority[best])) {
                best = i;
            }
        }
        running = best;
        acknowledged = false;
        if (best >= 0) {
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
            if (!acknowledged || current == null || current.inside) {
                return;
            }
            Thread.State state = current.thread.getState();
            boolean blocked = state == Thread.State.BLOCKED || state == Thread.State.WAITING
                    || state == Thread.State.TIMED_WAITING;
            if (blocked || now - acknowledgedAt > STALL_NANOS) {
                status[running] = Status.ESCAPED;
                escapes++;
                running = -1;
                grant();
            }
        } else if (!started && now - lastProgress > STALL_NANOS) {
            // A worker that never joined, most likely one that failed before the body: start
            // without it rather than wait out the round.
            started = true;
            escapes++;
            grant();
        }
    }
}
