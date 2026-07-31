package se.deversity.asynctest.extension;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies @AsyncTest(threadCounts={...}) fans out to one JUnit invocation per
 * matrix entry and that the display name reflects the entry's thread count.
 *
 * <p>The runner's per-thread execution semantics are covered elsewhere; this
 * class only needs to confirm the matrix wiring in AsyncTestExtension.
 */
class AsyncTestExtensionMatrixTest {

    @Test
    void matrixProducesOneInvocationPerThreadCount() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(MatrixFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(3).succeeded(3).failed(0));

        // Each invocation's display name embeds its thread count.
        Set<String> names = tests.finished().stream()
                .map(e -> e.getTestDescriptor().getDisplayName())
                .collect(Collectors.toSet());

        Set<String> expected = new HashSet<>(List.of(
                "[AsyncTest] 2 threads x 1 invocations",
                "[AsyncTest] 4 threads x 1 invocations",
                "[AsyncTest] 8 threads x 1 invocations"));
        assertEquals(expected, names,
                "Each matrix entry must produce a JUnit invocation with the matching display name");
    }

    @Test
    void emptyMatrixFallsBackToLegacyThreadsField() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(LegacyFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(1).succeeded(1).failed(0));

        String name = tests.finished().stream()
                .map(e -> e.getTestDescriptor().getDisplayName())
                .findFirst().orElseThrow();
        assertEquals("[AsyncTest] 3 threads x 1 invocations", name,
                "Legacy path must use @AsyncTest(threads=) verbatim");
    }

    // ---- Fixtures driven through JUnit-platform-testkit ----

    static class MatrixFixture {
        @AsyncTest(invocations = 1, threadCounts = {2, 4, 8}, timeoutMs = 10_000,
                detectAll = false, licenseMockMode = true)
        void matrixTest() {
            // No assertion needed; presence of the test events is what we verify.
        }
    }

    static class LegacyFixture {
        @AsyncTest(invocations = 1, threads = 3, timeoutMs = 10_000,
                detectAll = false, licenseMockMode = true)
        void legacyTest() {
            // No assertion needed.
        }
    }
}
