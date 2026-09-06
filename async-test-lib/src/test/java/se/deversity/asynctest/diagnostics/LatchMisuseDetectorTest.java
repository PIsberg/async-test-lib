package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LatchMisuseDetectorTest {

    private LatchMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LatchMisuseDetector();
    }

    @Test
    void anAwaitThatReturnedIsNotAMissingCountdown() {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);
        detector.registerLatch(latch, "handoff", 2);

        // Two executor tasks count the latch down in code the agent never wove, so the detector
        // records neither. The woven await then returns, which it can only do at zero.
        latch.countDown();
        latch.countDown();
        detector.recordAwait(latch);
        detector.recordAwaitReturned(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertTrue(report.missingCountDowns.isEmpty(),
            "await() returned, so the latch reached zero. The countdowns the detector did not "
                + "see happened outside the weaving boundary, which is a gap in observation and "
                + "not a missing countDown(): " + report.missingCountDowns);
    }

    @Test
    void noLatchesReturnNoIssues() {
        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.missingCountDowns.isEmpty());
        assertTrue(report.extraCountDowns.isEmpty());
    }

    @Test
    void correctCountdownCountNoIssues() {
        Object latch = new Object();
        detector.registerLatch(latch, "testLatch", 2);
        detector.recordCountDown(latch);
        detector.recordCountDown(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.missingCountDowns.isEmpty());
    }

    @Test
    void missingCountdownDetected() {
        Object latch = new Object();
        detector.registerLatch(latch, "incompleteLatch", 3);
        detector.recordCountDown(latch);
        detector.recordCountDown(latch);
        detector.recordAwait(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.missingCountDowns.isEmpty());
    }

    @Test
    void extraCountdownDetected() {
        Object latch = new Object();
        detector.registerLatch(latch, "overCountedLatch", 1);
        detector.recordCountDown(latch);
        detector.recordCountDown(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.extraCountDowns.isEmpty());
    }

    @Test
    void nullLatchHandled() {
        assertDoesNotThrow(() -> detector.registerLatch(null, "nullLatch", 1));
    }

    @Test
    void reportToStringWithMissingCountdown() {
        Object latch = new Object();
        detector.registerLatch(latch, "missingCountLatch", 2);
        detector.recordCountDown(latch);
        detector.recordAwait(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        String str = report.toString();
        assertNotNull(str);
        assertFalse(str.isEmpty());
    }

    @Test
    void reportHasIssuesFalseWhenCorrect() {
        Object latch = new Object();
        detector.registerLatch(latch, "correctLatch", 1);
        detector.recordCountDown(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void awaitWithoutCountdownDetected() {
        Object latch = new Object();
        detector.registerLatch(latch, "awaitOnlyLatch", 1);
        detector.recordAwait(latch);

        LatchMisuseDetector.LatchMisuseReport report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.missingCountDowns.isEmpty());
    }
}
