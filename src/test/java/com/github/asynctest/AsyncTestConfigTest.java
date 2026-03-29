package com.github.asynctest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AsyncTestConfig.Builder#build()} —
 * specifically the {@code detectAll} umbrella flag and {@code excludes} logic.
 */
class AsyncTestConfigTest {

    // ---- detectAll = true enables everything ----

    @Test
    void detectAll_enablesEveryPhase1Detector() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();

        assertTrue(cfg.detectDeadlocks,             "detectDeadlocks");
        assertTrue(cfg.detectVisibility,            "detectVisibility");
        assertTrue(cfg.detectLivelocks,             "detectLivelocks");
    }

    @Test
    void detectAll_enablesEveryPhase2Detector() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();

        assertTrue(cfg.detectFalseSharing,                   "detectFalseSharing");
        assertTrue(cfg.detectWakeupIssues,                   "detectWakeupIssues");
        assertTrue(cfg.validateConstructorSafety,            "validateConstructorSafety");
        assertTrue(cfg.detectABAProblem,                     "detectABAProblem");
        assertTrue(cfg.validateLockOrder,                    "validateLockOrder");
        assertTrue(cfg.monitorSynchronizers,                 "monitorSynchronizers");
        assertTrue(cfg.monitorThreadPool,                    "monitorThreadPool");
        assertTrue(cfg.detectMemoryOrderingViolations,       "detectMemoryOrderingViolations");
        assertTrue(cfg.monitorAsyncPipeline,                 "monitorAsyncPipeline");
        assertTrue(cfg.monitorReadWriteLockFairness,         "monitorReadWriteLockFairness");
        assertTrue(cfg.monitorSemaphore,                     "monitorSemaphore");
        assertTrue(cfg.detectCompletableFutureExceptions,    "detectCompletableFutureExceptions");
        assertTrue(cfg.detectConcurrentModifications,        "detectConcurrentModifications");
        assertTrue(cfg.detectLockLeaks,                      "detectLockLeaks");
        assertTrue(cfg.detectSharedRandom,                   "detectSharedRandom");
        assertTrue(cfg.detectBlockingQueueIssues,            "detectBlockingQueueIssues");
        assertTrue(cfg.detectConditionVariableIssues,        "detectConditionVariableIssues");
        assertTrue(cfg.detectSimpleDateFormatIssues,         "detectSimpleDateFormatIssues");
        assertTrue(cfg.detectParallelStreamIssues,           "detectParallelStreamIssues");
        assertTrue(cfg.detectResourceLeaks,                  "detectResourceLeaks");
        assertTrue(cfg.detectCountDownLatchIssues,           "detectCountDownLatchIssues");
        assertTrue(cfg.detectCyclicBarrierIssues,            "detectCyclicBarrierIssues");
        assertTrue(cfg.detectReentrantLockIssues,            "detectReentrantLockIssues");
        assertTrue(cfg.detectVolatileArrayIssues,            "detectVolatileArrayIssues");
        assertTrue(cfg.detectDoubleCheckedLocking,           "detectDoubleCheckedLocking");
        assertTrue(cfg.detectWaitTimeout,                    "detectWaitTimeout");
        assertTrue(cfg.detectPhaserIssues,                   "detectPhaserIssues");
        assertTrue(cfg.detectStampedLockIssues,              "detectStampedLockIssues");
        assertTrue(cfg.detectExchangerIssues,                "detectExchangerIssues");
        assertTrue(cfg.detectScheduledExecutorIssues,        "detectScheduledExecutorIssues");
        assertTrue(cfg.detectForkJoinPoolIssues,             "detectForkJoinPoolIssues");
        assertTrue(cfg.detectThreadFactoryIssues,            "detectThreadFactoryIssues");
    }

    @Test
    void detectAll_enablesEveryPhase3Detector() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();

        assertTrue(cfg.detectRaceConditions,        "detectRaceConditions");
        assertTrue(cfg.detectThreadLocalLeaks,      "detectThreadLocalLeaks");
        assertTrue(cfg.detectBusyWaiting,           "detectBusyWaiting");
        assertTrue(cfg.detectAtomicityViolations,   "detectAtomicityViolations");
        assertTrue(cfg.detectInterruptMishandling,  "detectInterruptMishandling");
    }

    // ---- detectAll + excludes ----

    @Test
    void detectAll_withExcludeDeadlocks_leavesDeadlocksDisabled() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{DetectorType.DEADLOCKS})
                .build();

        assertFalse(cfg.detectDeadlocks, "Excluded DEADLOCKS must remain false");
        assertTrue(cfg.detectVisibility, "Non-excluded detector must still be enabled");
    }

    @Test
    void detectAll_withMultipleExcludes_allExcludedAreDisabled() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{
                        DetectorType.FALSE_SHARING,
                        DetectorType.BUSY_WAITING,
                        DetectorType.RACE_CONDITIONS
                })
                .build();

        assertFalse(cfg.detectFalseSharing,      "FALSE_SHARING must be excluded");
        assertFalse(cfg.detectBusyWaiting,       "BUSY_WAITING must be excluded");
        assertFalse(cfg.detectRaceConditions,    "RACE_CONDITIONS must be excluded");
        // Others still on
        assertTrue(cfg.detectLivelocks,          "Non-excluded LIVELOCKS must be on");
    }

    // ---- excludes without detectAll ----

    @Test
    void excludes_withoutDetectAll_overridesExplicitEnable() {
        // If the user sets a flag true then also excludes it, excludes wins
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectVisibility(true)
                .excludes(new DetectorType[]{DetectorType.VISIBILITY})
                .build();

        assertFalse(cfg.detectVisibility,
                "excludes() must override an explicit enable when detectAll=false");
    }

    @Test
    void excludes_withoutDetectAll_doesNotEnableOtherDetectors() {
        // Excludes with no detectAll must not accidentally enable anything
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .excludes(new DetectorType[]{DetectorType.DEADLOCKS})
                .build();

        assertFalse(cfg.detectVisibility,   "visibility must remain off");
        assertFalse(cfg.detectLivelocks,    "livelocks must remain off");
        assertFalse(cfg.detectFalseSharing, "falseSharing must remain off");
        // Deadlocks itself is now excluded (overrides the default=true)
        assertFalse(cfg.detectDeadlocks,    "excluded deadlocks must be false");
    }

    // ---- defaults ----

    @Test
    void defaults_onlyDeadlocksIsOn() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().build();
        assertTrue(cfg.detectDeadlocks, "deadlocks is on by default");
        assertFalse(cfg.detectAll,       "detectAll is off by default");
        assertFalse(cfg.detectVisibility,"visibility is off by default");
        assertFalse(cfg.detectFalseSharing, "falseSharing is off by default");
    }
}
