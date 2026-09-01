package se.deversity.asynctest.telemetry;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.AsyncTest;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dogfoods the two name sets {@link TelemetryRegistry} keeps, with {@code @AsyncTest}.
 *
 * <p>Why this one needs no seam, unlike the rest of its class. {@code atomicallyManaged} and
 * {@code publishedByVolatile} record static facts the weaver found, and both sets only ever grow.
 * A test that declares names nothing else uses therefore cannot disturb the run driving it: it
 * adds entries no other caller will ever ask about, and removes nothing. The rest of
 * {@code TelemetryRegistry} is a different matter, since it owns a drain executor and a JVM
 * shutdown hook.
 *
 * <p>What is at stake. The weaver emits these declarations from the loading threads, and
 * {@code TelemetryBridge.onEvent} reads them on the drain thread to decide whether an access
 * belongs to a lock-free protocol and should be dropped rather than judged. A declaration lost on
 * the way in means the bridge never learns the field is atomically managed, so the lockset
 * reasoning is applied to a field no lockset can judge and the detector reports a race on correct
 * code. That is a false positive, which this library treats as the one error direction it must
 * never take, so it is worth pinning that a declaration made on any thread is visible from every
 * thread.
 *
 * <p>Each worker declares its own names and immediately reads them back, which catches a
 * declaration that never landed. The {@code @AfterAll} pass then reads every name from a single
 * thread, which catches one that landed somewhere no other thread can see.
 */
class TelemetryRegistryManagedNamesDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 20;

    /**
     * Declarations each worker makes per round.
     *
     * <p>One per worker per round is not enough to matter: the runner joins every worker before
     * the next round, so four adds separated by a join barely contend and a set with no thread
     * safety at all survives them. A loop inside the body puts hundreds of adds against each other
     * inside one round, which is the shape the weaver actually produces as classes load.
     */
    private static final int PER_WORKER = 50;

    private static final int EXPECTED = THREADS * ROUNDS * PER_WORKER;

    /** Unique per declaration, so nothing here collides with a real weaver-emitted name. */
    private static final String ATOMIC_PREFIX = "se.deversity.dogfood.AtomicSubject.field";
    private static final String VOLATILE_PREFIX = "se.deversity.dogfood.VolatileSubject.field";

    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final Set<String> DECLARED_ATOMIC = ConcurrentHashMap.newKeySet();
    private static final Set<String> DECLARED_VOLATILE = ConcurrentHashMap.newKeySet();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 20_000)
    void aDeclarationMadeOnAnyThreadIsVisibleToTheThreadThatMadeIt() {
        for (int i = 0; i < PER_WORKER; i++) {
            int id = NEXT.getAndIncrement();
            String atomic = ATOMIC_PREFIX + id;
            String published = VOLATILE_PREFIX + id;

            TelemetryRegistry.atomicallyManaged(atomic);
            TelemetryRegistry.publishedByVolatile(published);
            DECLARED_ATOMIC.add(atomic);
            DECLARED_VOLATILE.add(published);

            assertTrue(TelemetryRegistry.isAtomicallyManaged(atomic),
                    "a field declared atomically managed did not read back as one on the declaring "
                            + "thread, so the bridge would judge a lock-free field with a lockset "
                            + "and report a race on correct code");
            assertTrue(TelemetryRegistry.isPublishedByVolatile(published),
                    "a field declared published-by-volatile did not read back as one on the "
                            + "declaring thread");
        }
    }

    @AfterAll
    static void everyDeclarationIsVisibleFromAnotherThread() {
        assertEquals(EXPECTED, DECLARED_ATOMIC.size(), "the workers did not declare what was expected");
        assertEquals(EXPECTED, DECLARED_VOLATILE.size(), "the workers did not declare what was expected");

        for (String name : DECLARED_ATOMIC) {
            assertTrue(TelemetryRegistry.isAtomicallyManaged(name),
                    "declaration of " + name + " was lost: it was made under contention and is "
                            + "not visible from another thread, so accesses to a lock-free field "
                            + "would be judged by a lockset that cannot judge them");
        }
        for (String name : DECLARED_VOLATILE) {
            assertTrue(TelemetryRegistry.isPublishedByVolatile(name),
                    "declaration of " + name + " was lost under contention");
        }
    }
}
