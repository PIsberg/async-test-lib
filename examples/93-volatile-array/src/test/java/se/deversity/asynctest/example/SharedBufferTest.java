package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.SharedBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Demonstrates {@code VolatileArrayDetector}.
 *
 * <p>The passing tests show the buffer works correctly in a single thread.
 * The disabled test reveals the bug: multiple threads write to different slots
 * of the volatile array, and the detector observes multi-thread element writes
 * — flagging the misuse of {@code volatile} on an array.
 *
 * <p>Remove {@code @Disabled} to see the detector fire.
 */
class SharedBufferTest {

    private SharedBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new SharedBuffer();
    }

    @Test
    void test_singleThread_setAndGet() {
        buffer.set(0, 42);
        assertEquals(42, buffer.get(0));
    }

    @Test
    void test_singleThread_capacityIsCorrect() {
        assertNotNull(buffer.getBuffer());
        assertEquals(10, buffer.capacity());
    }

    /**
     * Remove {@code @Disabled} to see {@code VolatileArrayDetector} report
     * volatile array element accesses from multiple threads.
     *
     * <p>The detector is given the array instance via {@code registerArray()}.
     * Each concurrent invocation calls {@code recordElementWrite()} before
     * writing and {@code recordElementRead()} before reading. Because multiple
     * threads write to the same (volatile) array the detector flags it.
     */
    @Disabled("Remove @Disabled to see bug detected by VolatileArrayDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectVolatileArrayIssues = true)
    void test_concurrent_detectsVolatileArrayIssue() {
        var detector = AsyncTestContext.volatileArrayMonitor();
        int[] raw = buffer.getBuffer();
        String arrayName = "shared-buffer";

        // Register the array with the detector so it can track access patterns.
        detector.registerArray(raw, arrayName, int.class);

        int slot = (int) (Thread.currentThread().threadId() % buffer.capacity());
        int value = (int) (Thread.currentThread().threadId() * 17);

        // Record the element write — this triggers the multi-thread check.
        detector.recordElementWrite(raw, slot, arrayName);

        // BUG: plain array element write — no visibility guarantee.
        buffer.set(slot, value);

        // Record the element read.
        detector.recordElementRead(raw, slot, arrayName);

        // Read back — may observe a stale value from a different thread.
        buffer.get(slot);
    }
}
