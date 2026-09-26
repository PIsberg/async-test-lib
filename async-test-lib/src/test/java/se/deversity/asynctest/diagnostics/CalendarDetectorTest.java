package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CalendarDetector}.
 */
public class CalendarDetectorTest {

    @Test
    void testSingleThreadUsageNoIssues() {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();

        detector.registerCalendar(cal, "single-thread-calendar");
        cal.set(Calendar.YEAR, 2024);
        detector.recordSet(cal, "single-thread-calendar");
        detector.recordGet(cal, "single-thread-calendar");

        CalendarDetector.CalendarReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread access should not report issues");
    }

    @Test
    void testSharedCalendarDetection() throws InterruptedException {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();

        detector.registerCalendar(cal, "shared-calendar");

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                cal.set(Calendar.DAY_OF_MONTH, i + 1);
                detector.recordSet(cal, "shared-calendar");
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                cal.get(Calendar.DAY_OF_MONTH);
                detector.recordGet(cal, "shared-calendar");
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        CalendarDetector.CalendarReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Shared calendar should be detected");
        assertFalse(report.sharedCalendars.isEmpty(), "Should report shared calendars");
    }

    @Test
    void testAddRecording() {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();

        detector.registerCalendar(cal, "add-calendar");
        cal.add(Calendar.MONTH, 1);
        detector.recordAdd(cal, "add-calendar");

        CalendarDetector.CalendarReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single-thread add should not report issues");
        assertTrue(report.calendarActivity.containsKey("add-calendar"), "Should track add activity");
    }

