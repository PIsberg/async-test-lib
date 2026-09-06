package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LazyConstantMisuseDetector}.
 */
class LazyConstantMisuseDetectorTest {

    private LazyConstantMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LazyConstantMisuseDetector();
    }

    @Test
    void aSupplierThatThrewInAnEarlierRoundDoesNotLookReentrantInTheNext() {
        Thread t = Thread.currentThread();

        // Round one: the supplier throws, so the caller never reaches recordComputeEnd. Platform
        // worker threads are pooled, so the same thread comes back for round two.
        detector.recordComputeStart("CONFIG", t);

        detector.markInvocationStart();

        // Round two on the reused thread: a fresh, well-behaved computation.
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "value");

        assertTrue(detector.analyze().getReentrantIssues().isEmpty(),
            "round two's supplier is not re-entering round one's - the runner's latch separates "
                + "them. The stale in-flight entry must not survive the round boundary: "
                + detector.analyze().getReentrantIssues());
    }

    // ---- Happy path ----

    @Test
    void noIssues_whenComputedOnceThenRead() {
        Thread t = Thread.currentThread();
        detector.recordGet("CONFIG", t);
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "db-url=localhost");
        detector.recordGet("CONFIG", t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no issues: " + report);
        assertEquals(2, report.getTotalGets());
        assertEquals(1, report.getTotalComputes());
    }

    @Test
    void noIssues_forDistinctConstants() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("A", t);
        detector.recordComputeEnd("A", t, "a");
        detector.recordComputeStart("B", t);
        detector.recordComputeEnd("B", t, "b");

        assertFalse(detector.analyze().hasIssues());
    }

    // ---- Reentrant computation ----

    @Test
    void detectsReentrantSupplier() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeStart("CONFIG", t); // supplier re-entered itself — violation

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getReentrantIssues().size());
        String issue = report.getReentrantIssues().get(0);
        assertTrue(issue.contains("CONFIG"), issue);
        assertTrue(issue.contains("IllegalStateException"), issue);
    }

    @Test
    void noReentrancy_whenComputationEndsBeforeRestart() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "v");
        detector.recordComputeStart("CONFIG", t); // sequential, not reentrant
        detector.recordComputeEnd("CONFIG", t, "v");

        assertTrue(detector.analyze().getReentrantIssues().isEmpty());
    }

    @Test
    void noReentrancy_forDifferentConstantsOnSameThread() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("A", t);
        detector.recordComputeStart("B", t); // nested but different constant — fine
        detector.recordComputeEnd("B", t, "b");
        detector.recordComputeEnd("A", t, "a");

        assertTrue(detector.analyze().getReentrantIssues().isEmpty());
    }

    // ---- Null-producing supplier ----

    @Test
    void detectsNullProducingSupplier() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, null); // NPE on JDK 26

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getNullValueIssues().size());
        String issue = report.getNullValueIssues().get(0);
        assertTrue(issue.contains("CONFIG"), issue);
        assertTrue(issue.contains("NullPointerException"), issue);
    }

    // ---- Multiple computations ----

    @Test
    void detectsComputationRunningMoreThanOnce() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "v");
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "v"); // second completion — at-most-once broken

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getMultipleComputeIssues().size());
        assertTrue(report.getMultipleComputeIssues().get(0).contains("at-most-once"));
    }

    // ---- Non-deterministic supplier ----

    @Test
    void detectsNonDeterministicSupplier() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "first");
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "second"); // different value — non-deterministic

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getNonDeterministicIssues().size());
        String issue = report.getNonDeterministicIssues().get(0);
        assertTrue(issue.contains("first"), issue);
        assertTrue(issue.contains("second"), issue);
    }

    @Test
    void noNonDeterminism_whenRepeatComputationsProduceEqualValues() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "same");
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "same");

        assertTrue(detector.analyze().getNonDeterministicIssues().isEmpty());
    }

    // ---- Compute convoy ----

    @Test
    void detectsComputeConvoy_whenManyThreadsBlockBehindOneComputation() throws Exception {
        Thread computer = new Thread(() ->
                detector.recordComputeStart("CONFIG", Thread.currentThread()));
        computer.start();
        computer.join();

        Runnable getter = () -> detector.recordGet("CONFIG", Thread.currentThread());
        Thread[] waiters = new Thread[4];
        for (int i = 0; i < waiters.length; i++) waiters[i] = new Thread(getter);
        for (Thread w : waiters) w.start();
        for (Thread w : waiters) w.join();

        var report = detector.analyze();
        assertFalse(report.getConvoyWarnings().isEmpty(),
            "Four distinct threads blocked behind one computation should warn");
        assertTrue(report.getConvoyWarnings().get(0).contains("CONFIG"));
        assertFalse(report.hasIssues(), "Convoy is a warning, not a correctness issue");
    }

    @Test
    void noConvoy_whenGetsArriveAfterComputation() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, "v");
        for (int i = 0; i < 10; i++) detector.recordGet("CONFIG", t);

        assertTrue(detector.analyze().getConvoyWarnings().isEmpty());
    }

    // ---- Null safety ----

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.recordGet(null, Thread.currentThread());
            detector.recordGet("K", null);
            detector.recordComputeStart(null, Thread.currentThread());
            detector.recordComputeStart("K", null);
            detector.recordComputeEnd(null, Thread.currentThread(), "v");
            detector.recordComputeEnd("K", null, "v");
        });
    }

    // ---- toString ----

    @Test
    void toString_isClean_whenNoIssues() {
        assertTrue(detector.analyze().toString().contains("No LazyConstant misuse"));
    }

    @Test
    void toString_containsLearningContent_whenIssuesFound() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, null);
        String str = detector.analyze().toString();
        assertTrue(str.contains("LEARNING"), str);
        assertTrue(str.contains("LazyConstant"), str);
        assertTrue(str.contains("ofLazy"), str);
    }

    @Test
    void toString_showsCritical_forReentrantComputation() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeStart("CONFIG", t);
        assertTrue(detector.analyze().toString().contains("CRITICAL"));
    }

    @Test
    void toString_showsHigh_forNullValueOnly() {
        Thread t = Thread.currentThread();
        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeEnd("CONFIG", t, null);
        String str = detector.analyze().toString();
        assertTrue(str.contains("HIGH"), str);
    }
}
