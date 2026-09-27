package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link StableValueMisuseDetector}.
 */
class StableValueMisuseDetectorTest {

    private StableValueMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new StableValueMisuseDetector();
    }

    @Test
    void aSupplierThatThrewInAnEarlierRoundDoesNotLookReentrantInTheNext() {
        Thread t = Thread.currentThread();

        // Round one: the supplier throws before recordSupplierEnd, leaving the name in flight.
        detector.recordSupplierStart("CONFIG", t);

        detector.markInvocationStart();

        detector.recordSupplierStart("CONFIG", t);
        detector.recordSupplierEnd("CONFIG", t);

        assertTrue(detector.analyze().getReentrantIssues().isEmpty(),
            "a supplier that threw in an earlier round left its name in activeSuppliers; the next "
                + "round's supplier on the reused thread is not reentrant: "
                + detector.analyze().getReentrantIssues());
    }

    @Test
    void aFreshHolderSetOncePerRoundUnderTheSameLabelIsNotSetTwice() {
        // Each round builds its own StableValue (a per-test instance field) under the label
        // "CONFIG" and sets it once. Three holders, three sets, no second set on any of them.
        Thread[] setters = { new Thread("r1"), new Thread("r2"), new Thread("r3") };
        for (Thread setter : setters) {
            detector.markInvocationStart();
            detector.recordSet("CONFIG", setter);
            detector.recordRead("CONFIG", setter);
        }
        var report = detector.analyze();
        assertFalse(report.hasIssues(), "one set per fresh holder is the contract: " + report);
        assertTrue(report.getContentionWarnings().isEmpty(),
            "three rounds with one setter each is not three threads racing one holder: " + report);
    }

    @Test
    void oneHolderSetTwiceInOneRoundStillFires() {
        detector.markInvocationStart();
        detector.recordSet("CONFIG", new Thread("a"));
        detector.recordSet("CONFIG", new Thread("b"));
        assertFalse(detector.analyze().getDoubleSetIssues().isEmpty());
    }

    @Test
    void aHolderSetInAnEarlierRoundIsNotReadBeforeSetInTheNext() {
        // A static StableValue set in round one and only read afterwards.
        Thread t = Thread.currentThread();
        detector.markInvocationStart();
        detector.recordSet("CONFIG", t);
        detector.markInvocationStart();
        detector.recordRead("CONFIG", t);
        assertTrue(detector.analyze().getReadBeforeSetIssues().isEmpty(),
            detector.analyze().getReadBeforeSetIssues().toString());
    }

    // ---- Happy path ----

    @Test
    void noIssues_whenSetThenRead() {
        Thread t = Thread.currentThread();
        detector.recordSet("CONFIG", t);
        detector.recordRead("CONFIG", t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no issues: " + report);
        assertEquals(1, report.getTotalSets());
        assertEquals(1, report.getTotalReads());
    }

    @Test
    void noIssues_forOrElseSetSupplierThenRead() {
        Thread t = Thread.currentThread();
        detector.recordSupplierStart("CONFIG", t);
        detector.recordSupplierEnd("CONFIG", t);
        detector.recordRead("CONFIG", t); // value is set after supplier completes

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no issues: " + report);
    }

    // ---- Read before set ----

    @Test
    void detectsReadBeforeSet() {
        detector.recordRead("CONFIG", Thread.currentThread());

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getReadBeforeSetIssues().isEmpty());
        String issue = report.getReadBeforeSetIssues().get(0);
        assertTrue(issue.contains("CONFIG"), issue);
        assertTrue(issue.contains("NoSuchElementException"), issue);
    }

    @Test
    void noReadBeforeSet_whenReadHappensAfterSet() {
        Thread t = Thread.currentThread();
        detector.recordSet("CONFIG", t);
        detector.recordRead("CONFIG", t);
        detector.recordRead("CONFIG", t);

        assertTrue(detector.analyze().getReadBeforeSetIssues().isEmpty());
    }

    // ---- Double set ----

    @Test
    void detectsDoubleSet() {
        Thread t = Thread.currentThread();
        detector.recordSet("CONFIG", t);
        detector.recordSet("CONFIG", t); // second set — violation

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getDoubleSetIssues().size());
        String issue = report.getDoubleSetIssues().get(0);
        assertTrue(issue.contains("CONFIG"), issue);
        assertTrue(issue.contains("already set"), issue);
    }

    @Test
    void noDoubleSet_forDistinctHolders() {
        Thread t = Thread.currentThread();
        detector.recordSet("A", t);
        detector.recordSet("B", t);

        assertTrue(detector.analyze().getDoubleSetIssues().isEmpty());
    }

    // ---- Reentrant computation ----

    @Test
    void detectsReentrantSupplier() {
        Thread t = Thread.currentThread();
        detector.recordSupplierStart("CONFIG", t);
        detector.recordSupplierStart("CONFIG", t); // re-entered while computing — violation

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getReentrantIssues().size());
        assertTrue(report.getReentrantIssues().get(0).contains("CONFIG"));
    }

    @Test
    void noReentrancy_whenSupplierEndsBeforeRestart() {
        Thread t = Thread.currentThread();
        detector.recordSupplierStart("CONFIG", t);
        detector.recordSupplierEnd("CONFIG", t);
        detector.recordSupplierStart("CONFIG", t); // sequential, not reentrant
        detector.recordSupplierEnd("CONFIG", t);

        assertTrue(detector.analyze().getReentrantIssues().isEmpty());
    }

    // ---- Set contention ----

    @Test
    void detectsSetContention_whenManyThreadsRaceToSet() throws Exception {
        Runnable r = () -> detector.recordSet("CONFIG", Thread.currentThread());
        Thread a = new Thread(r), b = new Thread(r), c = new Thread(r);
        a.start(); b.start(); c.start();
        a.join(); b.join(); c.join();

        var report = detector.analyze();
        assertFalse(report.getContentionWarnings().isEmpty(),
            "Three distinct threads racing one holder should warn");
        assertTrue(report.getContentionWarnings().get(0).contains("CONFIG"));
    }

    // ---- Null safety ----

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.recordSet(null, Thread.currentThread());
            detector.recordSet("K", null);
            detector.recordRead(null, Thread.currentThread());
            detector.recordRead("K", null);
            detector.recordSupplierStart(null, null);
            detector.recordSupplierEnd("K", null);
        });
    }

    // ---- toString ----

    @Test
    void toString_isClean_whenNoIssues() {
        assertTrue(detector.analyze().toString().contains("No StableValue misuse"));
    }

    @Test
    void toString_containsLearningContent_whenIssuesFound() {
        detector.recordRead("CONFIG", Thread.currentThread());
        String str = detector.analyze().toString();
        assertTrue(str.contains("LEARNING"), str);
        assertTrue(str.contains("StableValue"), str);
        assertTrue(str.contains("orElseSet"), str);
    }

    @Test
    void toString_showsCritical_forReadBeforeSet() {
        detector.recordRead("CONFIG", Thread.currentThread());
        assertTrue(detector.analyze().toString().contains("CRITICAL"));
    }

    @Test
    void toString_showsHigh_forDoubleSetOnly() {
        Thread t = Thread.currentThread();
        detector.recordSet("CONFIG", t);
        detector.recordSet("CONFIG", t);
        String str = detector.analyze().toString();
        assertTrue(str.contains("HIGH"), str);
    }

    // ---- Holder-taking overloads (#776) ----

    @Test
    void twoHoldersUnderOneNameAreJudgedApart() {
        // Two StableValues both labelled "CONFIG" in one round, each set once. Keyed by the
        // label they are one holder set twice; keyed by the holder, neither is.
        Object first = new Object();
        Object second = new Object();
        detector.markInvocationStart();
        detector.recordSet(first, "CONFIG", new Thread("a"));
        detector.recordSet(second, "CONFIG", new Thread("b"));
        assertTrue(detector.analyze().getDoubleSetIssues().isEmpty(),
            "two holders each set once share a label, not a set: " + detector.analyze());
    }

    @Test
    void aReadOfAnUnsetHolderIsNotExcusedByAnotherHolderUnderItsName() {
        // The other direction of the same merge: the label says "CONFIG was set", but the holder
        // being read never was, and orElseThrow() on it throws.
        Object setOne = new Object();
        Object unsetOne = new Object();
        Thread t = Thread.currentThread();
        detector.markInvocationStart();
        detector.recordSet(setOne, "CONFIG", t);
        detector.recordRead(unsetOne, "CONFIG", t);
        assertEquals(1, detector.analyze().getReadBeforeSetIssues().size(), detector.analyze().toString());
    }

    @Test
    void oneHolderUnderTwoNamesIsJudgedTogether() {
        Object holder = new Object();
        detector.markInvocationStart();
        detector.recordSet(holder, "CONFIG", new Thread("a"));
        detector.recordSet(holder, "settings", new Thread("b"));
        assertEquals(1, detector.analyze().getDoubleSetIssues().size(),
            "one holder set under two labels is still set twice: " + detector.analyze());
    }

    @Test
    void aStaticHolderSetOnceInEachOfTwoRoundsIsSetTwice() {
        // The case the per-round name path misses: the holder outlives the round, so round two's
        // setOrThrow() throws IllegalStateException.
        Object staticHolder = new Object();
        Thread t = Thread.currentThread();
        detector.markInvocationStart();
        detector.recordSet(staticHolder, "CONFIG", t);
        detector.markInvocationStart();
        detector.recordSet(staticHolder, "CONFIG", t);
        assertEquals(1, detector.analyze().getDoubleSetIssues().size(), detector.analyze().toString());
    }

    @Test
    void aFreshHolderPerRoundNamedByObjectIsSetOnceEach() {
        Thread[] setters = { new Thread("r1"), new Thread("r2"), new Thread("r3") };
        for (Thread setter : setters) {
            detector.markInvocationStart();
            Object perRound = new Object();
            detector.recordSet(perRound, "CONFIG", setter);
            detector.recordRead(perRound, "CONFIG", setter);
        }
        var report = detector.analyze();
        assertFalse(report.hasIssues(), "one set per fresh holder is the contract: " + report);
        assertTrue(report.getContentionWarnings().isEmpty(), report.toString());
    }

    @Test
    void aStaticHolderSetInOneRoundAndReadInTheNextIsNotReadBeforeSet() {
        Object staticHolder = new Object();
        Thread t = Thread.currentThread();
        detector.markInvocationStart();
        detector.recordSupplierStart(staticHolder, "CONFIG", t);
        detector.recordSupplierEnd(staticHolder, "CONFIG", t);
        detector.markInvocationStart();
        detector.recordRead(staticHolder, "CONFIG", t);
        assertFalse(detector.analyze().hasIssues(), detector.analyze().toString());
    }

    @Test
    void aStaticHolderSetByOneThreadPerRoundIsNotContended() {
        // Contention is threads racing inside one round; a static holder reached from a new
        // thread each round is one setter per round. The repeat set is reported, not a race.
        Object staticHolder = new Object();
        for (int round = 0; round < 3; round++) {
            detector.markInvocationStart();
            detector.recordSupplierEnd(staticHolder, "CONFIG", new Thread("r" + round));
        }
        assertTrue(detector.analyze().getContentionWarnings().isEmpty(), detector.analyze().toString());
    }

    @Test
    void reentrancyIsJudgedPerHolder() {
        Thread t = Thread.currentThread();
        Object outer = new Object();
        Object inner = new Object();
        // A supplier that computes another holder under the same label is not re-entering itself.
        detector.recordSupplierStart(outer, "CONFIG", t);
        detector.recordSupplierStart(inner, "CONFIG", t);
        detector.recordSupplierEnd(inner, "CONFIG", t);
        detector.recordSupplierEnd(outer, "CONFIG", t);
        assertTrue(detector.analyze().getReentrantIssues().isEmpty(), detector.analyze().toString());

        // One holder reached under a second label while its supplier runs is.
        detector.recordSupplierStart(outer, "CONFIG", t);
        detector.recordSupplierStart(outer, "settings", t);
        assertEquals(1, detector.analyze().getReentrantIssues().size(), detector.analyze().toString());
    }

    @Test
    void aNullHolderFallsBackToTheName() {
        detector.recordSet(null, "CONFIG", new Thread("a"));
        detector.recordSet(null, "CONFIG", new Thread("b"));
        assertEquals(1, detector.analyze().getDoubleSetIssues().size(), detector.analyze().toString());
        assertDoesNotThrow(() -> {
            detector.recordSet(null, null, Thread.currentThread());
            detector.recordRead(new Object(), "K", null);
            detector.recordSupplierStart(null, null, null);
        });
    }

    @Test
    void anUnnamedHolderIsLabelledByItsIdentity() {
        Object holder = new Object();
        detector.recordRead(holder, null, Thread.currentThread());
        String issue = detector.analyze().getReadBeforeSetIssues().get(0);
        assertTrue(issue.contains("StableValue 'Object@"), issue);
    }
}
