package se.deversity.asynctest.extension;
import se.deversity.asynctest.E2E;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.Preset;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the 1.7.0 annotation-placement extensions:
 * <ul>
 *   <li>class-level {@code @AsyncTest} configures {@code @TestTemplate} methods,</li>
 *   <li>composed (meta-)annotations carry {@code @AsyncTest} configuration,</li>
 *   <li>a method-level {@code @AsyncTest} wins over the class-level one.</li>
 * </ul>
 */
@E2E
class ClassLevelAsyncTestTest {

    @Test
    void classLevelAnnotationDrivesTestTemplateMethods() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(ClassLevelFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(2).succeeded(2).failed(0));
    }

    @Test
    void composedAnnotationCarriesAsyncTestConfiguration() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(ComposedAnnotationFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(1).succeeded(1).failed(0));

        String name = tests.finished().stream()
                .map(e -> e.getTestDescriptor().getDisplayName())
                .findFirst().orElseThrow();
        assertEquals("[AsyncTest] 3 threads x 1 invocations", name,
                "Composed annotation's @AsyncTest attributes must drive the run");
    }

    @Test
    void methodLevelAnnotationWinsOverClassLevel() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(PrecedenceFixture.class))
                .execute()
                .testEvents();

        tests.assertStatistics(s -> s.started(1).succeeded(1).failed(0));

        String name = tests.finished().stream()
                .map(e -> e.getTestDescriptor().getDisplayName())
                .findFirst().orElseThrow();
        assertEquals("[AsyncTest] 5 threads x 1 invocations", name,
                "Method-level @AsyncTest must take precedence over the class-level one");
    }

    // ---- Fixtures driven through JUnit-platform-testkit ----

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 10_000,
            preset = Preset.NONE, licenseMockMode = true)
    static class ClassLevelFixture {
        @TestTemplate
        void first() {}

        @TestTemplate
        void second() {}
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @AsyncTest(threads = 3, invocations = 1, timeoutMs = 10_000,
            preset = Preset.NONE, licenseMockMode = true)
    @interface QuickAsyncTest {}

    static class ComposedAnnotationFixture {
        @QuickAsyncTest
        void composed() {}
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 10_000,
            preset = Preset.NONE, licenseMockMode = true)
    static class PrecedenceFixture {
        @AsyncTest(threads = 5, invocations = 1, timeoutMs = 10_000,
                preset = Preset.NONE, licenseMockMode = true)
        void methodWins() {}
    }
}
