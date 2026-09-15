package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a waiter that acts on a wakeup no notify accounted for: a {@code wait()} that returned
 * with no recorded {@code notify()}/{@code notifyAll()} that could have woken it, after which the
 * waiting thread went on without waiting again.
 *
 * <p>{@code Object.wait} may return spuriously, and a timed wait returns when its time runs out.
 * Either is harmless in a predicate loop, which checks the condition and waits again; it is a
 * defect when the wait is guarded by {@code if} instead of {@code while}, because the thread then
 * proceeds on a condition nobody established:
 *
 * <pre>{@code
 * synchronized (monitor) {
 *     if (!ready) {          // the bug: should be while (!ready)
 *         monitor.wait();    // a spurious return proceeds with ready still false
 *     }
 *     consume();
 * }
 * }</pre>
 *
 * <p>The recording API cannot see the predicate, so the re-check is observed through what the
 * thread records next: another {@link #recordWaitEnter(Object) wait} on the same monitor is the
 * loop going round again and clears the return. A return that is still not followed by a wait
 * when the round ends ({@link #markInvocationStart()}) or when the run is analysed is the finding.
 *
 * <p>A return is accounted for when a notify was recorded while its wait was open: a
 * {@code notifyAll} accounts for every wait open at that moment, a {@code notify} for one of them.
 * The {@code wasNotified} flag passed to {@link #recordWaitExit(Object, boolean)} is trusted only
 * in the silencing direction: {@code true} accounts for the return, {@code false} alone is never a
 * finding (#590: it used to be the whole spurious-wakeup finding).
 *
 * <p>A notify that finds no thread waiting is <em>not</em> a finding. It is also what the correct
 * flag-then-{@code notifyAll} handshake does when the waiter has not arrived yet: the waiter finds
 * the flag set and never waits. It is kept as context in the report. A wait that begins after such
 * a notify and is never signalled is {@code MissedSignalDetector}'s finding, not this one's.
 *
 * <p>A timed wait whose caller gives up when its deadline passes also leaves an unsignalled return
 * with no second wait. The caller records that branch with {@link #recordGaveUp(Object)}, which
 * closes the return without a finding (#607); a thread that proceeds after the timeout records no
 * give-up and is still reported.
 *
 * <p><strong>Boundary.</strong> A deadline loop that does not record its give-up is reported. A
 * caller that passes {@code wasNotified = true} for a return that was not notified, or records a
 * give-up and then acts on the condition anyway, hides it. Each wait is matched to the exit
 * recorded by the same thread; an exit from a thread with no open wait changes nothing.
 */
public class WakeupDetector {

    /** A recorded wait whose exit has not been recorded yet. */
    private static final class OpenWait {
        final Thread thread;
        /** Whether a recorded notify has been assigned to this wait. */
        boolean signalled;

        OpenWait(Thread thread) {
            this.thread = thread;
        }
    }

    /** One monitor's wait/notify history. Every field is guarded by the instance's monitor. */
    private static final class MonitorState {
        final String label;
        private final List<OpenWait> openWaits = new ArrayList<>();
        /** Threads whose last return was unaccounted for and that have not waited again. */
        private final Set<Thread> returnedUnsignalled = new HashSet<>();
        private long notifies;
        private int notifiesWithNoWaiter;
        private int unsignalledReturns;
        /** Unaccounted returns a closed round left without a second wait. */
        private int proceededInClosedRounds;
        /** Unaccounted returns closed by the thread giving up at its deadline (#607). Context only. */
        private int gaveUpAtDeadline;

        MonitorState(String label) {
            this.label = label;
        }

        synchronized void waitEntered(Thread thread) {
            returnedUnsignalled.remove(thread);   // the loop went round again
            openWaits.add(new OpenWait(thread));
        }

        synchronized void waitExited(Thread thread, boolean callerSaysNotified) {
            for (int i = openWaits.size() - 1; i >= 0; i--) {
                OpenWait wait = openWaits.get(i);
                if (wait.thread.equals(thread)) { // Thread keeps Object's identity equals
                    openWaits.remove(i);
                    if (!wait.signalled && !callerSaysNotified && !takeSignalFromAnotherWait()) {
                        unsignalledReturns++;
                        returnedUnsignalled.add(thread);
                    }
                    return;
                }
            }
            // An exit with no wait recorded by this thread matches nothing and changes nothing.
        }

        /** The thread gave up instead of proceeding: its pending unaccounted return is closed. */
        synchronized void gaveUp(Thread thread) {
            if (returnedUnsignalled.remove(thread)) {
                gaveUpAtDeadline++;
            }
        }

        /**
         * A {@code notify} is assigned to the oldest open wait, but the JVM may wake a different
         * one; the return that arrives first takes the signal over.
         */
        private boolean takeSignalFromAnotherWait() {
            for (OpenWait other : openWaits) {
                if (other.signalled) {
                    other.signalled = false;
                    return true;
                }
            }
            return false;
        }

        synchronized void notified(boolean all) {
            notifies++;
            if (openWaits.isEmpty()) {
                notifiesWithNoWaiter++;
                return;
            }
            for (OpenWait wait : openWaits) {
                if (!wait.signalled) {
                    wait.signalled = true;
                    if (!all) {
                        return;
                    }
                }
            }
        }

        synchronized void closeRound() {
            proceededInClosedRounds += returnedUnsignalled.size();
            returnedUnsignalled.clear();
            // A wait still open here belongs to a body execution that has already finished.
            openWaits.clear();
        }

        synchronized void describeInto(WakeupReport report) {
            int proceeded = proceededInClosedRounds + returnedUnsignalled.size();
            if (proceeded > 0) {
                report.monitorsWithSpuriousWakeups.add(String.format(
                        "%s: %d wait(s) returned with no notify accounting for them and the thread "
                                + "went on without waiting again (%d unsignalled return(s), %d notify "
                                + "call(s) recorded)",
                        label, proceeded, unsignalledReturns, notifies));
            }
            if (notifiesWithNoWaiter > 0) {
                report.monitorsWithLostNotifications.add(String.format(
                        "%s: %d of %d notify call(s) found no thread waiting",
                        label, notifiesWithNoWaiter, notifies));
            }
            if (gaveUpAtDeadline > 0) {
                report.monitorsWithDeadlineGiveUps.add(String.format(
                        "%s: %d unsignalled return(s) closed by the thread giving up at its deadline",
                        label, gaveUpAtDeadline));
            }
        }
    }

    private final Map<IdentityKey, MonitorState> monitors = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;

    /**
     * Record that the calling thread is about to wait on a monitor. Call it inside the loop, before
     * every {@code wait()}, so that waiting again after a return is visible.
     *
     * @param monitor the object being used as a monitor, tracked by identity
     */
    public void recordWaitEnter(Object monitor) {
        if (!enabled || monitor == null) return;
        stateFor(monitor).waitEntered(Thread.currentThread());
    }

    /**
     * Record that the calling thread returned from {@code wait()}. It closes the most recent wait
     * this thread recorded on the monitor; an exit from a thread with no recorded wait is ignored.
     *
     * @param monitor the object being used as a monitor, tracked by identity
     * @param wasNotified {@code true} when the caller knows the wait was notified, which accounts
     *        for the return; {@code false} when it cannot tell, which leaves the decision to the
     *        recorded notifies and to whether the thread waits again
     */
    public void recordWaitExit(Object monitor, boolean wasNotified) {
        if (!enabled || monitor == null) return;
        MonitorState state = monitors.get(new IdentityKey(monitor));
        if (state == null) return;
        state.waitExited(Thread.currentThread(), wasNotified);
    }

    /**
     * Record that the calling thread gave up waiting on a monitor: the branch that returns without
     * the condition, typically once a timed wait's deadline has passed (#607). It closes this
     * thread's pending unaccounted return on the monitor without a finding, because the thread did
     * not act on the condition. A give-up with no such return pending closes nothing, so a later
     * return is judged on its own.
     *
     * <p>Record it on the give-up branch, not after every timed wait. A timeout alone does not say
     * what the thread did next: {@code if (!ready) monitor.wait(t); consume();} times out and then
     * proceeds, which is the defect this detector reports, so a {@code timedOut} flag on
     * {@link #recordWaitExit(Object, boolean)} would silence it.
     *
     * @param monitor the object being used as a monitor, tracked by identity
     * @since 1.12.1
     */
    public void recordGaveUp(Object monitor) {
        if (!enabled || monitor == null) return;
        MonitorState state = monitors.get(new IdentityKey(monitor));
        if (state == null) return;
        state.gaveUp(Thread.currentThread());
    }

    /**
     * Record a notify call on a monitor. A {@code notifyAll} accounts for every wait open at this
     * moment, a {@code notify} for one of them; with nobody waiting it is kept as context only.
     *
     * @param monitor the object being used as a monitor, tracked by identity
     * @param notifyAll {@code true} for {@code notifyAll()}, {@code false} for {@code notify()}
     */
    public void recordNotify(Object monitor, boolean notifyAll) {
        if (!enabled || monitor == null) return;
        stateFor(monitor).notified(notifyAll);
    }

    /**
     * Internal: called by {@code AsyncTestContext.markInvocationStart()} before each invocation
     * round, after the previous round's workers have all finished. An unsignalled return that its
     * round left without a second wait is closed as a finding here, so the same pooled thread's
     * wait in the next round is not mistaken for its re-check; waits still open are dropped.
     *
     * @since 1.12.1
     */
    public void markInvocationStart() {
        for (MonitorState state : monitors.values()) {
            state.closeRound();
        }
    }

    /**
     * Analyze wakeup patterns for issues. Analysis reads the recorded state and does not change it.
     *
     * @return the findings this detector collected during the run
     */
    public WakeupReport analyzeWakeups() {
        WakeupReport report = new WakeupReport();
        for (MonitorState state : monitors.values()) {
            state.describeInto(report);
        }
        return report;
    }

    /**
     * Standardized alias for {@link #analyzeWakeups()}.
     *
     * @return the findings this detector collected during the run
     */
    public WakeupReport analyze() {
        return analyzeWakeups();
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        monitors.clear();
    }
    /**
     * Disable.
     */
    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */
    public void enable() {
        enabled = true;
    }

    private MonitorState stateFor(Object monitor) {
        return monitors.computeIfAbsent(new IdentityKey(monitor), key -> new MonitorState(
                monitor.getClass().getSimpleName() + "@" + Integer.toHexString(key.hashCode())));
    }

    public static class WakeupReport {
        /**
         * Monitors where a wait returned with no notify accounting for it and the waiting thread
         * went on without waiting again. This is the finding.
         */
        public final Set<String> monitorsWithSpuriousWakeups = new HashSet<>();
        /**
         * Monitors notified while no thread was waiting on them. Context only, never a finding on
         * its own: the correct flag-then-{@code notifyAll} handshake does this.
         */
        public final Set<String> monitorsWithLostNotifications = new HashSet<>();
        /**
         * No longer populated. It named monitors notified while nothing waited, which is correct
         * code, and was never part of {@link #hasIssues()}; see
         * {@link #monitorsWithLostNotifications} for that context.
         *
         * @deprecated always empty since 1.12.1 (#590)
         */
        @Deprecated(since = "1.12.1")
        public final Set<String> alwaysNotifyWithoutWait = new HashSet<>();
        /**
         * Monitors where a thread closed an unsignalled return with
         * {@link WakeupDetector#recordGaveUp(Object)}: it gave up at its deadline instead of acting
         * on the condition. Context only, never a finding.
         *
         * @since 1.12.1
         */
        public final Set<String> monitorsWithDeadlineGiveUps = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !monitorsWithSpuriousWakeups.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No wakeup issues detected.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("WAIT/NOTIFY ISSUES DETECTED:\n");
            sb.append("\nWakeups acted on without a notify (wait returned unsignalled, thread did not wait again):\n");
            for (String issue : monitorsWithSpuriousWakeups) {
                sb.append("  - ").append(issue).append("\n");
            }
            if (!monitorsWithLostNotifications.isEmpty()) {
                sb.append("\nContext, not a finding (notify with no thread waiting):\n");
                for (String note : monitorsWithLostNotifications) {
                    sb.append("  - ").append(note).append("\n");
                }
            }
            if (!monitorsWithDeadlineGiveUps.isEmpty()) {
                sb.append("\nContext, not a finding (thread gave up at its deadline):\n");
                for (String note : monitorsWithDeadlineGiveUps) {
                    sb.append("  - ").append(note).append("\n");
                }
            }
            sb.append("""
                      Why: wait() can return without a notification (a spurious wakeup, or a timed wait running out).
                           A thread that proceeds after a single if-check instead of re-checking in a while loop acts
                           as if the condition is met when nobody established it, producing logic errors or data corruption.
                      Fix: Always wrap wait() in a while loop: synchronized(lock) { while (!condition) { lock.wait(); } }
                           A loop that gives up at a deadline records the give-up branch with recordGaveUp(monitor).
                    """);
            return sb.toString();
        }
    }
}
