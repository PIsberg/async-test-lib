package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyCollectionMisuseDetectorTest {

    @Test
    void aMappingFunctionThatThrewInAnEarlierRoundDoesNotLookReentrantInTheNext() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();

        // Round one: the mapping function throws, so the caller never reaches recordComputeEnd
        // and the element stays on this thread's in-flight stack.
        d.recordComputeStart("BOARDS", 0, t);

        d.markInvocationStart();

        // Round two on the reused pool thread: the same element, computed properly this time.
        d.recordGet("BOARDS", 0, t);
        d.recordComputeStart("BOARDS", 0, t);
        d.recordComputeEnd("BOARDS", 0, t, "value");

        assertFalse(d.analyze().hasIssues(),
            "round two's computation is not re-entering round one's, and no other thread is "
                + "waiting behind a computation that ended when the round did: " + d.analyze());
    }

    @Test
    void cleanWhenNothingRecorded() {
        var d = new LazyCollectionMisuseDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("LAZY COLLECTION MISUSE - clean", d.analyze().toString());
    }

    /**
     * The corrected shape: a pure mapping function, one computation per element, no element
     * reaching into another. Same recording calls as the failing cases - no finding.
     */
    @Test
    void aPureMappingFunctionStaysSilent() {
        var d = new LazyCollectionMisuseDetector();
        for (int i = 0; i < 8; i++) {
            d.recordGet("BOARDS", i, Thread.currentThread());
            d.recordComputeStart("BOARDS", i, Thread.currentThread());
            d.recordComputeEnd("BOARDS", i, Thread.currentThread(), "board-" + i);
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void anElementThatReadsItselfWhileComputingIsReported() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        d.recordComputeStart("BOARDS", 3, t);
        d.recordComputeStart("BOARDS", 3, t);      // the mapping function read BOARDS.get(3)
        d.recordComputeEnd("BOARDS", 3, t, "x");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("re-entered its own mapping function")));
        assertTrue(report.structuredViolations.stream()
                .anyMatch(v -> "selfReentrantElement".equals(v.attributes().get("issue"))));
    }

    @Test
    void twoElementsDependingOnEachOtherAreReportedAsACycle() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        // Computing 0 reads 1; on another read, computing 1 reads 0.
        d.recordComputeStart("GRID", 0, t);
        d.recordComputeStart("GRID", 1, t);
        d.recordComputeEnd("GRID", 1, t, "b");
        d.recordComputeEnd("GRID", 0, t, "a");

        d.recordComputeStart("GRID", 1, t);
        d.recordComputeStart("GRID", 0, t);
        d.recordComputeEnd("GRID", 0, t, "a");
        d.recordComputeEnd("GRID", 1, t, "b");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("depend on each other in a cycle")));
    }

    /** A one-way dependency terminates; it is a blocking warning, not the deadlock. */
    @Test
    void aOneWayElementDependencyIsOnlyTheNestedWarning() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        d.recordComputeStart("GRID", 0, t);
        d.recordComputeStart("GRID", 1, t);
        d.recordComputeEnd("GRID", 1, t, "b");
        d.recordComputeEnd("GRID", 0, t, "a");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("GRID[0] -> GRID[1]")));
        assertFalse(report.violations.stream().anyMatch(v -> v.contains("depend on each other in a cycle")));
    }

    /** Elements of two different collections never form a dependency edge with each other. */
    @Test
    void nestingAcrossTwoCollectionsStaysSilent() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        d.recordComputeStart("GRID", 0, t);
        d.recordComputeStart("BOARDS", 0, t);
        d.recordComputeEnd("BOARDS", 0, t, "b");
        d.recordComputeEnd("GRID", 0, t, "a");

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void anElementComputedTwiceIsReported() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        for (int i = 0; i < 2; i++) {
            d.recordComputeStart("BOARDS", 5, t);
            d.recordComputeEnd("BOARDS", 5, t, "same");
        }

        var report = d.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("was computed 2 times")));
    }

    @Test
    void aMappingFunctionThatDisagreesWithItselfIsReported() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        d.recordComputeStart("BOARDS", 5, t);
        d.recordComputeEnd("BOARDS", 5, t, "first");
        d.recordComputeStart("BOARDS", 5, t);
        d.recordComputeEnd("BOARDS", 5, t, "second");

        var report = d.analyze();
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("produced values that are not equal")));
    }

    @Test
    void aNullProducingMappingFunctionIsReported() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        d.recordComputeStart("BOARDS", 2, t);
        d.recordComputeEnd("BOARDS", 2, t, null);

        var report = d.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("computed to null 1 time(s)")));
        assertTrue(report.toString().contains("LAZY COLLECTION MISUSE DETECTED"));
    }

    @Test
    void manyThreadsQueueingOnOneSlowElementIsReported() throws Exception {
        var d = new LazyCollectionMisuseDetector();
        var computing = new CountDownLatch(1);
        var readersDone = new CountDownLatch(4);
        var release = new CountDownLatch(1);

        Thread producer = new Thread(() -> {
            d.recordComputeStart("BOARDS", 9, Thread.currentThread());
            computing.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            d.recordComputeEnd("BOARDS", 9, Thread.currentThread(), "slow");
        }, "producer");
        producer.start();
        assertTrue(computing.await(5, TimeUnit.SECONDS));

        for (int i = 0; i < 4; i++) {
            Thread reader = new Thread(() -> {
                d.recordGet("BOARDS", 9, Thread.currentThread());
                readersDone.countDown();
            }, "reader-" + i);
            reader.start();
            reader.join();
        }
        assertTrue(readersDone.await(5, TimeUnit.SECONDS));
        release.countDown();
        producer.join();

        var report = d.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("thread(s) waiting on it")));
    }

    @Test
    void theConvoyThresholdIsNeverBelowTwo() {
        var d = new LazyCollectionMisuseDetector(0);
        d.recordComputeStart("BOARDS", 1, Thread.currentThread());
        d.recordGet("BOARDS", 1, Thread.currentThread());   // the computing thread is not a waiter
        d.recordComputeEnd("BOARDS", 1, Thread.currentThread(), "x");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void recordingIsIgnoredWhileDisabled() {
        var d = new LazyCollectionMisuseDetector();
        d.disable();
        d.recordComputeStart("BOARDS", 1, Thread.currentThread());
        d.recordComputeEnd("BOARDS", 1, Thread.currentThread(), null);
        assertFalse(d.analyze().hasIssues());

        d.enable();
        d.recordComputeStart("BOARDS", 1, Thread.currentThread());
        d.recordComputeEnd("BOARDS", 1, Thread.currentThread(), null);
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void nullArgumentsAreIgnored() {
        var d = new LazyCollectionMisuseDetector();
        d.recordGet(null, 1, Thread.currentThread());
        d.recordComputeStart("BOARDS", 1, null);
        d.recordComputeEnd(null, 1, Thread.currentThread(), "x");
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void aSelfReentrantElementIsCriticalAtTheGate() {
        LazyCollectionMisuseDetector d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        d.recordComputeStart("BOARDS", 3, t);
        d.recordComputeStart("BOARDS", 3, t);
        d.recordComputeEnd("BOARDS", 3, t, "x");
        String report = d.analyze().toString();
        assertEquals(IssueSeverity.CRITICAL, DetectorDefaultSeverity.of("LazyCollectionMisuseDetector", report),
            "each finding carries a severity in its Violation, but the text the gate reads carried none");
    }
}
