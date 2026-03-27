package com.github.asynctest.diagnostics;

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

    @Test
    void testNullValueDetection() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "nullExchanger");
        detector.recordExchangeStart(exchanger, "nullExchanger");
        detector.recordExchangeComplete(exchanger, "nullExchanger", null);  // Null value

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect null value exchange");
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
