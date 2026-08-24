package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;

/**
 * Phase 24, the JDK 26 concurrency surface — {@code SCOPE_JOINER_MISUSE},
 * {@code SCOPE_CONFIGURATION_MISUSE}, {@code SCOPE_RESULT_ESCAPE} and
 * {@code LAZY_COLLECTION_MISUSE}.
 *
 * <p>Each fixture proves its detector is reachable from the published artifact, runs the hazard
 * through the detector's public recording API, and asserts in {@code @AfterAll} that the finding
 * came back out through {@link AsyncFindings}.
 *
 * <p>Nothing here references {@code StructuredTaskScope}, {@code List.ofLazy} or any other preview
 * type. The library targets Java 21 and the fixture builds on every JDK in the matrix, so the
 * hazards are modelled through the same recording calls an instrumented JEP 525 / JEP 526 program
 * would make. What is being pinned is the detector's model, not the JDK's implementation of it.
 *
 * <p>See {@code docs/DETECTOR_CATALOG.md} entries 143–146 for the buggy-vs-fixed pair behind each.
 */
class Phase24Jdk26StructuredConcurrencyFixtureTest {

    private static final int SUBTASKS = 4;

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "ScopeJoinerMisuseDetector",
                    "ScopeConfigurationMisuseDetector",
                    "ScopeResultEscapeDetector",
                    "LazyCollectionMisuseDetector");
        } finally {
            findings.close();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SCOPE_JOINER_MISUSE})
    void scopeJoinerMisuse() {
        reachable("scopeJoinerMisuseDetector()", AsyncTestContext::scopeJoinerMisuseDetector);

        // The hazard: onComplete runs on the subtask threads, so four of them write the joiner's
        // state at once. Held at a latch so the overlap is deterministic rather than lucky.
        var detector = AsyncTestContext.scopeJoinerMisuseDetector();
        Object joiner = new Object();
        String scopeId = "fixture-scope-" + Thread.currentThread().getName();
        detector.recordJoinerBound(joiner, "fixtureJoiner", scopeId, Thread.currentThread());

        CountDownLatch allIn = new CountDownLatch(SUBTASKS);
        CountDownLatch allWrote = new CountDownLatch(SUBTASKS);
        runSubtasks(() -> {
            detector.recordOnCompleteEnter(joiner, Thread.currentThread());
            allIn.countDown();
            await(allIn);
            detector.recordAccumulate(joiner, Thread.currentThread());
            allWrote.countDown();
            await(allWrote);
            detector.recordOnCompleteExit(joiner, Thread.currentThread(), false);
        });
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SCOPE_CONFIGURATION_MISUSE})
    void scopeConfigurationMisuse() {
        reachable("scopeConfigurationMisuseDetector()",
                AsyncTestContext::scopeConfigurationMisuseDetector);

        // The hazard: the configuration lambda asked for a 3s deadline and the scope ended up
        // with none, which is what a lambda returning something other than its own chain does.
        var detector = AsyncTestContext.scopeConfigurationMisuseDetector();
        String scopeId = "fixture-scope-" + Thread.currentThread().getName();
        detector.recordScopeOpened(scopeId, "fixtureScope", 3_000L, null, Thread.currentThread());
        detector.recordEffectiveConfiguration(scopeId, "fixtureScope",
                se.deversity.asynctest.diagnostics.ScopeConfigurationMisuseDetector.NO_TIMEOUT);
        detector.recordJoinOutcome(scopeId, false);
        detector.recordScopeClosed(scopeId);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SCOPE_RESULT_ESCAPE})
    void scopeResultEscape() {
        reachable("scopeResultEscapeDetector()", AsyncTestContext::scopeResultEscapeDetector);

        // The hazard: the result list is read after the scope closed, which a JDK 25 Stream
        // made hard and a JDK 26 List makes easy.
        var detector = AsyncTestContext.scopeResultEscapeDetector();
        String scopeId = "fixture-scope-" + Thread.currentThread().getName();
        List<String> results = List.of("order-a", "order-b");

        detector.recordScopeOpened(scopeId, Thread.currentThread());
        detector.recordJoinCompleted(scopeId);
        detector.recordResultHandle(results, "fixtureResults", scopeId);
        detector.recordScopeClosed(scopeId);
        detector.recordHandleRead(results, Thread.currentThread());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LAZY_COLLECTION_MISUSE})
    void lazyCollectionMisuse() {
        reachable("lazyCollectionMisuseDetector()", AsyncTestContext::lazyCollectionMisuseDetector);

        // The hazard: element 0's mapping function computes element 1, and element 1's computes
        // element 0. Both edges are observed, so the cycle is a fact rather than a guess.
        var detector = AsyncTestContext.lazyCollectionMisuseDetector();
        String grid = "fixtureGrid-" + Thread.currentThread().getName();
        Thread self = Thread.currentThread();

        detector.recordComputeStart(grid, 0, self);
        detector.recordComputeStart(grid, 1, self);
        detector.recordComputeEnd(grid, 1, self, "cell-1");
        detector.recordComputeEnd(grid, 0, self, "cell-0");

        detector.recordComputeStart(grid, 1, self);
        detector.recordComputeStart(grid, 0, self);
        detector.recordComputeEnd(grid, 0, self, "cell-0");
        detector.recordComputeEnd(grid, 1, self, "cell-1");
    }

    /** Runs {@link #SUBTASKS} virtual threads through {@code body} and waits for all of them. */
    private static void runSubtasks(Runnable body) {
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < SUBTASKS; i++) {
            threads.add(Thread.ofVirtual().start(body));
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("subtasks never assembled");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
