package com.example.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical case a user writes first: a collection shared by the test body itself.
 *
 * <p>Separate from the corpus because it is not a third-party subject. It pins the path everything
 * else in this module depends on: the test class is loaded by JUnit before the runner attaches the
 * agent, so a finding here proves that retransformation reaches an already-loaded class and that
 * the call site in the body is rewritten. If this goes silent, every zero in the corpus report
 * becomes meaningless rather than informative.
 */
class TestBodyCollectionIsObservedTest {

    private static AsyncFindings findings;
    private static long eventsBefore;

    private final Map<String, Integer> shared = new HashMap<>();

    @BeforeAll
    static void collect() {
        findings = AsyncFindings.collect();
        eventsBefore = TelemetryRegistry.publishedEvents();
    }

    @AsyncTest(threads = 4, invocations = 25)
    void sharedHashMapFromTheTestBody() {
        shared.put("key", shared.size());
    }

    @AfterAll
    static void theBodysOwnCallsAreObserved() {
        try {
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("SharedCollection")),
                    "an unsynchronized HashMap written by four threads from the test body must be "
                            + "reported. Nothing was: either the agent no longer retransforms the "
                            + "already-loaded test class, or CollectionAccessWeaver stopped "
                            + "matching Map.put. Findings were: " + findings.violations() + ". "
                            + agentState());
        } finally {
            findings.close();
        }
    }

    /**
     * {@return what can be told, from inside the test, about why nothing was observed}
     *
     * <p>The failure this test exists for has three causes that read the same from the message
     * alone, and #579 was one run where it could not be told which: the agent was never attached,
     * it attached and wove nothing at all, or it wove and missed this one call site. Whether the
     * JVM was started with the agent separates the first; whether the telemetry counter moved
     * during the class separates the second from the third.
     */
    private static String agentState() {
        boolean agentOnCommandLine = ManagementFactory.getRuntimeMXBean().getInputArguments()
                .stream()
                .anyMatch(argument -> argument.startsWith("-javaagent:")
                        && argument.contains("async-test-agent"));
        long published = TelemetryRegistry.publishedEvents() - eventsBefore;
        return "Agent on the command line: " + agentOnCommandLine
                + "; woven events published during this class: " + published
                + (!agentOnCommandLine ? " (the agent was never attached)"
                        : published == 0 ? " (attached, but nothing woven reached telemetry)"
                        : " (weaving works, so this call site was missed)");
    }
}
