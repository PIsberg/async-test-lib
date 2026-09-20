package se.deversity.asynctest.fixture

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.FailOn
import com.example.kotlinfixture.KotlinDoWhileWaitHandOff

/**
 * The direction that must fire: `do { wait() } while (!ready)` written in Kotlin, woven by the
 * agent, is reported as a missed signal (#714).
 *
 * Without this half, `KotlinWaitLoopIsNotReportedTest` would pass just as well if the detector
 * never fired on Kotlin code at all, or if the agent never wove any of it. A gate that only
 * asserts silence measures nothing, which is the same reason every detector in this library ships
 * a buggy subject and a synchronized twin.
 *
 * Waiting before testing the predicate is the bug itself: a notify that found nobody waiting
 * strands the first wait of the round until its timeout. It must be reported whatever compiled it,
 * and in particular a rule widened to spare a rotated `while` must not also spare this.
 */
class KotlinDoWhileWaitIsReportedTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun theDoWhileWaitIsReported() {
            try {
                assertTrue(
                    findings.violations().any { it.detector().contains("MissedSignal") },
                    "KotlinDoWhileWaitHandOff waits before testing its predicate, so a lost " +
                        "notify leaves the wait stranded. No finding means either that the agent " +
                        "did not weave this package, which makes the silent twin worthless too, " +
                        "or that the back-edge rule now spares a do/while: check what this " +
                        "kotlinc emits for the loop, and whether the entry goto a rotated while " +
                        "has is being read into a shape that has none. Findings were: " +
                        findings.violations()
                )
            } finally {
                findings.close()
            }
        }
    }

    private val handOff = KotlinDoWhileWaitHandOff()

    @AsyncTest(
        threads = 4,
        invocations = 10,
        detectMissedSignals = true,
        failOn = FailOn.NONE,
        licenseMockMode = true
    )
    fun waitingInADoWhileLoopAfterTheSignalWasLost() {
        handOff.signalThenAwait()
    }
}
