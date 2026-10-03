package se.deversity.asynctest.agent;

import com.example.agentfixture.HandOffPublicationBean;
import com.example.agentfixture.HandOffPublicationBean.Parcel;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The hand-offs #741 taught the happens-before model, judged end to end: weaver, hooks, model and
 * {@code AtomicityValidator}.
 *
 * <p>Each case runs its two sides on two worker threads that the test coordinates with latches the
 * agent does not see, because the test class is not woven. The only edge the model can know about
 * is the one the fixture's call makes, so a case asserts exactly that edge: the hand-off stays
 * silent, and its twin, the same accesses with the order broken, keeps its finding. An executor's
 * pool thread is not a worker; its accesses count only while it runs a task a worker submitted
 * (#745), and what it contributes here is the clock it passes on.
 *
 * <p>Own class, because {@code selfAttach} is at most once per JVM and this one needs
 * {@code fields=true} and {@code collections=true}; {@code forkEvery=1} gives it its own JVM.
 */
@Tag("e2e")
class HandOffPublicationWeavingTest {

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

    /** A step a worker runs that may throw. */
    private interface Step {
        void run() throws Exception;
    }

    /** A reader's side: it runs {@code writerRuns} to let the writer go and wait for it. */
    private interface ReaderStep {
        void run(Runnable writerRuns) throws Exception;
    }

    /**
     * Runs {@code writer} on one worker and {@code reader} on another, and returns the validator's
     * findings. The writer waits until the reader runs the {@link Runnable} it is handed, which
     * returns once the writer has finished.
     */
    private static List<String> findings(Step writer, ReaderStep reader) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch written = new CountDownLatch(1);
        return findings(() -> {
            await(go);
            try {
                writer.run();
            } finally {
                written.countDown();
            }
        }, () -> {
            try {
                reader.run(() -> {
                    go.countDown();
                    await(written);
                });
            } finally {
                go.countDown();
            }
        });
    }

    /** Runs both sides at once on two workers, and returns the validator's findings. */
    private static List<String> findings(Step one, Step other) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            CountDownLatch registered = new CountDownLatch(2);
            CountDownLatch done = new CountDownLatch(2);
            for (Step step : List.of(one, other)) {
                new Thread(() -> {
                    workers.add(Thread.currentThread().threadId());
                    registered.countDown();
                    try {
                        await(registered);
                        step.run();
                    } catch (Throwable t) { // NOPMD - reported to the test thread below
                        failed.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                }, "hand-off-worker").start();
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        if (failed.get() != null) {
            throw new AssertionError("a worker failed, so nothing was measured", failed.get());
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "the other worker never got there");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean mentionsContents(List<String> findings) {
        return findings.stream().anyMatch(f -> f.contains("Parcel.contents"));
    }

    @Test
    @DisplayName("a parcel completed into a future is ordered for the thread that joined it")
    void completionIsSilent() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        CompletableFuture<Parcel> future = new CompletableFuture<>();
        List<String> findings = findings(() -> bean.complete(future, 1), writerRuns -> {
            writerRuns.run();
            bean.updateJoined(future);
        });

        assertFalse(mentionsContents(findings),
                "The writer filled the parcel and then completed the future with it; the reader's "
                        + "join returned it, which CompletableFuture orders after the completion. A "
                        + "finding here means the woven complete or join fed no edge. Findings "
                        + "were: " + findings);
    }

    @Test
    @DisplayName("the same parcel reached without the join keeps its finding")
    void unjoinedIsReported() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        CompletableFuture<Parcel> future = new CompletableFuture<>();
        List<String> findings = findings(() -> bean.complete(future, 1), writerRuns -> {
            writerRuns.run();
            bean.updateUnjoined();
        });

        assertTrue(mentionsContents(findings),
                "The reader reached the parcel through a plain field and never joined, so nothing "
                        + "orders the writer's fill before its update. Findings were: " + findings);
    }

    @Test
    @DisplayName("a function registered with thenApply runs after what its registrar did before (#741)")
    void aDependentStageIsOrderedAfterItsRegistration() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        Parcel parcel = new Parcel();
        CompletableFuture<Object> source = new CompletableFuture<>();
        List<String> findings = findings(() -> bean.fillAndChain(source, parcel, false), writerRuns -> {
            writerRuns.run();
            bean.completeSource(source);
        });

        assertFalse(mentionsContents(findings),
                "The writer filled the parcel and then registered a function that updates it; the "
                        + "reader completed the source, which ran the function on the reader's "
                        + "thread. CompletableFuture orders the registration before the function. "
                        + "Findings were: " + findings);
    }

    @Test
    @DisplayName("a parcel filled after the thenApply keeps its finding")
    void fillAfterTheRegistrationIsReported() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        Parcel parcel = new Parcel();
        CompletableFuture<Object> source = new CompletableFuture<>();
        List<String> findings = findings(() -> bean.fillAndChain(source, parcel, true), writerRuns -> {
            writerRuns.run();
            bean.completeSource(source);
        });

        assertTrue(mentionsContents(findings),
                "The writer filled the parcel after registering the function, which the registration "
                        + "cannot have ordered before it. Findings were: " + findings);
    }

    @Test
    @DisplayName("a parcel published with AtomicReference.set is ordered for the get that returned it")
    void atomicReferencePublicationIsSilent() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        AtomicReference<Parcel> slot = new AtomicReference<>();
        int[] updated = new int[1];
        List<String> findings = findings(() -> bean.publish(slot, 1), writerRuns -> {
            writerRuns.run();
            updated[0] = bean.updatePublished(slot);
        });

        assertTrue(updated[0] > 0, "the reader must have got the parcel, or nothing was measured");
        assertFalse(mentionsContents(findings),
                "The writer filled the parcel and set it; the reader's get returned it. set and get "
                        + "are a volatile write and read, which order the two. Findings were: "
                        + findings);
    }

    @Test
    @DisplayName("a parcel filled after its set keeps its finding")
    void fillAfterTheSetIsReported() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        AtomicReference<Parcel> slot = new AtomicReference<>();
        int[] updated = new int[1];
        List<String> findings = findings(() -> bean.publishThenFill(slot, 1), writerRuns -> {
            writerRuns.run();
            updated[0] = bean.updatePublished(slot);
        });

        assertTrue(updated[0] > 0, "the reader must have got the parcel, or nothing was measured");
        assertTrue(mentionsContents(findings),
                "The writer filled the parcel after the set, so the set published nothing of it. "
                        + "Findings were: " + findings);
    }

    @Test
    @DisplayName("parcels swapped through an Exchanger are ordered for the partner")
    void exchangeIsSilent() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        Exchanger<Parcel> exchanger = new Exchanger<>();
        List<String> findings = findings((Step) () -> bean.swap(exchanger, 1, false),
                (Step) () -> bean.swap(exchanger, 2, false));

        assertFalse(mentionsContents(findings),
                "Each side filled its parcel before the exchange and updated the partner's after "
                        + "it, which the Exchanger orders. Findings were: " + findings);
    }

    @Test
    @DisplayName("a parcel filled again after the swap keeps its finding")
    void fillAfterTheSwapIsReported() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        Exchanger<Parcel> exchanger = new Exchanger<>();
        List<String> findings = findings((Step) () -> bean.swap(exchanger, 1, true),
                (Step) () -> bean.swap(exchanger, 2, true));

        assertTrue(mentionsContents(findings),
                "Each side wrote its own parcel again after the exchange, while the partner was "
                        + "updating it. Findings were: " + findings);
    }

    @Test
    @DisplayName("a submitted task starts after what its submitter did before the submit")
    void submissionOrdersTheTask() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        CompletableFuture<Parcel> future = new CompletableFuture<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<String> findings = findings(() -> bean.fillAndSubmit(executor, future, 1, false).get(),
                    writerRuns -> {
                        writerRuns.run();
                        bean.updateJoined(future);
                    });

            assertFalse(mentionsContents(findings),
                    "The writer filled the parcel and then submitted a task that completed a future "
                            + "with it; the reader joined the future. The pool thread started after "
                            + "the submit, so its completion published the fill. Findings were: "
                            + findings);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a parcel filled after the submit keeps its finding")
    void fillAfterTheSubmitIsReported() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        CompletableFuture<Parcel> future = new CompletableFuture<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<String> findings = findings(() -> bean.fillAndSubmit(executor, future, 1, true).get(),
                    writerRuns -> {
                        writerRuns.run();
                        bean.updateJoined(future);
                    });

            assertTrue(mentionsContents(findings),
                    "The writer filled the parcel after the submit, which the pool thread's "
                            + "completion cannot have published. Findings were: " + findings);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a task's result is ordered for the thread whose get returned it")
    void getOrdersTheTask() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        BlockingQueue<Parcel> queue = new LinkedBlockingQueue<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<String> findings = findings(() -> bean.fillAndPut(queue, 1), writerRuns -> {
                writerRuns.run();
                bean.updateThroughATask(executor, queue, null, null);
            });

            assertFalse(mentionsContents(findings),
                    "The writer put the parcel on a queue; a task took it off and returned it, and "
                            + "the reader's get returned it. The take orders the pool thread after "
                            + "the writer, and the get the reader after the task. Findings were: "
                            + findings);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("the task's parcel reached before the get keeps its finding")
    void readBeforeTheGetIsReported() throws InterruptedException {
        HandOffPublicationBean bean = new HandOffPublicationBean();
        BlockingQueue<Parcel> queue = new LinkedBlockingQueue<>();
        BlockingQueue<Parcel> detour = new LinkedBlockingQueue<>();
        Consumer<Parcel> leak = detour::add;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<String> findings = findings(() -> bean.fillAndPut(queue, 1), writerRuns -> {
                writerRuns.run();
                bean.updateThroughATask(executor, queue, leak, () -> {
                    try {
                        return detour.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            assertTrue(mentionsContents(findings),
                    "The reader updated the parcel before its get, through a detour in unwoven "
                            + "code, so nothing orders it after the writer. Findings were: "
                            + findings);
        } finally {
            executor.shutdownNow();
        }
    }
}
