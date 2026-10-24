package se.deversity.asynctest.runner;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import se.deversity.asynctest.AsyncTestConfig;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A body that returns a {@link CompletionStage} counts as done when the stage completes, not when
 * the method returns.
 *
 * <p>The only route to this is the public {@link ConcurrencyRunner#execute}: JUnit runs no
 * {@code @TestTemplate} that returns a value (it reports "must not return a value. It will not be
 * executed" as a discovery warning), and {@code AsyncTestRunner}'s body is void. Nothing in the suite
 * took that route, so the wait could be deleted with every test green.
 */
class ConcurrencyRunnerCompletionStageTest {

    private static final int THREADS = 2;
    private static final int INVOCATIONS = 2;

    private static final ExecutorService ASYNC = Executors.newCachedThreadPool();

    @AfterAll
    static void shutDown() {
        ASYNC.shutdownNow();
    }

    /** The bodies; public so the runner's reflective call needs nothing opened. */
    public static final class Bodies {
        final AtomicInteger completed = new AtomicInteger();
        final List<Integer> completedAtStart = new CopyOnWriteArrayList<>();

        public CompletionStage<Void> finishesLater() {
            completedAtStart.add(completed.get());
            return CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completed.incrementAndGet();
            }, ASYNC);
        }

        public CompletionStage<Void> failsLater() {
            return CompletableFuture.runAsync(() -> {
                throw new IllegalStateException("the stage's own failure");
            }, ASYNC);
        }

        public CompletionStage<Void> neverCompletes() {
            return new CompletableFuture<>();
        }
    }

    private static void execute(Bodies target, String method, long timeoutMs) throws Throwable {
        Method m = Bodies.class.getMethod(method);
        ConcurrencyRunner.execute(new ReflectiveInvocationContext<>() {
            @Override public Class<?> getTargetClass() { return Bodies.class; }
            @Override public Optional<Object> getTarget() { return Optional.of(target); }
            @Override public Method getExecutable() { return m; }
            @Override public List<Object> getArguments() { return List.of(); }
        }, AsyncTestConfig.builder().threads(THREADS).invocations(INVOCATIONS).timeoutMs(timeoutMs)
                .licenseMockMode(true).build());
    }

    /**
     * Read at the start of each body, not after the run: the analysis after the last round outlasts
     * a short stage, so a count taken once the run returned matched even with no wait at all.
     */
    @Test
    void aRoundEndsOnlyAfterEveryStageItStartedCompleted() throws Throwable {
        Bodies bodies = new Bodies();

        execute(bodies, "finishesLater", 30_000);

        List<Integer> seen = bodies.completedAtStart.stream().sorted().toList();
        assertEquals(List.of(0, 0, THREADS, THREADS), seen,
                "the second round began while the first round's stages were still running: " + seen);
        assertEquals(THREADS * INVOCATIONS, bodies.completed.get());
    }

    @Test
    void aStageThatFailsFailsTheRun_withItsOwnException() {
        Throwable thrown = assertThrows(Throwable.class, () -> execute(new Bodies(), "failsLater", 30_000));

        boolean found = false;
        for (Throwable t = thrown; t != null && !found; t = t.getCause()) {
            found = t instanceof IllegalStateException && "the stage's own failure".equals(t.getMessage());
        }
        assertTrue(found, "the stage's exception must be the failure the run reports: " + thrown);
    }

    @Test
    void aStageThatNeverCompletesDoesNotPass() {
        long start = System.nanoTime();

        assertThrows(Throwable.class, () -> execute(new Bodies(), "neverCompletes", 1_000));

        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 20,
                "the wait is bounded by the round timeout");
    }
}
