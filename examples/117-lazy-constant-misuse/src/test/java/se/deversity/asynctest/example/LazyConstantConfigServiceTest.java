package se.deversity.asynctest.example;

import se.deversity.asynctest.diagnostics.LazyConstantMisuseDetector;
import se.deversity.asynctest.example.service.LazyConstantConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for LazyConstantConfigService.
 *
 * ========================================================================
 * DETECTOR: LazyConstantMisuseDetector  (JDK 26 — Lazy Constants, 2nd preview)
 * ========================================================================
 *
 * LazyConstant.of(supplier) computes at most once on first get(); the JDK 25
 * StableValue low-level methods (trySet/setOrThrow/orElseSet) were removed and
 * null values now throw NullPointerException.
 *
 * THE BUGS:
 *   - a supplier that returns null → NullPointerException on JDK 26
 *   - a hand-rolled lazy holder whose supplier runs more than once (check-then-act race)
 *   - a supplier that re-enters the same constant → IllegalStateException / recursion
 *
 * THE FIX:
 *   - LazyConstant.of(() -> loadConfig()) with a pure, non-null, deterministic supplier.
 */
class LazyConstantConfigServiceTest {

    private LazyConstantMisuseDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LazyConstantMisuseDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: correct usage — computed once, then read. No misuse.
    // -----------------------------------------------------------------------

    @Test
    void correctUsage_computeOnceThenRead_isClean() {
        var service = new LazyConstantConfigService(() -> "db-url=localhost");
        Thread t = Thread.currentThread();

        detector.recordGet("CONFIG", t);
        detector.recordComputeStart("CONFIG", t);
        String value = service.get();
        detector.recordComputeEnd("CONFIG", t, value);

        detector.recordGet("CONFIG", t);
        assertEquals("db-url=localhost", service.get());
        assertEquals(1, service.supplierRunCount(), "supplier ran exactly once");

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Expected clean usage:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: null-producing supplier — NPE on JDK 26, caught by the detector.
    // -----------------------------------------------------------------------

    @Test
    void nullProducingSupplier_isDetected() {
        var service = new LazyConstantConfigService(() -> null);   // BUG
        Thread t = Thread.currentThread();

        detector.recordComputeStart("CONFIG", t);
        assertThrows(NullPointerException.class, service::get);
        detector.recordComputeEnd("CONFIG", t, null);

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getNullValueIssues().isEmpty());
        assertTrue(report.getNullValueIssues().get(0).contains("NullPointerException"));
    }

    // -----------------------------------------------------------------------
    // Part 3: hand-rolled lazy holder — supplier races and runs more than once.
    // -----------------------------------------------------------------------

    @Test
    void racyHandRolledHolder_supplierRunsMoreThanOnce_isDetected() throws Exception {
        var bothEntered = new CountDownLatch(2);   // both threads are inside the supplier
        var release     = new CountDownLatch(1);
        var service = new LazyConstantConfigService(() -> {
            bothEntered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "computed-by-" + Thread.currentThread().getName();
        });

        Runnable racer = () -> {
            Thread self = Thread.currentThread();
            detector.recordComputeStart("CONFIG", self);
            String v = service.getRacy();                    // BUG: check-then-act race
            detector.recordComputeEnd("CONFIG", self, v);
        };
        Thread a = new Thread(racer, "racer-a");
        Thread b = new Thread(racer, "racer-b");
        a.start(); b.start();
        bothEntered.await();                                 // both suppliers running — race is locked in
        release.countDown();
        a.join(); b.join();

        assertEquals(2, service.supplierRunCount(), "both threads ran the supplier");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected misuse:\n" + report);
        assertFalse(report.getMultipleComputeIssues().isEmpty(),
                "at-most-once contract broken should be flagged");
        assertFalse(report.getNonDeterministicIssues().isEmpty(),
                "the two runs produced different values — non-deterministic supplier");
    }

    // -----------------------------------------------------------------------
    // Part 4: reentrant supplier — reads the constant it is computing.
    // -----------------------------------------------------------------------

    @Test
    void reentrantSupplier_isDetected() {
        Thread t = Thread.currentThread();

        detector.recordComputeStart("CONFIG", t);
        detector.recordComputeStart("CONFIG", t);   // BUG: supplier re-entered itself

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertFalse(report.getReentrantIssues().isEmpty());
        assertTrue(report.getReentrantIssues().get(0).contains("IllegalStateException"));
    }
}
