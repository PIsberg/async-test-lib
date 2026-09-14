package se.deversity.asynctest.diagnostics;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detector's findings rest on what the executor actually did, not only on what was recorded
 * about it (#568).
 *
 * <p>Recorded calls say that a shutdown or an await happened, never how it ended. An
 * {@code awaitTermination} that gives up returns {@code false} with tasks still running, which is
 * the leak this detector exists for, and it was recorded exactly like one that succeeded. And
 * {@code ExecutorService.close()}, which shuts down and waits, records nothing at all, so the
 * idiom the report's own fix text recommends was reported as never shut down.
 */
class ExecutorShutdownAccuracyTest {

    @Test
    @DisplayName("an awaitTermination that timed out, with a task still running, is reported")
    void timedOutAwaitIsReported() throws Exception {
        var detector = new ExecutorShutdownDetector();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            detector.recordExecutorCreated(pool, "stuck-pool");
            pool.submit(() -> {
                release.await();
                return null;
            });
            detector.recordTaskSubmitted(pool);
            pool.shutdown();
            detector.recordShutdownCalled(pool, false);
            boolean terminated = pool.awaitTermination(20, TimeUnit.MILLISECONDS);
            detector.recordAwaitTerminationCalled(pool);

            assertFalse(terminated, "premise: the wait gave up with the task still running");
            assertTrue(detector.analyze().hasIssues(),
                    "the pool was shut down and awaited, and its task still outlives the test; "
                            + "that is the leak, whatever the records say");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an executor closed with try-with-resources is not reported as never shut down")
    void closedExecutorIsNotReported() {
        var detector = new ExecutorShutdownDetector();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            detector.recordExecutorCreated(pool, "closed-pool");
            pool.submit(() -> { });
            detector.recordTaskSubmitted(pool);
        }

        assertFalse(detector.analyze().hasIssues(),
                "close() shut the pool down and waited for it: " + detector.analyze());
    }

    @Test
    @DisplayName("a shut-down executor that already terminated abandoned nothing")
    void terminatedWithoutRecordedAwaitIsNotReported() throws Exception {
        var detector = new ExecutorShutdownDetector();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        detector.recordExecutorCreated(pool, "drained-pool");
        // invokeAll returns only when every task has finished, so nothing is in flight at the
        // shutdown and there is nothing an awaitTermination would have waited for.
        pool.invokeAll(java.util.List.of(() -> 1, () -> 2));
        detector.recordTaskSubmitted(pool);
        pool.shutdown();
        detector.recordShutdownCalled(pool, false);
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "premise: the pool has terminated");

        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    @DisplayName("an executor really left running is still reported")
    void runningExecutorIsStillReported() {
        var detector = new ExecutorShutdownDetector();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            detector.recordExecutorCreated(pool, "leaked-pool");
            pool.submit(() -> { });
            detector.recordTaskSubmitted(pool);

            assertTrue(detector.analyze().hasIssues());
        } finally {
            pool.shutdownNow();
        }
    }
}
