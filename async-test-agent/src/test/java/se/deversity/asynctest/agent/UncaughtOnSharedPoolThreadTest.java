package se.deversity.asynctest.agent;

import com.example.agentfixture.TaskExecutingBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestMethodOrder;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.Preset;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A pool kept in a static, whose thread a worker of an earlier run constructed, runs a task a
 * later run handed it through a woven {@code execute}, and the task kills the thread (#1253). The
 * death is the later run's: the thread was constructed under the earlier run, so without the
 * woven hand-off it would be attributed there, to a run already over, and dropped.
 */
@Tag("e2e")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UncaughtOnSharedPoolThreadTest {

    private static final List<Thread> MADE = new CopyOnWriteArrayList<>();
    private static final ExecutorService SHARED = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "shared-pool-" + MADE.size());
        MADE.add(thread);
        return thread;
    });
    private static AsyncFindings findings;

    private final TaskExecutingBean bean = new TaskExecutingBean();

    @BeforeAll
    static void attachWithThreadWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,collections=true");
        findings = AsyncFindings.collect();
    }

    @Order(1)
    @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
            preset = Preset.NONE)
    void anEarlierRunConstructsThePoolThread() throws Exception {
        SHARED.submit(() -> { }).get(); // the worker's first submission constructs the thread
    }

    @Order(2)
    @AsyncTest(threads = 1, invocations = 1, includes = DetectorType.UNCAUGHT_EXCEPTION_HANDLER,
            preset = Preset.NONE)
    void aLaterRunKillsIt() throws InterruptedException {
        Thread poolThread = MADE.get(0);
        bean.execute(SHARED, () -> {
            throw new IllegalStateException("killed a pool thread an earlier run built");
        });
        poolThread.join();
    }

    @AfterAll
    static void theDeathIsTheLaterRuns() {
        SHARED.shutdownNow();
        try {
            String report = findings.violationsFrom("UncaughtExceptionHandlerDetector").toString();
            assertTrue(report.contains("Thread 'shared-pool-0' threw 'IllegalStateException'"),
                    "the task the later run handed over killed the pool thread, and nothing was "
                            + "reported; findings were: " + findings.violations());
        } finally {
            findings.close();
        }
    }
}
