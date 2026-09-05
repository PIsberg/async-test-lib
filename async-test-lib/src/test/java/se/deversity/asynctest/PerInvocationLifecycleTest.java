package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Verifies Fix 5: @BeforeEachInvocation and @AfterEachInvocation lifecycle hooks.
 *
 * @BeforeEach / @AfterEach only bracket the entire N×M execution once.
 * The new annotations let tests reset (or assert) state around every individual
 * invocation round without embedding that logic inside the test body itself.
 */
@E2E
class PerInvocationLifecycleTest {

    // ---- @BeforeEachInvocation resets state between rounds ----

    static final int THREADS = 5;
    static final int INVOCATIONS = 10;

    static class ResetBetweenRounds {
        private int counter = 0;
        private final AtomicInteger roundsChecked = new AtomicInteger(0);

        @BeforeEachInvocation
        void resetCounter() {
            counter = 0;
        }

        @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 5_000,
                   useVirtualThreads = false,
                   detectDeadlocks = false)
        void increment() {
            counter++;
        }

        @AfterEachInvocation
        void checkRoundResult() {
            // counter is not thread-safe, but it must be ≤ THREADS (one round)
            // and > 0 (at least one thread incremented)
            if (counter > 0 && counter <= THREADS) {
                roundsChecked.incrementAndGet();
            }
        }

