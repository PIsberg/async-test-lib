package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NotifyAllValidatorTest {

    private NotifyAllValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NotifyAllValidator();
    }

    @Test
    void noMonitorsReturnNoIssues() {
        NotifyAllValidator.NotifyAllReport report = validator.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.notifyInsteadOfNotifyAll.isEmpty());
    }

    @Test
    void notifyAllWithMultipleWaitersNoIssues() {
        Object monitor = new Object();
        validator.recordWaiterAdded(monitor, "sharedMonitor");
        validator.recordWaiterAdded(monitor, "sharedMonitor");
        validator.recordNotify(monitor, true);

        NotifyAllValidator.NotifyAllReport report = validator.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.notifyInsteadOfNotifyAll.isEmpty());
    }

    @Test
    void notifyWithSingleWaiterNoIssues() {
        Object monitor = new Object();
        validator.recordWaiterAdded(monitor, "singleWaiterMonitor");
        validator.recordNotify(monitor, false);

        NotifyAllValidator.NotifyAllReport report = validator.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.notifyInsteadOfNotifyAll.isEmpty());
    }

    @Test
    void notifyInsteadOfNotifyAllDetected() {
        Object monitor = new Object();
        validator.recordWaiterAdded(monitor, "multiWaiterMonitor");
        validator.recordWaiterAdded(monitor, "multiWaiterMonitor");
        validator.recordNotify(monitor, false);

        NotifyAllValidator.NotifyAllReport report = validator.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.notifyInsteadOfNotifyAll.isEmpty());
    }

    @Test
    void nullMonitorHandled() {
        assertDoesNotThrow(() -> validator.recordWaiterAdded(null, "nullMonitor"));
    }

    @Test
    void reportToStringWithIssue() {
        Object monitor = new Object();
        validator.recordWaiterAdded(monitor, "problematicMonitor");
        validator.recordWaiterAdded(monitor, "problematicMonitor");
        validator.recordNotify(monitor, false);

        NotifyAllValidator.NotifyAllReport report = validator.analyze();
        String str = report.toString();
        assertNotNull(str);
        assertTrue(str.contains("NOTIFY/NOTIFYALL ISSUES"));
    }

    @Test
    void resetClearsState() {
        Object monitor = new Object();
        validator.recordWaiterAdded(monitor, "monitor");
        validator.recordWaiterAdded(monitor, "monitor");
        validator.recordNotify(monitor, false);
        validator.reset();

        NotifyAllValidator.NotifyAllReport report = validator.analyze();
        assertFalse(report.hasIssues());
        assertTrue(report.notifyInsteadOfNotifyAll.isEmpty());
    }

    @Test
    void disabledSkipsRecording() {
        Object monitor = new Object();
        validator.disable();
        validator.recordWaiterAdded(monitor, "monitor");
        validator.recordWaiterAdded(monitor, "monitor");
        validator.recordNotify(monitor, false);

        NotifyAllValidator.NotifyAllReport report = validator.analyze();
        assertFalse(report.hasIssues());
    }
}
