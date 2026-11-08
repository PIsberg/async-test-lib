package com.example.unwoven

import kotlinx.coroutines.sync.Mutex

/**
 * `Mutex` calls the agent never weaves: this package is outside its `includes=`, as a library the
 * run does not instrument would be (#1221). Neither function is inline, so the `lock` and `unlock`
 * stay here rather than landing in a woven caller.
 */
object UnwovenMutex {

    suspend fun lock(mutex: Mutex) = mutex.lock()

    fun unlock(mutex: Mutex) = mutex.unlock()
}
