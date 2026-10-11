package se.deversity.asynctest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SystemPropertyMutationDetector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Process-global state written from a run's threads, reported through the woven hooks alone.
 *
 * <p>{@code System.setProperty}, {@code Locale.setDefault} and {@code TimeZone.setDefault} change
 * what every thread in the JVM reads. {@code SYSTEM_PROPERTY_MUTATION} judged only writes a body
 * recorded; the agent now substitutes the static calls themselves, and the two JVM defaults are
 * tracked under a key of their own, since nothing else covered them.
 */
class AgentGlobalStateHooksTest {

    private static final String KEY = "async-test.agent-global-state-hooks-test";

    private final Locale localeBefore = Locale.getDefault();
    private final TimeZone zoneBefore = TimeZone.getDefault();

    @AfterEach
    void restore() {
        System.clearProperty(KEY);
        Locale.setDefault(localeBefore);
        TimeZone.setDefault(zoneBefore);
    }

    /** Runs {@code body} on two threads at once, each with {@code context} installed. */
    private static void together(AsyncTestContext context, Runnable body) throws InterruptedException {
        CyclicBarrier start = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                AsyncTestContext.install(context);
                try {
                    start.await(10, TimeUnit.SECONDS);
                    body.run();
                } catch (Exception e) {
                    failure.set(e);
                } finally {
                    AsyncTestContext.uninstall();
                }
            }, "global-writer-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static AsyncTestContext newContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
    }

    private static SystemPropertyMutationDetector.SystemPropertyMutationReport reportOf(
            AsyncTestContext context) {
        AsyncTestContext.install(context);
        try {
            return AsyncTestContext.systemPropertyMutationDetector().analyze();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a system property set from two threads with no lock is reported")
    void propertySetFromTwoThreadsIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        together(context, () -> AgentSleepHooks.systemSetProperty(KEY, Thread.currentThread().getName()));

        SystemPropertyMutationDetector.SystemPropertyMutationReport report = reportOf(context);
        assertTrue(report.hasIssues(), "two unguarded writers of one key: " + report);
        assertTrue(report.toString().contains(KEY), report.toString());
    }

    @Test
    @DisplayName("the same writes under the properties table's monitor stay silent")
    void propertySetUnderTheTablesMonitorIsSilent() throws InterruptedException {
        AsyncTestContext context = newContext();
        together(context, () -> {
            synchronized (System.getProperties()) {
                AgentSleepHooks.systemSetProperty(KEY, "x");
                AgentSleepHooks.systemClearProperty(KEY);
            }
        });

        assertFalse(reportOf(context).hasIssues(),
                "a set-and-restore under the table's own lock is how a property is shared");
    }

    @Test
    @DisplayName("the default Locale set from two threads is reported")
    void defaultLocaleSetFromTwoThreadsIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        together(context, () -> AgentSleepHooks.localeSetDefault(Locale.CANADA_FRENCH));

        SystemPropertyMutationDetector.SystemPropertyMutationReport report = reportOf(context);
        assertTrue(report.hasIssues(),
                "Locale.setDefault changes what every String.format and DateFormat in the JVM "
                        + "reads, and two threads raced on it: " + report);
        assertTrue(report.toString().contains("default Locale"), report.toString());
        assertEquals(Locale.CANADA_FRENCH, Locale.getDefault(), "the hook still set the default");
    }

    @Test
    @DisplayName("the default TimeZone set from two threads is reported")
    void defaultTimeZoneSetFromTwoThreadsIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        TimeZone tokyo = TimeZone.getTimeZone("Asia/Tokyo");
        together(context, () -> AgentSleepHooks.timeZoneSetDefault(tokyo));

        assertTrue(reportOf(context).toString().contains("default TimeZone"),
                "TimeZone.setDefault is the same process-wide write");
        assertEquals("Asia/Tokyo", TimeZone.getDefault().getID(), "the hook still set the default");
    }

    @Test
    @DisplayName("one thread setting a property is a hygiene note, not a finding")
    void oneWriterIsNotAFinding() {
        AsyncTestContext context = newContext();
        AsyncTestContext.install(context);
        try {
            assertEquals(null, AgentSleepHooks.systemSetProperty(KEY, "one"),
                    "the hook returns the previous value, which was unset");
            assertEquals("one", AgentSleepHooks.systemClearProperty(KEY),
                    "and clearProperty returns what it removed");
        } finally {
            AsyncTestContext.uninstall();
        }
        assertFalse(reportOf(context).hasIssues(), "nothing raced");
    }
}
