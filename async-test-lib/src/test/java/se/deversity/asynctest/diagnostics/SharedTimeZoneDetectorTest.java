package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SharedTimeZoneDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SharedTimeZoneDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSingleThread() {
        var d = new SharedTimeZoneDetector();
        Object tz = new Object();
        d.recordMutation(tz, "setRawOffset", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsMultiThreadMutation() throws Exception {
        var d = new SharedTimeZoneDetector();
        Object tz = new Object();
        d.recordMutation(tz, "setRawOffset", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordMutation(tz, "setID", Thread.currentThread()));
        t2.start(); t2.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("2"));
        assertTrue(report.violations.get(0).contains("own monitor count as guarded"));
    }

    @Test
    void testSeparateTimezonesPerThreadNoIssue() throws Exception {
        var d = new SharedTimeZoneDetector();
        Object tz1 = new Object();
        Object tz2 = new Object();
        d.recordMutation(tz1, "setRawOffset", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordMutation(tz2, "setRawOffset", Thread.currentThread()));
        t2.start(); t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new SharedTimeZoneDetector();
        assertDoesNotThrow(() -> d.recordMutation(null, "op", Thread.currentThread()));
        assertDoesNotThrow(() -> d.recordMutation(new Object(), "op", null));
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new SharedTimeZoneDetector();
        Object tz = new Object();
        d.recordMutation(tz, "setRawOffset", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordMutation(tz, "setRawOffset", Thread.currentThread()));
        t2.start(); t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("SHARED TIMEZONE"));
        assertTrue(s.contains("Fix"));
    }

    // ---- Mutators are counted within one round (#748) ------------------------------------------
    //
    // The runner finishes one round before it starts the next, and with virtual threads every body
    // execution is a fresh thread, so a mutator set kept across the run printed a count, and a list
    // of names, that grew with the number of rounds rather than with the race that was found.

    /**
     * Starts the next round of {@code scope} and runs each body on a fresh thread named
     * {@code names[i]} with the scope bound, released together so their accesses overlap.
     */
    private static void round(SelfGuard.Scope scope, String[] names, Runnable body) throws InterruptedException {
        scope.markInvocationStart();
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(names.length);
        Thread[] workers = new Thread[names.length];
        for (int i = 0; i < names.length; i++) {
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
            }, names[i]);
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    @Test
    void theReportCountsTheThreadsOfTheRoundThatRaced() throws Exception {
        var d = new SharedTimeZoneDetector();
        Object tz = new Object();
        SelfGuard.Scope scope = new SelfGuard.Scope();

        // Round one: three mutators, every one under the zone's own monitor. Not a race.
        round(scope, new String[] {"guarded-1", "guarded-2", "guarded-3"}, () -> {
            synchronized (tz) {
                d.recordMutation(tz, "setRawOffset", Thread.currentThread());
            }
        });
        // Round two: two mutators, unguarded. This is the finding.
        round(scope, new String[] {"racer-1", "racer-2"},
                () -> d.recordMutation(tz, "setID", Thread.currentThread()));
        // Round three: one more mutator, alone.
        round(scope, new String[] {"later"},
                () -> d.recordMutation(tz, "setID", Thread.currentThread()));

        var report = d.analyze();
        assertTrue(report.hasIssues(), "two threads mutated the zone unguarded in round two");
        String finding = report.violations.get(0);
        assertTrue(finding.contains("mutated from 2 threads"),
                "the count is round two's, not the six threads of the run: " + finding);
        assertTrue(finding.contains("racer-1") && finding.contains("racer-2"), finding);
        assertFalse(finding.contains("guarded-") || finding.contains("later"),
                "threads of other rounds did not take part in the race: " + finding);
    }
}
