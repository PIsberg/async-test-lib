package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.diagnostics.KotlinMutexReentrancyDetector;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Calls kotlinx {@code Mutex} and reads the calling coroutine's {@code Job} for the woven
 * {@code Mutex} hooks (#1007), without the library depending on Kotlin.
 *
 * <p>Every type is resolved by name through the class loader of the {@code Mutex} interface the
 * receiver implements, found on the receiver's own class, so it is the kotlinx the call site
 * linked against. The weaver substitutes a call only where the call site's descriptor is the one
 * bound here, so the call-through handles always resolve; the {@code Job} and {@code holdsLock}
 * handles are best effort, and without them a call is made unchanged and nothing is recorded.
 */
final class KotlinMutexes {

    static final String MUTEX = "kotlinx.coroutines.sync.Mutex";
    private static final String CONTINUATION = "kotlin.coroutines.Continuation";
    private static final String CONTEXT = "kotlin.coroutines.CoroutineContext";
    private static final String KEY = "kotlin.coroutines.CoroutineContext$Key";
    private static final String ELEMENT = "kotlin.coroutines.CoroutineContext$Element";
    private static final String JOB = "kotlinx.coroutines.Job";
    private static final String SCOPE_COROUTINE = "kotlinx.coroutines.internal.ScopeCoroutine";
    private static final String SUSPENDED_TYPE = "kotlin.coroutines.intrinsics.CoroutineSingletons";

    /** Scopes followed outward from the caller before giving up; far beyond any real nesting. */
    private static final int MAX_DEPTH = 64;

    private static final ClassValue<Binding> BINDINGS = new ClassValue<>() {
        @Override
        protected Binding computeValue(Class<?> type) {
            return bind(type);
        }
    };

    private KotlinMutexes() {
    }

    /** The handles for one {@code Mutex} implementation class. */
    static final class Binding {
        /** {@code Mutex.lock(Object, Continuation)}, as {@code (Object, Object, Object)Object}. */
        final MethodHandle lock;
        /** {@code Mutex.lock$default}, as {@code (Object, Object, Object, int, Object)Object}. */
        final MethodHandle lockDefault;
        /** {@code Mutex.unlock(Object)}, as {@code (Object, Object)void}. */
        final MethodHandle unlock;
        /** {@code Mutex.unlock$default}, as {@code (Object, Object, int, Object)void}. */
        final MethodHandle unlockDefault;
        /** {@code Mutex.tryLock(Object)}, as {@code (Object, Object)boolean}. */
        final MethodHandle tryLock;
        /** {@code Mutex.tryLock$default}, as {@code (Object, Object, int, Object)boolean}. */
        final MethodHandle tryLockDefault;
        /** {@code Mutex.holdsLock(Object)}, as {@code (Object, Object)boolean}; {@code null} where absent. */
        final @Nullable MethodHandle holdsLock;
        /** Reads a continuation's {@code Job}, or {@code null} where kotlinx could not be read. */
        final @Nullable JobReader jobs;

        Binding(MethodHandle lock, MethodHandle lockDefault, MethodHandle unlock,
                MethodHandle unlockDefault, MethodHandle tryLock, MethodHandle tryLockDefault,
                @Nullable MethodHandle holdsLock, @Nullable JobReader jobs) {
            this.lock = lock;
            this.lockDefault = lockDefault;
            this.unlock = unlock;
            this.unlockDefault = unlockDefault;
            this.tryLock = tryLock;
            this.tryLockDefault = tryLockDefault;
            this.holdsLock = holdsLock;
            this.jobs = jobs;
        }

        /** {@return whether {@code token} holds {@code mutex}; false when the mutex cannot say} */
        boolean holds(Object mutex, Object token) {
            MethodHandle handle = holdsLock;
            if (handle == null) {
                return false;
            }
            try {
                return (boolean) handle.invokeExact(mutex, token);
            } catch (Throwable unreadable) { // NOPMD - a mutex that cannot answer holds nothing for us
                return false;
            }
        }
    }

    /**
     * The owner a woven {@code lock} passes when its caller passed none (#1221). kotlinx compares
     * owners by identity, and a fresh one per call never matches another, so the lock behaves as
     * with no owner: {@code unlock(null)} still releases it, and only {@code holdsLock} on this very
     * object says {@code true}. It prints as {@code null}, as the absent owner it stands for does in
     * the mutex's own {@code toString}.
     */
    static final class OwnerToken {
        @Override
        public String toString() {
            return "null";
        }
    }

    /** Reads the {@code Job} of a continuation's context, and the parent of a scoped coroutine. */
    static final class JobReader {
        private final MethodHandle context;
        private final MethodHandle get;
        private final Object jobKey;
        private final @Nullable MethodHandle parent;
        private final Class<?> scopeCoroutine;

        JobReader(MethodHandle context, MethodHandle get, Object jobKey, @Nullable MethodHandle parent,
                  Class<?> scopeCoroutine) {
            this.context = context;
            this.get = get;
            this.jobKey = jobKey;
            this.parent = parent;
            this.scopeCoroutine = scopeCoroutine;
        }

        /**
         * {@return the caller's {@code Job}, then each coroutine suspended until the one before it
         * completes, outward; empty when the continuation has no {@code Job}}
         */
        List<Object> waiting(Object continuation) {
            List<Object> chain = new ArrayList<>(4);
            try {
                Object job = get.invokeExact(context.invokeExact(continuation), jobKey);
                while (job != null && chain.size() < MAX_DEPTH) {
                    chain.add(job);
                    // A withContext, coroutineScope, supervisorScope or withTimeout block runs as a
                    // ScopeCoroutine its parent is suspended for; a launch or async child is not one.
                    if (parent == null || !scopeCoroutine.isInstance(job)) {
                        break;
                    }
                    job = parent.invokeExact(job);
                }
            } catch (Throwable unreadable) { // NOPMD - a context kotlinx would not read records nothing
                return chain;
            }
            return chain;
        }
    }

    /**
     * What a woven {@code lock} knew before the call, for the detector once it returns: who holds
     * the mutex, whether that is the caller or a coroutine waiting for it, and the owner the call
     * is made with.
     */
    static final class LockWatch {
        private final KotlinMutexReentrancyDetector detector;
        private final Object mutex;
        private final List<Object> waiting;
        private final int held;
        /** The owner to make the lock with: the caller's own, or a fresh {@link OwnerToken}. */
        final Object owner;

        private LockWatch(KotlinMutexReentrancyDetector detector, Object mutex, List<Object> waiting, int held,
                          Object owner) {
            this.detector = detector;
            this.mutex = mutex;
            this.waiting = waiting;
            this.held = held;
            this.owner = owner;
        }

        /**
         * {@return the watch for a lock about to be made with {@code owner}, or {@code null} when no
         * run hears it or the mutex cannot say who holds it, and the call is then made unchanged}
         * The hook passes the run's detector in, so the accessor call stays in the hook source,
         * where {@code DetectorFeedCoverageTest} looks for it.
         */
        static @Nullable LockWatch before(@Nullable KotlinMutexReentrancyDetector detector, Object mutex,
                                          @Nullable Object owner, Object continuation, Binding binding) {
            JobReader jobs = binding.jobs;
            if (detector == null || jobs == null || binding.holdsLock == null) {
                return null;
            }
            List<Object> waiting = jobs.waiting(continuation);
            int held = detector.holderAmong(mutex, waiting, token -> binding.holds(mutex, token));
            return new LockWatch(detector, mutex, waiting, held, owner != null ? owner : new OwnerToken());
        }

        /**
         * The lock returned. Either way the caller's {@code Job} is recorded with the owner it
         * locked with: it holds the mutex now, or once its wait ends. Waiting while the caller or a
         * coroutine suspended for it holds the mutex is the finding.
         */
        static void returned(@Nullable LockWatch watch, @Nullable Object result) {
            if (watch == null) {
                return;
            }
            boolean waited = suspended(result);
            if (waited) {
                watch.reentryIfHeld();
            }
            watch.detector.recordLocked(watch.mutex, watch.waiting.isEmpty() ? null : watch.waiting.get(0),
                    watch.owner, !waited);
        }

        /** The lock threw: an owner token the holder already used makes kotlinx throw instead of wait. */
        static void threw(@Nullable LockWatch watch) {
            if (watch != null) {
                watch.reentryIfHeld();
            }
        }

        private void reentryIfHeld() {
            if (held >= 0) {
                detector.recordReentry(mutex, names(waiting), held, Thread.currentThread());
            }
        }
    }

    /** {@return the binding for {@code mutex}'s class} */
    static Binding of(Object mutex) {
        return BINDINGS.get(mutex.getClass());
    }

    /** {@return each coroutine's class simple name, in order} */
    static List<String> names(List<Object> jobs) {
        List<String> names = new ArrayList<>(jobs.size());
        for (Object job : jobs) {
            names.add(job.getClass().getSimpleName());
        }
        return names;
    }

    /** {@return whether a {@code lock} call returned {@code COROUTINE_SUSPENDED}: it did not take the mutex yet} */
    static boolean suspended(@Nullable Object result) {
        return result instanceof Enum<?> e && SUSPENDED_TYPE.equals(e.getDeclaringClass().getName())
                && "COROUTINE_SUSPENDED".equals(e.name());
    }

    /**
     * Rethrows {@code t} unchanged, checked or not, as the substituted call would have; it never
     * returns. The declared result lets a caller write {@code throw rethrow(t)} where the compiler
     * needs the branch to end.
     */
    @SuppressWarnings("unchecked")
    static <T extends Throwable> AssertionError rethrow(Throwable t) throws T {
        throw (T) t;
    }

    private static Binding bind(Class<?> type) {
        Class<?> mutex = interfaceNamed(type, MUTEX);
        if (mutex == null) {
            throw new IllegalStateException(type.getName() + " does not implement " + MUTEX);
        }
        try {
            ClassLoader loader = mutex.getClassLoader();
            Class<?> continuation = Class.forName(CONTINUATION, false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle lock = lookup.findVirtual(mutex, "lock",
                    MethodType.methodType(Object.class, Object.class, continuation));
            MethodHandle lockDefault = lookup.findStatic(mutex, "lock$default",
                    MethodType.methodType(Object.class, mutex, Object.class, continuation, int.class, Object.class));
            MethodHandle unlock = lookup.findVirtual(mutex, "unlock",
                    MethodType.methodType(void.class, Object.class));
            MethodHandle unlockDefault = lookup.findStatic(mutex, "unlock$default",
                    MethodType.methodType(void.class, mutex, Object.class, int.class, Object.class));
            MethodHandle tryLock = lookup.findVirtual(mutex, "tryLock",
                    MethodType.methodType(boolean.class, Object.class));
            MethodHandle tryLockDefault = lookup.findStatic(mutex, "tryLock$default",
                    MethodType.methodType(boolean.class, mutex, Object.class, int.class, Object.class));
            MethodHandle holdsLock = holdsLockOf(lookup, mutex);
            return new Binding(
                    lock.asType(MethodType.genericMethodType(3)),
                    lockDefault.asType(MethodType.methodType(Object.class,
                            Object.class, Object.class, Object.class, int.class, Object.class)),
                    unlock.asType(MethodType.methodType(void.class, Object.class, Object.class)),
                    unlockDefault.asType(MethodType.methodType(void.class,
                            Object.class, Object.class, int.class, Object.class)),
                    tryLock.asType(MethodType.methodType(boolean.class, Object.class, Object.class)),
                    tryLockDefault.asType(MethodType.methodType(boolean.class,
                            Object.class, Object.class, int.class, Object.class)),
                    holdsLock,
                    jobReader(loader, continuation));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind " + MUTEX + " on " + type.getName(), e);
        }
    }

    private static @Nullable JobReader jobReader(@Nullable ClassLoader loader, Class<?> continuation) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> context = Class.forName(CONTEXT, false, loader);
            Class<?> key = Class.forName(KEY, false, loader);
            Class<?> element = Class.forName(ELEMENT, false, loader);
            Class<?> job = Class.forName(JOB, false, loader);
            Class<?> scope = Class.forName(SCOPE_COROUTINE, false, loader);
            MethodHandle getContext = lookup.findVirtual(continuation, "getContext", MethodType.methodType(context))
                    .asType(MethodType.genericMethodType(1));
            MethodHandle get = lookup.findVirtual(context, "get", MethodType.methodType(element, key))
                    .asType(MethodType.genericMethodType(2));
            MethodHandle parent = parentOf(lookup, job);
            Object jobKey = job.getField("Key").get(null);
            return new JobReader(getContext, get, jobKey, parent, scope);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { // NOPMD - unreadable records nothing
            return null;
        }
    }

    /** {@return {@code Mutex.holdsLock(Object)}, or {@code null} on a kotlinx without it: nothing is then recorded} */
    private static @Nullable MethodHandle holdsLockOf(MethodHandles.Lookup lookup, Class<?> mutex)
            throws IllegalAccessException {
        try {
            return lookup.findVirtual(mutex, "holdsLock", MethodType.methodType(boolean.class, Object.class))
                    .asType(MethodType.methodType(boolean.class, Object.class, Object.class));
        } catch (NoSuchMethodException olderKotlinx) {
            return null;
        }
    }

    /** {@return {@code Job.getParent()}, or {@code null} on a kotlinx without it: the caller alone is then checked} */
    private static @Nullable MethodHandle parentOf(MethodHandles.Lookup lookup, Class<?> job)
            throws IllegalAccessException {
        try {
            return lookup.findVirtual(job, "getParent", MethodType.methodType(job))
                    .asType(MethodType.genericMethodType(1));
        } catch (NoSuchMethodException olderKotlinx) {
            return null;
        }
    }

    private static @Nullable Class<?> interfaceNamed(Class<?> type, String name) {
        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            pending.addAll(List.of(c.getInterfaces()));
        }
        while (!pending.isEmpty()) {
            Class<?> candidate = pending.poll();
            if (name.equals(candidate.getName())) {
                return candidate;
            }
            pending.addAll(List.of(candidate.getInterfaces()));
        }
        return null;
    }
}
