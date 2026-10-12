package se.deversity.asynctest.example;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.BlockingOnNonBlockingThreadDetector;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.example.service.PriceFeed;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for PriceFeed.
 *
 * ========================================================================
 * DETECTOR: BlockingOnNonBlockingThreadDetector
 *           (DetectorType.BLOCKING_ON_NON_BLOCKING_THREAD)
 * ========================================================================
 *
 * An event loop runs every task queued for it, one after another. A
 * blocking call on it - a sleep, a remote call, a get of a pending future -
 * stalls every task behind it: quotes stop coming back, and nothing fails,
 * it just gets slow under load. Reactor's non-blocking schedulers, Netty and
 * Vert.x event loops and Kotlin's Dispatchers.Default all work this way.
 *
 * THE BUG:
 *   - PriceFeed.quoteOnTheLoop runs the slow lookup on the event loop
 *
 * THE FIX:
 *   - hand blocking work to a thread made for it (Schedulers.boundedElastic,
 *     executeBlocking, Dispatchers.IO; here a lookup pool)
 *
 * With the agent attached (collections=true) every woven blocking call feeds
 * the detector; an event loop the framework starts is attributed to the run
 * when the framework's scheduler packages are in the agent's includes=.
 * These tests record by hand so that they run without it.
 */
class PriceFeedTest {

    private BlockingOnNonBlockingThreadDetector detector;
    private PriceFeed feed;

    @BeforeEach
    void setUp() {
        detector = new BlockingOnNonBlockingThreadDetector();
        feed = new PriceFeed();
    }

    @AfterEach
    void tearDown() throws Exception {
        feed.close();
    }

    // -----------------------------------------------------------------------
    // Part 1: the shipped version answers. One quote at a time, the loop is
    // idle, and the stall costs nothing anyone measures.
    // -----------------------------------------------------------------------

    @Test
    void quotingOnTheLoopAnswers() throws Exception {
        assertEquals(400, feed.quoteOnTheLoop("ACME"));
    }

    // -----------------------------------------------------------------------
    // Part 2: the same lookup, recorded where it runs: on the event loop.
    // -----------------------------------------------------------------------

    @Test
    void blockingOnTheEventLoop_isDetected() throws Exception {
        ExecutorService loop = Executors.newSingleThreadExecutor(r -> new LoopThread(r));
        try {
            loop.submit(() -> detector.recordBlockingCall("remote price lookup", Thread.currentThread())).get();
        } finally {
            loop.shutdown();
        }

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "the event loop blocked:\n" + report);
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    // -----------------------------------------------------------------------
    // Part 3: the fix. The lookup runs on the lookup pool, whose threads may
    // block, and the event loop only formats the answer.
    // -----------------------------------------------------------------------

    @Test
    void blockingOnTheLookupPool_isClean() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> detector.recordBlockingCall("remote price lookup", Thread.currentThread())).get();
        } finally {
            pool.shutdown();
        }

        assertFalse(detector.analyze().hasIssues(), () -> detector.analyze().toString());
        assertEquals(400, feed.quote("ACME"));
    }

    /** A thread of a non-blocking scheduler, as PriceFeed's event loop runs on. */
    static final class LoopThread extends Thread implements reactor.core.scheduler.NonBlocking {
        LoopThread(Runnable task) {
            super(task, "test-event-loop");
        }
    }
}
