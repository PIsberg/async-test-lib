package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SharedSecureRandomDetector;
import se.deversity.asynctest.example.service.SessionTokenService;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for SessionTokenService.
 *
 * ========================================================================
 * DETECTOR: SharedSecureRandomDetector
 *           (DetectorType.SHARED_SECURE_RANDOM)
 * ========================================================================
 *
 * SecureRandom javadoc: "A SecureRandom object is thread-safe if the
 * underlying implementation is thread-safe. [...] Applications are
 * encouraged to use a separate SecureRandom instance per thread."
 *
 * That is a conditional guarantee, not a guarantee. The bundled SUN
 * providers synchronize, so a shared instance on a stock JVM is correct
 * and merely slow — every thread serialising on one lock at exactly the
 * moment every request needs a token. Swap in an HSM-backed, PKCS#11 or
 * FIPS provider and thread safety is theirs to promise. One that does not
 * synchronize can hand two concurrent requests the same bytes, which for a
 * session token means two users sharing a session.
 *
 * THE BUG:
 *   - one SecureRandom field behind every token the process issues
 *
 * THE FIX:
 *   - ThreadLocal.withInitial(SecureRandom::new). Faster where sharing was
 *     safe, correct where it was not — there is no trade to weigh.
 *
 * The report names the algorithm and provider, so a finding tells you
 * which implementation you are actually relying on.
 */
class SessionTokenServiceTest {

    private SharedSecureRandomDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedSecureRandomDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: an instance per thread. Nothing shared, nothing to report.
    // -----------------------------------------------------------------------

    @Test
    void instancePerThread_isClean() throws Exception {
        var service = new SessionTokenService();
        var tokens = Collections.synchronizedList(new ArrayList<String>());

        Runnable worker = () -> {
            SecureRandom own = new SecureRandom();          // this thread's own instance
            detector.recordAccess(own, "session-token-rng", Thread.currentThread());
            tokens.add(service.mintTokenSafely());
        };
        Thread a = new Thread(worker, "request-a");
        Thread b = new Thread(worker, "request-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
        assertEquals(2, new HashSet<>(tokens).size(), "distinct tokens");
    }

    // -----------------------------------------------------------------------
    // Part 2: one instance, two threads — flagged, with the algorithm and
    // provider named so you know what you are relying on.
    // -----------------------------------------------------------------------

    @Test
    void sharedSecureRandomAcrossThreads_isDetected() throws Exception {
        var service = new SessionTokenService();
        SecureRandom shared = service.sharedRandom();

        Runnable worker = () -> {
            detector.recordAccess(shared, "session-token-rng", Thread.currentThread());
            service.mintToken();
        };
        Thread a = new Thread(worker, "request-a");
        Thread b = new Thread(worker, "request-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected shared-SecureRandom violation:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("session-token-rng"), violation);
        assertTrue(violation.contains("2 threads"), violation);
        assertTrue(violation.contains("request-a"), violation);
        assertTrue(violation.contains("algorithm="), violation);
        assertTrue(violation.contains("provider="), violation);
    }

    // -----------------------------------------------------------------------
    // Part 3: why it matters — a token is only a credential while it is
    // unique. On a stock JVM the shared instance still produces distinct
    // tokens; the finding is about the provider you cannot see from here.
    // -----------------------------------------------------------------------

    @Test
    void tokensMustBeUnique_underConcurrentLoad() throws Exception {
        var service = new SessionTokenService();
        var tokens = Collections.synchronizedList(new ArrayList<String>());

        Runnable worker = () -> {
            for (int i = 0; i < 200; i++) {
                tokens.add(service.mintTokenSafely());
            }
        };
        List<Thread> threads = List.of(
                new Thread(worker, "minter-a"),
                new Thread(worker, "minter-b"),
                new Thread(worker, "minter-c"));
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        Set<String> unique = new HashSet<>(tokens);
        assertEquals(600, tokens.size());
        assertEquals(tokens.size(), unique.size(),
                "a duplicate session token means two users share a session");
    }

}
