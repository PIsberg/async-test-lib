package com.example.kotlinfixture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
}
