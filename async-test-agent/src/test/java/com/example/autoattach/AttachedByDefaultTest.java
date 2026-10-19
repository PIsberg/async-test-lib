package com.example.autoattach;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The README's first example, run the way a consumer runs it: the agent artifact on the test
 * classpath, no {@code -javaagent:}, no {@code -Dasynctest.agent}, and nothing in the test that
 * attaches anything.
 *
 * <p>Until the runner attached by default, this class passed and reported nothing: the bare
 * {@code counter++} compiles to a field read and a field write with no call between them, and only
 * the agent's field weaving sees those. It lives in {@code com.example.autoattach}, not beside the
 * agent's own tests, because the default scopes the weaving to the test's project, inferred from its
 * package, and the agent never weaves anything under {@code se.deversity.asynctest}.
 *
 * <p>The test class is loaded and instantiated before the runner attaches, so a finding here also
 * proves the retransformation of an already-loaded class, which the corpus lane, attaching with
 * {@code -javaagent:} at startup, never exercises. The guarded field is the other direction: the
 * same increments under one monitor must stay silent.
 */
@Tag("e2e")
class AttachedByDefaultTest {

    private static AsyncFindings findings;

    private final Object lock = new Object();
    private int racingCounter;
    private int guardedCounter;

    @BeforeAll
    static void noAgentAndNoFlag() {
        assumeTrue(System.getProperty("asynctest.agent") == null,
                "-Dasynctest.agent is set, so this run does not exercise the default");
        assertFalse(TelemetryRegistry.isRunning(),
                "an agent is already running in this JVM before any @AsyncTest ran, so the test "
                        + "would pass without the default attach; this class needs a fork of its own");
        findings = AsyncFindings.collect();
    }

    @AsyncTest(threads = 4, invocations = 25, detectAll = true)
    void counter_mustBeThreadSafe() {
        racingCounter++;
        synchronized (lock) {
            guardedCounter++;
        }
    }

    @AfterAll
    static void theRaceIsFoundWithNoSetup() {
        try {
            assertTrue(TelemetryRegistry.isRunning(),
                    "the agent is on this module's test classpath and no flag was given, so the "
                            + "runner should have attached it before the first round. It did not: "
                            + "check AgentAutoAttach.plan and that ConcurrencyRunner passes the test "
                            + "class.");
            assertTrue(reported("AttachedByDefaultTest.racingCounter"),
                    "the agent attached, but four threads incrementing a field from the test body "
                            + "were not reported by AtomicityValidator: the default arguments do not "
                            + "weave field instructions in the test's package, or the already-loaded "
                            + "test class was not retransformed. Findings were: "
                            + findings.violations());
            assertFalse(reported("AttachedByDefaultTest.guardedCounter"),
                    "a field incremented under synchronized (lock) was reported; the racing field "
                            + "above proves the pipeline is live, so this is a false positive. "
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
