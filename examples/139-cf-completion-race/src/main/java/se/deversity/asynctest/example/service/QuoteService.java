package se.deversity.asynctest.example.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Asks several pricing providers for a quote and publishes the answer into a future.
 *
 * <p>The shape is the ordinary "first responder wins" fan-out: one {@link CompletableFuture} that
 * every provider completes, whoever gets there first. What the shape hides is that
 * {@link CompletableFuture#complete(Object)} returns {@code false} for everyone else and drops
 * what they were carrying - including, when a provider fails, the failure.
 */
public final class QuoteService {

    /** A quote from one provider. Deterministic so the example asserts on values, not timing. */
    public String quoteFrom(String provider) {
        return provider + ":" + (provider.length() * 100);
    }

    /** The single completion slot the buggy shape shares between providers. */
    public CompletableFuture<String> newResultSlot() {
        return new CompletableFuture<>();
    }

    /**
     * The fixed shape's choice step: every provider answered into its own future, so all the
     * answers are still here to choose from.
     *
     * @param quotes one quote per provider, none of them discarded
     * @return the cheapest quote
     */
    public String cheapestOf(List<String> quotes) {
        return quotes.stream().min(String::compareTo).orElseThrow();
    }
}
