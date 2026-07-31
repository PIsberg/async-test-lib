package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Textbook-correct interrupt handling is to catch {@link InterruptedException} and restore the
 * flag:
 *
 * <pre>{@code
 * catch (InterruptedException e) {
 *     Thread.currentThread().interrupt();   // restored
 * }
 * }</pre>
 *
 * <p>{@code ignoredInterrupts} rightly keys off {@code !event.restored}. But
 * {@code repeatedIgnoredInterrupts} was derived from a separate counter that incremented on
 * <em>every</em> caught exception, restored or not — so a thread that handled two interrupts
 * perfectly was reported under "repeated ignored interrupts", and that finding counts toward
 * {@code hasIssues()}.
 *
 * <p>A false positive here is expensive: it teaches users that the detector cries wolf about the
 * very idiom the library's own fix advice tells them to adopt.
 */
class InterruptRestoredNotIgnoredTest {

    @Test
    void repeatedlyCatchingAndRestoringIsNotAnIgnoredInterrupt() {
        InterruptMonitor monitor = new InterruptMonitor();

        // Two interrupts, both handled correctly: caught, then the flag put back.
        monitor.recordInterruptException(new InterruptedException());
        monitor.recordInterruptRestored();
        monitor.recordInterruptException(new InterruptedException());
        monitor.recordInterruptRestored();

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();

        assertTrue(report.repeatedIgnoredInterrupts.isEmpty(),
            "restoring the interrupt flag is correct handling — it must not be reported as ignored: "
                + report.repeatedIgnoredInterrupts);
        assertTrue(report.ignoredInterrupts.isEmpty(),
            "no interrupt was ignored: " + report.ignoredInterrupts);
        assertFalse(report.hasIssues(), "correct interrupt handling must produce a clean report");
    }

    /** The real bug must still be caught: interrupts swallowed repeatedly, flag never restored. */
    @Test
    void repeatedlySwallowingInterruptsIsStillReported() {
        InterruptMonitor monitor = new InterruptMonitor();

        monitor.recordInterruptException(new InterruptedException());
        monitor.recordInterruptException(new InterruptedException());
        // ...the flag is never restored — the interrupt is swallowed.

        InterruptMonitor.InterruptReport report = monitor.analyzeInterruptHandling();

        assertFalse(report.repeatedIgnoredInterrupts.isEmpty(),
            "a thread that swallows interrupts repeatedly must be reported");
        assertTrue(report.hasIssues(), "the report must claim issues");
    }
}
