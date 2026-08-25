package se.deversity.asynctest.agent;

import com.example.agentfixture.DirectFieldMutationBean;
import com.example.agentfixture.GuardedFieldMutationBean;
import com.example.agentfixture.StaticLazyCacheBean;
import com.example.agentfixture.StaticLazySubmitBean;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the capability {@code fields=true} adds: a field touched only from inside a method body
 * becomes observable.
 *
 * <p><strong>Why this test exists.</strong> The README's headline example is a bare
 * {@code counter++} in a test body, and until field weaving landed nothing in the product could
 * see it — {@code AgentFeedsDetectorEndToEndTest} pins exactly that boundary, and it still does,
 * because weaving is opt-in and that test attaches without the flag. This class is the other side
 * of the same boundary: same fixture, same detector, same pipeline, one extra agent option. Read
 * the two together and the observation surface is fully specified.
 *
 * <p>Separate class rather than another method on the existing test because {@code selfAttach} is
 * at-most-once per JVM: the option string is fixed for the life of the process, so proving both
 * settings requires two processes. {@code forkEvery=1} gives each test class its own JVM, which
 * is what makes that work.
 *
 * <p>Deliberately not an {@code @AsyncTest} method, for the same reason the sibling test is not:
 * driving the threads directly keeps a failure pointing at the weaver-to-detector chain rather
 * than at {@code ConcurrencyRunner}'s wiring.
 */
@Tag("e2e")
class FieldWeavingEndToEndTest {

    @BeforeAll
    static void attachWithFieldWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    @Test
    @DisplayName("count++ inside a method is reported when fields=true, the README's own example")
    void directFieldMutationIsReportedWhenFieldWeavingIsEnabled() throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        DirectFieldMutationBean bean = new DirectFieldMutationBean();

