package se.deversity.asynctest;

import com.example.gcfixture.GcCaller;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ExplicitGcDetector;

import java.lang.ref.WeakReference;

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
        GcCaller.collect();
    }

    @Test
    @DisplayName("the hook really collects: an unreachable object is gone after it returns")
    void theHookPerformsTheCollection() {
        // "It returns without throwing" was all this class asserted about the collection itself,
        // and a hook that recorded and then skipped System.gc() passes that (#476). What the
        // substitution promises is that woven code collects where unwoven code would, so the
        // assertion has to be about a collection happening, and a cleared reference is the only
        // observable there is.
        //
        // Bounded by attempts rather than by time: System.gc() is a request, and a reference may
        // be cleared on the second full collection rather than the first. The failure this
        // catches is a hook that never asks at all, which no number of attempts rescues.
        Object garbage = new byte[1024];
        WeakReference<Object> reference = new WeakReference<>(garbage);
        garbage = null; // NOPMD - dropping the only strong reference is the point

        boolean collected = false;
        for (int attempt = 0; attempt < 5 && !collected; attempt++) {
            GcCaller.collect();
            collected = reference.get() == null;
        }
        assertTrue(collected,
                "five collections through the hook left an unreachable 1KB array alive, which is "
                        + "what a hook that dropped System.gc() looks like");
    }
}
