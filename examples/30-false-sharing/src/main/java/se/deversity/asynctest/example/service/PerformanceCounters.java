package se.deversity.asynctest.example.service;

/**
 * Global performance counters updated by every request-handling thread.
 *
 * BUG: {@code requestCount}, {@code errorCount}, and {@code latencySum} are
 * three consecutive {@code long} fields (8 bytes each, total 24 bytes).
 * On a typical 64-byte cache line they all fit within the same line,
 * starting at the object header offset (~16 bytes).
 *
 * When Thread A writes {@code requestCount} and Thread B writes
 * {@code errorCount} simultaneously, both writes invalidate the same
 * cache line on all CPU cores. Every write forces a cache-coherence
 * round-trip ("cache ping-pong") even though the threads are updating
 * completely independent fields.
 *
 * Under high concurrency this can degrade throughput by an order of
 * magnitude compared to properly padded counters.
 *
 * FIX: Annotate each hot field with {@code @jdk.internal.vm.annotation.Contended}
 * (requires {@code --add-opens}) so the JVM pads each field to its own
 * cache line. Alternatively, pad manually with 7 dummy {@code long} fields
 * between each hot counter, or redesign using {@link java.util.concurrent.atomic.LongAdder}
 * which performs its own internal striping.
 */
public class PerformanceCounters {

    // All three fields fit on the same 64-byte cache line — false sharing hotspot
    public volatile long requestCount;   // offset ~16
    public volatile long errorCount;     // offset ~24
    public volatile long latencySum;     // offset ~32

    public void recordRequest(long latencyMs) {
        requestCount++;
        latencySum += latencyMs;
    }

    public void recordError(long latencyMs) {
        requestCount++;
        errorCount++;
        latencySum += latencyMs;
    }

    public double averageLatency() {
        long count = requestCount;
        return count == 0 ? 0.0 : (double) latencySum / count;
    }

    public long getErrorRate() {
        long count = requestCount;
        return count == 0 ? 0 : (errorCount * 100) / count;
    }
}
