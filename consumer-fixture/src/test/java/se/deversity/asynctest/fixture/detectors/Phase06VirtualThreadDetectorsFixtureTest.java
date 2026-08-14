package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.pause;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 6, virtual-thread group — {@code STRUCTURED_CONCURRENCY} through
 * {@code VIRTUAL_THREAD_CARRIER_EXHAUSTION}.
 *
 * <p>The workloads use {@code Thread.ofVirtual()} and executors rather than
 * {@code StructuredTaskScope} / {@code ScopedValue} directly: those are preview APIs on
 * JDK 21, and the consumer fixture must compile on every JDK the library supports (21 and
 * 25 in CI). The detector reachability assertion is unaffected — it is the API surface
 * being pinned, not the JDK feature.
 *
 * <p>Corresponding examples: {@code examples/79-structured-concurrency},
 * {@code examples/04-virtual-thread-context-leak}, {@code examples/69-scoped-value-misuse},
 * {@code examples/91-virtual-thread-cpu-bound},
 * {@code examples/90-virtual-thread-carrier-exhaustion}.
 */
class Phase06VirtualThreadDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "StructuredConcurrencyMisuseDetector",
                    "VirtualThreadContextLeakDetector",
                    "ScopedValueMisuseDetector",
                    "VirtualThreadCpuBoundTaskDetector",
                    "VirtualThreadCarrierExhaustionDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true, includes = {DetectorType.STRUCTURED_CONCURRENCY})
    void structuredConcurrency() {
        reachable("structuredConcurrencyMisuseDetector()",
            AsyncTestContext::structuredConcurrencyMisuseDetector);

        // Fork two children and join both before returning — the discipline a structured
        // scope enforces, here written by hand so it compiles without preview APIs.
        // A scope whose subtasks are forked and whose results are read without a join is the
        // misuse; join() is what guarantees every child finished before the results are used.
        // recordScopeOpened returns the id the detector knows the scope by. Inventing an id
        // here instead resolved to no scope at all, so every later call was dropped and the
        // fixture recorded nothing however carefully it was written.
        var scopeDetector = AsyncTestContext.structuredConcurrencyMisuseDetector();
        String scopeId = scopeDetector.recordScopeOpened("fixture-scope");
        scopeDetector.recordSubtaskForked(scopeId);
        scopeDetector.recordSubtaskForked(scopeId);
        scopeDetector.recordResultAccessed(scopeId);
        try (ExecutorService scope = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> children = new ArrayList<>();
            children.add(scope.submit(() -> { spin(32); }));
            children.add(scope.submit(() -> { spin(32); }));
            for (java.util.concurrent.Future<?> child : children) {
                child.get(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Child failure is not the subject of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true, includes = {DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS})
    void virtualThreadContextLeaks() {
        reachable("virtualThreadContextLeakDetector()",
            AsyncTestContext::virtualThreadContextLeakDetector);

        // ThreadLocal state set on a virtual thread that is about to end — the leak is
        // assuming the carrier will clean it up.
        // A ThreadLocal set on a virtual thread and never removed is retained for the life of
        // the thread; with a virtual thread per task that is a per-request leak. The remove()
        // below keeps the fixture clean - the missing recordThreadLocalRemoved is the finding.
        var contextDetector = AsyncTestContext.virtualThreadContextLeakDetector();
        contextDetector.recordThreadLocalSet("request-context", Thread.currentThread());
        CONTEXT.set("request-42");
        try {
            spin(32);
        } finally {
            CONTEXT.remove();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true, includes = {DetectorType.SCOPED_VALUE})
    void scopedValue() {
        reachable("scopedValueMisuseDetector()", AsyncTestContext::scopedValueMisuseDetector);

        // ScopedValue itself is preview on JDK 21; the misuse it replaces — mutable
        // per-request state carried on a ThreadLocal across a task boundary — is not.
        // A get() with no enclosing binding is the misuse. Recorded around the child so the
        // detector sees the read and the binding lifecycle it belongs to.
        var scopedDetector = AsyncTestContext.scopedValueMisuseDetector();
        scopedDetector.recordGetCalled("tenant", Thread.currentThread());
        scopedDetector.recordBindingEntered("tenant", Thread.currentThread());
        CONTEXT.set("scoped-substitute");
        try {
            Thread child = Thread.ofVirtual().unstarted(() -> spin(32));
            child.start();
            child.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            CONTEXT.remove();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true, includes = {DetectorType.VIRTUAL_THREAD_CPU_BOUND})
    void virtualThreadCpuBound() {
        reachable("virtualThreadCpuBoundTaskDetector()",
            AsyncTestContext::virtualThreadCpuBoundTaskDetector);

        // CPU-bound work on a virtual thread gains nothing and occupies a carrier.
        // A virtual thread that computes without ever yielding pins its carrier for the whole
        // task, which is what virtual threads are least suited to.
        // The detector measures the longest stretch between yield points and reports it past
        // DEFAULT_CPU_THRESHOLD_MS (50ms). spin() alone finishes far inside that, so the
        // fixture has to occupy the carrier for long enough to be the thing being described.
        // recordTaskStart returns the id the detector knows the task by; an invented id
        // resolves to no task and every later call is dropped silently.
        var cpuDetector = AsyncTestContext.virtualThreadCpuBoundTaskDetector();
        String taskId = cpuDetector.recordTaskStart("cpu-bound");
        cpuDetector.recordYieldPoint(taskId);
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(80);
        while (System.nanoTime() < until) {
            spin(50_000);
        }
        cpuDetector.recordTaskEnd(taskId);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true,
               includes = {DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION})
    void virtualThreadCarrierExhaustion() {
        reachable("virtualThreadCarrierExhaustionDetector()",
            AsyncTestContext::virtualThreadCarrierExhaustionDetector);

        // Blocking inside a synchronized block pins the carrier; kept to 1 ms so the
        // fixture demonstrates the shape without occupying a carrier for long.
        // Blocking inside a monitor on a virtual thread holds the carrier; enough workers
        // doing it at once and the scheduler runs out of carriers entirely.
        // Exhaustion means as many virtual threads blocked at once as there are carriers, so
        // two workers can never reach it on a multi-core machine however the recording is
        // written. The fixture therefore blocks availableProcessors() virtual threads
        // simultaneously - genuinely, holding them all on a latch until every one has recorded
        // its block - which is the condition, not a description of it.
        var carrierDetector = AsyncTestContext.virtualThreadCarrierExhaustionDetector();
        int carriers = Runtime.getRuntime().availableProcessors();
        java.util.concurrent.CountDownLatch blocked =
                new java.util.concurrent.CountDownLatch(carriers);
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        for (int i = 0; i < carriers; i++) {
            Thread.ofVirtual().start(() -> {
                carrierDetector.recordBlockingStart("synchronized block");
                blocked.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                carrierDetector.recordBlockingEnd("synchronized block");
            });
        }
        try {
            blocked.await(5, TimeUnit.SECONDS);   // every carrier is now blocked at once
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            release.countDown();
        }
        synchronized (CARRIER_MONITOR) {
            pause(1);
        }
    }

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private static final Object CARRIER_MONITOR = new Object();
}
