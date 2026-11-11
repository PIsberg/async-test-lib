package se.deversity.asynctest.agent;

import com.example.agentfixture.TerminationBean;
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
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code ExecutorService.awaitTermination} returning {@code true}, and {@code close}, as
 * happens-before edges (#1266), judged end to end: weaver, hooks, model and
 * {@code AtomicityValidator}.
 *
 * <p>A pool terminates only once every task it ran has ended, so what each task did is ordered
 * before whatever the thread that saw the termination does next. Each silent case runs the tasks
 * on a pool a worker submitted to and updates their fields after the termination; each twin keeps
 * the same accesses without the order, coordinated through latches in this unwoven test class,
 * which the model cannot see, and must keep its finding.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM and this one needs
 * {@code fields=true} and {@code collections=true}; {@code forkEvery=1} gives it its own JVM.
 */
@Tag("e2e")
class TerminationEdgeWeavingTest {

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

    /** Runs {@code call} on one worker of a fresh bridge, the way the runner feeds a round. */
    private static List<String> onAWorker(Call call) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            Thread worker = new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    assertTrue(call.run() > 0, "the fixture reported an unexpected termination");
                } catch (Throwable t) { // NOPMD - reported to the test thread below
                    failed.set(t);
                }
            }, "run-worker");
            worker.start();
            worker.join(TimeUnit.SECONDS.toMillis(20));
            assertFalse(worker.isAlive(), "the worker did not finish");
            TelemetryRegistry.flush();
        }
        if (failed.get() != null) {
            throw new AssertionError("the worker failed, so nothing was measured", failed.get());
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }

    private static boolean mentionsTheBox(List<String> findings) {
        return findings.stream().anyMatch(f -> f.contains("Box.value"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the pool's side never got there");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("tasks submitted to a pool are ordered before the update after awaitTermination")
    void awaitTerminationOrdersSubmittedTasks() throws InterruptedException {
        TerminationBean bean = new TerminationBean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> findings = onAWorker(() -> bean.submitThenAwaitTermination(pool));

        assertFalse(mentionsTheBox(findings),
                "Three submitted tasks wrote their boxes, the pool was shut down and "
                        + "awaitTermination returned true, so every task had ended before the "
                        + "update. A finding here means the woven awaitTermination fed no edge. "
                        + "Findings were: " + findings);
    }

    @Test
    @DisplayName("the same for tasks given to execute, which have no future")
    void awaitTerminationOrdersExecutedTasks() throws InterruptedException {
        TerminationBean bean = new TerminationBean();
        ExecutorService pool = new ForkJoinPool(2);
        List<String> findings = onAWorker(() -> bean.executeThenAwaitTermination(pool));

        assertFalse(mentionsTheBox(findings),
                "execute hands back no future, so only the termination orders these tasks before "
                        + "the update. Findings were: " + findings);
    }

    @Test
    @DisplayName("tasks submitted inside try-with-resources are ordered before the update after it")
    void closeOrdersSubmittedTasks() throws InterruptedException {
        TerminationBean bean = new TerminationBean();
        List<String> findings = onAWorker(bean::submitInsideTryWithResources);

        assertFalse(mentionsTheBox(findings),
                "ExecutorService.close waits for every task to end, which is the idiom the JDK "
                        + "documents for virtual threads. Findings were: " + findings);
    }

    @Test
    @DisplayName("an update made before awaitTermination keeps its finding")
    void updatingBeforeTheTerminationIsReported() throws InterruptedException {
        TerminationBean bean = new TerminationBean();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch written = new CountDownLatch(3);
        List<String> findings = onAWorker(() -> bean.updateBeforeAwaitTermination(pool,
                written::countDown, () -> await(written)));

        assertTrue(mentionsTheBox(findings),
                "The submitter updated the boxes after a latch the agent does not see and before "
                        + "awaitTermination, so nothing the program does orders the tasks' writes "
                        + "before the update. Findings were: " + findings);
    }

    @Test
    @DisplayName("an awaitTermination that returned false orders nothing")
    void aTimedOutAwaitTerminationIsReported() throws InterruptedException {
        TerminationBean bean = new TerminationBean();
        ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<String> findings = onAWorker(() -> bean.updateAfterAwaitTerminationTimedOut(pool,
                    () -> {
                        // The pool counts a task as completed only after it returned, so after
                        // the wrapper ended it and released into the pool.
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                        while (pool.getCompletedTaskCount() < 1 && System.nanoTime() < deadline) {
                            Thread.onSpinWait();
                        }
                        assertTrue(pool.getCompletedTaskCount() >= 1, "the first task never ended");
                    },
                    () -> await(release)));

            assertTrue(mentionsTheBox(findings),
                    "The first task had ended and released into the pool, but the other held it "
                            + "open, so awaitTermination returned false and the update is ordered "
                            + "after nothing. Silence here means a false return acquired what the "
                            + "ended task published. Findings were: " + findings);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the pool did not terminate");
        }
    }

    @Test
    @DisplayName("close on the common pool, which does nothing, orders nothing")
    void closingTheCommonPoolIsReported() throws InterruptedException {
        TerminationBean bean = new TerminationBean();
        // Spins rather than awaitQuiescence, which may run the queued task on the calling worker
        // and leave nothing for two threads to race on. The pool is quiet only once no worker
        // is active, so once the task's wrapper has ended it and released into the pool.
        List<String> findings = onAWorker(() -> bean.updateAfterClosingTheCommonPool(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!ForkJoinPool.commonPool().isQuiescent() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(ForkJoinPool.commonPool().isQuiescent(), "the common pool never went quiet");
        }));

        assertTrue(mentionsTheBox(findings),
                "The JDK documents close on the common pool as having no effect: it waits for "
                        + "nothing, so the task's write is ordered before the update only by a "
                        + "quiescence wait the agent does not see. Findings were: " + findings);
    }
}
