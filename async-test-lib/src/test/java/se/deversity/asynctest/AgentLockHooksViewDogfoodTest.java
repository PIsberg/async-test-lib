package se.deversity.asynctest;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.diagnostics.HeldLocks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Dogfoods {@link AgentLockHooks}'s read/write view registry with {@code @AsyncTest}.
 *
 * <p>Why this exists: a woven {@code readLock()} call has to remember that the {@code Lock} it just
 * handed back is a <em>shared</em> view of its owning {@code ReentrantReadWriteLock}. If that
 * registration is lost, {@code noteAcquired} falls through to the plain-lock branch and records the
 * read lock as an ordinary exclusive guard. A read lock admits other readers and guards nothing a
 * writer does, so the effect is that one thread's read hold makes another thread's racing write
 * read as guarded. This class's own contract calls that the one error direction the library must
 * never take, and shared read-write-lock views are already on record as a false-positive root here.
 *
 * <p>The registration is a {@code putIfAbsent} into a synchronized {@code WeakHashMap}, so the only
 * moment it can go wrong is the instant several threads reach a brand new view together. Registering
 * once and reusing it, as a straight-line test does, never reaches that instant and never reaches it
 * again afterwards either: after the first call the entry is present and every later call is a hit.
 *
 * <p>So each round gets its own {@link ReentrantReadWriteLock}, created once and shared by the
 * {@link #THREADS} workers of that round, which puts a fresh registration under the runner's
 * barrier {@link #ROUNDS} times over. The assertions read the effect through
 * {@link HeldLocks#lockFingerprint(boolean)}, which is what every detector consults: a shared hold
 * must fingerprint for a read and vanish for a write, and an exclusive hold must fingerprint for
 * both.
 */
class AgentLockHooksViewDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 150;

    /** Handed out once per round, so every round races a first-ever view registration. */
    private static final Map<Integer, ReentrantReadWriteLock> PER_ROUND = new ConcurrentHashMap<>();

    /**
     * Body executions so far. The runner joins every worker before starting the next round, so
     * dividing by {@link #THREADS} names the round the calling worker is in.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final AtomicInteger BODY_EXECUTIONS = new AtomicInteger();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, useVirtualThreads = false, timeoutMs = 20_000)
    void aSharedViewRegisteredUnderContentionStillReadsAsShared() {
        BODY_EXECUTIONS.incrementAndGet();
        int round = SEQUENCE.getAndIncrement() / THREADS;
        ReentrantReadWriteLock rwl =
                PER_ROUND.computeIfAbsent(round, ignored -> new ReentrantReadWriteLock());

        assertEquals(0L, HeldLocks.lockFingerprint(false),
                "a worker entered the body already holding something; the baseline is not clean");

        // Both views are claimed here, so all THREADS race the same two first-ever registrations.
        Lock readView = AgentLockHooks.readLock(rwl);
        Lock writeView = AgentLockHooks.writeLock(rwl);

        // Shared hold. All THREADS genuinely hold this at once, which is the point of a read lock.
        AgentLockHooks.lock(readView);
        try {
            assertNotEquals(0L, HeldLocks.lockFingerprint(false),
                    "the read hold was not recorded at all");
            assertEquals(0L, HeldLocks.lockFingerprint(true),
                    "a shared read hold was recorded as guarding a write, which makes another "
                            + "thread's racing write read as guarded");
        } finally {
            AgentLockHooks.unlock(readView);
        }

        assertEquals(0L, HeldLocks.lockFingerprint(false), "the read hold outlived its unlock");

        // Exclusive hold. The acquisitions serialise, but the registration above did not.
        AgentLockHooks.lock(writeView);
        try {
            assertNotEquals(0L, HeldLocks.lockFingerprint(true),
                    "an exclusive write hold was not recorded as guarding a write");
        } finally {
            AgentLockHooks.unlock(writeView);
        }

        assertEquals(0L, HeldLocks.lockFingerprint(false), "the write hold outlived its unlock");
    }

    @AfterAll
    static void everyRoundRacedItsOwnRegistration() {
        assertEquals(THREADS * ROUNDS, BODY_EXECUTIONS.get(),
                "a worker never ran, so a round's registration was not contended");
        assertEquals(ROUNDS, PER_ROUND.size(),
                "rounds shared a lock, so fewer first-ever registrations were raced than rounds");
    }
}
