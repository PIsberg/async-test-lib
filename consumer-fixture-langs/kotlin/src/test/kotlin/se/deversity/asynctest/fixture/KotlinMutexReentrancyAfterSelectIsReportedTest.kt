package se.deversity.asynctest.fixture

import com.example.kotlinfixture.MutexBridges
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.DetectorType
import se.deversity.asynctest.FailOn

/**
 * A coroutine `Mutex` taken through `select { mutex.onLock { } }` and then locked again by the same
 * coroutine (#1220), against real kotlinx. `select` is inline, so the clause is registered in the
 * function it is written in, and the agent passes that function's continuation: the `runBlocking`
 * lambda in the first case, a named suspend function with an owner of its own in the second. Its own
 * class, so the findings it asserts come from these two shapes alone.
 */
class KotlinMutexReentrancyAfterSelectIsReportedTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun everyReentryIsReported() {
            try {
                val reports = findings.violationsFrom("KotlinMutexReentrancy")
                    .map { it.attributes()["report"].toString() }
                assertTrue(
                    reports.any { it.contains("ScopeCoroutine") && !it.contains("SupervisorCoroutine") },
                    "a lock inside coroutineScope, under a select onLock in a runBlocking lambda, was not " +
                        "reported. Findings were: " + findings.violations()
                )
                assertTrue(
                    reports.any { it.contains("SupervisorCoroutine") },
                    "a withLock inside supervisorScope, under a select onLock with an owner in a suspend function, was not " +
                        "reported. Findings were: " + findings.violations()
                )
            } finally {
                findings.close()
            }
        }
    }

    private val bridges = MutexBridges()

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun lockAfterSelect() {
        assertFalse(bridges.reenterAfterSelect(), "the inner lock was taken: kotlinx Mutex was reentrant?")
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun lockAfterSelectWithAnOwner() {
        assertFalse(bridges.reenterAfterSelectWithAnOwner(),
            "the inner lock was taken: kotlinx Mutex was reentrant?")
    }
}
