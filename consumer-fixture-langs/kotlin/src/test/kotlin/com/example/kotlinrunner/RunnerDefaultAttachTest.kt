package com.example.kotlinrunner

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTestConfig
import se.deversity.asynctest.AsyncTestRunner
import se.deversity.asynctest.DetectorType
import se.deversity.asynctest.telemetry.TelemetryRegistry

/**
 * `AsyncTestRunner.run` from Kotlin with `async-test-agent` on the test classpath and no flag
 * (#1042, #1068): the runner attaches the agent scoped to the package of the body's own class, a
 * Kotlin lambda compiled into this package, so a bare `counter++` in the body is reported with no
 * record call, and the same increment under a lock is not.
 *
 * It runs in a JVM of its own, without the `-javaagent:` the rest of this module starts with,
 * because the default only applies while no agent is running.
 */
class RunnerDefaultAttachTest {

    private val lock = Any()
    private var racingCounter = 0
    private var guardedCounter = 0

    @Test
    fun aBareIncrementInAKotlinBodyIsReportedWithNoFlag() {
        assertFalse(TelemetryRegistry.isRunning(), "an agent is already running; this needs a JVM without one")
        val config = AsyncTestConfig.builder().threads(4).invocations(25)
            .includes(arrayOf(DetectorType.ATOMICITY_VIOLATIONS))
            .timeoutMs(60_000).licenseMockMode(true).build()
        val findings = AsyncTestRunner.run(config, AsyncTestRunner.Body {
            racingCounter++
            synchronized(lock) {
                guardedCounter++
            }
        })
        assertTrue(TelemetryRegistry.isRunning(), "the runner should have attached the agent")
        assertTrue(reported(findings, "racingCounter"),
            "the bare increment was not reported: ${findings.violations()}")
        assertFalse(reported(findings, "guardedCounter"),
            "the guarded increment was reported: ${findings.violations()}")
    }

    private fun reported(findings: AsyncFindings, field: String): Boolean =
        findings.violations().any {
            it.detector().contains("AtomicityValidator") && it.attributes()["report"].toString().contains(field)
        }
}
