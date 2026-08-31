package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncTest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Dogfoods {@link TelemetryEventBuffer}, the multi-producer / single-consumer ring buffer every
 * woven field access publishes into, with {@code @AsyncTest}.
 *
 * <p>Why this exists: {@code TelemetryEventBufferTest} covers the same buffer with a fixed thread
 * pool that starts eight producers and runs them once. That shape never forces the producers to
 * claim adjacent slots at the same instant, which is the only moment the CAS claim, the release
 * fence on {@code sequence}, and the consumer's "first unpublished sequence ends the stream" rule
 * can disagree. {@code @AsyncTest} supplies that instant {@link #ROUNDS} times over: all
 * {@link #THREADS} producers are released from the runner's barrier together and go straight into
 * {@code publish}.
 *
 * <p>Each event carries a globally unique tag in {@code constantTag} and the producer's own thread
 * id, so the drained set can be compared with the published set as a whole map. That comparison
 * fails three separate ways, which is what makes it worth making:
 *
 * <ul>
 *   <li>a missing tag means an event was lost — a slot claimed and never published, or a
 *       consumer cursor advanced past a slot that was not yet written;</li>
 *   <li>a tag drained twice means a producer overwrote a slot the drain loop was still reading;</li>
 *   <li>a tag whose drained thread id is not its producer's means the event tore — the sequence
 *       became visible before the data fields it was supposed to fence.</li>
 * </ul>
 */
class TelemetryEventBufferDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 250;
    private static final int EVENTS_PER_BODY = 8;
    private static final int CAPACITY = 1024;
    private static final int EXPECTED_EVENTS = THREADS * ROUNDS * EVENTS_PER_BODY;

    private static final TelemetryEventBuffer BUFFER = new TelemetryEventBuffer(CAPACITY);

    private static final AtomicInteger NEXT_TAG = new AtomicInteger();

    /** tag to the thread id that published it. */
    private static final Map<Integer, Long> PUBLISHED = new ConcurrentHashMap<>();

    /** tag to the thread id the consumer read back for it. */
    private static final Map<Integer, Long> DRAINED = new ConcurrentHashMap<>();

    private static final AtomicInteger DUPLICATES = new AtomicInteger();

    private static volatile boolean draining = true;
    private static Thread consumer;

    /**
     * The single consumer. Overrides the widest {@code onEvent} overload because that is the one
     * {@code drain} calls, and the narrower default would drop {@code constantTag} on the floor.
     */
    private static final TelemetryEventBuffer.DrainCallback COLLECT =
            new TelemetryEventBuffer.DrainCallback() {
                @Override
                public void onEvent(long threadId, String targetField, boolean isWrite) {
                    throw new AssertionError("drain must call the widest overload");
                }

                @Override
                public void onEvent(long threadId, String targetField, boolean isWrite,
                                    long lockFingerprint, boolean volatileField, int constantTag,
                                    int identity, boolean afterVolatileRead, int ownMonitor,
                                    int methodMonitor, int storedIdentity) {
                    if (DRAINED.put(constantTag, threadId) != null) {
                        DUPLICATES.incrementAndGet();
                    }
                }
            };

    @BeforeAll
    static void startTheSingleConsumer() {
        consumer = new Thread(() -> {
            while (draining) {
                if (BUFFER.drain(COLLECT) == 0) {
                    Thread.onSpinWait();
                }
            }
        }, "dogfood-telemetry-drain");
        consumer.setDaemon(true);
        consumer.start();
    }

    @AsyncTest(threads = THREADS, invocations = ROUNDS, timeoutMs = 20_000)
    void producersCollideOnTheRingBuffer() {
        long threadId = Thread.currentThread().threadId();
        for (int i = 0; i < EVENTS_PER_BODY; i++) {
            int tag = NEXT_TAG.incrementAndGet();
            PUBLISHED.put(tag, threadId);
            BUFFER.publish(threadId, "dogfood.telemetry.field", true, 0L, false, tag, 0,
                    false, 0, 0, 0);
        }
    }

    @AfterAll
    static void everyEventArrivedOnceAndIntact() throws InterruptedException {
        draining = false;
        consumer.join(10_000);
        assertFalse(consumer.isAlive(), "drain thread did not stop; the final drain would break "
                + "the single-consumer contract");

        // Safe now that the drain thread is joined: still exactly one consumer.
        BUFFER.drain(COLLECT);

        assertEquals(EXPECTED_EVENTS, PUBLISHED.size(),
                "the producers themselves did not run the expected number of times");
        assertEquals(0L, BUFFER.droppedCount(),
                "publish gave up on a full buffer even though a consumer was draining throughout");
        assertEquals(EXPECTED_EVENTS, BUFFER.publishedCount(),
                "producer cursor disagrees with the number of events the producers issued");
        assertEquals(0, DUPLICATES.get(), "an event was drained more than once");
        assertEquals(PUBLISHED, DRAINED,
                "the drained events are not exactly the published events, intact");
    }
}
