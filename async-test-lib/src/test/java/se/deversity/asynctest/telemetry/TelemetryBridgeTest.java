package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import se.deversity.asynctest.diagnostics.AtomicityValidator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryBridgeTest {

    // Synthetic worker-thread ids. Using ids that are not real live threads makes the
    // filtering assertions deterministic regardless of the current thread's id.
    private static final long WORKER_A = 900_001L;
    private static final long WORKER_B = 900_002L;
    private static final long NON_WORKER = 700_007L;

    @BeforeEach
    @AfterEach
    void cleanup() {
        // Never leave the registry running between tests (mirrors TelemetryRegistryTest).
        TelemetryRegistry.stop();
    }

    @Test
    void forwardsWorkerEventsAndDropsNonWorkerEvents() {
        AtomicityValidator av = new AtomicityValidator();
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B))) {

            // A mixed read/write on the SAME identifier across two worker threads is the
            // signal AtomicityValidator surfaces as a cross-thread hazard.
            bridge.onEvent(WORKER_A, "com.example.Account.balance", false); // read
            bridge.onEvent(WORKER_B, "com.example.Account.balance", true);  // write

            AtomicityValidator.AtomicityReport report = av.analyzeAtomicity();
            assertTrue(report.hasIssues(),
                    "Worker-thread events must be forwarded to the detector");
            assertTrue(report.unsafeFieldAccesses.stream()
                            .anyMatch(s -> s.contains("com.example.Account.balance")),
                    "The forwarded field must appear in the cross-thread report");
        }

        // A second detector fed only by non-worker events must see nothing.
        AtomicityValidator filtered = new AtomicityValidator();
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(filtered, Set.of(WORKER_A, WORKER_B))) {
            bridge.onEvent(NON_WORKER, "com.example.Account.balance", false);
            bridge.onEvent(NON_WORKER, "com.example.Account.balance", true);
        }
        assertFalse(filtered.analyzeAtomicity().hasIssues(),
                "Events from non-worker threads must be dropped");
    }

    @Test
    void endToEndThroughRegistry() throws InterruptedException {
        AtomicityValidator av = new AtomicityValidator();
        try (TelemetryBridge ignored =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B))) {

            // Publish agent-style events; the registry drain thread (1 ms) forwards them
            // through the bridge into the detector.
            TelemetryRegistry.recordAccess(WORKER_A, "com.example.Order.total", false);
            TelemetryRegistry.recordAccess(WORKER_B, "com.example.Order.total", true);

            assertTrue(awaitIssues(av, 2, TimeUnit.SECONDS),
                    "Detector should observe the drained accesses within the drain window");
            assertTrue(av.analyzeAtomicity().totcouRaces.stream()
                            .anyMatch(s -> s.contains("com.example.Order.total")),
                    "The drained field must be attributed as a cross-thread race");
        }
    }

    @Test
    void closeIsIdempotentAndStopsForwarding() {
        AtomicityValidator av = new AtomicityValidator();
        TelemetryBridge bridge =
                TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B));

        bridge.close();
        // Second/extra close() and the deactivate() alias must not throw.
        bridge.close();
        bridge.deactivate();

        // After close, further events (even from worker threads) are not forwarded.
        bridge.onEvent(WORKER_A, "com.example.Cart.items", false);
        bridge.onEvent(WORKER_B, "com.example.Cart.items", true);

        assertFalse(av.analyzeAtomicity().hasIssues(),
                "A closed bridge must not forward events to the detector");
    }

    @Test
    void attributesEventsToOriginatingThreadNotDrainThread() {
        // The callback runs on the drain thread; if attribution used Thread.currentThread()
        // both events would collapse to one thread id and no cross-thread hazard would be
        // reported. Distinct worker ids proving up as "2 threads" confirms the explicit
        // thread-id overload is used.
        AtomicityValidator av = new AtomicityValidator();
        try (TelemetryBridge bridge =
                     TelemetryBridge.activate(av, Set.of(WORKER_A, WORKER_B))) {
            bridge.onEvent(WORKER_A, "com.example.Ledger.entry", true);
            bridge.onEvent(WORKER_B, "com.example.Ledger.entry", true);

            assertTrue(av.analyzeAtomicity().totcouRaces.stream()
                            .anyMatch(s -> s.contains("2 threads")),
                    "Events must be attributed to their two originating worker threads");
        }
    }

    @Test
    void activateRejectsNullDetectorAndNullWorkerSet() {
        assertThrows(NullPointerException.class,
                () -> TelemetryBridge.activate(null, Set.of(WORKER_A)));
        assertThrows(NullPointerException.class,
                () -> TelemetryBridge.activate(new AtomicityValidator(), null));
    }

    /**
     * Pins that a bridge closing does not silence the bridge that currently holds the slot.
     *
     * <p>The registry holds one callback, so two {@code @AsyncTest} runs in one JVM take it from
     * each other. That trade-off is documented and accepted — the loser under-reports. What was
     * not acceptable was the teardown: closing cleared the callback unconditionally, so when the
     * loser finished first it wiped the <em>winner's</em> registration. The winner then received
     * no further events for the rest of its run, its detectors saw nothing, and its test passed
     * green with nothing logged, because the drain thread was still running and so the
     * agent-absent hint could not fire either.
     *
     * <p>The assertion is deliberately about the second bridge still being live after the first
     * one closes, because that is the user-visible property: a test that was observing keeps
     * observing.
     */
    @Test
    void closingASupersededBridgeLeavesTheCurrentHoldersCallbackInPlace() {
        AtomicityValidator first = new AtomicityValidator();
        AtomicityValidator second = new AtomicityValidator();

        TelemetryBridge superseded = TelemetryBridge.activate(first, Set.of(WORKER_A));
        TelemetryBridge current = TelemetryBridge.activate(second, Set.of(WORKER_A));

        // The second activation took the slot; closing the first must not touch it.
        superseded.close();

        assertFalse(TelemetryRegistry.clearCallbackIf(superseded),
                "The superseded bridge must not have been holding the callback after the second "
                        + "activation replaced it.");
        assertTrue(TelemetryRegistry.clearCallbackIf(current),
                "The current holder's callback must survive an earlier bridge closing. It did "
                        + "not, which means close() cleared the slot unconditionally and the run "
                        + "that legitimately owned it has gone blind while still passing green.");
    }

    private static boolean awaitIssues(AtomicityValidator av, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (av.analyzeAtomicity().hasIssues()) {
                return true;
            }
            Thread.sleep(10);
        }
        return av.analyzeAtomicity().hasIssues();
    }
}
