package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The waiter model of {@link ConditionVariableDetector} (#583): every await is paired with the
 * signal that could have woken it, per condition and per thread, and a timed-out await is not a
 * missing signal.
 *
 * <p>Since #666 a missing signal, and a recorded await with no lock to read it from, are notes and
 * not findings: both are decided from the body's own records. The pairing is still pinned here,
 * through the notes it produces.
 *
 * <p>Each recording runs on a single-thread executor, so "waiter A" and "waiter B" are two real
 * threads and every step happens in the order written.
 */
@DisplayName("ConditionVariableDetector: pairing awaits with signals (#583)")
class ConditionVariableDetectorModelTest {

    private static final String NAME = "data-ready";

    private final ExecutorService waiterA = Executors.newSingleThreadExecutor();
    private final ExecutorService waiterB = Executors.newSingleThreadExecutor();
    private final ExecutorService producer = Executors.newSingleThreadExecutor();

    private final ConditionVariableDetector detector = new ConditionVariableDetector();
    private final Condition condition = new ReentrantLock().newCondition();

    @AfterEach
    void shutDown() {
        waiterA.shutdownNow();
        waiterB.shutdownNow();
        producer.shutdownNow();
    }

    private static void on(ExecutorService thread, Runnable step)
            throws InterruptedException, ExecutionException, TimeoutException {
        thread.submit(step).get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a timed await that times out by design, never signalled, stays silent")
    void timedAwaitThatTimesOutByDesignStaysSilent() throws Exception {
        detector.registerCondition(condition, NAME);
        on(waiterA, () -> detector.recordAwait(condition, NAME));
        on(waiterA, () -> detector.recordAwaitExit(condition, NAME, true));
        on(waiterB, () -> detector.recordAwait(condition, NAME));
        on(waiterB, () -> detector.recordAwaitExit(condition, NAME, true));

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "a poll that waits a bounded time and gives up is not waiting for a signal that "
                        + "was lost; nothing here is wrong. Report:\n" + report);
    }

    @Test
    @DisplayName("one signal does not silence a second waiter that nothing signalled")
    void oneSignalDoesNotSilenceASecondUnsignalledWaiter() throws Exception {
        detector.registerCondition(condition, NAME);
        on(waiterA, () -> detector.recordAwait(condition, NAME));
        on(waiterB, () -> detector.recordAwait(condition, NAME));
        on(producer, () -> detector.recordSignal(condition, NAME, false));   // wakes one of two
        on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false));
        on(waiterB, () -> detector.recordAwaitExit(condition, NAME, false));

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "a missing signal is a note since #666. Report:\n" + report);
        assertEquals(1, report.unsignalledWakeups.size(),
                "two awaits returned as woken and only one signal() was delivered while they "
                        + "waited, so one wakeup had no signal behind it. Report:\n" + report);
    }

    @Test
    @DisplayName("an await that returns as woken with no signal ever recorded is noted, not reported (#666)")
    void wokenAwaitWithNoSignalIsNoted() throws Exception {
        detector.registerCondition(condition, NAME);
        on(waiterA, () -> detector.recordAwait(condition, NAME));
        on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false));

        var report = detector.analyze();
        assertFalse(report.hasIssues(), report.toString());
        assertEquals(1, report.unsignalledWakeups.size(),
                "an await that returned as woken with nothing signalling the condition. Report:\n" + report);
    }

    @Test
    @DisplayName("a signal made before the await does not credit that later await")
    void signalBeforeTheAwaitDoesNotCreditIt() throws Exception {
        detector.registerCondition(condition, NAME);
        on(producer, () -> detector.recordSignal(condition, NAME, true));
        on(waiterA, () -> detector.recordAwait(condition, NAME));
        on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false));

        assertEquals(1, detector.analyze().unsignalledWakeups.size(),
                "the only signal went out while nobody waited, so it cannot have woken the await "
                        + "that started afterwards");
    }

    @Test
    @DisplayName("signalAll wakes every current waiter: both exits are paired")
    void signalAllCreditsEveryCurrentWaiter() throws Exception {
        detector.registerCondition(condition, NAME);
        on(waiterA, () -> detector.recordAwait(condition, NAME));
        on(waiterB, () -> detector.recordAwait(condition, NAME));
        on(producer, () -> detector.recordSignal(condition, NAME, true));
        on(waiterB, () -> detector.recordAwaitExit(condition, NAME, false));
        on(waiterA, () -> detector.recordAwaitExit(condition, NAME, false));

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "signalAll() woke both waiters. Report:\n" + report);
    }

    @Test
    @DisplayName("signalling into an empty condition, the producer-first handshake, stays silent")
    void signalWithNoWaiterIsNotAFinding() throws Exception {
        detector.registerCondition(condition, NAME);
        // Producer wins the race: sets its flag and signals before any consumer awaits. The
        // consumer tests its predicate first, finds it true, and never awaits at all.
        on(producer, () -> detector.recordSignal(condition, NAME, false));

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "a signal with nobody waiting is how correct predicate-guarded code runs whenever "
                        + "the producer gets there first. Report:\n" + report);
    }

    @Test
    @DisplayName("an exit with no matching await does not skew the waiter count")
    void unmatchedExitIsIgnored() throws Exception {
        detector.registerCondition(condition, NAME);
        on(waiterB, () -> detector.recordAwaitExit(condition, NAME, true));   // never awaited
        on(waiterA, () -> detector.recordAwait(condition, NAME));

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "no lock registered: a note since #666. Report:\n" + report);
        assertEquals(1, report.unconfirmedWaits.size(),
                "the one thread still inside an await must still be counted. Report:\n" + report);
        assertTrue(report.unconfirmedWaits.get(0).contains(": 1 thread"), report.toString());
    }

    @Test
    @DisplayName("a timed-out waiter does not strand the signal meant for the waiter that woke")
    void timedOutWaiterDoesNotStrandTheSignal() throws Exception {
        detector.registerCondition(condition, NAME);
        on(waiterA, () -> detector.recordAwait(condition, NAME));
        on(waiterB, () -> detector.recordAwait(condition, NAME));
        on(producer, () -> detector.recordSignal(condition, NAME, false));
        on(waiterA, () -> detector.recordAwaitExit(condition, NAME, true));   // gave up
        on(waiterB, () -> detector.recordAwaitExit(condition, NAME, false));  // got the signal

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "one signal, one woken waiter. Report:\n" + report);
    }
}
