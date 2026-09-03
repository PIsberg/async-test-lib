package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import se.deversity.asynctest.diagnostics.BlockingQueueDetector;
import se.deversity.asynctest.diagnostics.CountDownLatchDetector;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;
import se.deversity.asynctest.diagnostics.SemaphoreMisuseDetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The coordination hooks asked one at a time, on the path each one is the only feed for.
 *
 * <p>{@code AgentConcurrencyUtilHooksTest} asks whether the two agent-fed detectors can be
 * reached at all. They can, but a hook riding on a sibling's record looks identical from there:
 * PIT reported "removed call to record..." surviving in nine of these hooks and the branch that
 * decides which record to make surviving in three more (#476). The semaphore family had no case
 * of its own at all, so every {@code tryAcquire} could have returned a hard-coded {@code true}.
 *
 * <p>Each case here therefore makes the hook under test the first and only one to touch its
 * subject, and asks for a finding that exists only if that hook recorded what it saw. Every
 * predicate is asked in both directions, because a detector that fired either way would pass the
 * positive half on its own.
 *
 * <p>Both {@code recordAwaitSuccess} call sites are asserted here since #477 gave that record an
 * effect: a success clears a timeout already recorded against the same latch, because a
 * {@code CountDownLatch} only counts down and never blocks again once it is at zero. Before that
 * the call set a field nothing read, and neither mutant could be killed by any test.
 */
class AgentConcurrencyUtilHooksEachAloneTest {

    private static AsyncTestContext newContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
    }

    /** Runs {@code body} with a fresh context installed, and hands the context back. */
    private static AsyncTestContext underAFreshContext(ThrowingBody body) throws Exception {
        AsyncTestContext ctx = newContext();
        AsyncTestContext.install(ctx);
        try {
            body.run();
        } finally {
            AsyncTestContext.uninstall();
        }
        return ctx;
    }

    private static SemaphoreMisuseDetector.SemaphoreMisuseReport semaphoreReport(AsyncTestContext ctx) {
        AsyncTestContext.install(ctx);
        try {
            return AsyncTestContext.semaphoreMisuseDetector().analyze();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    private static BlockingQueueDetector.BlockingQueueReport queueReport(AsyncTestContext ctx) {
        AsyncTestContext.install(ctx);
        try {
            return AsyncTestContext.blockingQueueDetector().analyze();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** A body that may throw whatever the hook it calls throws. */
    @FunctionalInterface
    private interface ThrowingBody {
        void run() throws Exception;
    }

    // --- Semaphore ----------------------------------------------------------------------------

    @Test
    @DisplayName("every tryAcquire overload records the permit it took, and only when it took one")
    void everyTryAcquireOverloadRecordsWhatItTook() throws Exception {
        assertLeaksOnePermit("tryAcquire()", 1,
                permits -> assertTrue(AgentConcurrencyUtilHooks.tryAcquire(permits),
                        "a free permit is taken"));
        assertLeaksOnePermit("tryAcquire(int)", 1,
                permits -> assertTrue(AgentConcurrencyUtilHooks.tryAcquire(permits, 1),
                        "and so is a single permit asked for by count"));
        assertLeaksOnePermit("tryAcquire(long, TimeUnit)", 1,
                permits -> assertTrue(
                        AgentConcurrencyUtilHooks.tryAcquire(permits, 1, TimeUnit.SECONDS),
                        "the timed form takes it without waiting"));
        assertLeaksOnePermit("tryAcquire(int, long, TimeUnit)", 1,
                permits -> assertTrue(
                        AgentConcurrencyUtilHooks.tryAcquire(permits, 1, 1, TimeUnit.SECONDS),
                        "and so does the timed form that counts permits"));

        assertRecordsNothing("tryAcquire()",
                exhausted -> assertFalse(AgentConcurrencyUtilHooks.tryAcquire(exhausted),
                        "there is no permit to take"));
        assertRecordsNothing("tryAcquire(int)",
                exhausted -> assertFalse(AgentConcurrencyUtilHooks.tryAcquire(exhausted, 1),
                        "nor one to take by count"));
        assertRecordsNothing("tryAcquire(long, TimeUnit)",
                exhausted -> assertFalse(
                        AgentConcurrencyUtilHooks.tryAcquire(exhausted, 1, TimeUnit.MILLISECONDS),
                        "the timed form waits and still gets nothing"));
        assertRecordsNothing("tryAcquire(int, long, TimeUnit)",
                exhausted -> assertFalse(
                        AgentConcurrencyUtilHooks.tryAcquire(exhausted, 1, 1, TimeUnit.MILLISECONDS),
                        "and neither does its permit-counting sibling"));
    }

    /**
     * Takes a permit through {@code attempt} and never gives it back, then requires the detector
     * to have seen the acquisition.
     *
     * <p>An acquire with no matching release is a leaked permit, which is the finding that exists
     * only if the hook recorded. Nothing registers the semaphore first, so the record call is also
     * what brings it into the report at all.
     */
    private static void assertLeaksOnePermit(String what, int available, SemaphoreCase attempt)
            throws Exception {
        Semaphore semaphore = new Semaphore(available);
        AsyncTestContext ctx = underAFreshContext(() -> attempt.run(semaphore));
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = semaphoreReport(ctx);
        assertTrue(report.hasIssues(),
                what + " took a permit and nothing released it, which is a leak the detector can "
                        + "only see if the hook recorded the acquisition; got " + report);
    }

    /** Fails to take a permit through {@code attempt}, and requires the detector to stay silent. */
    private static void assertRecordsNothing(String what, SemaphoreCase attempt) throws Exception {
        Semaphore exhausted = new Semaphore(0);
        AsyncTestContext ctx = underAFreshContext(() -> attempt.run(exhausted));
        SemaphoreMisuseDetector.SemaphoreMisuseReport report = semaphoreReport(ctx);
        assertFalse(report.hasIssues(),
                what + " took nothing, so there is nothing to leak; a hook recording on the "
                        + "failure branch reports a leak on code that correctly gave up. Got "
                        + report);
    }

    /** One tryAcquire overload, applied to a semaphore. */
    @FunctionalInterface
    private interface SemaphoreCase {
        void run(Semaphore semaphore) throws Exception;
    }

    // --- CountDownLatch -----------------------------------------------------------------------

    @Test
    @DisplayName("countDown reaches the counting detector, not only the misuse one")
    void countDownReachesTheCountingDetector() throws Exception {
        // CountDownLatchDetector only counts latches something registered, and no hook registers:
        // its record calls are no-ops on an agent-only path, which is why dropping one changed no
        // report. A consumer that registers by hand and then runs under the agent is the case
        // where the call matters, and it is the only case that can see it.
        CountDownLatch latch = new CountDownLatch(1);
        AsyncTestContext ctx = underAFreshContext(() -> {
            AsyncTestContext.countDownLatchDetector().registerLatch(latch, "registered", 1);
            AgentConcurrencyUtilHooks.countDown(latch);
            AgentConcurrencyUtilHooks.countDown(latch);
        });

        AsyncTestContext.install(ctx);
        try {
            CountDownLatchDetector.CountDownLatchReport report =
                    AsyncTestContext.countDownLatchDetector().analyze();
            assertTrue(report.hasIssues(),
                    "two countDown() calls on a latch registered for one is an extra countdown, "
                            + "and the hook's recordCountDown is what delivers it; got " + report);
            assertTrue(report.toString().contains("registered"),
                    "the finding must name the latch as registered; got " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the untimed await blocks until the latch falls, and is recorded before it does")
    void untimedAwaitBlocksAndIsRecordedFirst() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicBoolean returned = new AtomicBoolean();
        AsyncTestContext ctx = newContext();

        Thread waiter = new Thread(() -> {
            AsyncTestContext.install(ctx);
            try {
                AgentConcurrencyUtilHooks.await(latch);
                returned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                AsyncTestContext.uninstall();
            }
        }, "untimed-await");
        waiter.start();
        awaitBlocked(waiter);

        assertFalse(returned.get(),
                "the hook must perform the await it replaced: the latch is at 2, so a hook that "
                        + "dropped the call would have returned already");
        latch.countDown();
        latch.countDown(); // not through the hook, so only the await is recorded
        waiter.join();
        assertTrue(returned.get(), "and must return once the latch reaches zero");

        AsyncTestContext.install(ctx);
        try {
            LatchMisuseDetector.LatchMisuseReport report =
                    AsyncTestContext.latchMisuseDetector().analyze();
            assertEquals(1, report.missingCountDowns.size(),
                    "the await was the only hook to touch this latch, so the finding exists only "
                            + "if that hook both observed the latch at 2 and recorded the await; "
                            + "got " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** Spins until {@code thread} is parked in the await, or fails rather than hanging forever. */
    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > deadline || !thread.isAlive()) {
                break;
            }
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, thread.getState(),
                "the waiter never blocked in await(), so the hook did not perform it");
    }

    @Test
    @DisplayName("a timed await that expires is recorded as a timeout, and one that succeeds is not")
    void timedAwaitRecordsWhichWayItWent() throws Exception {
        CountDownLatch neverFalls = new CountDownLatch(1);
        AsyncTestContext timedOut = underAFreshContext(() ->
                assertFalse(AgentConcurrencyUtilHooks.await(neverFalls, 5, TimeUnit.MILLISECONDS),
                        "nothing counts this latch down, so the wait expires"));

        AsyncTestContext.install(timedOut);
        try {
            assertTrue(AsyncTestContext.countDownLatchDetector().analyze().hasIssues(),
                    "an expired await is the timeout finding, and this hook is the only feed");
            assertEquals(1, AsyncTestContext.latchMisuseDetector().analyze().missingCountDowns.size(),
                    "the same call is also the only thing that observed the latch at 1 and "
                            + "recorded an await against it");
        } finally {
            AsyncTestContext.uninstall();
        }

        CountDownLatch alreadyDown = new CountDownLatch(1);
        alreadyDown.countDown();
        AsyncTestContext succeeded = underAFreshContext(() ->
                assertTrue(AgentConcurrencyUtilHooks.await(alreadyDown, 5, TimeUnit.SECONDS),
                        "a latch at zero returns immediately"));

        AsyncTestContext.install(succeeded);
        try {
            assertFalse(AsyncTestContext.countDownLatchDetector().analyze().hasIssues(),
                    "an await that succeeded is not a timeout; recording it as one would report "
                            + "every correctly coordinated latch in the run");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a later success clears the timeout, through either await hook")
    void aLaterSuccessClearsTheTimeout() throws Exception {
        assertFalse(stillReportsATimeoutAfter(latch -> AgentConcurrencyUtilHooks.await(latch)),
                "the untimed await must deliver its success: the latch reached zero, so the "
                        + "timeout recorded before it was a wait that started too early (#477)");
        assertFalse(stillReportsATimeoutAfter(
                        latch -> AgentConcurrencyUtilHooks.await(latch, 1, TimeUnit.SECONDS)),
                "and so must the timed await's success branch");
    }

    /**
     * Times a wait out on a latch, then falls the latch and waits again through {@code second},
     * and {@return whether the counting detector still reports the timeout}.
     */
    private static boolean stillReportsATimeoutAfter(LatchCase second) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AsyncTestContext ctx = underAFreshContext(() -> {
            assertFalse(AgentConcurrencyUtilHooks.await(latch, 5, TimeUnit.MILLISECONDS),
                    "nothing has counted the latch down yet, so the first wait expires");
            latch.countDown();
            second.run(latch);
        });

        AsyncTestContext.install(ctx);
        try {
            return AsyncTestContext.countDownLatchDetector().analyze().hasIssues();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** One await overload, applied to a latch. */
    @FunctionalInterface
    private interface LatchCase {
        void run(CountDownLatch latch) throws Exception;
    }

    // --- BlockingQueue ------------------------------------------------------------------------

    @Test
    @DisplayName("put alone fills the queue the detector reports as saturated")
    void putAloneSaturatesTheQueue() throws Exception {
        // The existing saturation case reaches the high-water mark through offer(), so dropping
        // put()'s record left the finding intact. Filling by put() alone is what makes that call
        // the only source of the size the report prints.
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(2);
        AsyncTestContext ctx = underAFreshContext(() -> {
            AgentConcurrencyUtilHooks.put(queue, "a");
            AgentConcurrencyUtilHooks.put(queue, "b");
        });

        BlockingQueueDetector.BlockingQueueReport report = queueReport(ctx);
        assertTrue(report.hasIssues(),
                "two puts into a queue of two is saturation, and put() is the only call that saw "
                        + "the size; got " + report);
        assertTrue(report.toString().contains("2/2"),
                "the finding must name the peak against the capacity; got " + report);
    }

    @Test
    @DisplayName("both poll overloads record whether they got an element")
    void bothPollOverloadsRecordWhatTheyGot() throws Exception {
        assertPollRecords("poll()", queue -> AgentConcurrencyUtilHooks.poll(queue));
        assertPollRecords("poll(long, TimeUnit)",
                queue -> AgentConcurrencyUtilHooks.poll(queue, 1, TimeUnit.MILLISECONDS));
    }

    /**
     * Polls a queue that has an element and then one that does not, and requires the detector to
     * have told the two apart.
     *
     * <p>An empty poll is counted and printed rather than treated as a finding - the canonical
     * drain loop ends with exactly one - so the assertion is on the line, not on
     * {@code hasIssues()}. A hook that recorded the wrong branch would print that line after the
     * successful poll instead.
     */
    private static void assertPollRecords(String what, PollCase poll) throws Exception {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(4);
        queue.add("first"); // not through a hook: only the polls below are recorded

        AsyncTestContext gotOne = underAFreshContext(() ->
                assertNotNull(poll.run(queue), what + " must hand back the element it took"));
        assertFalse(queueReport(gotOne).toString().contains("poll() returned null"),
                what + " returned an element, so nothing may be counted as an empty poll; got "
                        + queueReport(gotOne));

        AsyncTestContext gotNothing = underAFreshContext(() ->
                assertNull(poll.run(queue), what + " must hand back null from an empty queue"));
        assertTrue(queueReport(gotNothing).toString().contains("poll() returned null 1 times"),
                what + " came back empty, which the detector can only count if the hook recorded "
                        + "the result it got; got " + queueReport(gotNothing));
    }

    /** One poll overload, applied to a queue. */
    @FunctionalInterface
    private interface PollCase {
        Object run(BlockingQueue<Object> queue) throws Exception;
    }

    @Test
    @DisplayName("a timed offer whose rejection was discarded is reported as a dropped element")
    void theTimedOfferRecordsItsRejection() throws Exception {
        // The untimed overload has this case already; the timed one had none, so dropping its
        // record left the finding to the sibling that was never called.
        BlockingQueue<Object> full = new ArrayBlockingQueue<>(1);
        AsyncTestContext ctx = underAFreshContext(() -> {
            AgentConcurrencyUtilHooks.put(full, "occupant");
            boolean added = AgentConcurrencyUtilHooks.offer(full, "dropped", 1,
                    TimeUnit.MILLISECONDS);
            assertFalse(added, "the queue is full for the whole timeout");
            AgentConcurrencyUtilHooks.offerResultDiscarded(added);
        });

        BlockingQueueDetector.BlockingQueueReport report = queueReport(ctx);
        assertTrue(report.toString().contains("discarded the result"),
                "a rejected timed offer whose boolean was popped dropped the element, and the "
                        + "correlation to it exists only because the offer hook recorded; got "
                        + report);
    }
}
