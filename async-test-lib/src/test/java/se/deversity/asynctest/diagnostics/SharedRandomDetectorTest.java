package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SharedRandomDetector.
 */
public class SharedRandomDetectorTest {

    @Test
    void testSingleThreadRandomUsage() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        detector.registerRandom(random, "single-thread-random");
        
        for (int i = 0; i < 10; i++) {
            random.nextInt();
            detector.recordRandomAccess(random, "single-thread-random", "nextInt");
        }
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.hasIssues(), "Single thread usage should not report issues");
    }

    @Test
    void testSharedRandomDetection() throws InterruptedException {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        detector.registerRandom(random, "shared-random");
        
        // Simulate multiple threads accessing the same Random
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                random.nextInt();
                detector.recordRandomAccess(random, "shared-random", "nextInt");
            }
        });
        
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                random.nextInt();
                detector.recordRandomAccess(random, "shared-random", "nextInt");
            }
        });
        
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect shared random access");
        assertFalse(report.sharedRandoms.isEmpty(), "Should report shared randoms");
        assertTrue(report.sharedRandoms.get(0).contains("observes sharing, not locks"));
    }

    @Test
    void testMultipleMethodsTracking() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();

        detector.registerRandom(random, "multi-method-random");

        random.nextInt();
        detector.recordRandomAccess(random, "multi-method-random", "nextInt");

        random.nextLong();
        detector.recordRandomAccess(random, "multi-method-random", "nextLong");

        random.nextDouble();
        detector.recordRandomAccess(random, "multi-method-random", "nextDouble");

        SharedRandomDetector.SharedRandomReport report = detector.analyze();

        assertNotNull(report);
        // Single thread, so no issues expected
        assertFalse(report.hasIssues(), "Single thread should not report issues");
        assertTrue(report.randomActivity.containsKey("multi-method-random"), "Should track activity");
    }

    @Test
    void testAutoRegistration() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        // Record without explicit registration - should auto-register using the given name
        detector.recordRandomAccess(random, "auto-registered", "nextInt");
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        assertNotNull(report);
        assertFalse(report.randomActivity.isEmpty(), "Should track auto-registered random");
        assertTrue(report.randomActivity.containsKey("auto-registered"),
            "Auto-registered state should be keyed by the provided name, not a fallback identity name");
    }

    @Test
    void testAutoRegistrationFallsBackToIdentityNameWhenNameIsNull() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();

        // Record without explicit registration and without a name - should fall back to "random@<identity>"
        detector.recordRandomAccess(random, null, "nextInt");

        SharedRandomDetector.SharedRandomReport report = detector.analyze();

        assertNotNull(report);
        String expectedFallbackName = "random@" + System.identityHashCode(random);
        assertTrue(report.randomActivity.containsKey(expectedFallbackName),
            "Auto-registered state with a null name should fall back to the identity-based name");
    }

    @Test
    void testExplicitRegistrationTakesPrecedenceOverAutoRegister() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();

        detector.registerRandom(random, "registered-name");
        // Pass a different name here - since the random is already registered, this name must be ignored
        detector.recordRandomAccess(random, "ignored-name", "nextInt");

        SharedRandomDetector.SharedRandomReport report = detector.analyze();

        assertTrue(report.randomActivity.containsKey("registered-name"),
            "Explicit registerRandom() must actually register the random instance");
        assertFalse(report.randomActivity.containsKey("ignored-name"),
            "recordRandomAccess() must not re-register an already-registered random under a new name");
    }

    @Test
    void testRegisteredButNeverAccessedIsNotReportedAsActive() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();

        detector.registerRandom(random, "never-accessed");
        // Intentionally do not call recordRandomAccess

        SharedRandomDetector.SharedRandomReport report = detector.analyze();

        assertNotNull(report);
        assertFalse(report.randomActivity.containsKey("never-accessed"),
            "A registered random with zero accesses must not appear in random activity");
    }

    @Test
    void testMethodBreakdownFormatting() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();

        detector.registerRandom(random, "multi-format-random");

        // Two threads so the shared-access path (and method breakdown) is populated
        Thread t1 = new Thread(() -> detector.recordRandomAccess(random, "multi-format-random", "nextInt"));
        Thread t2 = new Thread(() -> detector.recordRandomAccess(random, "multi-format-random", "nextLong"));

        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        SharedRandomDetector.SharedRandomReport report = detector.analyze();

        String breakdown = report.methodBreakdown.get("multi-format-random");
        assertNotNull(breakdown);
        String[] parts = breakdown.split(", ");
        assertEquals(2, parts.length, "Exactly one ', ' separator should join the two method entries: " + breakdown);
        assertFalse(parts[0].isEmpty(), "The entry must not start with a stray ', ' separator: " + breakdown);
        assertFalse(parts[1].isEmpty(), "The second entry must be present: " + breakdown);
    }

    @Test
    void testHighContentionDetection() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();

        detector.registerRandom(random, "high-contention-random");
        // First access establishes firstAccessTime
        detector.recordRandomAccess(random, "high-contention-random", "nextInt");

        try {
            Thread.sleep(15);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // A large burst of accesses in a short window drives accesses/sec well past the 10,000 threshold
        for (int i = 0; i < 200_000; i++) {
            detector.recordRandomAccess(random, "high-contention-random", "nextInt");
        }

        SharedRandomDetector.SharedRandomReport report = detector.analyze();

        assertNotNull(report);
        assertTrue(report.hasIssues(), "High access rate should be flagged as an issue");
        assertFalse(report.highContention.isEmpty(), "High contention list should be populated");
        assertTrue(report.highContention.get(0).contains("high-contention-random"),
            "High contention entry should reference the random by name");
    }

    @Test
    void testNullSafety() {
        SharedRandomDetector detector = new SharedRandomDetector();
        
        // Should not throw on null inputs
        detector.registerRandom(null, "null-random");
        detector.recordRandomAccess(null, "null", "nextInt");
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        assertNotNull(report);
    }

    @Test
    void testReportToString() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        detector.registerRandom(random, "test-random");
        
        // Simulate shared access
        Thread t1 = new Thread(() -> {
            random.nextInt();
            detector.recordRandomAccess(random, "test-random", "nextInt");
        });
        
        Thread t2 = new Thread(() -> {
            random.nextInt();
            detector.recordRandomAccess(random, "test-random", "nextInt");
        });
        
        t1.start();
        t2.start();
        
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        String reportStr = report.toString();
        assertNotNull(reportStr);
        assertTrue(reportStr.contains("SHARED RANDOM ISSUES DETECTED"), "Report should have header");
        assertTrue(reportStr.contains("Shared Random Instances"), "Report should mention shared randoms");
        assertTrue(reportStr.contains("Method Breakdown:"), "Report should include method breakdown section");
        assertTrue(reportStr.contains("Random Activity:"), "Report should include random activity section");
        assertFalse(reportStr.contains("High Contention:"),
            "Report must not print a High Contention section when none was detected");
        assertFalse(reportStr.contains("No issues detected."),
            "Report must not claim no issues when shared random issues were detected");
    }

    @Test
    void testThreadActivityTracking() {
        SharedRandomDetector detector = new SharedRandomDetector();
        Random random = new Random();
        
        detector.registerRandom(random, "activity-random");
        
        // Multiple threads
        Thread t1 = new Thread(() -> {
            detector.recordRandomAccess(random, "activity-random", "nextInt");
        });
        
        Thread t2 = new Thread(() -> {
            detector.recordRandomAccess(random, "activity-random", "nextInt");
        });
        
        Thread t3 = new Thread(() -> {
            detector.recordRandomAccess(random, "activity-random", "nextInt");
        });
        
        t1.start();
        t2.start();
        t3.start();
        
        try {
            t1.join();
            t2.join();
            t3.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        SharedRandomDetector.SharedRandomReport report = detector.analyze();
        
        assertNotNull(report);
        assertTrue(report.hasIssues(), "Should detect 3 threads accessing");
        assertTrue(report.randomActivity.get("activity-random").contains("3 threads"),
                   "Should report 3 threads");
    }
}
