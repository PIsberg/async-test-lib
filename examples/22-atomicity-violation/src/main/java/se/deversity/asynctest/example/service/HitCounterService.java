package se.deversity.asynctest.example.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

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

    /** Called with the value read at the top of the compound operation. */
    private volatile LongConsumer onCountRead = value -> { };

    /** Called with the value stored at the bottom of the compound operation. */
    private volatile LongConsumer onCountWrite = value -> { };

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
        long current = cell[0];       // BUG step 1: read
        onCountRead.accept(current);
        long next = current + 1;      // BUG step 2: add, on a value another thread may already have moved
        cell[0] = next;               // BUG step 3: write, clobbering whatever landed in between
        onCountWrite.accept(next);
    }

    /**
     * Installs the hooks AtomicityValidator needs. No-ops by default, so production behaviour is
     * unchanged whether or not a test is watching.
     *
     * <p>The calls sit <em>inside</em> {@link #increment(String)}, at the read and at the write,
     * because those are the two ends of the window the validator is looking for. Recording around
     * the call from the test body would report two extra reads through {@link #getCount(String)}
     * and never the value that was actually stored.
     *
     * @param onRead  called with the value read, before the add
     * @param onWrite called with the value stored, after the write
     */
    public void observeCountAccess(LongConsumer onRead, LongConsumer onWrite) {
        this.onCountRead = onRead;
        this.onCountWrite = onWrite;
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
