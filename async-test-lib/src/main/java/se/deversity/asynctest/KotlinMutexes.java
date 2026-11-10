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
 * Calls kotlinx {@code Mutex} and {@code SelectBuilder} and reads the calling coroutine's
 * {@code Job} for the woven {@code Mutex} and {@code select} hooks (#1007, #1220), without the
 * library depending on Kotlin.
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
    static final String SELECT_BUILDER = "kotlinx.coroutines.selects.SelectBuilder";
    private static final String SELECT_CLAUSE = "kotlinx.coroutines.selects.SelectClause";
    private static final String SELECT_CLAUSE2 = "kotlinx.coroutines.selects.SelectClause2";
    private static final String FUNCTION2 = "kotlin.jvm.functions.Function2";

    /** Scopes followed outward from the caller before giving up; far beyond any real nesting. */
    private static final int MAX_DEPTH = 64;

    private static final ClassValue<Binding> BINDINGS = new ClassValue<>() {
        @Override
        protected Binding computeValue(Class<?> type) {
            return bind(type);
        }
    };

    private static final ClassValue<SelectBinding> SELECT_BINDINGS = new ClassValue<>() {
        @Override
        protected SelectBinding computeValue(Class<?> type) {
            return bindSelect(type);
        }
    };

    /** Whether a class implements kotlinx {@code Mutex}: a select clause's object is one only for {@code onLock}. */
    private static final ClassValue<Boolean> IS_MUTEX = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return interfaceNamed(type, MUTEX) != null;
        }
    };

    private KotlinMutexes() {
    }

    /** The handles for one {@code SelectBuilder} implementation class (#1220). */
    static final class SelectBinding {
        /** {@code SelectBuilder.invoke(SelectClause2, Function2)}, as {@code (Object, Object, Object)void}. */
        final MethodHandle invoke;
        /** {@code SelectBuilder.invoke(SelectClause2, Object, Function2)}, as {@code (Object, Object, Object, Object)void}. */
        final MethodHandle invokeWithOwner;
        /** {@code SelectClause.getClauseObject()}, as {@code (Object)Object}; {@code null} where absent. */
        final @Nullable MethodHandle clauseObject;

        SelectBinding(MethodHandle invoke, MethodHandle invokeWithOwner, @Nullable MethodHandle clauseObject) {
            this.invoke = invoke;
            this.invokeWithOwner = invokeWithOwner;
            this.clauseObject = clauseObject;
        }

        /** {@return the mutex {@code clause} is the {@code onLock} clause of, or {@code null} for any other clause} */
        @Nullable Object mutexOf(Object clause) {
            MethodHandle handle = clauseObject;
            if (handle == null) {
                return null;
            }
            try {
                Object object = handle.invokeExact(clause);
                return object != null && IS_MUTEX.get(object.getClass()) ? object : null;
            } catch (Throwable unreadable) { // NOPMD - a clause that cannot say what it selects on takes no mutex for us
                return null;
            }
        }
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

    /**
     * What a woven {@code tryLock} knew before the call (#1220): the calling coroutine's {@code Job},
     * read from the continuation the agent found around the call site, and the owner the call is
     * made with.
     */
    static final class TryLockWatch {
        private final KotlinMutexReentrancyDetector detector;
        private final Object mutex;
        private final Object job;
        /** The owner to make the call with: the caller's own, or a fresh {@link OwnerToken}. */
        final Object owner;

        private TryLockWatch(KotlinMutexReentrancyDetector detector, Object mutex, Object job, Object owner) {
            this.detector = detector;
            this.mutex = mutex;
            this.job = job;
            this.owner = owner;
        }

        /**
         * {@return the watch for a {@code tryLock} about to be made with {@code owner}, or {@code null}
         * when no run hears it, no continuation was found, it names no {@code Job}, or the mutex
         * cannot say who holds it; the call is then made unchanged}
         */
        static @Nullable TryLockWatch before(@Nullable KotlinMutexReentrancyDetector detector, Object mutex,
                                             @Nullable Object owner, @Nullable Object continuation,
                                             Binding binding) {
            JobReader jobs = binding.jobs;
            if (detector == null || continuation == null || jobs == null || binding.holdsLock == null) {
                return null;
            }
            List<Object> waiting = jobs.waiting(continuation);
            if (waiting.isEmpty()) {
                return null;
            }
            return new TryLockWatch(detector, mutex, waiting.get(0), owner != null ? owner : new OwnerToken());
        }

        /** The call returned: a mutex it took is held by the caller's {@code Job} under the owner. */
        static void returned(@Nullable TryLockWatch watch, boolean taken) {
            if (watch != null && taken) {
                watch.detector.recordLocked(watch.mutex, watch.job, watch.owner, true);
            }
        }

        /**
         * A {@code select} registered the mutex's {@code onLock} clause with the owner (#1220): the
         * caller's {@code Job} holds it under that owner once, and only if, the clause is selected,
         * which the mutex answers through {@code holdsLock}. A clause that loses never holds it.
         */
        static void registered(@Nullable TryLockWatch watch) {
            if (watch != null) {
                watch.detector.recordLocked(watch.mutex, watch.job, watch.owner, false);
            }
        }
    }

    /**
     * {@return the watch for a {@code select} clause about to be registered with {@code owner}, or
     * {@code null} when no run hears it, the clause is not a mutex's {@code onLock}, or the coroutine
     * or the holder cannot be named; the clause is then registered unchanged (#1220)}
     */
    static @Nullable TryLockWatch onLockWatch(@Nullable KotlinMutexReentrancyDetector detector, Object clause,
                                              @Nullable Object owner, @Nullable Object continuation,
                                              SelectBinding select) {
        if (detector == null || continuation == null || clause == null) {
            return null;
        }
        Object mutex = select.mutexOf(clause);
        return mutex == null ? null : TryLockWatch.before(detector, mutex, owner, continuation, of(mutex));
    }

    /** {@return the binding for {@code builder}'s class} */
    static SelectBinding selectOf(Object builder) {
        return SELECT_BINDINGS.get(builder.getClass());
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

    private static SelectBinding bindSelect(Class<?> type) {
        Class<?> builder = interfaceNamed(type, SELECT_BUILDER);
        if (builder == null) {
            throw new IllegalStateException(type.getName() + " does not implement " + SELECT_BUILDER);
        }
        try {
            ClassLoader loader = builder.getClassLoader();
            Class<?> clause2 = Class.forName(SELECT_CLAUSE2, false, loader);
            Class<?> function2 = Class.forName(FUNCTION2, false, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle invoke = lookup.findVirtual(builder, "invoke",
                    MethodType.methodType(void.class, clause2, function2));
            MethodHandle invokeWithOwner = lookup.findVirtual(builder, "invoke",
                    MethodType.methodType(void.class, clause2, Object.class, function2));
            return new SelectBinding(
                    invoke.asType(MethodType.methodType(void.class, Object.class, Object.class, Object.class)),
                    invokeWithOwner.asType(MethodType.methodType(void.class,
                            Object.class, Object.class, Object.class, Object.class)),
                    clauseObjectOf(lookup, loader));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind " + SELECT_BUILDER + " on " + type.getName(), e);
        }
    }

    /** {@return {@code SelectClause.getClauseObject()}, or {@code null} on a kotlinx without it: nothing is then recorded} */
    private static @Nullable MethodHandle clauseObjectOf(MethodHandles.Lookup lookup, @Nullable ClassLoader loader) {
        try {
            Class<?> clause = Class.forName(SELECT_CLAUSE, false, loader);
            return lookup.findVirtual(clause, "getClauseObject", MethodType.methodType(Object.class))
                    .asType(MethodType.genericMethodType(1));
        } catch (ReflectiveOperationException | LinkageError olderKotlinx) { // NOPMD - unreadable records nothing
            return null;
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
