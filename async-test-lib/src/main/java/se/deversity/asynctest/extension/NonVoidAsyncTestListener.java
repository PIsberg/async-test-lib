package se.deversity.asynctest.extension;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.DiscoveryIssue;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherDiscoveryListener;

import se.deversity.asynctest.AsyncTest;

import java.lang.reflect.Method;

/**
 * Fails discovery when an {@link AsyncTest} method returns a value, which JUnit would otherwise
 * skip with only a warning (#1119).
 *
 * <p>JUnit runs no {@code @TestTemplate} that returns a value: it reports
 * {@code must not return a value. It will not be executed} as a discovery warning and the build
 * goes green with nothing run. A method returning {@code CompletableFuture<Void>} is the natural
 * spelling of an async test, so this is the quietest way to get no testing at all out of the
 * library. {@link AsyncTestExtension} never sees such a method, because JUnit drops it before
 * asking any template provider; the only place that does is a launcher discovery listener, which
 * the launcher loads from {@code META-INF/services}.
 *
 * <p>Throwing here fails the engine's discovery, so the run reports one failure naming the method
 * and runs nothing else from that engine, which is what JUnit itself does with a discovery issue
 * at or above {@code junit.platform.discovery.issue.severity.critical}. Issues about methods that
 * are not {@code @AsyncTest} are left alone.
 *
 * @since 1.15.0
 */
@API(status = Status.INTERNAL)
public final class NonVoidAsyncTestListener implements LauncherDiscoveryListener {

    /** Called by the launcher through {@code ServiceLoader}. */
    public NonVoidAsyncTestListener() {
    }

    @Override
    public void issueEncountered(UniqueId engineUniqueId, DiscoveryIssue issue) {
        if (issue.source().orElse(null) instanceof MethodSource source) {
            Method method;
            try {
                method = source.getJavaMethod();
            } catch (RuntimeException unresolvable) {
                return;
            }
            if (method.getReturnType() != void.class
                    && AnnotationSupport.isAnnotated(method, AsyncTest.class)) {
                throw new JUnitException("@AsyncTest method " + method.getDeclaringClass().getName()
                        + "#" + method.getName() + " returns " + method.getGenericReturnType().getTypeName()
                        + ", so JUnit will not run it and the build would pass with nothing tested."
                        + " Declare it void; to wait for asynchronous work inside the body, use"
                        + " AsyncAssert.awaitAsync.");
            }
        }
    }
}
