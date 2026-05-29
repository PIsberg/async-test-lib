package se.deversity.asynctest;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Preset → AsyncTestConfig resolution. The matrix:
 *
 * <ul>
 *   <li>{@link Preset#ALL} / {@link Preset#STRICT} — every detector flag stays at the
 *       annotation default (current behavior, detectAll honored).</li>
 *   <li>{@link Preset#ESSENTIALS} — exactly the listed detectors enabled, rest off.</li>
 *   <li>{@link Preset#CI_FAST} — minimal high-signal subset; visibility / livelocks
 *       / heavyweight ones must be off.</li>
 *   <li>{@link Preset#NONE} — every detector flag is off.</li>
 *   <li>user-supplied {@code excludes()} prune even from a curated preset.</li>
 * </ul>
 */
class PresetResolutionTest {

    @Test
    void presetAll_enablesEverything() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.ALL));
        assertTrue(cfg.detectAll, "ALL preset must keep detectAll");
        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectVisibility, "ALL must enable VISIBILITY (heavy but in scope)");
    }

    @Test
    void presetStrict_isSameAsAll() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.STRICT));
        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectVisibility);
    }

    @Test
    void presetNone_disablesEveryDetector() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.NONE));
        // Spot-check across phases — every detector flag should be false.
        assertFalse(cfg.detectDeadlocks);
        assertFalse(cfg.detectRaceConditions);
        assertFalse(cfg.detectVisibility);
        assertFalse(cfg.detectLockLeaks);
        assertFalse(cfg.detectSharedMessageDigest);
        assertFalse(cfg.detectUncaughtExceptionHandler);
    }

    @Test
    void presetEssentials_enablesOnlyTheCuratedSet() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.ESSENTIALS));
        // In:
        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectAtomicityViolations);
        assertTrue(cfg.detectLockLeaks);
        assertTrue(cfg.detectInterruptMishandling);
        assertTrue(cfg.detectConcurrentModifications);
        assertTrue(cfg.detectCompletableFutureExceptions);
        assertTrue(cfg.detectResourceLeaks);
        // Out (representative samples):
        assertFalse(cfg.detectVisibility, "ESSENTIALS must skip the heavyweight visibility detector");
        assertFalse(cfg.detectFalseSharing);
        assertFalse(cfg.detectSharedMessageDigest);
    }

    @Test
    void presetCiFast_isSmallerThanEssentials() {
        AsyncTestConfig cfg = AsyncTestConfig.from(annotation(Preset.CI_FAST));
        assertTrue(cfg.detectDeadlocks);
        assertTrue(cfg.detectRaceConditions);
        assertTrue(cfg.detectAtomicityViolations);
        assertTrue(cfg.detectLockLeaks);
        assertTrue(cfg.detectConcurrentModifications);
        assertTrue(cfg.detectCompletableFutureExceptions);
        // CI_FAST omits these but ESSENTIALS includes them.
        assertFalse(cfg.detectLivelocks);
        assertFalse(cfg.detectInterruptMishandling);
        assertFalse(cfg.detectVisibility);
    }

    @Test
    void userExcludesPruneFromPreset() {
        AsyncTestConfig cfg = AsyncTestConfig.from(
                annotation(Preset.ESSENTIALS, DetectorType.DEADLOCKS, DetectorType.RACE_CONDITIONS));
        assertFalse(cfg.detectDeadlocks, "Explicit excludes must remove a detector even from a preset");
        assertFalse(cfg.detectRaceConditions);
        // Other preset members still on.
        assertTrue(cfg.detectAtomicityViolations);
    }

    // ---- helper: build a stand-in AsyncTest annotation via reflection proxy ----

    private static AsyncTest annotation(Preset preset, DetectorType... excludes) {
        // We don't need a real annotation — AsyncTestConfig.from reads via accessor
        // methods only, so a JDK proxy backed by the annotation's defaults plus our
        // overrides is the cleanest stand-in.
        return new AsyncTestStub(preset, excludes);
    }

    /**
     * Minimal AsyncTest implementation used to drive AsyncTestConfig.from in unit
     * tests. Every method defers to the annotation's default value except preset()
     * and excludes(), which the tests vary.
     */
    @SuppressWarnings("ClassExplicitlyAnnotation")
    private static final class AsyncTestStub implements AsyncTest {
        private final Preset preset;
        private final DetectorType[] excludes;
        AsyncTestStub(Preset preset, DetectorType[] excludes) {
            this.preset = preset;
            this.excludes = excludes;
        }
        private static <T> T def(String name) {
            try {
                @SuppressWarnings("unchecked")
                T v = (T) AsyncTest.class.getDeclaredMethod(name).getDefaultValue();
                return v;
            } catch (NoSuchMethodException e) { throw new AssertionError(e); }
        }
        @Override public int threads() { return def("threads"); }
        @Override public int[] threadCounts() { return def("threadCounts"); }
        @Override public int invocations() { return def("invocations"); }
        @Override public boolean useVirtualThreads() { return def("useVirtualThreads"); }
        @Override public long timeoutMs() { return def("timeoutMs"); }
        @Override public boolean detectDeadlocks() { return def("detectDeadlocks"); }
        @Override public boolean detectVisibility() { return def("detectVisibility"); }
        @Override public boolean detectLivelocks() { return def("detectLivelocks"); }
        @Override public String virtualThreadStressMode() { return def("virtualThreadStressMode"); }
        @Override public boolean detectAll() { return def("detectAll"); }
        @Override public Preset preset() { return preset; }
        @Override public long replaySeed() { return def("replaySeed"); }
        @Override public DetectorType[] excludes() { return excludes; }
        @Override public boolean detectFalseSharing() { return def("detectFalseSharing"); }
        @Override public boolean detectWakeupIssues() { return def("detectWakeupIssues"); }
        @Override public boolean validateConstructorSafety() { return def("validateConstructorSafety"); }
        @Override public boolean detectABAProblem() { return def("detectABAProblem"); }
        @Override public boolean validateLockOrder() { return def("validateLockOrder"); }
        @Override public boolean monitorSynchronizers() { return def("monitorSynchronizers"); }
        @Override public boolean monitorThreadPool() { return def("monitorThreadPool"); }
        @Override public boolean detectMemoryOrderingViolations() { return def("detectMemoryOrderingViolations"); }
        @Override public boolean monitorAsyncPipeline() { return def("monitorAsyncPipeline"); }
        @Override public boolean monitorReadWriteLockFairness() { return def("monitorReadWriteLockFairness"); }
        @Override public boolean detectRaceConditions() { return def("detectRaceConditions"); }
        @Override public boolean detectThreadLocalLeaks() { return def("detectThreadLocalLeaks"); }
        @Override public boolean detectBusyWaiting() { return def("detectBusyWaiting"); }
        @Override public boolean detectAtomicityViolations() { return def("detectAtomicityViolations"); }
        @Override public boolean detectInterruptMishandling() { return def("detectInterruptMishandling"); }
        @Override public boolean monitorSemaphore() { return def("monitorSemaphore"); }
        @Override public boolean detectCompletableFutureExceptions() { return def("detectCompletableFutureExceptions"); }
        @Override public boolean detectCompletableFutureCompletionLeaks() { return def("detectCompletableFutureCompletionLeaks"); }
        @Override public boolean detectVirtualThreadPinning() { return def("detectVirtualThreadPinning"); }
        @Override public boolean detectThreadPoolDeadlocks() { return def("detectThreadPoolDeadlocks"); }
        @Override public boolean detectConcurrentModifications() { return def("detectConcurrentModifications"); }
        @Override public boolean detectLockLeaks() { return def("detectLockLeaks"); }
        @Override public boolean detectSharedRandom() { return def("detectSharedRandom"); }
        @Override public boolean detectBlockingQueueIssues() { return def("detectBlockingQueueIssues"); }
        @Override public boolean detectConditionVariableIssues() { return def("detectConditionVariableIssues"); }
        @Override public boolean detectSimpleDateFormatIssues() { return def("detectSimpleDateFormatIssues"); }
        @Override public boolean detectParallelStreamIssues() { return def("detectParallelStreamIssues"); }
        @Override public boolean detectResourceLeaks() { return def("detectResourceLeaks"); }
        @Override public boolean detectCountDownLatchIssues() { return def("detectCountDownLatchIssues"); }
        @Override public boolean detectCyclicBarrierIssues() { return def("detectCyclicBarrierIssues"); }
        @Override public boolean detectReentrantLockIssues() { return def("detectReentrantLockIssues"); }
        @Override public boolean detectVolatileArrayIssues() { return def("detectVolatileArrayIssues"); }
        @Override public boolean detectDoubleCheckedLocking() { return def("detectDoubleCheckedLocking"); }
        @Override public boolean detectWaitTimeout() { return def("detectWaitTimeout"); }
        @Override public boolean detectLockContention() { return def("detectLockContention"); }
        @Override public boolean detectSynchronizedNonFinal() { return def("detectSynchronizedNonFinal"); }
        @Override public boolean detectMissedSignals() { return def("detectMissedSignals"); }
        @Override public boolean detectLazyInitRace() { return def("detectLazyInitRace"); }
        @Override public boolean detectPhaserIssues() { return def("detectPhaserIssues"); }
        @Override public boolean detectStampedLockIssues() { return def("detectStampedLockIssues"); }
        @Override public boolean detectExchangerIssues() { return def("detectExchangerIssues"); }
        @Override public boolean detectScheduledExecutorIssues() { return def("detectScheduledExecutorIssues"); }
        @Override public boolean detectForkJoinPoolIssues() { return def("detectForkJoinPoolIssues"); }
        @Override public boolean detectThreadFactoryIssues() { return def("detectThreadFactoryIssues"); }
        @Override public boolean detectThreadLeaks() { return def("detectThreadLeaks"); }
        @Override public boolean detectSleepInLock() { return def("detectSleepInLock"); }
        @Override public boolean detectUnboundedQueue() { return def("detectUnboundedQueue"); }
        @Override public boolean detectThreadStarvation() { return def("detectThreadStarvation"); }
        @Override public boolean detectCalendarIssues() { return def("detectCalendarIssues"); }
        @Override public boolean detectSharedCollections() { return def("detectSharedCollections"); }
        @Override public boolean detectTimerIssues() { return def("detectTimerIssues"); }
        @Override public boolean detectCopyOnWriteCollectionIssues() { return def("detectCopyOnWriteCollectionIssues"); }
        @Override public boolean detectStringBuilderIssues() { return def("detectStringBuilderIssues"); }
        @Override public boolean detectStructuredConcurrencyIssues() { return def("detectStructuredConcurrencyIssues"); }
        @Override public boolean detectVirtualThreadContextLeaks() { return def("detectVirtualThreadContextLeaks"); }
        @Override public boolean detectScopedValueMisuse() { return def("detectScopedValueMisuse"); }
        @Override public boolean detectVirtualThreadCpuBoundTasks() { return def("detectVirtualThreadCpuBoundTasks"); }
        @Override public boolean detectVirtualThreadCarrierExhaustion() { return def("detectVirtualThreadCarrierExhaustion"); }
        @Override public boolean detectHttpClientIssues() { return def("detectHttpClientIssues"); }
        @Override public boolean detectStreamClosing() { return def("detectStreamClosing"); }
        @Override public boolean detectCacheConcurrency() { return def("detectCacheConcurrency"); }
        @Override public boolean detectCompletableFutureChainIssues() { return def("detectCompletableFutureChainIssues"); }
        @Override public boolean detectExecutorShutdown() { return def("detectExecutorShutdown"); }
        @Override public boolean detectMutableMapKeys() { return def("detectMutableMapKeys"); }
        @Override public boolean detectNestedMonitorLockout() { return def("detectNestedMonitorLockout"); }
        @Override public boolean detectLockDowngrade() { return def("detectLockDowngrade"); }
        @Override public boolean detectInheritableThreadLocalMisuse() { return def("detectInheritableThreadLocalMisuse"); }
        @Override public boolean detectUncommittedChanges() { return def("detectUncommittedChanges"); }
        @Override public boolean detectThreadLocalContamination() { return def("detectThreadLocalContamination"); }
        @Override public boolean detectAtomicNonAtomicUpdates() { return def("detectAtomicNonAtomicUpdates"); }
        @Override public boolean detectSynchronizedCollectionIteration() { return def("detectSynchronizedCollectionIteration"); }
        @Override public boolean detectSharedFormatter() { return def("detectSharedFormatter"); }
        @Override public boolean detectConcurrentMapComputeRecursion() { return def("detectConcurrentMapComputeRecursion"); }
        @Override public boolean detectSynchronizedOnLiteral() { return def("detectSynchronizedOnLiteral"); }
        @Override public boolean detectPublicLockExposure() { return def("detectPublicLockExposure"); }
        @Override public boolean detectForkJoinTaskBlocking() { return def("detectForkJoinTaskBlocking"); }
        @Override public boolean detectOptimisticReadValidation() { return def("detectOptimisticReadValidation"); }
        @Override public boolean detectCFCommonPoolBlocking() { return def("detectCFCommonPoolBlocking"); }
        @Override public boolean detectSharedMatcher() { return def("detectSharedMatcher"); }
        @Override public boolean detectSharedDecimalFormat() { return def("detectSharedDecimalFormat"); }
        @Override public boolean detectWeakReferenceRace() { return def("detectWeakReferenceRace"); }
        @Override public boolean detectStatefulLambda() { return def("detectStatefulLambda"); }
        @Override public boolean detectSharedMessageDigest() { return def("detectSharedMessageDigest"); }
        @Override public boolean detectInterruptSwallowing() { return def("detectInterruptSwallowing"); }
        @Override public boolean detectMdcContextLeak() { return def("detectMdcContextLeak"); }
        @Override public boolean detectSystemPropertyMutation() { return def("detectSystemPropertyMutation"); }
        @Override public boolean detectFutureIgnored() { return def("detectFutureIgnored"); }
        @Override public boolean detectExplicitGc() { return def("detectExplicitGc"); }
        @Override public boolean detectDeprecatedThreadApi() { return def("detectDeprecatedThreadApi"); }
        @Override public boolean detectSharedXmlParser() { return def("detectSharedXmlParser"); }
        @Override public boolean detectBoxedPrimitiveLock() { return def("detectBoxedPrimitiveLock"); }
        @Override public boolean detectSharedTimeZone() { return def("detectSharedTimeZone"); }
        @Override public boolean detectUncaughtExceptionHandler() { return def("detectUncaughtExceptionHandler"); }
        @Override public boolean detectDaemonThreadHygiene() { return def("detectDaemonThreadHygiene"); }
        @Override public boolean detectNotifyWithoutMonitor() { return def("detectNotifyWithoutMonitor"); }
        @Override public boolean detectSharedSecureRandom() { return def("detectSharedSecureRandom"); }
        @Override public boolean detectWeakHashMapShared() { return def("detectWeakHashMapShared"); }
        @Override public boolean detectJdbcConnectionShared() { return def("detectJdbcConnectionShared"); }
        @Override public boolean detectSharedStatefulCrypto() { return def("detectSharedStatefulCrypto"); }
        @Override public boolean detectConcurrentMapCheckThenAct() { return def("detectConcurrentMapCheckThenAct"); }
        @Override public boolean detectSharedDeflater() { return def("detectSharedDeflater"); }
        @Override public boolean detectThisEscape() { return def("detectThisEscape"); }
        @Override public boolean detectThreadLocalRandomMisuse() { return def("detectThreadLocalRandomMisuse"); }
        @Override public boolean enableBenchmarking() { return def("enableBenchmarking"); }
        @Override public double benchmarkRegressionThreshold() { return def("benchmarkRegressionThreshold"); }
        @Override public boolean failOnBenchmarkRegression() { return def("failOnBenchmarkRegression"); }
        @Override public String keygenAccountId() { return def("keygenAccountId"); }
        @Override public String keygenApiKey() { return def("keygenApiKey"); }
        @Override public String keygenProductId() { return def("keygenProductId"); }
        @Override public String lemonSqueezyStore() { return def("lemonSqueezyStore"); }
        @Override public String licenseKey() { return def("licenseKey"); }
        @Override public boolean licenseMockMode() { return def("licenseMockMode"); }
        @Override public Class<? extends Annotation> annotationType() { return AsyncTest.class; }
    }
}
