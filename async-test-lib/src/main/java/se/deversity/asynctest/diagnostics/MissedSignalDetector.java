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
 * <p><strong>Say whether the wait is guarded.</strong> The detector cannot see the predicate, so
 * {@link #recordWait(Object, boolean)} asks the caller (#599). A guarded wait is never reported:
 * its loop re-tests the state a lost notify would have changed, so a consumer polling after the
 * last producer finished, whose timed wait runs out by design, stays silent. An unguarded wait is
 * judged against every notify lost before it, so a lost notify followed by one that reached some
 * other waiter no longer hides it. The forms that do not say ({@link #recordWait(Object)},
 * {@link #recordWait(String)}) keep the #586 rule: a wait is judged on the notify just before it,
 * which reports the guarded poll above and misses the hidden unguarded wait. Record the monitor
 * itself rather than a name where you can: named conditions share state with every other monitor
 * recorded under the same name.
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
 *         detector.recordWait(monitor, false); // no predicate loop around this wait
 *         monitor.wait(100);
 *         detector.recordWakeup(monitor);
 *     }
 * }
 * }</pre>
 */
public class MissedSignalDetector {

    /** What the caller said about the loop around a wait. */
    private enum Guard {
        /** Recorded without saying: decided on the notify just before the wait (#586). */
        UNKNOWN,
        /** Inside a loop that re-tests the state predicate: never a missed signal (#599). */
        GUARDED,
        /** No predicate: judged against every notify lost before the wait (#599). */
        UNGUARDED
    }

    /** A recorded wait whose wakeup has not been recorded yet. */
    private static final class OpenWait {
        final Thread thread;
        Guard guard;
        /** The condition's notify count when the wait began. */
        final long notifiesAtStart;
        /** Whether the most recent notify before this wait found nobody waiting. */
        final boolean afterLostNotify;
        /** How many notifies before this wait found nobody waiting. */
        final int lostNotifiesAtStart;

        OpenWait(Thread thread, Guard guard, long notifiesAtStart, boolean afterLostNotify,
                 int lostNotifiesAtStart) {
            this.thread = thread;
            this.guard = guard;
            this.notifiesAtStart = notifiesAtStart;
            this.afterLostNotify = afterLostNotify;
            this.lostNotifiesAtStart = lostNotifiesAtStart;
        }

        /** {@return whether this wait needed a notify that was lost and none has arrived since} */
        boolean missedItsSignal(long notifiesNow) {
            boolean noNotifySince = notifiesNow == notifiesAtStart;
            return switch (guard) {
                // The loop re-tests the state a lost notify would have changed, so the notify it
                // did not see cannot strand it; a guarded poll that times out is how it ends.
                case GUARDED -> false;
                // A later notify consumed by another waiter does not give back one already lost.
                case UNGUARDED -> lostNotifiesAtStart > 0 && noNotifySince;
                case UNKNOWN -> afterLostNotify && noNotifySince;
            };
        }
    }

    /** A wait that completed, tracked until analysis to observe whether a predicate recheck occurs (#635). */
    private static final class CompletedWait {
        final Thread thread;
        Guard guard;
        final long notifiesAtStart;
        final boolean afterLostNotify;
        final int lostNotifiesAtStart;
        final long notifiesAtWakeup;

        CompletedWait(Thread thread, Guard guard, long notifiesAtStart, boolean afterLostNotify,
                      int lostNotifiesAtStart, long notifiesAtWakeup) {
            this.thread = thread;
            this.guard = guard;
            this.notifiesAtStart = notifiesAtStart;
            this.afterLostNotify = afterLostNotify;
            this.lostNotifiesAtStart = lostNotifiesAtStart;
            this.notifiesAtWakeup = notifiesAtWakeup;
        }

        boolean missedItsSignal() {
            boolean noNotifySince = notifiesAtWakeup == notifiesAtStart;
            return switch (guard) {
                case GUARDED -> false;
                case UNGUARDED -> lostNotifiesAtStart > 0 && noNotifySince;
                case UNKNOWN -> afterLostNotify && noNotifySince;
            };
        }
    }

    /** One condition's wait/notify history. Every field is guarded by the instance's monitor. */
    private static final class ConditionState {
        final String label;
        private final List<OpenWait> openWaits = new ArrayList<>();
        private final List<CompletedWait> completedWaits = new ArrayList<>();
        private long notifies;
        private int notifiesWithNoWaiter;
        private boolean lastNotifyLost;

        ConditionState(String label) {
            this.label = label;
        }

        synchronized void waitStarted(Thread thread, Guard guard) {
            openWaits.add(new OpenWait(thread, guard, notifies, lastNotifyLost, notifiesWithNoWaiter));
        }

        synchronized void wokeUp(Thread thread) {
            for (int i = openWaits.size() - 1; i >= 0; i--) {
                OpenWait wait = openWaits.get(i);
                if (wait.thread.equals(thread)) { // Thread keeps Object's identity equals
                    openWaits.remove(i);
                    completedWaits.add(new CompletedWait(thread, wait.guard, wait.notifiesAtStart,
                            wait.afterLostNotify, wait.lostNotifiesAtStart, notifies));
                    return;
                }
            }
            // A wakeup with no wait recorded by this thread matches nothing and changes nothing.
        }

        synchronized void predicateChecked(Thread thread) {
            for (int i = completedWaits.size() - 1; i >= 0; i--) {
                CompletedWait wait = completedWaits.get(i);
                if (wait.thread.equals(thread)) {
                    wait.guard = Guard.GUARDED;
                    break;
                }
            }
            for (int i = openWaits.size() - 1; i >= 0; i--) {
                OpenWait wait = openWaits.get(i);
                if (wait.thread.equals(thread)) {
                    wait.guard = Guard.GUARDED;
                    break;
                }
            }
        }

        synchronized void notified() {
            notifies++;
            lastNotifyLost = openWaits.isEmpty();
            if (lastNotifyLost) {
                notifiesWithNoWaiter++;
            }
        }

        synchronized void describeInto(List<String> findings) {
            int unsignalledWaits = 0;
            for (CompletedWait wait : completedWaits) {
                if (wait.missedItsSignal()) {
                    unsignalledWaits++;
                }
            }
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
        byName(conditionName).waitStarted(Thread.currentThread(), Guard.UNKNOWN);
    }

    /**
     * Records that the calling thread is about to call {@code wait()} on {@code monitor}.
     * State is keyed by the monitor's identity, so distinct monitors never share a history.
     *
     * <p>This form does not say whether the wait is predicate-guarded, so it is decided on the
     * notify just before it; prefer {@link #recordWait(Object, boolean)}.
     *
     * @param monitor the object whose {@code wait()} is about to be called
     * @since 1.12.1
     */
    public void recordWait(Object monitor) {
        if (monitor == null) return;
        byMonitor(monitor).waitStarted(Thread.currentThread(), Guard.UNKNOWN);
    }

    /**
     * Records that the calling thread is about to call {@code wait()} on {@code monitor}, and
     * whether that wait sits in a loop that re-tests a state predicate under the monitor
     * ({@code while (!ready) monitor.wait(...)}).
     *
     * <p>A {@code guarded} wait is never reported by this detector: its loop re-tests the state a
     * lost notify would have changed, so the notify it did not see cannot strand it, and a guarded
     * timed wait that runs out is how a polling consumer ends. An unguarded wait is reported when
     * any earlier notify on the monitor found nobody waiting and no notify arrives while it waits;
     * a later notify consumed by another waiter does not give back the one that was lost.
     *
     * @param monitor the object whose {@code wait()} is about to be called
     * @param guarded {@code true} when the wait is inside a loop re-testing its predicate
     * @since 1.12.1
     */
    public void recordWait(Object monitor, boolean guarded) {
        if (monitor == null) return;
        byMonitor(monitor).waitStarted(Thread.currentThread(),
                guarded ? Guard.GUARDED : Guard.UNGUARDED);
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
     * Records that the calling thread evaluated the condition's state predicate (e.g. in a
     * {@code while (!ready)} loop). A predicate check observed after {@link #recordWakeup} confirms
     * that the wait re-tests its condition, making it predicate-guarded (#635).
     *
     * @param conditionName the name of the condition
     * @param satisfied {@code true} if the condition predicate was satisfied, {@code false} if not
     * @since 1.12.1
     */
    public void recordPredicateCheck(String conditionName, boolean satisfied) {
        if (conditionName == null) return;
        byName(conditionName).predicateChecked(Thread.currentThread());
    }

    /**
     * Records that the calling thread evaluated the monitor's state predicate. A predicate check
     * observed after {@link #recordWakeup(Object)} confirms that the wait re-tests its condition,
     * making it predicate-guarded (#635).
     *
     * @param monitor the monitor object
     * @param satisfied {@code true} if the condition predicate was satisfied, {@code false} if not
     * @since 1.12.1
     */
    public void recordPredicateCheck(Object monitor, boolean satisfied) {
        if (monitor == null) return;
        byMonitor(monitor).predicateChecked(Thread.currentThread());
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
