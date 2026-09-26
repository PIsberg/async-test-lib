package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    void aPooledThreadInsideAnotherInstancesConstructorIsNotStillConstructingTheFirst()
            throws Exception {
        // #778. One pooled thread builds the first subject (no end recorded) and hands it out
        // through a Future. The same thread then builds a second subject, and while it is inside
        // that constructor another thread reads the first one. A constructor of the class is on
        // the pooled thread's stack, but it is the second instance's, not the first one's.
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ConstructorSafetySubject first = pool.submit(
                    () -> new ConstructorSafetySubject(validator, self -> { }, false))
                    .get(5, TimeUnit.SECONDS);
            pool.submit(() -> new ConstructorSafetySubject(validator,
                    self -> onAnotherThread(
                            () -> validator.recordFieldAccess(first, "name", System.nanoTime())),
                    false)).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ConstructorSafetyValidator.ConstructorSafetyReport report =
                validator.validateConstructorSafety();
        assertFalse(report.hasIssues(),
            "the first constructor had returned before its object was published: " + report);
    }

    @Test
    void aPooledThreadsSecondInstanceEscapingItsConstructorIsStillReported() throws Exception {
        // The firing twin of the test above: the second instance leaks itself mid-construction.
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> new ConstructorSafetySubject(validator, self -> { }, false))
                    .get(5, TimeUnit.SECONDS);
            pool.submit(() -> new ConstructorSafetySubject(validator,
                    self -> onAnotherThread(
                            () -> validator.recordFieldAccess(self, "name", System.nanoTime())),
                    false)).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        ConstructorSafetyValidator.ConstructorSafetyReport report =
                validator.validateConstructorSafety();
        assertTrue(report.hasIssues(), "the second instance escaped its constructor: " + report);
        assertTrue(report.fieldsAccessedDuringConstruction.contains("ConstructorSafetySubject.name"),
            report.fieldsAccessedDuringConstruction.toString());
    }

    @Test
    void anEscapeAfterANestedConstructionOfTheSameClassIsStillReported() {
        // A constructor that builds another instance of its own class before leaking `this`: the
        // nested construction starts deeper on the same thread, so the outer one is still running.
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        new ConstructorSafetySubject(validator, self -> {
            new ConstructorSafetySubject(validator, inner -> { }, false);
            onAnotherThread(() -> validator.recordFieldAccess(self, "name", System.nanoTime()));
        }, false);

        assertTrue(validator.validateConstructorSafety().hasIssues(),
            "the outer constructor was still on the stack when its object was read");
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
