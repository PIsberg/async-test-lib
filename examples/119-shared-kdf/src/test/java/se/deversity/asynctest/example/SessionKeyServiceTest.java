package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.SharedKdfDetector;
import se.deversity.asynctest.example.service.SessionKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for SessionKeyService.
 *
 * ========================================================================
 * DETECTOR: SharedKdfDetector  (JEP 510 — Key Derivation Function API, JDK 25)
 * ========================================================================
 *
 * The javax.crypto.KDF javadoc: "Unless otherwise documented by an
 * implementation, the methods defined in this class are not thread-safe."
 * Concurrent deriveKey()/deriveData() calls on one shared instance can
 * interleave provider state and silently derive wrong keys — no exception,
 * just a key that fails to match the peer's.
 *
 * THE BUG:
 *   - one KDF instance stored in a field and used by every request thread
 *
 * THE FIX:
 *   - one KDF instance per thread (KDF.getInstance is cheap; ThreadLocal works),
 *     or external synchronization around every derive call.
 */
class SessionKeyServiceTest {

    private static final byte[] MASTER = "master-secret".getBytes(StandardCharsets.UTF_8);

    private SharedKdfDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedKdfDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: per-call derivation object, single-thread use. No misuse.
    // -----------------------------------------------------------------------

    @Test
    void perCallDerivation_isClean() throws Exception {
        var service = new SessionKeyService(MASTER);
        var kdf = new Object();   // models this thread's own KDF instance

        detector.recordAccess(kdf, "HKDF-SHA256", "deriveData", Thread.currentThread());
        byte[] key = service.deriveSessionKeySafely("session-1");

        assertEquals(32, key.length);
        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: one shared derivation object used from many threads — flagged.
    // -----------------------------------------------------------------------

    @Test
    void sharedKdfInstanceAcrossThreads_isDetected() throws Exception {
        var service = new SessionKeyService(MASTER);
        var sharedKdf = new Object();   // models the single KDF field every thread shares

        Runnable worker = () -> {
            detector.recordAccess(sharedKdf, "HKDF-SHA256", "deriveData", Thread.currentThread());
            service.deriveSessionKey("session-" + Thread.currentThread().getName());
        };
        Thread a = new Thread(worker, "request-a");
        Thread b = new Thread(worker, "request-b");
        a.start(); b.start();
        a.join(); b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected shared-KDF violation:\n" + report);
        String v = report.violations.get(0);
        assertTrue(v.contains("HKDF-SHA256"), v);
        assertTrue(v.contains("2 threads"), v);
        assertTrue(report.toString().contains("Fix:"));
    }

    // -----------------------------------------------------------------------
    // Part 3: the corruption itself — interleaved derivations produce a key
    // that matches neither thread's expected key.
    // -----------------------------------------------------------------------

    @Test
    void interleavedDerivations_produceWrongKeys() throws Exception {
        var service = new SessionKeyService(MASTER);
        byte[] expected1 = service.deriveSessionKeySafely("session-1");
        byte[] expected2 = service.deriveSessionKeySafely("session-2");

        // Simulate the interleaving a race produces on the SHARED digest:
        // thread 1 resets+updates master, thread 2's context lands in between.
        byte[] corrupted;
        synchronized (service) {
            // reset → update(master) → update("session-2") → update("session-1") → digest
            service.deriveSessionKey("session-2");            // leaves state clean...
            corrupted = deriveInterleaved(service);
        }

        assertFalse(java.util.Arrays.equals(corrupted, expected1),
                "interleaved derivation must not match session-1's key");
        assertFalse(java.util.Arrays.equals(corrupted, expected2),
                "interleaved derivation must not match session-2's key");
    }

    /** Two logical derivations interleaved on the one shared digest. */
    private static byte[] deriveInterleaved(SessionKeyService service) throws Exception {
        var digestField = SessionKeyService.class.getDeclaredField("sharedDigest");
        digestField.setAccessible(true);
        var digest = (java.security.MessageDigest) digestField.get(service);
        digest.reset();
        digest.update(MASTER);
        digest.update("session-2".getBytes(StandardCharsets.UTF_8));  // intruder bytes
        digest.update("session-1".getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }
}
