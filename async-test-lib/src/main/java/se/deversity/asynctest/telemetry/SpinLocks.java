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
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>The weaver sees the acquire and the common releases, but not every release: an
 * {@code updateAndGet}, a {@code getAndSetRelease}, an {@code Unsafe} store, or a release in code it
 * does not weave (the full list is below). A lock that stayed declared after such a release would make every later
 * access on that thread look guarded, and if every thread did the same, a real race would read as
 * consistently locked. That is the one direction this library must never take. So each
 * {@link Lock} is {@link HeldLocks.Revocable}: whenever the thread's lockset is read, the lock is
 * kept only while its flag still reads locked <em>and</em> this thread is the one that last won
 * it. An unobserved release by the holder turns the flag back; an unobserved release followed by
 * another thread's observed acquire changes the holder. Either drops the stale entry before the
 * next access is recorded.
 *
 * <h2>Revoking a stale holder before the next swap (#621)</h2>
 *
 * <p>Between another thread's won swap and that winner recording itself, the flag reads locked
 * again and the holder still names the thread that released unobserved, so that thread's accesses
 * would pass re-confirmation and share one lock with the winner's. A thread about to swap the
 * flag from unlocked therefore first revokes a stale holder: it reads the holder's {@link Stamp},
 * <em>then</em> the flag, and only if the flag reads unlocked does it compare-and-set that stamp
 * away. A stamp is a fresh object, written by a winner after its swap has landed, which is what
 * keeps this from ever revoking a live hold:
 * <ul>
 *   <li>a stamp read before a flag that reads unlocked belongs to a hold that had already ended;</li>
 *   <li>if a winner's swap lands after that read and it writes its stamp, the revoking
 *       compare-and-set finds a different object and fails, and no stamp is ever reused, so an
 *       ABA cannot make it succeed;</li>
 *   <li>if the revoking compare-and-set lands between a winner's swap and its stamp write, it
 *       clears a stamp the winner does not own, and the winner's write then replaces it.</li>
 * </ul>
 * Checking the flag before reading the stamp, or clearing on the flag alone as #648 did, loses
 * the first property: the swap and stamp write can both land between the check and the clear, and
 * a lock the winner genuinely holds is revoked, which reports correct code.
 *
 * <h2>A release between a contender's check and its swap (#658)</h2>
 *
 * <p>The stamp cannot close one window, and only observing the release does. If the holder's
 * release lands after the next winner has read the flag locked but before that winner's swap, no
 * revocation happens, and from the swap until the winner writes its stamp (a few instructions
 * inside {@code declare}) the old holder would still pass re-confirmation, so an access it records
 * there would look guarded by the winner's lock. An observed release closes that: it clears the
 * releasing thread's stamp and pops the lock from its lockset, and it runs on the releasing thread,
 * so that thread records nothing between its release and the bookkeeping, whichever side of it the
 * winner's swap and stamp write fall. The weaver therefore substitutes the value-returning releases
 * too, not only {@code set} and the swap back: a plain write of the flag field; on a
 * {@code VarHandle} {@code set}, {@code setVolatile}, {@code setRelease}, {@code setOpaque},
 * {@code compareAndSet}, {@code getAndSet}, {@code getAndAdd}, {@code compareAndExchange},
 * {@code weakCompareAndSet} and {@code weakCompareAndSetPlain}; on an updater {@code compareAndSet},
 * {@code set}, {@code lazySet}, {@code getAndSet}, {@code getAndAdd}, {@code addAndGet},
 * {@code getAndDecrement}, {@code decrementAndGet} and {@code weakCompareAndSet}; on an
 * {@code AtomicInteger} {@code compareAndSet}, {@code set}, {@code lazySet}, {@code getAndSet},
 * {@code getAndAdd}, {@code addAndGet}, {@code getAndDecrement}, {@code decrementAndGet},
 * {@code compareAndExchange}, {@code weakCompareAndSetPlain} and {@code weakCompareAndSetVolatile};
 * on an {@code AtomicBoolean} {@code compareAndSet}, {@code getAndSet}, {@code set},
 * {@code lazySet}, {@code compareAndExchange}, {@code weakCompareAndSetPlain} and
 * {@code weakCompareAndSetVolatile}.
 *
 * <p>The window stays open for a release through anything else, which is not observed:
 * <ul>
 *   <li>on a {@code VarHandle}: the {@code Acquire}/{@code Release} variants of
 *       {@code getAndSet}, {@code getAndAdd}, {@code compareAndExchange} and
 *       {@code weakCompareAndSet}, the {@code getAndBitwise} forms, and any call site whose declared
 *       result is neither {@code int} nor void (an {@code Object} or {@code long} result);</li>
 *   <li>on an updater or {@code AtomicInteger}: {@code getAndUpdate}, {@code updateAndGet},
 *       {@code getAndAccumulate}, {@code accumulateAndGet}, and the deprecated
 *       {@code weakCompareAndSet} on {@code AtomicInteger};</li>
 *   <li>on either atomic: {@code setPlain}, {@code setOpaque}, {@code setRelease}, and the
 *       deprecated {@code AtomicBoolean.weakCompareAndSet};</li>
 *   <li>a release through a subclass-typed call site, {@code Unsafe}, JNI, reflection, or in code
 *       the agent does not weave (the JDK, excluded packages, classes it could not retransform).</li>
 * </ul>
 * Both halves still have to fall in windows a few instructions wide on two threads at once, and
 * each ends the moment the winner declares, but for those forms it is a real false negative.
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

    /** The recorded field of a {@code newUpdater} call whose owner or name was not a constant (#619). */
    static final String UNREADABLE = "";

    /**
     * Which field each {@code VarHandle} or {@code AtomicIntegerFieldUpdater} reaches, as
     * {@code declaringClass.field}; the empty string for a handle that was asked and cannot say.
     */
    private static final Map<Object, String> HANDLE_FIELDS = new ConcurrentHashMap<>();

    /**
     * Fields each class binds through an {@code AtomicIntegerFieldUpdater.newUpdater} call,
     * as {@code declaringClass -> Set<String> qualifiedFieldNames} (#619).
     *
     * <p>Every class the weaver finished scanning has an entry, an empty set when it binds nothing,
     * so a class with no entry is one whose updaters nobody has seen. {@link #UNREADABLE} in a set
     * marks a {@code newUpdater} call in that class whose arguments were not constants.
     */
    private static final Map<String, Set<String>> CLASS_UPDATER_FIELDS = new ConcurrentHashMap<>();

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

    /**
     * One observed won acquire, compared by identity (#621), so deliberately a class and not a record.
     *
     * <p>Never reused: a revoking compare-and-set holds the stamp it read, and the same object
     * written again by a later hold would let that stale read revoke the live one.
     */
    private static final class Stamp {
        final long threadId;

        Stamp(long threadId) {
            this.threadId = threadId;
        }
    }

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
     * Records that {@code ownerClass} binds {@code field} through an updater (#619), or, when
     * {@code field} is {@link #UNREADABLE}, that it makes one the weaver could not read.
     */
    static void recordUpdaterField(String ownerClass, String field) {
        CLASS_UPDATER_FIELDS.computeIfAbsent(ownerClass, k -> ConcurrentHashMap.newKeySet()).add(field);
    }

    /** Records that the weaver has scanned every method of {@code className} (#619). */
    static void recordScannedClass(String className) {
        CLASS_UPDATER_FIELDS.computeIfAbsent(className, k -> ConcurrentHashMap.newKeySet());
    }

    /**
     * {@return the field an updater reaches on {@code receiver}, or {@code null}}
     *
     * <p>If the updater was bound before the agent attached, its owner's type initializer never
     * ran the woven binding call. It is then resolved from the receiver's class hierarchy, but
     * only when that hierarchy accounts for every updater it could hold (#619): every class in it
     * outside the JDK has been scanned, none makes an updater the weaver could not read, and
     * exactly one field was recorded across them. "One recorded field" alone is not "one updater":
     * a superclass the agent was not told to weave can bind its own, and naming the subclass's
     * flag for a swap through it would put a lock on the wrong flag in the lockset, which can
     * excuse a race. Refusing only loses a guard.
     *
     * <p>Two limits remain. JDK superclasses are not scanned and are assumed to bind no updater a
     * woven call site swaps. And an updater made in a class outside the receiver's hierarchy, on a
     * field that class can reach, is recorded only if that class was scanned: an unscanned one can
     * still leave a single recorded field that is the wrong one. Separately, the weave-time record
     * goes to the registry the agent's own loader sees ({@code AtomicFieldRegistry}), so under a
     * runner that loads the library in an isolated classloader this copy stays empty and every
     * pre-attach updater stays unresolved, which reports rather than hides.
     */
    static @Nullable String fieldOf(AtomicIntegerFieldUpdater<?> updater,
                                    @Nullable Object receiver) {
        String field = HANDLE_FIELDS.get(updater);
        if (field != null) {
            return field.isEmpty() ? null : field;
        }
        if (receiver == null || CLASS_UPDATER_FIELDS.isEmpty()) {
            return null;
        }
        field = resolveFromHierarchy(receiver.getClass());
        if (field != null) {
            HANDLE_FIELDS.put(updater, field);
            SPIN_FIELDS.add(field);
            return field;
        }
        HANDLE_FIELDS.put(updater, "");
        return null;
    }

    private static @Nullable String resolveFromHierarchy(Class<?> clazz) {
        String found = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Set<String> fields = CLASS_UPDATER_FIELDS.get(c.getName());
            if (fields == null) {
                if (isJdk(c)) {
                    continue;
                }
                // Never scanned: it may bind an updater nobody recorded.
                return null;
            }
            for (String field : fields) {
                if (UNREADABLE.equals(field) || (found != null && !found.equals(field))) {
                    return null;
                }
                found = field;
            }
        }
        return found;
    }

    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    private static boolean isJdk(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        return loader == null || loader == ClassLoader.getPlatformClassLoader();
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

    /**
     * Test-only seam: runs on the winning thread once its swap has landed and before any of the
     * lock's bookkeeping, which is the window #621 is about.
     */
    private static volatile @Nullable Runnable testHookAfterSwap;

    static void setTestHookAfterSwap(@Nullable Runnable hook) {
        testHookAfterSwap = hook;
    }

    /**
     * Test-only seam: runs on a thread about to swap once it has read a stamp and seen the flag
     * unlocked, and before it revokes that stamp.
     */
    private static volatile @Nullable Runnable testHookBeforeRevoke;

    static void setTestHookBeforeRevoke(@Nullable Runnable hook) {
        testHookBeforeRevoke = hook;
    }

    /**
     * Test-only seam: runs on a thread about to swap once it has finished checking for a stale
     * holder, and before its swap lands, which is the window #658 is about.
     */
    private static volatile @Nullable Runnable testHookAfterCheck;

    static void setTestHookAfterCheck(@Nullable Runnable hook) {
        testHookAfterCheck = hook;
    }

    @SuppressWarnings("PMD.NullAssignment")
    static void resetForTesting() {
        LOCKS.clear();
        SPIN_FIELDS.clear();
        HANDLE_FIELDS.clear();
        CLASS_UPDATER_FIELDS.clear();
        testHookAfterSwap = null;
        testHookBeforeRevoke = null;
        testHookAfterCheck = null;
    }

    static @Nullable Lock lockFor(Object subject, String field) {
        return LOCKS.get(new Key(System.identityHashCode(subject), field));
    }

    static @Nullable Lock lockFor(Object atomic) {
        return lockFor(atomic, ATOMIC);
    }

    /**
     * Prepares for a swap from 0 to 1 on {@code receiver}'s {@code field}, revoking a stale
     * holder before the swap instruction lands (#621).
     */
    static void aboutToAcquire(@Nullable Object receiver, @Nullable VarHandle handle) {
        if (receiver == null || handle == null || !anySpinField()) {
            return;
        }
        String field = fieldOf(handle);
        if (field != null && isSpinField(field)) {
            aboutToAcquire(receiver, field);
        }
    }

    /**
     * Prepares for a swap from 0 to 1 on {@code receiver}'s {@code field} through an updater (#621).
     */
    static void aboutToAcquire(@Nullable Object receiver,
                              @Nullable AtomicIntegerFieldUpdater<?> updater) {
        if (receiver == null || updater == null || !anySpinField()) {
            return;
        }
        String field = fieldOf(updater, receiver);
        if (field != null && isSpinField(field)) {
            aboutToAcquire(receiver, field);
        }
    }

    /**
     * Prepares for a swap from 0 to 1 on {@code atomic} (#621).
     */
    static void aboutToAcquire(@Nullable Object atomic) {
        if (atomic == null || LOCKS.isEmpty()) {
            return;
        }
        aboutToAcquire(atomic, ATOMIC);
    }

    private static void aboutToAcquire(Object subject, String field) {
        Lock lock = LOCKS.get(new Key(System.identityHashCode(subject), field));
        if (lock != null && lock.isFor(subject)) {
            lock.aboutToAcquire();
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
        Runnable hook = testHookAfterSwap;
        if (hook != null) {
            hook.run();
        }
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

        /**
         * The last observed winner, or {@code null} after an observed release or a revocation.
         *
         * <p>A fresh {@link Stamp} per won acquire rather than a thread id, so a compare-and-set
         * against a stamp read earlier can only match that one hold (#621). The one object this
         * costs is allocated on an observed won acquire, never on a spin or an access.
         */
        private final AtomicReference<@Nullable Stamp> holder = new AtomicReference<>();

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

        /**
         * Revokes a holder whose hold has ended unobserved, before this thread's swap lands (#621).
         *
         * <p>The stamp is read before the flag, and that order is the whole argument (see the
         * class javadoc): a stamp read ahead of a flag that reads unlocked cannot belong to a live
         * hold, and a hold won after the read has a different stamp the compare-and-set cannot
         * match. A contender spinning on a held lock reads the flag locked and writes nothing.
         */
        void aboutToAcquire() {
            Stamp seen = holder.get();
            if (seen != null && !isLocked()) {
                Runnable hook = testHookBeforeRevoke;
                if (hook != null) {
                    hook.run();
                }
                holder.compareAndSet(seen, null);
            }
            Runnable afterCheck = testHookAfterCheck;
            if (afterCheck != null) {
                afterCheck.run();
            }
        }

        void wonBy(long threadId) {
            holder.set(new Stamp(threadId));
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
            Stamp current = holder.get();
            if (current != null && current.threadId == threadId) {
                holder.compareAndSet(current, null);
            }
        }

        /**
         * {@return whether the calling thread is still the holder and the flag still reads locked}
         *
         * <p>Allocation-free: one atomic read, a weak-reference read and one read of the
         * flag through the same handle, updater or atomic the acquire used.
         */
        @Override
        public boolean stillHeld() {
            Stamp current = holder.get();
            return current != null && current.threadId == Thread.currentThread().threadId()
                    && isLocked();
        }

        boolean isLocked() {
            Object target = subject.get();
            if (target == null) {
                return false;
            }
            if (handle != null) {
                return (int) handle.getVolatile(target) == 1;
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
