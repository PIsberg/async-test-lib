package se.deversity.asynctest.example.service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts requests and bytes served, from every request thread.
 *
 * <p>Unlike its siblings, this is not a correctness bug. {@link AtomicLong} is entirely
 * thread-safe, and {@link AtomicLong#incrementAndGet()} always returns the right number.
 * The finding is a <strong>throughput advisory</strong>, and it is the reason
 * {@link LongAdder} exists.
 *
 * <p>{@code incrementAndGet()} is a CAS loop: read the value, compute the successor, publish
 * it if nobody else has, otherwise start over. Under low contention it succeeds first try.
 * Under high contention every thread is retrying against a single cache line that is
 * ping-ponging between cores, and throughput collapses well before the CPUs are busy —
 * the work is coherence traffic, not counting.
 *
 * <p>{@link LongAdder} spreads the same count across per-thread cells and adds them up in
 * {@code sum()}. It gives up the one thing {@code AtomicLong} offers that matters: an
 * accurate value read atomically <em>with</em> the update. If you only ever report the total
 * — metrics, statistics, counters — you were never using that guarantee.
 */
public final class RequestMetricsService {

    /** The advisory's subject: one hot counter, every thread, read-modify-write per request. */
    private final AtomicLong requestCount = new AtomicLong();

    /** The recommendation: same count, contention spread across cells. */
    private final LongAdder bytesServed = new LongAdder();

    /**
     * Correct, and slow under load. Every caller CASes the same cache line.
     */
    public long recordRequest() {
        return requestCount.incrementAndGet();
    }

    /**
     * The fix for a counter you only ever total up. No return value on purpose — that is
     * exactly the guarantee being traded away.
     */
    public void recordBytes(long bytes) {
        bytesServed.add(bytes);
    }

    /**
     * Keep {@code AtomicLong} when you need this: the new value, atomically, as part of the
     * update. Sequence numbers, ID allocation and "did I cross the limit?" checks all do.
     */
    public long nextSequenceNumber() {
        return requestCount.incrementAndGet();
    }

    public long requestCount() {
        return requestCount.get();
    }

    public long bytesServed() {
        return bytesServed.sum();
    }
}
