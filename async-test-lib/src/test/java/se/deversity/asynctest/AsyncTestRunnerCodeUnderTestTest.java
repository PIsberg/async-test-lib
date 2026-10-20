package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Which class the runner scopes its default agent attach by for an {@link AsyncTestRunner} body
 * (#1042, #1068): the body's own class, unless that class is a JDK proxy, as a dynamic-Groovy
 * closure coerced to {@link AsyncTestRunner.Body} is. A proxy class lives in {@code jdk.proxyN},
 * so the weaving was scoped to that package and nothing of the caller's was woven; the caller is
 * the class that built the body.
 */
class AsyncTestRunnerCodeUnderTestTest {

    @Test
    void aLambdaBodyNamesItsOwnClass() {
        AsyncTestRunner.Body body = () -> { };
        assertSame(body.getClass(), AsyncTestRunner.codeUnderTest(body));
    }

    @Test
    void aProxyBodyNamesItsCaller() {
        AsyncTestRunner.Body proxy = (AsyncTestRunner.Body) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AsyncTestRunner.Body.class},
                (self, method, args) -> null);
        assertSame(AsyncTestRunnerCodeUnderTestTest.class, AsyncTestRunner.codeUnderTest(proxy),
                "the proxy's class is in jdk.proxyN; the class that called the runner is this one");
    }
}
