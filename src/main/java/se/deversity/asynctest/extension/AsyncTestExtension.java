package se.deversity.asynctest.extension;

import se.deversity.asynctest.AsyncTest;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;
import org.junit.jupiter.api.extension.*;

import java.util.Collections;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@AIContract(reason = "JUnit 5 TestTemplateInvocationContextProvider SPI. The two overridden methods (supportsTestTemplate, provideTestTemplateInvocationContexts) must preserve their exact signatures as mandated by JUnit.")
@AIPublicAPI
public class AsyncTestExtension implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return context.getTestMethod()
                .map(m -> m.isAnnotationPresent(AsyncTest.class))
                .orElse(false);
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        AsyncTest asyncTest = context.getTestMethod().get().getAnnotation(AsyncTest.class);
        int[] matrix = asyncTest.threadCounts();

        if (matrix.length == 0) {
            // Legacy single-run path: one invocation context using @AsyncTest(threads=...).
            return Stream.of(buildContext(asyncTest, asyncTest.threads()));
        }

        // Schedule-matrix path: one invocation context per threadCounts entry.
        return IntStream.of(matrix)
                .mapToObj(threadCount -> buildContext(asyncTest, threadCount));
    }

    private static TestTemplateInvocationContext buildContext(AsyncTest asyncTest, int threadCount) {
        return new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return "[AsyncTest] " + threadCount + " threads x " + asyncTest.invocations() + " invocations";
            }

            @Override
            public java.util.List<Extension> getAdditionalExtensions() {
                return Collections.singletonList(new AsyncTestInvocationInterceptor(asyncTest, threadCount));
            }
        };
    }
}
