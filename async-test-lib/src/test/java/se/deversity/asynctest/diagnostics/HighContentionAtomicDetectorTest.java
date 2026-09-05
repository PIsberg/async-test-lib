package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HighContentionAtomicDetectorTest {

    private static void hammer(HighContentionAtomicDetector d, AtomicLong atomic,
                                int attempts, int failures) throws InterruptedException {
        Thread t = new Thread(() -> {
            for (int i = 0; i < attempts; i++) {
                d.recordCasAttempt(atomic, i >= failures);
            }
        });
        t.start();
        t.join();
    }

    @Test
    void cleanWhenNoAccess() {
        var d = new HighContentionAtomicDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadAccessIsNotFlaggedEvenWithHighFailureRatio() {
        var d = new HighContentionAtomicDetector(5L);
        var counter = new AtomicLong();
        for (int i = 0; i < 50; i++) {
            d.recordCasAttempt(counter, false);
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void belowAttemptThresholdIsNotFlagged() throws InterruptedException {
        var d = new HighContentionAtomicDetector(100L);
        var counter = new AtomicLong();
        for (int i = 0; i < 40; i++) {
            d.recordCasAttempt(counter, false);
        }
        hammer(d, counter, 40, 40);
        var report = d.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void highVolumeButLowFailureRatioIsNotFlagged() throws InterruptedException {
        var d = new HighContentionAtomicDetector(20L);
        var counter = new AtomicLong();
        for (int i = 0; i < 15; i++) {
            d.recordCasAttempt(counter, true);
        }
        hammer(d, counter, 15, 2);
        var report = d.analyze();
        assertFalse(report.hasIssues(), "6.7% failure ratio should stay under the 10% threshold");
    }

    @Test
    void aboveThresholdHighFailureRatioAcrossThreadsIsFlagged() throws InterruptedException {
        var d = new HighContentionAtomicDetector(20L);
        var counter = new AtomicLong();
        for (int i = 0; i < 15; i++) {
            d.recordCasAttempt(counter, false);
        }
        hammer(d, counter, 10, 10);

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("25 attempt"));
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("25 failed CAS"));
        assertTrue(msg.contains("100.0%"));
        assertTrue(msg.contains("LongAdder"));

        assertEquals(1, report.structuredViolations.size());
        var v = report.structuredViolations.get(0);
        assertEquals("HighContentionAtomic", v.detector());
        assertEquals(IssueSeverity.LOW, v.severity());
        assertEquals(25L, v.attributes().get("attempts"));
        assertEquals(25L, v.attributes().get("failures"));
        assertEquals(2, v.attributes().get("threadCount"));
        assertEquals(1.0, (double) v.attributes().get("failureRatio"), 0.0001);
    }

    @Test
    void recordUpdateCountsAsSuccessfulAttempt() throws InterruptedException {
        var d = new HighContentionAtomicDetector(20L);
        var counter = new AtomicLong();
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 15; i++) {
                counter.incrementAndGet();
                d.recordUpdate(counter);
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 15; i++) {
                counter.incrementAndGet();
                d.recordUpdate(counter);
            }
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // 30 attempts total, all via recordUpdate (never failed) -> 0% failure ratio, never flagged.
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void customThresholdConstructorIsRespected() throws InterruptedException {
        var lowThreshold = new HighContentionAtomicDetector(50L);
        var defaultThreshold = new HighContentionAtomicDetector();
        var counterA = new AtomicLong();
        var counterB = new AtomicLong();

        for (int i = 0; i < 30; i++) {
            lowThreshold.recordCasAttempt(counterA, false);
            defaultThreshold.recordCasAttempt(counterB, false);
        }
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                lowThreshold.recordCasAttempt(counterA, false);
                defaultThreshold.recordCasAttempt(counterB, false);
            }
        });
        t1.start();
        t1.join();

        // 60 attempts, 2 threads, 100% failure ratio.
        assertTrue(lowThreshold.analyze().hasIssues(), "60 attempts clears the custom 50-attempt threshold");
        assertFalse(defaultThreshold.analyze().hasIssues(), "60 attempts stays under the default 1000-attempt threshold");
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws InterruptedException {
        var d = new HighContentionAtomicDetector(20L);
        var a = new AtomicLong();
        var b = new AtomicLong();
        for (int i = 0; i < 15; i++) {
            d.recordCasAttempt(a, false);
        }
        hammer(d, a, 10, 10);
        for (int i = 0; i < 5; i++) {
            d.recordCasAttempt(b, true);
        }

        var report = d.analyze();
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("AtomicLong@"));
    }

    @Test
    void nullsAreIgnored() {
        var d = new HighContentionAtomicDetector();
        d.recordCasAttempt(null, false);
        d.recordUpdate(null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void reportDescribesAttemptsFailuresRatioAndThreadCount() throws InterruptedException {
        var d = new HighContentionAtomicDetector(20L);
        var counter = new AtomicLong();
        for (int i = 0; i < 15; i++) {
            d.recordCasAttempt(counter, false);
        }
        hammer(d, counter, 10, 10);

        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("ATOMIC CONTENTION ADVISORY"));
        assertTrue(rendered.contains("25 attempt"));
        assertTrue(rendered.contains("2 threads"));
        assertTrue(rendered.contains("25 failed CAS"));
        assertTrue(rendered.contains("100.0%"));
        assertTrue(rendered.contains("LongAdder"));
        assertTrue(rendered.toLowerCase().contains("keep atomiclong"));
    }

    @Test
    void analyzeIsIdempotent() throws InterruptedException {
        var d = new HighContentionAtomicDetector(20L);
        var counter = new AtomicLong();
        for (int i = 0; i < 15; i++) {
            d.recordCasAttempt(counter, false);
        }
        hammer(d, counter, 10, 10);

        var first = d.analyze();
        var second = d.analyze();

        assertEquals(first.violations, second.violations);
        assertEquals(first.structuredViolations.size(), second.structuredViolations.size());
        assertEquals(first.structuredViolations.get(0).attributes(), second.structuredViolations.get(0).attributes());
    }

    @Test
    void anAdvisoryFindingIsLowAtTheGateNotHigh() throws InterruptedException {
        HighContentionAtomicDetector d = new HighContentionAtomicDetector(10);
        var counter = new AtomicLong();
        for (int i = 0; i < 15; i++) {
            d.recordCasAttempt(counter, false);
        }
        hammer(d, counter, 10, 10);
        String rendered = d.analyze().toString();
        assertEquals(IssueSeverity.LOW, DetectorDefaultSeverity.of("HighContentionAtomicDetector", rendered),
            "the header carried the bare word HIGH, which the gate read as the severity of an advisory");
    }
}
