package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.ExecutorDeadlockDetector;
import se.deversity.asynctest.diagnostics.FutureBlockingDetector;
import se.deversity.asynctest.diagnostics.LatchMisuseDetector;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the fan-out wiring of {@link LatchMisuseDetector}, {@link ExecutorDeadlockDetector}
 * and {@link FutureBlockingDetector}.
 *
 * <p>All three shipped implemented and unit-tested but wired nowhere: no {@link DetectorType}
 * constant, no config flag, no registry field, no accessor. Their own unit tests passed while
 * a real {@code @AsyncTest} never constructed them, so nothing they detect could ever be
 * reported. This test pins each link of the chain that was missing — config flag → registry
 * field → {@code analyzeAll()} → context accessor.
 */
class ExecutorLatchDetectorWiringTest {

    @Test
    void detectAll_instantiatesAllThree() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();

        assertTrue(cfg.detectLatchMisuse);
        assertTrue(cfg.detectExecutorDeadlock);
        assertTrue(cfg.detectFutureBlocking);

        DetectorRegistry reg = new DetectorRegistry(cfg);
        assertNotNull(reg.latchMisuseDetector);
        assertNotNull(reg.executorDeadlockDetector);
        assertNotNull(reg.futureBlockingDetector);
    }

    @Test
    void excludes_disablesEachOfThem() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{
                        DetectorType.LATCH_MISUSE,
                        DetectorType.EXECUTOR_DEADLOCK,
                        DetectorType.FUTURE_BLOCKING})
                .build();

        DetectorRegistry reg = new DetectorRegistry(cfg);
        assertNull(reg.latchMisuseDetector);
        assertNull(reg.executorDeadlockDetector);
        assertNull(reg.futureBlockingDetector);
    }

    @Test
    void analyzeAll_isCleanWhenNoEventsRecorded() {
        // UNCOMMITTED_CHANGES is excluded because its report embeds working-tree file names,
        // which mid-change can contain the very words asserted against below.
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{DetectorType.UNCOMMITTED_CHANGES})
                .build();

        assertTrue(new DetectorRegistry(cfg).analyzeAll().stream()
                .noneMatch(s -> s.contains("LATCH MISUSE DETECTED")
                        || s.contains("EXECUTOR SELF-DEADLOCK DETECTED")
                        || s.contains("FUTURE BLOCKING ISSUES DETECTED")));
    }

    @Test
    void analyzeAll_reportsRecordedLatchMisuse() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{DetectorType.UNCOMMITTED_CHANGES})
                .build();
        DetectorRegistry reg = new DetectorRegistry(cfg);

        // A latch awaited but never counted down to zero — the canonical misuse.
        Object latch = new Object();
        reg.latchMisuseDetector.registerLatch(latch, "never-released", 2);
        reg.latchMisuseDetector.recordAwait(latch);

        Map<String, String> named = reg.analyzeAllNamed();
        assertTrue(named.containsKey("LatchMisuseDetector"),
                "a recorded misuse must surface through the registry the runner uses; got keys: "
                        + named.keySet());
    }

    @Test
    void contextExposesEachDetector() {
        AsyncTestContext ctx = new AsyncTestContext(
                AsyncTestConfig.builder().detectAll(true).build());
        AsyncTestContext.install(ctx);
        try {
            assertEquals(ctx.latchMisuseDetector, AsyncTestContext.latchMisuseDetector());
            assertEquals(ctx.executorDeadlockDetector, AsyncTestContext.executorDeadlockDetector());
            assertEquals(ctx.futureBlockingDetector, AsyncTestContext.futureBlockingDetector());
        } finally {
            // Install/uninstall must stay symmetric (CLAUDE.md); a leaked context would
            // carry stale detector state into the next test on this thread.
            AsyncTestContext.uninstall();
        }
    }
}
