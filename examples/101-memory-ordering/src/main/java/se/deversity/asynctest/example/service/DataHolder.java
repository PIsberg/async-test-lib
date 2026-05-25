package se.deversity.asynctest.example.service;

/**
 * Shares data between a producer thread and a consumer thread.
 *
 * <p><strong>Bug:</strong> Both {@code value} and {@code ready} are plain (non-volatile)
 * fields. The Java Memory Model does not guarantee that a consumer thread sees the writes
 * made by the producer thread without a happens-before relationship established by
 * {@code volatile}, {@code synchronized}, or an explicit memory fence. The consumer may
 * see stale values for either field independently.
 *
 * <p><strong>Fix:</strong> Declare both fields {@code volatile}, or protect all reads
 * and writes with {@code synchronized} blocks to establish a happens-before edge.
 */
public class DataHolder {

    // BUG: plain fields — no volatile, no synchronization
    private int value = 0;
    private boolean ready = false;

    /**
     * Called by the producer thread: stores the value and sets the ready flag.
     * The JMM does not guarantee that these writes are seen in order by the consumer.
     */
    public void publish(int v) {
        value = v;
        ready = true;  // BUG: may be reordered before value = v by the JIT/CPU
    }

    /**
     * Called by the consumer thread: returns the value if ready, otherwise -1.
     * May see a stale ready=false even after publish() completed, or stale value.
     */
    public int consume() {
        return ready ? value : -1;
    }

    /** Returns the current ready flag (for tests). */
    public boolean isReady() {
        return ready;
    }

    /** Resets state for a new round. */
    public void reset() {
        value = 0;
        ready = false;
    }
}
