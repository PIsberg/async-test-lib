package se.deversity.asynctest;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Weave targets that make a {@link Lock} visible to the lock model, the way a monitor already is.
 *
 * <h2>Why locks needed their own hooks</h2>
 *
 * <p>A {@code synchronized} block compiles to {@code MONITORENTER}, which the agent weaves, so the
 * detectors can tell guarded code from racing code. A {@link java.util.concurrent.locks.ReentrantLock}
 * compiles to an ordinary method call and looks like nothing at all, so code guarded by one was
 * reported as unguarded. That is not a corner case: it is how most modern concurrent code guards
 * itself, and Guava's own cache is an example the corpus eval caught.
 *
 * <h2>Read-write locks</h2>
 *
 * <p>The two views of a {@link ReentrantReadWriteLock} are two objects, so a lockset that records
 * the view records a reader and a writer as holding different locks and can never see them agree.
 * The {@code readLock()} and {@code writeLock()} hooks remember which owner a view belongs to, and
 * the acquire hooks record the owner instead, in shared mode for the read view. A reader and a
 * writer then share a lockset, while a write made under the read view stays unguarded, because a
 * lock that admits other readers guards no write.
 *
 * <h2>Ordering</h2>
 *
 * <p>Acquisition is recorded <em>after</em> the lock is taken and release <em>before</em> it is
 * given up, so the recorded interval is always contained by the real one. The opposite order would
 * declare a lock held while a thread is still blocked acquiring it, and an access by another thread
 * in that window would read as guarded when it was not. Under-claiming can only produce a finding
 * on correct code; over-claiming hides a real race, and between those two this library must always
 * take the first.
 *
 * <p>A failed {@link Lock#tryLock()} records nothing, because nothing was acquired. An exception
 * out of {@code lock()} likewise records nothing, since the hook never reaches the record call.
 *
 * @since 1.10.0
 */
@API(status = Status.INTERNAL)
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures are matched by CollectionAccessWeaver.lockSubstitutions and cannot change independently of it. The acquire-after / release-before ordering is a safety property, not a style choice - recording a lock as held before it is actually held would make another thread's racing access read as guarded, which is the one error direction this library must never take. Every hook must perform the original operation and propagate its exceptions unchanged.")
public final class AgentLockHooks {

    /** Which read-write lock a view belongs to, and whether it admits other holders. */
    private record View(ReadWriteLock owner, boolean shared) {
    }

    /**
     * Every view handed out through a woven {@code readLock()} or {@code writeLock()} call,
     * keyed by identity and held weakly so a lock's lifetime stays its own.
     */
    private static final Map<Lock, View> VIEWS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Set once a view of a {@link ReadWriteLock} other than the JDK's is registered. */
    private static volatile boolean foreignViews;

    private AgentLockHooks() {
    }

    /** Weaves {@code ReadWriteLock.readLock()}. @param receiver the lock @return its read view */
    public static Lock readLock(ReadWriteLock receiver) {
        Lock view = receiver.readLock();
        remember(view, receiver, true);
        return view;
    }

    /** Weaves {@code ReadWriteLock.writeLock()}. @param receiver the lock @return its write view */
    public static Lock writeLock(ReadWriteLock receiver) {
        Lock view = receiver.writeLock();
        remember(view, receiver, false);
        return view;
    }

    /**
     * Weaves {@code ReentrantReadWriteLock.readLock()}, whose covariant return type is what a
     * call site compiled against the concrete class expects on the stack.
     *
     * @param receiver the lock
     * @return its read view
     */
    public static ReentrantReadWriteLock.ReadLock readLock(ReentrantReadWriteLock receiver) {
        ReentrantReadWriteLock.ReadLock view = receiver.readLock();
        remember(view, receiver, true);
        return view;
    }

    /**
     * Weaves {@code ReentrantReadWriteLock.writeLock()}, covariant return type included.
     *
     * @param receiver the lock
     * @return its write view
     */
    public static ReentrantReadWriteLock.WriteLock writeLock(ReentrantReadWriteLock receiver) {
        ReentrantReadWriteLock.WriteLock view = receiver.writeLock();
        remember(view, receiver, false);
        return view;
    }

    private static void remember(Lock view, ReadWriteLock owner, boolean shared) {
        if (view == null) {
            return;
        }
        if (!(owner instanceof ReentrantReadWriteLock)) {
            foreignViews = true;
        }
        VIEWS.putIfAbsent(view, new View(owner, shared));
    }

    /** {@return the registered view behind {@code lock}, or {@code null} for an ordinary lock} */
    private static @org.jspecify.annotations.Nullable View viewOf(Lock lock) {
        // The instanceof checks keep every plain ReentrantLock off the map, whose one monitor
        // would otherwise be taken by every thread on every acquisition.
        if (lock instanceof ReentrantReadWriteLock.ReadLock
                || lock instanceof ReentrantReadWriteLock.WriteLock
                || foreignViews) {
            return VIEWS.get(lock);
        }
        return null;
    }

    private static void noteAcquired(Lock lock) {
        View view = viewOf(lock);
        if (view == null) {
            HeldLocks.acquired(lock);
        } else {
            HeldLocks.acquired(view.owner(), view.shared());
        }
    }

    private static void noteReleased(Lock lock) {
        View view = viewOf(lock);
        if (view == null) {
            HeldLocks.released(lock);
        } else {
            HeldLocks.released(view.owner(), view.shared());
        }
    }

    /** Weaves {@code Lock.lock()}. @param receiver the lock */
    public static void lock(Lock receiver) {
        receiver.lock();
        noteAcquired(receiver);
    }

    /** Weaves {@code Lock.lockInterruptibly()}. @param receiver the lock @throws InterruptedException if interrupted while waiting */
    public static void lockInterruptibly(Lock receiver) throws InterruptedException {
        receiver.lockInterruptibly();
        noteAcquired(receiver);
    }

    /** Weaves {@code Lock.tryLock()}. @param receiver the lock @return whether the lock was acquired */
    public static boolean tryLock(Lock receiver) {
        boolean acquired = receiver.tryLock();
        if (acquired) {
            noteAcquired(receiver);
        }
        return acquired;
    }

    /**
     * Weaves {@code Lock.tryLock(long, TimeUnit)}.
     *
     * @param receiver the lock
     * @param time     how long to wait
     * @param unit     the unit of {@code time}
     * @return whether the lock was acquired
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean tryLock(Lock receiver, long time, TimeUnit unit) throws InterruptedException {
        boolean acquired = receiver.tryLock(time, unit);
        if (acquired) {
            noteAcquired(receiver);
        }
        return acquired;
    }

    /** Weaves {@code Lock.unlock()}. @param receiver the lock */
    public static void unlock(Lock receiver) {
        noteReleased(receiver);
        receiver.unlock();
    }
}
