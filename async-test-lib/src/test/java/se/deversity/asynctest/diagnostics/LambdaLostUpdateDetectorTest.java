package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        assertTrue(msg.contains("lost 1 update(s)"));
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