    @Test
    void testErrorTracking() {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();

        detector.registerCalendar(cal, "error-calendar");
        detector.recordGet(cal, "error-calendar");
        detector.recordError(cal, "error-calendar", "CorruptedDateException");

        CalendarDetector.CalendarReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.calendarErrors.isEmpty(), "Should track errors");
    }

    @Test
    void testNullSafety() {
        CalendarDetector detector = new CalendarDetector();

        assertDoesNotThrow(() -> {
            detector.registerCalendar(null, "null-calendar");
            detector.recordGet(null, "null");
            detector.recordSet(null, "null");
            detector.recordAdd(null, "null");
            detector.recordError(null, "null", "error");
        });

        CalendarDetector.CalendarReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testAutoRegisterOnFirstAccess() {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();

        // Access without explicit registration — should auto-register
        detector.recordGet(cal, "auto-registered");

        CalendarDetector.CalendarReport report = detector.analyze();
        assertNotNull(report);
        assertTrue(report.calendarActivity.containsKey("auto-registered"), "Should auto-register on first access");
    }

    @Test
    void testReportToString() throws InterruptedException {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();

        detector.registerCalendar(cal, "report-calendar");

        Thread t1 = new Thread(() -> detector.recordSet(cal, "report-calendar"));
        Thread t2 = new Thread(() -> detector.recordSet(cal, "report-calendar"));

        t1.start(); t2.start();
        t1.join();  t2.join();

        CalendarDetector.CalendarReport report = detector.analyze();
        String text = report.toString();

        assertNotNull(text);
        assertTrue(text.contains("CALENDAR ISSUES DETECTED"), "Should contain header");
        assertTrue(text.contains("Shared Calendar Instances"), "Should mention shared calendars");
        assertTrue(text.contains("java.time"), "Should suggest java.time fix");
    }

    @Test
    void testMultipleCalendarsIndependentlyTracked() throws InterruptedException {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal1 = Calendar.getInstance();
        Calendar cal2 = Calendar.getInstance();

        detector.registerCalendar(cal1, "cal1");
        detector.registerCalendar(cal2, "cal2");

        // Only cal1 is shared
        Thread t1 = new Thread(() -> detector.recordSet(cal1, "cal1"));
        Thread t2 = new Thread(() -> detector.recordSet(cal1, "cal1"));
        t1.start(); t2.start();
        t1.join();  t2.join();

        // cal2 used by one thread
        detector.recordGet(cal2, "cal2");

        CalendarDetector.CalendarReport report = detector.analyze();
        assertTrue(report.hasIssues(), "cal1 should be flagged as shared");

        boolean cal2Flagged = report.sharedCalendars.stream()
                .anyMatch(s -> s.contains("cal2"));
        assertFalse(cal2Flagged, "cal2 should not be flagged (single-thread use)");
    }

    @Test
    void aCalendarGuardedByItsOwnMonitorIsNotReported() throws InterruptedException {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();
        Runnable body = () -> {
            for (int i = 0; i < 5; i++) {
                synchronized (cal) {
                    cal.set(Calendar.DAY_OF_MONTH, i + 1);
                    detector.recordSet(cal, "guarded-calendar");
                    cal.get(Calendar.DAY_OF_MONTH);
                    detector.recordGet(cal, "guarded-calendar");
                }
            }
        };
        Thread t1 = new Thread(body);
        Thread t2 = new Thread(body);
        t1.start(); t2.start(); t1.join(); t2.join();
        assertFalse(detector.analyze().hasIssues(),
            "every access held the calendar's monitor; the synchronized twin must stay silent: "
                + detector.analyze());
    }

    // #787 made a round with no write unshared. Calendar.get() is not a read in that sense: after
    // a set() it recomputes the time and the whole field set into the instance, so two threads
    // calling get() together after a set() in an earlier round write the same fields at once.
    @Test
    void twoUnguardedGetsInOneRoundAfterASetInAnEarlierRoundAreReported() throws InterruptedException {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();
        SelfGuard.Scope scope = new SelfGuard.Scope();
        Runnable get = () -> {
            cal.get(Calendar.DAY_OF_MONTH);
            detector.recordGet(cal, "lazy-calendar");
        };

        round(scope, () -> {
            cal.set(Calendar.DAY_OF_MONTH, 3);
            detector.recordSet(cal, "lazy-calendar");
        });
        round(scope, get, get);

        assertTrue(detector.analyze().hasIssues(),
            "the first get() after a set() recomputes the fields, so concurrent gets race");
    }

    // The other direction: counting get() as a write for the round verdict must not also stop a
    // read lock from guarding it. Gets under one read-write lock's read view, with no set() in the
    // run, only read fields computed when the calendar was built, and were not reported before.
    @Test
    void getsUnderOneReadLockAreNotReported() throws InterruptedException {
        CalendarDetector detector = new CalendarDetector();
        Calendar cal = Calendar.getInstance();
        java.util.concurrent.locks.ReentrantReadWriteLock lock =
                new java.util.concurrent.locks.ReentrantReadWriteLock();
        Runnable get = () -> {
            lock.readLock().lock();
            HeldLocks.acquired(lock, true);
            try {
                cal.get(Calendar.DAY_OF_MONTH);
                detector.recordGet(cal, "read-locked-calendar");
            } finally {
                HeldLocks.released(lock, true);
                lock.readLock().unlock();
            }
        };

        round(new SelfGuard.Scope(), get, get);

        assertFalse(detector.analyze().hasIssues(),
            "both gets held the read lock and nothing set the calendar: " + detector.analyze());
    }

    /**
     * Starts the next round of {@code scope} and runs each body on a fresh thread with the scope
     * bound, released together so their accesses overlap, as a run's workers are.
     */
    private static void round(SelfGuard.Scope scope, Runnable... bodies) throws InterruptedException {
        scope.markInvocationStart();
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(bodies.length);
        Thread[] workers = new Thread[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            Runnable body = bodies[i];
            workers[i] = new Thread(() -> {
                SelfGuard.Scope.bind(scope);
                try {
                    start.await();
                    body.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    SelfGuard.Scope.unbind();
                }
            }, "round-worker-" + i);
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }
}
