package se.deversity.asynctest.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrictModeListenerTest {

    @Test
    void onDetectorReport_throwsAssertionError() {
        StrictModeListener listener = new StrictModeListener();

        AssertionError ex = assertThrows(AssertionError.class, () ->
            listener.onDetectorReport("FalseSharingDetector", "False sharing detected"));

        assertTrue(ex.getMessage().contains("FalseSharingDetector"),
            "Error message should include the detector name");
        assertTrue(ex.getMessage().contains("False sharing detected"),
            "Error message should include the report content");
    }

    @Test
    void onDetectorReport_messageContainsStrictModeLabel() {
        StrictModeListener listener = new StrictModeListener();

        AssertionError ex = assertThrows(AssertionError.class, () ->
            listener.onDetectorReport("SomeDetector", "Some report"));

        assertTrue(ex.getMessage().contains("strict mode"),
            "Error message should indicate strict mode");
    }

    @Test
    void onInvocationStarted_doesNotThrow() {
        StrictModeListener listener = new StrictModeListener();
        assertDoesNotThrow(() -> listener.onInvocationStarted(0, 4));
    }

    @Test
    void onInvocationCompleted_doesNotThrow() {
        StrictModeListener listener = new StrictModeListener();
        assertDoesNotThrow(() -> listener.onInvocationCompleted(0, 100L));
    }

    @Test
    void onTestFailed_doesNotThrow() {
        StrictModeListener listener = new StrictModeListener();
        assertDoesNotThrow(() -> listener.onTestFailed(new AssertionError("test")));
    }

    @Test
    void onTimeout_doesNotThrow() {
        StrictModeListener listener = new StrictModeListener();
        assertDoesNotThrow(() -> listener.onTimeout(5000L));
    }
}
