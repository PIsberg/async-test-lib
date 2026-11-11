package se.deversity.asynctest.agent;

import com.example.agentfixture.ArrayCounterBean;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that {@code arrays=true} makes a race on a plain array element visible, judged per element,
 * and that woven array code computes what it computed before (#1250).
 *
 * <p>Before #1250 no array instruction was woven, so {@code counter[0]++} on six threads drew
 * nothing while the same update on a field was reported: moving a counter from a field into an
 * array silenced the library without fixing anything.
 */
@Tag("e2e")
class ArrayElementWeavingTest {

    private static final int WORKERS = 4;
    private static final int BUMPS = 200;

    @BeforeAll
    static void attachWithArrayWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true,arrays=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** Runs {@code bump} {@link #BUMPS} times on each of {@link #WORKERS} threads at once. */
    private static List<String> findings(IntConsumer bump) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            CountDownLatch registered = new CountDownLatch(WORKERS);
            CountDownLatch done = new CountDownLatch(WORKERS);
            for (int w = 0; w < WORKERS; w++) {
                int worker = w;
                new Thread(() -> {
                    workers.add(Thread.currentThread().threadId());
                    registered.countDown();
                    try {
                        assertTrue(registered.await(10, TimeUnit.SECONDS));
                        for (int i = 0; i < BUMPS; i++) {
                            bump.accept(worker);
                        }
                    } catch (Throwable t) { // NOPMD - reported to the test thread below
                        failed.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                }, "array-worker").start();
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        if (failed.get() != null) {
            throw new AssertionError("a worker failed, so nothing was measured", failed.get());
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }

    private static boolean mentions(List<String> findings, String element) {
        return findings.stream().anyMatch(f -> f.contains(element));
    }

    @Test
    @DisplayName("a shared int[] slot bumped by four threads is a race")
    void aSharedIntSlotFires() throws InterruptedException {
        int[] counter = new int[1];
        List<String> findings = findings(w -> ArrayCounterBean.bumpShared(counter));
        assertTrue(mentions(findings, "int[0]"),
                "counter[0]++ on four threads loses updates exactly as a field would; a silent "
                        + "report means the IALOAD/IASTORE hooks no longer reach the validator. "
                        + "Findings: " + findings);
    }

    @Test
    @DisplayName("the same bump inside a lambda body, a synthetic method, is a race too")
    void aSharedSlotInALambdaFires() throws InterruptedException {
        int[] counter = new int[1];
        List<String> findings = findings(w -> ArrayCounterBean.bumpSharedInALambda(counter));
        assertTrue(mentions(findings, "int[0]"),
                "a lambda's body is a synthetic method; a weaver that skips those misses "
                        + "() -> counter[0]++ entirely. Findings: " + findings);
    }

    @Test
    @DisplayName("a shared long[] slot, a two-slot store, is a race too")
    void aSharedLongSlotFires() throws InterruptedException {
        long[] histogram = new long[1];
        List<String> findings = findings(w -> ArrayCounterBean.bumpSharedLong(histogram));
        assertTrue(mentions(findings, "long[0]"), "the LASTORE shuffle path: " + findings);
    }

    @Test
    @DisplayName("each thread on its own slot is judged per element and stays silent")
    void aSlotPerThreadIsSilent() throws InterruptedException {
        int[] slots = new int[WORKERS];
        List<String> findings = findings(w -> ArrayCounterBean.bumpOwnSlot(slots, w));
        assertFalse(mentions(findings, "int["),
                "no element is touched by two threads, so nothing races: " + findings);
    }

    @Test
    @DisplayName("the shared slot under one lock is silent")
    void aSharedSlotUnderALockIsSilent() throws InterruptedException {
        int[] counter = new int[1];
        Object lock = new Object();
        List<String> findings = findings(w -> ArrayCounterBean.bumpUnderLock(counter, lock));
        assertFalse(mentions(findings, "int["), "every bump holds the lock: " + findings);
    }

    @Test
    @DisplayName("woven array code computes what it computed before")
    void wovenArrayCodeIsTransparent() {
        int[] counter = new int[1];
        for (int i = 0; i < 10; i++) {
            ArrayCounterBean.bumpShared(counter);
        }
        assertEquals(10, counter[0]);

        long[] histogram = new long[1];
        ArrayCounterBean.bumpSharedLong(histogram);
        ArrayCounterBean.bumpSharedLong(histogram);
        assertEquals(4L, histogram[0]);

        assertEquals(1.5d * (0 + 1 + 2 + 3 + 4), ArrayCounterBean.fillAndSum(new double[5]));

        Object value = new Object();
        assertSame(value, ArrayCounterBean.storeAndLoad(new Object[3], value, 2));

        assertEquals(7 + 'x' + 300 + 2 + 1, ArrayCounterBean.readEveryKind(new byte[1],
                new char[1], new short[1], new float[1], new boolean[1]));
    }
}
