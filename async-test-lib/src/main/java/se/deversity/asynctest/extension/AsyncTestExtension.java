package se.deversity.asynctest.extension;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.AsyncTest;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * JUnit 5 extension that executes methods annotated with {@link AsyncTest}.
 * 
 * <p>This extension acts as a {@link TestTemplateInvocationContextProvider}, 
 * transforming a single test template method into multiple concurrent invocations
 * managed by the {@code async-test} engine.
 * 
 * @since 1.0.0
 */
@AIContract(reason = "JUnit 5 TestTemplateInvocationContextProvider SPI. The two overridden methods (supportsTestTemplate, provideTestTemplateInvocationContexts) must preserve their exact signatures as mandated by JUnit.")
@AIPublicAPI
@API(status = Status.STABLE)
public class AsyncTestExtension
        implements TestTemplateInvocationContextProvider, BeforeEachCallback {

    private static final Logger log = LoggerFactory.getLogger(AsyncTestExtension.class);

    /** Classes already warned about, so the message appears once rather than once per method. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * Warns when a class carries {@code @AsyncTest} but a method in it runs as an ordinary
     * {@code @Test}.
     *
     * <p><strong>The trap.</strong> Class-level {@code @AsyncTest} is a documented feature for
     * sharing configuration, and it works only for methods declared as {@code @TestTemplate} —
     * JUnit never consults a template provider for a {@code @Test} method. Writing {@code @Test}
     * is the reflex; {@code @TestTemplate} is the unusual spelling. So the natural reading of
     * "class-level configuration" produces methods that run once, single-threaded, with no
     * barrier, no detectors and no licence check, and pass. Nothing in the output distinguishes
     * that from a real concurrent run except a missing display name.
     *
     * <p><strong>Why this warns instead of failing.</strong> A class holding both async templates
     * and ordinary unit tests is legitimate, and at runtime it is indistinguishable from the
     * mistake. Failing would break correct suites to catch an ambiguous one.
     *
     * @param context the extension context for the method about to run
     */
    @Override
    public void beforeEach(ExtensionContext context) {
        Optional<Method> testMethod = context.getTestMethod();
        if (testMethod.isEmpty()) {
            return;
        }
        Method method = testMethod.get();
        // A template method's own invocations also reach beforeEach; those are running correctly.
        if (AnnotationSupport.isAnnotated(method, AsyncTest.class)
                || AnnotationSupport.isAnnotated(method, TestTemplate.class)) {
            return;
        }
        Class<?> declaring = method.getDeclaringClass();
        if (!AnnotationSupport.isAnnotated(declaring, AsyncTest.class)) {
            return;
        }
        if (!WARNED.add(declaring.getName())) {
            return;
        }
        log.warn("runner.asynctest.not-applied class={} method={} "
                + "hint=\"this class is annotated @AsyncTest but this method is a plain @Test, so "
                + "it ran once on one thread with no barrier and no detectors. Class-level "
                + "@AsyncTest supplies configuration to @TestTemplate methods; JUnit does not "
                + "route @Test methods through a template provider. Annotate the method with "
                + "@AsyncTest, or declare it @TestTemplate to pick up the class-level settings. "
                + "Ignore this if the method is meant to be an ordinary unit test.\"",
            declaring.getName(), method.getName());
    }
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
