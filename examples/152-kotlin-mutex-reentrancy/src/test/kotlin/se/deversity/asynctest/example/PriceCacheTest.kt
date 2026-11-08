package se.deversity.asynctest.example

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import se.deversity.asynctest.diagnostics.IssueSeverity
import se.deversity.asynctest.diagnostics.KotlinMutexReentrancyDetector

/**
 * Test for PriceCache.
 *
 * ========================================================================
 * DETECTOR: KotlinMutexReentrancyDetector
 *           (DetectorType.KOTLIN_MUTEX_REENTRANCY)
 * ========================================================================
 *
 * A kotlinx Mutex is not reentrant. A coroutine that holds one and locks it
 * again waits for an unlock only it can make. The usual shape hides the
 * second lock behind a scope: a withLock inside the withContext block of the
 * same withLock. The block runs as a child coroutine its caller is
 * suspended for, so each waits for the other and neither resumes.
 *
 * THE BUG:
 *   - PriceCache.refresh holds the lock across a withContext load and then
 *     calls update, which locks it again
 *
 * THE FIX:
 *   - load with nothing locked, and hold the lock only to store the result
 *     (PriceCache.refreshFixed)
 *
 * With the agent attached (collections=true, the calling package and
 * kotlinx.coroutines in includes=) every woven lock, unlock and tryLock
 * feeds the detector, and the holder is the coroutine's Job read from the
 * continuation. These tests record by hand so that they run without it.
 */
class PriceCacheTest {

    private lateinit var detector: KotlinMutexReentrancyDetector

    @BeforeEach
    fun setUp() {
        detector = KotlinMutexReentrancyDetector()
    }

    // -----------------------------------------------------------------------
    // Part 1: the shipped version hangs. Nothing throws: the refresh just
    // never returns, so a timeout is the only thing that ends it.
    // -----------------------------------------------------------------------

    @Test
    fun refreshNeverFinishes() = runBlocking {
        val cache = PriceCache { 400 }
        assertNull(withTimeoutOrNull(500) { cache.refresh("ACME") }, "the refresh finished")
        assertNull(cache.price("ACME"))
    }

    // -----------------------------------------------------------------------
    // Part 2: the same calls, recorded as the agent would see them. The
    // outer withLock took the mutex without waiting; the inner lock waits,
    // and the coroutine holding the mutex is suspended for the caller's scope.
    // -----------------------------------------------------------------------

    @Test
    fun relockingFromTheWithContextBlock_isDetected() {
        val mutex = PriceCache { 400 }.lock
        val refresh = Any() // the refresh coroutine's Job
        val block = Any()   // the withContext block's Job

        detector.recordAcquired(mutex, refresh)
        val held = detector.holderAmong(mutex, listOf(block, refresh))
        detector.recordReentry(mutex, listOf("DispatchedCoroutine", "BlockingCoroutine"), held, Thread.currentThread())

        val report = detector.analyze()
        assertTrue(report.hasIssues()) { "the mutex was locked again by its holder:\n$report" }
        assertEquals(IssueSeverity.HIGH, report.structuredViolations[0].severity())
    }

    // -----------------------------------------------------------------------
    // Part 3: the fix. The load runs with nothing locked, so the one lock is
    // taken with nothing holding it, and the refresh finishes.
    // -----------------------------------------------------------------------

    @Test
    fun lockingOnlyToStore_isClean() = runBlocking {
        val cache = PriceCache { 400 }
        val refresh = Any()

        detector.recordAcquired(cache.lock, refresh)
        detector.recordHolderUnknown(cache.lock)
        assertFalse(detector.analyze().hasIssues()) { detector.analyze().toString() }

        cache.refreshFixed("ACME")
        assertEquals(400, cache.price("ACME"))
    }
}
