package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VarHandleNonAtomicUpdateDetector}.
 *
 * <p>Each rule is tested against the buggy shape and against the correct twin of that same code,
 * which is what makes a finding mean "your code is wrong" rather than "your code was concurrent".
 * Threads passed to the record methods are identity carriers only, so the scenarios are
 * deterministic rather than schedule-dependent; the two tests that need real contention use a
 * {@link CyclicBarrier} to get it.
 */
class VarHandleNonAtomicUpdateDetectorTest {

    /** Target of the VarHandle under test. */
    @SuppressWarnings("unused")   // written reflectively through the VarHandle
    static final class Holder {
        volatile int count;
    }

    private static final VarHandle COUNT;

    static {
        try {
            COUNT = MethodHandles.lookup().findVarHandle(Holder.class, "count", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** A second thread identity, never started: the record methods take threads for attribution. */
    private static final Thread OTHER = new Thread(() -> { }, "vh-other");

    private VarHandleNonAtomicUpdateDetector detector;

    @BeforeEach
    void setUp() {
        detector = new VarHandleNonAtomicUpdateDetector();
    }

    @Test
    void getThenSetIsFlaggedAsALostUpdate() {
        Holder h = new Holder();
        Thread t = Thread.currentThread();

        int v = (int) COUNT.getVolatile(h);
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, t);
        COUNT.setVolatile(h, v + 1);
        detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, t);
        // Another thread writes the same location, so there is a write the pair can lose.
        detector.recordAtomicUpdate(COUNT, h, "count", OTHER);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "get-then-set through a VarHandle must be flagged");
        assertTrue(report.toString().contains("non-atomic get-then-set"),
                "Report must name the lost update: " + report);
        assertFalse(report.structuredViolations.isEmpty(), "A structured Violation must be emitted");
    }

