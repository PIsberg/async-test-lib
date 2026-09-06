package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompletableFutureCompletionLeakDetector}.
 */
class CompletableFutureCompletionLeakDetectorTest {

    /**
     * The completion has to be claimed once, by whichever of the {@code whenComplete} callback
     * and {@code recordFutureCompleted} arrives first.
     *
     * <p><strong>Why this is a structural test and not a behavioural one.</strong> The defect it
     * guards (#516) is a check-then-set: both paths read the flag, both see {@code false}, both
     * decrement {@code leakCount}. The count then sits below the true number of open futures and
     * a genuine leak stops being reported. Every sequential ordering of the two paths behaves
     * identically before and after the fix, because the second caller sees the flag already set,
     * so no deterministic behavioural test can tell them apart. A 2,000-round barrier-aligned
     * stress probe never hit the window either, and was deleted rather than committed green:
     * a test that passes on the buggy code is worse than no test, because it reads as evidence.
     *
     * <p>So this pins the mechanism instead. A {@code volatile boolean} cannot claim anything
     * exactly once; an {@code AtomicBoolean} claimed with {@code compareAndSet} can. Reverting
     * the field type is the way this regression would come back, and that is what fails here.
     */
    @Test
    void theCompletionFlagIsClaimedAtomically() throws Exception {
        Class<?> state = Class.forName(
                "se.deversity.asynctest.diagnostics.CompletableFutureCompletionLeakDetector$FutureState");
        java.lang.reflect.Field completed = state.getDeclaredField("completed");

        assertEquals(java.util.concurrent.atomic.AtomicBoolean.class, completed.getType(),
                "FutureState.completed must be an AtomicBoolean so the whenComplete callback and "
                    + "recordFutureCompleted can claim the completion exactly once. A volatile "
                    + "boolean read-then-set lets both decrement leakCount, which hides a real "
                    + "leak (#516)");
    }

    @Test
    void completedFuture_noLeakDetected() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "completed-future");
        
        future.complete("result");
        detector.recordFutureCompleted(future, "completed-future");
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        
        assertFalse(report.hasLeaks(), "Completed future should not be reported as leak");
        assertEquals(0, report.getLeakCount());
    }

    @Test
    void uncompletedFuture_leakDetected() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "leaked-future");
        
        // Intentionally NOT completing the future
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        
        assertTrue(report.hasLeaks(), "Uncompleted future should be reported as leak");
        assertEquals(1, report.getLeakCount());
        
        CompletableFutureCompletionLeakDetector.LeakedFuture leaked = report.getLeakedFutures().get(0);
        assertEquals("leaked-future", leaked.getName());
        assertNotNull(leaked.getCreationStackTrace());
    }

    @Test
    void exceptionallyCompletedFuture_noLeakDetected() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "exceptional-future");
        
        future.completeExceptionally(new RuntimeException("test error"));
        detector.recordFutureCompleted(future, "exceptional-future", "completeExceptionally");
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        
        assertFalse(report.hasLeaks());
    }

    @Test
    void cancelledFuture_noLeakDetected() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "cancelled-future");
        
        future.cancel(true);
        detector.recordFutureCompleted(future, "cancelled-future", "cancel");
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        
        assertFalse(report.hasLeaks());
    }

    @Test
    void multipleLeaks_allReported() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future1 = new CompletableFuture<>();
        CompletableFuture<String> future2 = new CompletableFuture<>();
        CompletableFuture<String> future3 = new CompletableFuture<>();
        
        detector.recordFutureCreated(future1, "leak-1");
        detector.recordFutureCreated(future2, "leak-2");
        detector.recordFutureCreated(future3, "leak-3");
        
        // Complete only future2
        future2.complete("done");
        detector.recordFutureCompleted(future2, "leak-2");
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        
        assertTrue(report.hasLeaks());
        assertEquals(2, report.getLeakCount(), "Should report 2 leaks (future1 and future3)");
    }

    @Test
    void whenCompleteAutoTracksCompletion() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "auto-track");
        
        // Complete - whenComplete listener should auto-track
        future.complete("result");
        
        // Give async listener time to execute
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        
        assertFalse(report.hasLeaks(), "whenComplete listener should auto-track completion");
    }

    @Test
    void nullFuture_ignored() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        assertDoesNotThrow(() -> detector.recordFutureCreated(null, "null-future"));
        assertDoesNotThrow(() -> detector.recordFutureCompleted(null, "null-future"));
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        assertEquals(0, report.getLeakCount());
    }

    @Test
    void disabledDetector_noLeaksReported() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        detector.setEnabled(false);
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "disabled-future");
        // Not completed
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        
        assertFalse(report.hasLeaks(), "Disabled detector should not report leaks");
    }

    @Test
    void clear_resetsAllTrackedFutures() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "to-clear");
        
        detector.clear();
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        assertEquals(0, report.getLeakCount(), "After clear, no leaks should be reported");
    }

    @Test
    void hasLeaks_returnsTrueWhenLeaksPresent() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "leak");
        
        assertTrue(detector.hasLeaks());
        assertEquals(1, detector.getLeakCount());
    }

    @Test
    void reportToString_containsLeakDetails() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "test-leak");
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        String reportStr = report.toString();
        
        assertTrue(reportStr.contains("1 uncompleted CompletableFuture"));
        assertTrue(reportStr.contains("test-leak"));
        assertTrue(reportStr.contains("Possible causes"));
    }

    @Test
    void leakedFuture_detailsAccessible() {
        CompletableFutureCompletionLeakDetector detector = new CompletableFutureCompletionLeakDetector();
        
        CompletableFuture<String> future = new CompletableFuture<>();
        detector.recordFutureCreated(future, "detailed-leak");
        
        CompletableFutureCompletionLeakDetector.CompletionLeakReport report = detector.analyze();
        CompletableFutureCompletionLeakDetector.LeakedFuture leaked = report.getLeakedFutures().get(0);
        
        assertEquals("detailed-leak", leaked.getName());
        assertTrue(leaked.getCreatorThreadId() > 0);
        assertTrue(leaked.getAgeMillis() >= 0);
        assertNotNull(leaked.getCreationStackTrace());
        assertTrue(leaked.getCreationStackTrace().length > 0);
    }
}
