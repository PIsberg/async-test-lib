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
    void aFreshCollectionPerRoundUnderTheSameLabelComputesEachElementOnce() {
        // Each round builds its own List.ofLazy under "BOARDS"; element 0 computes once in each,
        // to a value that differs by round. Not one element computed three times.
        var d = new LazyCollectionMisuseDetector();
        for (int round = 0; round < 3; round++) {
            d.markInvocationStart();
            Thread t = new Thread("round-" + round);
            d.recordGet("BOARDS", 0, t);
            d.recordComputeStart("BOARDS", 0, t);
            d.recordComputeEnd("BOARDS", 0, t, "board-" + round);
        }
        assertFalse(d.analyze().hasIssues(), "one computation per fresh element: " + d.analyze());
    }

    @Test
    void anElementComputedTwiceInOneRoundStillFiresAfterTheRoundCloses() {
        var d = new LazyCollectionMisuseDetector();
        d.markInvocationStart();
        Thread a = new Thread("a");
        Thread b = new Thread("b");
        d.recordComputeStart("BOARDS", 0, a);
        d.recordComputeStart("BOARDS", 0, b);
        d.recordComputeEnd("BOARDS", 0, a, "x");
        d.recordComputeEnd("BOARDS", 0, b, "x");
        d.markInvocationStart();   // the next round starts; the finding belongs to the last one
        assertTrue(d.analyze().hasIssues(), d.analyze().toString());
        assertTrue(d.analyze().toString().contains("computed 2 times"), d.analyze().toString());
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

    // ---- Collection-taking overloads (#776) ----

    @Test
    void twoCollectionsUnderOneNameAreJudgedApart() {
        // Two lazy lists both labelled "GRID". In the first, element 0 reads element 1; in the
        // second, element 1 reads element 0. Keyed by the label that is a cycle; it is two
        // one-way dependencies in two collections, and nothing can deadlock.
        var d = new LazyCollectionMisuseDetector();
        Object first = new Object();
        Object second = new Object();
        Thread t = Thread.currentThread();
        d.recordComputeStart(first, "GRID", 0, t);
        d.recordComputeStart(first, "GRID", 1, t);
        d.recordComputeEnd(first, "GRID", 1, t, "b");
        d.recordComputeEnd(first, "GRID", 0, t, "a");

        d.recordComputeStart(second, "GRID", 1, t);
        d.recordComputeStart(second, "GRID", 0, t);
        d.recordComputeEnd(second, "GRID", 0, t, "c");
        d.recordComputeEnd(second, "GRID", 1, t, "d");

        var report = d.analyze();
        assertFalse(report.violations.stream().anyMatch(v -> v.contains("in a cycle")), report.toString());
        assertFalse(report.violations.stream().anyMatch(v -> v.contains("times")), report.toString());
        assertFalse(report.violations.stream().anyMatch(v -> v.contains("not equal")), report.toString());
    }

    @Test
    void oneCollectionUnderTwoNamesIsJudgedTogether() {
        var d = new LazyCollectionMisuseDetector();
        Object grid = new Object();
        Thread t = Thread.currentThread();
        d.recordComputeStart(grid, "GRID", 0, t);
        d.recordComputeStart(grid, "cells", 1, t);
        d.recordComputeEnd(grid, "cells", 1, t, "b");
        d.recordComputeEnd(grid, "GRID", 0, t, "a");

        d.recordComputeStart(grid, "cells", 1, t);
        d.recordComputeStart(grid, "GRID", 0, t);
        d.recordComputeEnd(grid, "GRID", 0, t, "a");
        d.recordComputeEnd(grid, "cells", 1, t, "b");

        var report = d.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("in a cycle")), report.toString());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("computed 2 times")), report.toString());
    }

    @Test
    void aStaticCollectionsElementComputedOnceInEachOfTwoRoundsIsComputedTwice() {
        // The case the per-round name path misses: the collection outlives the round.
        var d = new LazyCollectionMisuseDetector();
        Object staticGrid = new Object();
        Thread t = Thread.currentThread();
        d.markInvocationStart();
        d.recordComputeStart(staticGrid, "GRID", 0, t);
        d.recordComputeEnd(staticGrid, "GRID", 0, t, "a");
        d.markInvocationStart();
        d.recordComputeStart(staticGrid, "GRID", 0, t);
        d.recordComputeEnd(staticGrid, "GRID", 0, t, "a");
        var report = d.analyze();
        assertEquals(1, report.violations.size(), report.toString());
        assertTrue(report.violations.get(0).contains("GRID[0] was computed 2 times"), report.toString());
    }

    @Test
    void aFreshCollectionPerRoundNamedByObjectComputesEachElementOnce() {
        var d = new LazyCollectionMisuseDetector();
        for (int round = 0; round < 3; round++) {
            d.markInvocationStart();
            Object perRound = new Object();
            Thread t = new Thread("round-" + round);
            d.recordGet(perRound, "BOARDS", 0, t);
            d.recordComputeStart(perRound, "BOARDS", 0, t);
            d.recordComputeEnd(perRound, "BOARDS", 0, t, "board-" + round);
        }
        assertFalse(d.analyze().hasIssues(), d.analyze().toString());
    }

    @Test
    void waitersOnAStaticCollectionAreCountedPerRound() {
        // Three rounds, each with one computation abandoned by a throwing mapping function and
        // one other reader. Three waiters in all, never more than one in a round: not a convoy.
        var d = new LazyCollectionMisuseDetector(2);
        Object staticGrid = new Object();
        for (int round = 0; round < 3; round++) {
            d.markInvocationStart();
            d.recordComputeStart(staticGrid, "GRID", 0, new Thread("thrower-" + round));
            d.recordGet(staticGrid, "GRID", 0, new Thread("reader-" + round));
        }
        d.markInvocationStart();
        assertFalse(d.analyze().violations.stream().anyMatch(v -> v.contains("waiting on it")),
            d.analyze().toString());
    }

    @Test
    void aConvoyOnAStaticCollectionSurvivesTheRoundClosing() {
        var d = new LazyCollectionMisuseDetector();
        Object staticGrid = new Object();
        d.markInvocationStart();
        d.recordComputeStart(staticGrid, "GRID", 0, new Thread("computer"));
        for (int i = 0; i < 4; i++) {
            d.recordGet(staticGrid, "GRID", 0, new Thread("reader-" + i));
        }
        d.markInvocationStart();
        assertTrue(d.analyze().violations.stream().anyMatch(v -> v.contains("4 thread(s) waiting on it")),
            d.analyze().toString());
    }

    @Test
    void aNullCollectionFallsBackToTheName() {
        var d = new LazyCollectionMisuseDetector();
        Thread t = Thread.currentThread();
        d.recordComputeStart(null, "GRID", 0, t);
        d.recordComputeEnd(null, "GRID", 0, t, "a");
        d.recordComputeStart(null, "GRID", 0, t);
        d.recordComputeEnd(null, "GRID", 0, t, "a");
        assertTrue(d.analyze().violations.stream().anyMatch(v -> v.contains("GRID[0] was computed 2 times")),
            d.analyze().toString());

        var quiet = new LazyCollectionMisuseDetector();
        quiet.recordGet(null, null, 0, t);
        quiet.recordComputeStart(new Object(), "GRID", 0, null);
        quiet.recordComputeEnd(null, null, 0, null, "x");
        assertFalse(quiet.analyze().hasIssues(), quiet.analyze().toString());
    }

    @Test
    void anUnnamedCollectionIsLabelledByItsIdentity() {
        var d = new LazyCollectionMisuseDetector();
        Object grid = new Object();
        Thread t = Thread.currentThread();
        d.recordComputeStart(grid, null, 3, t);
        d.recordComputeEnd(grid, null, 3, t, null);
        assertTrue(d.analyze().violations.stream().anyMatch(v -> v.contains("Element Object@")),
            d.analyze().toString());
    }
}