    @Test
    void volatileModeDoesNotExcuseTheLostUpdate() {
        Holder h = new Holder();
        Thread t = Thread.currentThread();

        // The whole point: VOLATILE buys ordering, never atomicity across two operations.
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, t);
        detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, t);
        detector.recordAtomicUpdate(COUNT, h, "count", OTHER);

        assertTrue(detector.analyze().hasIssues(),
                "A volatile get followed by a volatile set is still a non-atomic read-modify-write");
    }

    @Test
    void casLoopIsClean() {
        Holder h = new Holder();
        Thread t = Thread.currentThread();

        int old;
        do {
            old = (int) COUNT.getVolatile(h);
            detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, t);
        } while ((int) COUNT.compareAndExchange(h, old, old + 1) != old);
        detector.recordAtomicUpdate(COUNT, h, "count", t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "A CAS loop is the correct twin and must stay silent: " + report);
        assertEquals(1, h.count, "The CAS loop must actually have incremented the field");
    }

    @Test
    void getAndAddIsClean() {
        Holder h = new Holder();
        COUNT.getAndAdd(h, 5);
        detector.recordAtomicUpdate(COUNT, h, "count", Thread.currentThread());

        assertFalse(detector.analyze().hasIssues(), "getAndAdd is indivisible and must stay silent");
        assertEquals(5, h.count);
    }

    @Test
    void plainModeSharedAcrossThreadsIsFlaggedAtMedium() {
        Holder h = new Holder();
        Thread writer = new Thread(() -> { }, "vh-writer");
        Thread reader = new Thread(() -> { }, "vh-reader");

        detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, writer);
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, reader);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Plain-mode sharing with a write must be flagged");
        assertTrue(report.toString().contains("plain VarHandle access mode"),
                "Report must name the access mode: " + report);
        assertTrue(report.toString().contains("MEDIUM"),
                "Plain-mode sharing is a prompt, not a verdict: " + report);
    }

    @Test
    void volatileModeSharedAcrossThreadsIsClean() {
        Holder h = new Holder();
        Thread writer = new Thread(() -> { }, "vh-writer");
        Thread reader = new Thread(() -> { }, "vh-reader");

        detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, writer);
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, reader);

        assertFalse(detector.analyze().hasIssues(),
                "Ordered access across threads is the correct twin and must stay silent");
    }

    @Test
    void plainModeReadOnlySharingIsClean() {
        Holder h = new Holder();
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN,
                new Thread(() -> { }, "r1"));
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN,
                new Thread(() -> { }, "r2"));

        assertFalse(detector.analyze().hasIssues(),
                "Two readers and no writer cannot race; nothing to report");
    }

    @Test
    void differentReceiversAreTrackedSeparately() {
        Holder a = new Holder();
        Holder b = new Holder();
        Thread t = Thread.currentThread();

        detector.recordGet(COUNT, a, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, t);
        detector.recordSet(COUNT, b, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, t);

        assertFalse(detector.analyze().hasIssues(),
                "A read of one object and a write of another is not a read-modify-write");
    }

    @Test
    void analyzeIsIdempotent() {
        Holder h = new Holder();
        Thread t = Thread.currentThread();
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, t);
        detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, t);
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, OTHER);
        assertTrue(detector.analyze().hasIssues(), "idempotence is only worth testing on a finding");

        assertEquals(detector.analyze().toString(), detector.analyze().toString(),
                "Repeated analyze() on quiescent state must produce identical reports");
    }

    @Test
    void nullVarHandleIsIgnored() {
        detector.recordGet(null, new Holder(), "count",
                VarHandleNonAtomicUpdateDetector.Mode.PLAIN, Thread.currentThread());
        detector.recordSet(null, new Holder(), "count",
                VarHandleNonAtomicUpdateDetector.Mode.PLAIN, Thread.currentThread());

        assertFalse(detector.analyze().hasIssues(), "Null arguments must be ignored, not reported");
    }

    @Test
    void concurrentGetThenSetFromTwoThreadsIsFlagged() throws Exception {
        Holder h = new Holder();
        CyclicBarrier gate = new CyclicBarrier(2);
        Runnable lostUpdate = () -> {
            try {
                gate.await();
            } catch (Exception e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            Thread me = Thread.currentThread();
            int v = (int) COUNT.getVolatile(h);
            detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, me);
            COUNT.setVolatile(h, v + 1);
            detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, me);
        };

        Thread t1 = new Thread(lostUpdate, "vh-1");
        Thread t2 = new Thread(lostUpdate, "vh-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Two threads doing get-then-set must be flagged");
        assertTrue(report.toString().contains("2 non-atomic get-then-set"),
                "Both threads' sequences must be counted: " + report);
    }

    @Test
    void getThenSetInsideSynchronizedOnTheReceiverIsNotALostUpdate() throws Exception {
        Holder h = new Holder();
        Runnable lockedUpdate = () -> {
            Thread me = Thread.currentThread();
            synchronized (h) {
                int v = (int) COUNT.getVolatile(h);
                detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, me);
                COUNT.setVolatile(h, v + 1);
                detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, me);
            }
        };
        onTwoThreads(lockedUpdate);

        assertFalse(detector.analyze().hasIssues(),
                "Every get and set held the receiver's monitor, so no write could land between "
                        + "them. The VarHandle buys nothing here, but pointless is not wrong, and "
                        + "the lost-update finding is graded VERDICT: " + detector.analyze());
        assertEquals(2, h.count, "The locked updates must both have landed");
    }

    @Test
    void plainModeSharingUnderADeclaredLockIsNotFlagged() throws Exception {
        Holder h = new Holder();
        Object lock = new Object();
        Runnable lockedPlainUpdate = () -> {
            Thread me = Thread.currentThread();
            try (var held = se.deversity.asynctest.AsyncTestContext.holdingLock(lock)) {
                synchronized (lock) {
                    detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, me);
                    detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.PLAIN, me);
                }
            }
        };
        onTwoThreads(lockedPlainUpdate);

        assertFalse(detector.analyze().hasIssues(),
                "a monitor both threads take orders the plain accesses and makes the pair atomic");
    }

    @Test
    void getThenSetConfinedToOneThreadIsNotALostUpdate() {
        Holder h = new Holder();
        Thread t = Thread.currentThread();
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, t);
        detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, t);

        assertFalse(detector.analyze().hasIssues(),
                "no other thread ever touched the location, so there was no write to lose");
    }

    @Test
    void getThenSetUnderALockAnotherWriterSkipsIsStillFlagged() throws Exception {
        Holder h = new Holder();
        Thread locked = new Thread(() -> {
            Thread me = Thread.currentThread();
            synchronized (h) {
                detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, me);
                detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, me);
            }
        }, "vh-locked");
        Thread unlocked = new Thread(() -> detector.recordAtomicUpdate(COUNT, h, "count",
                Thread.currentThread()), "vh-unlocked");
        locked.start();
        locked.join();
        unlocked.start();
        unlocked.join();

        assertTrue(detector.analyze().hasIssues(),
                "the other writer took no lock, so it can land between the locked get and set");
    }

    private static void onTwoThreads(Runnable body) throws InterruptedException {
        Thread a = new Thread(body, "vh-a");
        Thread b = new Thread(body, "vh-b");
        a.start();
        b.start();
        a.join();
        b.join();
    }

    /**
     * A default virtual thread has no name, so the lost-update detail printed "thread ''" (#790).
     * It is named by its id instead.
     */
    @Test
    void anUnnamedVirtualThreadIsNamedByIdInTheLostUpdateDetail() {
        Holder h = new Holder();
        Thread vt = Thread.ofVirtual().unstarted(() -> { });
        assertEquals("", vt.getName(), "precondition: a default virtual thread has no name");
        detector.recordGet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, vt);
        detector.recordSet(COUNT, h, "count", VarHandleNonAtomicUpdateDetector.Mode.VOLATILE, vt);
        detector.recordAtomicUpdate(COUNT, h, "count", OTHER);
        String report = detector.analyze().toString();
        assertFalse(report.contains("thread ''"), "the detail must not name a thread as '': " + report);
        assertTrue(report.contains("thread '#" + vt.threadId() + "' read 'count'"),
                "the unnamed thread must be named by its id: " + report);
    }
}
