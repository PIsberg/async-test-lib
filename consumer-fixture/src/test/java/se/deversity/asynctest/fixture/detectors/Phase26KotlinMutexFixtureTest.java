package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.List;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;

/**
 * Phase 26, Kotlin coroutines — {@code KOTLIN_MUTEX_REENTRANCY}.
 *
 * <p>The fixture proves the detector is reachable from the published artifact, records a mutex
 * locked again from a scope its holder waits for through its public recording API, and asserts in
 * {@code @AfterAll} that the finding came back out through {@link AsyncFindings}. The woven calls,
 * against real kotlinx, are proven in {@code consumer-fixture-langs/kotlin}.
 *
 * <p>See {@code docs/DETECTOR_CATALOG.md} entry 149 for the buggy-vs-fixed pair.
 */
class Phase26KotlinMutexFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings, "KotlinMutexReentrancyDetector");
        } finally {
            findings.close();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 30_000, licenseMockMode = true,
               includes = {DetectorType.KOTLIN_MUTEX_REENTRANCY})
    void kotlinMutexReentrancy() {
        reachable("kotlinMutexReentrancyDetector()", AsyncTestContext::kotlinMutexReentrancyDetector);

        // The hazard: the coroutine holding a mutex waits on a withContext block that locks it again.
        var detector = AsyncTestContext.kotlinMutexReentrancyDetector();
        Object mutex = new Object();
        Object outer = new Object();
        Object inner = new Object();
        detector.recordAcquired(mutex, outer);
        int held = detector.holderAmong(mutex, List.of(inner, outer));
        detector.recordReentry(mutex, List.of("DispatchedCoroutine", "BlockingCoroutine"), held,
                Thread.currentThread());
    }
}
