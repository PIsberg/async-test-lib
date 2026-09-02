package se.deversity.asynctest.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestListener;
import se.deversity.asynctest.AsyncTestListenerRegistry;
import se.deversity.asynctest.BeforeEachInvocation;
import se.deversity.asynctest.E2E;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Pins that a body failing with something other than an {@code AssertionError} still reaches
 * listeners as {@link AsyncTestListener#onTestFailed}.
 *
 * <p>{@code ConcurrencyRunner.execute} has two failure branches: {@code catch (AssertionError)}
 * for a failed assertion or a failed gate, and {@code catch (Throwable)} for everything else a
 * round can throw - a runtime exception out of the body, an error out of a lifecycle hook. The
 * first is exercised by every failing-assertion test in the suite; PIT's scoped run on
 * 2026-09-02 reported the second's {@code fireTestFailed} and {@code quiesceWorkers} calls with
 * no coverage at all (#426). A dashboard listening for failures would go blind to exactly the
 * failures that are not assertions, and nothing would say so.
 *
 * <p>The fixture fails from a {@code @BeforeEachInvocation} hook rather than from the body, and
 * that choice is the finding behind this test: an exception out of the <em>body</em> never
 * reaches the {@code Throwable} branch, because the workers aggregate it into an
 * {@code AssertionError} ("2 concurrent thread(s) failed"), which is the other branch. The first
 * draft of this fixture threw from the body and was told so by the assertion below. A hook runs
 * on the runner thread itself, and its failure is rethrown as a {@code RuntimeException}
 * carrying the hook's name, which is what the listener must receive.
 */
@E2E
class ConcurrencyRunnerThrowablePathBindingTest {

    private static final List<Throwable> FAILURES = new CopyOnWriteArrayList<>();

    /** Fails the round from its before-hook, on the runner thread, with something not an assertion. */
    public static class ThrowingHook {
        @BeforeEachInvocation
        void hook() {
            throw new IllegalStateException("boom from the hook");
        }

        @AsyncTest(threads = 2, invocations = 1)
        void body() {
            // Never reached: the hook fails first.
        }
    }

    @Test
    @DisplayName("a non-assertion failure out of a round reaches listeners as onTestFailed, unwrapped")
    void nonAssertionFailureIsDeliveredToListeners() {
        FAILURES.clear();
        AsyncTestListener capture = new AsyncTestListener() {
            @Override
            public void onTestFailed(Throwable cause) {
                FAILURES.add(cause);
            }
        };
        Events events;
        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(capture)) {
            events = EngineTestKit.engine("junit-jupiter")
                    .selectors(selectClass(ThrowingHook.class))
                    .execute()
                    .testEvents();
        }

        assertTrue(events.failed().count() >= 1,
                "the fixture must fail its round, or there is no failure to deliver");
        assertFalse(FAILURES.isEmpty(),
                "a hook failure takes the catch (Throwable) branch of ConcurrencyRunner.execute, "
                        + "and that branch must fire onTestFailed like the AssertionError branch "
                        + "does; losing it blinds every listener to every failure that is not an "
                        + "assertion (#426)");
        assertEquals(1, FAILURES.size(),
                "one failed run, one failure event; got " + FAILURES);
        Throwable delivered = FAILURES.get(0);
        assertTrue(String.valueOf(delivered.getMessage()).contains("@BeforeEachInvocation method 'hook' threw"),
                "the delivered cause names the hook that failed, which is what the runner wraps a "
                        + "hook failure in: " + delivered);
        assertTrue(delivered.getCause() instanceof IllegalStateException
                        && String.valueOf(delivered.getCause().getMessage()).contains("boom from the hook"),
                "and carries the hook's own exception as its cause, so a listener can see what the "
                        + "test author threw: " + delivered.getCause());
    }
}
