package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p><strong>Or record the loop.</strong> A caller that records every predicate evaluation with
 * {@link #recordPredicateCheck(Object, boolean)} (#635) lets the detector confirm an undeclared
 * wait as a loop's from the waiting thread's own next events on the condition, in the same
 * invocation round (#656): a check right after the wakeup that finds the predicate satisfied, or
 * one that finds it unsatisfied followed by another wait. A wait reached through that back-edge is
 * also confirmed when its last check is unsatisfied and no wait follows, which is how a bounded
 * poll gives up. Anything else closes the window: a notify by the waiter, a wait with no check
 * before it, or the end of the round. {@code if (!ready) wait()} followed later by a check that
 * finds {@code ready} still false is therefore reported. A wait that can no longer be confirmed is
 * folded into a count, so the detector keeps at most one woken wait per thread per condition.
 *
 * <p>What the recorded sequence alone cannot distinguish, because the calls are identical:
 * {@code if (!ready) wait()} followed later in the same body by a check that finds {@code ready}
 * true reads as a loop that exited; and two consecutive {@code if (!ready) wait()} blocks read as a
 * loop that waited again.
 *
 * <p><strong>Or mark the loops.</strong> {@link #recordLoopStart(Object)} before a
 * {@code while (!ready)} loop and {@link #recordLoopEnd(Object)} after it (in a {@code finally})
 * give the detector the back-edge the calls do not carry (#669). Marks are per monitor: once any
 * thread has marked a loop on a monitor, a wait on it recorded without a {@code guarded} flag is a
 * loop's when its thread is inside a marked loop on that monitor, and an {@code if}'s otherwise,
 * judged like {@code recordWait(monitor, false)} whatever predicate checks surround it. Both shapes
 * above are then reported. A monitor nobody marks keeps the reading of the paragraph above, and a
 * wait recorded before the monitor's first mark is read that way too, so mark a loop before the
 * waits it should decide. An explicit {@code guarded} flag still wins over a mark.
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
        final Guard guard;
        /** The condition's notify count when the wait began. */
        final long notifiesAtStart;
        /** Whether the most recent notify before this wait found nobody waiting. */
        final boolean afterLostNotify;
        /** How many notifies before this wait found nobody waiting. */
        final int lostNotifiesAtStart;
        /** Whether this wait is the loop's back-edge: its thread re-tested and waited again. */
        final boolean loopObserved;

        OpenWait(Thread thread, Guard guard, long notifiesAtStart, boolean afterLostNotify,
                 int lostNotifiesAtStart, boolean loopObserved) {
            this.thread = thread;
            this.guard = guard;
            this.notifiesAtStart = notifiesAtStart;
            this.afterLostNotify = afterLostNotify;
            this.lostNotifiesAtStart = lostNotifiesAtStart;
            this.loopObserved = loopObserved;
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

    /**
     * An undeclared wait its thread has woken from, which that thread's next events on the
     * condition in the same round may still confirm as a loop's (#635, #656).
     */
    private static final class PendingWait {
        /** Whether the wait, judged unguarded, needed a lost notify and none arrived before the wakeup. */
        final boolean missedItsSignal;
        /** Whether the wait itself followed a back-edge of its thread's loop. */
        final boolean loopObserved;
        /** The invocation round the wakeup was recorded in. */
        final long epoch;
        /** Whether a check after the wakeup found the predicate unsatisfied. */
        boolean checkedUnsatisfied;

        PendingWait(boolean missedItsSignal, boolean loopObserved, long epoch) {
            this.missedItsSignal = missedItsSignal;
            this.loopObserved = loopObserved;
            this.epoch = epoch;
        }

        /**
         * {@return whether the wait was a loop's when no further wait follows} A loop that has
         * already waited again and now finds its predicate still unsatisfied is a bounded poll
         * giving up; a first wait with no back-edge behind it has shown no loop at all.
         */
        boolean confirmedWithoutAnotherWait() {
            return checkedUnsatisfied && loopObserved;
        }
    }

    /** One condition's wait/notify history. Every field is guarded by the instance's monitor. */
    private static final class ConditionState {
        final String label;
        private final List<OpenWait> openWaits = new ArrayList<>();
        /**
         * At most one per thread: the latest undeclared wait it woke from, kept only until the
         * thread's next event on this condition or the end of its round settles it.
         */
        private final Map<Thread, PendingWait> pendingWaits = new HashMap<>();
        /** Settled waits that ended without the notify they needed. */
        private int unsignalledWaits;
        private long notifies;
        private int notifiesWithNoWaiter;
        private boolean lastNotifyLost;
        /** Whether any thread has marked a loop on this condition; its marks then decide (#669). */
        private boolean loopsMarked;
        /** How many marked loops each thread is inside; a thread leaves the map at depth zero. */
        private final Map<Thread, Integer> openLoops = new HashMap<>();

        ConditionState(String label) {
            this.label = label;
        }

        synchronized void loopStarted(Thread thread) {
            loopsMarked = true;
            openLoops.merge(thread, 1, Integer::sum);
        }

        synchronized void loopEnded(Thread thread) {
            // An end with no start finds no entry and closes nothing; at depth one the null
            // result removes the thread, so the map only ever holds threads inside a loop.
            openLoops.computeIfPresent(thread, (t, depth) -> depth <= 1 ? null : depth - 1);
        }

        synchronized void waitStarted(Thread thread, Guard declared, long epoch) {
            Guard guard = declared;
            if (guard == Guard.UNKNOWN && loopsMarked) {
                // The marks carry the back-edge: inside one the wait is a loop's, outside an if's.
                guard = openLoops.containsKey(thread) ? Guard.GUARDED : Guard.UNGUARDED;
            }
            PendingWait previous = pendingWaits.remove(thread);
            boolean backEdge = false;
            if (previous != null) {
                // Re-tested after the wakeup, found the predicate false, and waited again: a loop.
                backEdge = previous.epoch == epoch && previous.checkedUnsatisfied;
                settle(previous, backEdge || previous.confirmedWithoutAnotherWait());
            }
            openWaits.add(new OpenWait(thread, guard, notifies, lastNotifyLost, notifiesWithNoWaiter,
                    backEdge));
        }

        synchronized void wokeUp(Thread thread, long epoch) {
            for (int i = openWaits.size() - 1; i >= 0; i--) {
                OpenWait wait = openWaits.get(i);
                if (wait.thread.equals(thread)) { // Thread keeps Object's identity equals
                    openWaits.remove(i);
                    boolean missed = wait.missedItsSignal(notifies);
                    if (wait.guard != Guard.UNKNOWN) {
                        if (missed) {
                            unsignalledWaits++; // the caller declared it: nothing left to confirm
                        }
                        return;
                    }
                    PendingWait replaced = pendingWaits.put(thread,
                            new PendingWait(missed, wait.loopObserved, epoch));
                    if (replaced != null) { // waitStarted settled it already; kept for safety
                        settle(replaced, replaced.confirmedWithoutAnotherWait());
                    }
                    return;
                }
            }
            // A wakeup with no wait recorded by this thread matches nothing and changes nothing.
        }

        /**
         * Only the thread's own next events after a wakeup, in the same round, can confirm the wait
         * as a loop's: a check that finds the predicate satisfied (the loop exits), or a check that
         * finds it unsatisfied followed by another wait (the back-edge, settled in
         * {@link #waitStarted}). A check in a later round is that round's own test, and an
         * explicit {@code recordWait(monitor, false)} never became pending.
         */
        synchronized void predicateChecked(Thread thread, boolean satisfied, long epoch) {
            PendingWait pending = pendingWaits.get(thread);
            if (pending == null) {
                return; // a test before this thread's wait, or after its wait was settled
            }
            if (pending.epoch != epoch) {
                pendingWaits.remove(thread);
                settle(pending, pending.confirmedWithoutAnotherWait());
            } else if (satisfied) {
                pendingWaits.remove(thread);
                settle(pending, true);
            } else {
                pending.checkedUnsatisfied = true;
            }
        }

        synchronized void notified(Thread thread) {
            // A loop does not signal between its wakeup and its re-test: the body has moved on.
            PendingWait pending = pendingWaits.remove(thread);
            if (pending != null) {
                settle(pending, pending.confirmedWithoutAnotherWait());
            }
            notifies++;
            lastNotifyLost = openWaits.isEmpty();
            if (lastNotifyLost) {
                notifiesWithNoWaiter++;
            }
        }

        /** Settles every woken wait from a round before {@code epoch}: nothing can confirm it now. */
        synchronized void closeRoundsBefore(long epoch) {
            for (Iterator<PendingWait> it = pendingWaits.values().iterator(); it.hasNext(); ) {
                PendingWait pending = it.next();
                if (pending.epoch < epoch) {
                    it.remove();
                    settle(pending, pending.confirmedWithoutAnotherWait());
                }
            }
        }

        /** Folds a wait that can no longer change into the count; must hold the monitor. */
        private void settle(PendingWait wait, boolean confirmedLoop) {
            if (!confirmedLoop && wait.missedItsSignal) {
                unsignalledWaits++;
            }
        }

        synchronized int retainedWaits() {
            return openWaits.size() + pendingWaits.size() + openLoops.size();
        }

        synchronized void describeInto(List<String> findings) {
            int ended = unsignalledWaits;
            for (PendingWait wait : pendingWaits.values()) {
                if (wait.missedItsSignal && !wait.confirmedWithoutAnotherWait()) {
                    ended++;
                }
            }
            int stillWaiting = 0;
            for (OpenWait wait : openWaits) {
                if (wait.missedItsSignal(notifies)) {
                    stillWaiting++;
                }
            }
            if (ended + stillWaiting > 0) {
                findings.add(String.format(
                        "%s: %d wait(s) began after a notify no thread was waiting for and received "
                                + "no notify of their own (%d ended unsignalled, %d still waiting; "
                                + "%d of %d notify call(s) found no waiter) — SIGNAL LOST, "
                                + "potential indefinite wait!",
                        label, ended + stillWaiting, ended, stillWaiting,
                        notifiesWithNoWaiter, notifies));
            }
        }
    }

    /** Keyed by the condition name ({@link String}) or by the monitor's {@link IdentityKey}. */
    private final Map<Object, ConditionState> conditions = new ConcurrentHashMap<>();
    /** Bumped at the start of every invocation round; read on the recording threads. */
    private final AtomicLong invocationEpoch = new AtomicLong();

    // ---- Public API --------------------------------------------------------

    /**
     * Internal: called at the start of each invocation round. A predicate check confirms only a
     * wait from its own round, so a pooled worker's {@code if (!ready)} in the next round does not
     * read as a re-test of the wait it made in this one; every woken wait of the closed round is
     * settled into a count here, so memory does not grow with the number of waits (#656).
     *
     * @since 1.12.1
     */
    public void markInvocationStart() {
        long epoch = invocationEpoch.incrementAndGet();
        if (conditions.isEmpty()) {
            return;
        }
        for (ConditionState state : conditions.values()) {
            state.closeRoundsBefore(epoch);
        }
    }

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
        byName(conditionName).waitStarted(Thread.currentThread(), Guard.UNKNOWN, invocationEpoch.get());
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
        byMonitor(monitor).waitStarted(Thread.currentThread(), Guard.UNKNOWN, invocationEpoch.get());
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
                guarded ? Guard.GUARDED : Guard.UNGUARDED, invocationEpoch.get());
    }

    /**
     * Marks the start of a loop that re-tests a state predicate around waits on {@code monitor},
     * on the calling thread: call it before {@code while (!ready)}, and
     * {@link #recordLoopEnd(Object)} after the loop, in a {@code finally} (#669).
     *
     * <p>The mark is the loop's back-edge, which the other recorded calls do not carry. Once any
     * thread has marked a loop on a monitor, every wait on it recorded without a {@code guarded}
     * flag is decided by the marks: a wait while its thread is inside a marked loop on the monitor
     * is guarded and never reported, and a wait outside every mark is judged as unguarded, like
     * {@link #recordWait(Object, boolean) recordWait(monitor, false)}, whatever predicate checks
     * surround it. So {@code if (!ready) wait()} followed later by a check that finds {@code ready}
     * true, and two consecutive {@code if (!ready) wait()} blocks, are reported after a lost notify
     * where the marked loop stays silent. A monitor with no mark keeps the
     * {@link #recordPredicateCheck(Object, boolean)} reading, and so does a wait recorded before the
     * monitor's first mark. An explicit {@code guarded} flag is never overridden.
     *
     * <pre>{@code
     * synchronized (monitor) {
     *     detector.recordLoopStart(monitor);
     *     try {
     *         while (!ready) {
     *             detector.recordWait(monitor);
     *             monitor.wait(100);
     *             detector.recordWakeup(monitor);
     *         }
     *     } finally {
     *         detector.recordLoopEnd(monitor);
     *     }
     * }
     * }</pre>
     *
     * <p>Loops nest: a thread is inside a marked loop until it records as many ends as starts. A
     * start left unclosed keeps that thread's later waits on the monitor guarded, which errs towards
     * silence.
     *
     * @param monitor the object whose {@code wait()} the loop calls; {@code null} is ignored
     * @since 1.12.2
     */
    public void recordLoopStart(Object monitor) {
        if (monitor == null) return;
        byMonitor(monitor).loopStarted(Thread.currentThread());
    }

    /**
     * Marks the end of a loop the calling thread started with {@link #recordLoopStart(Object)} on
     * {@code monitor} (#669). An end with no matching start changes nothing.
     *
     * @param monitor the same monitor passed to {@link #recordLoopStart(Object)}; {@code null} is
     *                ignored
     * @since 1.12.2
     */
    public void recordLoopEnd(Object monitor) {
        if (monitor == null) return;
        byMonitor(monitor).loopEnded(Thread.currentThread());
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
        byName(conditionName).wokeUp(Thread.currentThread(), invocationEpoch.get());
    }

    /**
     * Records that the calling thread returned from {@code wait()} on {@code monitor}.
     *
     * @param monitor the same monitor passed to {@link #recordWait(Object)}
     * @since 1.12.1
     */
    public void recordWakeup(Object monitor) {
        if (monitor == null) return;
        byMonitor(monitor).wokeUp(Thread.currentThread(), invocationEpoch.get());
    }

    /**
     * Records that the calling thread evaluated the condition's state predicate, as a
     * {@code while (!ready)} loop does before its first wait and after every wakeup. Record every
     * evaluation, with its outcome.
     *
     * <p>A wait recorded without a {@code guarded} flag is confirmed as a loop's only by its own
     * thread's next events on the condition in the same invocation round (#656): a check that
     * finds the predicate satisfied right after the wakeup (the loop exits), or a check that finds
     * it unsatisfied followed by another wait (the loop's back-edge). A wait reached through such a
     * back-edge is also confirmed when its last check finds the predicate unsatisfied and no wait
     * follows, which is how a bounded poll gives up. A notify by the same thread, a wait with no
     * check before it, or the end of the round closes the window, and the wait is judged by the
     * #586 rule. It never overrides a wait recorded with an explicit {@code guarded} flag.
     *
     * @param conditionName the name of the condition
     * @param satisfied {@code true} if the predicate held, so the loop would exit
     * @since 1.12.1
     */
    public void recordPredicateCheck(String conditionName, boolean satisfied) {
        if (conditionName == null) return;
        byName(conditionName).predicateChecked(Thread.currentThread(), satisfied, invocationEpoch.get());
    }

    /**
     * Records that the calling thread evaluated the monitor's state predicate; see
     * {@link #recordPredicateCheck(String, boolean)} for what confirms a wait as a loop's and what
     * stays indistinguishable (#635, #656). It never overrides a wait recorded with
     * {@link #recordWait(Object, boolean)}.
     *
     * @param monitor the monitor object
     * @param satisfied {@code true} if the predicate held, so the loop would exit
     * @since 1.12.1
     */
    public void recordPredicateCheck(Object monitor, boolean satisfied) {
        if (monitor == null) return;
        byMonitor(monitor).predicateChecked(Thread.currentThread(), satisfied, invocationEpoch.get());
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
        byName(conditionName).notified(Thread.currentThread());
    }

    /**
     * Records a {@code notify()} call on {@code monitor}.
     *
     * @param monitor the object whose {@code notify()} is being called
     * @since 1.12.1
     */
    public void recordNotify(Object monitor) {
        if (monitor == null) return;
        byMonitor(monitor).notified(Thread.currentThread());
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

    /** {@return how many wait records all conditions still hold}; for the memory-bound test. */
    int retainedWaits() {
        int retained = 0;
        for (ConditionState state : conditions.values()) {
            retained += state.retainedWaits();
        }
        return retained;
    }

    private ConditionState byName(String conditionName) {
        return conditions.computeIfAbsent(conditionName, name -> new ConditionState((String) name));
    }

    private ConditionState byMonitor(Object monitor) {
        return conditions.computeIfAbsent(new IdentityKey(monitor),
                key -> new ConditionState(key.toString()));
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
