package se.deversity.asynctest.fixture

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.AsyncTestContext
import se.deversity.asynctest.FailOn

/**
 * The other direction: the same write and hook under {@code synchronized (this)}. The detector
 * records {@code Thread.holdsLock(owner)} at access time, so a guarded write is not reported.
 * Groovy's {@code synchronized} block compiles to the same monitorenter as Java's, so the probe
 * sees the lock.
 */
class GuardedCounterStaysSilentTest {

    private static AsyncFindings findings

    @BeforeAll
    static void collect() {
        findings = AsyncFindings.collect()
    }

    @AfterAll
    static void noRaceWasReported() {
        try {
            findings.assertNotReported("RaceConditionDetector")
        } finally {
            findings.close()
        }
    }

    private int counter = 0

    @AsyncTest(threads = 8, invocations = 200, failOn = FailOn.NONE, licenseMockMode = true)
    void guardedWritesFromEightThreads() {
        synchronized (this) {
            AsyncTestContext.get()?.sharedRaceConditionDetector()?.recordFieldWrite(this, "counter")
            counter++
        }
    }
}
