package se.deversity.asynctest.example.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Inventory management service that reserves stock for orders.
 *
 * BUG: {@link #reserveItem(String, int)} uses a non-atomic check-then-update pattern.
 * It reads the current stock level, checks whether there is enough, and then writes the
 * updated level as three separate, unguarded steps. Under concurrent load, multiple
 * threads can all pass the check before any of them writes the new value — they all
 * decrement the same initial stock, driving the balance well below zero.
 *
 * RaceConditionDetector (activated by {@code detectRaceConditions = true}) is recording-fed:
 * it analyses only the cross-thread field accesses it is handed. See
 * {@link #observeStockAccess(BiConsumer, BiConsumer)} for the seam that hands them over.
 *
 * FIX: Replace the three-step read-check-write sequence with a single atomic
 * compare-and-swap loop on an {@link AtomicInteger}, or use {@code synchronized}
 * to make the compound operation indivisible.
 */
public class InventoryService {

    /**
     * Maps SKU to current available stock level.
     *
     * BUG: Individual {@code int} values held in plain boxed integers are not
     * atomically updatable via CAS. The int is extracted, checked, decremented,
     * and stored back as separate steps — a classic TOCTOU window.
     */
    private final ConcurrentHashMap<String, Integer> stock = new ConcurrentHashMap<>();

    /**
     * Tracks how many reservations were granted. Used in tests to assert that total
     * reservations never exceed the initial stock level.
     */
    private final AtomicInteger totalReserved = new AtomicInteger(0);

    /**
     * Called with the backing map and the SKU before the stock level is read. A no-op unless a
     * test installs a hook through {@link #observeStockAccess(BiConsumer, BiConsumer)}.
     */
    private volatile BiConsumer<Object, String> onStockRead = (map, sku) -> { };

    /** Called with the backing map and the SKU before the decremented level is written. */
    private volatile BiConsumer<Object, String> onStockWrite = (map, sku) -> { };

    /**
     * Loads initial stock for a SKU. Thread-safe: called only during setup.
     */
    public void loadStock(String sku, int quantity) {
        stock.put(sku, quantity);
    }

    /**
     * Attempts to reserve {@code quantity} units of {@code sku}.
     *
     * BUG: The check ({@code current >= quantity}) and the update
     * ({@code stock.put(sku, current - quantity)}) are not atomic. Between the
     * read of {@code current} and the {@code put}, another thread may have already
     * decremented the stock. Both threads see sufficient stock, both decrement,
     * and the resulting balance can go negative — silently over-committing inventory.
     *
     * @param sku      the product identifier
     * @param quantity the number of units to reserve
     * @return {@code true} if reservation succeeded; {@code false} if insufficient stock
     */
    public boolean reserveItem(String sku, int quantity) {
        onStockRead.accept(stock, sku);
        Integer current = stock.get(sku);           // STEP 1: read
        if (current == null || current < quantity) { // STEP 2: check
            return false;
        }
        // --- RACE WINDOW ---
        // Another thread may decrement stock between STEP 2 and STEP 3.
        // Both threads see current >= quantity, both proceed to STEP 3.
        onStockWrite.accept(stock, sku);
        stock.put(sku, current - quantity);          // STEP 3: write (over-committed!)
        totalReserved.addAndGet(quantity);
        return true;
    }

    /**
     * Installs the hooks RaceConditionDetector needs. No-ops by default, so production
     * behaviour is unchanged whether or not a test is watching.
     *
     * <p>The calls sit <em>inside</em> {@link #reserveItem(String, int)}, on either side of the
     * race window, because that is the only place the read and the write are separable. Recording
     * around the call from the test body would report one read and one write per body execution
     * with the check nowhere in between, which is not the shape the detector is looking for.
     *
     * @param onRead  called with the backing map and the SKU before the stock level is read
     * @param onWrite called with the backing map and the SKU before the new level is written
     */
    public void observeStockAccess(BiConsumer<Object, String> onRead, BiConsumer<Object, String> onWrite) {
        this.onStockRead = onRead;
        this.onStockWrite = onWrite;
    }

    /**
     * Fixed version: uses an atomic compare-and-swap loop so the check and update
     * are indivisible. If another thread modifies the stock between the read and the
     * CAS, the CAS fails and the loop retries with the fresh value — no race window.
     *
     * @param sku      the product identifier
     * @param quantity the number of units to reserve
     * @return {@code true} if reservation succeeded; {@code false} if insufficient stock
     */
    public boolean reserveItemFixed(String sku, int quantity) {
        while (true) {
            Integer current = stock.get(sku);
            if (current == null || current < quantity) {
                return false;
            }
            // Atomic CAS: only succeeds if stock still equals 'current'
            if (stock.replace(sku, current, current - quantity)) {
                totalReserved.addAndGet(quantity);
                return true;
            }
            // Another thread changed stock; retry with the new value
        }
    }

    /**
     * Returns the current stock level for a SKU, or 0 if unknown.
     */
    public int getStock(String sku) {
        return stock.getOrDefault(sku, 0);
    }

    /**
     * Returns the total units reserved across all successful calls.
     */
    public int getTotalReserved() {
        return totalReserved.get();
    }
}
