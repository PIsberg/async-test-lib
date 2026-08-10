package se.deversity.asynctest.example.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A stock ledger with an unsynchronised counter, plus an async reservation call.
 *
 * <p>The bug is the ordinary one: {@code available--} is a read, a decrement and a write, so two
 * threads can both read 10 and both write 9. A sequential test never sees it.
 */
public class InventoryService {

    /** BUG: mutated from several threads with no synchronisation and no atomic type. */
    private int available;

    private volatile boolean warmedUp;

    public InventoryService(int initialStock) {
        this.available = initialStock;
    }

    /** Decrements the stock. Deliberately not thread-safe. */
    public void reserveOne() {
        available--;
    }

    public int available() {
        return available;
    }

    /** Flips a flag from another thread, so a test has something to poll for. */
    public void warmUpAsync(Executor executor) {
        executor.execute(() -> warmedUp = true);
    }

    public boolean isWarmedUp() {
        return warmedUp;
    }

    /** An async call, so the example can show how to await one from inside a test body. */
    public CompletableFuture<String> reserveAsync(String sku) {
        return CompletableFuture.supplyAsync(() -> "reserved:" + sku);
    }
}
