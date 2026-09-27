package se.deversity.asynctest.agent;

import com.example.agentfixture.ConstructionEscapeSubject;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ConstructorSafetyValidator;
import se.deversity.asynctest.diagnostics.ConstructorSafetyValidator.ConstructorSafetyReport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * With the agent, a constructor's return records the end of its construction, so
 * {@link ConstructorSafetyValidator} no longer judges an earlier instance by a later constructor of
 * its class on the constructing thread's stack (#791).
 *
 * <p>The two silent cases are the ones #778 left: a pooled thread already inside the next
 * constructor of the class, before that constructor records its start, and a later constructor
 * that records none. The loud ones are the escapes the return must not hide: a leak in the same
 * constructor, in a delegating constructor after its {@code this(...)} returned, and in a subclass
 * constructor after its superclass constructor returned.
 */
@Tag("e2e")
class ConstructorExitWeavingTest {

    @BeforeAll
    static void attachWithConstructionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
    }

    /** Runs {@code body} on a new thread and waits for it. */
    private static void onAnotherThread(Runnable body) {
        Thread t = new Thread(body, "constructor-exit-other");
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Reads {@code subject} on another thread, as a consumer of a published reference does. */
    private static Consumer<Object> readOnAnotherThread(ConstructorSafetyValidator validator,
                                                        Object subject) {
        return ignored -> onAnotherThread(
                () -> validator.recordFieldAccess(subject, "name", System.nanoTime()));
    }

    /**
     * Builds a first subject on one pooled thread, which records its start and never its end, and
     * hands it out; then runs {@code second} on the same pooled thread with the first.
     */
    private static ConstructorSafetyReport pooled(ConstructorSafetyValidator validator,
                                                  Consumer<ConstructionEscapeSubject> second)
            throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ConstructionEscapeSubject first = pool.submit(
                    () -> new ConstructionEscapeSubject(validator, self -> { }))
                    .get(5, TimeUnit.SECONDS);
            pool.submit(() -> second.accept(first)).get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        return validator.validateConstructorSafety();
    }

    @Test
    @DisplayName("a read before the pooled thread's next constructor records its start is not an escape")
    void aReadBeforeTheNextConstructorRecordsItsStartIsNotAnEscape() throws Exception {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetyReport report = pooled(validator, first -> new ConstructionEscapeSubject(
                readOnAnotherThread(validator, first), validator));

        assertFalse(report.hasIssues(),
                "the first constructor returned before its object was handed out, and the woven "
                        + "return recorded that; the constructor on the pooled thread's stack is "
                        + "the second instance's: " + report);
    }

    @Test
    @DisplayName("a later constructor that records no start is not still building the first")
    void aLaterConstructorRecordingNoStartIsNotStillBuildingTheFirst() throws Exception {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetyReport report = pooled(validator, first -> new ConstructionEscapeSubject(
                readOnAnotherThread(validator, first)));

        assertFalse(report.hasIssues(), "the first construction ended at its return: " + report);
    }

    @Test
    @DisplayName("the pooled thread's second instance escaping its own constructor still reports")
    void theSecondInstanceEscapingItsConstructorStillReports() throws Exception {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        ConstructorSafetyReport report = pooled(validator, first -> new ConstructionEscapeSubject(
                validator, self -> readOnAnotherThread(validator, self).accept(self)));

        assertTrue(report.hasIssues(), "the read ran before the second constructor returned");
    }

    @Test
    @DisplayName("a leak after this(...) returned is still inside construction")
    void aLeakAfterADelegationReturnedStillReports() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        new ConstructionEscapeSubject(validator,
                self -> readOnAnotherThread(validator, self).accept(self), 0);

        assertTrue(validator.validateConstructorSafety().hasIssues(),
                "the delegated-to constructor returned, but the delegating one had not: its "
                        + "return must not end the construction the delegating one continues");
    }

    @Test
    @DisplayName("a leak in a subclass constructor after its superclass constructor returned still reports")
    void aLeakAfterTheSuperclassConstructorReturnedStillReports() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        new ConstructionEscapeSubject.LeakingSubclass(validator,
                self -> readOnAnotherThread(validator, self).accept(self));

        assertTrue(validator.validateConstructorSafety().hasIssues(),
                "only the constructor of the object's own class ends its construction");
    }

    @Test
    @DisplayName("a construction whose end nobody recorded is complete once its constructor returned")
    void aReturnedConstructionIsComplete() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        new ConstructionEscapeSubject(validator, self -> { });

        ConstructorSafetyReport report = validator.validateConstructorSafety();
        assertFalse(report.hasIssues(), report.toString());
        assertTrue(report.possiblyIncompleteConstructions.isEmpty(),
                "the woven return recorded the end nobody recorded by hand: " + report);
    }
}
