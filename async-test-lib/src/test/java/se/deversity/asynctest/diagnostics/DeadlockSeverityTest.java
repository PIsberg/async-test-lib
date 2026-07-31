package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.FailOn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deadlock is the canonical CRITICAL finding — {@link IssueSeverity#CRITICAL} is documented
 * as "Application will hang, deadlock, or crash".
 *
 * <p>But the runner infers a finding's severity from its report text, and the deadlock report
 * carried no severity marker at all: no {@code 🔴 CRITICAL} label, no {@code [CRITICAL]}, no
 * {@code Severity: CRITICAL}. The only CRITICAL marker lived in {@code printLearningAndFix()},
 * which writes straight to {@code System.err} and never enters the report string.
 *
 * <p>So {@link IssueSeverity#fromReport} fell through to its "untagged reports are significant"
 * default of HIGH — and {@code FailOn.CRITICAL.triggeredBy(HIGH)} is false. A test declared
 * {@code @AsyncTest(failOn = FailOn.CRITICAL)}, whose Javadoc reads "Fail only on CRITICAL
 * findings", would detect the user's deadlock, print it, and still pass green.
 */
class DeadlockSeverityTest {

    @Test
    void aDeadlockReportIsCritical() {
        String report = new DeadlockDetector.DeadlockReport(true).toString();

        assertEquals(IssueSeverity.CRITICAL, IssueSeverity.fromReport(report),
            "a deadlock must be inferred as CRITICAL, not fall through to the HIGH default:\n" + report);
    }

    @Test
    void failOnCriticalActuallyFailsOnADeadlock() {
        String report = new DeadlockDetector.DeadlockReport(true).toString();

        assertTrue(FailOn.CRITICAL.triggeredBy(IssueSeverity.fromReport(report)),
            "failOn = CRITICAL must fail the test when the user's code deadlocks");
    }

    /** The clean report must stay clean — no severity marker, nothing to trip the gate. */
    @Test
    void noDeadlockReportsNothing() {
        DeadlockDetector.DeadlockReport clean = new DeadlockDetector.DeadlockReport(false);

        assertTrue(!clean.hasIssues(), "a clean report must not claim issues");
        assertEquals("No deadlocks detected.", clean.toString());
    }
}
