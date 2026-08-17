package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualThreadResourceSaturationDetectorTest {

    private static final int CAPACITY = 2;

    @Test
    void cleanWhenNothingRecorded() {
        var d = new VirtualThreadResourceSaturationDetector();
        assertFalse(d.analyze().hasIssues());
        assertEquals("VIRTUAL THREAD RESOURCE SATURATION - clean", d.analyze().toString());
    }

    @Test
    void anUnregisteredResourceIsIgnored() {
        var d = new VirtualThreadResourceSaturationDetector();
        d.recordAcquireStart("never-declared", Thread.currentThread());
        d.recordAcquired("never-declared", Thread.currentThread());
        assertFalse(d.analyze().hasIssues(), "without a declared capacity there is nothing to compare against");
    }

    /**
     * The corrected shape: a semaphore sized to the resource bounds the fan-out, so however many
     * virtual threads arrive, no more than {@code CAPACITY} of them are ever queued on the
     * resource itself. This must stay silent.
     */
    @Test
    void fanOutBoundedBySemaphoreStaysSilent() throws Exception {
        var d = new VirtualThreadResourceSaturationDetector();
        d.registerResource("connections", CAPACITY);
        Semaphore gate = new Semaphore(CAPACITY);

        runOnVirtualThreads(8, () -> {
            gate.acquire();                    // admission control before the resource
            try {
                d.recordAcquireStart("connections", Thread.currentThread());
                d.recordAcquired("connections", Thread.currentThread());
            } finally {
                gate.release();
            }
        });

        var report = d.analyze();
        assertFalse(report.hasIssues(), () -> "a bounded fan-out must be clean:\n" + report);
    }

    @Test
    void unboundedVirtualFanOutOnABoundedResourceIsDetected() throws Exception {
        var d = new VirtualThreadResourceSaturationDetector();
        d.registerResource("connections", CAPACITY);
        CountDownLatch allWaiting = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);

        runOnVirtualThreads(8, () -> {
            d.recordAcquireStart("connections", Thread.currentThread());
            allWaiting.countDown();
            release.await(5, TimeUnit.SECONDS);   // hold every caller in the queue at once
            d.recordAcquired("connections", Thread.currentThread());
        }, allWaiting, release);

        var report = d.analyze();
        assertTrue(report.hasIssues(), () -> "8 waiters on a capacity-2 resource:\n" + report);
        var v = report.structuredViolations.get(0);
        assertEquals("VirtualThreadResourceSaturation", v.detector());
        assertEquals(IssueSeverity.HIGH, v.severity());
        assertEquals(CAPACITY, v.attributes().get("capacity"));
        assertEquals(8, v.attributes().get("peakWaiting"));
        assertEquals(8, v.attributes().get("virtualAcquirers"));
    }

    @Test
    void aQueueNoDeeperThanCapacityIsSilent() throws Exception {
        var d = new VirtualThreadResourceSaturationDetector();
        d.registerResource("connections", 8);      // capacity comfortably above the fan-out
        CountDownLatch allWaiting = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);

        runOnVirtualThreads(4, () -> {
            d.recordAcquireStart("connections", Thread.currentThread());
            allWaiting.countDown();
            release.await(5, TimeUnit.SECONDS);
            d.recordAcquired("connections", Thread.currentThread());
        }, allWaiting, release);

        assertFalse(d.analyze().hasIssues(), "4 waiters against capacity 8 is not saturation");
    }

    @Test
    void platformOnlyContentionIsOutOfScope() throws Exception {
        var d = new VirtualThreadResourceSaturationDetector();
        d.registerResource("connections", CAPACITY);
        CountDownLatch allWaiting = new CountDownLatch(6);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            Thread t = Thread.ofPlatform().start(() -> {
                d.recordAcquireStart("connections", Thread.currentThread());
                allWaiting.countDown();
                awaitQuietly(release);
                d.recordAcquired("connections", Thread.currentThread());
            });
            threads.add(t);
        }
        assertTrue(allWaiting.await(5, TimeUnit.SECONDS));
        release.countDown();
        for (Thread t : threads) t.join();

        assertFalse(d.analyze().hasIssues(),
                "a bounded platform pool cannot produce this hazard; THREAD_POOL_DEADLOCK owns that ground");
    }

    /**
     * A caller returns the resource and then records having done so, and in that window the next
     * caller can legitimately be granted it. The detector must not read that skew as the limit
     * being breached - which it did until CI caught it on a correctly bounded pool.
     */
    @Test
    void holdersAreNotCountedBecauseTheCountCannotBeTrusted() throws Exception {
        var d = new VirtualThreadResourceSaturationDetector();
        d.registerResource("connections", 2);
        Semaphore realLimit = new Semaphore(2);

        // Eight callers through a genuine 2-permit gate, recording after releasing - the natural
        // ordering, and the one that used to produce a false positive.
        runOnVirtualThreads(8, () -> {
            realLimit.acquire();
            try {
                d.recordAcquireStart("connections", Thread.currentThread());
                d.recordAcquired("connections", Thread.currentThread());
            } finally {
                realLimit.release();
            }
        });

        assertFalse(d.analyze().hasIssues(), "the limit was respected throughout");
    }

    @Test
    void capacityBelowOneIsRejected() {
        var d = new VirtualThreadResourceSaturationDetector();
        d.registerResource("nonsense", 0);
        d.recordAcquireStart("nonsense", Thread.currentThread());
        assertFalse(d.analyze().hasIssues(), "a resource with no stated capacity is not tracked");
    }

    @Test
    void nullsAreIgnored() {
        var d = new VirtualThreadResourceSaturationDetector();
        d.registerResource(null, 4);
        d.registerResource("connections", CAPACITY);
        d.recordAcquireStart(null, Thread.currentThread());
        d.recordAcquireStart("connections", null);
        assertFalse(d.analyze().hasIssues());
    }

    @Test
    void disableStopsRecording() {
        var d = new VirtualThreadResourceSaturationDetector();
        d.disable();
        d.registerResource("connections", 1);
        queueTwoVirtualWaiters(d, "connections");
        assertFalse(d.analyze().hasIssues());

        d.enable();
        d.registerResource("other", 1);
        queueTwoVirtualWaiters(d, "other");
        assertTrue(d.analyze().hasIssues());
    }

    @Test
    void reportToStringCarriesTheFinding() {
        var d = new VirtualThreadResourceSaturationDetector();
        d.registerResource("connections", 1);
        queueTwoVirtualWaiters(d, "connections");

        String rendered = d.analyze().toString();
        assertTrue(rendered.contains("VIRTUAL THREAD RESOURCE SATURATION DETECTED"));
        assertTrue(rendered.contains("connections"));
        assertTrue(rendered.contains("Fix:"));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Puts two virtual waiters on {@code resource} without starting anything.
     *
     * <p>The detector reads only {@code threadId()} and {@code isVirtual()}, so an unstarted
     * virtual thread is a faithful stand-in and keeps the assertion free of scheduling.
     */
    private static void queueTwoVirtualWaiters(VirtualThreadResourceSaturationDetector d, String resource) {
        d.recordAcquireStart(resource, Thread.ofVirtual().unstarted(() -> { }));
        d.recordAcquireStart(resource, Thread.ofVirtual().unstarted(() -> { }));
    }

    /** A body that may throw, so the tests can await inside a virtual thread. */
    private interface Body {
        void run() throws Exception;
    }

    private static void runOnVirtualThreads(int count, Body body) throws Exception {
        runOnVirtualThreads(count, body, null, null);
    }

    /**
     * Starts {@code count} virtual threads. When {@code allStarted} and {@code release} are given,
     * waits for every thread to reach the latch before releasing them, so the peak is deterministic
     * rather than a matter of scheduling luck.
     */
    private static void runOnVirtualThreads(int count, Body body,
                                            CountDownLatch allStarted, CountDownLatch release)
            throws Exception {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    body.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        if (allStarted != null && release != null) {
            assertTrue(allStarted.await(5, TimeUnit.SECONDS), "every worker should have queued");
            release.countDown();
        }
        for (Thread t : threads) t.join();
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
