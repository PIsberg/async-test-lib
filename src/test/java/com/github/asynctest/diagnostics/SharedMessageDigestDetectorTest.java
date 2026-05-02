package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import static org.junit.jupiter.api.Assertions.*;

public class SharedMessageDigestDetectorTest {

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testNoIssuesWhenEmpty() {
        var d = new SharedMessageDigestDetector();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testNoIssueWhenSingleThread() {
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        d.recordAccess(md, "sha256", Thread.currentThread());
        d.recordAccess(md, "sha256", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testDetectsSharedDigest() throws Exception {
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        d.recordAccess(md, "sha256", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(md, "sha256", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("sha256"));
        assertTrue(d.analyze().violations.get(0).contains("2"));
    }

    @Test
    void testSeparateDigestPerThreadNoIssue() throws Exception {
        var d = new SharedMessageDigestDetector();
        MessageDigest md1 = sha256();
        MessageDigest md2 = sha256();
        d.recordAccess(md1, "md1", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(md2, "md2", Thread.currentThread()));
        t2.start();
        t2.join();
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testAutoLabelFromClassName() throws Exception {
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        d.recordAccess(md, null, Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(md, null, Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("SHA-256") ||
                   d.analyze().violations.get(0).contains("MessageDigest"));
    }

    @Test
    void testNullSafety() {
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        assertDoesNotThrow(() -> {
            d.recordAccess(null, "x", Thread.currentThread());
            d.recordAccess(md, "x", null);
        });
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void testReportToStringContainsFixHint() throws Exception {
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        d.recordAccess(md, "md", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(md, "md", Thread.currentThread()));
        t2.start();
        t2.join();
        String s = d.analyze().toString();
        assertTrue(s.contains("SHARED MESSAGE DIGEST"));
        assertTrue(s.contains("Fix"));
    }
}
