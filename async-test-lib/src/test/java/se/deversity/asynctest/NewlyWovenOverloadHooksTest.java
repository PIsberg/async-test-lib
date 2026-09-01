package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DecimalFormat;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Calendar;
import java.util.Formatter;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import se.deversity.asynctest.diagnostics.SleepInLockDetector.SleepInLockReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hooks added for #434 do what they replaced, and count what they claim to count.
 *
 * <p>{@code WovenOverloadCoverageTest} in the agent module proves the weaver's table lists these
 * overloads. It cannot prove the hook behind an entry behaves - a hook that called the wrong
 * overload, or dropped a return value, would satisfy it completely. These tests are that other
 * half.
 *
 * <p>The delegation test matters more than it looks. A substitution hook stands between the
 * caller and the JDK on every call, so a hook that quietly changed behaviour would corrupt the
 * program under test rather than merely mis-measure it, and it would do so only when the agent
 * was attached.
 */
class NewlyWovenOverloadHooksTest {

    @Test
    @DisplayName("every hook added for #434 performs the operation it replaced")
    void hooksDelegateOutsideAnAsyncTest() throws Exception {
        StringBuilder builder = new StringBuilder();
        AgentSharedInstanceHooks.append(builder, 'a');
        AgentSharedInstanceHooks.append(builder, 1L);
        AgentSharedInstanceHooks.append(builder, true);
        AgentSharedInstanceHooks.append(builder, (Object) "o");
        AgentSharedInstanceHooks.append(builder, (CharSequence) "c");
        assertEquals("a1trueoc", builder.toString(), "each append wrote what it was given");

        Matcher matcher = Pattern.compile("([a-z]+)-([0-9]+)").matcher("corpus-42");
        assertTrue(AgentSharedInstanceHooks.find(matcher, 0), "find(int) searches from the index");
        assertEquals("corpus", AgentSharedInstanceHooks.group(matcher, 1),
                "group(int) returns the indexed group");

        DecimalFormat decimal = new DecimalFormat("#");
        assertEquals("7", AgentSharedInstanceHooks.format(decimal, 7L),
                "format(long) formats the number rather than its address");

        Calendar calendar = new GregorianCalendar();
        AgentSharedInstanceHooks.set(calendar, 2026, Calendar.MARCH, 4);
        assertEquals(2026, calendar.get(Calendar.YEAR), "set(y, m, d) set the year");
        assertEquals(4, calendar.get(Calendar.DAY_OF_MONTH), "set(y, m, d) set the day");

        Formatter formatter = new Formatter(new StringBuilder(), Locale.ROOT);
        AgentSharedInstanceHooks.format(formatter, Locale.ROOT, "%d", 3);
        assertEquals("3", formatter.out().toString(), "format(Locale, ...) wrote through");
        formatter.close();

        Semaphore semaphore = new Semaphore(2);
        AgentConcurrencyUtilHooks.acquire(semaphore, 2);
        assertEquals(0, semaphore.availablePermits(), "acquire(int) took both permits");
        AgentConcurrencyUtilHooks.release(semaphore, 2);
        assertEquals(2, semaphore.availablePermits(), "release(int) returned both");
        assertTrue(AgentConcurrencyUtilHooks.tryAcquire(semaphore, 1), "tryAcquire(int) succeeds");
        semaphore.release();
        assertTrue(AgentConcurrencyUtilHooks.tryAcquire(semaphore, 10, TimeUnit.MILLISECONDS),
                "the timed tryAcquire succeeds when a permit is free");
        semaphore.release();

        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        assertTrue(AgentConcurrencyUtilHooks.offer(queue, "x", 10, TimeUnit.MILLISECONDS),
                "the timed offer accepts into a queue with room");
        assertFalse(AgentConcurrencyUtilHooks.offer(queue, "y", 1, TimeUnit.MILLISECONDS),
                "the timed offer reports a full queue rather than blocking forever");
        assertEquals("x", AgentConcurrencyUtilHooks.poll(queue, 10, TimeUnit.MILLISECONDS),
                "the timed poll returns the head");
    }

