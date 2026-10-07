package com.example.corpus;

import com.google.common.util.concurrent.AtomicLongMap;
import org.apache.commons.lang3.mutable.MutableInt;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.OperationHistory;
import se.deversity.asynctest.SequentialSpec;

import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Linearizability checking on library code from the corpus's own dependencies, in both directions
 * (invariant 10, #932): the #924 prototype was only ever tried on JDK subjects and a hand-written
 * lost update.
 *
 * <p><strong>Correct twins</strong> run Guava's {@link AtomicLongMap} the way a user would: each
 * worker draws its operations from the replay seed ({@code generate}), all workers start each one
 * together, and the history reports through the run ({@code verifiedAgainst}). Their silence holds on
 * any core count.
 *
 * <p><strong>Broken twins</strong> are the bug users write with commons-lang3's {@link MutableInt},
 * whose javadoc says it is not thread-safe: a read-modify-write through its own API,
 * {@code getValue()} then {@code setValue(read + 1)}, alone and inside a {@link ConcurrentHashMap},
 * a safe container of unsafe values. A {@code rendezvous()} between the two halves makes every
 * worker read before any writes. Without it the twin was not observable: measured on 2026-10-06,
 * a bare {@code MutableInt.incrementAndGet()} under drawn scenarios was caught in 10 of 10 runs on a
 * 16-core machine and in 0 of 6 runs pinned to 2 cores, the size of a CI runner. A check that
 * observes histories cannot make a race a few instructions wide happen; the rendezvous decides when
 * the halves run, which is the condition the lost update needs, so the twin fails on every machine.
 */
class LinearizabilityLibraryPairsTest {

    private static final String KEY = "k";
    private static final int KEYS = 3;

    /** One counter: increment returns the count after it, get the count. */
    private static final SequentialSpec<long[]> COUNTER = SequentialSpec.of(
            () -> new long[1], long[]::clone,
            (state, op, arg) -> op.equals("increment") ? ++state[0] : state[0]);

    private static AsyncTestConfig.Builder rounds() {
        // Platform threads: virtual threads released from a rendezvous are mounted one after
        // another, which serialises bodies this small.
        return AsyncTestConfig.builder().threads(8).invocations(30).useVirtualThreads(false)
                .timeoutMs(120_000).licenseMockMode(true);
    }

    /** Draws {@code operations} operations one at a time, every worker released together before each. */
    private static void drawAligned(OperationHistory<?> history, int operations) {
        for (int i = 0; i < operations; i++) {
            AsyncTestContext.rendezvous();
            history.generate(1);
        }
    }

    /** The read-modify-write users write over {@link MutableInt}: get, then set. */
    private static long incrementByHand(MutableInt counter) {
        int read = counter.getValue();
        AsyncTestContext.rendezvous();
        counter.setValue(read + 1);
        return read + 1L;
    }

    /**
     * The finding must be the verdict, not the size bound: a round over the 64 operations one
     * search takes is also reported under this name, and would let a broken twin pass for the wrong
     * reason. The correct twins stay under it: 8 workers drawing 6 operations is 48, and 8 drawing
     * 15 over 3 keys is about 40 per key.
     */
    private static void assertNotLinearizable(AsyncFindings findings) {
        findings.assertReported("Linearizability");
        assertTrue(findings.violationsFrom("Linearizability").stream()
                        .anyMatch(v -> v.message().contains("is not linearizable")),
                findings.violationsFrom("Linearizability").toString());
    }

    @Test
    void guavaAtomicLongMapIsLinearizable() throws Throwable {
        OperationHistory<AtomicLongMap<String>> history = OperationHistory.of(AtomicLongMap::<String>create)
                .operation("increment", random -> null, (map, unused) -> map.incrementAndGet(KEY))
                .operation("get", random -> null, (map, unused) -> map.get(KEY))
                .verifiedAgainst(COUNTER);
        AsyncFindings findings = AsyncTestRunner.run(rounds().build(), () -> drawAligned(history, 6));
        findings.assertNotReported("Linearizability");
    }

    @Test
    void aMutableIntIncrementedByHandIsNot() throws Throwable {
        OperationHistory<MutableInt> history = OperationHistory.of(MutableInt::new).verifiedAgainst(COUNTER);
        AsyncFindings findings = AsyncTestRunner.run(rounds().invocations(3).build(), () -> {
            MutableInt counter = history.subject();
            history.call("increment", null, () -> incrementByHand(counter));
        });
        assertNotLinearizable(findings);
    }

    @Test
    void guavaAtomicLongMapIsLinearizable_keyByKey() throws Throwable {
        OperationHistory<AtomicLongMap<Integer>> history = OperationHistory.of(AtomicLongMap::<Integer>create)
                .operation("increment", random -> random.nextInt(KEYS), (map, key) -> map.incrementAndGet((Integer) key))
                .operation("get", random -> random.nextInt(KEYS), (map, key) -> map.get((Integer) key))
                .verifiedAgainst(COUNTER, (op, key) -> key);
        AsyncFindings findings = AsyncTestRunner.run(rounds().build(), () -> drawAligned(history, 15));
        findings.assertNotReported("Linearizability");
    }

    @Test
    void aConcurrentMapOfMutableIntsIncrementedByHandIsNot_keyByKey() throws Throwable {
        OperationHistory<ConcurrentHashMap<Integer, MutableInt>> history =
                OperationHistory.of(ConcurrentHashMap<Integer, MutableInt>::new).verifiedAgainst(COUNTER, (op, key) -> key);
        AsyncFindings findings = AsyncTestRunner.run(rounds().invocations(3).build(), () -> {
            ConcurrentHashMap<Integer, MutableInt> map = history.subject();
            Integer key = 7;
            history.call("increment", key, () -> incrementByHand(map.computeIfAbsent(key, k -> new MutableInt())));
        });
        assertNotLinearizable(findings);
    }
}
