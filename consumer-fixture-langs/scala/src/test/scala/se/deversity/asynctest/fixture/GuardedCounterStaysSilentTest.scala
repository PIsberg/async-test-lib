package se.deversity.asynctest.fixture

import org.junit.jupiter.api.{AfterAll, BeforeAll}
import se.deversity.asynctest.{AsyncFindings, AsyncTest, AsyncTestContext, FailOn}

/**
 * The other direction: the same write and hook inside `this.synchronized`. `AnyRef.synchronized`
 * is a monitorenter on `this`, so the detector's `Thread.holdsLock(owner)` probe sees the lock
 * and the write is not reported.
 */
object GuardedCounterStaysSilentTest {
  private var findings: AsyncFindings = scala.compiletime.uninitialized

  @BeforeAll
  def collect(): Unit =
    findings = AsyncFindings.collect()

  @AfterAll
  def noRaceWasReported(): Unit =
    try findings.assertNotReported("RaceConditionDetector")
    finally findings.close()
}

class GuardedCounterStaysSilentTest {

  private var counter = 0

  @AsyncTest(threads = 8, invocations = 200, failOn = FailOn.NONE, licenseMockMode = true)
  def guardedWritesFromEightThreads(): Unit = this.synchronized {
    val ctx = AsyncTestContext.get()
    if (ctx != null) ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter")
    counter += 1
  }
}
