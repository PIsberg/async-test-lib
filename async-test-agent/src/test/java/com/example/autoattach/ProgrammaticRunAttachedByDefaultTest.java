package com.example.autoattach;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link AttachedByDefaultTest} for a body run through {@link AsyncTestRunner}, the entry point for
 * Kotlin, Scala, Groovy and Clojure suites that are not Jupiter (#1042).
 *
 * <p>The runner infers the package to weave from the class it is handed. For an {@code @AsyncTest}
 * that is the test class; for {@code AsyncTestRunner.run} it was the runner's own holder class in
 * {@code se.deversity.asynctest}, which the agent never weaves, so the default attached nothing and
 * a bare field race went unreported unless the user set {@code -Dasynctest.agent}. The body's own
 * class, a lambda here, lives in the caller's package. The guarded field is the other direction.
 */
@Tag("e2e")
class ProgrammaticRunAttachedByDefaultTest {

    private final Object lock = new Object();
    private int racingCounter;
    private int guardedCounter;

    @Test
    void aBodyRunThroughAsyncTestRunnerIsWovenWithNoFlag() throws Throwable {
        assumeTrue(System.getProperty("asynctest.agent") == null,
                "-Dasynctest.agent is set, so this run does not exercise the default");
        assertFalse(TelemetryRegistry.isRunning(),
                "an agent is already running in this JVM, so the test would pass without the default "
                        + "attach; this class needs a fork of its own");

        AsyncFindings findings = AsyncTestRunner.run(AsyncTestConfig.builder().threads(4).invocations(25)
                .includes(new DetectorType[] {DetectorType.ATOMICITY_VIOLATIONS})
                .timeoutMs(60_000).licenseMockMode(true).build(), () -> {
            racingCounter++;
            synchronized (lock) {
                guardedCounter++;
            }
        });

        assertTrue(TelemetryRegistry.isRunning(),
                "the agent is on this module's test classpath and no flag was given, so the runner "
                        + "should have attached it, inferring the package from the body's class");
        assertTrue(reported(findings, "ProgrammaticRunAttachedByDefaultTest.racingCounter"),
                "the agent attached, but four threads incrementing a field from the body were not "
                        + "reported: the default include does not cover the caller's package. Findings: "
                        + findings.violations());
        assertFalse(reported(findings, "ProgrammaticRunAttachedByDefaultTest.guardedCounter"),
                "a field incremented under synchronized (lock) was reported: " + findings.violations());
    }

    private static boolean reported(AsyncFindings findings, String field) {
        return findings.violations().stream()
                .anyMatch(v -> v.detector().contains("AtomicityValidator")
                        && String.valueOf(v.attributes().get("report")).contains(field));
    }
}
