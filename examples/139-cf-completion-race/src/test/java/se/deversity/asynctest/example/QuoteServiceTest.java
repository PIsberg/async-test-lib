package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.CompletableFutureCompletionRaceDetector;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.example.service.QuoteService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for QuoteService.
 *
 * ========================================================================
 * DETECTOR: CompletableFutureCompletionRaceDetector
 *           (DetectorType.COMPLETABLE_FUTURE_COMPLETION_RACE)
 * ========================================================================
 *
 * complete() and completeExceptionally() are first-writer-wins. They
 * return a boolean saying whether this call was the one that published,
 * and essentially nobody reads it. So when several threads publish into
 * one future - a callback bridge, a cache fill, a "first responder wins"
 * fan-out - every loser silently drops what it was carrying.
 *
 * THE BUG:
 *   - one shared completion slot that every provider completes
 *   - the losing provider's quote is discarded, and if the loser was
 *     reporting a failure, that failure disappears and the caller sees
 *     a success
 *
 * THE FIX:
 *   - one future per provider, combined afterwards; nothing is dropped
 *     because nothing is competing for a single slot
 *   - where a race really is intended, read the boolean and route the
 *     losers somewhere instead of ignoring them
 *
 * WHY THE FINDING IS A FACT:
 *   the detector reports only attempts observed to lose. A slot
 *   completed by exactly one thread produces no finding, so the fixed
 *   shape below is silent rather than merely quieter.
 */
class QuoteServiceTest {

    private QuoteService service;
    private CompletableFutureCompletionRaceDetector detector;

    @BeforeEach
    void setUp() {
        service = new QuoteService();
        detector = new CompletableFutureCompletionRaceDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the fixed shape. Each provider owns its own future, so every
    // quote survives and the choice is made from all of them.
    // -----------------------------------------------------------------------

    @Test
    void oneFuturePerProvider_isClean() throws InterruptedException {
        List<String> providers = List.of("alpha", "bravo", "charlie");
        List<CompletableFuture<String>> slots = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(providers.size());

        for (String provider : providers) {
            CompletableFuture<String> own = service.newResultSlot();
            slots.add(own);
            new Thread(() -> {
                try {
                    start.await();
                    detector.complete(own, provider, service.quoteFrom(provider));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, provider).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        List<String> quotes = slots.stream().map(CompletableFuture::join).toList();
        assertEquals(3, quotes.size(), "every provider's answer survived");
        assertEquals("alpha:500", service.cheapestOf(quotes));

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "a slot per provider must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: the buggy shape. Three providers, one slot. Two of the three
    // quotes are discarded and the code has no idea which answer it kept.
    // -----------------------------------------------------------------------

    @Test
    void sharedSlotAcrossProviders_isDetected() throws InterruptedException {
        List<String> providers = List.of("alpha", "bravo", "charlie");
        CompletableFuture<String> shared = service.newResultSlot();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(providers.size());

        for (String provider : providers) {
            new Thread(() -> {
                try {
                    start.await();
                    detector.complete(shared, "quote", service.quoteFrom(provider));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, provider).start();
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "three providers, one slot, two dropped:\n" + report);
        assertTrue(report.toString().contains("quote"), report.toString());
        assertEquals(2, report.structuredViolations.get(0).attributes().get("lostAttempts"));
    }

    // -----------------------------------------------------------------------
    // Part 3: the version that costs money. The winner published a price;
    // the loser was reporting that its backend was down. The caller sees a
    // clean success and never learns the quote came from a partial fan-out.
    // -----------------------------------------------------------------------

    @Test
    void aLosingFailureVanishes_isDetectedAsHigh() {
        CompletableFuture<String> shared = service.newResultSlot();

        assertTrue(detector.complete(shared, "quote", service.quoteFrom("alpha")));
        boolean published = detector.completeExceptionally(
                shared, "quote", new IllegalStateException("bravo backend unavailable"));

        assertFalse(published, "the failure lost the race");
        assertEquals("alpha:500", shared.join(), "and the caller sees a plain success");

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity(),
                "a discarded exception is the severe case, not a discarded duplicate value");
        assertTrue(report.toString().contains("bravo backend unavailable"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 4: the deliberate race, handled. The finding does not go away -
    // a value really was dropped - but the code now knows which one and can
    // log it, retry it, or fold it into the answer.
    // -----------------------------------------------------------------------

    @Test
    void handledLoser_stillReportedButNothingIsSilentlyLost() throws InterruptedException {
        CompletableFuture<String> shared = service.newResultSlot();
        List<String> rejected = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(2);

        for (String provider : List.of("alpha", "bravo")) {
            new Thread(() -> {
                try {
                    String quote = service.quoteFrom(provider);
                    // Read the boolean: the loser is handled rather than ignored.
                    if (!detector.complete(shared, "quote", quote)) {
                        synchronized (rejected) {
                            rejected.add(quote);
                        }
                    }
                } finally {
                    done.countDown();
                }
            }, provider).start();
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));

        var report = detector.analyze();
        // The race still happened, so the detector still reports it - and that is correct:
        // a value was dropped. What changed is that the code now knows which one.
        assertTrue(report.hasIssues());
        synchronized (rejected) {
            assertEquals(1, rejected.size(), "the losing quote was captured, not lost");
        }
    }
}
