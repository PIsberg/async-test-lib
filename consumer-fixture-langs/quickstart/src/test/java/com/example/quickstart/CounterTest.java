package com.example.quickstart;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The README's first example, in a build configured exactly as the README's quick start says.
 *
 * <p>The README's version fails the test with {@code failOn = FailOn.CRITICAL}; this one asserts the
 * finding instead, so the fixture passes when the quick start works. Three things must hold:
 * the agent arrived by {@code -javaagent:} at startup (not the runner's runtime fallback, which a
 * future JDK will refuse), the bare {@code counter++} is reported, and the same increment under a
 * lock is not.
 */
class CounterTest {

    private static AsyncFindings findings;

    private final Object lock = new Object();
    private int counter = 0;
    private int guarded = 0;

    @BeforeAll
    static void theAgentIsOnTheCommandLine() {
        assertTrue(ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                        .anyMatch(argument -> argument.startsWith("-javaagent:")
                                && argument.contains("async-test-agent")),
                "the quick-start build should start the test JVM with -javaagent:<async-test-agent jar>;"
                        + " it did not, so the README's snippet no longer attaches the agent at startup."
                        + " JVM arguments: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
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
                    "the agent is attached, but the README's counter++ was not reported. Findings were: "
                            + findings.violations());
            assertFalse(reported("CounterTest.guarded"),
                    "the same increment under synchronized (lock) was reported, a false positive. "
                            + "Findings were: " + findings.violations());
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
