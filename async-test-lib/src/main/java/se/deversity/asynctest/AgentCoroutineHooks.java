package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.diagnostics.HappensBefore;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tells the field rules which coroutine an access is made in (#1007).
 *
 * <p>Every rule here keys an access by its thread, and a coroutine is not a thread: two coroutines
 * on one {@code runBlocking} event loop or single-threaded dispatcher share a thread, so a lost
 * update between them, one reading a field, suspending, and writing it back after the other wrote
 * it, has one owner as far as a thread is concerned and was reported by nothing.
 *
 * <p>Kotlin runs every step of every coroutine through one call: {@code BaseContinuationImpl
 * .resumeWith} calling {@code invokeSuspend}. With {@code fields=true} the agent replaces that
 * call with {@link #coroutineStep}, which marks the step on the thread for its length, in a
 * {@code finally}, and makes the call through a handle. A field access the agent records inside a
 * step goes to the run's {@code AtomicityValidator} with the coroutine and the step
 * ({@link #fieldAccessed}).
 *
 * <p>The coroutine is its outermost {@code Job} that is not a scope: a {@code coroutineScope},
 * {@code withContext} or {@code withTimeout} block runs as a coroutine of its own while its
 * caller waits for it, so it counts as its caller. A {@code launch} or {@code async} child is a
 * coroutine of its own. A continuation without a {@code Job}, a {@code sequence} builder's, and
 * any step run outside a run, is not marked.
 *
 * @since 1.14.5
 */
@API(status = Status.INTERNAL, since = "1.14.5")
public final class AgentCoroutineHooks {

    private static final String BASE_CONTINUATION = "kotlin.coroutines.jvm.internal.BaseContinuationImpl";
    private static final String CONTINUATION = "kotlin.coroutines.Continuation";

    /** The step running on this thread, {@code null} outside one. */
    private static final ThreadLocal<@Nullable Step> CURRENT = new ThreadLocal<>();

    /** Set once a step has run, so a JVM without Kotlin pays one volatile read per field access. */
    private static volatile boolean stepsSeen;

    /** Issues step numbers; each resumption of a coroutine is a new one. */
    private static final AtomicLong STEPS = new AtomicLong();

    private static final ClassValue<@Nullable Binding> BINDINGS = new ClassValue<>() {
        @Override
        protected @Nullable Binding computeValue(Class<?> type) {
            return bind(type);
        }
    };

    private AgentCoroutineHooks() {
    }

    /** One step: the coroutine, its number, the run it reports to, and the step it interrupted. */
    private record Step(Object coroutine, long number, AtomicityValidator validator,
                        @Nullable Step previous) {
    }

    /** {@code invokeSuspend} and the {@code Job} reader for one continuation class. */
    private record Binding(MethodHandle invokeSuspend, KotlinMutexes.@Nullable JobReader jobs) {
    }

    /**
     * Replaces {@code invokeSuspend(result)} in {@code BaseContinuationImpl.resumeWith}: runs the
     * step with the coroutine marked on this thread, and returns what it returned.
     *
     * @param continuation the continuation being resumed
     * @param result       the value or failure it is resumed with
     * @return what {@code invokeSuspend} returned, {@code COROUTINE_SUSPENDED} included
     * @throws Throwable whatever {@code invokeSuspend} threw, unchanged
     */
    public static @Nullable Object coroutineStep(Object continuation, @Nullable Object result)
            throws Throwable {
        Binding binding = BINDINGS.get(continuation.getClass());
        if (binding == null) {
            throw new IllegalStateException("no invokeSuspend on " + continuation.getClass().getName());
        }
        Step previous = CURRENT.get();
        Step step = stepOf(binding, continuation, previous);
        if (step == null) {
            return binding.invokeSuspend().invokeExact(continuation, result);
        }
        stepsSeen = true;
        CURRENT.set(step);
        try {
            return binding.invokeSuspend().invokeExact(continuation, result);
        } finally {
            CURRENT.set(previous);
        }
    }

    /**
     * Hands a field access recorded on this thread to the run's {@code AtomicityValidator} when it
     * is made inside a coroutine step. Called from the agent's field hook; nothing outside a step.
     *
     * @param receiver the object the field belongs to, the declaring class for a static field;
     *                 {@code null} records nothing
     * @param field    combined {@code declaringClass.field} identifier
     * @param isWrite  whether the access writes the field
     */
    public static void fieldAccessed(@Nullable Object receiver, String field, boolean isWrite) {
        if (!stepsSeen || receiver == null) {
            return;
        }
        Step step = CURRENT.get();
        if (step != null) {
            // Sampled here, on the coroutine's own thread, the only place it means anything: an
            // access whose owner's monitor is held is excused like a lock-guarded field (#1574).
            step.validator().recordCoroutineAccess(HappensBefore.fieldKey(receiver, field), field,
                    step.coroutine(), step.number(), isWrite, Thread.holdsLock(receiver));
        }
    }

    /** {@return the step to mark, or {@code null} when the coroutine or the run cannot be named} */
    private static @Nullable Step stepOf(Binding binding, Object continuation, @Nullable Step previous) {
        KotlinMutexes.JobReader jobs = binding.jobs();
        if (jobs == null) {
            return null;
        }
        AsyncTestContext context = AsyncTestContext.agentContext();
        AtomicityValidator validator = context == null ? null : context.sharedAtomicityValidator();
        if (validator == null) {
            return null;
        }
        List<Object> chain = jobs.waiting(continuation);
        if (chain.isEmpty()) {
            return null;
        }
        return new Step(chain.get(chain.size() - 1), STEPS.incrementAndGet(), validator, previous);
    }

    /** {@return the handles for {@code type}, or {@code null} when it is no {@code BaseContinuationImpl}} */
    private static @Nullable Binding bind(Class<?> type) {
        Class<?> base = type;
        while (base != null && !BASE_CONTINUATION.equals(base.getName())) {
            base = base.getSuperclass();
        }
        if (base == null) {
            return null;
        }
        try {
            Method method = base.getDeclaredMethod("invokeSuspend", Object.class);
            method.setAccessible(true);
            MethodHandle invokeSuspend = MethodHandles.lookup().unreflect(method)
                    .asType(MethodType.genericMethodType(2));
            ClassLoader loader = base.getClassLoader();
            KotlinMutexes.JobReader jobs =
                    KotlinMutexes.jobReader(loader, Class.forName(CONTINUATION, false, loader));
            return new Binding(invokeSuspend, jobs);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The weaver substitutes the call only where the stdlib's module is unnamed or the agent
            // opened its package to this one (#1468), so the method can always be opened; failing
            // here would fail every coroutine.
            throw new IllegalStateException("cannot call invokeSuspend on " + type.getName(), e);
        }
    }
}
