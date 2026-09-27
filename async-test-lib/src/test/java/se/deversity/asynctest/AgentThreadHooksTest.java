package se.deversity.asynctest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the thread starts a builder makes are judged on (#834), called the way woven code calls them.
 *
 * <p>A woven {@code Thread.start} is an observed start: {@link DaemonThreadHygieneDetector} reports
 * the thread while it lives unless a woven {@code setDaemon(true)} was seen. A
 * {@code Thread.Builder} decides the flag with {@code daemon(true)} inside the JDK, where nothing is
 * woven, so its start must not be judged that way or the decision would read as missing.
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
    @DisplayName("a daemon thread a builder started with daemon(true) is not judged undecided")
    void aBuilderStartIsNotAnObservedStart() throws InterruptedException {
        assertFalse(lingeringStartReported(task -> AgentThreadHooks.threadBuilderStart(
                        Thread.ofPlatform().daemon(true).name("built-daemon"), task)),
                "daemon(true) on the builder is the decision the rule asks for, made where the agent "
                        + "does not look");
    }

    @Test
    @DisplayName("the same lingering thread started through a woven Thread.start without setDaemon is")
    void aWovenStartWithNoDecisionIsStillReported() throws InterruptedException {
        assertTrue(lingeringStartReported(task -> {
            Thread thread = new Thread(task, "inherited-daemon");
            AgentThreadHooks.threadStart(thread);
            return thread;
        }), "daemon only by inheritance from the daemon parent, and no setDaemon seen");
    }
}
