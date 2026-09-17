package com.example.corpus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The companion to {@link TestBodyCollectionIsObservedTest}: instance fields touched directly by the
 * test body itself.
 *
 * <p>Pins the instruction-level weaving path: {@code FieldAccessWeaver} weaves an observation call in
 * front of every {@code GETFIELD} and {@code PUTFIELD} instruction, and captures lock state around
 * {@code MONITORENTER}/{@code MONITOREXIT}. The lane attaches the agent with {@code -javaagent} at
 * JVM startup, so the test class is woven as it loads, and a finding on the unguarded field proves
 * that load-time weaving reaches the test class and that field access instructions are rewritten
 * and forwarded into {@code AtomicityValidator}. It says nothing about retransforming a class loaded
 * before a dynamic attach; this lane never does one.
 *
 * <p>The guarded field provides the symmetric control: every mutation happens inside
 * {@code synchronized (lock)}, so {@code AtomicityValidator} must remain silent on it while reporting
 * the racing field.
 */
class TestBodyFieldIsObservedTest {

    private static AsyncFindings findings;
    private static long eventsBefore;

    private final Object lock = new Object();
    private int racingCounter;
    private int guardedCounter;

    @BeforeAll
    static void collect() {
        findings = AsyncFindings.collect();
        eventsBefore = TelemetryRegistry.publishedEvents();
    }

    @AsyncTest(threads = 4, invocations = 25)
    void fieldMutationsFromTheTestBody() {
        racingCounter++;
        synchronized (lock) {
            guardedCounter++;
        }
    }

    @AfterAll
    static void theBodysOwnFieldAccessesAreObserved() {
        try {
            // 1. True positive: unsynchronized field access across 4 threads MUST be detected
            assertTrue(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("AtomicityValidator")
                                    && String.valueOf(v.attributes().get("report"))
                                            .contains("TestBodyFieldIsObservedTest.racingCounter")),
                    "an unsynchronized field incremented by four threads from the test body must be "
                            + "reported by AtomicityValidator. Nothing was: either the agent no longer "
                            + "weaves the test class as it loads, or FieldAccessWeaver stopped "
                            + "matching GETFIELD/PUTFIELD. Findings were: " + findings.violations() + ". "
                            + agentState());

            // 2. True negative: properly synchronized field access MUST NOT trigger a false positive
            assertFalse(findings.violations().stream()
                            .anyMatch(v -> v.detector().contains("AtomicityValidator")
                                    && String.valueOf(v.attributes().get("report"))
                                            .contains("TestBodyFieldIsObservedTest.guardedCounter")),
                    "a field incremented under synchronized (lock) must not be reported by AtomicityValidator. "
                            + "The racing control above proves the pipeline is live. Findings were: "
                            + findings.violations());
        } finally {
            findings.close();
        }
    }

    /**
     * {@return what can be told, from inside the test, about why nothing was observed}
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
