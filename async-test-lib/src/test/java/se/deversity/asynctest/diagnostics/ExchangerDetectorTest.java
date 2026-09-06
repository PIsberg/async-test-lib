package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Exchanger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExchangerDetector.
 */
public class ExchangerDetectorTest {

    @Test
    void aNullRendezvousIsLegalAndNotAFinding() {
        ExchangerDetector detector = new ExchangerDetector();
        java.util.concurrent.Exchanger<String> exchanger = new java.util.concurrent.Exchanger<>();
        detector.registerExchanger(exchanger, "rendezvous");

        // Exchanger.exchange(null) is documented as permitted, and handing over null is the
        // normal way to use an exchanger as a pure rendezvous: the handoff is the
        // synchronisation and the payload is irrelevant. Reporting it at CRITICAL said the code
        // was wrong when it was not (#517).
        detector.recordExchangeComplete(exchanger, "rendezvous", null);
        detector.recordExchangeComplete(exchanger, "rendezvous", null);

        assertFalse(detector.analyze().hasIssues(),
                "a null exchange is legal, and using an exchanger purely to rendezvous is a "
                        + "normal thing to do: " + detector.analyze());
    }

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

    @Test
    void testNullValueIsCountedButNotAFinding() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "nullExchanger");
        detector.recordExchangeStart(exchanger, "nullExchanger");
        detector.recordExchangeComplete(exchanger, "nullExchanger", null);  // Null value

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.hasIssues(),
                "Exchanger.exchange(null) is permitted, and a rendezvous that carries no payload "
                        + "is a normal use of one, so the count is context rather than a finding "
                        + "(#517): " + report);
        assertTrue(report.toString().contains("Null value exchanges"),
                "the count is still shown, so nothing is hidden: " + report);
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
