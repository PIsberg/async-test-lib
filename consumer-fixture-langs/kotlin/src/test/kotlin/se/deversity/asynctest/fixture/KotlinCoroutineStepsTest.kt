package se.deversity.asynctest.fixture

import com.example.kotlincoroutines.CoroutineSteps
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.DetectorType
import se.deversity.asynctest.FailOn

/**
 * A lost update between two coroutines on one thread is reported, and its silent twins stay
 * silent (#1007): the same update under a `Mutex` held across the suspension, and one coroutine
 * updating its own state across threads. Runs in its own surefire execution, with `fields=true`.
 */
class KotlinCoroutineStepsTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun judge() {
            try {
                val atomicity = findings.violationsFrom("AtomicityValidator").joinToString("\n") { it.message() + " " + it.attributes() }
                assertTrue(atomicity.contains("LostBox.n: lost update across a suspension"),
                    "two coroutines on one thread lost an update across a suspension: $atomicity")
                assertFalse(atomicity.contains("GuardedBox.n"),
                    "the Mutex held across the suspension kept the other coroutine out: $atomicity")
                assertFalse(atomicity.contains("OwnBox.n"),
                    "one coroutine updating its own box is one owner: $atomicity")
                assertFalse(atomicity.contains("DelayedTaskQueue"),
                    "kotlinx reads and stores its queue's timeNow only under the queue's monitor (#1574): $atomicity")
            } finally {
                findings.close()
            }
        }
    }

    private val steps = CoroutineSteps()

    @AsyncTest(threads = 4, invocations = 8, failOn = FailOn.NONE, licenseMockMode = true,
        timeoutMs = 60_000, includes = [DetectorType.ATOMICITY_VIOLATIONS])
    fun twoCoroutinesLoseAnUpdate() {
        val total = steps.twoCoroutinesLoseAnUpdate()
        assertTrue(total < 2 * CoroutineSteps.STEPS, "the update was not lost, so nothing was measured: $total")
    }

    @AsyncTest(threads = 4, invocations = 8, failOn = FailOn.NONE, licenseMockMode = true,
        timeoutMs = 60_000, includes = [DetectorType.ATOMICITY_VIOLATIONS])
    fun twoCoroutinesHoldAMutex() {
        assertEquals(2 * CoroutineSteps.STEPS, steps.twoCoroutinesHoldAMutex())
    }

    @AsyncTest(threads = 4, invocations = 8, failOn = FailOn.NONE, licenseMockMode = true,
        timeoutMs = 60_000, includes = [DetectorType.ATOMICITY_VIOLATIONS])
    fun twoCoroutinesOnlyDelay() {
        assertEquals(2 * CoroutineSteps.STEPS, steps.twoCoroutinesDelay())
    }

    @AsyncTest(threads = 4, invocations = 8, failOn = FailOn.NONE, licenseMockMode = true,
        timeoutMs = 60_000, includes = [DetectorType.ATOMICITY_VIOLATIONS])
    fun ownStepsAcrossThreads() {
        assertEquals(CoroutineSteps.STEPS, steps.ownStepsAcrossThreads())
    }
}
