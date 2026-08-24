package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.ScopeResultEscapeDetector;
import se.deversity.asynctest.example.service.OrderFetcher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for OrderFetcher.
 *
 * ========================================================================
 * DETECTOR: ScopeResultEscapeDetector
 *           (DetectorType.SCOPE_RESULT_ESCAPE)
 * ========================================================================
 *
 * Structured concurrency's whole guarantee is that a subtask does not
 * outlive its scope. JDK 25's joiners returned a Stream, which is lazy
 * and single-use, so holding one past close() tended to fail early and
 * loudly. JDK 26 returns a List - the ergonomic win everyone wanted, and
 * a value that assigns to a field without a murmur.
 *
 * A handle read after close() has no happens-before edge to anything the
 * scope did on the way out. A handle read by a thread that never called
 * join() has none to the subtasks either: join() on the owner is where
 * that edge is made.
 *
 * STRUCTURED_TASK_SCOPE_MISUSE covers reading a result TOO EARLY, before
 * join(). This detector covers too late, or on the wrong thread.
 *
 * THE BUG:
 *   - the List<Subtask<T>> assigned out of the try-with-resources and
 *     read afterwards
 *
 * THE FIX:
 *   - extract the values inside the block and return those
 */
class OrderFetcherTest {

    private ScopeResultEscapeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ScopeResultEscapeDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the bug. The result list is assigned out of the block, so the
    // handles are read after the scope that guaranteed them is gone.
    // -----------------------------------------------------------------------

    @Test
    void resultsReadAfterTheScopeClosed_isDetected() {
        List<OrderFetcher.Handle> results;
        var scope = new OrderFetcher();
        detector.recordScopeOpened("scope-1", Thread.currentThread());
        try {
            scope.fork("order-a");
            scope.fork("order-b");
            results = scope.join();
            detector.recordJoinCompleted("scope-1");
            detector.recordResultHandle(results, "orderResults", "scope-1");
        } finally {
            scope.close();
            detector.recordScopeClosed("scope-1");
        }

        // Outside the structure now. The handle still answers, which is the trap.
        detector.recordHandleRead(results, Thread.currentThread());
        assertFalse(results.get(0).isScopeOpen(), "the scope it belonged to is closed");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "the list escaped its scope:\n" + report);

        var v = report.structuredViolations.stream()
                .filter(x -> "resultReadAfterScopeClose".equals(x.attributes().get("issue")))
                .findFirst()
                .orElseThrow();
        assertEquals(IssueSeverity.CRITICAL, v.severity());
        assertEquals(1, v.attributes().get("readsAfterClose"));
    }

    // -----------------------------------------------------------------------
    // Part 2: the fix. Read inside the block and return the values, not the
    // handles. Same detector calls, same order, nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void valuesExtractedInsideTheScope_isClean() {
        List<String> orders;
        var scope = new OrderFetcher();
        detector.recordScopeOpened("scope-1", Thread.currentThread());
        try {
            scope.fork("order-a");
            scope.fork("order-b");
            List<OrderFetcher.Handle> results = scope.join();
            detector.recordJoinCompleted("scope-1");
            detector.recordResultHandle(results, "orderResults", "scope-1");
            detector.recordHandleRead(results, Thread.currentThread());
            orders = results.stream().map(OrderFetcher.Handle::get).toList();
        } finally {
            scope.close();
            detector.recordScopeClosed("scope-1");
        }

        assertEquals(List.of("order-a", "order-b"), orders);

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "values read inside the structure carry the scope's guarantee:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 3: the other escape. join() on the owner is what publishes the
    // subtasks' writes; a thread that never called it has no edge to them.
    // -----------------------------------------------------------------------

    @Test
    void resultsReadOnAnotherThread_isDetected() throws InterruptedException {
        var scope = new OrderFetcher();
        detector.recordScopeOpened("scope-1", Thread.currentThread());
        scope.fork("order-a");
        List<OrderFetcher.Handle> results = scope.join();
        detector.recordJoinCompleted("scope-1");
        detector.recordResultHandle(results, "orderResults", "scope-1");

        Thread consumer = new Thread(
                () -> detector.recordHandleRead(results, Thread.currentThread()), "report-writer");
        consumer.start();
        consumer.join();

        scope.close();
        detector.recordScopeClosed("scope-1");

        var report = detector.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("report-writer")));
    }

    // -----------------------------------------------------------------------
    // Part 4: the list a JDK 26 joiner hands back is unmodifiable. Reaching
    // for a sort in place means the caller took it for a private copy.
    // -----------------------------------------------------------------------

    @Test
    void mutatingTheReturnedList_isDetected() {
        var scope = new OrderFetcher();
        detector.recordScopeOpened("scope-1", Thread.currentThread());
        scope.fork("order-b");
        scope.fork("order-a");
        List<OrderFetcher.Handle> results = scope.join();
        detector.recordJoinCompleted("scope-1");
        detector.recordResultHandle(results, "orderResults", "scope-1");

        assertThrows(UnsupportedOperationException.class,
                () -> results.sort((x, y) -> x.get().compareTo(y.get())),
                "the returned list is unmodifiable");
        detector.recordHandleMutation(results, Thread.currentThread());

        scope.close();
        detector.recordScopeClosed("scope-1");

        var report = detector.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("was modified 1 time(s)")));
    }
}
