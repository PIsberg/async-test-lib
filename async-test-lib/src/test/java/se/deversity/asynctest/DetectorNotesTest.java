package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ConditionVariableDetector;
import se.deversity.asynctest.diagnostics.ExchangerDetector;
import se.deversity.asynctest.diagnostics.ReentrantLockDetector;
import se.deversity.asynctest.diagnostics.SynchronizedNonFinalDetector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A detector note that is not a finding reaches {@link AsyncTestContext#detectorNotes()}, from
 * which the runner logs it, because a report with no finding is never printed (#816).
 */
class DetectorNotesTest {

    /** A non-final instance lock field: several monitors are undecided without the instance. */
    private static final class Holder {
        private Object lock = new Object();
    }

    private static AsyncTestContext context() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectSynchronizedNonFinal(true).build());
    }

    @Test
    void aNoteInAReportWithNoFindingIsCollectedOnce() {
        AsyncTestContext ctx = context();
        SynchronizedNonFinalDetector detector = ctx.synchronizedNonFinalDetector;
        for (int i = 0; i < 3; i++) {
            Holder holder = new Holder();
            detector.recordLockObject(holder.lock, "lock", Holder.class);
        }

        Map<String, String> reports = ctx.analyzeAllNamed();
        assertTrue(reports.isEmpty(), "three instances with a lock each are no finding: " + reports);
        List<String> notes = ctx.detectorNotes().get("SynchronizedNonFinalDetector");
        assertEquals(1, notes == null ? 0 : notes.size(),
            "one undecided slot is one note, keyed by the detector that wrote it: " + ctx.detectorNotes());
        assertTrue(notes.get(0).contains("recordLockObject(lock, \"lock\", Holder.class, this)"),
            "the note names the call that decides the slot: " + notes);
    }

    @Test
    void aReportWithAFindingKeepsItsNotesInItsOwnText() {
        AsyncTestContext ctx = context();
        SynchronizedNonFinalDetector detector = ctx.synchronizedNonFinalDetector;
        Holder reassigned = new Holder();
        detector.recordLockObject(reassigned.lock, "lock", Holder.class, reassigned);
        reassigned.lock = new Object();
        detector.recordLockObject(reassigned.lock, "lock", Holder.class, reassigned);
        for (int i = 0; i < 3; i++) {
            Holder holder = new Holder();
            detector.recordLockObject(holder.lock, "lock", Holder.class);
        }

        Map<String, String> reports = ctx.analyzeAllNamed();
        String report = reports.get("SynchronizedNonFinalDetector");
        assertTrue(report != null && report.contains("NOT FINAL"),
            "one instance reassigning its lock is a finding, reported as before: " + reports);
        assertTrue(report.contains("recordLockObject(lock, \"lock\", Holder.class, this)"),
            "and the printed report carries the note beside it: " + report);
        assertTrue(ctx.detectorNotes().isEmpty(),
            "so the note is not collected a second time for the log: " + ctx.detectorNotes());
    }

    @Test
    void aRunWithNoNotesCollectsNone() {
        AsyncTestContext ctx = context();
        Holder holder = new Holder();
        ctx.synchronizedNonFinalDetector.recordLockObject(holder.lock, "lock", Holder.class);

        assertTrue(ctx.analyzeAllNamed().isEmpty());
        assertTrue(ctx.detectorNotes().isEmpty(), "one monitor is nothing to note: " + ctx.detectorNotes());
    }

    // ---- The detectors that opted in after SynchronizedNonFinal (#816). Each note asks the caller
    // ---- to change a recording or a registration; background for a finding stays out.

    private static AsyncTestContext conditionVariableContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectConditionVariableIssues(true).build());
    }

    @Test
    void aConditionRegisteredWithoutItsLockAndLeftWaitingIsANote() {
        AsyncTestContext ctx = conditionVariableContext();
        ConditionVariableDetector detector = ctx.conditionVariableDetector;
        Condition condition = new ReentrantLock().newCondition();
        detector.registerCondition(condition, "data-ready");
        detector.recordAwait(condition, "data-ready");

        assertTrue(ctx.analyzeAllNamed().isEmpty(), "an unconfirmed wait is no finding");
        List<String> notes = ctx.detectorNotes().get("ConditionVariableDetector");
        assertEquals(1, notes == null ? 0 : notes.size(),
            "the open wait nothing can confirm is one note for the caller: " + ctx.detectorNotes());
        assertTrue(notes.get(0).contains("registerCondition(lock, condition, ready, name)"),
            "and it names the registration that would let the lock decide it: " + notes);
    }

    @Test
    void aConditionRegisteredWithALockThatDidNotMakeItIsANote() {
        AsyncTestContext ctx = conditionVariableContext();
        Condition condition = new ReentrantLock().newCondition();
        ctx.conditionVariableDetector.registerCondition(new ReentrantLock(), condition, () -> true, "data-ready");

        assertTrue(ctx.analyzeAllNamed().isEmpty(), "a registration that cannot be read is no finding");
        List<String> notes = ctx.detectorNotes().get("ConditionVariableDetector");
        assertEquals(1, notes == null ? 0 : notes.size(),
            "the wrong lock is one note, since nothing on this condition can ever be decided: "
                + ctx.detectorNotes());
        assertTrue(notes.get(0).contains("register the lock whose newCondition() made it"), notes.toString());
    }

    @Test
    void conditionBackgroundOnACleanRunIsNotANote() {
        AsyncTestContext ctx = conditionVariableContext();
        ConditionVariableDetector detector = ctx.conditionVariableDetector;
        ReentrantLock lock = new ReentrantLock();
        Condition condition = lock.newCondition();
        detector.registerCondition(lock, condition, () -> true, "data-ready");
        detector.recordSignal(condition, "data-ready", true);     // nobody waiting: correct code
        detector.recordAwait(condition, "data-ready");
        detector.recordAwaitExit(condition, "data-ready", false); // woken with no signal behind it

        assertTrue(ctx.analyzeAllNamed().isEmpty());
        assertTrue(ctx.detectorNotes().isEmpty(),
            "a signal with no waiter and an unsignalled wakeup are what correct code produces too, so "
                + "they are background for a finding, not something the caller should change: "
                + ctx.detectorNotes());
    }

    @Test
    void aConditionReportWithAFindingKeepsTheNoteInItsText() throws InterruptedException {
        AsyncTestContext ctx = conditionVariableContext();
        ConditionVariableDetector detector = ctx.conditionVariableDetector;
        ReentrantLock lock = new ReentrantLock();
        Condition stuck = lock.newCondition();
        detector.registerCondition(lock, stuck, () -> true, "stuck");
        Condition misregistered = new ReentrantLock().newCondition();
        detector.registerCondition(lock, misregistered, () -> true, "misregistered");
        Thread waiter = parkOn(lock, stuck);
        try {
            String report = ctx.analyzeAllNamed().get("ConditionVariableDetector");
            assertTrue(report != null && report.contains("Stuck Waiters"),
                "a thread parked while its predicate holds is the finding: " + report);
            assertTrue(report.contains("register the lock whose newCondition() made it"),
                "the printed report carries the note beside it: " + report);
            assertTrue(ctx.detectorNotes().isEmpty(),
                "so it is not collected a second time for the log: " + ctx.detectorNotes());
        } finally {
            waiter.interrupt();
            waiter.join(10_000);
        }
    }

    /** Starts a daemon thread parked in {@code condition.await()}, and returns once the lock shows it. */
    private static Thread parkOn(ReentrantLock lock, Condition condition) throws InterruptedException {
        Thread waiter = new Thread(() -> {
            lock.lock();
            try {
                condition.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            lock.lock();
            try {
                if (lock.hasWaiters(condition)) {
                    return waiter;
                }
            } finally {
                lock.unlock();
            }
            assertTrue(System.nanoTime() < deadline, "the waiter never parked");
            Thread.onSpinWait();
        }
    }

    private static AsyncTestContext exchangerContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectExchangerIssues(true).build());
    }

    @Test
    void anExchangeEndRecordedWithNoStartIsANote() {
        AsyncTestContext ctx = exchangerContext();
        ExchangerDetector detector = ctx.exchangerDetector;
        Exchanger<String> exchanger = new Exchanger<>();
        detector.recordExchangeComplete(exchanger, "swap", "value");
        detector.recordExchangeStart(exchanger, "swap");
        detector.recordTimeout(exchanger); // a handled timeout: background

        assertTrue(ctx.analyzeAllNamed().isEmpty(), "every start ended, so no exchange is orphaned");
        List<String> notes = ctx.detectorNotes().get("ExchangerDetector");
        assertEquals(1, notes == null ? 0 : notes.size(),
            "the end with no start is one note, and the handled timeout none: " + ctx.detectorNotes());
        assertTrue(notes.get(0).startsWith("swap: 1 end(s)"), "the note names the exchanger: " + notes);
        assertTrue(notes.get(0).contains("record the start and the end on the calling thread"),
            "and says how to record it: " + notes);
    }

    @Test
    void anExchangerReportWithAFindingKeepsTheNoteInItsText() throws InterruptedException {
        AsyncTestContext ctx = exchangerContext();
        ExchangerDetector detector = ctx.exchangerDetector;
        Exchanger<String> exchanger = new Exchanger<>();
        detector.recordExchangeStart(exchanger, "swap"); // never ended on this thread: orphaned
        Thread other = new Thread(() -> detector.recordExchangeComplete(exchanger, "swap", "value"));
        other.start();
        other.join(10_000);

        String report = ctx.analyzeAllNamed().get("ExchangerDetector");
        assertTrue(report != null && report.contains("Orphaned Exchanges"),
            "the start nothing ended on its own thread is the finding: " + report);
        assertTrue(report.contains("record the start and the end on the calling thread"),
            "the printed report carries the note beside it: " + report);
        assertTrue(ctx.detectorNotes().isEmpty(),
            "so it is not collected a second time for the log: " + ctx.detectorNotes());
    }

    private static AsyncTestContext reentrantLockContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectReentrantLockIssues(true).build());
    }

    /** Takes {@code lock} on a thread named {@code name} that ends holding it, recording or not. */
    private static void takeAndEnd(ReentrantLock lock, String name, ReentrantLockDetector detector,
                                   boolean record) throws InterruptedException {
        Thread holder = new Thread(() -> {
            lock.lock();
            if (record) {
                detector.recordLockAcquired(lock, name);
            }
        }, name);
        holder.start();
        holder.join(10_000);
    }

    @Test
    void aLockHeldByAThreadThatNeverRecordedTakingItIsANote() throws InterruptedException {
        AsyncTestContext ctx = reentrantLockContext();
        ReentrantLockDetector detector = ctx.reentrantLockDetector;
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "counter-lock");
        takeAndEnd(lock, "notes-unrecorded-holder", detector, false);
        detector.recordStarvation("waiter", 250);
        detector.recordStarvation("other-waiter", 300);

        assertTrue(ctx.analyzeAllNamed().isEmpty(), "a hold nobody recorded cannot be judged a leak");
        List<String> notes = ctx.detectorNotes().get("ReentrantLockDetector");
        assertEquals(2, notes == null ? 0 : notes.size(),
            "the unjudged hold and the waits with no lock named, each one note: " + ctx.detectorNotes());
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("counter-lock:")
                && n.contains("notes-unrecorded-holder") && n.contains("recordLockAcquired")),
            "one names the lock, its holder and the call that would let it be judged: " + notes);
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("2 wait(s)")
                && n.contains("recordStarvation(lock, threadName, waitTimeMs)")),
            "the other counts the lock-less waits once, and names the overload that judges them: " + notes);
    }

    @Test
    void reentrantLockBackgroundOnACleanRunIsNotANote() {
        AsyncTestContext ctx = reentrantLockContext();
        ReentrantLockDetector detector = ctx.reentrantLockDetector;
        ReentrantLock lock = new ReentrantLock();
        detector.registerLock(lock, "counter-lock");
        detector.recordLockTimeout(lock); // a handled timeout
        lock.lock();
        detector.recordLockAcquired(lock, "main");
        detector.recordStarvation(lock, "main", 250); // a wait with no barging seen
        detector.recordLockReleased(lock, "main");
        lock.unlock();

        assertTrue(ctx.analyzeAllNamed().isEmpty());
        assertTrue(ctx.detectorNotes().isEmpty(),
            "a handled timeout and a wait the lock did not corroborate are background, and the "
                + "recording was already the one that judges them: " + ctx.detectorNotes());
    }

    @Test
    void aReentrantLockReportWithAFindingKeepsTheNoteInItsText() throws InterruptedException {
        AsyncTestContext ctx = reentrantLockContext();
        ReentrantLockDetector detector = ctx.reentrantLockDetector;
        ReentrantLock leaked = new ReentrantLock();
        detector.registerLock(leaked, "leaked-lock");
        takeAndEnd(leaked, "notes-recorded-holder", detector, true);
        ReentrantLock unjudged = new ReentrantLock();
        detector.registerLock(unjudged, "unjudged-lock");
        takeAndEnd(unjudged, "notes-unrecorded-holder-2", detector, false);

        String report = ctx.analyzeAllNamed().get("ReentrantLockDetector");
        assertTrue(report != null && report.contains("Lock Still Held At Analysis"),
            "a recorded holder that finished with the lock taken is the finding: " + report);
        assertTrue(report.contains("unjudged-lock") && report.contains("never recorded taking the lock"),
            "the printed report carries the unjudged hold beside it: " + report);
        assertTrue(ctx.detectorNotes().isEmpty(),
            "so it is not collected a second time for the log: " + ctx.detectorNotes());
    }
}
