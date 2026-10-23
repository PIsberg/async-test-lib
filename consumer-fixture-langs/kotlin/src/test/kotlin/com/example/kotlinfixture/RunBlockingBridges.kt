package com.example.kotlinfixture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * A blocking API built on `runBlocking`, called from the two places it can be (#1007). This
 * package is in the agent's `includes=`, so every `runBlocking` call site here is woven; kotlinx
 * is too, so the woven `Thread.start` inside its scheduler attributes each new dispatcher worker to
 * the run that dispatched to it.
 */
class RunBlockingBridges {

    /** The blocking API: parks its caller until a suspending lookup finishes. */
    fun lookup(): Int = runBlocking {
        delay(1)
        42
    }

    /**
     * The misuse: a coroutine on `Dispatchers.Default` calls the blocking API, so a per-core worker
     * parks in `runBlocking` until the inner coroutine is done. The outer `runBlocking` runs on the
     * caller's own thread, which is its intended use.
     */
    fun lookupInsideADefaultCoroutine(): Int = runBlocking {
        withContext(Dispatchers.Default) { lookup() }
    }

    /**
     * The misuse on the caller's own thread: the coroutine an outer `runBlocking` runs calls the
     * blocking API, so the inner `runBlocking` parks the thread inside the outer one's event loop.
     * No dispatcher worker is involved; what gives the coroutine away is the outer `runBlocking`'s
     * frames below the inner call.
     */
    fun lookupInsideARunBlocking(): Int = runBlocking { lookup() }

    /**
     * The misuse on a dispatcher of one thread: an executor's plain thread, with no class to
     * recognise, where a `runBlocking` parks the only thread the dispatcher has. A bare
     * `ScheduledThreadPoolExecutor` rather than `newSingleThreadContext`: the woven `execute` hands
     * the task over, and with it the run, on the JDK's own pool classes only, and kotlinx wraps its
     * pool in `Executors.unconfigurableExecutorService`, through which the hook does not look
     * (#1107). Closed after each call so the thread is this run's.
     */
    fun lookupOnASingleThreadDispatcher(): Int =
        Executors.newScheduledThreadPool(1) { Thread(it, "single-thread-dispatcher") }
            .asCoroutineDispatcher()
            .use { single -> runBlocking { withContext(single) { lookup() } } }
}
