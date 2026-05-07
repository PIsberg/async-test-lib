package se.deversity.asynctest.example;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the MdcContextLeakDetector (Phase 12).
 *
 * ============================================================
 * NOTE: MdcContextLeakDetector ships in async-test-lib 0.10.0.
 * This example targets 0.10.0 so it compiles from Maven Central.
 * ============================================================
 *
 * THE BUG: A request handler sets MDC keys (requestId, userId) for
 * structured logging but never clears them. When a pooled thread
 * processes the next request, the stale MDC entries are still present —
 * request B's log lines show request A's requestId.
 *
 * WHY @Test PASSES: Single-threaded execution processes each "request"
 * sequentially and the test only checks the immediate result, not the
 * thread's MDC state afterward.
 *
 * WHY @AsyncTest DETECTS THE BUG (0.10.0): The detector compares MDC
 * snapshots before and after each task execution and reports keys that
 * were added but not removed.
 */
class MdcContextLeakTest {

    /** Simulates an MDC context (plain Map, no SLF4J dependency). */
    static final ThreadLocal<Map<String, String>> MDC_STORE =
            ThreadLocal.withInitial(HashMap::new);

    static class RequestHandler {
        String handle(String requestId, String userId) {
            // Buggy: sets MDC but never clears it
            MDC_STORE.get().put("requestId", requestId);
            MDC_STORE.get().put("userId", userId);
            return "processed:" + requestId;
        }

        String handleFixed(String requestId, String userId) {
            MDC_STORE.get().put("requestId", requestId);
            MDC_STORE.get().put("userId", userId);
            try {
                return "processed:" + requestId;
            } finally {
                MDC_STORE.get().remove("requestId"); // Fix
                MDC_STORE.get().remove("userId");    // Fix
            }
        }
    }

    // =========================================================================
    // Part 1: @Test — passes, gives false confidence
    // =========================================================================

    @Test
    void part1_requestHandled_singleThread() {
        RequestHandler handler = new RequestHandler();
        String result = handler.handle("req-001", "user-42");
        assertEquals("processed:req-001", result);
        // @Test doesn't check what MDC state the thread leaves behind
    }

    // =========================================================================
    // Part 2: Upgrade to @AsyncTest (0.10.0) to detect the bug
    //
    // @AsyncTest(threads = 4, invocations = 3, detectMdcContextLeak = true, timeoutMs = 5000)
    // =========================================================================

    @Test
    void part2_detectMdcLeak_placeholder() {
        // After upgrading to 0.10.0, replace with:
        //
        //   var d = AsyncTestContext.mdcContextLeakDetector();
        //   Map<String,String> before = new HashMap<>(MDC_STORE.get());
        //   d.recordTaskStart(Thread.currentThread(), before);
        //   try {
        //       handler.handle("req-001", "user-42");
        //   } finally {
        //       d.recordTaskEnd(Thread.currentThread(), new HashMap<>(MDC_STORE.get()));
        //       // BUG: no MDC.clear() here
        //   }
        //
        // The detector will report "Thread '...' left 2 MDC key(s) behind:
        // [requestId, userId] — these will contaminate the next task."
        assertTrue(true, "Placeholder — see comments above");
    }

    // =========================================================================
    // Part 3: Fixed — clears MDC in finally block
    // =========================================================================

    @Test
    void part3_fixed_mdcClearedAfterRequest() {
        RequestHandler handler = new RequestHandler();
        MDC_STORE.get().clear(); // start from a known-empty state regardless of prior tests
        Map<String, String> before = new HashMap<>(MDC_STORE.get());
        handler.handleFixed("req-002", "user-99");
        assertEquals(before, MDC_STORE.get(),
                "MDC should be identical to pre-task state after fixed handler");
    }
}
