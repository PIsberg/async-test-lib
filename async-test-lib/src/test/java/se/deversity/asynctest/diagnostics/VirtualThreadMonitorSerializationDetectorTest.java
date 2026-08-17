package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualThreadMonitorSerializationDetectorTest {

    private static final Object LOCK = new Object();

    @Test
    void cleanWhenNothingRecorded() {
        var d = new VirtualThreadMonitorSerializationDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("VIRTUAL THREAD MONITOR SERIALIZATION - clean", d.analyze().toString());
    }

    /**
     * The corrected shape: the critical section is short enough that callers pass straight
     * through, so the queue never reaches the threshold. Same monitor, same virtual threads, no
     * finding - which is what stops this detector from simply reporting every {@code
     * synchronized} block.
     */
    @Test
    void aMonitorNobodyQueuesOnStaysSilent() throws Exception {
        var d = new VirtualThreadMonitorSerializationDetector();
        Object lock = new Object();

        // Serially: each thread enters and acquires before the next one arrives.
        for (int i = 0; i < 8; i++) {
            Thread t = Thread.ofVirtual().start(() -> {
                d.recordMonitorEnter(lock, "shortSection", Thread.currentThread());
                d.recordMonitorAcquired(lock, Thread.currentThread());
            });
            t.join();
        }

        var report = d.analyze();
        assertFalse(report.hasIssues(), () -> "no queue ever formed:\n" + report);
    }

    @Test
    void aDeepQueueOfVirtualThreadsIsDetected() throws Exception {
        var d = new VirtualThreadMonitorSerializationDetector();
        Object lock = new Object();
        CountDownLatch allQueued = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                d.recordMonitorEnter(lock, "sessionCache", Thread.currentThread());
                allQueued.countDown();
                awaitQuietly(release);            // every caller is in the queue at once
                d.recordMonitorAcquired(lock, Thread.currentThread());
            }));
        }
        assertTrue(allQueued.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (Thread t : threads) t.join();

        var report = d.analyze();
        assertTrue(report.hasIssues(), () -> "6 virtual threads queued on one monitor:\n" + report);
        var v = report.structuredViolations.get(0);
        assertEquals("VirtualThreadMonitorSerialization", v.detector());
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertEquals(6, v.attributes().get("peakWaiting"));
        assertEquals(6, v.attributes().get("virtualWaiters"));
        assertTrue(report.toString().contains("sessionCache"), report.toString());
    }

    @Test
    void platformThreadsQueueingAreNotThisDetectorsFinding() throws Exception {
        var d = new VirtualThreadMonitorSerializationDetector();
        Object lock = new Object();
        CountDownLatch allQueued = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                d.recordMonitorEnter(lock, "sessionCache", Thread.currentThread());
                allQueued.countDown();
                awaitQuietly(release);
                d.recordMonitorAcquired(lock, Thread.currentThread());
            }));
        }
        assertTrue(allQueued.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (Thread t : threads) t.join();

        assertFalse(d.analyze().hasIssues(),
                "a bounded pool queueing on a monitor is LOCK_CONTENTION's finding, not this one");
    }

    @Test
    void aQueueBelowTheThresholdIsSilent() {
        var d = new VirtualThreadMonitorSerializationDetector(8, 25);
        // Three simultaneous waiters, threshold 8.
        for (int i = 0; i < 3; i++) d.recordMonitorEnter(LOCK, "belowThreshold", Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void onJdk24AndLaterTheReportSaysNothingElseCatchesIt() {
        var d = new VirtualThreadMonitorSerializationDetector(2, 25);
        var lock = new Object();
        d.recordMonitorEnter(lock, "cache", Thread.ofVirtual().unstarted(() -> { }));
        d.recordMonitorEnter(lock, "cache", Thread.ofVirtual().unstarted(() -> { }));

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.toString().contains("no longer pins the carrier"), report.toString());
        assertEquals(false, report.structuredViolations.get(0).attributes().get("stillPins"));
    }

    @Test
    void beforeJdk24TheReportPointsAtThePinningDetectorToo() {
        var d = new VirtualThreadMonitorSerializationDetector(2, 21);
        var lock = new Object();
        d.recordMonitorEnter(lock, "cache", Thread.ofVirtual().unstarted(() -> { }));
        d.recordMonitorEnter(lock, "cache", Thread.ofVirtual().unstarted(() -> { }));

        var report = d.analyze();
        assertTrue(report.hasIssues());
        assertTrue(report.toString().contains("VIRTUAL_THREAD_PINNING reports it too"), report.toString());
        assertEquals(true, report.structuredViolations.get(0).attributes().get("stillPins"));
    }

    @Test
    void oneVirtualWaiterIsNotSerialization() {
        var d = new VirtualThreadMonitorSerializationDetector(2, 25);
        var lock = new Object();
        Thread virtual = Thread.ofVirtual().unstarted(() -> { });
        d.recordMonitorEnter(lock, "cache", virtual);
        d.recordMonitorEnter(lock, "cache", virtual);   // same thread twice, still one thread
        assertFalse(d.analyze().hasIssues(), "the finding needs at least two distinct virtual waiters");
    }

    @Test
    void thresholdIsNeverBelowTwo() {
        var d = new VirtualThreadMonitorSerializationDetector(0, 25);
        var lock = new Object();
        d.recordMonitorEnter(lock, "cache", Thread.ofVirtual().unstarted(() -> { }));
        assertFalse(d.analyze().hasIssues(), "one waiter is not a queue whatever the caller asked for");
    }

    @Test
    void acquiringLeavesTheQueue() {
        var d = new VirtualThreadMonitorSerializationDetector(3, 25);
        var lock = new Object();
        Thread a = Thread.ofVirtual().unstarted(() -> { });
        Thread b = Thread.ofVirtual().unstarted(() -> { });

        d.recordMonitorEnter(lock, "cache", a);
        d.recordMonitorAcquired(lock, a);       // a is through before b arrives
        d.recordMonitorEnter(lock, "cache", b);
        d.recordMonitorAcquired(lock, b);

        assertFalse(d.analyze().hasIssues(), "peak queue depth was 1, not 2");
    }

    @Test
    void nullsAreIgnored() {
        var d = new VirtualThreadMonitorSerializationDetector();
        d.recordMonitorEnter(null, "cache", Thread.currentThread());
        d.recordMonitorEnter(LOCK, "cache", null);
        d.recordMonitorAcquired(null, Thread.currentThread());
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void missingLabelFallsBackToIdentity() {
        var d = new VirtualThreadMonitorSerializationDetector(2, 25);
        var lock = new Object();
        d.recordMonitorEnter(lock, null, Thread.ofVirtual().unstarted(() -> { }));
        d.recordMonitorEnter(lock, null, Thread.ofVirtual().unstarted(() -> { }));
        assertTrue(d.analyze().violations.get(0).contains("monitor@"));
    }

    @Test
    void disableStopsRecording() {
        var d = new VirtualThreadMonitorSerializationDetector(2, 25);
        var lock = new Object();
        d.disable();
        d.recordMonitorEnter(lock, "off", Thread.ofVirtual().unstarted(() -> { }));
        d.recordMonitorEnter(lock, "off", Thread.ofVirtual().unstarted(() -> { }));
        assertFalse(d.analyze().hasIssues());

        d.enable();
        var other = new Object();
        d.recordMonitorEnter(other, "on", Thread.ofVirtual().unstarted(() -> { }));
        d.recordMonitorEnter(other, "on", Thread.ofVirtual().unstarted(() -> { }));
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void reportToStringCarriesTheFinding() {
        var d = new VirtualThreadMonitorSerializationDetector(2, 25);
        var lock = new Object();
        d.recordMonitorEnter(lock, "sessionCache", Thread.ofVirtual().unstarted(() -> { }));
        d.recordMonitorEnter(lock, "sessionCache", Thread.ofVirtual().unstarted(() -> { }));

        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("VIRTUAL THREAD MONITOR SERIALIZATION DETECTED"));
        assertTrue(rendered.contains("sessionCache"));
        assertTrue(rendered.contains("Fix:"));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("latch never opened");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
