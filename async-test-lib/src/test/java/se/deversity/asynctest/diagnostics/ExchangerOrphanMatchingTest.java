package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Exchanger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which recorded end closes which recorded start (#597, #598).
 *
 * <p>Since #585 the finding is an exchange started and never ended. These pin the two ways an end
 * used to close an exchange it had nothing to do with: an end recorded on a thread that never
 * started one (#597), and an interrupt the runner itself delivered while abandoning a timed-out
 * round (#598).
 */
class ExchangerOrphanMatchingTest {

    /**
     * #597: thread A is left in {@code exchange()}; thread B records a completion it never recorded
     * starting. Counted per exchanger, B's end cancelled A's start and the orphan went unreported.
     */
    @Test
    void anUnmatchedEndOnAnotherThreadDoesNotCloseAnOrphan() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        detector.registerExchanger(exchanger, "orphan-and-stray");

        detector.recordExchangeStart(exchanger, "orphan-and-stray");   // this thread: never ended
        Thread stray = new Thread(
                () -> detector.recordExchangeComplete(exchanger, "orphan-and-stray", "late"),
                "stray-completion");
        stray.start();
        stray.join();

        ExchangerDetector.ExchangerReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the start on this thread was never ended; a completion from a thread that never "
                        + "started an exchange cannot be the end of it. Report:\n" + report);
        assertTrue(report.toString().contains("no start recorded on that thread"),
                "the stray end is worth a line of context: " + report);
    }

    /** The twin: a correct pair, each thread recording its own start and end, stays silent. */
    @Test
    void aPairRecordedOnTheirOwnThreadsStaysSilent() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        detector.registerExchanger(exchanger, "pair");

        Runnable party = () -> {
            detector.recordExchangeStart(exchanger, "pair");
            try {
                detector.recordExchangeComplete(exchanger, "pair", exchanger.exchange("v"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread a = new Thread(party, "party-a");
        Thread b = new Thread(party, "party-b");
        a.start();
        b.start();
        a.join(5_000);
        b.join(5_000);

        ExchangerDetector.ExchangerReport report = detector.analyze();
        assertFalse(report.hasIssues(), "both parties met and left: " + report);
        assertFalse(report.toString().contains("no start recorded on that thread"),
                "no end was unmatched: " + report);
    }

    /** An end on the starting thread still closes its own exchange, however many threads record. */
    @Test
    void eachThreadsEndClosesOnlyItsOwnStart() throws Exception {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        detector.registerExchanger(exchanger, "two-starts");

        detector.recordExchangeStart(exchanger, "two-starts");          // this thread, left open
        Thread other = new Thread(() -> {
            detector.recordExchangeStart(exchanger, "two-starts");
            detector.recordTimeout(exchanger);
            detector.recordTimeout(exchanger);                          // a second end: unmatched
        }, "gives-up");
        other.start();
        other.join();

        assertTrue(detector.analyze().hasIssues(),
                "the other thread's two ends settle its one start and nothing else");
    }

    /**
     * #598: the runner times a round out and interrupts its workers. A body that catches that
     * interrupt and records it must not close the exchange it was stuck in, because that exchange
     * was orphaned when the deadline expired.
     */
    @Test
    void anInterruptRecordedAfterTheRunnerTimedTheRoundOutKeepsTheOrphan() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        detector.registerExchanger(exchanger, "stuck");

        detector.recordExchangeStart(exchanger, "stuck");
        detector.markRoundTimedOut();
        detector.recordInterrupted(exchanger);

        ExchangerDetector.ExchangerReport report = detector.analyze();
        assertTrue(report.hasIssues(),
                "the interrupt is the runner abandoning the round, not the caller leaving: " + report);
        assertTrue(report.toString().contains("interrupted by the round timeout"),
                "the report says how that exchange ended: " + report);
    }

    /** The twin: an interrupt the body handled on a round the runner did not time out closes it. */
    @Test
    void anInterruptBeforeAnyTimeoutStillClosesTheExchange() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        detector.registerExchanger(exchanger, "shutdown");

        detector.recordExchangeStart(exchanger, "shutdown");
        detector.recordInterrupted(exchanger);

        assertFalse(detector.analyze().hasIssues(),
                "a handled interrupt with no round timeout is how the thread left the exchange");
    }

    /** A completion or a timeout after the runner's timeout is still a real way out. */
    @Test
    void aCompletionAfterTheTimeoutStillClosesTheExchange() {
        ExchangerDetector detector = new ExchangerDetector();
        Exchanger<String> exchanger = new Exchanger<>();
        detector.registerExchanger(exchanger, "late-partner");

        detector.recordExchangeStart(exchanger, "late-partner");
        detector.markRoundTimedOut();
        detector.recordExchangeComplete(exchanger, "late-partner", "met");

        assertFalse(detector.analyze().hasIssues(),
                "the exchange completed; only an interrupt is ambiguous after a timeout");
    }
}
