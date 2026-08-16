package se.deversity.asynctest.fixture

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.AsyncTestContext
import se.deversity.asynctest.FailOn

/**
 * `@AsyncTest` from Kotlin, the direction that must fire: eight threads write the same field with
 * no lock, and `RaceConditionDetector` reports it.
 *
 * Nothing here is Kotlin-specific beyond syntax. `@AsyncTest` is a JUnit 5 `@TestTemplate`, its
 * attributes are named arguments, and the JUnit lifecycle methods live in a `companion object`
 * with `@JvmStatic` because JUnit needs them static.
 *
 * The finding is asserted in `@AfterAll`, not in the body: detectors analyse after the last round,
 * so a finding cannot be observed from inside the test. `failOn = FailOn.NONE` keeps the report
 * from failing the test itself, so the assertion below is the only thing that can go red.
 */
class LostUpdateIsReportedTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun theRaceWasReported() {
            try {
                findings.assertReported("RaceConditionDetector")
            } finally {
                findings.close()
            }
        }
    }

    private var counter = 0

    @AsyncTest(threads = 8, invocations = 200, failOn = FailOn.NONE, licenseMockMode = true)
    fun unguardedWritesFromEightThreads() {
        // The hook is what makes the write observable to the detector without the agent; a
        // consumer using -Dasynctest.agent=fields=true gets the same event from the weaver.
        AsyncTestContext.get()?.sharedRaceConditionDetector()?.recordFieldWrite(this, "counter")
        counter++
    }
}
