package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code ThreadLocal} value lives per thread, so cleanup has to be judged per thread (#565).
 *
 * <p>The monitor kept one {@code cleanedUp} flag per {@code ThreadLocal} for the whole run, which
 * failed in both directions. A {@code remove()} on one thread marked the value gone on every
 * other thread and in every later round, where it was still set. And the accumulation rule counted
 * every {@code ThreadLocal} a thread had ever touched, including the ones it had removed, so a
 * thread that tidied up after six of them was reported for retaining six values.
 */
class ThreadLocalMonitorAccuracyTest {

    @Test
    @DisplayName("a thread that removed every ThreadLocal it set retains none")
    void removedThreadLocalsDoNotAccumulate() {
        var monitor = new ThreadLocalMonitor();
        List<ThreadLocal<String>> locals = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            locals.add(new ThreadLocal<>());
        }

        for (ThreadLocal<String> local : locals) {
            local.set("value");
            monitor.recordThreadLocalInit(local, "tidy");
            local.remove();
            monitor.recordThreadLocalCleanup(local);
        }

        assertFalse(monitor.analyze().hasIssues(), monitor.analyze().toString());
    }

    @Test
    @DisplayName("a remove on one thread does not clean up another thread's value")
    void cleanupIsPerThread() throws InterruptedException {
        var monitor = new ThreadLocalMonitor();
        ThreadLocal<String> local = new ThreadLocal<>();

        Thread tidy = new Thread(() -> {
            local.set("tidy");
            monitor.recordThreadLocalInit(local, "shared");
            local.remove();
            monitor.recordThreadLocalCleanup(local);
        });
        Thread leaky = new Thread(() -> {
            local.set("leaky");
            monitor.recordThreadLocalInit(local, "shared");
        });
        tidy.start();
        tidy.join();
        leaky.start();
        leaky.join();

        assertTrue(monitor.analyze().hasIssues(),
                "the second thread set a value and never removed it; the first thread's remove() "
                        + "cannot reach another thread's ThreadLocal map");
    }

    @Test
    @DisplayName("a remove in one round does not clean up a later round's value")
    void cleanupIsPerRound() {
        var monitor = new ThreadLocalMonitor();
        ThreadLocal<String> local = new ThreadLocal<>();

        monitor.markInvocationStart();
        local.set("round one");
        monitor.recordThreadLocalInit(local, "per-round");
        local.remove();
        monitor.recordThreadLocalCleanup(local);

        monitor.markInvocationStart();
        local.set("round two");
        monitor.recordThreadLocalInit(local, "per-round");
        local.remove();

        assertTrue(monitor.analyze().hasIssues(),
                "round two set a value and recorded no cleanup, so the first round's does not "
                        + "cover it");
    }

    @Test
    @DisplayName("every thread removing its own value stays silent")
    void everyThreadCleaningUpIsSilent() throws InterruptedException {
        var monitor = new ThreadLocalMonitor();
        ThreadLocal<String> local = new ThreadLocal<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            threads.add(new Thread(() -> {
                local.set("value");
                monitor.recordThreadLocalInit(local, "everyone-tidy");
                monitor.recordThreadLocalAccess(local);
                local.remove();
                monitor.recordThreadLocalCleanup(local);
            }));
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertFalse(monitor.analyze().hasIssues(), monitor.analyze().toString());
    }
}
