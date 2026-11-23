package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.Preset;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The context's round start reaches a detector through the {@code RoundScoped} loop, the path the
 * runner takes, not only when a test calls the detector's {@code markInvocationStart()} itself.
 *
 * <p>The scenario is {@code ConditionVariableDetectorLockAndRoundsTest}'s abandoned await (#593):
 * an await one round recorded and never exited must still be noted after the same pooled thread
 * completes a wait in the next round. If the loop skipped the detector, the two rounds would merge,
 * round two's exit would close round one's await, and the note would vanish.
 */
@DisplayName("AsyncTestContext.markInvocationStart reaches the round-scoped detectors")
class RoundStartReachesDetectorsTest {

    private static final String NAME = "data-ready";

    private final ExecutorService waiter = Executors.newSingleThreadExecutor();
    private final ExecutorService producer = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutDown() {
        waiter.shutdownNow();
        producer.shutdownNow();
    }

    private static void on(ExecutorService thread, Runnable step) throws Exception {
        thread.submit(step).get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("an await abandoned in round one is not merged into round two's when the context starts the round")
    void theContextsRoundStartSeparatesRounds() throws Exception {
        AsyncTestContext context = new AsyncTestContext(AsyncTestConfig.builder().preset(Preset.ALL).build());
        // The context keeps its detectors in package-private fields of its own package.
        java.lang.reflect.Field field = AsyncTestContext.class.getDeclaredField("conditionVariableDetector");
        field.setAccessible(true);
        ConditionVariableDetector detector = (ConditionVariableDetector) field.get(context);
        assertNotNull(detector, "Preset.ALL enables the condition-variable detector");
        Condition condition = new ReentrantLock().newCondition();
        detector.registerCondition(condition, NAME);

        // Round one: the body records its await and throws before it records the exit.
        on(waiter, () -> detector.recordAwait(condition, NAME));
        context.markInvocationStart();
        // Round two, same pooled thread: a complete, signalled wait.
        on(waiter, () -> detector.recordAwait(condition, NAME));
        on(producer, () -> detector.recordSignal(condition, NAME, false));
        on(waiter, () -> detector.recordAwaitExit(condition, NAME, false));

        var report = detector.analyze();
        assertEquals(1, report.unconfirmedWaits.stream().filter(n -> n.contains("earlier round")).count(),
                "round one's await never exited and must still be noted; if the context's round start "
                        + "skipped this detector, round two's exit closed it. Report:\n" + report);
    }
}
