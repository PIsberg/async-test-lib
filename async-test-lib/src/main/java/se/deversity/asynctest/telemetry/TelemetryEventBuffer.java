package se.deversity.asynctest.telemetry;

import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * MPSC (multi-producer, single-consumer) lock-free ring buffer for field-access telemetry.
 *
 * <p>The baseline approach of recording detector events via synchronous writes to thread-local
 * lists changes scheduling behaviour: the overhead of capturing a stack trace or acquiring a
 * lock slows the recording thread enough to prevent the race from manifesting — a classic
 * <em>Heisenbug</em> / observer effect.
 *
 * <p>This buffer eliminates that observer effect by keeping the producer path allocation-free
 * and lock-free. Each {@link #publish} call:
 * <ol>
 *   <li>Claims a ring-buffer slot with a single {@link java.util.concurrent.atomic.AtomicLong#getAndIncrement()}</li>
 *   <li>Writes the event fields into the pre-allocated {@link AccessEvent} at that slot</li>
 *   <li>Publishes the slot's sequence number via a VarHandle release fence, signalling
 *       the single consumer that the slot is ready</li>
 * </ol>
 *
 * <p>The single consumer (typically a background drain thread created by
 * {@link TelemetryRegistry}) calls {@link #drain(DrainCallback)} to process all
 * available events without blocking producers.
 *
 * <p><strong>Capacity:</strong> must be a power of two. When the buffer fills, producers
 * apply backpressure by spin-waiting in {@link #publish} until the single consumer drains a
 * slot — events are never overwritten. If the buffer stays full with no consumer progress
 * for a sustained period (no drain thread is running any more), {@link #publish} drops the
 * event instead of spinning forever — see {@link #droppedCount()}. For typical async-test
 * invocation sizes the buffer is never full and neither path is taken.
 *
 * @since 1.6.0
 */
public final class TelemetryEventBuffer {

    /** Callback invoked by the consumer for each drained event. */
    @FunctionalInterface
    public interface DrainCallback {
        /**
         * Invoked for a drained event.
         *
         * @param threadId    the thread ID
         * @param targetField the target field
         * @param isWrite     true if it was a write access, false otherwise
         */
        void onEvent(long threadId, @Nullable String targetField, boolean isWrite);
    }

    /**
     * Pre-allocated event slot. Fields are written by producers; {@code sequence} is the
     * last field written and acts as the publication signal for the consumer.
     */
    static final class AccessEvent {
        volatile long sequence = -1;
        long threadId;
        @Nullable String targetField;
        boolean isWrite;
    }

    private static final VarHandle SEQ_VH;

    static {
        try {
            SEQ_VH = MethodHandles.lookup()
                    .findVarHandle(AccessEvent.class, "sequence", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final int bufferSize;
    private final int mask;
    private final AccessEvent[] ringBuffer;

    // Producers claim slots with a compareAndSet (so a full-buffer give-up can abandon
    // the attempt without claiming); only the sequence number stored in the slot itself
    // signals to the consumer that the slot is fully written.
    private final java.util.concurrent.atomic.AtomicLong producerCursor =
            new java.util.concurrent.atomic.AtomicLong(-1);

    // Consumer cursor — only touched by the single drain thread.
    private volatile long consumerCursor = -1;

    /**
     * How long {@link #publish} keeps retrying against a full buffer with no consumer
     * progress before dropping the event. A live drain runs every millisecond, so any
     * healthy full-buffer episode resolves about three orders of magnitude faster than
     * this bound; reaching it means no consumer exists any more.
     */
    private static final long FULL_BUFFER_GIVE_UP_NANOS = 1_000_000_000L;

    // Events abandoned by publish() after FULL_BUFFER_GIVE_UP_NANOS of zero consumer
    // progress. See droppedCount().
    private final java.util.concurrent.atomic.AtomicLong droppedEvents =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Creates a TelemetryEventBuffer.
     *
     * @param capacityPowerOfTwo ring-buffer capacity; must be a power of two (e.g. 1024, 4096)
     */
    public TelemetryEventBuffer(int capacityPowerOfTwo) {
        if (capacityPowerOfTwo <= 0 || Integer.bitCount(capacityPowerOfTwo) != 1) {
            throw new IllegalArgumentException(
                    "capacityPowerOfTwo must be a positive power of two, got: " + capacityPowerOfTwo);
        }
        this.bufferSize = capacityPowerOfTwo;
        this.mask = capacityPowerOfTwo - 1;
        this.ringBuffer = new AccessEvent[capacityPowerOfTwo];
        for (int i = 0; i < capacityPowerOfTwo; i++) {
            ringBuffer[i] = new AccessEvent();
        }
    }

    /**
     * Publishes a field-access event from any producer thread.
     *
     * <p>This method is designed for the hot recordAccess path: it performs no allocation,
     * no lock acquisition, and no blocking. The claim is a compare-and-set rather than an
     * unconditional increment so that a full buffer can be abandoned without claiming a
     * sequence: the consumer treats the first unpublished sequence as the end of the
     * stream, so a claimed-but-never-published slot would silently truncate every later
     * event and wedge every later producer.
     *
     * @param threadId    {@code Thread.currentThread().threadId()}
     * @param targetField field or method identifier (e.g.
     *                    {@code "com.example.OrderService.setCount"} as produced by the
     *                    agent's {@code @Advice.Origin("#t.#m")} pattern)
     * @param isWrite     {@code true} for a write access, {@code false} for a read
     */
    public void publish(long threadId, String targetField, boolean isWrite) {
        long fullSinceNanos = 0L;
        int spins = 0;
        for (;;) {
            long claimed = producerCursor.get();
            long seq = claimed + 1;
            if (seq - consumerCursor > bufferSize) {
                // Buffer full. Spin briefly — a live consumer drains every millisecond, so
                // this clears in microseconds — but give up after a bound: with no consumer
                // left (registry stopped, drain thread dead) nothing ever advances the
                // cursor, and an unbounded spin here turns every instrumented thread in the
                // JVM into a busy-spinning hostage. Dropping after a sustained full-buffer
                // period with zero progress is strictly better than hanging the caller.
                if (fullSinceNanos == 0L) {
                    fullSinceNanos = System.nanoTime();
                } else {
                    spins++;
                    if ((spins & 255) == 0
                            && System.nanoTime() - fullSinceNanos > FULL_BUFFER_GIVE_UP_NANOS) {
                        droppedEvents.incrementAndGet();
                        return;
                    }
                }
                Thread.onSpinWait();
                continue;
            }
            fullSinceNanos = 0L; // not full — a later full episode times itself afresh
            if (producerCursor.compareAndSet(claimed, seq)) {
                // The full-check above used the same `claimed` this CAS validated, and
                // consumerCursor only ever advances, so the claimed slot is free: its
                // previous occupant (sequence seq - bufferSize) is already consumed.
                int index = (int) (seq & mask);
                AccessEvent event = ringBuffer[index];
                // Write data fields before the sequence, which acts as the publication signal.
                event.threadId = threadId;
                event.targetField = targetField;
                event.isWrite = isWrite;
                // Release fence: consumer will not observe the event until this store completes.
                SEQ_VH.setRelease(event, seq);
                return;
            }
        }
    }

    /**
     * Number of events {@link #publish} dropped after the full-buffer wait bound expired,
     * i.e. after ~1s of a full buffer with no consumer progress. Zero in any healthy
     * pipeline; nonzero means events were being published with no live drain thread.
     *
     * @return the number of events dropped since this buffer was created
     * @since 1.7.3
     */
    public long droppedCount() {
        return droppedEvents.get();
    }

    /**
     * Drains all available events to {@code callback} from the consumer thread.
     *
     * <p>Must be called from a single thread only (single-consumer contract).
     *
     * @param callback invoked once per available event in publication order
     * @return number of events drained
     */
    public int drain(DrainCallback callback) {
        int count = 0;
        long localCursor = consumerCursor;
        long next = localCursor + 1;
        while (true) {
            int index = (int) (next & mask);
            AccessEvent event = ringBuffer[index];
            // Acquire fence: ensure we see the data written before the sequence store.
            long publishedSeq = (long) SEQ_VH.getAcquire(event);
            if (publishedSeq < next) {
                break; // slot not yet published by any producer
            }
            callback.onEvent(event.threadId, event.targetField, event.isWrite);
            localCursor = next;
            next++;
            count++;
        }
        // Publish the cursor advance only once, at the end. Publishing it after every
        // event would release a backpressured producer mid-drain, which could race
        // ahead and overwrite a slot the drain loop is about to read — yielding a
        // duplicated event count on slow runners.
        if (count > 0) {
            consumerCursor = localCursor;
        }
        return count;
    }

    /**
     * Returns the number of events published so far (monotonically increasing).
     *
     * @return the number of events published since this buffer was created
     */
    public long publishedCount() {
        return producerCursor.get() + 1;
    }
}
