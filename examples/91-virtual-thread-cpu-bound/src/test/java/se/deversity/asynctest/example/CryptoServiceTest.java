package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.VirtualThreadCpuBoundTaskDetector;
import se.deversity.asynctest.example.service.CryptoService;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates {@code VirtualThreadCpuBoundTaskDetector}.
 *
 * <p>The passing tests verify correct encryption behaviour in a single thread.
 * The disabled test shows that running the CPU-intensive encryption on virtual
 * test threads causes the detector to flag the tasks as CPU-bound (duration
 * exceeds the threshold without any blocking / yield points).
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class CryptoServiceTest {

    private CryptoService service;

    @BeforeEach
    void setUp() {
        service = new CryptoService();
    }

    @Test
    void test_singleThread_encryptDecryptRoundtrip() {
        byte[] plain = "hello world".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = service.encrypt(plain, 0L);
        byte[] decrypted = service.decrypt(encrypted);

        assertArrayEquals(plain, decrypted);
    }

    @Test
    void test_singleThread_emptyInputReturnsEmpty() {
        assertNotNull(service.encrypt(new byte[0], 0L));
    }

    /**
     * The work really does take the time it claims to, which is what the old iteration count
     * did not: 500,000 rounds of integer arithmetic finish in well under a millisecond.
     */
    @Test
    void test_encryptOccupiesItsThreadForTheStatedTime() {
        long start = System.nanoTime();

        service.encrypt("payload".getBytes(StandardCharsets.UTF_8));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs >= CryptoService.CPU_WORK_MILLIS,
                "encrypt() returned after " + elapsedMs + "ms, which is less than the "
                        + CryptoService.CPU_WORK_MILLIS + "ms of work it is supposed to do");
    }

    /**
     * The detector's positive direction: a virtual thread that computes past the threshold
     * without a yield point.
     */
    @Test
    void testCpuBoundDetector_longRunOnAVirtualThread_reports() throws Exception {
        VirtualThreadCpuBoundTaskDetector detector = new VirtualThreadCpuBoundTaskDetector();

        Thread.ofVirtual().start(() -> {
            String taskId = detector.recordTaskStart("crypto-encrypt", Thread.currentThread());
            try {
                service.encrypt("sensitive-data".getBytes(StandardCharsets.UTF_8));
            } finally {
                detector.recordTaskEnd(taskId);
            }
        }).join();

        assertTrue(detector.analyze().hasIssues(),
                "a virtual thread computing for " + CryptoService.CPU_WORK_MILLIS
                        + "ms without yielding is holding its carrier");
    }

    /**
     * And the other direction: the same call with no CPU work in it is over long before the
     * threshold, and there is nothing to report. Virtual threads are fine, as long as what
     * runs on them lets go.
     */
    @Test
    void testCpuBoundDetector_shortRunOnAVirtualThread_isSilent() throws Exception {
        VirtualThreadCpuBoundTaskDetector detector = new VirtualThreadCpuBoundTaskDetector();

        Thread.ofVirtual().start(() -> {
            String taskId = detector.recordTaskStart("crypto-encrypt", Thread.currentThread());
            try {
                service.encrypt("sensitive-data".getBytes(StandardCharsets.UTF_8), 0L);
            } finally {
                detector.recordTaskEnd(taskId);
            }
        }).join();

        assertFalse(detector.analyze().hasIssues(),
                "a task that finished promptly did not monopolise anything");
    }

    /**
     * Remove {@code @Disabled} to see {@code VirtualThreadCpuBoundTaskDetector} report a task
     * that held its carrier past the 50ms threshold with no yield point in it.
     *
     * <p>invocations is 1 because eight virtual threads each computing for
     * {@code CPU_WORK_MILLIS} is already the demonstration, and repeating the round only
     * multiplies the wall time.
     *
     * <p>Before issue #346 the work was an iteration count rather than a duration: 500,000
     * rounds of integer arithmetic, which the JIT finishes in well under a millisecond. The
     * task never approached the threshold, and the demonstration fired only on the runs where
     * the machine happened to be loaded enough to stretch it past 50ms of wall time. That is
     * why it appears in the issue's "fires sometimes" list rather than the "never fires" one.
     */
    @Disabled("Remove @Disabled to see bug detected by VirtualThreadCpuBoundTaskDetector")
    @AsyncTest(threads = 8, invocations = 1, detectAll = false,
            detectVirtualThreadCpuBoundTasks = true, failOn = FailOn.LOW)
    void test_concurrent_detectsCpuBoundTask() {
        VirtualThreadCpuBoundTaskDetector detector =
                AsyncTestContext.virtualThreadCpuBoundTaskDetector();

        String taskId = detector.recordTaskStart("crypto-encrypt", Thread.currentThread());
        try {
            // BUG: CPU-bound work on a virtual thread, with no yield point anywhere in it.
            service.encrypt("sensitive-data".getBytes(StandardCharsets.UTF_8));
        } finally {
            detector.recordTaskEnd(taskId);
        }
    }
}
