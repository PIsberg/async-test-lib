package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletableFutureCompletionRaceDetectorTest {

    @Test
    void cleanWhenNothingRecorded() {
        var d = new CompletableFutureCompletionRaceDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("COMPLETABLE FUTURE COMPLETION RACE - clean", d.analyze().toString());
    }

    @Test
    void singleCompletionIsSilent() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        assertTrue(d.complete(f, "lookup", "a"));
        assertFalse(d.analyze().hasIssues());
    }

    /**
     * The correctly written twin: a guard elects one completer, so only one attempt is ever made
     * and nothing is dropped. This must stay silent - it is the direction that decides whether the
     * detector is usable with {@code failOn}.
     */
    @Test
    void guardedSingleCompleterStaysSilent() throws Exception {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<Integer>();
        var elected = new AtomicInteger();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            final int value = i;
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    if (elected.compareAndSet(0, 1)) {   // exactly one thread completes
                        d.complete(f, "guarded", value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertFalse(d.analyze().hasIssues(), "one completer means nothing was discarded");
    }

    @Test
    void losingAttemptWithADifferentValueIsHigh() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        assertTrue(d.complete(f, "lookup", "winner"));
        assertFalse(d.complete(f, "lookup", "loser"));

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("lookup"));
        assertTrue(msg.contains("dropped value loser"));
        assertTrue(msg.contains("winning completion was winner"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("CompletableFutureCompletionRace", report.structuredViolations.get(0).detector());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void losingAttemptWithAnEqualValueIsMedium() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        d.complete(f, "idempotent", "same");
        d.complete(f, "idempotent", "same");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
    }

    @Test
    void aDiscardedExceptionIsHigh() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        d.complete(f, "bridge", "ok");
        assertFalse(d.completeExceptionally(f, "bridge", new IllegalStateException("boom")));

        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("dropped exception IllegalStateException: boom"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void aLossWithNoObservedWinnerIsHigh() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        f.complete("completed out of view");   // not recorded
        d.complete(f, "bridge", "first");
        d.complete(f, "bridge", "second");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("(not observed)"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void concurrentRaceIsReported() throws Exception {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<Integer>();
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            final int value = i;
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    d.complete(f, "fanout", value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        var report = d.analyze();
        assertTrue(report.hasIssues(), "three of four attempts must lose");
        assertEquals(3, report.structuredViolations.get(0).attributes().get("lostAttempts"));
    }

    @Test
    void recordedAttemptsWithoutTheConvenienceMethodWork() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        d.recordCompletionAttempt(f, "manual", "a", true, Thread.currentThread());
        d.recordCompletionAttempt(f, "manual", "b", false, Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void nullsAreIgnored() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        assertFalse(d.complete(null, "x", "v"));
        assertFalse(d.completeExceptionally(null, "x", new RuntimeException()));
        d.recordCompletionAttempt(f, "x", "v", false, null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingLabelFallsBackToIdentity() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        d.complete(f, null, "a");
        d.complete(f, null, "b");
        assertTrue(d.analyze().violations.get(0).contains("CompletableFuture@"));
    }

    @Test
    void disableStopsRecording() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        d.disable();
        d.complete(f, "off", "a");
        d.complete(f, "off", "b");
        assertFalse(d.analyze().hasIssues());

        d.enable();
        var g = new CompletableFuture<String>();
        d.complete(g, "on", "a");
        d.complete(g, "on", "b");
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void reportToStringCarriesTheFinding() {
        var d = new CompletableFutureCompletionRaceDetector();
        var f = new CompletableFuture<String>();
        d.complete(f, "lookup", "a");
        d.complete(f, "lookup", "b");
        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("COMPLETABLE FUTURE COMPLETION RACE DETECTED"));
        assertTrue(rendered.contains("lookup"));
        assertTrue(rendered.contains("Fix:"));
    }
}
