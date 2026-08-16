package se.deversity.asynctest.fixture

import org.junit.jupiter.api.{AfterAll, BeforeAll}
import se.deversity.asynctest.{AsyncFindings, AsyncTest, AsyncTestContext, FailOn}

/**
 * `@AsyncTest` from Scala 3: eight threads write the same `var` with no lock, and
 * `RaceConditionDetector` reports it.
 *
 * The annotation goes on a `def` in a `class` and JUnit discovers it as-is. The JUnit lifecycle
 * methods live in the companion `object`: Scala 3 emits static forwarders for them, which is
 * what JUnit needs for `@BeforeAll` / `@AfterAll`. Under sbt this needs `sbt-jupiter-interface`
 * to run Jupiter at all; this module is Maven and Gradle like the rest of the repository.
 *
 * One Scala-specific fact worth knowing: a `var` compiles to accessors named `counter()` and
 * `counter_$eq(int)`, not `getCounter`/`setCounter`, so the agent's default accessor weaving
 * does not see it. The explicit hook below makes the write observable regardless;
 * `-Dasynctest.agent=fields=true` weaves the field instructions themselves.
 */
object LostUpdateIsReportedTest {
  private var findings: AsyncFindings = scala.compiletime.uninitialized

  @BeforeAll
  def collect(): Unit =
    findings = AsyncFindings.collect()

  @AfterAll
  def theRaceWasReported(): Unit =
    try findings.assertReported("RaceConditionDetector")
    finally findings.close()
}

class LostUpdateIsReportedTest {

  private var counter = 0

  @AsyncTest(threads = 8, invocations = 200, failOn = FailOn.NONE, licenseMockMode = true)
  def unguardedWritesFromEightThreads(): Unit = {
    val ctx = AsyncTestContext.get()
    if (ctx != null) ctx.sharedRaceConditionDetector().recordFieldWrite(this, "counter")
    counter += 1
  }
}
