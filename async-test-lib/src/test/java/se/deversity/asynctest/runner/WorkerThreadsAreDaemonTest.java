package se.deversity.asynctest.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import se.deversity.asynctest.AsyncTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A worker this library starts must never be the reason a JVM cannot exit.
 *
 * <p>The runner cannot interrupt a worker blocked on a monitor - nothing can - so a test body that
 * deadlocks leaves its workers alive for the life of the process. While those workers were
 * platform threads created by {@code Executors.defaultThreadFactory()} they were also non-daemon,
 * which meant the deadlock did not merely fail one test: it held the whole JVM open.
 *
 * <p>Surefire hides that completely, because {@code reuseForks=false} gives every test class its
 * own fork and kills it. PIT does not: it runs every class in one JVM and waits for that JVM to
 * exit, so the same two deadlocked workers stopped a mutation run dead. The evidence is in #479 -
 * a run whose last output was at 16:31:39, then three hours at zero load with 2.3 GB of a 16 GB
 * machine used, ending with the runner reporting orphaned java processes. A thread dump of the
 * reproduction showed the whole JVM down to {@code DestroyJavaVM} plus two BLOCKED
 * {@code async-test-worker-*} threads with no daemon marker.
 *
 * <p>Virtual threads have always been daemon by construction, and they are the default, which is
 * why this went unnoticed: only a test that asks for {@code useVirtualThreads = false} could hold
 * a JVM open.
 */
class WorkerThreadsAreDaemonTest {

    /** What each worker saw about itself, keyed by thread name so a rerun cannot inflate it. */
    private static final Set<String> DAEMON = ConcurrentHashMap.newKeySet();
    private static final Set<String> NON_DAEMON = ConcurrentHashMap.newKeySet();

    /**
     * Reports what the running worker is. Asserting from inside the body is deliberate: reading
     * the property after the run would have to find the threads again, and a thread that has
     * already exited is indistinguishable from one that was never started.
     */
    public static class PlatformWorkerDummy {

        @AsyncTest(threads = 3, invocations = 2, useVirtualThreads = false)
        void recordWhatKindOfThreadRunsMe() {
            Thread self = Thread.currentThread();
            (self.isDaemon() ? DAEMON : NON_DAEMON).add(self.getName());
        }
    }

    @Test
    @DisplayName("platform workers are daemon threads, so a stuck one cannot hold the JVM open")
    void platformWorkersAreDaemonThreads() {
        DAEMON.clear();
        NON_DAEMON.clear();

        org.junit.platform.launcher.core.LauncherFactory.create().execute(
            org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request()
                .selectors(org.junit.platform.engine.discovery.DiscoverySelectors
                    .selectClass(PlatformWorkerDummy.class))
                .build());

        assertFalse(DAEMON.isEmpty() && NON_DAEMON.isEmpty(),
            "the dummy never ran, so this test proves nothing about worker threads");
        assertEquals(Set.of(), NON_DAEMON,
            "a non-daemon worker keeps the JVM alive after main returns, and a worker deadlocked "
                + "on a monitor can never be interrupted out of it - which is how two blocked "
                + "workers stopped a PIT run for three hours (#479)");
        assertTrue(DAEMON.size() >= 3,
            "three threads were asked for; got " + DAEMON);
    }
}
