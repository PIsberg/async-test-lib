package se.deversity.asynctest.extension;

import se.deversity.asynctest.AsyncTest;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;
import org.junit.jupiter.api.extension.*;

import java.util.Collections;
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

        TestTemplateInvocationContext invocationContext = new TestTemplateInvocationContext() {
            @Override
            public String getDisplayName(int invocationIndex) {
                return "[AsyncTest] " + asyncTest.threads() + " threads x " + asyncTest.invocations() + " invocations";
            }

            @Override
            public java.util.List<Extension> getAdditionalExtensions() {
                return Collections.singletonList(new AsyncTestInvocationInterceptor(asyncTest));
            }
        };

        return Stream.of(invocationContext);
    }
}
