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
 * The direction that must fire: `runBlocking` called inside a coroutine on a real kotlinx
 * `Dispatchers.Default` worker, woven by the agent, is reported (#1007).
 *
 * The worker is attributed to the run by the woven start inside kotlinx's scheduler, so it must be
 * created during this run: a worker some earlier test in this JVM created belongs to that test. No
 * other class in this module touches a dispatcher, and `KotlinRunBlockingOnCallerIsNotReportedTest`
 * stays off them for this reason.
 */
class KotlinRunBlockingInCoroutineIsReportedTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun theRunBlockingIsReported() {
            try {
                assertTrue(
                    findings.violationsFrom("BlockingOnNonBlockingThread")
                        .any { it.attributes()["report"].toString().contains("blocked in runBlocking") },
                    "a Dispatchers.Default coroutine entered runBlocking. No finding means the call " +
                        "site was not woven (is com.example.kotlinfixture in includes=, and does " +
                        "kotlinc still compile runBlocking against kotlinx.coroutines.BuildersKt?), " +
                        "or the worker was not attributed to the run (is kotlinx.coroutines in " +
                        "includes=, and did an earlier test create the workers?). Findings were: " +
                        findings.violations()
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
    fun runBlockingInsideADefaultCoroutine() {
        bridges.lookupInsideADefaultCoroutine()
    }
}
