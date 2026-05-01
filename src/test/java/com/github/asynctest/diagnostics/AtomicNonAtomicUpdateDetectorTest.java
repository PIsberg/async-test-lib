package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

public class AtomicNonAtomicUpdateDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new AtomicNonAtomicUpdateDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssuesWhenCasUsed() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger(0);
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.recordCas(counter, "counter", t); // CAS clears the pending get
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssuesWhenSetWithoutPriorGet() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger(0);
        d.recordSet(counter, "counter", Thread.currentThread()); // set without prior get — not a violation
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsGetThenSet() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger(0);
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.recordSet(counter, "counter", t); // non-atomic update
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("counter"));
    }

    @Test
    void testDetectsMultipleAtomics() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger ai = new AtomicInteger();
        AtomicLong    al = new AtomicLong();
        Thread t = Thread.currentThread();
        d.recordGet(ai, "ai", t);
        d.recordSet(ai, "ai", t);
        d.recordGet(al, "al", t);
        d.recordSet(al, "al", t);
        assertTrue(d.analyze().hasIssues());
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testCasAfterGetPreventsViolation() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger(0);
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.recordCas(counter, "counter", t); // successful CAS — clears pending get
        d.recordSet(counter, "counter", t); // subsequent set has no pending get to flag
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNullSafety() {
        var d = new AtomicNonAtomicUpdateDetector();
        assertDoesNotThrow(() -> {
            d.recordGet(null, "x", Thread.currentThread());
            d.recordSet(null, "x", Thread.currentThread());
            d.recordCas(null, "x", Thread.currentThread());
            AtomicInteger ai = new AtomicInteger();
            d.recordGet(ai, "x", null);
            d.recordSet(ai, "x", null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new AtomicNonAtomicUpdateDetector();
        AtomicInteger counter = new AtomicInteger();
        Thread t = Thread.currentThread();
        d.recordGet(counter, "counter", t);
        d.recordSet(counter, "counter", t);
        String s = d.analyze().toString();
        assertTrue(s.contains("ATOMIC NON-ATOMIC UPDATE"));
        assertTrue(s.contains("Fix"));
        assertTrue(s.contains("compareAndSet"));
    }
}