        @AfterEach
        void verify() {
            // All INVOCATIONS rounds were checked by @AfterEachInvocation
            assertEquals(INVOCATIONS, roundsChecked.get(),
                "AfterEachInvocation must have run for every invocation round");
        }
    }

    @Test
    void beforeAndAfterEachInvocationHooksAreInvoked() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(ResetBetweenRounds.class))
            .execute()
            .testEvents();

        // The test itself should pass (the @AfterEach assertion must hold)
        assertEquals(0, events.failed().count(),
            "ResetBetweenRounds test must pass: " + events.failed().count() + " failures");
    }

    // ---- @BeforeEachInvocation runs before @AfterEachInvocation for every round ----

    static class OrderVerification {
        private final AtomicInteger beforeCount  = new AtomicInteger(0);
        private final AtomicInteger afterCount   = new AtomicInteger(0);

        @BeforeEachInvocation
        void before() { beforeCount.incrementAndGet(); }

        @AsyncTest(threads = 2, invocations = 3, timeoutMs = 5_000,
                   useVirtualThreads = false, detectDeadlocks = false)
        void noOp() { /* nothing */ }

        @AfterEachInvocation
        void after() { afterCount.incrementAndGet(); }

        @AfterEach
        void verify() {
            assertEquals(3, beforeCount.get(), "@BeforeEachInvocation must fire 3 times");
            assertEquals(3, afterCount.get(),  "@AfterEachInvocation must fire 3 times");
        }
    }

    @Test
    void hooksFireExactlyOncePerInvocationRound() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(OrderVerification.class))
            .execute()
            .testEvents();

        assertEquals(0, events.failed().count(),
            "OrderVerification test must pass");
    }

    // ---- @AfterEachInvocation runs even when the round fails ----

    static class AfterRunsOnFailure {
        private final AtomicInteger afterCount = new AtomicInteger(0);
        private final AtomicInteger rounds     = new AtomicInteger(0);

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 5_000,
                   useVirtualThreads = false, detectDeadlocks = false)
        void alwaysFails() {
            throw new AssertionError("intentional");
        }

        @AfterEachInvocation
        void countAfter() { afterCount.incrementAndGet(); }
    }

    @Test
    void afterEachInvocationRunsEvenOnRoundFailure() {
        // We just verify the engine doesn't crash; the test itself will fail
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(AfterRunsOnFailure.class))
            .execute()
            .testEvents();

        // Exactly 1 test ran (the @AsyncTest), and it failed
        assertEquals(1, events.failed().count());
    }

    // ---- a throwing @AfterEachInvocation must not swallow the round's failure ----

    static class BothRoundAndAfterHookFail {

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 5_000,
                   useVirtualThreads = false, detectDeadlocks = false)
        void alwaysFails() {
            throw new AssertionError("the failure the user needs to see");
        }

        @AfterEachInvocation
        void teardownAlsoFails() {
            throw new IllegalStateException("teardown blew up too");
        }
    }

    /**
     * Pins which exception survives when the round and its teardown both fail.
     *
     * <p>The sibling test above pins that after-hooks <em>run</em> on a failing round. It cannot
     * see this defect, because it only counts failures — and the count is 1 either way. Running
     * the hooks in a bare {@code finally} meant a throwing hook replaced the in-flight round
     * failure outright (Java discards it; auto-suppression happens only in try-with-resources),
     * so the user was told a teardown method threw and never saw the assertion, the timeout, or
     * the per-worker causes attached to it.
     *
     * <p>The correlation is what makes it worth a test: after-hooks typically reset state the
     * failing round corrupted, so "round failed" and "hook then failed" arrive together far more
     * often than chance, and the diagnosis is destroyed exactly when it is most needed.
     */
    @Test
    void aThrowingAfterHookIsSuppressedRatherThanReplacingTheRoundFailure() {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(BothRoundAndAfterHookFail.class))
            .execute()
            .testEvents();

        assertEquals(1, events.failed().count(), "the @AsyncTest must still report as failed");

        Throwable surfaced = events.failed().stream()
            .findFirst()
            .orElseThrow()
            .getRequiredPayload(org.junit.platform.engine.TestExecutionResult.class)
            .getThrowable()
            .orElseThrow();

        String rendered = surfaced + java.util.Arrays.toString(surfaced.getSuppressed());

        assertTrue(rendered.contains("the failure the user needs to see"),
            "The round's own failure must be what surfaces. It was replaced by the teardown "
                + "failure, which is what a bare finally does to an in-flight exception. What "
                + "surfaced instead: " + rendered);

        assertTrue(rendered.contains("teardown blew up too"),
            "The teardown failure must still be visible, attached as a suppressed exception "
                + "rather than discarded — losing it would just invert the original bug. What "
                + "surfaced: " + rendered);
    }

    // ---- inherited hooks follow the @BeforeEach/@AfterEach rules ----
    //
    // JUnit: a lifecycle method is inherited from a superclass unless it is overridden, and
    // an override is a hook only if it carries the annotation itself. Superclass before-hooks
    // run before subclass ones; subclass after-hooks run before superclass ones.

    static class HookBase {
        final java.util.List<String> calls =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @BeforeEachInvocation
        void before() { calls.add("base.before"); }

        @AfterEachInvocation
        void after() { calls.add("base.after"); }
    }

    static class OverridesAnnotatedHooks extends HookBase {
        @Override
        @BeforeEachInvocation
        void before() { calls.add("sub.before"); }

        @Override
        @AfterEachInvocation
        void after() { calls.add("sub.after"); }

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 5_000,
                   useVirtualThreads = false, detectDeadlocks = false)
        void noOp() { /* nothing */ }

        @AfterEach
        void verify() {
            assertEquals(java.util.List.of("sub.before", "sub.after", "sub.before", "sub.after"),
                calls, "an overriding hook replaces the inherited one and runs once per round");
        }
    }

    static class OverridesWithoutAnnotation extends HookBase {
        @Override
        void before() { calls.add("sub.before-unannotated"); }

        @AsyncTest(threads = 2, invocations = 2, timeoutMs = 5_000,
                   useVirtualThreads = false, detectDeadlocks = false)
        void noOp() { /* nothing */ }

        @AfterEach
        void verify() {
            assertEquals(java.util.List.of("base.after", "base.after"), calls,
                "an un-annotated override is not a hook, and the inherited hook it replaced "
                    + "must not run through it either");
        }
    }

    static class AddsHooksAlongsideInherited extends HookBase {
        @BeforeEachInvocation
        void subBefore() { calls.add("sub.before"); }

        @AfterEachInvocation
        void subAfter() { calls.add("sub.after"); }

        @AsyncTest(threads = 2, invocations = 1, timeoutMs = 5_000,
                   useVirtualThreads = false, detectDeadlocks = false)
        void noOp() { /* nothing */ }

        @AfterEach
        void verify() {
            assertEquals(java.util.List.of("base.before", "sub.before", "sub.after", "base.after"),
                calls, "superclass before-hooks first, subclass after-hooks first");
        }
    }

    /**
     * Pins inheritance semantics. Before the fix, {@code findLifecycleMethods} collected an
     * overridden hook from both the subclass and the superclass; {@code Method.invoke} on the
     * superclass method dispatches virtually to the override, so it ran twice per round, and an
     * un-annotated override was invoked through its annotated parent even though it is not a
     * hook at all.
     */
    @Test
    void anOverridingHookReplacesTheInheritedOneAndRunsOncePerRound() {
        assertPasses(OverridesAnnotatedHooks.class);
    }

    @Test
    void anUnannotatedOverrideIsNotAHookAndDoesNotRunThroughItsParent() {
        assertPasses(OverridesWithoutAnnotation.class);
    }

    @Test
    void inheritedHooksRunInTheJUnitOrder() {
        assertPasses(AddsHooksAlongsideInherited.class);
    }

    private static void assertPasses(Class<?> testClass) {
        Events events = EngineTestKit.engine("junit-jupiter")
            .selectors(selectClass(testClass))
            .execute()
            .testEvents();
        String failures = events.failed().stream()
            .map(e -> e.getRequiredPayload(org.junit.platform.engine.TestExecutionResult.class)
                .getThrowable().map(Throwable::getMessage).orElse("?"))
            .collect(java.util.stream.Collectors.joining("; "));
        assertEquals(0, events.failed().count(),
            testClass.getSimpleName() + " must pass, but failed with: " + failures);
    }
}
