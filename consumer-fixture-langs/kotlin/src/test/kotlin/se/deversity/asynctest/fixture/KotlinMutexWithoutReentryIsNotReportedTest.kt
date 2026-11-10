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
 * The silent twin of `KotlinMutexReentrancyIsReportedTest` (#1007): the same woven `Mutex` calls in
 * shapes that never wait for their own coroutine. A mutex locked twice in a row; a `launch` child
 * that waits its turn behind its parent; the run's workers contending for one mutex; a `tryLock`
 * inside the lock, which backs off rather than waits; a lock that waits for another coroutine
 * after an unlock and a lock the agent never saw (#1221); and a lock after a `select` whose
 * `onLock` clause lost, or whose mutex was unlocked again (#1220).
 */
class KotlinMutexWithoutReentryIsNotReportedTest {

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
                    findings.violationsFrom("KotlinMutexReentrancy").isEmpty(),
                    "no coroutine here waits for a mutex it holds: " + findings.violations()
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
    fun lockTwiceInARow() {
        bridges.lockTwiceInARow()
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun launchedChildWaitsItsTurn() {
        bridges.childWaitsItsTurn()
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun workersContend() {
        bridges.contend()
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun tryLockInsideTheLockBacksOff() {
        assertFalse(bridges.tryLockInsideTheLock(), "tryLock took a mutex its own coroutine holds")
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun lockAgainAfterAnUnseenHandOver() {
        assertTrue(bridges.lockAgainAfterAnUnseenHandOver(), "the lock after the hand-over was never taken")
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun lockAfterTryLockIsReleased() {
        assertTrue(bridges.lockAfterTryLockIsReleased(), "the lock after the released tryLock was never taken")
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun lockAfterALostSelect() {
        assertTrue(bridges.lockAfterALostSelect(), "the lock after the lost select was never taken")
    }

    @AsyncTest(
        threads = 4,
        invocations = 4,
        failOn = FailOn.NONE,
        licenseMockMode = true,
        timeoutMs = 60_000,
        includes = [DetectorType.KOTLIN_MUTEX_REENTRANCY]
    )
    fun lockAfterSelectIsReleased() {
        assertTrue(bridges.lockAfterSelectIsReleased(), "the lock after the released select was never taken")
    }
}
