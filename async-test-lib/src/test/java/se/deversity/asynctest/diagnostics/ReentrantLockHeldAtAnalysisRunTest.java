package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.E2E;
import se.deversity.asynctest.FailOn;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The held-at-analysis finding through the real runner, both directions (#589).
 *
 * <p>The unit tests analyse on the test's own thread after a worker has finished. The runner
 * analyses once its workers are done with the round, on a thread that is not one of them, and a
 * pool worker that leaked a hold is still alive and idle at that point. This pins that the lock is
 * reported under that arrangement, which is what {@code examples/66-reentrant-lock} claims, and that
 * the fixed twin, whose tryLock timeouts are handled, passes a {@code failOn = LOW} gate.
 */
@E2E
class ReentrantLockHeldAtAnalysisRunTest {

    @Test
    void aHoldLeftTakenByAWorkerFailsTheGate() {
        Events tests = run(LeakFixture.class);

        Throwable thrown = tests.failed().stream()
                .findFirst()
                .flatMap(e -> e.getPayload(TestExecutionResult.class))
                .flatMap(TestExecutionResult::getThrowable)
                .orElse(null);
        assertNotNull(thrown, "a worker left the lock taken, so the gate must fail");
        String message = String.valueOf(thrown.getMessage());
        // The gate's message names the detector; the lock and its holder are in the printed report.
        assertTrue(message.contains("ReentrantLockDetector"),
                "the failure must name the detector. Message was: " + message);
    }

    @Test
    void handledTimeoutsOnAReleasedLockPassTheGate() {
        Events tests = run(HandledTimeoutFixture.class);

        assertEquals(0, tests.failed().count(),
                () -> "every hold was released and every timeout handled, which is correct code: "
                        + tests.failed().list());
        assertEquals(1, tests.succeeded().count(), "the fixture must have run");
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    /** The CounterService shape: the first worker in re-enters the lock and never releases the extra hold. */
    static class LeakFixture {

        static final ReentrantLock LOCK = new ReentrantLock();

        @AsyncTest(threads = 4, invocations = 2, timeoutMs = 20_000, detectAll = false,
                detectDeadlocks = false, detectReentrantLockIssues = true,
                failOn = FailOn.LOW, licenseMockMode = true)
        void increment() throws InterruptedException {
            ReentrantLockDetector detector = AsyncTestContext.reentrantLockDetector();
            detector.registerLock(LOCK, "leaky-counter-lock");
            if (LOCK.tryLock(100, TimeUnit.MILLISECONDS)) {
                detector.recordLockAcquired(LOCK, Thread.currentThread().getName());
                try {
                    LOCK.lock(); // validate() re-enters and never unlocks
                } finally {
                    detector.recordLockReleased(LOCK, Thread.currentThread().getName());
                    LOCK.unlock();
                }
            } else {
                detector.recordLockTimeout(LOCK); // the other workers back off, correctly
            }
        }
    }

    /** The same contention with the extra hold released: timeouts happen, and are handled. */
    static class HandledTimeoutFixture {

        static final ReentrantLock LOCK = new ReentrantLock();

        @AsyncTest(threads = 4, invocations = 3, timeoutMs = 20_000, detectAll = false,
                detectDeadlocks = false, detectReentrantLockIssues = true,
                failOn = FailOn.LOW, licenseMockMode = true)
        void increment() throws InterruptedException {
            ReentrantLockDetector detector = AsyncTestContext.reentrantLockDetector();
            detector.registerLock(LOCK, "counter-lock");
            if (LOCK.tryLock(1, TimeUnit.MILLISECONDS)) {
                detector.recordLockAcquired(LOCK, Thread.currentThread().getName());
                try {
                    Thread.sleep(5);
                } finally {
                    detector.recordLockReleased(LOCK, Thread.currentThread().getName());
                    LOCK.unlock();
                }
            } else {
                detector.recordLockTimeout(LOCK);
            }
        }
    }
}
