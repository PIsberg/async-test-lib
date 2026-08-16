package se.deversity.asynctest.fixture

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.AsyncTestContext
import se.deversity.asynctest.FailOn

/**
 * {@code @AsyncTest} from a plain JUnit 5 class written in Groovy: eight threads write the same
 * field with no lock, and {@code RaceConditionDetector} reports it.
 *
 * Plain JUnit 5, not Spock: Spock is its own JUnit Platform engine, and a {@code @TestTemplate}
 * such as {@code @AsyncTest} is a Jupiter concept, so it does not run inside a Spock
 * {@code Specification}. The finding is asserted in {@code @AfterAll} because detectors analyse
 * after the last round.
 */
class LostUpdateIsReportedTest {

    private static AsyncFindings findings

    @BeforeAll
    static void collect() {
        findings = AsyncFindings.collect()
    }

    @AfterAll
    static void theRaceWasReported() {
        try {
            findings.assertReported("RaceConditionDetector")
        } finally {
            findings.close()
        }
    }

    private int counter = 0

    @AsyncTest(threads = 8, invocations = 200, failOn = FailOn.NONE, licenseMockMode = true)
    void unguardedWritesFromEightThreads() {
        AsyncTestContext.get()?.sharedRaceConditionDetector()?.recordFieldWrite(this, "counter")
        counter++
    }
}
