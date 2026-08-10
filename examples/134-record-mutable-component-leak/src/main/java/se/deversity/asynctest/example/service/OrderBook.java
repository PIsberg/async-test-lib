package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Publishes order snapshots to downstream consumers.
 *
 * <p>Records are shallowly immutable, and that word does a lot of quiet damage. The record's
 * <em>fields</em> cannot be reassigned. What they point at can be anything, including an
 * {@code ArrayList} that every holder of the record can still mutate:
 *
 * <pre>{@code
 * record Order(String id, List<String> lines) { }
 *
 * var order = new Order("o-1", lines);
 * publish(order);          // handed to three threads
 * lines.add("late-item");  // BUG: they all see it, whenever they happen to look
 * }</pre>
 *
 * <p>The record is doing exactly what it promised. The mistake is on the reader's side: "it's
 * a record, so it's safe to share" is true only when every component is itself immutable.
 * Here the caller keeps a reference to the same list the record holds, so the snapshot is not
 * a snapshot — it is a live view that can change under a consumer mid-read, and
 * {@code ArrayList} offers no thread safety while that happens.
 *
 * <p>The fix is a defensive copy in a compact constructor, which is the one place a record
 * gives you to enforce an invariant. {@code List.copyOf} both copies and makes the result
 * genuinely unmodifiable, so a later {@code add} on the caller's list cannot reach the record
 * and a consumer cannot mutate it either.
 *
 * <p>Worth noting what this costs: one copy per construction. For a snapshot handed to several
 * threads that is a bargain against a data race, and if profiling ever says otherwise, the
 * answer is a persistent collection, not a shared mutable one.
 */
public final class OrderBook {

    /** BUG: the list component is whatever the caller passed, and the caller kept it. */
    public record Order(String id, List<String> lines) {
    }

    /** The fix: a compact constructor copies, so the record owns an unmodifiable component. */
    public record SafeOrder(String id, List<String> lines) {
        public SafeOrder {
            lines = List.copyOf(lines);
        }
    }

    /** Also safe: every component is already immutable, so there is nothing to copy. */
    public record Quote(String symbol, long priceMinor, Map<String, String> tags) {
        public Quote {
            tags = Map.copyOf(tags);
        }
    }

    private final List<Order> published = new ArrayList<>();

    public Order publish(String id, List<String> lines) {
        Order order = new Order(id, lines);
        published.add(order);
        return order;
    }

    public SafeOrder publishSafely(String id, List<String> lines) {
        return new SafeOrder(id, lines);
    }

    public List<Order> published() {
        return published;
    }
}
