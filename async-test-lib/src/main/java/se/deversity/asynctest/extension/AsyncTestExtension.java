package se.deversity.asynctest.extension;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.AsyncTest;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

import java.util.Collections;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@AIContract(reason = "JUnit 5 TestTemplateInvocationContextProvider SPI. The two overridden methods (supportsTestTemplate, provideTestTemplateInvocationContexts) must preserve their exact signatures as mandated by JUnit.")
@AIPublicAPI
@API(status = Status.STABLE)
public class AsyncTestExtension implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return resolveAnnotation(context).isPresent();
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        AsyncTest asyncTest = resolveAnnotation(context).orElseThrow(
            () -> new IllegalStateException("No @AsyncTest annotation found on test method or class"));
        int[] matrix = asyncTest.threadCounts();

        if (matrix.length == 0) {
            // Legacy single-run path: one invocation context using @AsyncTest(threads=...).
            return Stream.of(buildContext(asyncTest, asyncTest.threads()));
        }

        // Schedule-matrix path: one invocation context per threadCounts entry.
        return IntStream.of(matrix)
                .mapToObj(threadCount -> buildContext(asyncTest, threadCount));
    }

    /**
     * Resolves the effective {@link AsyncTest} annotation for a test method.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>the test method itself, including meta-annotations — this is what makes
     *       composed annotations like {@code @EssentialsAsyncTest} work;</li>
     *   <li>the test class (also meta-aware), so a class-level {@code @AsyncTest}
     *       provides shared configuration for every {@code @TestTemplate} method.</li>
     * </ol>
     *
     * @since 1.7.0
     */
    private static Optional<AsyncTest> resolveAnnotation(ExtensionContext context) {
        Optional<AsyncTest> onMethod = context.getTestMethod()
                .flatMap(m -> AnnotationSupport.findAnnotation(m, AsyncTest.class));
        if (onMethod.isPresent()) {
            return onMethod;
        }
        return context.getTestClass()
                .flatMap(c -> AnnotationSupport.findAnnotation(c, AsyncTest.class));
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
