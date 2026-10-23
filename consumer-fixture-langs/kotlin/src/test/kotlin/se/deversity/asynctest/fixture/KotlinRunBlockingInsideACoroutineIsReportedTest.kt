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
 * The two `runBlocking` misuses that run on no `CoroutineScheduler` worker (#1007): nested in
 * another `runBlocking` on the run's own thread, and on a single-threaded executor dispatcher,
 * whose thread is a plain `Thread`. Both are recognised by the `kotlinx.coroutines` frames below
 * the call, so the thread class says nothing and the rule still fires.
 *
 * The dispatcher is a bare `ScheduledThreadPoolExecutor`, created and closed inside each call: the
 * woven `execute` hands its task, and the run, to that pool class, so the thread is attributed while
 * the task runs. `newSingleThreadContext` wraps its pool in a wrapper the hook does not look through
 * and its thread is never attributed (#1107), which is why it is not the subject here.
 */
class KotlinRunBlockingInsideACoroutineIsReportedTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun bothRunBlockingsAreReported() {
            try {
                val reports = findings.violationsFrom("BlockingOnNonBlockingThread")
                    .map { it.attributes()["report"].toString() }
                    .filter { it.contains("blocked in runBlocking") && it.contains("inside a coroutine (") }
                assertTrue(
                    reports.any { !it.contains("single-thread-dispatcher") },
                    "a runBlocking nested in another, on the run's own thread, was not reported. " +
                        "Findings were: " + findings.violations()
                )
                assertTrue(
                    reports.any { it.contains("single-thread-dispatcher") },
                    "a runBlocking on a single-threaded executor dispatcher was not reported: either the " +
                        "kotlinx frame was not seen, or the pool thread was not attributed to the run " +
                        "(is the woven execute still handing tasks to a ScheduledThreadPoolExecutor?). " +
                        "Findings were: " + findings.violations()
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
        timeoutMs = 60_000,
        includes = [DetectorType.BLOCKING_ON_NON_BLOCKING_THREAD]
    )
    fun runBlockingNestedInARunBlocking() {
        bridges.lookupInsideARunBlocking()
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.BLOCKING_ON_NON_BLOCKING_THREAD]
    )
    fun runBlockingOnASingleThreadDispatcher() {
        bridges.lookupOnASingleThreadDispatcher()
    }
}
