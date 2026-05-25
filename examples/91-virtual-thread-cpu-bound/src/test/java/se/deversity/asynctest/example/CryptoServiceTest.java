package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.CryptoService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        byte[] plain = "hello world".getBytes();
        byte[] encrypted = service.encrypt(plain);
        byte[] decrypted = service.decrypt(encrypted);
        assertArrayEquals(plain, decrypted);
    }

    @Test
    void test_singleThread_emptyInputReturnsEmpty() {
        byte[] result = service.encrypt(new byte[0]);
        assertNotNull(result);
    }

    /**
     * Remove {@code @Disabled} to see {@code VirtualThreadCpuBoundTaskDetector}
     * report that the encryption task ran for longer than the CPU-bound threshold.
     *
     * <p>The detector is given a task id via {@code recordTaskStart()}, the
     * encryption executes (no yield points), and then {@code recordTaskEnd()}
     * is called. The detector measures elapsed wall time and reports the task as
     * CPU-bound when it exceeds the configured threshold.
     */
    @Disabled("Remove @Disabled to see bug detected by VirtualThreadCpuBoundTaskDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectVirtualThreadCpuBoundTasks = true)
    void test_concurrent_detectsCpuBoundTask() {
        var detector = AsyncTestContext.virtualThreadCpuBoundTaskDetector();

        // Record task start — returns an opaque task ID.
        String taskId = detector.recordTaskStart(
                "crypto-encrypt", Thread.currentThread());

        try {
            // BUG: CPU-bound loop on a virtual thread; never yields to the scheduler.
            service.encrypt("sensitive-data".getBytes());
        } finally {
            detector.recordTaskEnd(taskId);
        }
    }
}
