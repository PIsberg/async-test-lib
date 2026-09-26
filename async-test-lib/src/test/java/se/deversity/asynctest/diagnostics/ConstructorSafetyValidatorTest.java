package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;
import static se.deversity.asynctest.diagnostics.ConstructorSafetySubject.onAnotherThread;

class ConstructorSafetyValidatorTest {

    @Test
    void theReportCountsThreadsRatherThanAccesses() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();

        // One other thread, reading the half-built object three times. That is one escape, not
        // three threads, and the message says threads (#501).
        new ConstructorSafetySubject(validator, self -> onAnotherThread(() -> {
            for (int i = 0; i < 3; i++) {
                validator.recordFieldAccess(self, "state", System.nanoTime());
            }
        }), true);

        ConstructorSafetyValidator.ConstructorSafetyReport report =
                validator.validateConstructorSafety();
        assertEquals(1, report.unsafeObjects.size(), "one object escaped");
        String finding = report.unsafeObjects.iterator().next();
        assertTrue(finding.contains("1 thread(s)"),
            "one thread made all three accesses: " + finding);
        assertTrue(finding.contains("3 access(es)"),
            "and the access count is still shown: " + finding);
    }

    @Test
    void noRecordingsReturnNoIssues() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues());
    }

    @Test
    void completeConstructionNoIssues() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        new ConstructorSafetySubject(validator,
                self -> validator.recordFieldAccess(self, "field1", System.nanoTime()), true);
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues());
    }

    @Test
    void aFieldReadByAnotherThreadWhileTheConstructorRunsIsReported() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        new ConstructorSafetySubject(validator,
                self -> onAnotherThread(() -> validator.recordFieldAccess(self, "name", System.nanoTime())),
                true);
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertTrue(report.hasIssues(), report.toString());
        assertTrue(report.fieldsAccessedDuringConstruction.contains("ConstructorSafetySubject.name"),
            report.fieldsAccessedDuringConstruction.toString());
        assertTrue(report.toString().contains("HIGH"), report.toString());
    }

    @Test
    void anEscapeIsReportedEvenWhenTheEndIsNeverRecorded() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        new ConstructorSafetySubject(validator,
                self -> onAnotherThread(() -> validator.recordFieldAccess(self, "name", System.nanoTime())),
                false);
        assertTrue(validator.validateConstructorSafety().hasIssues(),
            "the read happened while the constructor was on the constructing thread's stack");
    }

    @Test
    void aStartRecordedOutsideAnyConstructorIsNotAConstruction() {
        // The object is built before any record: publishing it through a concurrent
        // collection, reading it elsewhere, then recording the "end", is safe publication
        // however the records are placed.
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        Object built = new Object();
        validator.recordConstructionStart(built);
        Queue<Object> handoff = new ConcurrentLinkedQueue<>();
        handoff.add(built);
        onAnotherThread(() -> validator.recordFieldAccess(handoff.poll(), "name", System.nanoTime()));
        validator.recordConstructionEnd(built);

        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues(), "no constructor was running at any record: " + report);
    }

    @Test
    void anEndRecordedAfterSafePublicationIsNotAnEscape() {
        // Start recorded in the constructor, the end left out there and recorded by the caller
        // only after it has published the finished object through a concurrent queue.
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetySubject subject = new ConstructorSafetySubject(validator, self -> { }, false);
        Queue<ConstructorSafetySubject> handoff = new ConcurrentLinkedQueue<>();
        handoff.add(subject);
        onAnotherThread(() -> validator.recordFieldAccess(handoff.poll(), "name", System.nanoTime()));
        validator.recordConstructionEnd(subject);

        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues(),
            "the constructor had returned before the reference was published: " + report);
    }

    @Test
    void readsOnTwoThreadsOfAnObjectWhoseEndWasNeverRecordedAreNotAnEscape() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetySubject subject = new ConstructorSafetySubject(validator, self -> { }, false);
        validator.recordFieldAccess(subject, "name", System.nanoTime());
        onAnotherThread(() -> validator.recordFieldAccess(subject, "name", System.nanoTime()));

        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues(), "both reads came after the constructor returned: " + report);
        assertTrue(report.fieldsAccessedDuringConstruction.isEmpty(),
            report.fieldsAccessedDuringConstruction.toString());
    }

    @Test
    void nullObjectHandled() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        assertDoesNotThrow(() -> validator.recordConstructionStart(null));
    }

    @Test
    void reportHasIssuesFalseByDefault() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetyValidator.ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues());
        assertTrue(report.unsafeObjects.isEmpty());
        assertTrue(report.possiblyIncompleteConstructions.isEmpty());
        assertTrue(report.fieldsAccessedDuringConstruction.isEmpty());
    }

    @Test
    void reportToStringNoIssues() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        String text = validator.validateConstructorSafety().toString();
        assertNotNull(text);
        assertFalse(text.isBlank());
    }

    @Test
    void resetClearsState() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        new ConstructorSafetySubject(validator,
                self -> onAnotherThread(() -> validator.recordFieldAccess(self, "name", System.nanoTime())),
                true);
        assertTrue(validator.validateConstructorSafety().hasIssues());
        validator.reset();
        assertFalse(validator.validateConstructorSafety().hasIssues());
    }
}
