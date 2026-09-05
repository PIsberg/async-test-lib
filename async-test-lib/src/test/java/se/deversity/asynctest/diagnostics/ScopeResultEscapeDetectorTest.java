package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeResultEscapeDetectorTest {

    @Test
    void cleanWhenNothingRecorded() {
        var d = new ScopeResultEscapeDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("SCOPE RESULT ESCAPE - clean", d.analyze().toString());
    }

    /**
     * The corrected shape: the results are read on the owner thread, inside the scope, after
     * join(). Same calls as the failing cases, in the right order - no finding.
     */
    @Test
    void resultsReadInsideTheScopeOnTheOwnerThreadStaySilent() {
        var d = new ScopeResultEscapeDetector();
        List<String> results = List.of("a", "b");
        d.recordScopeOpened("scope-1", Thread.currentThread());
        d.recordJoinCompleted("scope-1");
        d.recordResultHandle(results, "orders", "scope-1");
        d.recordHandleRead(results, Thread.currentThread());
        d.recordHandlePublished(results, Thread.currentThread());
        d.recordScopeClosed("scope-1");

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void aResultReadAfterTheScopeClosedIsReported() {
        var d = new ScopeResultEscapeDetector();
        List<String> results = List.of("a");
        d.recordScopeOpened("scope-1", Thread.currentThread());
        d.recordJoinCompleted("scope-1");
        d.recordResultHandle(results, "orders", "scope-1");
        d.recordScopeClosed("scope-1");
        d.recordHandleRead(results, Thread.currentThread());   // outside the try-with-resources

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("read 1 time(s) after the scope closed")));
        assertTrue(report.structuredViolations.stream()
                .anyMatch(v -> "resultReadAfterScopeClose".equals(v.attributes().get("issue"))));
    }

    @Test
    void aResultReadOnAnotherThreadIsReported() throws Exception {
        var d = new ScopeResultEscapeDetector();
        List<String> results = List.of("a");
        d.recordScopeOpened("scope-1", Thread.currentThread());
        d.recordJoinCompleted("scope-1");
        d.recordResultHandle(results, "orders", "scope-1");

        Thread reader = new Thread(() -> d.recordHandleRead(results, Thread.currentThread()), "consumer");
        reader.start();
        reader.join();
        d.recordScopeClosed("scope-1");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("consumer")));
    }

    @Test
    void publishingTheHandleBeforeJoinIsReported() {
        var d = new ScopeResultEscapeDetector();
        List<String> results = new ArrayList<>();
        d.recordScopeOpened("scope-1", Thread.currentThread());
        d.recordResultHandle(results, "orders", "scope-1");
        d.recordHandlePublished(results, Thread.currentThread());   // join() has not returned yet
        d.recordJoinCompleted("scope-1");
        d.recordScopeClosed("scope-1");

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("published to shared state 1 time(s) before join()")));
    }

    @Test
    void mutatingTheReturnedListIsReported() {
        var d = new ScopeResultEscapeDetector();
        List<String> results = List.of("a");
        d.recordScopeOpened("scope-1", Thread.currentThread());
        d.recordJoinCompleted("scope-1");
        d.recordResultHandle(results, "orders", "scope-1");
        d.recordHandleMutation(results, Thread.currentThread());
        d.recordScopeClosed("scope-1");

        var report = d.analyze();
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("was modified 1 time(s)")));
        assertTrue(report.toString().contains("SCOPE RESULT ESCAPE DETECTED"));
    }

    /** A scope that never closed cannot have a read after its close. */
    @Test
    void readsWhileTheScopeIsStillOpenStaySilent() {
        var d = new ScopeResultEscapeDetector();
        List<String> results = List.of("a");
        d.recordScopeOpened("scope-1", Thread.currentThread());
        d.recordJoinCompleted("scope-1");
        d.recordResultHandle(results, "orders", "scope-1");
        for (int i = 0; i < 10; i++) d.recordHandleRead(results, Thread.currentThread());

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void aHandleWithNoKnownScopeIsIgnored() {
        var d = new ScopeResultEscapeDetector();
        List<String> results = List.of("a");
        d.recordResultHandle(results, "orders", "never-opened");
        d.recordHandleRead(results, Thread.currentThread());
        d.recordHandleMutation(results, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void recordingIsIgnoredWhileDisabled() {
        var d = new ScopeResultEscapeDetector();
        List<String> results = List.of("a");
        d.recordScopeOpened("scope-1", Thread.currentThread());
        d.recordResultHandle(results, "orders", "scope-1");
        d.recordScopeClosed("scope-1");
        d.disable();
        d.recordHandleRead(results, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());

        d.enable();
        d.recordHandleRead(results, Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void nullArgumentsAreIgnored() {
        var d = new ScopeResultEscapeDetector();
        d.recordScopeOpened(null, Thread.currentThread());
        d.recordScopeOpened("scope-1", null);
        d.recordResultHandle(null, "x", "scope-1");
        d.recordHandleRead(null, Thread.currentThread());
        d.recordJoinCompleted(null);
        d.recordScopeClosed(null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void aScopeIdReopenedInTheNextRoundStartsFresh() {
        var d = new ScopeResultEscapeDetector();
        Object first = new Object();
        Object second = new Object();
        d.recordScopeOpened("scope-1", Thread.currentThread());
        d.recordJoinCompleted("scope-1");
        d.recordResultHandle(first, "orders", "scope-1");
        d.recordHandleRead(first, Thread.currentThread());
        d.recordScopeClosed("scope-1");

        d.recordScopeOpened("scope-1", Thread.currentThread());      // same id, next round
        d.recordJoinCompleted("scope-1");
        d.recordResultHandle(second, "orders", "scope-1");
        d.recordHandleRead(second, Thread.currentThread());
        d.recordScopeClosed("scope-1");

        assertFalse(d.analyze().hasIssues(),
            "the read happened inside the reopened scope; comparing it against the previous "
                + "round's close invented a read-after-close: " + d.analyze());
    }
}