    @Test
    @DisplayName("every sleep and map hook added for #440 performs the operation it replaced")
    void theSleepAndMapHooksDelegate() throws Exception {
        long before = System.nanoTime();
        AgentSleepHooks.sleep(Duration.ofMillis(2));
        AgentSleepHooks.sleep(1L, 500_000);
        AgentSleepHooks.sleepHoldingMonitor(Duration.ofMillis(2), LOCK);
        AgentSleepHooks.sleepHoldingMonitor(1L, 500_000, LOCK);
        assertTrue(System.nanoTime() - before >= 4_000_000L,
                "all four hooks must actually sleep, not just record");

        Map<Object, Object> map = new HashMap<>();
        map.put("k", "v");
        assertFalse(AgentCollectionHooks.mapRemove(map, "k", "other"),
                "the conditional remove leaves an entry whose value does not match");
        assertTrue(map.containsKey("k"), "and really leaves it");
        assertTrue(AgentCollectionHooks.mapRemove(map, "k", "v"),
                "the conditional remove takes an entry whose value matches");
        assertTrue(map.isEmpty(), "and really takes it");
    }

    @Test
    @DisplayName("a sleep holding a monitor is reported whichever overload expressed the duration")
    void everySleepOverloadReportsWhenAMonitorIsHeld() {
        // The point of #440. Before it, a sleep inside a synchronized method was invisible purely
        // because its duration was spelled as a Duration rather than as a long - the detector's
        // question is whether a lock went un-progressed, which does not depend on the spelling.
        assertTrue(sleepReportsHoldingTheLock(
                        () -> AgentSleepHooks.sleepHoldingMonitor(Duration.ofMillis(2), LOCK)),
                "sleepHoldingMonitor(Duration, Object) must report");
        assertTrue(sleepReportsHoldingTheLock(
                        () -> AgentSleepHooks.sleepHoldingMonitor(2L, 0, LOCK)),
                "sleepHoldingMonitor(long, int, Object) must report");
    }

    @Test
    @DisplayName("a sleep holding nothing stays silent, whichever overload")
    void noSleepOverloadReportsWithoutAMonitor() {
        // The other direction, because the test above passes for a hook that reports every sleep.
        assertFalse(sleepReports(() -> AgentSleepHooks.sleep(Duration.ofMillis(2))),
                "sleep(Duration) with no lock held is a backoff, not a finding");
        assertFalse(sleepReports(() -> AgentSleepHooks.sleep(2L, 0)),
                "sleep(long, int) with no lock held is a backoff, not a finding");
    }

    @Test
    @DisplayName("a sub-millisecond sleep under a monitor is reported, not truncated away")
    void subMillisecondSleepsUnderAMonitorAreReported() {
        // recordSleep drops anything at or below zero, so a hook that truncated the sub-
        // millisecond part would leave these silent - and silence is what an unwoven call site
        // looks like, which is the one thing this weaving surface exists to remove. The sleep is
        // real: on JDK 26, sleep(0, 500_000) blocks for about 1.5ms against about 17us for
        // sleep(0).
        assertTrue(sleepReportsHoldingTheLock(
                        () -> AgentSleepHooks.sleepHoldingMonitor(Duration.ofNanos(500_000), LOCK)),
                "sleepHoldingMonitor(Duration, Object) must report a half-millisecond sleep");
        assertTrue(sleepReportsHoldingTheLock(
                        () -> AgentSleepHooks.sleepHoldingMonitor(0L, 500_000, LOCK)),
                "sleepHoldingMonitor(long, int, Object) must report a half-millisecond sleep");
    }

    @Test
    @DisplayName("a zero-length sleep under a monitor stays silent")
    void zeroLengthSleepsUnderAMonitorStaySilent() {
        // The boundary the rounding must not cross. sleep(0) and Duration.ZERO hold the lock for
        // no measurable time, so rounding them up would report every one of them - and sleep(0)
        // is a yield idiom, not a bug.
        assertFalse(sleepReportsHoldingTheLock(
                        () -> AgentSleepHooks.sleepHoldingMonitor(Duration.ZERO, LOCK)),
                "a zero Duration is not a sleep");
        assertFalse(sleepReportsHoldingTheLock(
                        () -> AgentSleepHooks.sleepHoldingMonitor(0L, 0, LOCK)),
                "sleep(0, 0) is not a sleep");
    }

