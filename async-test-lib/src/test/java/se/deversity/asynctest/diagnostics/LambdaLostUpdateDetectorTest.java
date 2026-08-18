package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaLostUpdateDetectorTest {

    private static Thread here() { return Thread.currentThread(); }

    /** A distinct object per test so identity keys never collide across tests. */
    private static Runnable lambda() { return () -> { }; }

    @Test
    void cleanWhenNothingRecorded() {
        var d = new LambdaLostUpdateDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("LAMBDA CAPTURED-STATE LOST UPDATE - clean", d.analyze().toString());
    }

    @Test
    void oneThreadUpdatingIsSilent() {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        d.recordReadModifyWrite(task, "counter", 0, 1, here());
        d.recordReadModifyWrite(task, "counter", 1, 2, here());
        assertFalse(d.analyze().hasIssues(), "a single thread cannot lose its own update");
    }

    /**
     * The correctly written twin. An atomic increment gives every thread a distinct pre-value, so
     * no two threads ever read the same one and there is nothing to report.
     */
    @Test
    void atomicIncrementStaysSilent() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var counter = new AtomicInteger();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    int after = counter.incrementAndGet();
                    d.recordReadModifyWrite(task, "counter", after - 1, after, here());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertFalse(d.analyze().hasIssues(), "distinct pre-values mean no update was lost");
    }

    /** The other correct twin: the same read-modify-write, but under one consistently held monitor. */
    @Test
    void consistentlyHeldMonitorSuppressesTheFinding() {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var guard = new Object();

        synchronized (guard) {
            d.recordReadModifyWrite(task, "counter", 0, 1, guard, here());
        }
        synchronized (guard) {
            d.recordReadModifyWrite(task, "counter", 0, 1, guard, here());
        }

        assertFalse(d.analyze().hasIssues(),
                "under one monitor a repeated pre-value is a recurring value, not a lost write");
    }

    /**
     * The third correct twin, and the one the pre-value rule alone gets wrong. A flag toggled
     * under a {@link ReentrantLock}, one thread at a time: false to true, true to false, false to
     * true. Two of the three threads read {@code false} - the same pre-value - and nothing was
     * lost, the value simply came round again. A ReentrantLock is not a monitor, so the guard
     * overload cannot vouch for it; the sequence of values has to: every value written was read
     * by the next update, which is what a serial history looks like.
     */
    @Test
    void aRecurringValueUnderAnotherSerialisationStaysSilent() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var lock = new ReentrantLock();
        boolean[] flag = {false};

        for (int i = 0; i < 3; i++) {
            Thread t = Thread.ofPlatform().start(() -> {
                lock.lock();
                try {
                    boolean before = flag[0];
                    flag[0] = !before;
                    d.recordReadModifyWrite(task, "flag", before, !before, here());
                } finally {
                    lock.unlock();
                }
            });
            t.join();     // strictly one after another: the value recurs, no update is lost
        }

        var report = d.analyze();
        assertFalse(report.hasIssues(),
                () -> "false->true, true->false, false->true: two threads read false, but every write "
                    + "was picked up by the next update, so nothing was lost:\n" + report);
    }

    /**
     * The gate that keeps the twin above silent must not swallow a real loss whose colliding value
     * was also written once: 0->1, then two threads both read 1 and both write 2, then 2->3. Four
     * increments, final value 3. No serial order of these four updates exists, and the finding
     * stands with the collision named.
     */
    @Test
    void aLostUpdateOnAValueThatWasAlsoWrittenOnceIsStillReported() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();

        d.recordReadModifyWrite(task, "counter", 0, 1, here());
        d.recordReadModifyWrite(task, "counter", 1, 2, here());
        Thread other = Thread.ofPlatform().start(
                () -> d.recordReadModifyWrite(task, "counter", 1, 2, here()));   // same pre-value as above
        other.join();
        d.recordReadModifyWrite(task, "counter", 2, 3, here());

        var report = d.analyze();
        assertTrue(report.hasIssues(), "1 was read twice and written once: one increment is gone");
        assertTrue(report.violations.get(0).contains("all read 1"), report.violations.get(0));
        assertEquals(1, report.structuredViolations.get(0).attributes().get("lostWrites"));
    }

    /**
     * The count is what the values prove, not the sum over collision groups. A serial toggle
     * (0->1, 1->0, 0->1) followed by a real loss (two threads read 1, both write 2) has two
     * collision groups - "0" with two readers, "1" with three - which the old arithmetic added up
     * to three lost writes. Only one write was lost. The values leave two reads that no write
     * accounts for; one of them is the starting value, so at least one write was overwritten
     * unread, and that is the number the finding may claim.
     */
    @Test
    void theCountIsTheMinimumTheValuesProveNotTheGroupSum() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();

        d.recordReadModifyWrite(task, "state", 0, 1, here());
        onAnotherThread(() -> d.recordReadModifyWrite(task, "state", 1, 0, here()));
        onAnotherThread(() -> d.recordReadModifyWrite(task, "state", 0, 1, here()));   // legitimate recurrence
        onAnotherThread(() -> d.recordReadModifyWrite(task, "state", 1, 2, here()));
        onAnotherThread(() -> d.recordReadModifyWrite(task, "state", 1, 2, here()));   // the real loss

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("lost at least 1 update(s)"), msg);
        assertEquals(1, report.structuredViolations.get(0).attributes().get("lostWrites"));
    }

    /**
     * Four threads, one barrier, all read 0 and all write 1: three writes overwritten unread, and
     * the values prove exactly that (four reads of 0, no write of 0, one starting value).
     */
    @Test
    void fourReadersOfOneValueAreThreeLostWrites() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();

        d.recordReadModifyWrite(task, "counter", 0, 1, here());
        for (int i = 0; i < 3; i++) {
            onAnotherThread(() -> d.recordReadModifyWrite(task, "counter", 0, 1, here()));
        }

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("lost at least 3 update(s)"), report.violations.get(0));
        assertEquals(3, report.structuredViolations.get(0).attributes().get("lostWrites"));
    }

    private static void onAnotherThread(Runnable body) throws InterruptedException {
        Thread t = Thread.ofPlatform().start(body);
        t.join();
    }

    /**
     * Two monitors are not one guard: a lock only excludes the threads that take the same one.
     * The finding stands, and the message must say what happened - not "some, not all, held the
     * monitor", because every update here did hold one.
     */
    @Test
    void twoDifferentMonitorsAreNotOneGuardAndTheMessageSaysSo() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var guardA = new Object();
        var guardB = new Object();
        var done = new CountDownLatch(1);

        synchronized (guardA) {
            d.recordReadModifyWrite(task, "counter", 0, 1, guardA, here());
        }
        Thread other = Thread.ofPlatform().start(() -> {
            synchronized (guardB) {
                d.recordReadModifyWrite(task, "counter", 0, 1, guardB, here());
            }
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        other.join();

        var report = d.analyze();
        assertTrue(report.hasIssues(), "two monitors serialise nothing between them");
        String msg = report.violations.get(0);
        assertTrue(msg.contains("2 different monitors"), msg);
        assertFalse(msg.contains("Some - not all"), msg);
        assertEquals(2, report.structuredViolations.get(0).attributes().get("monitorsHeld"));
    }

    @Test
    void twoThreadsReadingTheSameValueIsAProvenLostUpdate() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var done = new CountDownLatch(1);

        d.recordReadModifyWrite(task, "counter", 0, 1, here());
        Thread other = Thread.ofPlatform().start(() -> {
            d.recordReadModifyWrite(task, "counter", 0, 1, here());   // same pre-value
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        other.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("counter"));
        assertTrue(msg.contains("lost at least 1 update(s)"), msg);
        assertTrue(msg.contains("all read 0"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertEquals("LambdaLostUpdate", report.structuredViolations.get(0).detector());
        assertEquals(1, report.structuredViolations.get(0).attributes().get("lostWrites"));
    }

    @Test
    void sameThreadRepeatingAValueIsNotAFinding() {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        d.recordReadModifyWrite(task, "flag", false, true, here());
        d.recordReadModifyWrite(task, "flag", false, true, here());   // one thread, toggling
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void inconsistentGuardingIsStillReportedAndSaidSo() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var guard = new Object();
        var done = new CountDownLatch(1);

        synchronized (guard) {
            d.recordReadModifyWrite(task, "counter", 0, 1, guard, here());   // guarded
        }
        Thread other = Thread.ofPlatform().start(() -> {
            d.recordReadModifyWrite(task, "counter", 0, 1, guard, here());   // not guarded
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        other.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("Some - not all - of these updates held"));
        assertEquals(true, report.structuredViolations.get(0).attributes().get("partiallyGuarded"));
    }

    @Test
    void differentCapturedNamesAreTrackedSeparately() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var done = new CountDownLatch(1);

        d.recordReadModifyWrite(task, "hits", 0, 1, here());
        Thread other = Thread.ofPlatform().start(() -> {
            d.recordReadModifyWrite(task, "misses", 0, 1, here());   // a different capture
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        other.join();

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void nullValuesAreRenderedNotDropped() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var done = new CountDownLatch(1);

        d.recordReadModifyWrite(task, "ref", null, "a", here());
        Thread other = Thread.ofPlatform().start(() -> {
            d.recordReadModifyWrite(task, "ref", null, "b", here());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        other.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("all read null"));
    }

    @Test
    void nullsAreIgnored() {
        var d = new LambdaLostUpdateDetector();
        d.recordReadModifyWrite(null, "counter", 0, 1, here());
        d.recordReadModifyWrite(lambda(), "counter", 0, 1, null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingCapturedNameFallsBackToADefault() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var done = new CountDownLatch(1);

        d.recordReadModifyWrite(task, null, 0, 1, here());
        Thread other = Thread.ofPlatform().start(() -> {
            d.recordReadModifyWrite(task, null, 0, 1, here());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        other.join();

        assertTrue(d.analyze().violations.get(0).contains("capturedState"));
    }

    @Test
    void disableStopsRecording() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var done = new CountDownLatch(1);

        d.disable();
        d.recordReadModifyWrite(task, "counter", 0, 1, here());
        Thread other = Thread.ofPlatform().start(() -> {
            d.recordReadModifyWrite(task, "counter", 0, 1, here());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        other.join();
        assertFalse(d.analyze().hasIssues());

        d.enable();
        var second = lambda();
        var done2 = new CountDownLatch(1);
        d.recordReadModifyWrite(second, "counter", 0, 1, here());
        Thread third = Thread.ofPlatform().start(() -> {
            d.recordReadModifyWrite(second, "counter", 0, 1, here());
            done2.countDown();
        });
        assertTrue(done2.await(5, TimeUnit.SECONDS));
        third.join();
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void reportToStringCarriesTheFinding() throws Exception {
        var d = new LambdaLostUpdateDetector();
        var task = lambda();
        var done = new CountDownLatch(1);

        d.recordReadModifyWrite(task, "counter", 0, 1, here());
        Thread other = Thread.ofPlatform().start(() -> {
            d.recordReadModifyWrite(task, "counter", 0, 1, here());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        other.join();

        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("LAMBDA CAPTURED-STATE LOST UPDATE DETECTED"));
        assertTrue(rendered.contains("counter"));
        assertTrue(rendered.contains("Fix:"));
    }
}
