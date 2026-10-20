package com.example.scalarunner

import org.junit.jupiter.api.Assertions.{assertFalse, assertTrue}
import org.junit.jupiter.api.Test
import se.deversity.asynctest.{AsyncFindings, AsyncTestConfig, AsyncTestRunner, DetectorType}
import se.deversity.asynctest.telemetry.TelemetryRegistry

/** Two counters, one incremented bare and one under a lock. */
final class Counters {
  private val lock = new Object
  var racing = 0
  var guarded = 0

  def incrementRacing(): Unit = racing += 1

  def incrementGuarded(): Unit = lock.synchronized {
    guarded += 1
  }
}

/**
 * `AsyncTestRunner.run` from Scala with `async-test-agent` on the test classpath and no flag
 * (#1042, #1068): the body is a Scala function converted to `AsyncTestRunner.Body`, compiled
 * into this package, so the runner scopes the agent here; the bare increment is reported and the
 * guarded one is not. It runs in a JVM of its own: the default only applies while no agent runs.
 */
class RunnerDefaultAttachTest {

  @Test
  def aBareIncrementReachedFromAScalaBodyIsReportedWithNoFlag(): Unit = {
    assertFalse(TelemetryRegistry.isRunning(), "an agent is already running; this needs a JVM without one")
    val counters = new Counters
    val config = AsyncTestConfig.builder().threads(4).invocations(25)
      .includes(Array(DetectorType.ATOMICITY_VIOLATIONS))
      .timeoutMs(60000).licenseMockMode(true).build()
    val body: AsyncTestRunner.Body = () => {
      counters.incrementRacing()
      counters.incrementGuarded()
    }
    val findings = AsyncTestRunner.run(config, body)
    assertTrue(TelemetryRegistry.isRunning(), "the runner should have attached the agent")
    assertTrue(reported(findings, "Counters.racing"), s"the bare increment was not reported: ${findings.violations()}")
    assertFalse(reported(findings, "Counters.guarded"), s"the guarded increment was reported: ${findings.violations()}")
  }

  private def reported(findings: AsyncFindings, field: String): Boolean = {
    val it = findings.violations().iterator()
    var found = false
    while (it.hasNext) {
      val v = it.next()
      if (v.detector().contains("AtomicityValidator") && String.valueOf(v.attributes().get("report")).contains(field)) {
        found = true
      }
    }
    found
  }
}