        CountDownLatch done = new CountDownLatch(2);
        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            for (int t = 0; t < 2; t++) {
                new Thread(() -> {
                    workerThreadIds.add(Thread.currentThread().threadId());
                    for (int i = 0; i < 200; i++) {
                        // Neither a getter nor a setter. The count++ inside compiles to a
                        // GETFIELD/PUTFIELD pair with no accessor call for Advice to bind to,
                        // which is precisely why this needs instruction-level weaving.
                        bean.increment();
                    }
                    done.countDown();
                }, "mutator-" + t).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertTrue(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("DirectFieldMutationBean.count")),
                "fields=true was supplied, so the GETFIELD and PUTFIELD inside increment() should "
                        + "both have been woven and DirectFieldMutationBean.count should be "
                        + "reported as read and written across two threads. Nothing matched, "
                        + "which means the field weaver is not reaching the detector: check that "
                        + "FieldAccessWeaver.visitor() is still applied in "
                        + "AsyncTestAgent.installUnguarded when options.fields() is set, that the "
                        + "owner is not caught by FieldAccessWeaver.shouldWeave, and that the "
                        + "emitted identifier still survives TelemetryBridge.fieldIdentifier "
                        + "unchanged. Findings were: " + report.unsafeFieldAccesses);
    }

    /**
     * Guards the property that makes the weaving safe to ship: instrumenting the instruction
     * stream must not change what the program computes.
     *
     * <p>A stack-neutral insertion is easy to get subtly wrong — one leftover operand and the
     * verifier rejects the class, or worse, the field is written with the wrong value. Running
     * the racy increment single-threaded gives an exact expected value, so any corruption of the
     * operand stack shows up as a wrong count rather than as a vague failure elsewhere.
     */
    @Test
    @DisplayName("weaving preserves program semantics: single-threaded increments still add up")
    void weavingDoesNotChangeTheComputedValue() {
        DirectFieldMutationBean bean = new DirectFieldMutationBean();
        for (int i = 0; i < 1000; i++) {
            bean.increment();
        }
        assertEquals(1000, bean.observedCount(),
                "increment() was woven with an observation call before each field instruction. "
                        + "The inserted sequence must push and consume exactly the operands it "
                        + "uses, leaving the stack as it found it. A wrong value here means the "
                        + "weaver corrupted the operand stack.");
    }

    /**
     * The reason monitor weaving exists: an agent-fed finding used to carry no lock model at all.
     *
     * <p>Both fields here are mutated the same way, from two threads, with {@code fields=true}
     * making both visible. The only difference is that one mutation happens inside
     * {@code synchronized (lock)}. Before the {@code MONITORENTER}/{@code MONITOREXIT} weaving
     * the agent could not tell them apart - a {@code count++} is a {@code GETFIELD} and a
     * {@code PUTFIELD} either way - so the guarded field was reported exactly as loudly as the
     * racing one, and a user who fixed their race by adding the lock saw the finding stay.
     *
     * <p>The unguarded field is the control, and it is asserted first for the same reason the
     * other negative test in this suite does it: without a finding somewhere in this run, the
     * absence of one for the guarded field would also be satisfied by an agent that never
     * attached or a bridge that never forwarded.
     */
    @Test
    @DisplayName("a field mutated under a synchronized block is not reported; its twin is")
    void monitorWeavingDistinguishesTheGuardedFieldFromTheRacingOne() throws Exception {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        GuardedFieldMutationBean bean = new GuardedFieldMutationBean();

        CountDownLatch done = new CountDownLatch(2);
        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            for (int t = 0; t < 2; t++) {
                new Thread(() -> {
                    workerThreadIds.add(Thread.currentThread().threadId());
                    for (int i = 0; i < 200; i++) {
                        bean.incrementGuarded();
                        bean.incrementUnguarded();
                    }
                    done.countDown();
                }, "mutator-" + t).start();
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }

        AtomicityValidator.AtomicityReport report = validator.analyzeAtomicity();

        assertTrue(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("GuardedFieldMutationBean.unguarded")),
                "The unguarded field was not reported, so this run proves nothing about the "
                        + "guarded one: the pipeline itself was not working. Findings were: "
                        + report.unsafeFieldAccesses);

        assertFalse(report.unsafeFieldAccesses.stream()
                        .anyMatch(f -> f.contains("GuardedFieldMutationBean.guarded")),
                "Every access to this field happened inside synchronized (lock), on both "
                        + "threads, so one monitor covered all of them and there is no race to "
                        + "report - the control above shows the pipeline was live while that "
                        + "held. A finding here means the lock never reached the detector: check "
                        + "that FieldAccessWeaver still weaves MONITORENTER and MONITOREXIT, "
                        + "that TelemetryRegistry.monitorEntered feeds HeldLocks, and that "
                        + "recordAccess still captures the fingerprint on the worker thread "
                        + "rather than leaving it to the drain thread, which holds nothing. "
                        + "Findings were: " + report.unsafeFieldAccesses);
    }

    /**
     * Monitor weaving is an insertion into the instruction stream too, and the same corruption
     * risk applies: {@code DUP} then a call that consumes it must leave the objectref the
     * {@code MONITORENTER} itself needs. Getting that wrong throws
     * {@code IllegalMonitorStateException} at the matching exit, or deadlocks on the wrong
     * object, so a single-threaded run that completes and adds up is the cheap proof.
     */
    @Test
    @DisplayName("monitor weaving preserves program semantics: the guarded counter still adds up")
    void monitorWeavingDoesNotChangeTheComputedValue() {
        GuardedFieldMutationBean bean = new GuardedFieldMutationBean();
        for (int i = 0; i < 1000; i++) {
            bean.incrementGuarded();
        }
        assertEquals(1000, bean.observedGuarded(),
                "incrementGuarded() was woven with a DUP and a call before both MONITORENTER and "
                        + "MONITOREXIT. The DUP must feed the call and leave the original "
                        + "objectref for the monitor instruction: a wrong value, or an "
                        + "IllegalMonitorStateException reaching this assertion, means the "
                        + "operand stack was corrupted.");
    }

    /**
     * The verifier's verdict on the {@code PUTSTATIC} sequence (#337).
     *
     * <p>Reaching a static store's value needs {@code DUP; LDC class; SWAP}, because a static
     * store has only the value on the stack and the class constant has to end up above it. That
     * is a different shape from every other write the weaver handles, and getting it wrong does
     * not produce a wrong answer, it produces a class the JVM refuses to load. So this asserts
     * the boring thing on purpose: the class loads, the method runs, and it computes what it
     * computed before instrumentation. A {@code VerifyError} on the first call would be the
     * failure, and it cannot be caught by inspecting bytecode - only by running it.
     */
    @Test
    @DisplayName("a static reference store survives the verifier and still stores the right value")
    void staticReferenceStoreIsWovenWithoutBreakingTheClass() {
        StaticLazyCacheBean.reset();

        StaticLazyCacheBean.Snapshot first = StaticLazyCacheBean.lookup(42, () -> { });
        StaticLazyCacheBean.Snapshot second = StaticLazyCacheBean.lookup(99, () -> { });

        assertEquals(42, first.size(),
                "The PUTSTATIC was woven with DUP, a class constant and SWAP ahead of it. The "
                        + "original value must be left untouched at the bottom of the stack for "
                        + "the store itself; a different size here means the swap put the class "
                        + "constant in the field.");
        assertSame(first, second,
                "The second call must hit the filled cache. A different instance means the store "
                        + "did not take effect, so the woven sequence consumed the value the "
                        + "PUTSTATIC was supposed to store.");
        assertSame(first, StaticLazyCacheBean.peek(),
                "The GETSTATIC is woven too, and must leave the field's value as it found it.");
    }

    /**
     * The silent half of the static pair (#337): a class-scope cache whose value goes quiet.
     *
     * <p>Both threads are held at the miss point until the other arrives, so the double miss is
     * deterministic rather than hoped for: both read {@code null}, both build a snapshot, and one
     * store is lost. That is exactly the access stream a lost update produces, and the field
     * alone cannot tell the two apart. What settles it is what was published, and until the
     * weaver reached a {@code PUTSTATIC}'s value nothing in the pipeline carried that.
     *
     * <p>The rounds are driven by hand, with a flush before each boundary, because the drain is
     * asynchronous: an event stamped after the epoch moved would land in the wrong round and the
     * convergence this asserts would be measured against a stream nobody produced.
     */
    @Test
    @DisplayName("a static single-check cache whose value goes quiet is not reported (#337)")
    void staticSingleCheckCacheWithQuiescentValueIsSilent() throws Exception {
        StaticLazyCacheBean.reset();
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();

        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            validator.markInvocationStart();
            CyclicBarrier bothMissed = new CyclicBarrier(2);
            onTwoWorkers(workerThreadIds,
                    () -> StaticLazyCacheBean.lookup(7, () -> awaitQuietly(bothMissed)));
            TelemetryRegistry.flush();

            for (int round = 0; round < 3; round++) {
                validator.markInvocationStart();
                onTwoWorkers(workerThreadIds, StaticLazyCacheBean::peek);
                TelemetryRegistry.flush();
            }
        }

        assertFalse(validator.analyzeAtomicity().hasIssues(),
                "The static field warmed in one round and was then read from two threads for "
                        + "three quiet rounds, and neither published snapshot was ever written "
                        + "again. That is the single-check cache the settle excuse exists for. "
                        + "If this fires, the PUTSTATIC's stored identity is not reaching "
                        + "AtomicityValidator: check FieldAccessWeaver.liftStaticReceiver still "
                        + "emits DUP/class/SWAP for a reference descriptor, and that "
                        + "carriesPublishedValueEvidence still lets the identity-0 group reach "
                        + "the excuses. Findings were: "
                        + validator.analyzeAtomicity().unsafeFieldAccesses);
    }

    /**
     * The loud half (#337): the same field shape, a payload that keeps working.
     *
     * <p>Identical access stream on the static field itself - same forced double miss, same lost
     * store, same quiet rounds afterwards - and the opposite verdict, because the job that lost
     * the race keeps writing its own state. This is the pair's whole point: a detector that
     * silences the cache above by silencing static fields in general would pass that test and
     * fail this one.
     */
    @Test
    @DisplayName("a static lazy-init whose value keeps mutating is still reported (#337)")
    void staticLazyInitWithLiveValueStillFires() throws Exception {
        StaticLazySubmitBean.reset();
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
        List<StaticLazySubmitBean.Job> created = Collections.synchronizedList(new ArrayList<>());

        try (TelemetryBridge bridge =
                     TelemetryBridge.activateWithFilter(validator, workerThreadIds::contains)) {
            validator.markInvocationStart();
            CyclicBarrier bothMissed = new CyclicBarrier(2);
            onTwoWorkers(workerThreadIds,
                    () -> created.add(StaticLazySubmitBean.submit(() -> awaitQuietly(bothMissed))));
            TelemetryRegistry.flush();

            for (int round = 0; round < 3; round++) {
                validator.markInvocationStart();
                onTwoWorkers(workerThreadIds, () -> {
                    StaticLazySubmitBean.peek();
                    // Every job that was created is still running, the winner and the loser
                    // alike. The loser is the one nothing will ever read.
                    for (StaticLazySubmitBean.Job job : List.copyOf(created)) {
                        job.advance();
                    }
                });
                TelemetryRegistry.flush();
            }
        }

        assertTrue(validator.analyzeAtomicity().hasIssues(),
                "The static field settled exactly as the quiescent cache does, so convergence "
                        + "alone would excuse it. The jobs it published keep writing their own "
                        + "state after the round that published them, which is a side effect and "
                        + "not a value, so the excuse must not be granted. Silence here means the "
                        + "static store's value evidence is being ignored, or that static fields "
                        + "are being excused wholesale.");
    }

    /** Runs {@code body} on two fresh threads, registering both as workers, and waits. */
    private static void onTwoWorkers(Set<Long> workerThreadIds, Runnable body)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(2);
        for (int t = 0; t < 2; t++) {
            new Thread(() -> {
                workerThreadIds.add(Thread.currentThread().threadId());
                try {
                    body.run();
                } finally {
                    done.countDown();
                }
            }, "static-worker-" + t).start();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
    }

    /**
     * Waits at the barrier, turning a failure into a test failure rather than a silent pass.
     *
     * <p>If this timed out the two threads would no longer miss together, the field would warm
     * from one thread, and the silent assertion above would pass for a reason that has nothing
     * to do with what it claims to measure.
     */
    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the twin miss", e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException(
                    "the two threads did not reach the miss point together, so the double miss "
                            + "this test depends on never happened", e);
        }
    }
}
