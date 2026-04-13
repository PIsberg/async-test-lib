package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScopedValueMisuseDetector}.
 */
class ScopedValueMisuseDetectorTest {

    private ScopedValueMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ScopedValueMisuseDetector();
    }

    // ---- Happy path ----

    @Test
    void noIssues_whenGetCalledInsideBinding() {
        Thread thread = Thread.currentThread();
        detector.recordBindingEntered("USER_ID", thread);
        detector.recordGetCalled("USER_ID", thread);       // inside binding — OK
        detector.recordBindingExited("USER_ID", thread);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Expected no issues: " + report);
        assertEquals(0, report.getUnboundGetCount());
    }

    @Test
    void noIssues_forMultipleBindingsAndGets() {
        Thread thread = Thread.currentThread();
        for (int i = 0; i < 10; i++) {
            detector.recordBindingEntered("SV_" + i, thread);
        }
        for (int i = 0; i < 10; i++) {
            detector.recordGetCalled("SV_" + i, thread);
        }
        for (int i = 0; i < 10; i++) {
            detector.recordBindingExited("SV_" + i, thread);
        }

        var report = detector.analyze();
        assertFalse(report.hasIssues());
        assertEquals(0, report.getUnboundGetCount());
    }

    // ---- Unbound get ----

    @Test
    void detectsUnboundGet_whenGetCalledWithoutBinding() {
        Thread thread = Thread.currentThread();
        // No binding entered
        detector.recordGetCalled("USER_ID", thread);

        var report = detector.analyze();

        assertTrue(report.hasIssues());
        assertFalse(report.getUnboundGetIssues().isEmpty(),
            "Should detect get() without binding");
        String issue = report.getUnboundGetIssues().get(0);
        assertTrue(issue.contains("USER_ID"), issue);
        assertTrue(issue.contains("NoSuchElementException"), issue);
        assertEquals(1, report.getUnboundGetCount());
    }

    @Test
    void detectsUnboundGet_whenGetCalledAfterBindingExited() {
        Thread thread = Thread.currentThread();
        detector.recordBindingEntered("USER_ID", thread);
        detector.recordGetCalled("USER_ID", thread);  // OK
        detector.recordBindingExited("USER_ID", thread);

        // Now outside the binding scope
        detector.recordGetCalled("USER_ID", thread);  // VIOLATION

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getUnboundGetCount(), "Only the post-exit get should be counted");
    }

    @Test
    void detectsUnboundGet_forDifferentVariable() {
        Thread thread = Thread.currentThread();
        detector.recordBindingEntered("USER_ID", thread);
        // Get a different variable that was never bound
        detector.recordGetCalled("TENANT_ID", thread);  // not bound!
        detector.recordBindingExited("USER_ID", thread);

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getUnboundGetIssues().isEmpty());
        assertTrue(report.getUnboundGetIssues().get(0).contains("TENANT_ID"));
    }

    // ---- Re-binding detection ----

    @Test
    void detectsRebinding_whenSameScopedValueBoundTwice() {
        Thread thread = Thread.currentThread();
        detector.recordBindingEntered("USER_ID", thread);  // outer
        detector.recordBindingEntered("USER_ID", thread);  // inner — re-bind!
        detector.recordGetCalled("USER_ID", thread);
        detector.recordBindingExited("USER_ID", thread);   // inner
        detector.recordBindingExited("USER_ID", thread);   // outer

        var report = detector.analyze();
        assertFalse(report.getRebindIssues().isEmpty(),
            "Should detect re-binding of same ScopedValue");
        String issue = report.getRebindIssues().get(0);
        assertTrue(issue.contains("USER_ID"), issue);
        assertTrue(issue.contains("nested"), issue);
    }

    @Test
    void noRebindWarning_whenDifferentVariablesBound() {
        Thread thread = Thread.currentThread();
        detector.recordBindingEntered("USER_ID", thread);
        detector.recordBindingEntered("TENANT_ID", thread); // different SV — OK
        detector.recordGetCalled("USER_ID", thread);
        detector.recordGetCalled("TENANT_ID", thread);
        detector.recordBindingExited("TENANT_ID", thread);
        detector.recordBindingExited("USER_ID", thread);

        var report = detector.analyze();
        assertTrue(report.getRebindIssues().isEmpty(),
            "Binding two different ScopedValues should not trigger re-bind warning");
    }

    // ---- Null safety ----

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.recordBindingEntered(null, Thread.currentThread());
            detector.recordBindingEntered("KEY", null);
            detector.recordGetCalled(null, Thread.currentThread());
            detector.recordGetCalled("KEY", null);
            detector.recordBindingExited(null, Thread.currentThread());
            detector.recordBindingExited("KEY", null);
        });
    }

    // ---- Stats ----

    @Test
    void tracksBindingAndGetCounts() {
        Thread thread = Thread.currentThread();
        detector.recordBindingEntered("A", thread);
        detector.recordBindingEntered("B", thread);
        detector.recordGetCalled("A", thread);
        detector.recordGetCalled("B", thread);
        detector.recordGetCalled("A", thread);
        detector.recordBindingExited("A", thread);
        detector.recordBindingExited("B", thread);

        var report = detector.analyze();
        assertEquals(2, report.getTotalBindings());
        assertEquals(3, report.getTotalGetCalls());
        assertEquals(0, report.getUnboundGetCount());
    }

    // ---- toString ----

    @Test
    void toString_containsLearningContent_whenIssuesFound() {
        detector.recordGetCalled("UNBOUND_SV", Thread.currentThread());
        var report = detector.analyze();
        String str = report.toString();
        assertTrue(str.contains("LEARNING"), str);
        assertTrue(str.contains("ScopedValue"), str);
        assertTrue(str.contains("where"), str);
    }

    @Test
    void toString_isClean_whenNoIssues() {
        var report = detector.analyze();
        assertTrue(report.toString().contains("No ScopedValue misuse"));
    }

    @Test
    void toString_showsCriticalSeverity_forUnboundGet() {
        detector.recordGetCalled("SV", Thread.currentThread());
        var report = detector.analyze();
        String str = report.toString();
        assertTrue(str.contains("CRITICAL"), str);
    }

    // ---- Multi-thread ----

    @Test
    void isolatesBindings_perThread() throws Exception {
        // Thread A has a binding; thread B does not
        Thread[] threadBRef = new Thread[1];
        Thread threadA = new Thread(() -> {
            detector.recordBindingEntered("USER_ID", Thread.currentThread());
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            detector.recordGetCalled("USER_ID", Thread.currentThread()); // OK in thread A
            detector.recordBindingExited("USER_ID", Thread.currentThread());
        });
        Thread threadB = new Thread(() -> {
            threadBRef[0] = Thread.currentThread();
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            // Thread B never entered binding
            detector.recordGetCalled("USER_ID", Thread.currentThread()); // VIOLATION in thread B
        });

        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Thread B's unbound get should be detected");
        assertEquals(1, report.getUnboundGetCount(), "Only thread B violated");
    }
}
