package com.example.kotlinfixture

import com.example.unwoven.UnwovenMutex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.Executors

/**
 * A coroutine `Mutex` locked again by the coroutine that holds it, and the shapes that only look
 * like it (#1007). This package is in the agent's `includes=`, so each `lock`, `unlock` and
 * `tryLock` here is woven, the ones `withLock` inlines included.
 *
 * kotlinx `Mutex` is not reentrant, so each misuse would hang for good; a `withTimeoutOrNull`
 * around the inner lock ends the wait, and each returns whether the inner lock was ever taken.
 * Each call makes its own mutex, so no two workers of a run contend on one and every outer lock is
 * taken without waiting.
 *
 * Nothing here runs on `Dispatchers.Default`: a worker an earlier test class started belongs to that
 * run, and a lock on it would be unseen by this one. The coroutines stay on the run's own thread, or
 * move to an executor created and closed inside the call, whose thread the woven `execute` hands
 * the run to.
 */
class MutexBridges {

    private val shared = Mutex()
    private var counter = 0

    /** The misuse through `withContext` on the same thread: the outer coroutine waits for the block, which waits for the outer unlock. */
    fun reenterThroughWithContext(): Boolean = runBlocking {
        val mutex = Mutex()
        mutex.withLock {
            withTimeoutOrNull(TIMEOUT_MS) {
                withContext(CoroutineName("inner")) { mutex.withLock { true } }
            } ?: false
        }
    }

    /**
     * The same misuse with the block moved to another thread, on a dispatcher of its own. A bare
     * `ScheduledThreadPoolExecutor`, as in [RunBlockingBridges]: the woven `execute` hands the run
     * to its thread.
     */
    fun reenterOnAnotherDispatcher(): Boolean =
        Executors.newScheduledThreadPool(1) { Thread(it, "mutex-dispatcher") }
            .asCoroutineDispatcher()
            .use { other ->
                runBlocking {
                    val mutex = Mutex()
                    mutex.withLock {
                        withTimeoutOrNull(TIMEOUT_MS) {
                            withContext(other) { mutex.withLock { true } }
                        } ?: false
                    }
                }
            }

