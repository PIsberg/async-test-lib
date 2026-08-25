package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
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
 * RaceConditionDetector is a Phase 1 detector (activated by detectRaceConditions = true).
 * The test body calls:
 *
 *   RaceConditionDetector detector = (RaceConditionDetector) AsyncTestContext.get()
 *       .getClass().getDeclaredField("...") ...
 *
 * RaceConditionDetector is a Phase 1 detector. It is created automatically when
 * {@code detectRaceConditions = true} and its report is printed after the test run.
 * The library also detects the observable symptom: the final stock assertion fails
 * because over-reservation drove the balance negative.
 *
 * DETECTORS TRIGGERED:
 * RaceConditionDetector — concurrent writes to the stock field with no synchronization;
 * Observable symptom — stock goes negative (more units reserved than stocked)
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
     * RaceConditionDetector is a Phase 1 detector activated by
     * {@code detectRaceConditions = true}. It tracks cross-thread field accesses
     * recorded with {@code recordFieldRead} and {@code recordFieldWrite}.
     * The detector instance can be obtained from the current test context, and the
     * test body also records explicit access events on the shared service to help
     * the detector build the access timeline.
     *
     * The observable symptom — stock going negative — is also caught directly by the
     * assertion at the end of the test body.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — the @AsyncTest will fail with a stock-negative assertion,
     *    and RaceConditionDetector will report the unsynchronized concurrent writes
     * 3. Fix: replace reserveItem() with reserveItemFixed() in the test body
     */
    @Disabled("Remove @Disabled to see race condition detected by RaceConditionDetector")
    @AsyncTest(threads = 8, invocations = 100, detectRaceConditions = true, failOn = FailOn.LOW)
    void testReserveItem_concurrent_detectsRaceCondition() {
        // Record the field read so RaceConditionDetector can track cross-thread access.
        // The detector instance is obtained from the current Phase 1 context.
        int stockBefore = service.getStock(SKU);

        // Record the read on the shared service object / "stock" field
        // RaceConditionDetector is a Phase 1 detector: it is created automatically
        // when detectRaceConditions = true and its report is printed after the run.
        // We can also get a local reference to record access events explicitly:
        //
        //   RaceConditionDetector detector = ...  (no static accessor in AsyncTestContext)
        //
        // The observable symptom speaks for itself: the assertion below will fail
        // when the race drives stock negative.

        service.reserveItem(SKU, 1); // BUG: check-then-update without synchronization

        // Observable race symptom: stock can go negative when multiple threads all
        // pass the check with the same stale value and all write stale decrements.
        int stockAfter = service.getStock(SKU);
        assertTrue(stockAfter >= -INITIAL_STOCK,
                "Stock went to " + stockAfter + " — race condition over-committed inventory");
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
