package se.deversity.asynctest;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.ConditionVariableDetector;
import se.deversity.asynctest.diagnostics.LockOrderValidator;
import se.deversity.asynctest.diagnostics.MissedSignalDetector;
import se.deversity.asynctest.diagnostics.SpuriousWakeupDetector;
import se.deversity.asynctest.diagnostics.SynchronizedNonFinalDetector;
import se.deversity.asynctest.telemetry.ControlledSchedule;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for {@code Object.wait}, {@code notify} and {@code notifyAll}, for {@code Condition.await},
 * {@code signal} and {@code signalAll} (#1255), and for the back-edge of a loop around a wait.
 *
 * <h2>Why these need the agent</h2>
 *
 * <p>{@code Object} exposes neither its waiter set nor whether a notify reached anybody, so
 * {@link MissedSignalDetector} could only judge what the body recorded about itself (#694). Woven,
 * the hooks run while the calling thread holds the monitor, which both {@code wait} and
 * {@code notify} require: the waits open when a notify arrives are then the threads really inside
 * {@code wait()} on that monitor, in the included classes.
 *
 * <h2>The back-edge</h2>
 *
 * <p>Whether a wait is a missed-signal bug depends on the loop around it, and a call site does not
 * say. The weaver emits {@link #loopBackEdge()} before every backward jump whose target precedes a
 * woven wait in the same method. A thread that reaches it after waking is in
 * {@code while (!ready) wait()}; a thread that never does made an {@code if (!ready) wait()}.
 *
 * <h2>The monitor field</h2>
 *
 * <p>{@link #monitorFieldEntered} is woven by {@code FieldAccessWeaver}, not by a call-site table:
 * before a {@code MONITORENTER} whose monitor the method has just read from an instance field, it
 * passes the monitor and the instance it was read from (#793).
 *
 * @since 1.12.2
 */
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures here are matched by CollectionAccessWeaver.MONITOR_ENTRIES, loopBackEdge by name with a ()V descriptor, and monitorFieldEntered by FieldAccessWeaver with an (Object, Object, String)V descriptor, so none can change independently of the weaver. monitorFieldEntered runs before a MONITORENTER and must not throw: a null monitor is left for the MONITORENTER to reject with its own NullPointerException. Every hook must perform the original call on the receiver and propagate what it throws unchanged: InterruptedException, and the IllegalMonitorStateException of a call made without the monitor, which is why nothing is recorded on a monitor unless Thread.holdsLock(receiver); a Condition cannot be asked about its lock, so its await is recorded before the call, and one made without the lock throws before it waits (#1255). Record the wait before wait() releases the monitor and the wakeup after it is reacquired, in a finally, so a notify is judged against the threads really waiting; record a notify before making it. The same goes for a controlled schedule's record of the hold: releasingForWait before wait(), reacquiredAfterWait in an outer finally, so a peer waiting to enter the monitor is not passed over while it is free (#1048). A wait or await that ends in InterruptedException is recorded as interrupted rather than as a wakeup, still from that finally, because it was cancelled and its loop is left without a back-edge (#1501). loopBackEdge takes nothing and returns nothing because the weaver inserts it in front of a jump whose operands are already on the stack, and loopWaitReturned, which records the same thing, takes nothing for the same reason after a call a marked loop encloses (#1555); conditionAwaitResultTested is not an operation either: the weaver emits it in front of the IFNE that tests a timed Condition await's result, and it returns the boolean it took.")
public final class AgentMonitorHooks {

    private AgentMonitorHooks() {
    }

    /**
     * Weaves {@code Object.wait()}.
     *
     * @param receiver the monitor
     * @throws InterruptedException if interrupted while waiting
     */
    public static void monitorWait(Object receiver) throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Object.wait", false);
        MissedSignalDetector detector = observing(receiver);
        int holds = ControlledSchedule.releasingForWait(receiver);
        int orderHolds = releasingOrderForWait(receiver);
        try {
            MissedSignalDetector taken =
                    detector != null && detector.recordObservedWait(receiver, AsyncTestContext.notAWorker())
                            ? detector : null;
            boolean interrupted = false;
            try {
                receiver.wait();
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            } finally {
                woke(taken, receiver, interrupted);
            }
            returnedFromWait(receiver);
        } finally {
            reacquiredOrderAfterWait(receiver, orderHolds);
            ControlledSchedule.reacquiredAfterWait(receiver, holds);
        }
    }

    /**
     * Weaves {@code Object.wait(long)}.
     *
     * @param receiver      the monitor
     * @param timeoutMillis the maximum time to wait
     * @throws InterruptedException if interrupted while waiting
     */
    public static void monitorWait(Object receiver, long timeoutMillis) throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Object.wait", false);
        MissedSignalDetector detector = observing(receiver);
        int holds = ControlledSchedule.releasingForWait(receiver);
        int orderHolds = releasingOrderForWait(receiver);
        try {
            MissedSignalDetector taken =
                    detector != null && detector.recordObservedWait(receiver, AsyncTestContext.notAWorker())
                            ? detector : null;
            boolean interrupted = false;
            try {
                receiver.wait(timeoutMillis);
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            } finally {
                woke(taken, receiver, interrupted);
            }
            returnedFromWait(receiver);
        } finally {
            reacquiredOrderAfterWait(receiver, orderHolds);
            ControlledSchedule.reacquiredAfterWait(receiver, holds);
        }
    }

    /**
     * Weaves {@code Object.wait(long, int)}.
     *
     * @param receiver      the monitor
     * @param timeoutMillis the maximum time to wait
     * @param nanos         additional nanoseconds
     * @throws InterruptedException if interrupted while waiting
     */
    public static void monitorWait(Object receiver, long timeoutMillis, int nanos)
            throws InterruptedException {
        AgentConcurrencyUtilHooks.blocking("Object.wait", false);
        MissedSignalDetector detector = observing(receiver);
        int holds = ControlledSchedule.releasingForWait(receiver);
        int orderHolds = releasingOrderForWait(receiver);
        try {
            MissedSignalDetector taken =
                    detector != null && detector.recordObservedWait(receiver, AsyncTestContext.notAWorker())
                            ? detector : null;
            boolean interrupted = false;
            try {
                receiver.wait(timeoutMillis, nanos);
            } catch (InterruptedException e) {
                interrupted = true;
                throw e;
            } finally {
                woke(taken, receiver, interrupted);
            }
            returnedFromWait(receiver);
        } finally {
            reacquiredOrderAfterWait(receiver, orderHolds);
            ControlledSchedule.reacquiredAfterWait(receiver, holds);
        }
    }

    /**
     * Weaves {@code Object.notify()}.
     *
     * @param receiver the monitor
     */
    public static void monitorNotify(Object receiver) {
        MissedSignalDetector detector = observing(receiver);
        if (detector != null) {
            detector.recordObservedNotify(receiver);
        }
        receiver.notify(); // NOPMD - the hook makes the call it replaced, not a better one
    }

    /**
     * Weaves {@code Object.notifyAll()}.
     *
     * @param receiver the monitor
     */
    public static void monitorNotifyAll(Object receiver) {
        MissedSignalDetector detector = observing(receiver);
        if (detector != null) {
            detector.recordObservedNotify(receiver);
        }
        receiver.notifyAll();
    }

    // ------------------------------------------------------------------------------------------
    // Condition.await (#1048): the wait lets the condition's lock go, as Object.wait lets a
    // monitor go, so a controlled schedule's record of the hold is handed back for the wait, and
    // so is LockOrderValidator's (#1256): taking the lock back blocks, so it is nested inside
    // whatever else the thread still holds. Each finds the lock among its own record of holds.
    // Nothing else is recorded: the lockset and the other lock detectors see the thread still
    // inside lock()/unlock(), which is where the condition's lock is held when await returns.
    // ------------------------------------------------------------------------------------------

    /** Weaves {@code Condition.await()}. @param receiver the condition @throws InterruptedException if interrupted while waiting */
    public static void conditionAwait(Condition receiver) throws InterruptedException {
        ConditionVariableDetector conditions = observingAwait(receiver);
        Object owner = ControlledSchedule.lockOf(receiver);
        int holds = owner == null ? 0 : ControlledSchedule.releasingForWait(owner);
        Object orderOwner = orderLockOf(receiver);
        int orderHolds = orderOwner == null ? 0 : releasingOrderForWait(orderOwner);
        boolean interrupted = false;
        try {
            receiver.await();
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            awaitExited(conditions, receiver, interrupted);
            if (orderOwner != null) {
                reacquiredOrderAfterWait(orderOwner, orderHolds);
            }
            if (owner != null) {
                ControlledSchedule.reacquiredAfterWait(owner, holds);
            }
        }
    }

    /** Weaves {@code Condition.await(long, TimeUnit)}. @param receiver the condition @param time the maximum time to wait @param unit its unit @return false if the time elapsed first @throws InterruptedException if interrupted while waiting */
    public static boolean conditionAwait(Condition receiver, long time, TimeUnit unit) throws InterruptedException {
        ConditionVariableDetector conditions = observingAwait(receiver);
        Object owner = ControlledSchedule.lockOf(receiver);
        int holds = owner == null ? 0 : ControlledSchedule.releasingForWait(owner);
        Object orderOwner = orderLockOf(receiver);
        int orderHolds = orderOwner == null ? 0 : releasingOrderForWait(orderOwner);
        boolean interrupted = false;
        try {
            boolean signalled = receiver.await(time, unit);
            timedAwaitReturned(conditions, receiver, signalled);
            return signalled;
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            awaitExited(conditions, receiver, interrupted);
            if (orderOwner != null) {
                reacquiredOrderAfterWait(orderOwner, orderHolds);
            }
            if (owner != null) {
                ControlledSchedule.reacquiredAfterWait(owner, holds);
            }
        }
    }

    /** Weaves {@code Condition.awaitNanos(long)}. @param receiver the condition @param nanosTimeout the maximum time to wait @return the time left, as the condition reports it @throws InterruptedException if interrupted while waiting */
    public static long conditionAwaitNanos(Condition receiver, long nanosTimeout) throws InterruptedException {
        ConditionVariableDetector conditions = observingAwait(receiver);
        Object owner = ControlledSchedule.lockOf(receiver);
        int holds = owner == null ? 0 : ControlledSchedule.releasingForWait(owner);
        Object orderOwner = orderLockOf(receiver);
        int orderHolds = orderOwner == null ? 0 : releasingOrderForWait(orderOwner);
        boolean interrupted = false;
        try {
            return receiver.awaitNanos(nanosTimeout);
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            awaitExited(conditions, receiver, interrupted);
            if (orderOwner != null) {
                reacquiredOrderAfterWait(orderOwner, orderHolds);
            }
            if (owner != null) {
                ControlledSchedule.reacquiredAfterWait(owner, holds);
            }
        }
    }

    /** Weaves {@code Condition.awaitUninterruptibly()}. @param receiver the condition */
    public static void conditionAwaitUninterruptibly(Condition receiver) {
        ConditionVariableDetector conditions = observingAwait(receiver);
        Object owner = ControlledSchedule.lockOf(receiver);
        int holds = owner == null ? 0 : ControlledSchedule.releasingForWait(owner);
        Object orderOwner = orderLockOf(receiver);
        int orderHolds = orderOwner == null ? 0 : releasingOrderForWait(orderOwner);
        try {
            receiver.awaitUninterruptibly();
        } finally {
            if (conditions != null) {
                conditions.recordObservedAwaitExit(receiver);
            }
            if (orderOwner != null) {
                reacquiredOrderAfterWait(orderOwner, orderHolds);
            }
            if (owner != null) {
                ControlledSchedule.reacquiredAfterWait(owner, holds);
            }
        }
    }

    /** Weaves {@code Condition.awaitUntil(Date)}. @param receiver the condition @param deadline the absolute time to wait until @return false if the deadline passed first @throws InterruptedException if interrupted while waiting */
    public static boolean conditionAwaitUntil(Condition receiver, Date deadline) throws InterruptedException {
        ConditionVariableDetector conditions = observingAwait(receiver);
        Object owner = ControlledSchedule.lockOf(receiver);
        int holds = owner == null ? 0 : ControlledSchedule.releasingForWait(owner);
        Object orderOwner = orderLockOf(receiver);
        int orderHolds = orderOwner == null ? 0 : releasingOrderForWait(orderOwner);
        boolean interrupted = false;
        try {
            boolean signalled = receiver.awaitUntil(deadline);
            timedAwaitReturned(conditions, receiver, signalled);
            return signalled;
        } catch (InterruptedException e) {
            interrupted = true;
            throw e;
        } finally {
            awaitExited(conditions, receiver, interrupted);
            if (orderOwner != null) {
                reacquiredOrderAfterWait(orderOwner, orderHolds);
            }
            if (owner != null) {
                ControlledSchedule.reacquiredAfterWait(owner, holds);
            }
        }
    }

    /**
     * Weaves {@code Condition.signal()}.
     *
     * @param receiver the condition
     */
    public static void conditionSignal(Condition receiver) {
        ConditionVariableDetector conditions = AsyncTestContext.currentConditionVariableDetector();
        if (conditions != null) {
            conditions.recordObservedSignal(receiver);
        }
        receiver.signal();
    }

    /**
     * Weaves {@code Condition.signalAll()}.
     *
     * @param receiver the condition
     */
    public static void conditionSignalAll(Condition receiver) {
        ConditionVariableDetector conditions = AsyncTestContext.currentConditionVariableDetector();
        if (conditions != null) {
            conditions.recordObservedSignal(receiver);
        }
        receiver.signalAll();
    }

    /**
     * Emitted right after a woven wait, or a call into a waiting method, that a marked loop
     * encloses, once it has returned (#1555). It records what {@link #loopBackEdge()} records: the
     * weaver has already seen the back-edge that closes the loop, so a loop that leaves after
     * waking by {@code return} or {@code break}, past that back-edge, still confirms the wait as
     * its own. A separate name so a back-edge count counts back-edges.
     *
     * @since 1.14.5
     */
    public static void loopWaitReturned() {
        loopBackEdge();
    }

    /** Emitted before a backward jump that encloses a woven wait: the loop's back-edge. */
    public static void loopBackEdge() {
        MissedSignalDetector detector = AsyncTestContext.currentMissedSignalDetector();
        if (detector != null) {
            detector.recordObservedLoopBackEdge();
        }
        SpuriousWakeupDetector spurious = AsyncTestContext.currentSpuriousWakeupDetector();
        if (spurious != null) {
            spurious.recordObservedLoopBackEdge();
        }
        ConditionVariableDetector conditions = AsyncTestContext.currentConditionVariableDetector();
        if (conditions != null) {
            conditions.recordObservedLoopBackEdge();
        }
    }

    /**
     * Records the end of a wait {@code taken} took: a wakeup, or for a wait that ended in
     * {@code InterruptedException} a cancellation, which is not judged (#1501). Always from the
     * hook's {@code finally}, so the wait stops counting as open whichever way it ended.
     */
    private static void woke(@Nullable MissedSignalDetector taken, Object receiver, boolean interrupted) {
        if (taken == null) {
            return;
        }
        if (interrupted) {
            taken.recordObservedWaitInterrupted(receiver);
        } else {
            taken.recordObservedWakeup(receiver);
        }
    }

    /** {@link #woke} for a woven {@code Condition} await. */
    private static void awaitExited(@Nullable ConditionVariableDetector conditions, Condition receiver,
                                    boolean interrupted) {
        if (conditions == null) {
            return;
        }
        if (interrupted) {
            conditions.recordObservedAwaitInterrupted(receiver);
        } else {
            conditions.recordObservedAwaitExit(receiver);
        }
    }

    /**
     * The condition the calling thread's last timed await timed out on, held weakly until
     * {@link #conditionAwaitResultTested} says whether its caller handled the timeout (#1501).
     */
    private static final ThreadLocal<java.lang.ref.WeakReference<Condition>> TIMED_OUT_CONDITION =
            new ThreadLocal<>();

    /** Remembers a timed await that timed out, for the tested hook that may follow it. */
    private static void timedAwaitReturned(@Nullable ConditionVariableDetector conditions,
                                           Condition receiver, boolean signalled) {
        if (conditions == null || signalled) {
            TIMED_OUT_CONDITION.remove();
        } else {
            TIMED_OUT_CONDITION.set(new java.lang.ref.WeakReference<>(receiver));
        }
    }

    /**
     * Not an operation: the weaver calls it in front of an {@code IFNE} that tests the boolean a
     * timed {@code Condition.await(long, TimeUnit)} or {@code awaitUntil(Date)} just returned and
     * falls into the caller's code for a timeout: {@code if (!ready.await(..)) return;} inside a
     * {@code while (!done)} loop (#1501). Such a loop ends on its timeout without reaching its
     * back-edge, so this confirms the await as a loop's in its place. An await whose result is
     * popped, {@code if (!done) ready.await(..);}, keeps its judgement.
     *
     * @param signalled what the await returned, which the jump after this call tests
     * @return {@code signalled}, for the jump
     * @since 1.14.5
     */
    public static boolean conditionAwaitResultTested(boolean signalled) {
        java.lang.ref.WeakReference<Condition> pending = TIMED_OUT_CONDITION.get();
        if (pending == null) {
            return signalled;
        }
        TIMED_OUT_CONDITION.remove();
        Condition condition = pending.get();
        ConditionVariableDetector conditions = AsyncTestContext.currentConditionVariableDetector();
        if (!signalled && condition != null && conditions != null) {
            conditions.recordObservedAwaitTimeoutHandled(condition);
        }
        return signalled;
    }

    /**
     * {@return the detector a woven await is recorded on, having taken the await, or {@code null}}
     * Unlike a monitor, a {@code Condition} cannot be asked whether the caller holds its lock; an
     * await made without it throws before it waits, and the body fails on that exception (#1255).
     */
    private static @Nullable ConditionVariableDetector observingAwait(Condition receiver) {
        ConditionVariableDetector conditions = AsyncTestContext.currentConditionVariableDetector();
        return conditions != null && conditions.recordObservedAwait(receiver, AsyncTestContext.notAWorker())
                ? conditions : null;
    }

    /**
     * Delivers a wait that returned normally, with the monitor reacquired, to the run's
     * {@link SpuriousWakeupDetector} (#1255). Not from a {@code finally}: a wait that ends in
     * {@code InterruptedException} leaves a loop without its back-edge and was no wakeup at all.
     */
    private static void returnedFromWait(Object receiver) {
        SpuriousWakeupDetector spurious = AsyncTestContext.currentSpuriousWakeupDetector();
        if (spurious != null) {
            spurious.recordObservedWakeup(receiver, AsyncTestContext.notAWorker());
        }
    }

    /**
     * Emitted before a {@code MONITORENTER} whose monitor was just read from an instance field
     * (#793): {@code ALOAD owner; GETFIELD; DUP; ASTORE}, the shape javac gives
     * {@code synchronized (owner.field)}. The owner is what tells one instance whose lock changed
     * from several instances with a lock each, which no recording without it can decide.
     *
     * @param monitor the monitor about to be entered, {@code null} when the
     *                {@code MONITORENTER} is about to throw
     * @param owner   the instance the field was read from
     * @param field   the declaring class's simple name and the field's, as {@code "Service.lock"}
     * @since 1.12.4
     */
    public static void monitorFieldEntered(@Nullable Object monitor, Object owner, String field) {
        if (monitor == null) {
            return;
        }
        SynchronizedNonFinalDetector detector = AsyncTestContext.currentSynchronizedNonFinalDetector();
        if (detector != null) {
            detector.recordMonitorField(monitor, field, owner);
        }
    }

    /**
     * Delivers a woven {@code synchronized} block's entry to the run's {@link LockOrderValidator}
     * (#1243). Called from {@code TelemetryRegistry.monitorEntered}, which the weaver emits before
     * every {@code MONITORENTER}: entering a monitor can block, so it is nested by every lock the
     * thread already holds, and a lock-order inversion on two {@code synchronized} blocks is the
     * same deadlock as one on two {@code ReentrantLock}s.
     *
     * @param monitor the monitor about to be entered, {@code null} when the
     *                {@code MONITORENTER} is about to throw
     * @since 1.14.3
     */
    public static void lockOrderMonitorEntered(@Nullable Object monitor) {
        LockOrderValidator order = AsyncTestContext.currentLockOrderValidator();
        if (order != null && monitor != null) {
            order.recordLockAcquisition(monitor);
        }
    }

    /**
     * Delivers a woven {@code synchronized} method's entry to the run's {@link LockOrderValidator}
     * (#1243). No exit is woven when an exception leaves the method, so the validator re-confirms
     * this hold with {@link Thread#holdsLock} before it nests anything.
     *
     * @param monitor the monitor the method holds: {@code this}, or the class for a static method
     * @since 1.14.3
     */
    public static void lockOrderMethodMonitorEntered(Object monitor) {
        LockOrderValidator order = AsyncTestContext.currentLockOrderValidator();
        if (order != null) {
            order.recordMethodMonitorAcquisition(monitor);
        }
    }

    /**
     * The release half of {@link #lockOrderMonitorEntered} and
     * {@link #lockOrderMethodMonitorEntered}: one exit undoes one entry.
     *
     * @param monitor the monitor being exited
     * @since 1.14.3
     */
    public static void lockOrderMonitorExited(Object monitor) {
        LockOrderValidator order = AsyncTestContext.currentLockOrderValidator();
        if (order != null) {
            order.recordLockRelease(monitor);
        }
    }

    /** {@code wait} lets every hold of the monitor go: the validator must not see it held. */
    private static int releasingOrderForWait(Object receiver) {
        LockOrderValidator order = AsyncTestContext.currentLockOrderValidator();
        return order == null ? 0 : order.recordReleaseForWait(receiver);
    }

    /** The lock a condition belongs to, among those the validator sees the thread holding (#1256). */
    private static @Nullable Object orderLockOf(Condition receiver) {
        LockOrderValidator order = AsyncTestContext.currentLockOrderValidator();
        return order == null ? null : order.heldLockOf(receiver);
    }

    /** Taking the monitor back after {@code wait} blocks, so it is nested by what else is held. */
    private static void reacquiredOrderAfterWait(Object receiver, int holds) {
        LockOrderValidator order = AsyncTestContext.currentLockOrderValidator();
        if (order != null) {
            order.recordReacquireAfterWait(receiver, holds);
        }
    }

    /**
     * {@return the detector to record on, or {@code null} when there is none or the call is about
     * to throw} A call made without the monitor throws and neither waits nor signals.
     */
    private static @Nullable MissedSignalDetector observing(Object receiver) {
        MissedSignalDetector detector = AsyncTestContext.currentMissedSignalDetector();
        return detector != null && receiver != null && Thread.holdsLock(receiver) ? detector : null;
    }
}
