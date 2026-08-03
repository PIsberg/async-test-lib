package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SharedKdfDetector}.
 */
class SharedKdfDetectorTest {

    /** Stands in for a javax.crypto.KDF instance (JDK 24+ type, library targets 21). */
    private static final class FakeKdf { }

    private SharedKdfDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedKdfDetector();
    }

    // ---- Happy path ----

    @Test
    void noIssues_whenSingleThreadUsesKdf() {
        FakeKdf kdf = new FakeKdf();
        Thread t = Thread.currentThread();
        detector.recordAccess(kdf, "HKDF-SHA256", "deriveKey", t);
        detector.recordAccess(kdf, "HKDF-SHA256", "deriveData", t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Single-thread use is safe: " + report);
    }

    @Test
    void noIssues_whenEachThreadHasItsOwnInstance() throws Exception {
        Runnable r = () ->
                detector.recordAccess(new FakeKdf(), "HKDF-SHA256", "deriveKey", Thread.currentThread());
        Thread a = new Thread(r), b = new Thread(r);
        a.start(); b.start();
        a.join(); b.join();

        assertFalse(detector.analyze().hasIssues());
    }

    // ---- Shared instance ----

    @Test
    void detectsKdfSharedAcrossThreads() throws Exception {
        FakeKdf kdf = new FakeKdf();
        Runnable r = () ->
                detector.recordAccess(kdf, "HKDF-SHA256", "deriveKey", Thread.currentThread());
        Thread a = new Thread(r, "worker-a"), b = new Thread(r, "worker-b");
        a.start(); b.start();
        a.join(); b.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.violations.size());
        String v = report.violations.get(0);
        assertTrue(v.contains("HKDF-SHA256"), v);
        assertTrue(v.contains("2 threads"), v);
        assertTrue(v.contains("deriveKey"), v);
        assertTrue(v.contains("not thread-safe"), v);
        assertTrue(v.contains("observes sharing, not locks"), v);
    }

    @Test
    void structuredViolation_carriesAlgorithmAndSeverity() throws Exception {
        FakeKdf kdf = new FakeKdf();
        Runnable r = () ->
                detector.recordAccess(kdf, "PBKDF2WithHmacSHA256", "deriveKey", Thread.currentThread());
        Thread a = new Thread(r), b = new Thread(r);
        a.start(); b.start();
        a.join(); b.join();

        var report = detector.analyze();
        assertEquals(1, report.structuredViolations.size());
        var violation = report.structuredViolations.get(0);
        assertEquals("SharedKdf", violation.detector());
        assertEquals(IssueSeverity.HIGH, violation.severity());
        assertEquals("PBKDF2WithHmacSHA256", violation.attributes().get("algorithm"));
        assertEquals(2, violation.attributes().get("threadCount"));
    }

    @Test
    void oneViolationPerSharedInstance() throws Exception {
        FakeKdf kdf1 = new FakeKdf();
        FakeKdf kdf2 = new FakeKdf();
        Runnable r = () -> {
            detector.recordAccess(kdf1, "HKDF-SHA256", "deriveKey", Thread.currentThread());
            detector.recordAccess(kdf2, "HKDF-SHA512", "deriveData", Thread.currentThread());
        };
        Thread a = new Thread(r), b = new Thread(r);
        a.start(); b.start();
        a.join(); b.join();

        assertEquals(2, detector.analyze().violations.size());
    }

    // ---- Null handling ----

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.recordAccess(null, "HKDF-SHA256", "deriveKey", Thread.currentThread());
            detector.recordAccess(new FakeKdf(), null, null, Thread.currentThread());
            detector.recordAccess(new FakeKdf(), "HKDF-SHA256", "deriveKey", null);
        });
        assertFalse(detector.analyze().hasIssues());
    }

    @Test
    void nullAlgorithm_isReportedAsUnknown() throws Exception {
        FakeKdf kdf = new FakeKdf();
        Runnable r = () ->
                detector.recordAccess(kdf, null, "deriveKey", Thread.currentThread());
        Thread a = new Thread(r), b = new Thread(r);
        a.start(); b.start();
        a.join(); b.join();

        String v = detector.analyze().violations.get(0);
        assertTrue(v.contains("unknown"), v);
    }

    // ---- toString ----

    @Test
    void toString_isClean_whenNoIssues() {
        assertTrue(detector.analyze().toString().contains("clean"));
    }

    @Test
    void toString_containsFixGuidance_whenShared() throws Exception {
        FakeKdf kdf = new FakeKdf();
        Runnable r = () ->
                detector.recordAccess(kdf, "HKDF-SHA256", "deriveKey", Thread.currentThread());
        Thread a = new Thread(r), b = new Thread(r);
        a.start(); b.start();
        a.join(); b.join();

        String s = detector.analyze().toString();
        assertTrue(s.contains("SHARED KDF DETECTED"), s);
        assertTrue(s.contains("Fix:"), s);
        assertTrue(s.contains("ThreadLocal"), s);
    }
}
