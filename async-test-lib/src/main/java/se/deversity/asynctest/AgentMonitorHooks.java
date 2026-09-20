package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.MissedSignalDetector;
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
 * @since 1.12.2
 */
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures here are matched by CollectionAccessWeaver.MONITOR_ENTRIES, and loopBackEdge by name with a ()V descriptor, so none can change independently of the weaver. Every hook must perform the original call on the receiver and propagate what it throws unchanged: InterruptedException, and the IllegalMonitorStateException of a call made without the monitor, which is why nothing is recorded unless Thread.holdsLock(receiver). Record the wait before wait() releases the monitor and the wakeup after it is reacquired, in a finally, so a notify is judged against the threads really waiting; record a notify before making it. loopBackEdge takes nothing and returns nothing because the weaver inserts it in front of a jump whose operands are already on the stack.")
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
     * {@return the detector to record on, or {@code null} when there is none or the call is about
     * to throw} A call made without the monitor throws and neither waits nor signals.
     */
    private static @Nullable MissedSignalDetector observing(Object receiver) {
        MissedSignalDetector detector = AsyncTestContext.currentMissedSignalDetector();
        return detector != null && receiver != null && Thread.holdsLock(receiver) ? detector : null;
    }
}
