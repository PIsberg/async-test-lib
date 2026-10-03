package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.MissedSignalDetector;
import se.deversity.asynctest.diagnostics.SynchronizedNonFinalDetector;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for {@code Object.wait}, {@code notify} and {@code notifyAll}, and for the back-edge of a
 * loop around a wait.
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
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures here are matched by CollectionAccessWeaver.MONITOR_ENTRIES, loopBackEdge by name with a ()V descriptor, and monitorFieldEntered by FieldAccessWeaver with an (Object, Object, String)V descriptor, so none can change independently of the weaver. monitorFieldEntered runs before a MONITORENTER and must not throw: a null monitor is left for the MONITORENTER to reject with its own NullPointerException. Every hook must perform the original call on the receiver and propagate what it throws unchanged: InterruptedException, and the IllegalMonitorStateException of a call made without the monitor, which is why nothing is recorded unless Thread.holdsLock(receiver). Record the wait before wait() releases the monitor and the wakeup after it is reacquired, in a finally, so a notify is judged against the threads really waiting; record a notify before making it. loopBackEdge takes nothing and returns nothing because the weaver inserts it in front of a jump whose operands are already on the stack.")
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
        MissedSignalDetector detector = observing(receiver);
        if (detector == null || !detector.recordObservedWait(receiver)) {
            receiver.wait();
            return;
        }
        try {
            receiver.wait();
        } finally {
            detector.recordObservedWakeup(receiver);
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
        MissedSignalDetector detector = observing(receiver);
        if (detector == null || !detector.recordObservedWait(receiver)) {
            receiver.wait(timeoutMillis);
            return;
        }
        try {
            receiver.wait(timeoutMillis);
        } finally {
            detector.recordObservedWakeup(receiver);
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
        MissedSignalDetector detector = observing(receiver);
        if (detector == null || !detector.recordObservedWait(receiver)) {
            receiver.wait(timeoutMillis, nanos);
            return;
        }
        try {
            receiver.wait(timeoutMillis, nanos);
        } finally {
            detector.recordObservedWakeup(receiver);
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

    /** Emitted before a backward jump that encloses a woven wait: the loop's back-edge. */
    public static void loopBackEdge() {
        MissedSignalDetector detector = AsyncTestContext.currentMissedSignalDetector();
        if (detector != null) {
            detector.recordObservedLoopBackEdge();
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
     * {@return the detector to record on, or {@code null} when there is none or the call is about
     * to throw} A call made without the monitor throws and neither waits nor signals.
     */
    private static @Nullable MissedSignalDetector observing(Object receiver) {
        MissedSignalDetector detector = AsyncTestContext.currentMissedSignalDetector();
        return detector != null && receiver != null && Thread.holdsLock(receiver) ? detector : null;
    }
}
