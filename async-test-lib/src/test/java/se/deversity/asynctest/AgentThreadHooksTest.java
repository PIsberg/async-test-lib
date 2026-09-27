package se.deversity.asynctest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a woven thread start is judged on (#834, #737), with the hooks called the way woven code
 * calls them.
 *
 * <p>A woven {@code Thread.start} of a thread woven code constructed is an observed start:
 * {@link DaemonThreadHygieneDetector} reports the thread while it lives unless a woven daemon
 * decision was seen, a {@code setDaemon(true)} or a {@code daemon(true)} on the builder that made
 * it. A thread constructed where nothing is woven may have been decided there, so its start is
 * judged by the flag alone.
 */
class AgentThreadHooksTest {

    /**
     * Starts a lingering thread from a daemon parent inside a context, through {@code start}, and
     * {@return whether the daemon-hygiene detector reported it}.
     */
    private static boolean lingeringStartReported(java.util.function.Function<Runnable, Thread> start)
            throws InterruptedException {
        AsyncTestContext.install(new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build()));
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> started = new AtomicReference<>();
        try {
            AsyncTestContext context = AsyncTestContext.get();
            Thread daemonParent = new Thread(() -> {
                AsyncTestContext.install(context);
                try {
                    started.set(start.apply(() -> {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                } finally {
                    AsyncTestContext.uninstall();
                }
            }, "daemon-parent");
            daemonParent.setDaemon(true);
            daemonParent.start();
            daemonParent.join();
            return AsyncTestContext.daemonThreadHygieneDetector().analyze().hasIssues();
        } finally {
            release.countDown();
            if (started.get() != null) {
                started.get().join();
            }
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("a daemon thread a builder started with a woven daemon(true) is not judged undecided")
    void aBuilderStartCarriesTheBuildersDecision() throws InterruptedException {
        assertFalse(lingeringStartReported(task -> AgentThreadHooks.threadBuilderStart(
                        AgentThreadHooks.threadBuilderDaemon(Thread.ofPlatform().name("built-daemon"),
                                true), task)),
                "daemon(true) on the builder is the decision the rule asks for");
        assertFalse(lingeringStartReported(task -> {
            Thread thread = AgentThreadHooks.threadBuilderUnstarted(
                    AgentThreadHooks.threadBuilderDaemon(Thread.ofPlatform().name("built-daemon")),
                    task);
            AgentThreadHooks.threadStart(thread);
            return thread;
        }), "daemon(), then unstarted and a separate start, is the same decision");
    }

    @Test
    @DisplayName("a builder start with no daemon decision is an observed, undecided start")
    void aBuilderStartWithNoDecisionIsReported() throws InterruptedException {
        assertTrue(lingeringStartReported(task -> AgentThreadHooks.threadBuilderStart(
                        Thread.ofPlatform().name("built-undecided"), task)),
                "the builder made the thread in woven code and nothing decided its flag, so it is "
                        + "daemon only by inheritance from the daemon parent");
    }

    @Test
    @DisplayName("a thread woven code constructed and started without setDaemon is reported")
    void aWovenStartWithNoDecisionIsStillReported() throws InterruptedException {
        assertTrue(lingeringStartReported(task -> {
            Thread thread = new Thread(task, "inherited-daemon");
            AgentThreadHooks.threadConstructed(thread);
            AgentThreadHooks.threadStart(thread);
            return thread;
        }), "daemon only by inheritance from the daemon parent, and no setDaemon seen");
    }

    @Test
    @DisplayName("a daemon thread constructed where nothing is woven is judged by its flag")
    void aThreadConstructedOutsideTheWovenSetIsJudgedByItsFlag() throws InterruptedException {
        assertFalse(lingeringStartReported(task -> {
            Thread thread = new Thread(task, "decided-elsewhere");
            thread.setDaemon(true);
            AgentThreadHooks.threadStart(thread);
            return thread;
        }), "setDaemon(true) where nothing is woven is a decision the agent cannot see, so the "
                + "missing record must not read as a missing decision (#737)");
        assertTrue(lingeringStartReported(task -> {
            Thread thread = new Thread(task, "non-daemon-elsewhere");
            thread.setDaemon(false);
            AgentThreadHooks.threadStart(thread);
            return thread;
        }), "a non-daemon thread holds the JVM open whoever made it");
    }
}
