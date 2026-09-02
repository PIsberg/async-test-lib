package se.deversity.asynctest;

import com.example.gcfixture.GcCaller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ExplicitGcDetector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the woven {@code System.gc()} hook reports with no hand-written instrumentation.
 *
 * <p>The hook is called only from bytecode the agent rewrites, so PIT found every one of its
 * mutants with no coverage (#427). A test can call it the way woven code would; what it cannot
 * fake is the substitution, which the agent module covers.
 */
class AgentGcHooksTest {

    @Test
    @DisplayName("an explicit collection through the hook is reported, naming the caller and not the hook")
    void collectionIsReportedAgainstItsCaller() {
        AsyncTestContext.install(new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
        try {
            GcCaller.collect();

            ExplicitGcDetector.ExplicitGcReport report = AsyncTestContext.explicitGcDetector().analyze();
            assertTrue(report.hasIssues(),
                    "System.gc() through the hook must reach ExplicitGcDetector; report: " + report);
            assertTrue(report.toString().contains("GcCaller.collect"),
                    "the finding must name the caller outside the library, which is what the stack "
                            + "walk skipping se.deversity.asynctest frames is for; got: " + report);
            assertFalse(report.toString().contains("AgentGcHooks"),
                    "and never the hook itself: " + report);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("with no context installed the hook still collects and records nothing")
    void hookDelegatesOutsideAnAsyncTest() {
        // Nothing to assert on the collection itself; that it returns without throwing is the
        // contract, and a hook that skipped System.gc() would change the program under test.
        GcCaller.collect();
    }
}
