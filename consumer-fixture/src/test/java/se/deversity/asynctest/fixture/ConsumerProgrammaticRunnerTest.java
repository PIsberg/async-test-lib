package se.deversity.asynctest.fixture;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.AsyncTestRunner;
import se.deversity.asynctest.FailOn;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Consumer-side coverage of the 1.10.0 programmatic entry point, {@code AsyncTestRunner}: the
 * engine as a method call, for the test frameworks {@code @AsyncTest} cannot run inside of.
 *
 * <p>Like the other files here it compiles against the built artifact rather than the source, so
 * a passing run proves a consumer can reach {@code AsyncTestRunner}, {@code AsyncTestRunner.Body}
 * and the returned {@code AsyncFindings} without touching internals. It is a plain {@code @Test}
 * on purpose: the point of the API is that no {@code @AsyncTest} is involved.
 */
class ConsumerProgrammaticRunnerTest {

    @Test
    void theEngineRunsAsAMethodCall_andReturnsWhatItFound() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        Object owner = new Object();
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .threads(4).invocations(20).detectAll(true).failOn(FailOn.NONE)
                .licenseMockMode(true)
                .build();

        AsyncFindings findings = AsyncTestRunner.run("consumer-fixture", cfg, () -> {
            runs.incrementAndGet();
            AsyncTestContext ctx = AsyncTestContext.get();
            assertNotNull(ctx, "the body must see an installed context, as an annotated method does");
            ctx.sharedRaceConditionDetector().recordFieldWrite(owner, "counter");
        });

        assertEquals(80, runs.get(), "4 threads x 20 rounds");
        findings.assertReported("RaceConditionDetector");
    }
}
