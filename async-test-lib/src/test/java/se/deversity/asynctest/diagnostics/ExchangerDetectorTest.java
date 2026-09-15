package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ExchangerDetector.
 *
 * <p>The finding is an orphaned exchange: one recorded as started that neither completed, timed
 * out nor was interrupted by the time the run is analysed (#585). A timeout or an interrupt the
 * caller recorded is how a thread <em>left</em> an exchange, so it closes the exchange and is
 * printed as context, never reported on its own.
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

    /**
     * A timed exchange that catches {@code TimeoutException} is the report's own prescribed fix,
     * so it must not be the finding. It used to be reported CRITICAL (#585).
     */
    @Test
    void aTimedExchangeThatHandlesItsTimeoutStaysSilent() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        detector.registerExchanger(exchanger, "pollingExchanger");

        detector.recordExchangeStart(exchanger, "pollingExchanger");
        try {
            exchanger.exchange("offer", 1, TimeUnit.MILLISECONDS);
            fail("nobody else is on this exchanger, so the timed exchange cannot complete");
        } catch (TimeoutException expected) {
            detector.recordTimeout(exchanger);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("not interrupted");
        }

        ExchangerDetector.ExchangerReport report = detector.analyze();
        assertFalse(report.hasIssues(),
                "the thread left the exchange through the timeout it asked for and handled; nothing "
                        + "is blocked and nothing is orphaned. Report:\n" + report);
    }

    /** An interrupt the caller handled, typically during shutdown, is how the thread left. */
    @Test
    void aHandledInterruptStaysSilent() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "shutdownExchanger");
        detector.recordExchangeStart(exchanger, "shutdownExchanger");
        detector.recordInterrupted(exchanger);

        assertFalse(detector.analyze().hasIssues(),
                "a recorded interrupt says the thread came back out of exchange(); reporting it as "
                        + "a swallowed interrupt claimed something this detector never observed");
    }

    /** The orphan: started, and nothing ever ended it. */
    @Test
    void aStartedExchangeWithNoCompletionAtAnalysisFires() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "orphanedExchanger");
        detector.recordExchangeStart(exchanger, "orphanedExchanger");

        ExchangerDetector.ExchangerReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "an exchange that started and never completed, timed out or was interrupted is a "
                        + "thread still waiting for a partner, which is the hazard this detector models");
        assertTrue(report.toString().contains("orphanedExchanger"),
                "the report must name the exchanger: " + report);
    }

    /** One exchange closed by a timeout must not hide a second that never ended. */
    @Test
    void aClosedExchangeDoesNotHideAnOpenOne() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "halfOrphaned");
        detector.recordExchangeStart(exchanger, "halfOrphaned");
        detector.recordExchangeStart(exchanger, "halfOrphaned");
        detector.recordTimeout(exchanger);

        assertTrue(detector.analyze().hasIssues(),
                "two exchanges started and one ended, so one thread is still waiting");
    }

    /**
     * The real shape, with real threads: three callers of an untimed exchange, so two pair and the
     * third is left blocked in {@code exchange()} when the run is analysed.
     */
    @Test
    void anUntimedExchangeLeftWithoutAPartnerFires() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        detector.registerExchanger(exchanger, "oddCallers");

        Runnable caller = () -> {
            detector.recordExchangeStart(exchanger, "oddCallers");
            try {
                String received = exchanger.exchange("payload");
                detector.recordExchangeComplete(exchanger, "oddCallers", received);
            } catch (InterruptedException e) {
                // Interrupted by this test's cleanup after the analysis; deliberately not recorded.
                Thread.currentThread().interrupt();
            }
        };
        Thread[] callers = new Thread[3];
        for (int i = 0; i < callers.length; i++) {
            callers[i] = new Thread(caller, "exchanger-caller-" + i);
            callers[i].setDaemon(true);
            callers[i].start();
        }
        try {
            awaitTwoPairedAndOneBlocked(callers);

            ExchangerDetector.ExchangerReport report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "one of three callers can never find a partner and is still blocked. Report:\n"
                            + report);
        } finally {
            for (Thread t : callers) {
                t.interrupt();
                t.join(2_000);
            }
        }
    }

    /** Waits until two callers have finished and the third is parked, so the analysis is not early. */
    private static void awaitTwoPairedAndOneBlocked(Thread[] callers) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            int finished = 0;
            int parked = 0;
            for (Thread t : callers) {
                Thread.State state = t.getState();
                if (state == Thread.State.TERMINATED) {
                    finished++;
                } else if (state == Thread.State.WAITING) {
                    parked++;
                }
            }
            if (finished == 2 && parked == 1) {
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("expected two paired callers to finish and one to park in exchange()");
            }
            Thread.sleep(5);
        }
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
     * The null count and the handled timeouts survive as context on a run that did have something
     * to report.
     *
     * <p>Dropping a finding must not drop the observation: an orphaned exchange is the finding, and
     * how the other exchanges on the same exchanger ended is worth knowing while reading it.
     */
    @Test
    void theContextCountsAreStillReportedAlongsideARealFinding() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();

        detector.registerExchanger(exchanger, "mixedExchanger");
        detector.recordExchangeStart(exchanger, "mixedExchanger");
        detector.recordExchangeComplete(exchanger, "mixedExchanger", null);
        detector.recordExchangeStart(exchanger, "mixedExchanger");
        detector.recordTimeout(exchanger);
        detector.recordExchangeStart(exchanger, "mixedExchanger");

        ExchangerDetector.ExchangerReport report = detector.analyze();

        assertTrue(report.hasIssues(), "the third exchange never ended");
        String rendered = report.toString();
        assertTrue(rendered.contains("Null value exchanges"),
                "the null count is context on the finding and must still be printed: " + rendered);
        assertTrue(rendered.contains("timed out: 1"),
                "the handled timeout is context on the finding and must still be printed: "
                        + rendered);
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
        detector.recordExchangeStart(exchanger, "testExchanger");

        ExchangerDetector.ExchangerReport report = detector.analyze();

        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("EXCHANGER ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("CRITICAL"),
                "the failOn gate reads severity from the report text, so the label must be in it: "
                        + reportStr);
    }
}
