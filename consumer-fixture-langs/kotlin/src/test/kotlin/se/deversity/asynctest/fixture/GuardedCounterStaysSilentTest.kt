package se.deversity.asynctest.fixture

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.AsyncTestContext
import se.deversity.asynctest.FailOn

/**
 * The other direction: the same write, the same hook, under `synchronized(this)`. The detector
 * records `Thread.holdsLock(owner)` on the accessing thread at access time, so a guarded write
 * is not a race and must not be reported. Without this class the fixture above could be
 * satisfied by a detector that reports every write it sees.
 */
class GuardedCounterStaysSilentTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun noRaceWasReported() {
            try {
                findings.assertNotReported("RaceConditionDetector")
            } finally {
                findings.close()
            }
        }
    }

    private var counter = 0

    @AsyncTest(threads = 8, invocations = 200, failOn = FailOn.NONE, licenseMockMode = true)
    fun guardedWritesFromEightThreads() {
        synchronized(this) {
            AsyncTestContext.get()?.sharedRaceConditionDetector()?.recordFieldWrite(this, "counter")
            counter++
        }
    }
}
