package se.deversity.asynctest.example

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A price cache refreshed from a slow source.
 *
 * [refresh] is the shipped version. It holds the lock across the load and then calls [update],
 * which takes the same lock again. A kotlinx `Mutex` is not reentrant: the inner `withLock` waits
 * for the outer one to end, and the outer one waits for the block that holds the inner one, so the
 * coroutine never resumes. Nothing throws; the refresh just never finishes.
 *
 * [refreshFixed] loads with nothing locked and holds the lock only to store the result, through
 * [updateLocked], which assumes its caller holds it.
 */
class PriceCache(private val source: suspend (String) -> Int) {

    private val mutex = Mutex()
    private var prices: Map<String, Int> = emptyMap()

    /** The lock this cache guards its prices with, for a test that watches it. */
    val lock: Mutex get() = mutex

    suspend fun refresh(symbol: String) = mutex.withLock {
        val fresh = withContext(Dispatchers.IO) { source(symbol) }
        update(symbol, fresh) // locks mutex again: waits for this withLock, which waits for it
    }

    suspend fun update(symbol: String, price: Int) = mutex.withLock {
        prices = prices + (symbol to price)
    }

    suspend fun refreshFixed(symbol: String) {
        val fresh = withContext(Dispatchers.IO) { source(symbol) } // nothing locked while loading
        mutex.withLock { updateLocked(symbol, fresh) }
    }

    private fun updateLocked(symbol: String, price: Int) {
        prices = prices + (symbol to price)
    }

    fun price(symbol: String): Int? = prices[symbol]
}
