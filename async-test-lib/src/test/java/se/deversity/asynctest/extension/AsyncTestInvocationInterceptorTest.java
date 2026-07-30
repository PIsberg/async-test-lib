package se.deversity.asynctest.extension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.Preset;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AsyncTestInvocationInterceptor}.
 *
 * <p><strong>Critical semantics under test (see CLAUDE.md core_elements note on this class):</strong>
 * the interceptor must call {@code invocation.skip()} and must <em>never</em> call
 * {@code invocation.proceed()}. {@link se.deversity.asynctest.runner.ConcurrencyRunner} owns the
 * full N x M execution of the test body via reflection; restoring {@code proceed()} would run the
 * test body once, outside the {@code CyclicBarrier}, bypassing every detector.
 *
 * <p>The project declares no mocking library (checked in {@code pom.xml}: only
 * {@code junit-jupiter-api/engine}, {@code junit-platform-testkit}, {@code jazzer-api},
 * {@code archunit-junit5} as test-scoped dependencies — no Mockito), so this test hand-rolls
 * minimal fakes for {@link InvocationInterceptor.Invocation} and
 * {@link ReflectiveInvocationContext}. The {@code ExtensionContext} parameter of
 * {@code interceptTestTemplateMethod} is never read by the interceptor's implementation (it is
 * only forwarded nowhere — {@link se.deversity.asynctest.runner.ConcurrencyRunner#execute} does
 * not take it), so {@code null} is passed for it directly rather than faking that large SPI
 * interface.
 *
 * <p>Because {@code interceptTestTemplateMethod} drives the real
 * {@link se.deversity.asynctest.runner.ConcurrencyRunner#execute}, which in turn calls
 * {@link se.deversity.asynctest.runner.LicenseGuard#check}, {@code license.mock.mode} is set for
 * the duration of each test and restored afterward — matching how the build itself runs tests
 * (see {@code -Dlicense.mock.mode=true} in {@code pom.xml}).
 */
class AsyncTestInvocationInterceptorTest {

    private String previousLicenseMockMode;

    @BeforeEach
    void enableLicenseMockMode() {
        previousLicenseMockMode = System.getProperty("license.mock.mode");
        System.setProperty("license.mock.mode", "true");
    }

    @AfterEach
    void restoreLicenseMockMode() {
        if (previousLicenseMockMode == null) {
            System.clearProperty("license.mock.mode");
        } else {
            System.setProperty("license.mock.mode", previousLicenseMockMode);
        }
    }

    @Test
    void interceptTestTemplateMethod_skipsInvocationAndNeverProceeds() throws Throwable {
        Fixture fixture = new Fixture();
        Method method = Fixture.class.getDeclaredMethod("noop");
        AsyncTest asyncTestAnnotation = method.getAnnotation(AsyncTest.class);
        assertNotNull(asyncTestAnnotation, "fixture method must carry @AsyncTest to obtain a real annotation instance");

        AsyncTestInvocationInterceptor interceptor =
            new AsyncTestInvocationInterceptor(asyncTestAnnotation);

        RecordingInvocation invocation = new RecordingInvocation();
        FakeInvocationContext context = new FakeInvocationContext(fixture, method, List.of());

        interceptor.interceptTestTemplateMethod(invocation, context, null);

        assertTrue(invocation.skipCalled, "interceptor must call invocation.skip()");
        assertFalse(invocation.proceedCalled,
            "interceptor must NEVER call invocation.proceed() -- ConcurrencyRunner owns the N x M execution");
    }

    @Test
    void interceptTestTemplateMethod_drivesExecutionViaConcurrencyRunner_notInvocationProceed() throws Throwable {
        Fixture fixture = new Fixture();
        Method method = Fixture.class.getDeclaredMethod("noop");
        AsyncTest asyncTestAnnotation = method.getAnnotation(AsyncTest.class);

        AsyncTestInvocationInterceptor interceptor =
            new AsyncTestInvocationInterceptor(asyncTestAnnotation);

        RecordingInvocation invocation = new RecordingInvocation();
        FakeInvocationContext context = new FakeInvocationContext(fixture, method, List.of());

        interceptor.interceptTestTemplateMethod(invocation, context, null);

        // If proceed() had been (re)introduced, the test body would run exactly once, outside
        // the barrier, in addition to (or instead of) the runner-driven executions below. Pinning
        // the exact threads*invocations count demonstrates the runner -- not invocation.proceed() --
        // is the sole driver of test-body execution.
        assertEquals(Fixture.THREADS * Fixture.INVOCATIONS, fixture.executions.get(),
            "ConcurrencyRunner must drive exactly threads*invocations executions of the test body");
        assertFalse(invocation.proceedCalled);
    }

    @Test
    void twoArgConstructor_usesExplicitThreadCountOverride() throws Throwable {
        // Covers the schedule-matrix constructor path used by @AsyncTest(threadCounts = {...}),
        // where each matrix entry runs with its own thread count independent of threads().
        Fixture fixture = new Fixture();
        Method method = Fixture.class.getDeclaredMethod("noop");
        AsyncTest asyncTestAnnotation = method.getAnnotation(AsyncTest.class);

        int overrideThreadCount = 3;
        AsyncTestInvocationInterceptor interceptor =
            new AsyncTestInvocationInterceptor(asyncTestAnnotation, overrideThreadCount);

        RecordingInvocation invocation = new RecordingInvocation();
        FakeInvocationContext context = new FakeInvocationContext(fixture, method, List.of());

        interceptor.interceptTestTemplateMethod(invocation, context, null);

        assertTrue(invocation.skipCalled);
        assertFalse(invocation.proceedCalled);
        assertEquals(overrideThreadCount * Fixture.INVOCATIONS, fixture.executions.get(),
            "explicit threadCount constructor argument must override asyncTest.threads()");
    }

    // ---- Fixture: minimal @AsyncTest method used purely to obtain a real annotation instance ----

    static class Fixture {
        static final int THREADS = 2;
        static final int INVOCATIONS = 2;

        final AtomicInteger executions = new AtomicInteger(0);

        // preset = NONE keeps this fast: it disables every detector (see AsyncTestConfig.build()),
        // so the runner only drives the barrier + reflective invoke, nothing else.
        @AsyncTest(threads = THREADS, invocations = INVOCATIONS, useVirtualThreads = false,
                   timeoutMs = 5_000, preset = Preset.NONE, detectDeadlocks = false)
        void noop() {
            executions.incrementAndGet();
        }
    }

    // ---- Hand-rolled fakes (no Mockito declared in pom.xml) ----

    /**
     * Records whether {@link #skip()} / {@link #proceed()} were invoked. {@link #proceed()}
     * intentionally does not run the fixture's test body itself -- the interceptor under test is
     * never supposed to call it, so any test that observes {@code proceedCalled == true} has
     * already failed regardless of what this method returns.
     */
    static final class RecordingInvocation implements InvocationInterceptor.Invocation<Void> {
        volatile boolean skipCalled;
        volatile boolean proceedCalled;

        @Override
        public Void proceed() {
            proceedCalled = true;
            return null;
        }

        @Override
        public void skip() {
            skipCalled = true;
        }
    }

    static final class FakeInvocationContext implements ReflectiveInvocationContext<Method> {
        private final Object target;
        private final Method method;
        private final List<Object> arguments;

        FakeInvocationContext(Object target, Method method, List<Object> arguments) {
            this.target = target;
            this.method = method;
            this.arguments = arguments;
        }

        @Override
        public Class<?> getTargetClass() {
            return target.getClass();
        }

        @Override
        public Method getExecutable() {
            return method;
        }

        @Override
        public List<Object> getArguments() {
            return arguments;
        }

        @Override
        public Optional<Object> getTarget() {
            return Optional.of(target);
        }
    }
}
