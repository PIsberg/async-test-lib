package com.github.asynctest.diagnostics;

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
