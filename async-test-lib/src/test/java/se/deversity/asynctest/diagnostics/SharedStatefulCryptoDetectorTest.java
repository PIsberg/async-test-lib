package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.security.Signature;
import javax.crypto.Cipher;
import javax.crypto.Mac;

import static org.junit.jupiter.api.Assertions.*;

class SharedStatefulCryptoDetectorTest {

    @Test
    void cleanWhenNoAccess() {
        var d = new SharedStatefulCryptoDetector();
        assertFalse(d.analyze().hasIssues());
        assertTrue(d.analyze().toString().contains("clean"));
    }

    @Test
    void singleThreadAccessIsNotFlagged() throws Exception {
        var d = new SharedStatefulCryptoDetector();
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        for (int i = 0; i < 5; i++) {
            d.recordAccess(cipher, "sole-thread", Thread.currentThread());
        }
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void sharedCipherAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedStatefulCryptoDetector();
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        d.recordAccess(cipher, "payload-cipher", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(cipher, "payload-cipher", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        String msg = report.violations.get(0);
        assertTrue(msg.contains("payload-cipher"));
        assertTrue(msg.contains("2 threads"));
        assertTrue(msg.contains("Cipher"));
        assertTrue(msg.contains("observes sharing, not locks"));
        assertEquals(1, report.structuredViolations.size());
        assertEquals("SharedStatefulCrypto", report.structuredViolations.get(0).detector());
        assertEquals("Cipher", report.structuredViolations.get(0).attributes().get("kind"));
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
    }

    @Test
    void sharedMacAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedStatefulCryptoDetector();
        var mac = Mac.getInstance("HmacSHA256");
        d.recordAccess(mac, "hmac", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(mac, "hmac", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("Mac"));
        assertEquals("Mac", report.structuredViolations.get(0).attributes().get("kind"));
    }

    @Test
    void sharedSignatureAcrossThreadsIsFlagged() throws Exception {
        var d = new SharedStatefulCryptoDetector();
        var sig = Signature.getInstance("SHA256withRSA");
        d.recordAccess(sig, "jwt-signer", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(sig, "jwt-signer", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.violations.get(0).contains("Signature"));
        assertEquals("Signature", report.structuredViolations.get(0).attributes().get("kind"));
    }

    @Test
    void distinctInstancesAreTrackedSeparately() throws Exception {
        var d = new SharedStatefulCryptoDetector();
        var a = Cipher.getInstance("AES/CBC/PKCS5Padding");
        var b = Cipher.getInstance("AES/CBC/PKCS5Padding");
        d.recordAccess(a, "cipher-a", Thread.currentThread());
        d.recordAccess(b, "cipher-b", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(a, "cipher-a", Thread.currentThread()));
        t.start();
        t.join();
        var report = d.analyze();
        assertEquals(1, report.violations.size());
        assertTrue(report.violations.get(0).contains("cipher-a"));
    }

    @Test
    void reportEmbedsAlgorithm() throws Exception {
        var d = new SharedStatefulCryptoDetector();
        var mac = Mac.getInstance("HmacSHA256");
        d.recordAccess(mac, "labelled", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(mac, "labelled", Thread.currentThread()));
        t.start();
        t.join();
        String msg = d.analyze().violations.get(0);
        assertTrue(msg.contains("algorithm="), "Report must include the algorithm name");
    }

    @Test
    void nullsAreIgnored() throws Exception {
        var d = new SharedStatefulCryptoDetector();
        d.recordAccess((Cipher) null, "label", Thread.currentThread());
        d.recordAccess((Mac) null, "label", Thread.currentThread());
        d.recordAccess((Signature) null, "label", Thread.currentThread());
        d.recordAccess(Cipher.getInstance("AES/CBC/PKCS5Padding"), "label", null);
        assertFalse(d.analyze().hasIssues());
    }
}
