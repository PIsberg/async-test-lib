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
