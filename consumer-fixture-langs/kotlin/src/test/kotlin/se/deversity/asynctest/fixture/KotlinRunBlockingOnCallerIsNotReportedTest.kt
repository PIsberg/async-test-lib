package se.deversity.asynctest.fixture

import com.example.kotlinfixture.RunBlockingBridges
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.DetectorType
import se.deversity.asynctest.FailOn

/**
 * The silent twin of `KotlinRunBlockingInCoroutineIsReportedTest` (#1007): the same woven blocking
 * API, called on the run's own thread, where bridging blocking code to suspending code is what
 * `runBlocking` is for.
 *
 * It stays off every dispatcher on purpose: workers it created would belong to this run, and the
 * firing test, if it ran second, would reuse them unattributed and report nothing.
 */
class KotlinRunBlockingOnCallerIsNotReportedTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun nothingIsReported() {
            try {
                assertTrue(
                    findings.violationsFrom("BlockingOnNonBlockingThread").isEmpty(),
                    "runBlocking on an ordinary thread is its intended use: " + findings.violations()
                )
            } finally {
                findings.close()
            }
        }
    }

    private val bridges = RunBlockingBridges()

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        // The first round loads kotlinx and the agent weaves every class of it as it loads; on a
        // Windows JDK 26 that alone ran past the default 5 s (#1007).
        timeoutMs = 60_000,
        includes = [DetectorType.BLOCKING_ON_NON_BLOCKING_THREAD]
    )
    fun runBlockingOnTheCaller() {
        bridges.lookup()
    }
}
