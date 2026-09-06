package se.deversity.asynctest.diagnostics;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * The set of locks the calling thread currently holds, as far as the library can know it.
 *
 * <p><strong>Why this exists.</strong> A detector that watches a shared instance can ask
 * {@link Thread#holdsLock(Object)} about that instance and nothing else, because that is the only
 * lock it can name. So {@code synchronized (theInstance)} is recognised as correct guarding and
 * every other guard - a {@code ReentrantLock}, a private lock object, the enclosing service's own
 * monitor - looks identical to no guard at all. That was the single remaining cause of every
 * false positive the accuracy evals pinned.
 *
 * <p>Declaring a lock here closes that gap. What the detectors then compute is the classic Eraser
 * lockset: for each tracked instance, the intersection of the locks held at every recorded access.
 * While that intersection is non-empty some lock consistently protects the instance, and the
 * sharing is reported only once it becomes empty. Guard-on-self falls out as one member of the
 * set rather than a special case.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * try (var held = AsyncTestContext.holdingLock(cacheLock)) {
 *     cacheLock.lock();
 *     try {
 *         detector.recordAccess(sharedCache, "cache", Thread.currentThread());
 *         sharedCache.put(k, v);
 *     } finally {
 *         cacheLock.unlock();
 *     }
 * }
 * }</pre>
 *
 * <h2>What it does not do</h2>
 *
 * <p>Without the agent, nothing is discovered automatically: a {@code synchronized} block on an
 * object other than the tracked instance emits no callback the library can observe, so a lock only
 * enters this set when the test declares it.
 *
 * <p>With the agent attached, locks arrive on their own. Since 1.9.6 the weaver instruments
 * {@code MONITORENTER} and {@code MONITOREXIT} and routes them here through
 * {@code TelemetryRegistry.monitorEntered}, so a plain {@code synchronized} block in instrumented
 * code counts as a held lock with no declaration at all. With {@code collections=true} the woven
 * {@code Lock.lock()}/{@code unlock()} call sites feed the same stack through
 * {@code AgentLockHooks}, read-write views resolved to their owner and read views marked shared,
 * so a {@link java.util.concurrent.locks.ReentrantLock} needs no declaration either. What still
 * does: a lock acquired only inside code the weaver never sees.
 *
 * <p>An undeclared lock the agent cannot see is invisible and the finding stands, which is the safe
 * direction: the library would rather ask you to verify synchronization that exists than stay
 * silent about one that does not.
 *
 * <p>Deliberately not fed from the other detectors' lock-recording APIs
 * ({@code LockLeakDetector.recordLockAcquired} and friends). Those detectors only exist when
 * enabled, so routing through them would make one detector's verdict depend on whether an
 * unrelated detector is switched on - the same code and configuration producing a finding or not
 * depending on a flag somewhere else is worse than reporting consistently.
 *
 * <p>Locks are identified by {@link System#identityHashCode}, as tracked instances are everywhere
 * else in this package. A collision would make one lock look like another and could only hide a
 * finding, never invent one.
 *
 * @since 1.9.6
 */
public final class HeldLocks {

    /** Shared empty result: this thread holds nothing, or the intersection has collapsed. */
    static final int[] NONE = new int[0];

    /** Depth cap. Beyond this the frame stops growing and the deepest locks go unrecorded. */
    private static final int MAX_DEPTH = 64;

    /**
     * Per-thread lock stack. Not an {@code InheritableThreadLocal}: a lock held by the thread that
     * spawned a worker is not held by the worker, and inheriting it would invent guarding.
     */
    private static final ThreadLocal<Frame> FRAMES = ThreadLocal.withInitial(Frame::new);

    private HeldLocks() {
    }

    /** A declared lock region. Closing it releases exactly the lock it opened. */
    public interface Guard extends AutoCloseable {
        /** Ends the region. Never throws, so it is safe in a try-with-resources on any path. */
        @Override
        void close();
    }

    /**
     * Declares that the calling thread holds {@code lock} until the returned guard is closed.
     *
     * @param lock the lock object; {@code null} is ignored and yields a no-op guard
     * @return a guard to close when the lock is released, intended for try-with-resources
     */
    public static Guard holding(@Nullable Object lock) {
        if (lock == null) {
            return () -> { };
        }
        acquired(lock);
        return new SingleRelease(lock);
    }

    /**
     * Declares that the calling thread has acquired {@code lock}.
     *
     * <p>For callers whose acquire and release are too far apart for {@link #holding(Object)}.
     * Every call must be paired with {@link #released(Object)} on the same thread; an unpaired
     * acquire leaves the lock in the set for the rest of the invocation and can hide findings,
     * which is why the scoped form is the one to reach for first.
     *
     * @param lock the lock object; {@code null} is ignored
     */
    public static void acquired(@Nullable Object lock) {
        acquired(lock, false);
    }

    /**
     * Declares that the calling thread has acquired {@code lock}, in shared or exclusive mode.
     *
     * <p>A shared acquisition is a read lock: it keeps writers out, and nothing else. It therefore
     * guards a read and never a write, which is the one distinction the lockset has to keep, or a
     * write made under a read lock would look protected by the very lock that permits it. Mapping
     * both views of a {@link java.util.concurrent.locks.ReentrantReadWriteLock} to the one lock
     * object, with this flag telling them apart, is what lets a reader holding the read view and
     * a writer holding the write view share a lockset.
     *
     * @param lock   the lock object; {@code null} is ignored
     * @param shared {@code true} for a read lock, {@code false} for exclusive ownership
     * @since 1.9.8
     */
    public static void acquired(@Nullable Object lock, boolean shared) {
        if (lock != null) {
            FRAMES.get().push(lock, shared);
        }
    }

    /**
     * Declares that the calling thread has released {@code lock}.
     *
     * <p>Releases the most recent matching acquisition, so reentrant acquisition works and a
     * non-LIFO release order does not corrupt the set. A release with no matching acquire is
     * ignored rather than throwing: this is diagnostic bookkeeping and must never be the reason a
     * user's test fails.
     *
     * @param lock the lock object; {@code null} is ignored
     */
    public static void released(@Nullable Object lock) {
        released(lock, false);
    }

    /**
     * Declares that the calling thread has released {@code lock}, matching the mode it was
     * acquired in, so a thread that holds both views of a read-write lock releases the right one.
     *
     * @param lock   the lock object; {@code null} is ignored
     * @param shared the mode passed to {@link #acquired(Object, boolean)}
     * @since 1.9.8
     */
    public static void released(@Nullable Object lock, boolean shared) {
        if (lock != null) {
            FRAMES.get().pop(lock, shared);
        }
    }

    /**
     * {@return whether the calling thread currently holds {@code lock} by declaration}
     *
     * <p>Says nothing about monitors the thread holds without declaring them; use
     * {@link Thread#holdsLock(Object)} for that.
     *
     * @param lock the lock object
     */
    public static boolean holds(@Nullable Object lock) {
        return lock != null && FRAMES.get().indexOf(lock) >= 0;
    }

    /**
     * {@return whether the calling thread currently holds any lock this class knows about}
     *
     * <p>{@link #holds(Object)} answers the question a detector asks about a named lock: was this
     * access guarded by <em>that</em> one. This asks the question a detector asks about the
     * thread: is it inside a critical section at all. Sleeping while holding a lock is a bug
     * whichever lock it is, so naming one would be the wrong shape.
     *
     * <p>Reads only the calling thread's own frame, so it is a plain field read with no
     * synchronisation and nothing to leak.
     */
    public static boolean anyHeld() {
        return FRAMES.get().depth > 0;
    }

    /**
     * {@return the most recently acquired lock this thread still holds, or {@code null}}
     *
     * <p>The innermost one rather than any one: a detector reporting a sleep under a lock wants
     * the lock the code was actually inside, and that is the last one pushed.
     *
     * <p>Reads only the calling thread's own frame, so it is a plain array read.
     */
    public static @Nullable Object topHeld() {
        Frame frame = FRAMES.get();
        return frame.depth == 0 ? null : frame.locks[frame.depth - 1];
    }

    /**
     * Clears the calling thread's declared locks.
     *
     * <p>Called from {@code AsyncTestContext.uninstall()} so the set cannot outlive the invocation
     * that built it. A leak here would carry one round's locks into the next and silence real
     * findings, which is the same hazard the ThreadLocal symmetry rule exists for.
     */
    public static void clear() {
        FRAMES.remove();
    }

    /**
     * {@return a value identifying the exact set of locks this thread currently holds, or 0 for
     * none}
     *
     * <p>For callers that cannot carry a lockset with them. The agent's telemetry path is the
     * case that needs it: the producer runs on the worker thread, where the set is known, while
     * the analysis runs later on a drain thread, where it is not, and the ring buffer between
     * them is deliberately allocation-free so a set cannot be passed through it.
     *
     * <p>Two accesses holding the same locks produce the same value regardless of the order they
     * took them, which is what lets the drain side ask "was this field always covered by the same
     * locks" without ever seeing the locks. It is a weaker question than the intersection
     * {@link #intersect} computes: a thread holding {@code {A, B}} and one holding {@code {A}}
     * are genuinely both protected by {@code A}, but their values differ and the field is
     * reported. That direction is the safe one, and the detectors say which model produced a
     * finding.
     */
    public static long lockFingerprint() {
        return lockFingerprint(false);
    }

    /**
     * {@return a value identifying the locks this thread holds that guard an access of the given
     * kind, or 0 for none}
     *
     * <p>For a write, a lock held in shared mode is left out: a read lock admits other readers and
     * guards nothing a writer does. For a read every held lock counts.
     *
     * <p>Unlike the fingerprint alone, the set behind this value is recoverable: the first time a
     * thread computes it for a new set, the members are registered so that a consumer on another
     * thread can intersect sets instead of comparing digests. See {@link #members(long)}.
     *
     * @param forWrite whether the access being recorded is a write
     * @since 1.9.8
     */
    public static long lockFingerprint(boolean forWrite) {
        return FRAMES.get().registeredFingerprint(forWrite);
    }

    /**
     * {@return the identity hashes behind a fingerprint produced by {@link #lockFingerprint(boolean)},
     * or {@code null} when the fingerprint is not one this registry has seen}
     *
     * <p>This is what turns the agent path's equality test into the intersection the owner-aware
     * path always computed: a thread holding {@code {A, B}} and one holding {@code {A}} are both
     * covered by {@code A}, which only a consumer that can see the members can tell. The registry
     * is bounded; once full, new sets stay unregistered and a consumer falls back to treating the
     * fingerprint as one opaque lock, which is the equality model this replaces. A caller must not
     * modify the returned array.
     *
     * @param fingerprint a value from {@link #lockFingerprint(boolean)}
     * @since 1.9.8
     */
    public static int @Nullable [] members(long fingerprint) {
        return fingerprint == 0L ? NONE : LocksetRegistry.members(fingerprint);
    }

    /**
     * Forgets every registered lockset. Called when the telemetry registry starts a run: every
     * consumer resolves members as events arrive, so nothing from an earlier run needs them.
     *
     * @since 1.9.8
     */
    public static void forgetRegisteredLocksets() {
        LocksetRegistry.clear();
    }

    /**
     * {@return a value identifying the locks this thread holds, counting {@code self}'s own
     * monitor when it is held, or 0 for none}
     *
     * <p>The variant for detectors that track a specific object and keep per-access state rather
     * than a running intersection. Folding the instance's own monitor in here is what keeps
     * {@code synchronized (theInstance)} recognised without any declaration, exactly as it is for
     * the intersection model.
     *
     * @param self the tracked instance, or {@code null} to ask about declared locks only
     */
    public static long lockFingerprint(@Nullable Object self) {
        return lockFingerprint(self, false);
    }

    /**
     * {@return a value identifying the locks this thread holds that guard an access of the given
     * kind on {@code self}, or 0 for none}
     *
     * <p>The self-aware counterpart of {@link #lockFingerprint(boolean)}: {@code self}'s own
     * monitor joins the set when this thread holds it, and a lock held in shared mode is left out
     * for a write. Two threads writing under the same read lock therefore share no fingerprint,
     * which is the point - a read lock admits every other reader and guards nothing a writer does
     * (#500).
     *
     * @param self     the instance being accessed, whose own monitor counts as a lock on it
     * @param forWrite whether the access being recorded is a write
     * @since 1.11.2
     */
    public static long lockFingerprint(@Nullable Object self, boolean forWrite) {
        int selfHash = self != null && Thread.holdsLock(self) ? System.identityHashCode(self) : 0;
        return FRAMES.get().fingerprint(selfHash, forWrite);
    }

    /**
     * Intersects {@code candidate} with the locks this thread holds right now.
     *
     * <p>Returns {@code null} rather than the input when nothing dropped out, so the caller can
     * skip its compare-and-set entirely on the common path where the guarding is consistent.
     *
     * @param candidate the locks common to every previous access, or {@code null} for none yet
     * @param self      the tracked instance, whose own monitor counts as held when it is
     * @return the new intersection, which is {@code candidate} itself when nothing dropped out
     */
    static int[] intersect(int @Nullable [] candidate, Object self) {
        return intersect(candidate, self, true);
    }

    /**
     * Intersects {@code candidate} with the locks this thread holds right now that guard an access
     * of the given kind: every held lock for a read, only exclusively held ones for a write.
     *
     * @param candidate the locks common to every previous access, or {@code null} for none yet
     * @param self      the tracked instance, whose own monitor counts as held when it is
     * @param forWrite  whether the access is a write, which a shared lock does not guard
     * @return the new intersection, which is {@code candidate} itself when nothing dropped out
     */
    static int[] intersect(int @Nullable [] candidate, Object self, boolean forWrite) {
        Frame frame = FRAMES.get();
        boolean selfHeld = Thread.holdsLock(self);
        int selfHash = selfHeld ? System.identityHashCode(self) : 0;

        if (candidate == null) {
            return frame.snapshot(selfHeld, selfHash, forWrite);
        }
        if (candidate.length == 0) {
            return candidate;
        }

        int kept = 0;
        // Stays null while every candidate lock is still held, which is both the common case and
        // the allocation-free one: a consistently guarded instance never copies its set again.
        int[] survivors = null;
        for (int hash : candidate) {
            boolean stillHeld = (selfHeld && hash == selfHash) || frame.containsHash(hash, forWrite);
            if (stillHeld) {
                if (survivors != null) {
                    survivors[kept] = hash;
                }
                kept++;
            } else if (survivors == null) {
                // First drop: copy what survived so far into a fresh array, leaving the shared
                // candidate untouched for any thread reading it concurrently.
                survivors = new int[candidate.length - 1];
                System.arraycopy(candidate, 0, survivors, 0, kept);
            }
        }
        if (survivors == null) {
            return candidate;
        }
        if (kept == 0) {
            return NONE;
        }
        return kept == survivors.length ? survivors : Arrays.copyOf(survivors, kept);
    }

    /** One thread's lock stack. Confined to its own thread, so it needs no synchronization. */
    private static final class Frame {
        private Object[] locks = new Object[8];
        private int[] hashes = new int[8];
        private boolean[] shared = new boolean[8];
        private int depth;

        /**
         * Cached per-mode fingerprints, recomputed lazily after a push or pop.
         *
         * <p>An access records the fingerprint and nothing changes between two accesses under the
         * same locks, so the digest, and the registration that goes with it, are paid once per
         * lock transition rather than once per field instruction.
         */
        private long readFingerprint;
        private long writeFingerprint;
        private boolean fingerprintsValid;
        private int registeredGeneration;

        void push(Object lock, boolean isShared) {
            if (depth == MAX_DEPTH) {
                return;
            }
            if (depth == locks.length) {
                locks = Arrays.copyOf(locks, locks.length * 2);
                hashes = Arrays.copyOf(hashes, hashes.length * 2);
                shared = Arrays.copyOf(shared, shared.length * 2);
            }
            locks[depth] = lock;
            hashes[depth] = System.identityHashCode(lock);
            shared[depth] = isShared;
            depth++;
            fingerprintsValid = false;
        }

        void pop(Object lock, boolean isShared) {
            int at = indexOf(lock, isShared);
            if (at < 0) {
                // A release in the other mode still refers to this lock: a caller that acquired
                // without a mode and releases with one, or the reverse, must not leak an entry.
                at = indexOf(lock);
                if (at < 0) {
                    return;
                }
            }
            System.arraycopy(locks, at + 1, locks, at, depth - at - 1);
            System.arraycopy(hashes, at + 1, hashes, at, depth - at - 1);
            System.arraycopy(shared, at + 1, shared, at, depth - at - 1);
            depth--;
            locks[depth] = null;
            fingerprintsValid = false;
        }

        /**
         * {@return the topmost index holding {@code lock}, or -1}
         *
         * <p>Identity, not {@code equals}: a lock is the object whose monitor is held, and two
         * objects that compare equal are two different monitors. Matching by {@code equals} here
         * would let one lock stand in for another and report consistent guarding that never
         * happened, so the Error Prone suggestion is wrong for this comparison.
         */
        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
        int indexOf(Object lock) {
            for (int i = depth - 1; i >= 0; i--) {
                if (locks[i] == lock) {
                    return i;
                }
            }
            return -1;
        }

        /** {@return the topmost index holding {@code lock} in the given mode, or -1} */
        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
        int indexOf(Object lock, boolean isShared) {
            for (int i = depth - 1; i >= 0; i--) {
                if (locks[i] == lock && shared[i] == isShared) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * {@return a commutative digest of the held hashes, or 0 when nothing is held}
         *
         * <p>Commutative on purpose: two threads that took the same two locks in opposite orders
         * are equally protected, and an order-sensitive digest would call that a race. The count
         * is folded in so that a set and a strict subset cannot collide by summing alike.
         */
        long fingerprint(int extraHash) {
            return fingerprint(extraHash, false);
        }

        /** {@return the digest over the locks that guard an access of the given kind} */
        long fingerprint(int extraHash, boolean forWrite) {
            boolean addExtra = extraHash != 0 && !containsHash(extraHash, forWrite);
            int size = addExtra ? 1 : 0;
            long sum = 0L;
            long xor = 0L;
            for (int i = 0; i < depth; i++) {
                if (forWrite && shared[i]) {
                    continue;
                }
                sum += hashes[i];
                xor ^= hashes[i];
                size++;
            }
            if (size == 0) {
                return 0L;
            }
            if (addExtra) {
                sum += extraHash;
                xor ^= extraHash;
            }
            long value = (sum * 0x9E3779B97F4A7C15L) ^ (xor << 1) ^ ((long) size << 48);
            // 0 is reserved for "nothing held", so a set that digests to it borrows another bit.
            return value == 0L ? 1L : value;
        }

        /**
         * {@return the per-mode fingerprint, registering its members the first time this frame
         * computes it since the last lock transition}
         */
        long registeredFingerprint(boolean forWrite) {
            int generation = LocksetRegistry.generation();
            if (!fingerprintsValid || registeredGeneration != generation) {
                readFingerprint = fingerprint(0, false);
                writeFingerprint = fingerprint(0, true);
                fingerprintsValid = true;
                registeredGeneration = generation;
                if (readFingerprint != 0L && !LocksetRegistry.isRegistered(readFingerprint)) {
                    LocksetRegistry.register(readFingerprint, snapshot(false, 0, false));
                }
                if (writeFingerprint != 0L && !LocksetRegistry.isRegistered(writeFingerprint)) {
                    LocksetRegistry.register(writeFingerprint, snapshot(false, 0, true));
                }
            }
            return forWrite ? writeFingerprint : readFingerprint;
        }

        boolean containsHash(int hash) {
            return containsHash(hash, false);
        }

        /** {@return whether {@code hash} is held in a mode that guards the given access kind} */
        boolean containsHash(int hash, boolean forWrite) {
            for (int i = 0; i < depth; i++) {
                if (hashes[i] == hash && !(forWrite && shared[i])) {
                    return true;
                }
            }
            return false;
        }

        /** {@return a fresh array of the held hashes, plus {@code selfHash} when held} */
        int[] snapshot(boolean selfHeld, int selfHash) {
            return snapshot(selfHeld, selfHash, false);
        }

        /** {@return a fresh array of the hashes guarding the given access kind, plus self} */
        int[] snapshot(boolean selfHeld, int selfHash, boolean forWrite) {
            boolean selfAlreadyIn = selfHeld && containsHash(selfHash, forWrite);
            int size = selfHeld && !selfAlreadyIn ? 1 : 0;
            for (int i = 0; i < depth; i++) {
                if (!(forWrite && shared[i])) {
                    size++;
                }
            }
            if (size == 0) {
                return NONE;
            }
            int[] out = new int[size];
            int at = 0;
            for (int i = 0; i < depth; i++) {
                if (!(forWrite && shared[i])) {
                    out[at] = hashes[i];
                    at++;
                }
            }
            if (at < size) {
                out[at] = selfHash;
            }
            return out;
        }
    }

    /** Guard for exactly one acquisition, so closing twice cannot pop somebody else's lock. */
    private static final class SingleRelease implements Guard {
        private final Object lock;
        private boolean open = true;

        SingleRelease(Object lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (open) {
                open = false;
                released(lock);
            }
        }
    }
}
