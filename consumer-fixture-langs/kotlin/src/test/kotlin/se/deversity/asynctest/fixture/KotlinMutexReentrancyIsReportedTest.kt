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
 * A coroutine `Mutex` locked again by the coroutine that holds it (#1007), against real kotlinx: a
 * `withLock` inside `withContext` on the same thread, the same with the block on another
 * dispatcher, and a bare `lock()` inside `coroutineScope`. In each the outer coroutine is suspended
 * until the block completes, so the inner lock waits for an unlock that cannot come; a
 * `withTimeoutOrNull` ends it, and the body asserts the inner lock was never taken, so the shape
 * under test is the real deadlock and not a lookalike.
 */
class KotlinMutexReentrancyIsReportedTest {

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
                    reports.any { it.contains("UndispatchedCoroutine") },
                    "a withLock inside withContext on the same thread was not reported. Findings were: " +
                        findings.violations()
                )
                assertTrue(
                    reports.any { it.contains("-> DispatchedCoroutine") || it.contains("until DispatchedCoroutine") },
                    "a withLock inside withContext on another dispatcher was not reported: was the " +
                        "executor thread attributed to the run? Findings were: " + findings.violations()
                )
                assertTrue(
                    reports.any { it.contains("ScopeCoroutine") },
                    "a bare lock() inside coroutineScope was not reported. Findings were: " +
                        findings.violations()
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
    fun withLockInsideWithContext() {
        assertFalse(bridges.reenterThroughWithContext(), "the inner lock was taken: kotlinx Mutex was reentrant?")
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun withLockOnAnotherDispatcher() {
        assertFalse(bridges.reenterOnAnotherDispatcher(), "the inner lock was taken: kotlinx Mutex was reentrant?")
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun bareLockInsideCoroutineScope() {
        assertFalse(bridges.reenterWithBareLock(), "the inner lock was taken: kotlinx Mutex was reentrant?")
    }
}
