package se.deversity.asynctest.diagnostics;

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
    void oneVirtualThreadBlockedInTwoNestedOperationsIsOneBlockedThread() {
        // A synchronized block around a native call records two starts on one thread; that is one
        // pinned carrier, not two, so two carriers are not exhausted (#964).
        Thread vt = Thread.ofVirtual().unstarted(() -> { });
        detector.recordBlockingStart("synchronized-lock", vt);
        detector.recordBlockingStart("native-call", vt);
        detector.recordBlockingEnd("native-call", vt);
        detector.recordBlockingEnd("synchronized-lock", vt);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), report.toString());
        assertEquals(1, report.getPeakConcurrentlyBlocked());
    }

    @Test
    void anOuterBlockStaysCountedAfterANestedOneEnds() {
        Thread a = Thread.ofVirtual().unstarted(() -> { });
        Thread b = Thread.ofVirtual().unstarted(() -> { });
        detector.recordBlockingStart("synchronized-lock", a);
        detector.recordBlockingStart("native-call", a);
        detector.recordBlockingEnd("native-call", a);
        detector.recordBlockingStart("synchronized-lock", b);   // a is still blocked: two carriers

        assertTrue(detector.analyze().hasIssues(), "two threads blocked at once on two carriers");
        detector.recordBlockingEnd("synchronized-lock", b);
        detector.recordBlockingEnd("synchronized-lock", a);
    }

    @Test
    void aBlockNeverEndedDoesNotCountInTheNextRound() {
        // A body that threw inside its blocking section never records the end; its virtual thread
        // is gone by the next round and holds no carrier there (#964).
        Thread previous = Thread.ofVirtual().unstarted(() -> { });
        detector.recordBlockingStart("synchronized-lock", previous);
        detector.markInvocationStart();
        Thread next = Thread.ofVirtual().unstarted(() -> { });
        detector.recordBlockingStart("synchronized-lock", next);
        detector.recordBlockingEnd("synchronized-lock", next);

        assertFalse(detector.analyze().hasIssues(), "one thread blocked this round, on two carriers");
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

    @Test
    void analyzeIsIdempotentForAThreadStillBlocked() {
        detector.recordBlockingStart("socket-read");
        int first = detector.analyze().getExhaustionDetails().size();
        int second = detector.analyze().getExhaustionDetails().size();
        assertEquals(first, second, "analyze() appended the still-blocked line to instance state on every call");
    }
}
