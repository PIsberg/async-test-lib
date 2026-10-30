package com.example.kotlinfixture

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
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
     * `ScheduledThreadPoolExecutor`; [lookupOnNewSingleThreadContext] is the same through kotlinx's
     * own factory. Closed after each call so the thread is this run's.
     */
    fun lookupOnASingleThreadDispatcher(): Int =
        Executors.newScheduledThreadPool(1) { Thread(it, "single-thread-dispatcher") }
            .asCoroutineDispatcher()
            .use { single -> runBlocking { withContext(single) { lookup() } } }

    /**
     * The same misuse on kotlinx's own single-threaded dispatcher (#1107). `newSingleThreadContext`
     * wraps a `ScheduledThreadPoolExecutor` in `Executors.unconfigurableExecutorService`; the woven
     * `execute` looks through that wrapper to the pool behind it, so the thread is attributed while
     * the task runs. Closed after each call so the thread is this run's.
     */
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun lookupOnNewSingleThreadContext(): Int =
        newSingleThreadContext("single-thread-context")
            .use { single -> runBlocking { withContext(single) { lookup() } } }
}
