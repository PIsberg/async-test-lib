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
        assertTrue(d.analyze().violations.get(0).contains("never declared is not observed"));
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

    @Test
    void testRepeatedAccessSameInstanceProducesSingleViolation() throws Exception {
        // Exercises the hot-path lookup short-circuit: repeated recordAccess on
        // the same instance must not double-count or duplicate violations.
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        // Many repeated calls on main thread (fast-path).
        for (int i = 0; i < 1_000; i++) {
            d.recordAccess(md, "sha-hot", Thread.currentThread());
        }
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 1_000; i++) {
                d.recordAccess(md, "sha-hot", Thread.currentThread());
            }
        });
        t2.start();
        t2.join();

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.violations.size(), "Repeated access must not duplicate violations");
        assertTrue(report.violations.get(0).contains("sha-hot"));
        assertTrue(report.violations.get(0).contains("2 threads"));
    }

    @Test
    void testReportEmitsStructuredViolation() throws Exception {
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        d.recordAccess(md, "sha-struct", Thread.currentThread());
        Thread t = new Thread(() -> d.recordAccess(md, "sha-struct", Thread.currentThread()));
        t.start();
        t.join();

        var report = d.analyze();
        assertEquals(1, report.structuredViolations.size());
        var v = report.structuredViolations.get(0);
        assertEquals("SharedMessageDigest", v.detector());
        assertEquals(se.deversity.asynctest.diagnostics.IssueSeverity.HIGH, v.severity());
        assertTrue(v.message().contains("sha-struct"));
        assertEquals("MessageDigest", v.attributes().get("type"));
        assertEquals(2, v.attributes().get("threads"));
        assertNotNull(v.when());
    }

    @Test
    void testReportIncludesSourceLineAttribution() throws Exception {
        // Two distinct call sites on the same instance from two threads. The
        // captured Set<Site> should dedupe by (class, line) and surface both
        // sites in the toString().
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        d.recordAccess(md, "sha-attr", Thread.currentThread()); // site A
        Thread t = new Thread(() -> d.recordAccess(md, "sha-attr", Thread.currentThread())); // site B
        t.start();
        t.join();

        String msg = d.analyze().violations.get(0);
        // The report mentions "Access sites:" once the attribution block fires.
        assertTrue(msg.contains("Access sites:"),
                "Violation must include source-line attribution; got: " + msg);
        assertTrue(msg.contains("SharedMessageDigestDetectorTest"),
                "Site should name the user-code test class, not framework internals: " + msg);
    }

    @Test
    void testLabelFallbackOnlyEvaluatedOnFirstAccess() {
        // When name is null on first call, the fallback label is captured.
        // Subsequent calls with a different name MUST NOT mutate the stored label
        // (label is fixed at first registration).
        var d = new SharedMessageDigestDetector();
        MessageDigest md = sha256();
        d.recordAccess(md, null, Thread.currentThread());
        d.recordAccess(md, "renamed", new Thread("worker-x"));
        // Trigger the violation (need >1 thread)
        Thread t = new Thread(() -> d.recordAccess(md, "ignored", Thread.currentThread()));
        t.start();
        try { t.join(); } catch (InterruptedException ignored) {}

        String msg = d.analyze().violations.get(0);
        // First-access fallback label has form "MessageDigest$Delegate@<hash>" or similar
        assertFalse(msg.contains("renamed"), "Label captured on first access must be sticky");
    }

    // ---- Sharing is judged within one invocation round ----------------------------------------
    //
    // The runner orders rounds: the previous round's workers have all finished before the next
    // round's are submitted. Two threads that each used an instance in a different round never
    // overlapped, and with virtual threads (the default) every body execution is a fresh thread,
    // so counting threads across the whole run reported a digest no two threads ever held at
    // once. These drive the detector through an installed context, the way a run does.

    private static AsyncTestContext digestContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectSharedMessageDigest(true).build());
    }

    private static SharedMessageDigestDetector detectorOf(AsyncTestContext ctx) {
        AsyncTestContext.install(ctx);
        try {
            return AsyncTestContext.sharedMessageDigestDetector();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** Starts one worker per body, each with {@code ctx} installed, and waits for all of them. */
    private static void runWorkers(AsyncTestContext ctx, Runnable... bodies) throws InterruptedException {
        Thread[] workers = new Thread[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            Runnable body = bodies[i];
            workers[i] = new Thread(() -> {
                AsyncTestContext.install(ctx);
                try {
                    body.run();
                } finally {
                    AsyncTestContext.uninstall();
                }
            }, "worker-" + i);
        }
        for (Thread worker : workers) {
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
    }

    private static void use(MessageDigest md) {
        AsyncTestContext.sharedMessageDigestDetector().recordAccess(md, "sha256", Thread.currentThread());
        md.update((byte) 1);
    }

    private static Runnable together(java.util.concurrent.CyclicBarrier barrier, Runnable body) {
        return () -> {
            try {
                barrier.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            body.run();
        };
    }

    @Test
    void uninstallUnbindsTheSharingScope() {
        // The symmetry rule: a scope left bound would file this thread's next records under the
        // round clock of a run that has finished.
        AsyncTestContext ctx = digestContext();
        assertNull(SelfGuard.Scope.current(), "no scope outside a body execution");
        AsyncTestContext.install(ctx, 0);
        try {
            assertNotNull(SelfGuard.Scope.current());
        } finally {
            AsyncTestContext.uninstall();
        }
        assertNull(SelfGuard.Scope.current(), "uninstall() must unbind the scope");
    }

    @Test
    void oneThreadPerRoundOnAFreshThreadEachRoundIsNotSharing() throws Exception {
        AsyncTestContext ctx = digestContext();
        MessageDigest md = sha256();
        for (int round = 0; round < 3; round++) {
            ctx.markInvocationStart();
            runWorkers(ctx, () -> use(md));
        }

        var report = detectorOf(ctx).analyze();
        assertFalse(report.hasIssues(),
                "three rounds, one thread each, ordered by the runner: nothing overlapped; got "
                        + report.violations);
    }

    @Test
    void twoThreadsInOneRoundWithoutALockStillFire() throws Exception {
        AsyncTestContext ctx = digestContext();
        MessageDigest md = sha256();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        ctx.markInvocationStart();
        runWorkers(ctx, () -> use(md));
        ctx.markInvocationStart();
        runWorkers(ctx, together(barrier, () -> use(md)), together(barrier, () -> use(md)));

        var report = detectorOf(ctx).analyze();
        assertTrue(report.hasIssues(), "two threads used one digest in the same round, unguarded");
        assertTrue(report.violations.get(0).contains("MessageDigest is not thread-safe"),
                report.violations.get(0));
    }

    @Test
    void aDifferentLockInEachRoundIsNotInconsistentLocking() throws Exception {
        // Round one guards every access with one lock, round two with another. Within each round
        // the guarding is consistent, and nothing crosses the round boundary.
        AsyncTestContext ctx = digestContext();
        MessageDigest md = sha256();
        for (Object lock : new Object[] {new Object(), new Object()}) {
            ctx.markInvocationStart();
            Runnable guarded = () -> {
                synchronized (lock) {
                    try (var held = AsyncTestContext.holdingLock(lock)) {
                        use(md);
                    }
                }
            };
            runWorkers(ctx, guarded, guarded);
        }

        var report = detectorOf(ctx).analyze();
        assertFalse(report.hasIssues(), "each round was consistently locked; got " + report.violations);
    }
}
