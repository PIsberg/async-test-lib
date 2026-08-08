package se.deversity.asynctest.example

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import se.deversity.asynctest.AsyncTest
import java.util.concurrent.atomic.AtomicInteger

/**
 * `@AsyncTest` driven from Kotlin.
 *
 * There is nothing to configure and nothing Kotlin-specific to do. `@AsyncTest` is a JUnit 5
 * `@TestTemplate`, so it works from any language that produces JUnit 5 test classes; the only
 * thing worth knowing is that the annotation's attributes are named arguments in Kotlin, which
 * reads better than the Java form.
 *
 * The two tests below are the same body run two ways, and that contrast is the point of the
 * example: the sequential test passes and tells you nothing.
 */
class InventoryCounterTest {

    private val counter = InventoryCounter()
    private val safeCounter = AtomicInteger()

    @AfterEach
    fun report() {
        println("plain=${counter.count} atomic=${safeCounter.get()}")
    }

    /**
     * Passes. Runs the increment 1000 times on one thread and finds nothing, because there is
     * nothing to find on one thread. This is the test most codebases actually have.
     */
    @Test
    fun sequentialIncrementsAreFine() {
        repeat(1000) { counter.record() }
        assertEquals(1000, counter.count)
    }

    /**
     * Fails, on purpose. Eight threads x 200 rounds through the same `count++`, released together
     * on a CyclicBarrier so they collide rather than queue. The final count comes out below
     * 8 * 200 because increments are lost, and the report names the field.
     *
     * Disabled so the examples build stays green. Remove `@Disabled` to watch it fail, then paste
     * the `replaySeed` from the failure into `@AsyncTest(replaySeed = ...)` to get the same
     * interleaving back.
     */
    @Disabled("demonstrates a real lost update: enabling it makes this module fail by design")
    @AsyncTest(threads = 8, invocations = 200, detectAll = true)
    fun concurrentIncrementsLoseUpdates() {
        counter.record()
    }

    /**
     * The correct twin, and it stays green under the same contention. `AtomicInteger` makes the
     * read-modify-write indivisible, so nothing is lost and no detector fires.
     */
    @AsyncTest(threads = 8, invocations = 200, detectAll = true)
    fun atomicIncrementsSurviveContention() {
        safeCounter.incrementAndGet()
    }
}
