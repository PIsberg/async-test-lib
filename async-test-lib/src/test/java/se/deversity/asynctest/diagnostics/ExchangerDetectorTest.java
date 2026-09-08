package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Exchanger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExchangerDetector.
 */
public class ExchangerDetectorTest {

    @Test
    void testNormalExchange() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "normalExchanger");
        detector.recordExchangeStart(exchanger, "normalExchanger");
        detector.recordExchangeComplete(exchanger, "normalExchanger", "data");

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Normal exchange should not report issues");
    }

    @Test
    void testTimeoutDetection() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "timeoutExchanger");
        detector.recordTimeout(exchanger);  // Timeout!

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect timeout");
    }

    @Test
    void testInterruptedDetection() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "interruptedExchanger");
        detector.recordInterrupted(exchanger);  // Interrupted!

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect interrupted exchange");
    }

    /**
     * A payload-free rendezvous is not a finding, which is what #521 settled.
     *
     * <p>This test used to assert the opposite. {@code Exchanger.exchange(null)} is permitted by
     * the JDK and a null handoff is how the class is used as a pure rendezvous, so reporting it
     * said correct code was wrong. The detector's own report text had conceded the point already,
     * printing the count as "legal" and then calling it a warning in the next line.
     */
    @Test
    void aNullPayloadIsNotAFinding() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "nullExchanger");
        detector.recordExchangeStart(exchanger, "nullExchanger");
        detector.recordExchangeComplete(exchanger, "nullExchanger", null);

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(),
                "exchange(null) is legal and a payload-free handoff is a normal rendezvous, so on "
                        + "its own it must not produce a finding");
    }

    /**
     * The null count survives as context on a run that did have something to report.
     *
     * <p>Dropping the finding must not drop the observation: a timed-out exchange is the finding,
     * and how many exchanges carried nothing is worth knowing while reading it. This pins the
     * difference between "no longer a verdict" and "no longer collected".
     */
    @Test
    void theNullCountIsStillReportedAlongsideARealFinding() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "mixedExchanger");
        detector.recordExchangeStart(exchanger, "mixedExchanger");
        detector.recordExchangeComplete(exchanger, "mixedExchanger", null);
        detector.recordTimeout(exchanger);

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertTrue(report.hasIssues(), "the timeout is still a finding");
        assertTrue(report.toString().contains("Null value exchanges"),
                "the null count is context on the finding and must still be printed: "
                        + report);
    }

    /** An orphaned rendezvous is the hazard this detector models, and still fires. */
    @Test
    void aTimedOutExchangeIsStillAFinding() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "orphanedExchanger");
        detector.recordExchangeStart(exchanger, "orphanedExchanger");
        detector.recordTimeout(exchanger);

        assertTrue(detector.analyze().hasIssues(),
                "a thread that arrived and found no partner is left on a handoff that cannot "
                        + "complete, which is the thing this detector is for");
    }

    @Test
    void testMultiThreadExchange() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "multiThreadExchanger");

        Thread t1 = new Thread(() -> {
            try {
                detector.recordExchangeStart(exchanger, "multiThreadExchanger");
                String result = exchanger.exchange("data1");
                detector.recordExchangeComplete(exchanger, "multiThreadExchanger", result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                detector.recordExchangeStart(exchanger, "multiThreadExchanger");
                String result = exchanger.exchange("data2");
                detector.recordExchangeComplete(exchanger, "multiThreadExchanger", result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        t1.start();
        t2.start();

        t1.join();
        t2.join();

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(), "Multi-thread exchange should work correctly");
    }

    @Test
    void testReportToString() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "testExchanger");
        detector.recordTimeout(exchanger);

        ExchangerDetector.ExchangerReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("EXCHANGER ISSUES DETECTED"), "Report should have header");
    }
}
