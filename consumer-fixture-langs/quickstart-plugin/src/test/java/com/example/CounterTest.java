package com.example;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.lang.management.ManagementFactory;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README's first example, in a build that attaches the agent through the build plugins (#1045):
 * {@code async-test:prepare-agent} under Maven, {@code id("se.deversity.async-test")} under Gradle.
 *
 * <p>The agent must have arrived by {@code -javaagent:} at startup, not by the runner's runtime
 * fallback; under Maven, JaCoCo's agent must have arrived beside it, which is the composition the
 * hand-written {@code argLine} broke unless the user knew to write {@code @{argLine}}; and the
 * bare {@code counter++} must be reported while the same increment under a lock is not.
 */
class CounterTest {

    private static AsyncFindings findings;

    private final Object lock = new Object();
    private int counter = 0;
    private int guarded = 0;

    @BeforeAll
    static void theAgentsAreOnTheCommandLine() {
        List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        assertTrue(arguments.stream().anyMatch(a -> a.startsWith("-javaagent:") && a.contains("async-test-agent")
                        && a.contains("includes=com.example")),
                "the build plugin should start the test JVM with -javaagent:<async-test-agent jar> and the "
                        + "includes this build configures; JVM arguments: " + arguments);
        boolean jacoco = arguments.stream().anyMatch(a -> a.startsWith("-javaagent:") && a.contains("jacoco"));
        assertEquals(Boolean.getBoolean("asynctest.fixture.expectJacoco"), jacoco,
                "JaCoCo's agent and async-test's must compose through argLine under Maven, and only Maven "
                        + "runs JaCoCo here; JVM arguments: " + arguments);
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 10, invocations = 100, detectAll = true)
    void counter_mustBeThreadSafe() {
        counter++;  // compound read-modify-write: not atomic
        synchronized (lock) {
            guarded++;
        }
    }

    @AfterAll
    static void theRaceIsReported() {
        try {
            assertTrue(reported("CounterTest.counter"),
                    "the agent is attached, but counter++ was not reported. Findings were: " + findings.violations());
            assertFalse(reported("CounterTest.guarded"),
                    "the same increment under synchronized (lock) was reported, a false positive. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }

    private static boolean reported(String field) {
        return findings.violations().stream()
                .anyMatch(v -> v.detector().contains("AtomicityValidator")
                        && String.valueOf(v.attributes().get("report")).contains(field));
    }
}
