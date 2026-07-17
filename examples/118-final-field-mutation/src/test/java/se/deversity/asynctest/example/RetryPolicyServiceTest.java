package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.FinalFieldMutationDetector;
import se.deversity.asynctest.example.service.RetryPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RetryPolicyService.
 *
 * ========================================================================
 * DETECTOR: FinalFieldMutationDetector  (JEP 500 — JDK 26)
 * ========================================================================
 *
 * JDK 26 runs with --illegal-final-field-mutation=warn and prints
 * "Mutating final fields will be blocked in a future release". Beyond the
 * deprecation, a reflective write to a final field voids the JMM final-field
 * publication guarantee: readers on other threads have no happens-before edge
 * to the write and may see the stale value forever.
 *
 * THE BUG:
 *   - Field.setAccessible(true) + Field.set on a final field (test injection,
 *     hand-rolled DI, config override)
 *
 * THE FIX:
 *   - Make the field non-final (and volatile if it must change), or inject via
 *     constructor.
 */
class RetryPolicyServiceTest {

    private FinalFieldMutationDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FinalFieldMutationDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: constructor-injected value, plain reads. No misuse.
    // -----------------------------------------------------------------------

    @Test
    void constructorInjection_isClean() {
        var service = new RetryPolicyService(3);
        Thread t = Thread.currentThread();

        detector.recordRead("RetryPolicyService.maxRetries", t);
        assertEquals(3, service.maxRetries());

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: reflective override of the final field — flagged even single-threaded.
    // -----------------------------------------------------------------------

    @Test
    void reflectiveOverride_isDetected() throws Exception {
        var service = new RetryPolicyService(3);
        Thread t = Thread.currentThread();

        detector.recordMutation("RetryPolicyService.maxRetries", t);
        service.overrideMaxRetriesReflectively(5);            // BUG: works today, denied tomorrow

        assertEquals(5, service.maxRetries(),
                "the mutator itself sees its own write — the trap is other threads");

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getMutationIssues().isEmpty());
        assertTrue(report.getMutationIssues().get(0).contains("JDK 26"));
    }

    // -----------------------------------------------------------------------
    // Part 3: reflective write racing a reader on another thread — CRITICAL.
    // -----------------------------------------------------------------------

    @Test
    void mutationRacingReader_isEscalated() throws Exception {
        var service = new RetryPolicyService(3);

        detector.recordMutation("RetryPolicyService.maxRetries", Thread.currentThread());
        service.overrideMaxRetriesReflectively(5);

        Thread reader = new Thread(() -> {
            detector.recordRead("RetryPolicyService.maxRetries", Thread.currentThread());
            service.maxRetries();   // no happens-before edge to the reflective write
        }, "policy-reader");
        reader.start();
        reader.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getRacingReaderIssues().isEmpty(),
                "a foreign reader escalates the finding");
        assertTrue(report.toString().contains("CRITICAL"));
    }
}
