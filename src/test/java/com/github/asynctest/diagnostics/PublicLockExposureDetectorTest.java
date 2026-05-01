package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PublicLockExposureDetectorTest {

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new PublicLockExposureDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueSynchronizedOnPrivateObject() {
        var d = new PublicLockExposureDetector();
        Object lock = new Object();
        d.recordSynchronizedOnThis(lock, Thread.currentThread(), "MyService");
        // lock is never published
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssuePublishedObjectNotUsedAsLock() {
        var d = new PublicLockExposureDetector();
        Object obj = new Object();
        d.recordObjectPublished(obj, "getService()");
        // obj is never used as a lock
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsPublishedLock() {
        var d = new PublicLockExposureDetector();
        Object service = new Object();
        d.recordSynchronizedOnThis(service, Thread.currentThread(), "MyService");
        d.recordObjectPublished(service, "returned from getService()");
        assertTrue(d.analyze().hasIssues());
        String msg = d.analyze().violations.get(0);
        assertTrue(msg.contains("MyService"));
        assertTrue(msg.contains("getService()"));
    }

    @Test
    void testDetectsMultiplePublishedLocks() {
        var d = new PublicLockExposureDetector();
        Object s1 = new Object();
        Object s2 = new Object();
        d.recordSynchronizedOnThis(s1, Thread.currentThread(), "ServiceA");
        d.recordSynchronizedOnThis(s2, Thread.currentThread(), "ServiceB");
        d.recordObjectPublished(s1, "field serviceA");
        d.recordObjectPublished(s2, "field serviceB");
        assertEquals(2, d.analyze().violations.size());
    }

    @Test
    void testNullSafety() {
        var d = new PublicLockExposureDetector();
        assertDoesNotThrow(() -> {
            d.recordSynchronizedOnThis(null, Thread.currentThread(), "X");
            d.recordObjectPublished(null, "X");
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() {
        var d = new PublicLockExposureDetector();
        Object obj = new Object();
        d.recordSynchronizedOnThis(obj, Thread.currentThread(), "Svc");
        d.recordObjectPublished(obj, "public field");
        String s = d.analyze().toString();
        assertTrue(s.contains("PUBLIC LOCK EXPOSURE"));
        assertTrue(s.contains("Fix"));
        assertTrue(s.contains("private final Object lock"));
    }
}
