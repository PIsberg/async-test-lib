package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.DecimalFormat;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hooks added for #434 do what they replaced, and count what they claim to count.
 *
 * <p>{@code WovenOverloadCoverageTest} in the agent module proves the weaver's table lists these
 * overloads. It cannot prove the hook behind an entry behaves - a hook that called the wrong
 * overload, or dropped a return value, would satisfy it completely. These two tests are that
 * other half.
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
