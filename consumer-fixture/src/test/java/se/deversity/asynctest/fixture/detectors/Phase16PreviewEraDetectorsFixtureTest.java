package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.registerOnce;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 16, JDK 25/26 preview-era group — {@code STABLE_VALUE_MISUSE},
 * {@code STRUCTURED_TASK_SCOPE_MISUSE}, {@code GATHERER_CONCURRENCY_MISUSE}.
 *
 * <p>The workloads model these hazards with generally-available APIs. {@code StableValue}
 * (JEP 502), {@code StructuredTaskScope} and {@code Gatherer} are preview or JDK-25-only,
 * and the consumer fixture must compile on JDK 21 as well — CI runs both. The detectors'
 * own record/analyze APIs are exercised directly by
 * {@code se.deversity.asynctest.fixture.ConsumerJdk25And26DetectorsTest}; what these
 * fixtures add is proof that the three are reachable as wired {@code DetectorType}s.
 *
 * <p>Corresponding examples: {@code examples/114-stable-value-misuse},
 * {@code examples/115-structured-task-scope-misuse},
 * {@code examples/116-gatherer-parallel-misuse}.
 */
class Phase16PreviewEraDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "StableValueMisuseDetector",
                    "StructuredTaskScopeMisuseDetector",
                    "GathererConcurrencyMisuseDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STABLE_VALUE_MISUSE})
    void stableValueMisuse() {
        reachable("stableValueMisuseDetector()", AsyncTestContext::stableValueMisuseDetector);

        // Read-before-set is the misuse; an AtomicReference stands in for StableValue so
        // this compiles on JDK 21.
        // A stable value is meant to be computed once. Here both workers run the supplier and
        // both try to set it, which is the misuse the detector reports.
        var stable = AsyncTestContext.stableValueMisuseDetector();
        stable.recordRead("SET_ONCE", Thread.currentThread());
        stable.recordSupplierStart("SET_ONCE", Thread.currentThread());
        if (SET_ONCE.get() == null) {
            SET_ONCE.compareAndSet(null, "computed");
        }
        stable.recordSupplierEnd("SET_ONCE", Thread.currentThread());
        stable.recordSet("SET_ONCE", Thread.currentThread());
        spin(SET_ONCE.get().length());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STRUCTURED_TASK_SCOPE_MISUSE})
    void structuredTaskScopeMisuse() {
        reachable("structuredTaskScopeMisuseDetector()",
            AsyncTestContext::structuredTaskScopeMisuseDetector);

        // The misuse is forking without joining, or joining outside the scope's owner
        // thread. Nothing is forked here — the claim is reachability, not detection.
        // StructuredTaskScope is not on the fixture's compile path on every supported JDK, so
        // the scope's lifecycle is recorded rather than run. The misuse is reading a subtask's
        // result before join(), which is what the ordering below models.
        var scope = AsyncTestContext.structuredTaskScopeMisuseDetector();
        String scopeId = "fixture-scope-" + Thread.currentThread().threadId();
        scope.recordScopeOpened(scopeId, Thread.currentThread());
        scope.recordFork(scopeId, "subtask-1", Thread.currentThread());
        scope.recordResultRead(scopeId, "subtask-1", Thread.currentThread());   // before join
        scope.recordJoin(scopeId, Thread.currentThread());
        spin(32);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.GATHERER_CONCURRENCY_MISUSE})
    void gathererConcurrencyMisuse() {
        reachable("gathererConcurrencyMisuseDetector()",
            AsyncTestContext::gathererConcurrencyMisuseDetector);

        // A stateful stage in a parallel pipeline is the hazard a Gatherer makes easy to
        // write; the sequential collect below is its safe counterpart.
        // A parallel gatherer with no combiner integrated from more than one thread is the
        // misuse: without a combiner the integrator's state cannot be merged safely.
        var gatherer = AsyncTestContext.gathererConcurrencyMisuseDetector();
        registerOnce("gatherer", () -> gatherer.registerGatherer("fixture-gatherer", false, true));
        gatherer.recordIntegrate("fixture-gatherer", Thread.currentThread());
        List<Integer> collected = List.of(1, 2, 3).stream()
            .map(value -> value * 2)
            .collect(Collectors.toList());
        spin(collected.size());
    }

    private static final AtomicReference<String> SET_ONCE = new AtomicReference<>();
}
