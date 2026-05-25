package se.deversity.asynctest.example.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BUGGY service that demonstrates fire-and-forget CompletableFuture chains.
 *
 * BUG: processAsync() returns a CompletableFuture that the caller never joins.
 *      The chain contains a step that randomly throws — the exception is
 *      swallowed because no .exceptionally() handler is attached and the
 *      future is never awaited.
 *
 * FIX: Either join/get the returned future, or attach .exceptionally(ex -> ...)
 *      so that failures are always observed and recorded.
 */
public class PipelineService {

    private final AtomicInteger callCount = new AtomicInteger(0);

    /**
     * Processes the input asynchronously. The returned future is often
     * discarded by the caller — exceptions inside the chain are lost.
     */
    public CompletableFuture<String> processAsync(String input) {
        return CompletableFuture.supplyAsync(() -> input)
                .thenApply(s -> {
                    // BUG: throws on every 3rd call; caller won't see it
                    if (callCount.incrementAndGet() % 3 == 0) {
                        throw new RuntimeException("Pipeline step failed for: " + s);
                    }
                    return s.toUpperCase();
                })
                .thenApply(s -> "[processed] " + s);
        // BUG: no .exceptionally() and caller discards the future
    }
}
