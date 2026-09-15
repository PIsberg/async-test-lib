package se.deversity.asynctest.telemetry;

import se.deversity.asynctest.diagnostics.HeldLocks;
import org.jspecify.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The compare-and-swap spinlocks the woven hooks in {@link TelemetryRegistry} declare (#554, #558).
 *
 * <p>A spinlock is a flag some thread swaps from unlocked to locked, and what it writes before
 * swapping back is as guarded as anything written under a monitor. The flag is either an
 * {@code int} field of a receiver, reached through a {@code VarHandle} or an
 * {@code AtomicIntegerFieldUpdater}, or an {@code AtomicBoolean} or {@code AtomicInteger} that is
 * the lock itself. Each receiver-and-field, or each atomic object, gets one {@link Lock}, so every
 * thread that takes the spinlock declares the same lock and the lockset can intersect on it.
 *
 * <h2>Why a hold is re-confirmed rather than trusted</h2>
 *
 * <p>The weaver sees the acquire and the common releases, but not every release: a
 * {@code getAndSet(0)}, a {@code decrementAndGet()}, a {@code compareAndExchange}, or a release in
 * code it does not weave. A lock that stayed declared after such a release would make every later
 * access on that thread look guarded, and if every thread did the same, a real race would read as
 * consistently locked. That is the one direction this library must never take. So each
 * {@link Lock} is {@link HeldLocks.Revocable}: whenever the thread's lockset is read, the lock is
 * kept only while its flag still reads locked <em>and</em> this thread is the one that last won
 * it. An unobserved release by the holder turns the flag back; an unobserved release followed by
 * another thread's observed acquire changes the holder. Either drops the stale entry before the
 * next access is recorded.
 *
 * <p>The window that remains is narrow and one-sided: another thread's acquire lands between its
 * swap and its holder write while the stale thread records an access. The next round's
 * interleaving closes it; no steady-state code shape keeps it open.
 *
 * <h2>What identifies a lock</h2>
 *
 * <p>The receiver's identity hash and the field, with a weak reference to the receiver so nothing is
 * retained and a collision can be told apart: two live objects that share a hash do not share a
 * lock, and the second one's acquire is simply not declared. That loses a guard, which can only
 * report an access, never hide one.
 */
final class SpinLocks {

    /** The pseudo-field an {@code AtomicBoolean} or {@code AtomicInteger} lock is keyed under. */
    private static final String ATOMIC = "#atomic";

    /**
     * Which field each {@code VarHandle} or {@code AtomicIntegerFieldUpdater} reaches, as
     * {@code declaringClass.field}; the empty string for a handle that was asked and cannot say.
     */
    private static final Map<Object, String> HANDLE_FIELDS = new ConcurrentHashMap<>();

    /**
     * Fields some thread has used as a spinlock flag.
     *
     * <p>Keeps the release check off the access path until a spinlock actually exists: the woven
     * write hook asks this set only when it is non-empty.
     */
    private static final Set<String> SPIN_FIELDS = ConcurrentHashMap.newKeySet();

    /** One lock per flag, so every thread declares the same one. */
    private static final Map<Key, Lock> LOCKS = new ConcurrentHashMap<>();

    /**
     * The map size that triggers the next sweep of collected subjects.
     *
     * <p>An {@code AtomicInteger} counter that passes from 0 to 1 takes a lock entry like a real
     * spinlock does, so a suite that creates many short-lived counters would otherwise keep one
     * dead entry per counter for the life of the JVM. Doubling the threshold after each sweep keeps
     * the sweeps amortised over the entries that caused them.
     */
    private static final AtomicInteger NEXT_SWEEP = new AtomicInteger(4096);

    /** A flag: the identity hash of its receiver or atomic object, and its field. */
    private record Key(int subject, String field) { }

    private SpinLocks() {
    }

    /** Records that {@code handle}, a {@code VarHandle} or updater, reaches {@code field}. */
    static void bound(@Nullable Object handle, @Nullable String field) {
        if (handle != null && field != null) {
            HANDLE_FIELDS.put(handle, field);
        }
    }

    /** {@return whether a write to {@code field} could be the release of a spinlock} */
    static boolean isSpinField(String field) {
        return !SPIN_FIELDS.isEmpty() && SPIN_FIELDS.contains(field);
    }

    /** {@return whether any field has been used as a spinlock flag yet} */
    static boolean anySpinField() {
        return !SPIN_FIELDS.isEmpty();
    }

    /**
     * {@return the field {@code handle} reaches, resolving a handle bound before the agent attached,
     * or {@code null} when it cannot be told}
     *
     * <p>A handle whose class initialised before the attach never ran the woven binding call, so
     * the registry has no name for it. A direct instance-field handle can describe itself: its
     * nominal descriptor names the field and the class it was looked up in, which is unambiguous,
     * unlike guessing from the class's {@code int} fields. Anything else - an adapted handle, a
     * static field, an array element, a hidden class - describes itself as nothing and stays
     * unresolved, and a spinlock through it is not declared.
     */
    static @Nullable String fieldOf(VarHandle handle) {
        String field = HANDLE_FIELDS.get(handle);
        if (field == null) {
            field = describe(handle);
            HANDLE_FIELDS.put(handle, field);
        }
        return field.isEmpty() ? null : field;
    }

    /**
     * {@return the field an updater reaches, or {@code null}}
     *
     * <p>Only the woven binding in the owner's type initializer can say: an updater exposes no
     * field name, so one created before the agent attached stays unresolved.
     */
    static @Nullable String fieldOf(AtomicIntegerFieldUpdater<?> updater) {
        String field = HANDLE_FIELDS.get(updater);
        return field == null || field.isEmpty() ? null : field;
    }

    /** {@return {@code declaringClass.field} for a direct {@code int} instance-field handle, else ""} */
    private static String describe(VarHandle handle) {
        try {
            if (handle.varType() != int.class || handle.coordinateTypes().size() != 1) {
                return "";
            }
            Optional<VarHandle.VarHandleDesc> described = handle.describeConstable();
            if (described.isEmpty()
                    || !ConstantDescs.BSM_VARHANDLE_FIELD.equals(described.get().bootstrapMethod())) {
                return "";
            }
            VarHandle.VarHandleDesc desc = described.get();
            ConstantDesc declaring = desc.bootstrapArgsList().get(0);
            if (!(declaring instanceof ClassDesc type) || !type.isClassOrInterface()) {
                return "";
            }
            String descriptor = type.descriptorString();
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.')
                    + '.' + desc.constantName();
        } catch (RuntimeException e) { // NOPMD - an undescribable handle is only an unresolved one
            return "";
        }
    }

    /** Declares a won acquire of {@code receiver}'s flag {@code field}, confirmed through {@code handle}. */
    static void acquire(Object receiver, String field, VarHandle handle) {
        SPIN_FIELDS.add(field);
        declare(receiver, field, handle, null);
    }

    /** Declares a won acquire of {@code receiver}'s flag {@code field}, confirmed through {@code updater}. */
    static void acquire(Object receiver, String field, AtomicIntegerFieldUpdater<Object> updater) {
        SPIN_FIELDS.add(field);
        declare(receiver, field, null, updater);
    }

    /** Declares a won acquire of an {@code AtomicBoolean} or {@code AtomicInteger} used as the lock. */
    static void acquire(Object atomic) {
        declare(atomic, ATOMIC, null, null);
    }

    /** Releases {@code receiver}'s flag {@code field} if this thread declared it. */
    static void release(@Nullable Object receiver, String field) {
        if (receiver == null || LOCKS.isEmpty()) {
            return;
        }
        Lock lock = LOCKS.get(new Key(System.identityHashCode(receiver), field));
        if (lock != null && lock.isFor(receiver)) {
            lock.releasedBy(Thread.currentThread().threadId());
            HeldLocks.released(lock);
        }
    }

    /** Releases an atomic object used as the lock, if this thread declared it. */
    static void release(@Nullable Object atomic) {
        release(atomic, ATOMIC);
    }

    private static void declare(Object subject, String field, @Nullable VarHandle handle,
                                @Nullable AtomicIntegerFieldUpdater<Object> updater) {
        Key key = new Key(System.identityHashCode(subject), field);
        Lock lock = LOCKS.get(key);
        if (lock == null) {
            Lock fresh = new Lock(subject, handle, updater);
            Lock raced = LOCKS.putIfAbsent(key, fresh);
            lock = raced == null ? fresh : raced;
            if (raced == null) {
                sweepIfLarge();
            }
        } else if (lock.isGone()) {
            // The receiver this entry was for has been collected and its hash reused.
            Lock fresh = new Lock(subject, handle, updater);
            lock = LOCKS.replace(key, lock, fresh) ? fresh : LOCKS.get(key);
        }
        if (lock == null || !lock.isFor(subject)) {
            // Two live objects share an identity hash. Sharing one lock would let one guard the
            // other, so this acquire goes undeclared: its writes report rather than hide.
            return;
        }
        lock.wonBy(Thread.currentThread().threadId());
        if (!HeldLocks.holds(lock)) {
            HeldLocks.acquired(lock);
        }
    }

    /** Drops entries whose subject has been collected, once the map has grown past the threshold. */
    private static void sweepIfLarge() {
        int threshold = NEXT_SWEEP.get();
        if (LOCKS.size() < threshold || !NEXT_SWEEP.compareAndSet(threshold, Integer.MAX_VALUE)) {
            return;
        }
        LOCKS.values().removeIf(Lock::isGone);
        NEXT_SWEEP.set(Math.max(4096, LOCKS.size() * 2));
    }

    /**
     * One spinlock, confirmed against its flag whenever a holder's lockset is read.
     *
     * <p>Only the identity of this object enters a lockset. The fields are how it answers
     * {@link #stillHeld()}: the flag it swaps, reached the way the acquire reached it.
     */
    static final class Lock implements HeldLocks.Revocable {

        private final WeakReference<Object> subject;
        private final @Nullable VarHandle handle;
        private final @Nullable AtomicIntegerFieldUpdater<Object> updater;

        /** The thread id of the last observed winner, or 0 after an observed release. */
        private final AtomicLong holder = new AtomicLong();

        Lock(Object subject, @Nullable VarHandle handle,
             @Nullable AtomicIntegerFieldUpdater<Object> updater) {
            this.subject = new WeakReference<>(subject);
            this.handle = handle == null ? null : handle.withInvokeBehavior();
            this.updater = updater;
        }

        @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
        boolean isFor(Object candidate) {
            return subject.get() == candidate;
        }

        boolean isGone() {
            return subject.get() == null;
        }

        void wonBy(long threadId) {
            holder.set(threadId);
        }

        /**
         * Clears the holder, but only from the thread that holds it.
         *
         * <p>A compare-and-set, not a check followed by a write. A release through a swap lands the
         * flag before this runs, so the next winner can record itself in between; a plain
         * {@code if (holder == me) holder = 0} could then erase that winner and revoke a lock it
         * genuinely holds, which would report its guarded writes.
         */
        void releasedBy(long threadId) {
            holder.compareAndSet(threadId, 0L);
        }

        /**
         * {@return whether the calling thread is still the holder and the flag still reads locked}
         *
         * <p>Allocation-free: one atomic read, a weak-reference read and one read of the
         * flag through the same handle, updater or atomic the acquire used.
         */
        @Override
        public boolean stillHeld() {
            if (holder.get() != Thread.currentThread().threadId()) {
                return false;
            }
            Object target = subject.get();
            if (target == null) {
                return false;
            }
            if (handle != null) {
                return (int) handle.get(target) == 1;
            }
            if (updater != null) {
                return updater.get(target) == 1;
            }
            if (target instanceof AtomicBoolean flag) {
                return flag.get();
            }
            return target instanceof AtomicInteger count && count.get() == 1;
        }
    }
}
