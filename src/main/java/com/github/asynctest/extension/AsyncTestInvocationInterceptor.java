package com.github.asynctest.extension;

import com.github.asynctest.AsyncTest;
import com.github.asynctest.AsyncTestConfig;
import com.github.asynctest.runner.ConcurrencyRunner;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

import java.lang.reflect.Method;

public class AsyncTestInvocationInterceptor implements InvocationInterceptor {

    private final AsyncTest asyncTest;

    public AsyncTestInvocationInterceptor(AsyncTest asyncTest) {
        this.asyncTest = asyncTest;
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        // The runner drives all N×M executions via method.invoke() and never calls
        // invocation.proceed() — as the sole InvocationInterceptor we own the execution.
        // Skipping proceed() is valid per JUnit's contract (Fix 6).
        invocation.skip();
        ConcurrencyRunner.execute(invocationContext, AsyncTestConfig.from(asyncTest));
    }
}
