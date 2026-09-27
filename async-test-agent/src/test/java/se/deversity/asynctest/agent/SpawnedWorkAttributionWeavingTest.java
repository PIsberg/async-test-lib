package se.deversity.asynctest.agent;

import com.example.agentfixture.SpawnedWorkBean;
import com.example.agentfixture.SpawnedWorkBean.Box;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Work a test body hands to threads of its own, judged end to end: weaver, hooks, the bridge's
 * attribution and {@code AtomicityValidator} (#745).
 *
 * <p>The bridge forwards the events of the threads in its worker set, which is what the runner
 * gives it: each worker adds itself as it starts. A child thread a worker starts, or a pool thread
 * running a task a worker submitted, is none of those, and its accesses used to be dropped, so a
 * race between a body and the thread it spawned passed silent. Each case runs the fixture on one
 * registered worker and asserts both halves: the unordered read is reported, the read ordered by
 * the join or the get is not. The last case is the other direction of attribution: a child that
 * outlives the run that started it contributes nothing to the next run.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM and this one needs
 * {@code fields=true} and {@code collections=true}; {@code forkEvery=1} gives it its own JVM.
 */
@Tag("e2e")
class SpawnedWorkAttributionWeavingTest {

    @BeforeAll
    static void attachWithFieldAndCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** A fixture call that may throw. */
    private interface Call {
        int run() throws Exception;
    }

    /** What one run saw: the validator's findings and the events its bridge dropped. */
    private record Run(List<String> findings, long dropped) {

        boolean mentionsTheBox() {
            return findings.stream().anyMatch(f -> f.contains("Box.value"));
        }
    }

    /** Runs {@code call} on one worker of a fresh bridge, the way the runner feeds a round. */
    private static Run onAWorker(Call call) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        long dropped;
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            Thread worker = new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    assertEquals(42, call.run(), "the worker must have read the spawned write");
                } catch (Throwable t) { // NOPMD - reported to the test thread below
                    failed.set(t);
                }
            }, "run-worker");
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(20));
            assertFalse(worker.isAlive(), "the worker did not finish");
            TelemetryRegistry.flush();
            dropped = bridge.droppedNonWorkerEvents();
        }
        if (failed.get() != null) {
            throw new AssertionError("the worker failed, so nothing was measured", failed.get());
        }
        return new Run(validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList(), dropped);
    }

    /** A written/await-written pair, paired through a latch the agent does not see. */
    private static final class Written {

        final CountDownLatch latch = new CountDownLatch(1);

        void signal() {
            latch.countDown();
        }

        void await() {
            try {
                assertTrue(latch.await(10, TimeUnit.SECONDS), "the spawned side never wrote");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    @DisplayName("a child's write the worker reads before the join is reported")
    void childReadBeforeTheJoinIsReported() throws InterruptedException {
        SpawnedWorkBean bean = new SpawnedWorkBean();
        Written written = new Written();
        Run run = onAWorker(() -> bean.childReadBeforeJoin(42, written::signal, written::await));

        assertTrue(run.mentionsTheBox(),
                "The worker started a child, which wrote the box, and read it before the join, "
                        + "after a latch the agent does not see: a race between the body and the "
                        + "thread it spawned. A silent run means the child's write never reached "
                        + "the validator. Findings were: " + run.findings()
                        + ", events dropped: " + run.dropped());
    }

    @Test
    @DisplayName("the same write read after the join is ordered, and nothing was dropped")
    void childReadAfterTheJoinIsSilent() throws InterruptedException {
        SpawnedWorkBean bean = new SpawnedWorkBean();
        Run run = onAWorker(() -> bean.childReadAfterJoin(42));

        assertFalse(run.mentionsTheBox(),
                "Thread.join orders the child's write before the worker's read. Findings were: "
                        + run.findings());
        assertEquals(0L, run.dropped(),
                "the child was started by a worker, so its accesses belong to the run");
    }

    @Test
    @DisplayName("a submitted task's write the worker reads before the get is reported")
    void taskReadBeforeTheGetIsReported() throws InterruptedException {
        SpawnedWorkBean bean = new SpawnedWorkBean();
        Written written = new Written();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Run run = onAWorker(() -> bean.taskReadBeforeGet(executor, 42, written::signal,
                    written::await));

            assertTrue(run.mentionsTheBox(),
                    "The worker submitted a task, which wrote the box on a pool thread, and read "
                            + "it before the get. A silent run means the task's write never reached "
                            + "the validator. Findings were: " + run.findings()
                            + ", events dropped: " + run.dropped());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("the same task's write read after the get is ordered, and nothing was dropped")
    void taskReadAfterTheGetIsSilent() throws InterruptedException {
        SpawnedWorkBean bean = new SpawnedWorkBean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Run run = onAWorker(() -> bean.taskReadAfterGet(executor, 42));

            assertFalse(run.mentionsTheBox(),
                    "The task started after the submit and the get returned after it ended, so "
                            + "the write is ordered before the read. Findings were: "
                            + run.findings());
            assertEquals(0L, run.dropped(),
                    "the task was submitted by a worker, so its accesses belong to the run");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a child that outlives the run that started it contributes nothing to the next run")
    void aChildOutlivingItsRunIsNotAttributedToTheNext() throws InterruptedException {
        SpawnedWorkBean bean = new SpawnedWorkBean();
        Box box = bean.newBox();
        CountDownLatch release = new CountDownLatch(1);
        Thread[] lingering = new Thread[1];
        onAWorker(() -> {
            lingering[0] = bean.startLingering(box, 42, () -> {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return 42;
        });

        Run next = onAWorker(() -> {
            release.countDown();
            lingering[0].join(TimeUnit.SECONDS.toMillis(10));
            return bean.read(box);
        });

        assertFalse(next.mentionsTheBox(),
                "The child was started by the first run's worker and wrote the box during the "
                        + "second run; the second run's worker never started it or handed it a "
                        + "task, so the write is not the second run's. Findings were: "
                        + next.findings());
        assertTrue(next.dropped() > 0,
                "the lingering child's write must have reached the second run's bridge and been "
                        + "dropped there, or this case measured nothing");
    }
}
