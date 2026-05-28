package se.deversity.asynctest.extension;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.runner.ConcurrencyRunner;
import se.deversity.vibetags.annotations.AICore;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;

@AICore(
    sensitivity = "Critical",
    note = "invocation.skip() is intentional — ConcurrencyRunner owns the full N×M execution and must never call invocation.proceed(). Restoring proceed() would run the test body once outside the CyclicBarrier, bypassing all detectors."
)
public class AsyncTestInvocationInterceptor implements InvocationInterceptor {

    private final AsyncTest asyncTest;
    private final int threadCount;

    public AsyncTestInvocationInterceptor(AsyncTest asyncTest) {
        this(asyncTest, asyncTest.threads());
    }

    /**
     * Construct with an explicit thread count, used by the schedule-matrix path
     * in {@code @AsyncTest(threadCounts=...)} where each matrix entry runs with
     * its own count.
     *
     * @since 1.6.0
     */
    public AsyncTestInvocationInterceptor(AsyncTest asyncTest, int threadCount) {
        this.asyncTest = asyncTest;
        this.threadCount = threadCount;
    }

    @Override
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
