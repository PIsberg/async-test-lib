package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects missed (lost) signals: a {@code notify()} or {@code notifyAll()} that found no thread
 * waiting, followed by a wait that received no notify of its own.
 *
 * <p>The missed-signal bug is a wait with no state predicate, entered after the signal it needs:
 *
 * <pre>{@code
 * // Thread A (runs first)
 * synchronized (monitor) {
 *     monitor.notify();   // nobody is waiting: the signal is discarded
 * }
 *
 * // Thread B (runs second)
 * synchronized (monitor) {
 *     monitor.wait();     // nothing records that A already signalled: blocks forever
 * }
 * }</pre>
 *
 * <p>A notify with nobody waiting is <em>not</em> reported on its own. It is also how correct
 * code behaves: when A sets {@code dataReady = true} before notifying and B waits in
 * {@code while (!dataReady) monitor.wait()}, B finds the flag already set and never waits, so
 * nothing is lost. What distinguishes the bug is what happens next: a wait that begins after the
 * lost notify and then receives no notify at all, because it ended unsignalled (a timed
 * {@code wait} ran out) or was still waiting when the run was analysed.
 *
 * <p>Each wait is matched to the wakeup recorded by the same thread, so a wakeup from a thread
 * that never recorded a wait changes nothing (#586: it used to decrement a shared counter and
 * erase a live waiter, making the next notify read as lost).
 *
 * <p><strong>Boundary.</strong> The recording API cannot see the predicate. A predicate loop
 * whose timed wait legitimately runs out after a lost notify (a consumer polling after the last
 * producer finished) records the same sequence and is reported; a lost notify followed by a
 * notify that reached another waiter hides a later unguarded wait. Record the monitor itself
 * ({@link #recordWait(Object)}) rather than a name where you can: named conditions share state
 * with every other monitor recorded under the same name.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 4, detectMissedSignals = true)
 * void testMissedSignal() throws InterruptedException {
 *     MissedSignalDetector detector = AsyncTestContext.missedSignalDetector();
 *
 *     synchronized (monitor) {
 *         detector.recordNotify(monitor);
 *         monitor.notify();
 *     }
 *     synchronized (monitor) {
 *         detector.recordWait(monitor);
 *         monitor.wait(100);
 *         detector.recordWakeup(monitor);
 *     }
 * }
 * }</pre>
 */
public class MissedSignalDetector {

    /** A recorded wait whose wakeup has not been recorded yet. */
    private static final class OpenWait {
        final Thread thread;
        /** The condition's notify count when the wait began. */
        final long notifiesAtStart;
        /** Whether the most recent notify before this wait found nobody waiting. */
        final boolean afterLostNotify;

        OpenWait(Thread thread, long notifiesAtStart, boolean afterLostNotify) {
            this.thread = thread;
            this.notifiesAtStart = notifiesAtStart;
            this.afterLostNotify = afterLostNotify;
        }

        /** {@return whether this wait followed a lost notify and no notify has arrived since} */
        boolean missedItsSignal(long notifiesNow) {
            return afterLostNotify && notifiesNow == notifiesAtStart;
        }
    }

    /** One condition's wait/notify history. Every field is guarded by the instance's monitor. */
    private static final class ConditionState {
        final String label;
        private final List<OpenWait> openWaits = new ArrayList<>();
        private long notifies;
        private int notifiesWithNoWaiter;
        private boolean lastNotifyLost;
        private int unsignalledWaits;

        ConditionState(String label) {
            this.label = label;
        }

        synchronized void waitStarted(Thread thread) {
            openWaits.add(new OpenWait(thread, notifies, lastNotifyLost));
        }

        synchronized void wokeUp(Thread thread) {
            for (int i = openWaits.size() - 1; i >= 0; i--) {
                OpenWait wait = openWaits.get(i);
                if (wait.thread.equals(thread)) { // Thread keeps Object's identity equals
                    openWaits.remove(i);
                    if (wait.missedItsSignal(notifies)) {
                        unsignalledWaits++;
                    }
                    return;
                }
            }
            // A wakeup with no wait recorded by this thread matches nothing and changes nothing.
        }

        synchronized void notified() {
            notifies++;
            lastNotifyLost = openWaits.isEmpty();
            if (lastNotifyLost) {
                notifiesWithNoWaiter++;
            }
        }

        synchronized void describeInto(List<String> findings) {
            int stillWaiting = 0;
            for (OpenWait wait : openWaits) {
                if (wait.missedItsSignal(notifies)) {
                    stillWaiting++;
                }
            }
            if (unsignalledWaits + stillWaiting > 0) {
                findings.add(String.format(
                        "%s: %d wait(s) began after a notify no thread was waiting for and received "
                                + "no notify of their own (%d ended unsignalled, %d still waiting; "
                                + "%d of %d notify call(s) found no waiter) — SIGNAL LOST, "
                                + "potential indefinite wait!",
                        label, unsignalledWaits + stillWaiting, unsignalledWaits, stillWaiting,
                        notifiesWithNoWaiter, notifies));
            }
        }
    }

    /** Keyed by the condition name ({@link String}) or by the monitor's {@link IdentityKey}. */
    private final Map<Object, ConditionState> conditions = new ConcurrentHashMap<>();

