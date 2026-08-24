package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeJoinerMisuseDetectorTest {

    private static Object joiner() { return new Object(); }

    /** Waits on a latch, failing the test rather than the thread if it never opens. */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "latch never opened");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting on latch", e);
        }
    }

    @Test
    void cleanWhenNothingRecorded() {
        var d = new ScopeJoinerMisuseDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("SCOPE JOINER MISUSE - clean", d.analyze().toString());
    }

    /**
     * The corrected shape: one joiner, one scope, subtasks completing one at a time, everything
     * owner-confined. Same recording calls as the failing cases - no finding.
     */
    @Test
    void aWellBehavedJoinerStaysSilent() {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        Thread owner = Thread.currentThread();
        d.recordJoinerBound(j, "orders", "scope-1", owner);

        for (int i = 0; i < 4; i++) {
            d.recordFork(j, owner);
            d.recordOnCompleteEnter(j, owner);
            d.recordAccumulate(j, owner);
            d.recordOnCompleteExit(j, owner, false);
        }
        d.recordResult(j, owner);

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void aJoinerPassedToTwoScopesIsReported() {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        d.recordJoinerBound(j, "orders", "scope-1", Thread.currentThread());
        d.recordJoinerBound(j, "orders", "scope-2", Thread.currentThread());

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("passed to 2 scopes")));
        assertTrue(report.structuredViolations.stream()
                .anyMatch(v -> "joinerReusedAcrossScopes".equals(v.attributes().get("issue"))));
    }

    /** A fresh joiner per scope is the fix, and it produces no finding. */
    @Test
    void aFreshJoinerPerScopeStaysSilent() {
        var d = new ScopeJoinerMisuseDetector();
        d.recordJoinerBound(joiner(), "orders", "scope-1", Thread.currentThread());
        d.recordJoinerBound(joiner(), "orders", "scope-2", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void twoThreadsWritingInsideOverlappingOnCompleteIsReported() throws Exception {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        d.recordJoinerBound(j, "orders", "scope-1", Thread.currentThread());

        // Both threads are inside onComplete at the same moment, and both write before
        // either leaves - otherwise the first to exit drops the in-flight count and the
        // second write is no longer evidence of anything.
        var bothIn = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var bothWrote = new CountDownLatch(2);
        Runnable body = () -> {
            d.recordOnCompleteEnter(j, Thread.currentThread());
            bothIn.countDown();
            await(release);
            d.recordAccumulate(j, Thread.currentThread());
            bothWrote.countDown();
            await(bothWrote);
            d.recordOnCompleteExit(j, Thread.currentThread(), false);
        };
        Thread a = new Thread(body, "subtask-a");
        Thread b = new Thread(body, "subtask-b");
        a.start();
        b.start();
        assertTrue(bothIn.await(5, TimeUnit.SECONDS));
        release.countDown();
        a.join();
        b.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("inside onComplete at once")));
    }

    /** Declaring the accumulator thread-safe suppresses that finding and nothing else fires. */
    @Test
    void aThreadSafeJoinerSuppressesTheAccumulationFinding() throws Exception {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        d.recordJoinerBound(j, "orders", "scope-1", Thread.currentThread());
        d.declareThreadSafe(j);

        var bothIn = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var bothWrote = new CountDownLatch(2);
        Runnable body = () -> {
            d.recordOnCompleteEnter(j, Thread.currentThread());
            bothIn.countDown();
            await(release);
            d.recordAccumulate(j, Thread.currentThread());
            bothWrote.countDown();
            await(bothWrote);
            d.recordOnCompleteExit(j, Thread.currentThread(), false);
        };
        Thread a = new Thread(body, "subtask-a");
        Thread b = new Thread(body, "subtask-b");
        a.start();
        b.start();
        assertTrue(bothIn.await(5, TimeUnit.SECONDS));
        release.countDown();
        a.join();
        b.join();

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void onTimeoutEnteredWhileSubtasksAreStillCompletingIsReported() {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        Thread owner = Thread.currentThread();
        d.recordJoinerBound(j, "orders", "scope-1", owner);

        d.recordOnCompleteEnter(j, owner);       // still in flight, never exits
        d.recordOnTimeout(j, owner);

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream()
                .anyMatch(v -> v.contains("onTimeout() while 1 onComplete call(s) were still running")));
    }

    /** onTimeout after every onComplete has returned reads a settled accumulator: no finding. */
    @Test
    void onTimeoutAfterCompletionsSettledStaysSilent() {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        Thread owner = Thread.currentThread();
        d.recordJoinerBound(j, "orders", "scope-1", owner);

        d.recordOnCompleteEnter(j, owner);
        d.recordOnCompleteExit(j, owner, false);
        d.recordOnTimeout(j, owner);

        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void resultCalledOffTheOwnerThreadIsReported() throws Exception {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        d.recordJoinerBound(j, "orders", "scope-1", Thread.currentThread());

        Thread other = new Thread(() -> d.recordResult(j, Thread.currentThread()), "stranger");
        other.start();
        other.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("result()@stranger")));
    }

    @Test
    void forkingAfterTheShortCircuitIsReported() {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        Thread owner = Thread.currentThread();
        d.recordJoinerBound(j, "orders", "scope-1", owner);

        d.recordOnCompleteEnter(j, owner);
        d.recordOnCompleteExit(j, owner, true);   // asked the scope to cancel
        d.recordFork(j, owner);
        d.recordFork(j, owner);

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.stream().anyMatch(v -> v.contains("2 fork(s) followed")));
        assertTrue(report.toString().contains("SCOPE JOINER MISUSE DETECTED"));
    }

    @Test
    void recordingIsIgnoredWhileDisabledAndResumesAfterEnable() {
        var d = new ScopeJoinerMisuseDetector();
        Object j = joiner();
        d.recordJoinerBound(j, "orders", "scope-1", Thread.currentThread());
        d.disable();
        d.recordJoinerBound(j, "orders", "scope-2", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());

        d.enable();
        d.recordJoinerBound(j, "orders", "scope-3", Thread.currentThread());
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void nullArgumentsAreIgnored() {
        var d = new ScopeJoinerMisuseDetector();
        d.recordJoinerBound(null, "x", "scope-1", Thread.currentThread());
        d.recordOnCompleteEnter(null, Thread.currentThread());
        d.recordAccumulate(new Object(), null);
        d.recordResult(new Object(), Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }
}
