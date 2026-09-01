package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.diagnostics.AtomicityValidator;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dogfoods {@link TelemetryBridge}'s forwarding and its close-is-a-hard-stop contract with
 * {@code @AsyncTest}.
 *
 * <p>Why this needed a seam first: every public factory on the bridge ends in
 * {@code TelemetryRegistry.start(bridge)}, so obtaining a bridge installs it as the process-wide
 * drain callback. A test that put one under contention would hijack the telemetry of the run
 * driving it, which is why this class was on the blocked list. {@code TelemetryBridge.detached}
 * builds one the registry never sees, and the last assertion here checks that claim rather than
 * trusting it: the registry's running state must be exactly what it was before this class ran.
 *
 * <p>What is pinned. {@code active} is documented as written by {@code close()} on a test thread
 * and read by {@code onEvent} on the drain thread, so a closed bridge forwards nothing even before
 * the registry has swapped the callback. That is the direction that matters: a bridge that keeps
 * forwarding after close feeds a detector belonging to a round that has already been analysed, and
 * the finding lands against the wrong test. The opposite direction is pinned too, since a bridge
 * that quietly drops events makes a detector go silent.
 *
 * <p>Each round gets its own bridge and its own counting validator, so the two directions are
 * counted separately and a round cannot borrow another round's deliveries.
 */
class TelemetryBridgeCloseDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 100;

    /** Counts what the bridge forwarded. The validator's own bookkeeping is not the subject here. */
    private static final class CountingValidator extends AtomicityValidator {
        private final AtomicInteger delivered = new AtomicInteger();

        @Override
        public void recordFieldAccessUnderLocks(String fieldName, @Nullable Object value,
                                                boolean isWrite, long threadId, long lockFingerprint,
                                                int ownMonitor, int methodMonitor,
                                                boolean volatileField, int constantTag, int identity,
                                                int storedIdentity) {
            delivered.incrementAndGet();
        }
    }

    private static final Map<Integer, CountingValidator> OPEN_VALIDATORS = new ConcurrentHashMap<>();
    private static final Map<Integer, TelemetryBridge> OPEN_BRIDGES = new ConcurrentHashMap<>();
    private static final AtomicInteger OPEN_SEQUENCE = new AtomicInteger();

    private static final Map<Integer, CountingValidator> CLOSED_VALIDATORS = new ConcurrentHashMap<>();
    private static final Map<Integer, TelemetryBridge> CLOSED_BRIDGES = new ConcurrentHashMap<>();
    private static final AtomicInteger CLOSED_SEQUENCE = new AtomicInteger();

    private static boolean registryWasRunning;

    @BeforeAll
    static void rememberGlobalState() {
        registryWasRunning = TelemetryRegistry.isRunning();
    }

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 20_000)
    void anOpenBridgeForwardsEveryWorkersEvent() {
        int round = OPEN_SEQUENCE.getAndIncrement() / THREADS;
        CountingValidator validator = OPEN_VALIDATORS.computeIfAbsent(round,
                ignored -> new CountingValidator());
        TelemetryBridge bridge = OPEN_BRIDGES.computeIfAbsent(round,
                ignored -> TelemetryBridge.detached(validator, threadId -> true));

        publishOne(bridge, round);
    }

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 20_000)
    void aClosedBridgeForwardsNothing() {
        int round = CLOSED_SEQUENCE.getAndIncrement() / THREADS;
        CountingValidator validator = CLOSED_VALIDATORS.computeIfAbsent(round,
                ignored -> new CountingValidator());
        TelemetryBridge bridge = CLOSED_BRIDGES.computeIfAbsent(round,
                ignored -> TelemetryBridge.detached(validator, threadId -> true));

        // Every worker closes before its own event, and close() is documented idempotent, so all
        // THREADS race the same close and no event may be forwarded from any of them.
        bridge.close();
        publishOne(bridge, round);
    }

    private static void publishOne(TelemetryBridge bridge, int round) {
        bridge.onEvent(Thread.currentThread().threadId(),
                "se.deversity.dogfood.Subject.field" + round, true, 0L, false,
                Integer.MIN_VALUE, 0, false, 0, 0, 0);
    }

    @AfterAll
    static void bothDirectionsHeldAndTheRegistryWasNeverTouched() {
        assertEquals(ROUNDS, OPEN_BRIDGES.size(), "rounds shared an open bridge");
        assertEquals(ROUNDS, CLOSED_BRIDGES.size(), "rounds shared a closed bridge");

        int forwarded = 0;
        for (CountingValidator validator : OPEN_VALIDATORS.values()) {
            forwarded += validator.delivered.get();
        }
        assertEquals(THREADS * ROUNDS, forwarded,
                "an open bridge dropped events, so the detector behind it goes quiet on accesses "
                        + "that did happen");

        int leaked = 0;
        for (CountingValidator validator : CLOSED_VALIDATORS.values()) {
            leaked += validator.delivered.get();
        }
        assertEquals(0, leaked,
                "a closed bridge forwarded " + leaked + " events, so a detector belonging to a "
                        + "round that has already been analysed is still being fed and the finding "
                        + "lands against the wrong test");

        assertEquals(registryWasRunning, TelemetryRegistry.isRunning(),
                "a detached bridge changed the process-wide telemetry registry, which is the one "
                        + "thing detaching exists to avoid");
    }
}
