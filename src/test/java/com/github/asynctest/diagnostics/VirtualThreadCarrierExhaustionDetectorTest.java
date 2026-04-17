package com.github.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VirtualThreadCarrierExhaustionDetectorTest {

    private VirtualThreadCarrierExhaustionDetector detector;

    @BeforeEach
    void setUp() {
        // Use a carrier count of 2 so tests can trigger exhaustion easily
        detector = new VirtualThreadCarrierExhaustionDetector(2);
    }

    @Test
    void noBlockingRecorded_reportHasNoIssues() {
        var report = detector.analyze();
        assertFalse(report.hasIssues());
        assertEquals(0, report.getExhaustionEventCount());
        assertEquals(0, report.getPeakConcurrentlyBlocked());
    }

    @Test
    void singleBlockingEvent_belowThreshold_noIssue() throws Exception {
        Thread vt = Thread.ofVirtual().start(() -> {
            detector.recordBlockingStart("test-lock");
            detector.recordBlockingEnd("test-lock");
        });
        vt.join(200);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), report.toString());
        assertEquals(1, report.getPeakConcurrentlyBlocked());
    }

    @Test
    void concurrentBlockingReachesCarrierCount_exhaustionDetected() throws Exception {
        // carrier count = 2; two simultaneous blocks should trigger exhaustion
        CountDownLatch bothBlocking = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Thread vt1 = Thread.ofVirtual().start(() -> {
            detector.recordBlockingStart("lock-1");
            bothBlocking.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            detector.recordBlockingEnd("lock-1");
        });
        Thread vt2 = Thread.ofVirtual().start(() -> {
            detector.recordBlockingStart("lock-2");
            bothBlocking.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            detector.recordBlockingEnd("lock-2");
        });

        bothBlocking.await();
        release.countDown();
        vt1.join(500);
        vt2.join(500);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "Expected exhaustion event");
        assertTrue(report.getExhaustionEventCount() > 0);
        assertEquals(2, report.getPeakConcurrentlyBlocked());
        assertEquals(2, report.getCarrierCount());
    }

    @Test
    void platformThreadsIgnored() throws InterruptedException {
        Thread pt = Thread.ofPlatform().start(() -> {
            detector.recordBlockingStart("platform-lock");
            detector.recordBlockingEnd("platform-lock");
        });
        pt.join(200);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Platform threads should not contribute to exhaustion count");
        assertEquals(0, report.getPeakConcurrentlyBlocked());
    }

    @Test
    void blockingEndDecrementsCount() throws Exception {
        AtomicInteger peakDuringTest = new AtomicInteger(0);

        Thread vt = Thread.ofVirtual().start(() -> {
            detector.recordBlockingStart("op-1");
            detector.recordBlockingEnd("op-1");
            // After end, concurrent count should be back to 0
            detector.recordBlockingStart("op-2");
            detector.recordBlockingEnd("op-2");
        });
        vt.join(200);

        var report = detector.analyze();
        // Peak should be 1 (only one at a time)
        assertEquals(1, report.getPeakConcurrentlyBlocked());
        assertFalse(report.hasIssues());
    }

    @Test
    void exhaustionReport_toStringContainsDiagnostics() throws Exception {
        CountDownLatch bothBlocking = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        Thread vt1 = Thread.ofVirtual().start(() -> {
            detector.recordBlockingStart("sync-block");
            bothBlocking.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            detector.recordBlockingEnd("sync-block");
        });
        Thread vt2 = Thread.ofVirtual().start(() -> {
            detector.recordBlockingStart("sync-block");
            bothBlocking.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            detector.recordBlockingEnd("sync-block");
        });

        bothBlocking.await();
        release.countDown();
        vt1.join(500);
        vt2.join(500);

        var report = detector.analyze();
        String text = report.toString();
        assertTrue(text.contains("carrier"));
        assertTrue(text.contains("LEARNING"));
        assertTrue(text.contains("ReentrantLock"));
    }

    @Test
    void noIssues_toStringContainsNoIssuesMessage() {
        var report = detector.analyze();
        assertTrue(report.toString().contains("No carrier exhaustion detected"));
    }

    @Test
    void carrierCountReflectedInReport() {
        var report = detector.analyze();
        assertEquals(2, report.getCarrierCount());
    }
}
