package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SleepInLockDetector;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the woven {@code Thread.sleep} hooks record, across all three overloads.
 *
 * <p>Only {@code sleep(long)} was woven until #440. The other two were tracked gaps rather than
 * decisions, because a sleep under a lock is the same bug however it is spelled and
 * {@code sleep(Duration)} is the form new code writes since JDK 19. Each overload needs its own
 * monitor-taking variant on this class, which is what kept them out of #434's sweep.
 *
 * <p>Every case runs on the test thread and calls the hook exactly as woven code would. The
 * guarded and unguarded halves are both here: a sleep with no lock held is rate limiting,
 * back-off or polling, and reporting it would make the finding worthless.
 */
class AgentSleepHooksTest {

    /** The monitor the guarded cases hold, standing in for a synchronized method's receiver. */
    private static final Object MONITOR = new Object();

    private static SleepInLockDetector installedDetector() {
        AsyncTestContext.install(
                new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
        SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
        // recordSleep drops everything while monitoring is false, so a test that skipped this
        // would assert silence it never earned.
        detector.startMonitoring();
        return detector;
    }

    @Test
    @DisplayName("a Duration sleep inside a synchronized method is recorded against its monitor")
    void durationSleepHoldingTheMonitorIsRecorded() throws InterruptedException {
        SleepInLockDetector detector = installedDetector();
        try {
            synchronized (MONITOR) {
                AgentSleepHooks.sleepHoldingMonitor(Duration.ofMillis(2), MONITOR);
            }

            SleepInLockDetector.SleepInLockReport report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "the monitor is held by construction on this path, so the sleep is a finding. "
                            + "Report: " + report);
            assertEquals(2L, report.getEvents().get(0).sleepDuration,
                    "the duration is recorded in milliseconds, which is the detector's model and "
                            + "what its report prints");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a Duration sleep with no lock held records nothing")
    void durationSleepHoldingNothingIsNotAFinding() throws InterruptedException {
        SleepInLockDetector detector = installedDetector();
        try {
            AgentSleepHooks.sleep(Duration.ofMillis(1));

            assertFalse(detector.analyze().hasIssues(),
                    "back-off, polling and rate limiting are all Thread.sleep with nothing held, "
                            + "and they are vastly more common than the bug");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a sub-millisecond sleep under a lock is reported rather than rounded away")
    void subMillisecondSleepIsStillRecorded() throws InterruptedException {
        SleepInLockDetector detector = installedDetector();
        try {
            synchronized (MONITOR) {
                AgentSleepHooks.sleepHoldingMonitor(0L, 500_000, MONITOR);
            }

            SleepInLockDetector.SleepInLockReport report = detector.analyze();
            assertTrue(report.hasIssues(),
                    "recordSleep drops anything at or below zero, so truncating 0ms + 500us to "
                            + "zero would lose this sleep entirely - silence indistinguishable "
                            + "from a sleep that never happened. Report: " + report);
            assertEquals(1L, report.getEvents().get(0).sleepDuration,
                    "rounded up to the smallest duration the model can carry, not down to none");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("the nanosecond overload records the milliseconds it was given")
    void nanosecondOverloadRecordsItsMilliseconds() throws InterruptedException {
        SleepInLockDetector detector = installedDetector();
        try {
            synchronized (MONITOR) {
                AgentSleepHooks.sleepHoldingMonitor(3L, 250_000, MONITOR);
            }

            assertEquals(3L, detector.analyze().getEvents().get(0).sleepDuration,
                    "a sleep with a whole-millisecond part is recorded as that, with the "
                            + "remainder below what the model represents");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a monitor the caller does not hold records nothing, on every overload")
    void namingAMonitorYouDoNotHoldAssertsNothingIntoExistence() throws InterruptedException {
        SleepInLockDetector detector = installedDetector();
        try {
            AgentSleepHooks.sleepHoldingMonitor(1L, MONITOR);
            AgentSleepHooks.sleepHoldingMonitor(Duration.ofMillis(1), MONITOR);
            AgentSleepHooks.sleepHoldingMonitor(1L, 0, MONITOR);

            assertFalse(detector.analyze().hasIssues(),
                    "recordSleep asks Thread.holdsLock before believing the caller, and nothing "
                            + "here holds MONITOR. The guarded variants are only ever reached "
                            + "from inside a synchronized method, so this is the shape a "
                            + "mis-woven call site would take");
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("every hook performs the sleep it replaced, with no context installed")
    void hooksDelegateOutsideAnAsyncTest() throws InterruptedException {
        long before = System.nanoTime();
        AgentSleepHooks.sleep(1L);
        AgentSleepHooks.sleep(Duration.ofMillis(1));
        AgentSleepHooks.sleep(0L, 500_000);
        AgentSleepHooks.sleepHoldingMonitor(1L, MONITOR);
        AgentSleepHooks.sleepHoldingMonitor(Duration.ofMillis(1), MONITOR);
        AgentSleepHooks.sleepHoldingMonitor(0L, 500_000, MONITOR);

        assertTrue(System.nanoTime() - before >= 3_500_000L,
                "six hooks asked for 3.5ms of sleep between them; a hook that skipped the call it "
                        + "replaced would make instrumented code compute something different "
                        + "from uninstrumented code");
    }

    @Test
    @DisplayName("every sleep hook has the monitor-taking variant the weaver resolves by name")
    void everySleepHookHasItsSynchronizedVariant() {
        List<Method> plain = Arrays.stream(AgentSleepHooks.class.getMethods())
                .filter(m -> Modifier.isStatic(m.getModifiers()))
                .filter(m -> "sleep".equals(m.getName()))
                .toList();
        assertEquals(3, plain.size(),
                "Thread.sleep has three overloads and all three are woven; found: " + plain);

        for (Method hook : plain) {
            Class<?>[] withMonitor = Arrays.copyOf(hook.getParameterTypes(),
                    hook.getParameterCount() + 1);
            withMonitor[withMonitor.length - 1] = Object.class;
            assertTrue(hasMethod("sleepHoldingMonitor", withMonitor),
                    "CollectionAccessWeaver resolves the whenSynchronized hook as the entry's own "
                            + "parameters plus the monitor, and throws at table-build time when "
                            + "it is missing - which means agent install fails rather than one "
                            + "overload quietly going unwoven. Missing for "
                            + Arrays.toString(hook.getParameterTypes()));
        }
    }

    private static boolean hasMethod(String name, Class<?>... signature) {
        try {
            AgentSleepHooks.class.getMethod(name, signature);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }
}
