package se.deversity.asynctest.example.service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-page hit counter that tracks request counts for each URL path.
 *
 * BUG: increment() performs a non-atomic "read, add 1, write" sequence
 * on a volatile long field. Under concurrency, two threads can read the
 * same value simultaneously, both compute value+1, and both write back
 * the same result — silently losing one of the increments.
 *
 * volatile guarantees visibility (every read sees the latest write) but
 * NOT atomicity of compound operations. The read-modify-write is three
 * separate memory operations with a race window between them.
 *
 * AtomicityValidator flags the compound operation on "count" as a
 * check-then-act / TOCTOU violation.
 *
 * FIX: Use LongAdder per page (highest throughput) or replace the
 * volatile long with an AtomicLong and call incrementAndGet().
 */
public class HitCounterService {

    private final ConcurrentHashMap<String, long[]> counts = new ConcurrentHashMap<>();

    /**
     * Records one hit for the given page path.
     *
     * BUG: The three steps (read counts[page], add 1, write counts[page])
     * are NOT atomic. Two concurrent threads can interleave as follows:
     *
     *   Thread A reads count = 5
     *   Thread B reads count = 5   (same value!)
     *   Thread A writes count = 6
     *   Thread B writes count = 6  (increment lost!)
     */
    public void increment(String page) {
        long[] cell = counts.computeIfAbsent(page, k -> new long[]{0L});
        cell[0] = cell[0] + 1;   // BUG: non-atomic read + write on shared array element
    }

    /**
     * Returns the current hit count for the given page.
     */
    public long getCount(String page) {
        long[] cell = counts.get(page);
        return cell == null ? 0L : cell[0];
    }

    /**
     * Resets the counter for the given page to zero.
     */
    public void reset(String page) {
        counts.remove(page);
    }
}
