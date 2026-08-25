package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.example.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for InventoryService.
 *
 * ========================================================================
 * DETECTOR: RaceConditionDetector
 * ========================================================================
 *
 * This test demonstrates a check-then-update race condition in an inventory
 * management service:
 * - A sequential @Test PASSES — reservations are consistent, stock never goes negative
 * - The @AsyncTest with 8 threads exposes the TOCTOU window: multiple threads pass
 *   the stock check before any of them writes the decremented value, driving stock
 *   well below zero
 *
 * THE BUG:
 * {@code reserveItem()} performs three separate steps:
 *   1. Read  — {@code current = stock.get(sku)}
 *   2. Check — {@code if (current < quantity) return false}
 *   3. Write — {@code stock.put(sku, current - quantity)}
 *
 * Between steps 2 and 3 (the race window), another thread may have already
 * decremented the stock. Both threads believe sufficient stock exists; both
 * write back a stale decrement — effectively performing a double-decrement on
 * the same starting value.
 *
 * WHY @Test PASSES:
 * Sequential execution serialises all three steps. No other thread touches the
 * stock between the check and the write, so the balance is always consistent.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * RaceConditionDetector is activated by {@code detectRaceConditions = true}, and it is
 * recording-fed: it only sees the accesses the code under test hands it, through
 * {@code recordFieldRead} / {@code recordFieldWrite}. The demonstration wires those two
 * methods into InventoryService.observeStockAccess, so the read on either side of the
 * check and the write after it are reported from the threads that made them. The detector
 * pairs accesses within one invocation round and reports the field as a write hotspot.
 *
 * DETECTOR ENABLED HERE:
 * RaceConditionDetector — concurrent writes to the stock entry with no synchronization.
 * It is the only one this demonstration switches on, so it is the only one that can report.
 *
 * FIX:
 * Replace the three-step read-check-write with an atomic CAS loop:
 *   {@code stock.replace(sku, current, current - quantity)}
 * The CAS fails atomically if the value has changed, so the check and update are
 * always consistent.
 */
class InventoryServiceTest {

    private static final String SKU = "WIDGET-42";
    private static final int    INITIAL_STOCK = 10;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = new InventoryService();
        service.loadStock(SKU, INITIAL_STOCK);
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes, no race visible
    // -------------------------------------------------------------------------

    @Test
    void testReserveItem_singleThread_succeeds() {
        assertTrue(service.reserveItem(SKU, 3));
        assertEquals(7, service.getStock(SKU));
        assertEquals(3, service.getTotalReserved());
    }

    @Test
    void testReserveItem_insufficientStock_returnsFalse() {
        assertFalse(service.reserveItem(SKU, 99));
        assertEquals(INITIAL_STOCK, service.getStock(SKU));
    }

    @Test
    void testReserveItem_exactStock_succeeds() {
        assertTrue(service.reserveItem(SKU, INITIAL_STOCK));
        assertEquals(0, service.getStock(SKU));
        assertFalse(service.reserveItem(SKU, 1), "Should reject after stock is exhausted");
    }

    @Test
    void testReserveItem_sequential_neverNegative() {
        // Sequential reservations: total reserved must never exceed initial stock
        int reserved = 0;
        for (int i = 0; i < 20; i++) {
            if (service.reserveItem(SKU, 1)) {
                reserved++;
            }
        }
        assertEquals(reserved, service.getTotalReserved());
        assertTrue(service.getStock(SKU) >= 0,
                "Stock must never go negative under sequential access");
    }

    @Test
    void testReserveItemFixed_singleThread_succeeds() {
        assertTrue(service.reserveItemFixed(SKU, 4));
        assertEquals(6, service.getStock(SKU));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the check-then-update race condition
    // -------------------------------------------------------------------------

    /**
     * The bug: 8 threads all attempt to reserve 1 unit of the same SKU. Because
     * the check and the write are not atomic, multiple threads pass the stock check
     * before any of them decrements the balance. All of them then write back a
     * stale value, driving the stock negative and over-committing inventory.
     *
     * RaceConditionDetector tracks cross-thread field accesses recorded with
     * {@code recordFieldRead} and {@code recordFieldWrite}. The hooks are installed on the
     * service so the read and the write are reported from inside reserveItem, on either side
     * of the race window, rather than around the call where the check would be invisible.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with a RaceConditionDetector finding naming the stock entry
     *    as a write hotspot: "8 writes observed across 8 threads"
     * 3. Fix: replace reserveItem() with reserveItemFixed(), whose CAS loop makes the check and
     *    the update indivisible
     */
    @Disabled("Remove @Disabled to see race condition detected by RaceConditionDetector")
    @AsyncTest(threads = 8, invocations = 100, detectRaceConditions = true, failOn = FailOn.LOW)
    void testReserveItem_concurrent_detectsRaceCondition() {
        // RaceConditionDetector is recording-fed: nothing reaches it unless the code under test
        // says which object and field it touched. This demonstration used to record nothing at
        // all and relied on an assertion that could not fail, so enabling it printed an empty
        // report and passed. See issue #346.
        var detector = AsyncTestContext.raceConditionDetector();
        service.observeStockAccess(detector::recordFieldRead, detector::recordFieldWrite);

        service.reserveItem(SKU, 1); // BUG: check-then-update without synchronization
    }

    /**
     * Fixed version: atomic CAS loop makes check and update indivisible.
     * No matter how many threads compete, only one can commit a given stock level —
     * all others retry with the freshly decremented value.
     */
    @Test
    void testReserveItemFixed_singleThread_exactlyTenReservations() {
        int reserved = 0;
        for (int i = 0; i < INITIAL_STOCK + 5; i++) {
            if (service.reserveItemFixed(SKU, 1)) {
                reserved++;
            }
        }
        // Exactly INITIAL_STOCK reservations should succeed; the rest are rejected
        assertEquals(INITIAL_STOCK, reserved,
                "Fixed version should allow exactly " + INITIAL_STOCK + " reservations");
        assertEquals(0, service.getStock(SKU),
                "Stock should be exactly zero after all units are reserved");
    }
}