    @Test
    @DisplayName("the duration a finding carries is in milliseconds")
    void theReportedDurationIsInMilliseconds() {
        // SleepInLockEventSnapshot.sleepDuration was documented as nanoseconds. Both recordSleep
        // overloads take milliseconds and the report prints "ms", so the javadoc was wrong rather
        // than the code - and a public field's unit is not something to leave to a reader's guess.
        SleepInLockReport report = sleepReportHoldingTheLock(
                () -> AgentSleepHooks.sleepHoldingMonitor(7L, LOCK));

        assertEquals(1, report.getEvents().size(), "one sleep, one event: " + report);
        assertEquals(7L, report.getEvents().get(0).sleepDuration,
                "a 7ms sleep is recorded as 7, so the field is milliseconds, not nanoseconds");
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
                    "CollectionAccessWeaver resolves a whenSynchronized hook as the entry's own "
                            + "parameters plus the monitor, so a missing variant fails the table "
                            + "build rather than quietly leaving one overload unwoven. Missing "
                            + "for " + Arrays.toString(hook.getParameterTypes()));
        }
    }

    /** {@return whether {@code AgentSleepHooks} declares {@code name} with {@code signature}} */
    private static boolean hasMethod(String name, Class<?>... signature) {
        try {
            AgentSleepHooks.class.getMethod(name, signature);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    /**
     * {@return whether {@code body} reported, run with {@link #LOCK} genuinely held}
     *
     * <p>The monitor has to be held for real. {@code recordSleep} asks {@code Thread.holdsLock}
     * rather than trusting the argument, so passing the monitor without holding it records
     * nothing - which is what the weaver's synchronized path guarantees at the call site and what
     * this has to reproduce for the assertion to mean anything.
     */
    private static boolean sleepReportsHoldingTheLock(Sleeping body) {
        return sleepReportHoldingTheLock(body).hasIssues();
    }

    /** {@return the report {@code body} produced, run with {@link #LOCK} genuinely held} */
    private static SleepInLockReport sleepReportHoldingTheLock(Sleeping body) {
        return sleepReport(() -> {
            synchronized (LOCK) {
                body.run();
            }
        });
    }

    /** {@return whether {@code body} produced a sleep-in-lock finding} */
    private static boolean sleepReports(Sleeping body) {
        return sleepReport(body).hasIssues();
    }

    /** {@return the sleep-in-lock report {@code body} produced} */
    private static SleepInLockReport sleepReport(Sleeping body) {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectSleepInLock(true).build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        AsyncTestContext.install(ctx);
        try {
            AsyncTestContext.sleepInLockDetector().startMonitoring();
            body.run();
            return AsyncTestContext.sleepInLockDetector().analyze();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing here interrupts", e);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    /** A body that sleeps and may be interrupted. */
    @FunctionalInterface
    private interface Sleeping {
        void run() throws InterruptedException;
    }

    /** The monitor the synchronized-form sleeps are recorded against. */
    private static final Object LOCK = new Object();

    @Test
    @DisplayName("a semaphore's permit count is recorded per permit, not per call")
    void permitCountsAreRecordedIndividually() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().monitorSemaphore(true).build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        AsyncTestContext.install(ctx);
        try {
            Semaphore semaphore = new Semaphore(3);
            AgentConcurrencyUtilHooks.acquire(semaphore, 3);
            AgentConcurrencyUtilHooks.release(semaphore, 1);

            // Three permits out and one back is a leak of two. Recording one event per call
            // would make the counts 1 and 1, which reads as balanced - the bug this hook has to
            // avoid, and the reason the loop inside it is not an accident.
            assertTrue(AsyncTestContext.semaphoreMisuseDetector().analyze().hasIssues(),
                    "acquire(3) followed by release(1) leaks two permits and must report");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing here blocks", e);
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a balanced permit count reports nothing")
    void balancedPermitCountsAreSilent() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().monitorSemaphore(true).build();
        AsyncTestContext ctx = new AsyncTestContext(cfg);
        AsyncTestContext.install(ctx);
        try {
            Semaphore semaphore = new Semaphore(3);
            AgentConcurrencyUtilHooks.acquire(semaphore, 3);
            AgentConcurrencyUtilHooks.release(semaphore, 3);

            // The other direction, because the test above passes for a detector that reports
            // every semaphore it ever sees.
            assertFalse(AsyncTestContext.semaphoreMisuseDetector().analyze().hasIssues(),
                    "acquire(3) followed by release(3) is balanced and must stay silent");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("nothing here blocks", e);
        } finally {
            AsyncTestContext.uninstall();
        }
    }
}
