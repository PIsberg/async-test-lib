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
}
