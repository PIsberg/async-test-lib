package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LazyInitValidatorTest {

    private LazyInitValidator validator;

    @BeforeEach
    void setUp() {
        validator = new LazyInitValidator();
    }

    @Test
    void noAccessesReturnNoIssues() {
        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.multipleInitializations.isEmpty());
        assertTrue(report.unsafePublication.isEmpty());
    }

    @Test
    void synchronizedInitNoIssues() {
        validator.recordAccess("myField", true, true, true, false);
        validator.recordAccess("myField", true, true, true, false);

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void volatileFieldNoIssues() {
        validator.recordAccess("volatileField", true, true, false, true);
        validator.recordAccess("volatileField", true, true, false, true);

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertFalse(report.hasIssues());
    }

    @Test
    void unsafePublicationFromMultipleThreads() throws InterruptedException {
        Thread t1 = new Thread(() ->
                validator.recordAccess("sharedField", true, true, false, false));
        Thread t2 = new Thread(() ->
                validator.recordAccess("sharedField", true, true, false, false));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.unsafePublication.isEmpty());
    }

    @Test
    void multipleInitAttemptDetected() throws InterruptedException {
        Thread t1 = new Thread(() ->
                validator.recordAccess("lazyField", true, true, false, false));
        Thread t2 = new Thread(() ->
                validator.recordAccess("lazyField", true, true, false, false));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.multipleInitializations.isEmpty());
    }

    @Test
    void resetClearsState() {
        validator.recordAccess("someField", true, true, false, false);
        validator.reset();

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.multipleInitializations.isEmpty());
        assertTrue(report.unsafePublication.isEmpty());
    }

    @Test
    void reportToStringWithIssue() throws InterruptedException {
        Thread t1 = new Thread(() ->
                validator.recordAccess("problematicField", true, true, false, false));
        Thread t2 = new Thread(() ->
                validator.recordAccess("problematicField", true, true, false, false));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        LazyInitValidator.LazyInitReport report = validator.analyze();
        String str = report.toString();
        assertNotNull(str);
        assertFalse(str.isEmpty());
    }

    /** Runs {@code body} on a fresh thread and waits for it, standing in for one round's worker. */
    private static void onAFreshThread(Runnable body) throws InterruptedException {
        Thread t = new Thread(body);
        t.start();
        t.join();
    }

    @Test
    void aFieldBuiltOncePerRoundByADifferentThreadIsNoRaceAcrossRounds() throws InterruptedException {
        // #764: a test that builds its lazy holder per round reuses the field name for a fresh
        // field, initialised once, by one thread, each round. Kept run-wide, the rounds add up to
        // two initialisations from two threads, which is the finding for a race nobody had.
        onAFreshThread(() -> validator.recordAccess("config", true, true, false, false));
        validator.markInvocationStart();
        onAFreshThread(() -> validator.recordAccess("config", true, true, false, false));

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertFalse(report.hasIssues(), "one initialisation per round is not a race: " + report);
    }

    @Test
    void aRaceWithinOneRoundIsStillReportedAfterTheRoundCloses() throws InterruptedException {
        Thread t1 = new Thread(() -> validator.recordAccess("config", true, true, false, false));
        Thread t2 = new Thread(() -> validator.recordAccess("config", true, true, false, false));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        validator.markInvocationStart();   // a clean round follows; the race belongs to the one before
        onAFreshThread(() -> validator.recordAccess("config", false, true, false, false));

        LazyInitValidator.LazyInitReport report = validator.analyze();
        assertFalse(report.multipleInitializations.isEmpty(), "the closed round's race must survive: " + report);
        assertFalse(report.unsafePublication.isEmpty(), "and so must its unsafe publication: " + report);
    }

    @Test
    void resetAlsoForgetsClosedRounds() throws InterruptedException {
        Thread t1 = new Thread(() -> validator.recordAccess("config", true, true, false, false));
        Thread t2 = new Thread(() -> validator.recordAccess("config", true, true, false, false));
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        validator.markInvocationStart();
        validator.reset();

        assertFalse(validator.analyze().hasIssues(), "reset starts the instance over");
    }
}
