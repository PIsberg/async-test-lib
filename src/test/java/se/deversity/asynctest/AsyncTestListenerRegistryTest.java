package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.IssueSeverity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AsyncTestListenerRegistry} and the listener event system.
 */
class AsyncTestListenerRegistryTest {

    @BeforeEach
    void setUp() {
        // Clear any existing listeners before each test
        AsyncTestListenerRegistry.clearAll();
    }

    @AfterEach
    void tearDown() {
        // Clean up listeners after each test
        AsyncTestListenerRegistry.clearAll();
    }

    // ---- Registration tests ----

    @Test
    void register_addsListener() {
        AsyncTestListener listener = new NoopAsyncTestListener();
        AsyncTestListenerRegistry.register(listener);

        assertEquals(1, AsyncTestListenerRegistry.getListenerCount());
    }

    @Test
    void register_nullThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
            AsyncTestListenerRegistry.register(null));
    }

    @Test
    void unregister_removesListener() {
        AsyncTestListener listener = new NoopAsyncTestListener();
        AsyncTestListenerRegistry.register(listener);

        boolean removed = AsyncTestListenerRegistry.unregister(listener);

        assertTrue(removed, "unregister should return true for registered listener");
        assertEquals(0, AsyncTestListenerRegistry.getListenerCount());
    }

    @Test
    void unregister_nonExistentReturnsFalse() {
        AsyncTestListener listener = new NoopAsyncTestListener();
        boolean removed = AsyncTestListenerRegistry.unregister(listener);

        assertFalse(removed, "unregister should return false for non-registered listener");
    }

    @Test
    void clearAll_removesAllListeners() {
        AsyncTestListenerRegistry.register(new NoopAsyncTestListener());
        AsyncTestListenerRegistry.register(new NoopAsyncTestListener());
        AsyncTestListenerRegistry.register(new NoopAsyncTestListener());

        AsyncTestListenerRegistry.clearAll();

        assertEquals(0, AsyncTestListenerRegistry.getListenerCount());
    }

    // ---- Event firing tests ----

    @Test
    void fireInvocationStarted_notifiesAllListeners() {
        List<Integer> receivedRounds = new ArrayList<>();
        List<Integer> receivedThreads = new ArrayList<>();

        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onInvocationStarted(int round, int threads) {
                receivedRounds.add(round);
                receivedThreads.add(threads);
            }
        };

        AsyncTestListenerRegistry.register(listener);
        AsyncTestListenerRegistry.register(listener); // Register twice to test multiple

        AsyncTestListenerRegistry.fireInvocationStarted(3, 8);

        assertEquals(2, receivedRounds.size(), "Both listeners should be notified");
        assertEquals(3, receivedRounds.get(0));
        assertEquals(3, receivedRounds.get(1));
        assertEquals(8, receivedThreads.get(0));
        assertEquals(8, receivedThreads.get(1));
    }

    @Test
    void fireInvocationCompleted_notifiesAllListeners() {
        List<Long> receivedDurations = new ArrayList<>();

        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onInvocationCompleted(int round, long durationMs) {
                receivedDurations.add(durationMs);
            }
        };

        AsyncTestListenerRegistry.register(listener);
        AsyncTestListenerRegistry.fireInvocationCompleted(0, 150L);

        assertEquals(1, receivedDurations.size());
        assertEquals(150L, receivedDurations.get(0));
    }

    @Test
    void fireTestFailed_notifiesAllListeners() {
        List<Throwable> receivedCauses = new ArrayList<>();

        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onTestFailed(Throwable cause) {
                receivedCauses.add(cause);
            }
        };

        AsyncTestListenerRegistry.register(listener);
        AssertionError expected = new AssertionError("test failure");
        AsyncTestListenerRegistry.fireTestFailed(expected);

        assertEquals(1, receivedCauses.size());
        assertSame(expected, receivedCauses.get(0));
    }

    @Test
    void fireDetectorReport_notifiesAllListeners() {
        List<String> receivedNames = new ArrayList<>();
        List<String> receivedReports = new ArrayList<>();

        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onDetectorReport(String detectorName, String report) {
                receivedNames.add(detectorName);
                receivedReports.add(report);
            }
        };

        AsyncTestListenerRegistry.register(listener);
        AsyncTestListenerRegistry.fireDetectorReport("FalseSharingDetector", "Report content");

        assertEquals(1, receivedNames.size());
        assertEquals("FalseSharingDetector", receivedNames.get(0));
        assertEquals("Report content", receivedReports.get(0));
    }

    @Test
    void fireDetectorReport_alsoNotifiesOnStructuredReport() {
        AtomicReference<String> receivedName = new AtomicReference<>();
        AtomicReference<IssueSeverity> receivedSeverity = new AtomicReference<>();

        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onStructuredReport(String detectorName, IssueSeverity severity, String report) {
                receivedName.set(detectorName);
                receivedSeverity.set(severity);
            }
        };

        AsyncTestListenerRegistry.register(listener);
        AsyncTestListenerRegistry.fireDetectorReport("FalseSharingDetector", "🔴 CRITICAL: deadlock");

        assertEquals("FalseSharingDetector", receivedName.get());
        assertEquals(IssueSeverity.CRITICAL, receivedSeverity.get());
    }

    @Test
    void fireTimeout_notifiesAllListeners() {
        AtomicLong receivedTimeout = new AtomicLong(-1);

        AsyncTestListener listener = new AsyncTestListener() {
            @Override
            public void onTimeout(long timeoutMs) {
                receivedTimeout.set(timeoutMs);
            }
        };

        AsyncTestListenerRegistry.register(listener);
        AsyncTestListenerRegistry.fireTimeout(5000L);

        assertEquals(5000L, receivedTimeout.get());
    }

    // ---- Listener exception handling ----

    @Test
    void fireInvocationStarted_listenerExceptionDoesNotPropagate() {
        AsyncTestListener throwingListener = new AsyncTestListener() {
            @Override
            public void onInvocationStarted(int round, int threads) {
                throw new RuntimeException("Listener error");
            }
        };

        AsyncTestListenerRegistry.register(throwingListener);

        // Should not throw
        assertDoesNotThrow(() -> AsyncTestListenerRegistry.fireInvocationStarted(0, 4));
    }

    // ---- NoopAsyncTestListener tests ----

    @Test
    void noopAsyncTestListener_allMethodsAreNoop() {
        NoopAsyncTestListener noop = new NoopAsyncTestListener();

        // All methods should complete without throwing or side effects
        assertDoesNotThrow(() -> {
            noop.onInvocationStarted(0, 4);
            noop.onInvocationCompleted(0, 100L);
            noop.onTestFailed(new AssertionError("test"));
            noop.onDetectorReport("TestDetector", "Report");
            noop.onTimeout(5000L);
        });
    }

    @Test
    void noopAsyncTestListener_canBeRegistered() {
        NoopAsyncTestListener noop = new NoopAsyncTestListener();
        AsyncTestListenerRegistry.register(noop);

        assertEquals(1, AsyncTestListenerRegistry.getListenerCount());
        assertTrue(AsyncTestListenerRegistry.unregister(noop));
    }

    // ---- Custom listener implementation test ----

    @Test
    void customListener_receivesAllEvents() {
        CustomTestListener custom = new CustomTestListener();
        AsyncTestListenerRegistry.register(custom);

        AsyncTestListenerRegistry.fireInvocationStarted(2, 5);
        AsyncTestListenerRegistry.fireInvocationCompleted(2, 250L);
        AssertionError failure = new AssertionError("failed");
        AsyncTestListenerRegistry.fireTestFailed(failure);
        AsyncTestListenerRegistry.fireDetectorReport("RaceDetector", "Race detected");
        AsyncTestListenerRegistry.fireTimeout(3000L);

        assertTrue(custom.invocationStartedCalled, "onInvocationStarted should be called");
        assertEquals(2, custom.lastRound);
        assertEquals(5, custom.lastThreads);
        assertTrue(custom.invocationCompletedCalled, "onInvocationCompleted should be called");
        assertEquals(250L, custom.lastDuration);
        assertTrue(custom.testFailedCalled, "onTestFailed should be called");
        assertSame(failure, custom.lastFailure);
        assertTrue(custom.detectorReportCalled, "onDetectorReport should be called");
        assertEquals("RaceDetector", custom.lastDetectorName);
        assertTrue(custom.timeoutCalled, "onTimeout should be called");
        assertEquals(3000L, custom.lastTimeout);
    }

    private static class CustomTestListener implements AsyncTestListener {
        boolean invocationStartedCalled;
        int lastRound;
        int lastThreads;
        boolean invocationCompletedCalled;
        long lastDuration;
        boolean testFailedCalled;
        Throwable lastFailure;
        boolean detectorReportCalled;
        String lastDetectorName;
        String lastReport;
        boolean timeoutCalled;
        long lastTimeout;

        @Override
        public void onInvocationStarted(int round, int threads) {
            invocationStartedCalled = true;
            lastRound = round;
            lastThreads = threads;
        }

        @Override
        public void onInvocationCompleted(int round, long durationMs) {
            invocationCompletedCalled = true;
            lastDuration = durationMs;
        }

        @Override
        public void onTestFailed(Throwable cause) {
            testFailedCalled = true;
            lastFailure = cause;
        }

        @Override
        public void onDetectorReport(String detectorName, String report) {
            detectorReportCalled = true;
            lastDetectorName = detectorName;
            lastReport = report;
        }

        @Override
        public void onTimeout(long timeoutMs) {
            timeoutCalled = true;
            lastTimeout = timeoutMs;
        }
    }

    // ---- Scoped registration / snapshot tests ----

    @Test
    void registerScoped_addsAndAutoUnregistersOnClose() {
        AsyncTestListener listener = new NoopAsyncTestListener();
        try (AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(listener)) {
            assertEquals(1, AsyncTestListenerRegistry.getListenerCount());
            assertNotNull(r);
        }
        assertEquals(0, AsyncTestListenerRegistry.getListenerCount(),
                "Listener should be unregistered after try-with-resources block");
    }

    @Test
    void registerScoped_nullThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                AsyncTestListenerRegistry.registerScoped(null));
    }

    @Test
    void registrationClose_isIdempotent() {
        AsyncTestListener listener = new NoopAsyncTestListener();
        AsyncTestListenerRegistry.Registration r = AsyncTestListenerRegistry.registerScoped(listener);
        r.close();
        r.close(); // second close must not throw or affect other state
        AsyncTestListenerRegistry.register(listener);
        r.close(); // should not remove the re-registered instance again
        assertEquals(1, AsyncTestListenerRegistry.getListenerCount());
    }

    @Test
    void snapshotAndRestore_revertsAddsAndRemoves() {
        AsyncTestListener keep = new NoopAsyncTestListener();
        AsyncTestListenerRegistry.register(keep);

        AsyncTestListenerRegistry.Snapshot snap = AsyncTestListenerRegistry.snapshot();

        AsyncTestListener transient_ = new NoopAsyncTestListener();
        AsyncTestListenerRegistry.register(transient_);
        AsyncTestListenerRegistry.unregister(keep);
        assertEquals(1, AsyncTestListenerRegistry.getListenerCount());

        AsyncTestListenerRegistry.restoreSnapshot(snap);
        assertEquals(1, AsyncTestListenerRegistry.getListenerCount(),
                "Restore must revert to the snapshot — keep present, transient gone");
    }

    @Test
    void restoreSnapshot_nullThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                AsyncTestListenerRegistry.restoreSnapshot(null));
    }
}
