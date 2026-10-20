package com.example.groovyrunner

import org.junit.jupiter.api.Test
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTestConfig
import se.deversity.asynctest.AsyncTestRunner
import se.deversity.asynctest.DetectorType
import se.deversity.asynctest.telemetry.TelemetryRegistry

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * {@code AsyncTestRunner.run} from Groovy with {@code async-test-agent} on the test classpath and
 * no flag (#1042, #1068), the body a dynamic-Groovy closure coerced to
 * {@code AsyncTestRunner.Body}, the way a Spock specification would pass one. The runner must
 * still scope the agent to this package, so the bare increment in {@link Counters} is reported and
 * the guarded one is not. It runs in a JVM of its own: the default only applies while no agent is
 * running.
 */
class RunnerDefaultAttachTest {

    @Test
    void aBareIncrementReachedFromAGroovyClosureIsReportedWithNoFlag() {
        assertFalse(TelemetryRegistry.isRunning(), 'an agent is already running; this needs a JVM without one')
        Counters counters = new Counters()
        AsyncTestConfig config = AsyncTestConfig.builder().threads(4).invocations(25)
                .includes([DetectorType.ATOMICITY_VIOLATIONS] as DetectorType[])
                .timeoutMs(60_000).licenseMockMode(true).build()
        AsyncFindings findings = AsyncTestRunner.run(config, {
            counters.incrementRacing()
            counters.incrementGuarded()
        } as AsyncTestRunner.Body)
        assertTrue(TelemetryRegistry.isRunning(), 'the runner should have attached the agent')
        assertTrue(reported(findings, 'Counters.racing'),
                "the bare increment was not reported: ${findings.violations()}".toString())
        assertFalse(reported(findings, 'Counters.guarded'),
                "the guarded increment was reported: ${findings.violations()}".toString())
    }

    private static boolean reported(AsyncFindings findings, String field) {
        findings.violations().any {
            it.detector().contains('AtomicityValidator') && String.valueOf(it.attributes().get('report')).contains(field)
        }
    }
}
