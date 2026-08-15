package se.deversity.asynctest;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * Executes the Gherkin scenarios in {@code src/test/resources/features/core-flows.feature}
 * against the real engine: each binding runs a nested {@code @AsyncTest} class through
 * {@link EngineTestKit}, the same way {@code AsyncFindingsE2eTest} and
 * {@code DetectAllIntegrationTest} do, and asserts what a consumer would observe.
 *
 * <p>Deliberately dependency-free: the build runs JUnit Platform 6 and Cucumber's engine
 * targets Platform 1.x, so instead of a framework this test parses scenario titles itself and
 * binds each to an executable block. The binding is scenario-level, not step-level; the step
 * lines are the human-readable specification, and the binding must implement all of them. The
 * contract that keeps the feature file honest runs both ways: a scenario with no binding fails
 * the build, and a binding with no scenario fails the build, so prose and execution cannot
 * drift apart silently.
 *
 * <p>Nested classes match {@code *$*} and are excluded from direct surefire discovery; they run
 * only through the kit, which is why this class carries {@link E2E} like every other meta-test.
 */
@E2E
class CoreFlowsBddTest {

    private static final String FEATURE = "/features/core-flows.feature";

    @TestFactory
    List<DynamicTest> everyScenarioInTheFeatureFileRuns() throws IOException {
        List<String> scenarios = scenarioTitles();
        Map<String, ScenarioBinding> bindings = bindings();

        assertEquals(bindings.keySet(), new LinkedHashSet<>(scenarios),
                "feature file and bindings disagree; a scenario without a binding is fiction, "
                        + "and a binding without a scenario is dead code");

        List<DynamicTest> tests = new ArrayList<>();
        for (String title : scenarios) {
            ScenarioBinding binding = bindings.get(title);
            tests.add(DynamicTest.dynamicTest(title, binding::run));
        }
        return tests;
    }

    // -----------------------------------------------------------------------

    @FunctionalInterface
    private interface ScenarioBinding {
        void run() throws Exception;
    }

    private Map<String, ScenarioBinding> bindings() {
        Map<String, ScenarioBinding> map = new LinkedHashMap<>();

        map.put("Every thread runs the body in every round", () -> {
            CountingBody.executions.set(0);
            Events events = run(CountingBody.class);
            assertEquals(0, events.failed().count(), "a plain counting body must not fail");
            assertTrue(events.succeeded().count() > 0, "the template must have executed");
            assertEquals(3 * 4, CountingBody.executions.get(),
                    "3 threads x 4 invocations must run the body exactly 12 times");
        });

        map.put("A detector finding fails the test when failOn is HIGH", () -> {
            try (AsyncFindings findings = AsyncFindings.collect()) {
                Events events = run(RacingFailOnHigh.class);
                assertTrue(events.failed().count() > 0,
                        "an unsynchronized write recorded from two threads must fail the test "
                                + "when failOn is HIGH");
                findings.assertReported("RaceConditionDetector");
            }
        });

        map.put("Report-only mode records the finding and the test stays green", () -> {
            try (AsyncFindings findings = AsyncFindings.collect()) {
                Events events = run(RacingReportOnly.class);
                assertEquals(0, events.failed().count(),
                        "failOn = NONE means the finding is reported, not thrown");
                findings.assertReported("RaceConditionDetector");
            }
        });

        map.put("An excluded detector stays silent", () -> {
            try (AsyncFindings findings = AsyncFindings.collect()) {
                Events events = run(RacingWithRaceExcluded.class);
                assertEquals(0, events.failed().count(),
                        "with RACE_CONDITIONS excluded the recorded write must not fail the test");
                assertTrue(findings.violationsFrom("RaceConditionDetector").isEmpty(),
                        "an excluded detector must report nothing");
            }
        });

        map.put("A run configured to execute nothing is refused", () -> {
            Events events = run(ZeroInvocations.class);
            assertTrue(events.failed().count() > 0,
                    "invocations = 0 would run the body zero times while JUnit reports a pass; "
                            + "it must be refused");
            String messages = events.failed().stream()
                    .map(e -> e.getPayload(org.junit.platform.engine.TestExecutionResult.class)
                            .flatMap(org.junit.platform.engine.TestExecutionResult::getThrowable)
                            .map(Throwable::getMessage)
                            .orElse(""))
                    .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(messages.contains("invocations"),
                    "the refusal must name the offending attribute; was: " + messages);
        });

        return map;
    }

    private static Events run(Class<?> testClass) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(testClass))
                .execute()
                .testEvents();
    }

    /** Scenario titles, in file order, from the {@code Scenario:} lines of the feature file. */
    private List<String> scenarioTitles() throws IOException {
        try (InputStream in = CoreFlowsBddTest.class.getResourceAsStream(FEATURE)) {
            assertNotNull(in, "feature file missing from the test classpath: " + FEATURE);
            List<String> titles = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String stripped = line.strip();
                if (stripped.startsWith("Scenario:")) {
                    titles.add(stripped.substring("Scenario:".length()).strip());
                }
            }
            return titles;
        }
    }

    // -----------------------------------------------------------------------
    // the subjects: nested classes the kit runs, one per scenario shape
    // -----------------------------------------------------------------------

    /** 3 threads x 4 invocations, no detectors: the body must run exactly 12 times. */
    public static class CountingBody {
        static final AtomicInteger executions = new AtomicInteger();

        @AsyncTest(threads = 3, invocations = 4, detectAll = false, failOn = FailOn.NONE)
        void count() {
            executions.incrementAndGet();
        }
    }

    /**
     * The library sees field access through instrumentation, not by magic: a bare
     * {@code counter++} is invisible to {@code RaceConditionDetector}, so the body records the
     * write it is about to make. Same shape as {@code DetectAllIntegrationTest.RaceTestWithDetectAll}.
     */
    public static class RacingFailOnHigh {
        private int counter = 0;

        @AsyncTest(threads = 2, invocations = 10, detectAll = true, failOn = FailOn.HIGH)
        void race() {
            AsyncTestContext ctx = AsyncTestContext.get();
            if (ctx != null) {
                ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter");
            }
            counter++;
        }
    }

    /** Same race, report-only. */
    public static class RacingReportOnly {
        private int counter = 0;

        @AsyncTest(threads = 2, invocations = 10, detectAll = true, failOn = FailOn.NONE)
        void race() {
            AsyncTestContext ctx = AsyncTestContext.get();
            if (ctx != null) {
                ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter");
            }
            counter++;
        }
    }

    /**
     * Same race with the detector excluded. "Excluded" is observable from inside the body: the
     * shared accessor returns {@code null} for a detector that is not enabled, so a body that
     * records unconditionally fails with an NPE rather than a finding (which is how the first
     * draft of this scenario went red). A consumer records only when the detector is there.
     */
    public static class RacingWithRaceExcluded {
        private int counter = 0;

        @AsyncTest(threads = 2, invocations = 10, detectAll = true,
                excludes = {DetectorType.RACE_CONDITIONS, DetectorType.LIVELOCKS},
                failOn = FailOn.HIGH)
        void race() {
            AsyncTestContext ctx = AsyncTestContext.get();
            if (ctx != null && ctx.sharedRaceConditionDetector() != null) {
                ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter");
            }
            counter++;
        }
    }

    /** The silent-zero-execution shape: must be refused at configuration time. */
    public static class ZeroInvocations {
        @AsyncTest(threads = 2, invocations = 0)
        void neverRuns() {
            throw new AssertionError("the body must never run when invocations is 0");
        }
    }
}
