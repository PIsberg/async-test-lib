package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.DataHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for DataHolder.
 *
 * ========================================================================
 * DETECTOR: MemoryOrderingMonitor
 * ========================================================================
 *
 * THE BUG:
 * DataHolder.value and DataHolder.ready are plain (non-volatile) fields. Without
 * volatile or synchronization, the JMM allows the JIT compiler and CPU to reorder
 * stores and cache results in registers. A consumer thread may observe ready=false
 * even after publish() completed, or see value=0 after publish(42) was called —
 * a stale read that is invisible to single-threaded tests.
 *
 * WHY @Test PASSES:
 * In a single-threaded test, publish() is always called before consume() with a
 * sequential happens-before relationship. There is no opportunity for stale caches
 * or CPU reordering to hide the writes.
 *
 * WHY @AsyncTest DETECTS:
 * With mixed producer/consumer threads, MemoryOrderingMonitor records writes (WRITE)
 * and reads (READ) per location. When a read sees a different value than the most
 * recent write from another thread, the monitor reports a stale read.
 *
 * FIX:
 * Declare both value and ready as volatile, or use synchronized on all accesses.
 */
class DataHolderTest {

    private DataHolder holder;

    @BeforeEach
    void setUp() {
        holder = new DataHolder();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testPublishThenConsume_returnsValue() {
        holder.publish(42);
        assertEquals(42, holder.consume());
    }

    @Test
    void testConsume_beforePublish_returnsMinusOne() {
        assertEquals(-1, holder.consume());
    }

    @Test
    void testIsReady_afterPublish_isTrue() {
        holder.publish(1);
        assertTrue(holder.isReady());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the memory ordering violation
    // -------------------------------------------------------------------------

    /**
     * Producer threads write to the shared DataHolder; consumer threads read.
     * MemoryOrderingMonitor records each write and read per location. When a
     * consumer reads a value that does not match the last write from another
     * thread, the monitor flags a stale read.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: declare value and ready as volatile
     */
    @Disabled("Remove @Disabled to see the bug detected by MemoryOrderingMonitor")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectMemoryOrderingViolations = true, failOn = FailOn.LOW)
    void test_concurrent_detectsStaleRead() {
        var mon = AsyncTestContext.memoryOrderingMonitor();
        String name = Thread.currentThread().getName();

        if (name.hashCode() % 2 == 0) {
            // Producer: publish a value and record the writes
            int v = (int) (Math.random() * 1000);
            holder.publish(v);
            mon.recordWrite("DataHolder.ready", true);
            mon.recordWrite("DataHolder.value", v);
        } else {
            // Consumer: read and record what was seen
            int result = holder.consume();
            boolean ready = holder.isReady();
            mon.recordRead("DataHolder.ready", ready);
            mon.recordRead("DataHolder.value", result);
            holder.reset();
        }
    }
}
