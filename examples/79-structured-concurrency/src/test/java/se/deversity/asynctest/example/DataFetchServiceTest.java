package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.DataFetchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for DataFetchService.
 *
 * ========================================================================
 * DETECTOR: StructuredConcurrencyMisuseDetector
 * ========================================================================
 *
 * This test demonstrates a common pattern where:
 * - Sequential @Test PASSES (scope leaks but doesn't immediately crash)
 * - The same scenario with @AsyncTest FAILS (unclosed scopes accumulate)
 *
 * THE BUG:
 * DataFetchService.fetchAll() opens a StructuredTaskScope.ShutdownOnFailure,
 * forks tasks, and joins — but never calls scope.close(). Each call leaks the
 * scope and its virtual threads. Under concurrent load, leaked scopes exhaust
 * the virtual-thread carrier pool.
 *
 * WHY @Test PASSES:
 * A single call with a small ID list completes quickly. The GC or JVM shutdown
 * hooks may eventually clean up the leaked scope before visible failures occur.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * StructuredConcurrencyMisuseDetector tracks scope open/fork/join/close events.
 * Scopes that appear in opened-but-not-closed are flagged as unclosed in the
 * analysis report.
 *
 * DETECTORS TRIGGERED:
 *   StructuredConcurrencyMisuseDetector — primary: detects unclosed scopes
 *
 * FIX: always use try-with-resources for StructuredTaskScope.
 */
class DataFetchServiceTest {

    private DataFetchService service;

    @BeforeEach
    void setUp() {
        service = new DataFetchService();
    }

    // -----------------------------------------------------------------------
    // Part 1: @Test — sequential, passes cleanly
    // -----------------------------------------------------------------------

    @Test
    void test_singleThread_fetchAll_returnsResults() throws Exception {
        var results = service.fetchAll(List.of("id-1", "id-2"));
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.startsWith("result-for-")));
    }

    @Test
    void test_singleThread_emptyIds_returnsEmpty() throws Exception {
        var results = service.fetchAll(List.of());
        assertTrue(results.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes unclosed StructuredTaskScope
    // -----------------------------------------------------------------------

    @Disabled("Remove @Disabled to see unclosed StructuredTaskScope detected by StructuredConcurrencyMisuseDetector")
    @AsyncTest(threads = 8, invocations = 30, detectAll = false, detectStructuredConcurrencyIssues = true)
    void test_concurrent_detectsUnclosedScope() throws Exception {
        var detector = AsyncTestContext.get().structuredConcurrencyMisuseDetector();

        // Tell the detector a new scope is being opened.
        String scopeId = detector.recordScopeOpened("ShutdownOnFailure");
        detector.recordSubtaskForked(scopeId);
        detector.recordJoinCalled(scopeId);

        // Invoke the buggy service — it never calls close() on the scope.
        service.fetchAll(List.of("a", "b", "c"));

        // BUG: recordScopeClosed(scopeId) is intentionally not called,
        // mirroring the missing scope.close() in the service.
    }
}
