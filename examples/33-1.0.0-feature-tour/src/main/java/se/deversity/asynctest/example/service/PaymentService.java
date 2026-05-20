package se.deversity.asynctest.example.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Toy payment service used by the 1.0.0 feature-tour tests. Intentionally
 * has thread-safety problems so the detectors light up.
 */
public class PaymentService {

    private final Map<String, Long> balances = new HashMap<>();     // BUG: shared, unsynchronized
    private final Random jitter;                                    // seeded; deterministic given a seed

    public PaymentService(long seed) {
        this.jitter = new Random(seed);
    }

    public CompletableFuture<Long> chargeAsync(String account, long amount) {
        return CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(jitter.nextInt(3)); } catch (InterruptedException ignored) {}
            long current = balances.getOrDefault(account, 0L);
            // BUG: get-then-put across threads; race condition
            balances.put(account, current + amount);
            return current + amount;
        });
    }

    public long balance(String account) {
        return balances.getOrDefault(account, 0L);
    }
}
