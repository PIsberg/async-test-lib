package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import se.deversity.asynctest.AgentConstructionHooks;

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

    /**
     * What {@link ConstructorSafetyValidator} makes of the constructor returns the agent reports
     * (#791), with the hook calls written where the weaver inserts them: before each return of a
     * constructor, and after a {@code this(...)} delegation. {@code ConstructorExitWeavingTest} in
     * the agent module runs the same cases through real weaving.
     */
    @Nested
    class WovenConstructorReturns {

        /** Records its start and never its end, and reports its returns as a woven class does. */
        static class Subject {

            String name;

            /** Records its start, then runs {@code during}. */
            Subject(ConstructorSafetyValidator validator, Consumer<Object> during) {
                validator.recordConstructionStart(this);
                during.accept(this);
                this.name = "built";
                AgentConstructionHooks.constructorReturned(this, Subject.class.getName());
            }

            /** Runs {@code beforeStart}, then records its start. */
            Subject(Consumer<Object> beforeStart, ConstructorSafetyValidator validator) {
                beforeStart.accept(this);
                validator.recordConstructionStart(this);
                this.name = "built";
                AgentConstructionHooks.constructorReturned(this, Subject.class.getName());
            }

            /** Records nothing. */
            Subject(Consumer<Object> during) {
                during.accept(this);
                this.name = "built";
                AgentConstructionHooks.constructorReturned(this, Subject.class.getName());
            }

            /** Delegates, then runs {@code afterDelegation}. */
            Subject(ConstructorSafetyValidator validator, Consumer<Object> afterDelegation,
                    int marker) {
                this(validator, self -> { });
                AgentConstructionHooks.constructorResumed(this, Subject.class.getName());
                afterDelegation.accept(this);
                AgentConstructionHooks.constructorReturned(this, Subject.class.getName());
            }
        }

        /** Leaks itself once its superclass constructor has returned. */
        static final class Sub extends Subject {

            Sub(ConstructorSafetyValidator validator, Consumer<Object> afterSuper) {
                super(validator, self -> { });
                afterSuper.accept(this);
                AgentConstructionHooks.constructorReturned(this, Sub.class.getName());
            }
        }

        private static Consumer<Object> readOnAnotherThread(ConstructorSafetyValidator validator,
                                                            Object subject) {
            return ignored -> onAnotherThread(
                    () -> validator.recordFieldAccess(subject, "name", System.nanoTime()));
        }

        private static ConstructorSafetyValidator.ConstructorSafetyReport pooled(
                ConstructorSafetyValidator validator, Consumer<Subject> second) throws Exception {
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Subject first = pool.submit(() -> new Subject(validator, self -> { }))
                        .get(5, TimeUnit.SECONDS);
                pool.submit(() -> second.accept(first)).get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            return validator.validateConstructorSafety();
        }

        @Test
        @DisplayName("a read before the pooled thread's next start is recorded is not an escape")
        void aReadBeforeTheNextStartIsNotAnEscape() throws Exception {
            ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
            assertFalse(pooled(validator,
                    first -> new Subject(readOnAnotherThread(validator, first), validator))
                    .hasIssues());
        }

        @Test
        @DisplayName("a later constructor recording no start is not still building the first")
        void aLaterConstructorRecordingNoStartIsNotAnEscape() throws Exception {
            ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
            assertFalse(pooled(validator,
                    first -> new Subject(readOnAnotherThread(validator, first))).hasIssues());
        }

        @Test
        @DisplayName("the second instance escaping its own constructor still reports")
        void theSecondInstanceEscapingStillReports() throws Exception {
            ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
            assertTrue(pooled(validator, first -> new Subject(validator,
                    self -> readOnAnotherThread(validator, self).accept(self))).hasIssues());
        }

        @Test
        @DisplayName("a delegation reopens what its callee's return closed")
        void aDelegationReopensTheConstruction() {
            ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
            new Subject(validator, self -> readOnAnotherThread(validator, self).accept(self), 0);
            assertTrue(validator.validateConstructorSafety().hasIssues());
        }

        @Test
        @DisplayName("a superclass constructor's return closes nothing")
        void aSuperclassReturnClosesNothing() {
            ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
            new Sub(validator, self -> readOnAnotherThread(validator, self).accept(self));
            assertTrue(validator.validateConstructorSafety().hasIssues());
        }

        @Test
        @DisplayName("an end recorded by hand is not reopened by a delegation")
        void aHandRecordedEndStaysEnded() {
            ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
            Object built = new Object() {
                // A constructor that records its start, has its end recorded by hand, and is then
                // told a delegation resumed: the hand-recorded end is the authority.
                {
                    validator.recordConstructionStart(this);
                    validator.recordConstructionEnd(this);
                    AgentConstructionHooks.constructorResumed(this, getClass().getName());
                    onAnotherThread(
                            () -> validator.recordFieldAccess(this, "name", System.nanoTime()));
                }
            };
            assertFalse(validator.validateConstructorSafety().hasIssues(), built.toString());
        }

        @Test
        @DisplayName("a return with nothing tracked for its class allocates nothing")
        void aReturnOfAnUntrackedClassAllocatesNothing() throws InterruptedException {
            // Something is tracked, so the empty-map check alone does not answer.
            ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
            Subject tracked = new Subject(validator, self -> { });
            Object untracked = new Object();
            String name = Object.class.getName();

            long bytes = RecordPathAllocation.measuredBytes(
                    () -> AgentConstructionHooks.constructorReturned(untracked, name));

            assertTrue(bytes < RecordPathAllocation.CEILING, "every constructor of every woven class calls this: "
                    + bytes + " bytes over " + RecordPathAllocation.MEASURED_CALLS + " calls");
            assertTrue(validator.validateConstructorSafety()
                            .possiblyIncompleteConstructions.isEmpty(),
                    "and the tracked one was closed by its return: " + tracked.name);
        }
    }
}
