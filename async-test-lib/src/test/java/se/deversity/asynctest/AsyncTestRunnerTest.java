package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that {@link AsyncTestRunner#run} is the annotated path without the annotation: same
 * N x M execution, same context, same failure and finding semantics. Each test states what it
 * would take to fail it, because the API exists for callers (Spock, ScalaTest, kotest,
 * clojure.test) who cannot use {@code @AsyncTest} and have nothing else to compare against.
 */
class AsyncTestRunnerTest {

    private static AsyncTestConfig.Builder config() {
        // detectAll(true): the builder defaults every detector to off, unlike the annotation.
        return AsyncTestConfig.builder().licenseMockMode(true).detectAll(true).failOn(FailOn.NONE);
    }

    /** Fails if the body ran fewer or more than threads x invocations times, or on one thread. */
    @Test
    void runsTheBodyThreadsTimesInvocationsTimes_onSeveralThreads() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        Set<Long> threads = ConcurrentHashMap.newKeySet();

        AsyncTestRunner.run(config().threads(4).invocations(10).build(), () -> {
            runs.incrementAndGet();
            threads.add(Thread.currentThread().threadId());
        });

        assertEquals(40, runs.get(), "4 threads x 10 rounds is 40 body executions");
        assertTrue(threads.size() > 1, "the body must run on the runner's workers, not the caller: " + threads);
    }

    /** Fails if the context is not installed on the worker, which would silence every hook. */
    @Test
    void theBodySeesAnInstalledContext_likeAnAnnotatedTest() throws Throwable {
        AtomicInteger missing = new AtomicInteger();

        AsyncTestRunner.run(config().threads(2).invocations(3).build(), () -> {
            if (AsyncTestContext.get() == null) missing.incrementAndGet();
        });

        assertEquals(0, missing.get(), "AsyncTestContext.get() must be non-null inside the body");
    }

    /**
     * Fails if a body failure is lost or arrives as something other than what the annotated path
     * produces: the engine's AssertionError with the body's exception as its cause (N workers on
     * one defect are collapsed into one error).
     */
    @Test
    void aBodyFailureSurfacesAsTheEnginesAssertionError_withTheBodysExceptionAsCause() {
        AssertionError thrown = assertThrows(AssertionError.class, () ->
                AsyncTestRunner.run(config().threads(2).invocations(3).build(), () -> {
                    throw new IllegalStateException("boom from the body");
                }));
        assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                "the body's own exception must be the cause: " + thrown);
        assertEquals("boom from the body", thrown.getCause().getMessage());
    }

    /** Fails if the detectors do not see the run, or if the collector is not what comes back. */
    @Test
    void findingsOfTheRunAreReturned() throws Throwable {
        Object owner = new Object();

        AsyncFindings findings = AsyncTestRunner.run(config().threads(4).invocations(20).build(), () -> {
            AsyncTestContext ctx = AsyncTestContext.get();
            assertNotNull(ctx);
            ctx.sharedRaceConditionDetector().recordFieldWrite(owner, "counter");
        });

        findings.assertReported("RaceConditionDetector");
        assertFalse(findings.violationsFrom("RaceConditionDetector").isEmpty());
    }

    /** Fails if the collector stays registered and picks up the next run's findings. */
    @Test
    void theReturnedCollectorIsClosed_soALaterRunDoesNotLeakIntoIt() throws Throwable {
        Object owner = new Object();
        AsyncTestRunner.Body racy = () ->
                AsyncTestContext.get().sharedRaceConditionDetector().recordFieldWrite(owner, "counter");

        AsyncFindings first = AsyncTestRunner.run(config().threads(4).invocations(20).build(), racy);
        int reportedByFirstRun = first.violations().size();
        assertTrue(reportedByFirstRun > 0, "precondition: the first run reported something");

        AsyncTestRunner.run(config().threads(4).invocations(20).build(), racy);

        assertEquals(reportedByFirstRun, first.violations().size(),
                "the first collector must not record the second run's findings");
    }

    /** Fails if the failOn gate does not apply to programmatic runs, or throws the wrong type. */
    @Test
    void theFailOnGateAppliesExactlyAsForAnAnnotatedTest() {
        Object owner = new Object();
        AsyncTestConfig cfg = config().threads(4).invocations(20).failOn(FailOn.HIGH).build();

        AssertionError gate = assertThrows(AssertionError.class, () ->
                AsyncTestRunner.run(cfg, () ->
                        AsyncTestContext.get().sharedRaceConditionDetector().recordFieldWrite(owner, "counter")));

        assertTrue(gate.getMessage().contains("failOn=HIGH"),
                "the gate's own message, not a wrapper: " + gate.getMessage());
        assertTrue(gate.getMessage().contains("AsyncTestRunner$BodyHolder#run"),
                "the documented identity of a programmatic run: " + gate.getMessage());
    }

    /** Fails if a hung body does not surface as the engine's timeout AssertionError. */
    @Test
    void aTimeoutIsTheEnginesAssertionError() {
        AsyncTestConfig cfg = config().threads(2).invocations(1).timeoutMs(300).build();

        Throwable thrown = assertThrows(Throwable.class, () ->
                AsyncTestRunner.run(cfg, () -> Thread.sleep(10_000)));

        assertInstanceOf(AssertionError.class, thrown, "timeouts are AssertionErrors: " + thrown);
        assertTrue(thrown.getMessage().toLowerCase().contains("time"),
                "the message must say it timed out: " + thrown.getMessage());
    }

    /** Fails if the null contract is silently relaxed. */
    @Test
    void nullArgumentsAreRefused() {
        AsyncTestConfig cfg = config().build();
        assertThrows(NullPointerException.class, () -> AsyncTestRunner.run(null, () -> { }));
        assertThrows(NullPointerException.class, () -> AsyncTestRunner.run(cfg, null));
        assertThrows(NullPointerException.class, () -> AsyncTestRunner.run(null, cfg, () -> { }));
    }
}
