package com.example.kotlincoroutines

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.Executors

/**
 * Coroutines updating plain fields across suspensions (#1007). This package is in the agent's
 * `includes=` with `fields=true`, so every field access here is recorded, and the agent knows
 * which coroutine each is made in.
 *
 * Each shape runs on `runBlocking`'s own event loop, one thread, where no rule keyed by thread
 * can see two owners, except [ownStepsAcrossThreads], which moves one coroutine between threads.
 */
class CoroutineSteps {

    /** Read, suspend, write back: two coroutines interleave and one update is lost. */
    class LostBox {
        var n = 0
    }

    /** The same update with a Mutex held across the suspension. */
    class GuardedBox {
        var n = 0
    }

    /** One coroutine's own state. */
    class OwnBox {
        var n = 0
    }

    /**
     * Two coroutines on one thread, each reading the box, suspending, then writing what it read
     * plus one. Returns the final count, which is below the 2 x [STEPS] updates made.
     */
    fun twoCoroutinesLoseAnUpdate(): Int {
        val box = LostBox()
        runBlocking {
            repeat(2) {
                launch {
                    repeat(STEPS) {
                        val seen = box.n
                        yield()
                        box.n = seen + 1
                    }
                }
            }
        }
        return box.n
    }

    /** The same two coroutines with a Mutex held from the read to the write: nothing is lost. */
    fun twoCoroutinesHoldAMutex(): Int {
        val box = GuardedBox()
        val mutex = Mutex()
        runBlocking {
            repeat(2) {
                launch {
                    repeat(STEPS) {
                        mutex.withLock {
                            val seen = box.n
                            yield()
                            box.n = seen + 1
                        }
                    }
                }
            }
        }
        return box.n
    }

    /**
     * Two coroutines on one thread that only wait, so the shared state is kotlinx's own: its event
     * loop's delayed-task queue, whose `timeNow` every `delay` reads and stores inside
     * `synchronized` on the queue (#1574). Returns the delays completed, 2 x [STEPS].
     */
    fun twoCoroutinesDelay(): Int {
        var done = 0
        runBlocking {
            repeat(2) {
                launch {
                    repeat(STEPS) {
                        delay(1)
                        done++
                    }
                }
            }
        }
        return done
    }

    /** One coroutine reading, suspending and writing its own box, moved between two threads. */
    fun ownStepsAcrossThreads(): Int =
        Executors.newFixedThreadPool(2) { Thread(it, "step-dispatcher") }
            .asCoroutineDispatcher()
            .use { pool ->
                runBlocking {
                    withContext(pool) {
                        val box = OwnBox()
                        repeat(STEPS) {
                            val seen = box.n
                            if (it % 2 == 0) yield() else delay(1)
                            box.n = seen + 1
                        }
                        box.n
                    }
                }
            }

    companion object {
        const val STEPS = 20
    }
}
