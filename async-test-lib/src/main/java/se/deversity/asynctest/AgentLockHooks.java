package se.deversity.asynctest;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

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

    private AgentLockHooks() {
    }

    /** Weaves {@code Lock.lock()}. @param receiver the lock */
    public static void lock(Lock receiver) {
        receiver.lock();
        HeldLocks.acquired(receiver);
    }

    /** Weaves {@code Lock.lockInterruptibly()}. @param receiver the lock @throws InterruptedException if interrupted while waiting */
    public static void lockInterruptibly(Lock receiver) throws InterruptedException {
        receiver.lockInterruptibly();
        HeldLocks.acquired(receiver);
    }

    /** Weaves {@code Lock.tryLock()}. @param receiver the lock @return whether the lock was acquired */
    public static boolean tryLock(Lock receiver) {
        boolean acquired = receiver.tryLock();
        if (acquired) {
            HeldLocks.acquired(receiver);
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
            HeldLocks.acquired(receiver);
        }
        return acquired;
    }

    /** Weaves {@code Lock.unlock()}. @param receiver the lock */
    public static void unlock(Lock receiver) {
        HeldLocks.released(receiver);
        receiver.unlock();
    }
}
