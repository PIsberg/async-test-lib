package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class StatefulLambdaDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new StatefulLambdaDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenExecutedOnSingleThread() {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable r = () -> counter[0]++;
        d.recordExecution(r, "r", Thread.currentThread());
        d.recordCapturedMutation(r, "counter", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenMultiThreadButNoMutation() throws Exception {
        var d = new StatefulLambdaDetector();
        Runnable r = () -> {};
        d.recordExecution(r, "r", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordExecution(r, "r", Thread.currentThread()));
        t2.start();
        t2.join();
        // multi-thread execution with no captured mutation — not an issue
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsMutationFromMultipleThreads() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable r = () -> counter[0]++;
        d.recordExecution(r, "task", Thread.currentThread());
        d.recordCapturedMutation(r, "counter", Thread.currentThread());
        Thread t2 = new Thread(() -> {
            d.recordExecution(r, "task", Thread.currentThread());
            d.recordCapturedMutation(r, "counter", Thread.currentThread());
        });
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("task"));
        assertTrue(d.analyze().violations.get(0).contains("counter"));
    }

    @Test
    void lambdaBumpingACapturedLongAdderIsNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        java.util.concurrent.atomic.LongAdder hits = new java.util.concurrent.atomic.LongAdder();
        java.util.concurrent.atomic.AtomicLong total = new java.util.concurrent.atomic.AtomicLong();
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            hits.increment();
            d.recordCapturedMutation(task[0], "hits", hits, Thread.currentThread());
            total.addAndGet(2);
            d.recordCapturedMutation(task[0], "total", total, Thread.currentThread());
        };
        onTwoThreads(task[0]);

        assertFalse(d.analyze().hasIssues(),
                "LongAdder and AtomicLong are the thread-safe state the report's own Fix section "
                        + "recommends; a lambda sharing them across threads is correct code: "
                        + d.analyze().violations);
    }

    @Test
    void lambdaMutatingItsCaptureUnderADeclaredLockIsNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Object lock = new Object();
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            try (var held = se.deversity.asynctest.AsyncTestContext.holdingLock(lock)) {
                synchronized (lock) {
                    counter[0]++;
                    d.recordCapturedMutation(task[0], "counter", Thread.currentThread());
                }
            }
        };
        onTwoThreads(task[0]);

        assertFalse(d.analyze().hasIssues(),
                "every mutation of the capture held one lock: " + d.analyze().violations);
    }

    @Test
    void lambdaMutatingACapturedArrayUnderItsOwnMonitorIsNotReported() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            synchronized (counter) {
                counter[0]++;
                d.recordCapturedMutation(task[0], "counter", counter, Thread.currentThread());
            }
        };
        onTwoThreads(task[0]);

        assertFalse(d.analyze().hasIssues(), "synchronized (counter) guarded every mutation");
    }

    @Test
    void lambdaMutatingACapturedArrayWithNoLockIsReportedThroughTheStateOverload() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] counter = {0};
        Runnable[] task = new Runnable[1];
        task[0] = () -> {
            d.recordExecution(task[0], "task", Thread.currentThread());
            counter[0]++;
            d.recordCapturedMutation(task[0], "counter", counter, Thread.currentThread());
        };
        onTwoThreads(task[0]);

        assertTrue(d.analyze().hasIssues(), "an int[] is not thread-safe state");
        assertTrue(d.analyze().violations.get(0).contains("counter"));
    }

    private static void onTwoThreads(Runnable body) throws InterruptedException {
        Thread a = new Thread(body);
        Thread b = new Thread(body);
        a.start();
        b.start();
        a.join();
        b.join();
    }

    @Test
    void testSeparateLambdaInstancesNoIssue() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] c1 = {0};
        int[] c2 = {0};
        Runnable r1 = () -> c1[0]++;
        Runnable r2 = () -> c2[0]++;
        d.recordExecution(r1, "r1", Thread.currentThread());
        d.recordCapturedMutation(r1, "c1", Thread.currentThread());
        Thread t2 = new Thread(() -> {
            d.recordExecution(r2, "r2", Thread.currentThread());
            d.recordCapturedMutation(r2, "c2", Thread.currentThread());
        });
        t2.start();
        t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new StatefulLambdaDetector();
        Runnable r = () -> {};
        assertDoesNotThrow(() -> {
            d.recordExecution(null, "x", Thread.currentThread());
            d.recordExecution(r, "x", null);
            d.recordCapturedMutation(null, "x", Thread.currentThread());
            d.recordCapturedMutation(r, "x", null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new StatefulLambdaDetector();
        int[] c = {0};
        Runnable r = () -> c[0]++;
        d.recordExecution(r, "r", Thread.currentThread());
        d.recordCapturedMutation(r, "c", Thread.currentThread());
        Thread t2 = new Thread(() -> {
            d.recordExecution(r, "r", Thread.currentThread());
            d.recordCapturedMutation(r, "c", Thread.currentThread());
        });
        t2.start();
        t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("STATEFUL LAMBDA"));
        assertTrue(s.contains("Fix"));
    }
}
