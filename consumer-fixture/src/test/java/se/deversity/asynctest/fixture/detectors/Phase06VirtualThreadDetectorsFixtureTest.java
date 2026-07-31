package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.pause;
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

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true, includes = {DetectorType.STRUCTURED_CONCURRENCY})
    void structuredConcurrency() {
        reachable("structuredConcurrencyMisuseDetector()",
            AsyncTestContext::structuredConcurrencyMisuseDetector);

        // Fork two children and join both before returning — the discipline a structured
        // scope enforces, here written by hand so it compiles without preview APIs.
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
        spin(50_000);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               useVirtualThreads = true,
               includes = {DetectorType.VIRTUAL_THREAD_CARRIER_EXHAUSTION})
    void virtualThreadCarrierExhaustion() {
        reachable("virtualThreadCarrierExhaustionDetector()",
            AsyncTestContext::virtualThreadCarrierExhaustionDetector);

        // Blocking inside a synchronized block pins the carrier; kept to 1 ms so the
        // fixture demonstrates the shape without occupying a carrier for long.
        synchronized (CARRIER_MONITOR) {
            pause(1);
        }
    }

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private static final Object CARRIER_MONITOR = new Object();
}
