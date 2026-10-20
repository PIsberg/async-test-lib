package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.WorkerSlot;
import se.deversity.asynctest.report.Violation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A woven iteration is a read of the collection it walks (#1052).
 *
 * <p>One worker iterates a shared {@code ArrayList} while another adds to it: the concurrent
 * modification bug, in code that records nothing. The woven {@code Iterable.iterator()} told only
 * the synchronized-wrapper detector, so {@code SharedCollectionDetector} saw a single writer and
 * reported nothing ({@code AgentFeedOverlapAuditTest} row 1). The bodies make the woven calls by
 * hand: {@code AgentCollectionHooks.iterableIterator} in place of {@code iterator()} and
 * {@code collectionAdd} in place of {@code add}.
 */
class IterationIsAReadTest {

    private static List<Violation> sharedCollectionFindings(boolean guarded) throws Throwable {
        List<Object> list = new ArrayList<>(List.of(1, 2, 3, 4));
        CyclicBarrier lineUp = new CyclicBarrier(2);
        try (AsyncFindings findings = AsyncFindings.collect()) {
            try {
                AsyncTestRunner.run(AsyncTestConfig.builder().threads(2).invocations(10)
                        .includes(new DetectorType[] {DetectorType.SHARED_COLLECTIONS}).timeoutMs(60_000)
                        .licenseMockMode(true).build(), () -> {
                    int slot = WorkerSlot.current();
                    lineUp.await(10, TimeUnit.SECONDS);
                    if (guarded) {
                        synchronized (list) {
                            act(slot, list);
                        }
                    } else {
                        act(slot, list);
                    }
                });
            } catch (Throwable thrown) { // NOPMD - the unguarded race may throw CME; the findings are the result
                // ConcurrentModificationException from the iteration
            }
            return findings.violationsFrom("SharedCollection");
        }
    }

    private static void act(int slot, List<Object> list) {
        if (slot == 0) {
            try {
                Iterator<Object> it = AgentCollectionHooks.iterableIterator(list);
                while (it.hasNext()) {
                    it.next();
                }
            } catch (java.util.ConcurrentModificationException e) {
                // the race this test is about; the run goes on so its findings are delivered
            }
        } else {
            AgentCollectionHooks.collectionAdd(list, slot);
        }
    }

    @Test
    void aListIteratedByOneWorkerWhileAnotherAddsIsReported() throws Throwable {
        List<Violation> found = sharedCollectionFindings(false);
        assertFalse(found.isEmpty(), "iteration and an add on one ArrayList from two workers, unguarded, must"
                + " be reported by SharedCollectionDetector: the iteration is the read beside the write");
    }

    @Test
    void theSameUnderTheListsOwnMonitorIsNot() throws Throwable {
        List<Violation> found = sharedCollectionFindings(true);
        assertTrue(found.isEmpty(), "the same iteration and add under synchronized (list) is correct: " + found);
    }
}
