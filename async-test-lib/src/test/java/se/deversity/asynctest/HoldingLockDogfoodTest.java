package se.deversity.asynctest;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.diagnostics.HeldLocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Dogfoods {@link AsyncTestContext#holdingLock(Object)}, the way a user declares a lock the
 * detectors cannot see for themselves, with {@code @AsyncTest}.
 *
 * <p>This is the dogfoodable half of {@code AsyncTestContext}. The other half, {@code install} and
 * {@code uninstall}, is not, and deliberately so: {@code install} carries
 * {@code @AICallersOnly({"se.deversity.asynctest.runner.ConcurrencyRunner"})}, and
 * {@code uninstall} clears {@link HeldLocks} as well, so a test calling either from inside a body
 * would be dropping the worker's declared locks mid-round and violating a stated boundary to do
 * it. Invariant 2 already names {@code AsyncTestContextTest} and {@code PerInvocationLifecycleTest}
 * as its gates. {@code holdingLock} by contrast documents itself as safe outside a run, because
 * the declaration is per-thread bookkeeping.
 *
 * <p>What is pinned, and why it is not merely a ThreadLocal test. The fingerprint a declaration
 * produces is registered in a bounded, process-wide lockset registry so that a consumer on another
 * thread can intersect sets rather than compare digests. That registry is shared, so the property
 * worth contention is that nesting is exact under it: closing an inner guard must restore the
 * fingerprint the outer one had, to the value, and closing the outer must leave nothing behind. A
 * declaration that outlived its guard would be intersected into the next round's lockset and could
 * silence a real finding there, which is the failure this library can least afford.
 *
 * <p>Every round shares one outer lock across its workers and gives each worker an inner lock of
 * its own, so the shared object is declared by all {@link #THREADS} at once while the sets they
 * build from it stay distinct.
 */
class HoldingLockDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 150;

    /** Declared by every worker of a round at once. */
    private static final Map<Integer, Object> SHARED_PER_ROUND = new ConcurrentHashMap<>();

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger BODY_EXECUTIONS = new AtomicInteger();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 20_000)
    void nestedDeclarationsUnwindToExactlyWhatTheyFound() {
        BODY_EXECUTIONS.incrementAndGet();
        int round = SEQUENCE.getAndIncrement() / THREADS;
        Object shared = SHARED_PER_ROUND.computeIfAbsent(round, ignored -> new Object());
        Object own = new Object();

        assertEquals(0L, HeldLocks.lockFingerprint(false),
                "a worker entered the body already holding a declaration, so a previous round or "
                        + "another thread leaked one");

        try (HeldLocks.Guard outer = AsyncTestContext.holdingLock(shared)) {
            long withShared = HeldLocks.lockFingerprint(false);
            assertNotEquals(0L, withShared, "declaring a lock recorded nothing");

            try (HeldLocks.Guard inner = AsyncTestContext.holdingLock(own)) {
                assertNotEquals(withShared, HeldLocks.lockFingerprint(false),
                        "the nested declaration did not change the lockset, so a second lock the "
                                + "detectors cannot see would go unrecorded");
            }

            assertEquals(withShared, HeldLocks.lockFingerprint(false),
                    "closing the inner guard did not restore the outer lockset exactly; an access "
                            + "recorded after it would be attributed to the wrong set of locks");
        }

        assertEquals(0L, HeldLocks.lockFingerprint(false),
                "a declaration outlived its guard, and it would be intersected into the next "
                        + "round's lockset and could silence a real finding there");
    }

    @AfterAll
    static void everyRoundRanOnEveryWorker() {
        assertEquals(THREADS * ROUNDS, BODY_EXECUTIONS.get(), "a worker never ran");
        assertEquals(ROUNDS, SHARED_PER_ROUND.size(),
                "rounds shared a lock object, so fewer simultaneous declarations were made "
                        + "than rounds");
    }
}