    /** The misuse with a bare `lock()` and `unlock()`, inside `coroutineScope`. */
    fun reenterWithBareLock(): Boolean = runBlocking {
        val mutex = Mutex()
        mutex.lock()
        try {
            withTimeoutOrNull(TIMEOUT_MS) {
                coroutineScope {
                    mutex.lock()
                    mutex.unlock()
                    true
                }
            } ?: false
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The misuse after a wait (#1220): the outer lock first waits for another coroutine, and once it
     * holds the mutex its `supervisorScope` block locks it again.
     */
    fun reenterAfterWaiting(): Boolean = runBlocking {
        val mutex = Mutex()
        val taken = CompletableDeferred<Unit>()
        launch {
            mutex.withLock {
                taken.complete(Unit)
                delay(HOLD_MS)
            }
        }
        taken.await()
        mutex.withLock {
            withTimeoutOrNull(TIMEOUT_MS) {
                supervisorScope { mutex.withLock { true } }
            } ?: false
        }
    }

    /**
     * The misuse after a `tryLock` (#1220): the coroutine takes the mutex without waiting through
     * `tryLock`, in the `runBlocking` lambda itself, and its `coroutineScope` block locks it again.
     */
    fun reenterAfterTryLock(): Boolean = runBlocking {
        val mutex = Mutex()
        check(mutex.tryLock()) { "a fresh mutex refused tryLock" }
        try {
            withTimeoutOrNull(TIMEOUT_MS) {
                coroutineScope {
                    mutex.lock()
                    mutex.unlock()
                    true
                }
            } ?: false
        } finally {
            mutex.unlock()
        }
    }

    /** The same after a `tryLock` in a named suspend function, whose `supervisorScope` relocks (#1220). */
    fun reenterAfterTryLockInASuspendFunction(): Boolean = runBlocking { tryLockThenReenter(Mutex()) }

    private suspend fun tryLockThenReenter(mutex: Mutex): Boolean {
        check(mutex.tryLock()) { "a fresh mutex refused tryLock" }
        try {
            return withTimeoutOrNull(TIMEOUT_MS) {
                supervisorScope { mutex.withLock { true } }
            } ?: false
        } finally {
            mutex.unlock()
        }
    }

    /**
     * The misuse after a `select` (#1220): the coroutine takes the mutex through `onLock`, and its
     * `coroutineScope` block locks it again. `select` is inline, so the clause is registered in the
     * `runBlocking` lambda itself.
     */
    @Suppress("DEPRECATION")
    fun reenterAfterSelect(): Boolean = runBlocking {
        val mutex = Mutex()
        select<Unit> { mutex.onLock { } }
        try {
            withTimeoutOrNull(TIMEOUT_MS) {
                coroutineScope {
                    mutex.lock()
                    mutex.unlock()
                    true
                }
            } ?: false
        } finally {
            mutex.unlock()
        }
    }

    /** The same with an owner passed to the clause, in a named suspend function whose `supervisorScope` relocks. */
    fun reenterAfterSelectWithAnOwner(): Boolean = runBlocking { selectThenReenter(Mutex()) }

    @Suppress("DEPRECATION")
    private suspend fun selectThenReenter(mutex: Mutex): Boolean {
        val owner = Any()
        select<Unit> { mutex.onLock(owner) { } }
        try {
            return withTimeoutOrNull(TIMEOUT_MS) {
                supervisorScope { mutex.withLock { true } }
            } ?: false
        } finally {
            mutex.unlock(owner)
        }
    }

    /**
     * Silent: a `select` whose `onLock` clause loses, because a child holds the mutex and another
     * clause is ready at once, then a lock by the same coroutine, which waits for the child alone.
     * Returns whether that lock was taken.
     */
    @Suppress("DEPRECATION")
    fun lockAfterALostSelect(): Boolean = runBlocking {
        val mutex = Mutex()
        val ready = CompletableDeferred(Unit)
        launch { mutex.withLock { delay(HOLD_MS) } }
        yield()
        select<Unit> {
            mutex.onLock { mutex.unlock() }
            ready.onAwait { }
        }
        coroutineScope { mutex.withLock { true } }
    }

    /** Silent: a mutex taken through `select`, unlocked, then locked by the same coroutine. */
    @Suppress("DEPRECATION")
    fun lockAfterSelectIsReleased(): Boolean = runBlocking {
        val mutex = Mutex()
        select<Unit> { mutex.onLock { } }
        mutex.unlock()
        coroutineScope { mutex.withLock { true } }
    }

    /** Silent: a `tryLock`, an unlock, then a lock by the same coroutine, which is free by then. */
    fun lockAfterTryLockIsReleased(): Boolean = runBlocking {
        val mutex = Mutex()
        check(mutex.tryLock()) { "a fresh mutex refused tryLock" }
        mutex.unlock()
        coroutineScope { mutex.withLock { true } }
    }

    /**
     * Silent (#1221): the mutex is unlocked, then taken by another coroutine, both through
     * [UnwovenMutex], and the first coroutine's next woven lock waits for that other holder, which
     * lets go. Returns whether that lock was taken.
     */
    fun lockAgainAfterAnUnseenHandOver(): Boolean = runBlocking {
        val mutex = Mutex()
        mutex.lock()
        UnwovenMutex.unlock(mutex)
        val taken = CompletableDeferred<Unit>()
        launch {
            UnwovenMutex.lock(mutex)
            taken.complete(Unit)
            delay(HOLD_MS)
            UnwovenMutex.unlock(mutex)
        }
        taken.await()
        mutex.withLock { true }
    }

    /** Silent: the same mutex locked twice in a row, released in between. */
    fun lockTwiceInARow(): Int = runBlocking {
        val mutex = Mutex()
        mutex.withLock { counter++ }
        mutex.withLock { counter++ }
    }

    /**
     * Silent: a `launch` child locks the mutex its parent holds. The parent does not wait for the
     * child inside the lock, so it unlocks and the child takes its turn.
     */
    fun childWaitsItsTurn(): Int = runBlocking {
        val mutex = Mutex()
        mutex.withLock {
            launch { mutex.withLock { counter++ } }
            counter++
        }
    }

    /** Silent: the run's workers contend for one mutex, each waiting for another coroutine to unlock it. */
    fun contend(): Int = runBlocking {
        shared.withLock {
            delay(1)
            counter++
        }
    }

    /** Silent: a `tryLock` that finds the mutex taken backs off, rather than waiting for itself. */
    fun tryLockInsideTheLock(): Boolean = runBlocking {
        val mutex = Mutex()
        mutex.withLock {
            withContext(CoroutineName("probe")) { mutex.tryLock() }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 200L

        /** How long another coroutine keeps the mutex, so the lock under test has to wait for it. */
        const val HOLD_MS = 20L
    }
}
