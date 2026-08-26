package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.ScopedContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for ScopedContextService.
 *
 * ========================================================================
 * DETECTOR: ScopedValueMisuseDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - A sequential @Test PASSES (but gives false confidence)
 * - The same test with @AsyncTest FAILS (exposing the real concurrent bug)
 *
 * THE BUG:
 * ScopedContextService.getCurrentUser() reads USER_ID (ThreadLocal) without
 * checking for null. When called outside a runWithUser() scope it returns null,
 * causing a downstream NullPointerException or IllegalStateException. In a
 * concurrent test some invocations reach getCurrentUser() from an unbound thread.
 *
 * WHY @Test PASSES:
 * The sequential test always wraps the call inside runWithUser(), establishing
 * the binding before getCurrentUser() is called. The guard is never triggered.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * Some threads call getCurrentUser() directly without establishing a binding.
 * ScopedValueMisuseDetector records every recordGetCalled() that lacks a
 * preceding recordBindingEntered() and reports them as unbound-get issues.
 *
 * DETECTORS TRIGGERED:
 *   ScopedValueMisuseDetector — primary: detects get() calls without a binding
 *
 * FIX: guard with USER_ID.isBound() before every USER_ID.get() call.
 */
class ScopedContextServiceTest {

    private static final String SV_NAME = "USER_ID";

    private ScopedContextService service;

    @BeforeEach
    void setUp() {
        service = new ScopedContextService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void testRunWithUser_providesBinding() {
        service.runWithUser("alice", () -> {
            String user = service.getCurrentUser();
            assertEquals("alice", user, "Bound user must be 'alice'");
        });
    }

    @Test
    void testRunWithUser_nestedCall_returnsCorrectUser() {
        service.runWithUser("bob", () -> {
            String result = service.processRequest();
            assertTrue(result.contains("bob"), "Result must reference the bound user 'bob'");
        });
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes unbound get() calls
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see unbound get() detected by ScopedValueMisuseDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectScopedValueMisuse = true, failOn = FailOn.LOW)

    void testGetCurrentUser_concurrent_detectsUnboundGet() {
        // get() with no preceding recordBindingEntered() is the whole finding: this thread is
        // reading a ScopedValue it never bound.
        AsyncTestContext.scopedValueMisuseDetector()
                .recordGetCalled(SV_NAME, Thread.currentThread());

        // getCurrentUser() throws IllegalStateException precisely because there is no binding,
        // which is the bug rather than a surprise. Nothing here asserts that a user comes back:
        // the previous version did, and since no binding exists the assertion failed on all
        // eight threads, so the demonstration failed on its own broken expectation instead of
        // on ScopedValueMisuseDetector's finding. See issue #363.
        try {
            service.getCurrentUser();
        } catch (IllegalStateException unbound) {
            // Expected: this is what reading an unbound ScopedValue does.
        }
    }
}
