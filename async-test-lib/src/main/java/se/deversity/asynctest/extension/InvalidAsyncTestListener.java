package se.deversity.asynctest.extension;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.DiscoveryIssue;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherDiscoveryListener;

import se.deversity.asynctest.AsyncTest;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Fails discovery when JUnit would skip an {@link AsyncTest} method with only a warning: one that
 * returns a value (#1119), is a Kotlin {@code suspend fun}, or is {@code private} or
 * {@code static} (#1146).
 *
 * <p>JUnit runs no such {@code @TestTemplate}. It reports, for example,
 * {@code must not be private. It will not be executed} as a discovery warning, and the build goes
 * green with nothing run. A method returning {@code CompletableFuture<Void>} is the natural
 * spelling of an async test, so this is the quietest way to get no testing at all out of the
 * library. {@link AsyncTestExtension} never sees such a method, because JUnit drops it before
 * asking any template provider; the only place that does is a launcher discovery listener, which
 * the launcher loads from {@code META-INF/services}.
 *
 * <p>A Kotlin {@code suspend fun} compiles to a method that takes a trailing
 * {@code kotlin.coroutines.Continuation} and returns {@code Object}. JUnit 6 does not skip it
 * quietly, but neither of its answers helps: without {@code kotlin-reflect} discovery of the whole
 * class fails with "requires org.jetbrains.kotlin:kotlin-reflect", and with it JUnit accepts the
 * method, the runner invokes it without a continuation, and every worker fails with
 * {@code wrong number of arguments}. The runner cannot drive a coroutine body, so both paths now
 * say what to do instead: a plain {@code fun} whose body is {@code runBlocking { ... }}. This
 * listener answers the first, from the class's discovery issue, and {@link AsyncTestExtension}
 * the second, through {@link #rejectKotlinSuspend}. The type is matched by name, so the library
 * needs no Kotlin.
 *
 * <p>Throwing here fails the engine's discovery, so the run reports one failure naming the method
 * and runs nothing else from that engine, which is what JUnit itself does with a discovery issue
 * at or above {@code junit.platform.discovery.issue.severity.critical}. Issues about methods that
 * are not {@code @AsyncTest}, and issues about an {@code @AsyncTest} for any other reason, are
 * left alone: an unresolvable parameter, for one, already fails the test visibly when it runs.
 *
 * @since 1.15.0
 */
@API(status = Status.INTERNAL)
public final class InvalidAsyncTestListener implements LauncherDiscoveryListener {

    private static final String KOTLIN_CONTINUATION = "kotlin.coroutines.Continuation";

    /** Called by the launcher through {@code ServiceLoader}. */
    public InvalidAsyncTestListener() {
        // Stateless: every decision is made from the issue alone.
    }

    @Override
    public void issueEncountered(UniqueId engineUniqueId, DiscoveryIssue issue) {
        @Nullable Object source = issue.source().orElse(null);
        if (source instanceof ClassSource classSource) {
            Method[] methods;
            try {
                methods = classSource.getJavaClass().getDeclaredMethods();
            } catch (RuntimeException | LinkageError unresolvable) {
                return; // JUnit reports the class itself; nothing here to add
            }
            for (Method method : methods) {
                if (AnnotationSupport.isAnnotated(method, AsyncTest.class)) {
                    rejectKotlinSuspend(method);
                }
            }
            return;
        }
        if (source instanceof MethodSource methodSource) {
            Method method;
            try {
                method = methodSource.getJavaMethod();
            } catch (RuntimeException unresolvable) {
                return;
            }
            if (!AnnotationSupport.isAnnotated(method, AsyncTest.class)) {
                return;
            }
            String problem = problem(method);
            if (problem != null) {
                throw new JUnitException("@AsyncTest method " + method.getDeclaringClass().getName()
                        + "#" + method.getName() + " " + problem);
            }
        }
    }

    /**
     * Throws when {@code method} is a Kotlin {@code suspend fun}, which the runner cannot drive,
     * naming the method and the fix. Shared with {@link AsyncTestExtension}, which meets such a
     * method when {@code kotlin-reflect} is on the classpath and JUnit accepts it.
     *
     * @param method an {@link AsyncTest} method
     * @throws JUnitException if it is a suspending function
     */
    static void rejectKotlinSuspend(Method method) {
        if (isKotlinSuspend(method)) {
            throw new JUnitException("@AsyncTest method " + method.getDeclaringClass().getName()
                    + "#" + method.getName() + " is a Kotlin suspend fun, which the runner cannot"
                    + " call: it has no coroutine to resume it with. Declare it a plain fun and"
                    + " wrap the body in runBlocking { ... }; to wait for asynchronous work, use"
                    + " AsyncAssert.awaitAsync.");
        }
    }

    /** What keeps JUnit from running {@code method}, with the fix, or {@code null} if none of ours. */
    private static @Nullable String problem(Method method) {
        rejectKotlinSuspend(method);
        if (method.getReturnType() != void.class) {
            return "returns " + method.getGenericReturnType().getTypeName()
                    + ", so JUnit will not run it and the build would pass with nothing tested."
                    + " Declare it void; to wait for asynchronous work inside the body, use"
                    + " AsyncAssert.awaitAsync.";
        }
        int modifiers = method.getModifiers();
        if (Modifier.isPrivate(modifiers)) {
            return "is private, so JUnit will not run it and the build would pass with nothing"
                    + " tested. Make it package-private (no modifier) or wider.";
        }
        if (Modifier.isStatic(modifiers)) {
            return "is static, so JUnit will not run it and the build would pass with nothing"
                    + " tested. Make it an instance method; keep shared state in a static field.";
        }
        return null;
    }

    private static boolean isKotlinSuspend(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return parameters.length > 0
                && KOTLIN_CONTINUATION.equals(parameters[parameters.length - 1].getName());
    }
}
