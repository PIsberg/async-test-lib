package se.deversity.asynctest.fixture

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import se.deversity.asynctest.AsyncFindings
import se.deversity.asynctest.AsyncTest
import se.deversity.asynctest.FailOn
import com.example.kotlinfixture.KotlinLoopWaitHandOff
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * The direction that must stay silent: a correct bounded poll written in Kotlin, woven by the
 * agent, is not reported as a missed signal (#714).
 *
 * This is the gate #710 left as a hand check. The back-edge rule that separates a guarded poll
 * from `do { wait() } while (...)` reads the shape the compiler emitted, and a compiler that
 * rotates loops emits a different one: test after the body, conditional back-edge. ECJ rotates and
 * javac does not, which `MissedSignalRotatedLoopWeavingTest` gates by running the real ECJ.
 * kotlinc was the other compiler named, verified by hand at 2.4.10 and by nothing since. If a
 * Kotlin release starts rotating loops and the rule stops covering the shape, every bounded poll
 * in a woven Kotlin codebase starts being reported, and this test goes red before a user finds it.
 *
 * It asserts on the finding rather than on the weaver's marks on purpose. The marks are reachable
 * only from inside `async-test-agent`, which would mean a Kotlin compiler on that module's test
 * classpath, and the finding is what a user actually sees. `KotlinDoWhileWaitIsReportedTest` is
 * the other direction, so neither test can pass by the detector having gone quiet.
 *
 * The agent is attached by `-javaagent` from the surefire and Gradle test configuration, with
 * `includes=` scoped to the `wait` package, so nothing else in this fixture is woven.
 */
class KotlinWaitLoopIsNotReportedTest {

    companion object {
        private lateinit var findings: AsyncFindings

        @JvmStatic
        @BeforeAll
        fun collect() {
            findings = AsyncFindings.collect()
        }

        @JvmStatic
        @AfterAll
        fun thePredicateLoopIsNotReported() {
            try {
                assertFalse(
                    findings.violations().any { it.detector().contains("MissedSignal") },
                    "KotlinLoopWaitHandOff re-tests its predicate around every wait, so a notify " +
                        "it did not hear cannot strand it. A finding here means the loop's " +
                        "back-edge was not marked, and the first thing to check is whether this " +
                        "kotlinc still emits javac's loop shape: test at the top, unconditional " +
                        "goto closing the loop. If it rotates loops now, the rule in " +
                        "CollectionAccessWeaver has to cover what it emits the way it covers " +
                        "ECJ's. Findings were: " + findings.violations()
                )
            } finally {
                findings.close()
            }
        }
    }

    private val handOff = KotlinLoopWaitHandOff()

    @AsyncTest(
        threads = 4,
        invocations = 10,
        detectMissedSignals = true,
        failOn = FailOn.NONE,
        licenseMockMode = true
    )
    fun pollingInAPredicateLoopAfterTheSignalWasLost() {
        handOff.signalThenAwait()
    }
}
