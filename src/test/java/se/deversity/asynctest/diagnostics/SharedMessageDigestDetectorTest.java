package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import java.security.Signature;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;

import static org.junit.jupiter.api.Assertions.*;

public class SharedMessageDigestDetectorTest {

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static Cipher cipher() {
        try {
            return Cipher.getInstance("AES/CBC/PKCS5Padding");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Mac mac() {
        try {
            return Mac.getInstance("HmacSHA256");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Signature signature() {
        try {
            return Signature.getInstance("SHA256withRSA");
        } catch (Exception e) {
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
        assertTrue(s.contains("Why"));
    }

    @Test
    void testDetectsSharedCipher() throws Exception {
        var d = new SharedMessageDigestDetector();
        Cipher c = cipher();
        d.recordAccess(c, "aes-cipher", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(c, "aes-cipher", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("aes-cipher"));
        assertTrue(d.analyze().violations.get(0).contains("Cipher"));
    }

    @Test
    void testDetectsSharedMac() throws Exception {
        var d = new SharedMessageDigestDetector();
        Mac m = mac();
        d.recordAccess(m, "hmac-sha256", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(m, "hmac-sha256", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("hmac-sha256"));
        assertTrue(d.analyze().violations.get(0).contains("Mac"));
    }

    @Test
    void testDetectsSharedSignature() throws Exception {
        var d = new SharedMessageDigestDetector();
        Signature s = signature();
        d.recordAccess(s, "sha256-rsa", Thread.currentThread());
        Thread t2 = new Thread(() -> d.recordAccess(s, "sha256-rsa", Thread.currentThread()));
        t2.start();
        t2.join();
        assertTrue(d.analyze().hasIssues());
        assertTrue(d.analyze().violations.get(0).contains("sha256-rsa"));
        assertTrue(d.analyze().violations.get(0).contains("Signature"));
    }

    @Test
    void testReportToStringContainsAllJcaFixHints() throws Exception {
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        Cipher c = cipher();
        Mac m = mac();
        Signature sig = signature();

        d.recordAccess(md, "md", Thread.currentThread());
        d.recordAccess(c, "c", Thread.currentThread());
        d.recordAccess(m, "m", Thread.currentThread());
        d.recordAccess(sig, "sig", Thread.currentThread());

        Thread t2 = new Thread(() -> {
            d.recordAccess(md, "md", Thread.currentThread());
            d.recordAccess(c, "c", Thread.currentThread());
            d.recordAccess(m, "m", Thread.currentThread());
            d.recordAccess(sig, "sig", Thread.currentThread());
        });
        t2.start();
        t2.join();

        String s = d.analyze().toString();
        assertTrue(s.contains("SHARED MESSAGE DIGEST"));
        assertTrue(s.contains("[MessageDigest]"));
        assertTrue(s.contains("[Cipher]"));
        assertTrue(s.contains("[Mac]"));
        assertTrue(s.contains("[Signature]"));
    }

    @Test
    void testSharedCryptographyDetectorAlias() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectSharedMessageDigest(true).build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        AsyncTestContext.install(ctx);
        try {
            var d = AsyncTestContext.sharedCryptographyDetector();
            assertNotNull(d);
        } finally {
            AsyncTestContext.uninstall();
        }
    }
}
