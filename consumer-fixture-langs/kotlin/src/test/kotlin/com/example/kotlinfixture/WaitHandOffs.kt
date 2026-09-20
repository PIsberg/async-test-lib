package com.example.kotlinfixture

/**
 * The two wait shapes the `MISSED_SIGNAL` back-edge rule has to tell apart, written in Kotlin so
 * that what the weaver reads is what kotlinc emits (#714).
 *
 * The rule was written against javac, which puts a `while` loop's test at the top and closes the
 * loop with an unconditional `goto`. A compiler that rotates loops emits `goto test; body;
 * test: if (...) goto body` instead, so a correct poll closes with a *conditional* back-edge and
 * puts its test after the wait, which under a javac-only rule reads as `do { wait() } while (...)`
 * and gets reported. ECJ rotates, and #710 widened the rule to cover it. kotlinc agreed with javac
 * when it was checked by hand at 2.4.10, and nothing re-checked that until this file existed.
 *
 * The package is its own because the agent's `includes=` points at it: the race-condition fixtures
 * in `se.deversity.asynctest.fixture` must keep running unwoven, or this gate would be changing
 * what they measure. It is outside `se.deversity.asynctest` for a harder reason. The weaver
 * refuses to substitute inside the library's own root, because `AgentCollectionHooks.mapPut` ends
 * by calling `Map.put` and weaving it would replace that call with a call to itself. A wait bean
 * under that root is therefore never woven at all, the detector reports nothing, and a gate that
 * only asserted silence would have passed while measuring nothing.
 *
 * `java.lang.Object` rather than `Any`, because `wait` and `notifyAll` are not on `Any` and the
 * detector's subject is the JDK monitor. The suppression is kotlinc's warning about naming a
 * platform class it normally maps away, which is exactly what has to be named here.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class KotlinLoopWaitHandOff {

    private val monitor = java.lang.Object()
    private var ready = false

    /**
     * Signals whoever is waiting, then polls for the predicate until a deadline.
     *
     * A notify that found nobody waiting cannot strand this wait: the loop reads the state the
     * notify announced rather than relying on having heard it. The wait still runs out, exactly
     * as its twin's does. What differs is the backward jump around it, which the agent marks.
     */
    fun signalThenAwait() {
        synchronized(monitor) {
            monitor.notifyAll()
        }
        synchronized(monitor) {
            val deadline = System.nanoTime() + 20_000_000L
            while (!ready) {
                val leftMillis = (deadline - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0L) {
                    break
                }
                monitor.wait(leftMillis)
            }
        }
    }
}

/**
 * The twin: the same signal and the same bounded poll with `do`/`while` in place of `while`.
 *
 * This shape enters `wait` before it has ever read `ready`, so a notify that found nobody waiting
 * strands the first wait of the round until its timeout. That is the missed-signal bug, and it has
 * to be reported however it was compiled. Its bytecode still has a backward jump over the wait and
 * still reads something before it blocks, so neither the back-edge alone nor a conditional jump in
 * front of the wait alone separates it from its twin.
 *
 * The deadline clamps the wait and ends the loop, where the twin's `if` guards the wait instead.
 * This is the one that must produce a finding, so it has to reach `wait` on every run, and a guard
 * does not: a thread descheduled between reading the clock and testing it would break out having
 * waited for nothing, which is a round that must fire with no wait to report.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class KotlinDoWhileWaitHandOff {

    private val monitor = java.lang.Object()
    private var ready = false

    /** Signals whoever is waiting, then waits before polling for the predicate until a deadline. */
    fun signalThenAwait() {
        synchronized(monitor) {
            monitor.notifyAll()
        }
        synchronized(monitor) {
            val deadline = System.nanoTime() + 20_000_000L
            do {
                val leftMillis = (deadline - System.nanoTime()) / 1_000_000L
                monitor.wait(if (leftMillis > 0L) leftMillis else 1L)
            } while (!ready && System.nanoTime() < deadline)
        }
    }
}
