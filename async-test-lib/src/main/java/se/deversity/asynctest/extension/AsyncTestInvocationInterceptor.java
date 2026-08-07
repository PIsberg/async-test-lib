package se.deversity.asynctest.extension;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.runner.ConcurrencyRunner;
import se.deversity.vibetags.annotations.AICore;
import se.deversity.vibetags.annotations.AILoadBearing;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;

/**
 * Intercepts the execution of the test method and delegates it to the {@link ConcurrencyRunner}.
 * 
 * <p>This interceptor skips the default JUnit invocation and instead runs the test body
 * concurrently across multiple threads to maximize the chance of detecting race conditions.
 * 
 * @since 1.0.0
 */
@AICore(
    sensitivity = "Critical",
    note = "invocation.skip() is intentional — ConcurrencyRunner owns the full N×M execution and must never call invocation.proceed(). Restoring proceed() would run the test body once outside the CyclicBarrier, bypassing all detectors."
)
@API(status = Status.STABLE)
public class AsyncTestInvocationInterceptor implements InvocationInterceptor {

    private final AsyncTest asyncTest;
    private final int threadCount;
    /**
     * Creates a AsyncTestInvocationInterceptor.
     *
     * @param asyncTest the annotation on the test method, supplying the run configuration
     */
    public AsyncTestInvocationInterceptor(AsyncTest asyncTest) {
        this(asyncTest, asyncTest.threads());
    }

    /**
     * Construct with an explicit thread count, used by the schedule-matrix path
     * in {@code @AsyncTest(threadCounts=...)} where each matrix entry runs with
     * its own count.
     *
     * @since 1.6.0
     *
     * @param asyncTest the annotation on the test method, supplying the run configuration
     * @param threadCount thread count to use instead of the annotation value, for a parameterised template
     */
    public AsyncTestInvocationInterceptor(AsyncTest asyncTest, int threadCount) {
        this.asyncTest = asyncTest;
        this.threadCount = threadCount;
    }

    @Override
    @AILoadBearing(
        invariant = "This method calls invocation.skip() and never invocation.proceed().",
        breaksIf = "Someone 'fixes' the apparently-dropped invocation by calling proceed(). The "
                 + "test body then runs once on the JUnit thread, outside the CyclicBarrier and "
                 + "outside AsyncTestContext, so no detector observes it — and because that "
                 + "single run usually passes, the suite goes green while every concurrency "
                 + "check has silently stopped running."
    )
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        // The runner drives all N×M executions via method.invoke() and never calls
        // invocation.proceed() — as the sole InvocationInterceptor we own the execution.
        // Skipping proceed() is valid per JUnit's contract (Fix 6).
        invocation.skip();
        ConcurrencyRunner.execute(invocationContext, AsyncTestConfig.from(asyncTest, threadCount));
    }
}