    // ---- Public API --------------------------------------------------------

    /**
     * Records that the calling thread is about to call {@code wait()} on the
     * named condition.  Call this while already holding the associated monitor.
     *
     * <p>Every monitor recorded under the same name shares one history; prefer
     * {@link #recordWait(Object)}.
     *
     * @param conditionName a stable logical name for the condition, e.g. {@code "dataReady"}
     */
    public void recordWait(String conditionName) {
        if (conditionName == null) return;
        byName(conditionName).waitStarted(Thread.currentThread());
    }

    /**
     * Records that the calling thread is about to call {@code wait()} on {@code monitor}.
     * State is keyed by the monitor's identity, so distinct monitors never share a history.
     *
     * @param monitor the object whose {@code wait()} is about to be called
     * @since 1.12.1
     */
    public void recordWait(Object monitor) {
        if (monitor == null) return;
        byMonitor(monitor).waitStarted(Thread.currentThread());
    }

    /**
     * Records that the calling thread returned from {@code wait()} (either via
     * a signal or a spurious wakeup / timeout). It closes the most recent wait
     * this thread recorded on the condition; a wakeup from a thread with no
     * recorded wait is ignored.
     *
     * @param conditionName the same name passed to {@link #recordWait(String)}
     */
    public void recordWakeup(String conditionName) {
        if (conditionName == null) return;
        byName(conditionName).wokeUp(Thread.currentThread());
    }

    /**
     * Records that the calling thread returned from {@code wait()} on {@code monitor}.
     *
     * @param monitor the same monitor passed to {@link #recordWait(Object)}
     * @since 1.12.1
     */
    public void recordWakeup(Object monitor) {
        if (monitor == null) return;
        byMonitor(monitor).wokeUp(Thread.currentThread());
    }

    /**
     * Records a {@code notify()} call on the named condition. A notify that finds
     * no waiter is remembered, and becomes a finding only if a later wait receives
     * no notify of its own.
     *
     * @param conditionName the name of the condition being signalled
     */
    public void recordNotify(String conditionName) {
        if (conditionName == null) return;
        byName(conditionName).notified();
    }

    /**
     * Records a {@code notify()} call on {@code monitor}.
     *
     * @param monitor the object whose {@code notify()} is being called
     * @since 1.12.1
     */
    public void recordNotify(Object monitor) {
        if (monitor == null) return;
        byMonitor(monitor).notified();
    }

    /**
     * Records a {@code notifyAll()} call on the named condition; see {@link #recordNotify(String)}.
     *
     * @param conditionName the name of the condition being signalled
     */
    public void recordNotifyAll(String conditionName) {
        recordNotify(conditionName);
    }

    /**
     * Records a {@code notifyAll()} call on {@code monitor}; see {@link #recordNotify(Object)}.
     *
     * @param monitor the object whose {@code notifyAll()} is being called
     * @since 1.12.1
     */
    public void recordNotifyAll(Object monitor) {
        recordNotify(monitor);
    }

    // ---- Analysis ----------------------------------------------------------

    /**
     * Analyses recorded signal/wait data and returns a report of conditions
     * that suffered missed signals.
     *
     * @return the findings this detector collected during the run
     */
    public MissedSignalReport analyze() {
        MissedSignalReport report = new MissedSignalReport();
        for (ConditionState state : conditions.values()) {
            state.describeInto(report.missedConditions);
        }
        return report;
    }

    // ---- Internal ----------------------------------------------------------

    private ConditionState byName(String conditionName) {
        return conditions.computeIfAbsent(conditionName, name -> new ConditionState((String) name));
    }

    private ConditionState byMonitor(Object monitor) {
        return conditions.computeIfAbsent(new IdentityKey(monitor), key -> new ConditionState(
                monitor.getClass().getSimpleName() + "@" + Integer.toHexString(key.hashCode())));
    }

    // ---- Report ------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class MissedSignalReport {

        final List<String> missedConditions = new ArrayList<>();

        /**
         * Returns {@code true} when at least one condition suffered a missed signal.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
        public boolean hasIssues() {
            return !missedConditions.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("MISSED SIGNAL ISSUES DETECTED:\n");

            if (!missedConditions.isEmpty()) {
                sb.append("  Conditions with lost notify() calls:\n");
                for (String entry : missedConditions) {
                    sb.append("    - ").append(entry).append("\n");
                }
            } else {
                sb.append("  No missed signals detected.\n");
            }

            sb.append("""
  Why: A notify() that fires before wait() is called is silently lost, and a wait() entered afterwards
     with nothing recording that the signal already happened never wakes. This is the "missed signal"
     (or "lost wakeup") problem: a wait began after a notify nobody received, and no notify reached it.
     A predicate loop whose timed wait runs out by design records the same sequence.
  Fix:
    - Always guard wait() with a state predicate in a while loop:
        synchronized(lock) { while (!ready) { lock.wait(); } }
    - The while loop re-checks the condition after every wakeup, so a signal that arrives before wait()
      is handled correctly — the thread checks the condition, sees it is true, and skips wait() entirely
    - Use notifyAll() instead of notify() to avoid leaving other waiters stranded\
""");
            return sb.toString();
        }
    }
}
