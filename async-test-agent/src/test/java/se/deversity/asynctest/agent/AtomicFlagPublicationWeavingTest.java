package se.deversity.asynctest.agent;

import com.example.agentfixture.AtomicFlagBean;
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
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A primitive atomic used as a completion flag is a happens-before edge (#1315), end to end:
 * weaver, hooks, happens-before model and {@code AtomicityValidator}.
 *
 * <p>A store into an {@code AtomicBoolean}, {@code AtomicInteger} or {@code AtomicLong} is a
 * volatile write and a {@code get} a volatile read. Before #1315 only an {@code AtomicReference}
 * fed the model, so a field written before {@code done.set(true)} and read after
 * {@code done.get()} returned {@code true} read as an unordered publication.
 *
 * <p>Own class: {@code selfAttach} is at most once per JVM and this needs {@code fields=true}.
 */
@Tag("e2e")
class AtomicFlagPublicationWeavingTest {

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
    @DisplayName("AtomicBoolean.set then a get that saw it orders the reader's update")
    void aBooleanSetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanSet, AtomicFlagBean::bumpAfterBoolean);
    }

    @Test
    @DisplayName("a won AtomicBoolean.compareAndSet publishes")
    void aBooleanCompareAndSetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanCompareAndSet, AtomicFlagBean::bumpAfterBoolean);
    }

    @Test
    @DisplayName("AtomicBoolean.getAndSet publishes")
    void aBooleanGetAndSetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanGetAndSet, AtomicFlagBean::bumpAfterBoolean);
    }

    @Test
    @DisplayName("AtomicInteger.set publishes")
    void anIntSetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntSet, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("AtomicInteger.incrementAndGet publishes, the completion counter")
    void anIntIncrementPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntIncrement, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("a won AtomicInteger.compareAndSet publishes")
    void anIntCompareAndSetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntCompareAndSet, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("AtomicLong.set publishes")
    void aLongSetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughLongSet, AtomicFlagBean::bumpAfterTotal);
    }

    @Test
    @DisplayName("AtomicLong.addAndGet publishes")
    void aLongAddPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughLongAdd, AtomicFlagBean::bumpAfterTotal);
    }

    @Test
    @DisplayName("AtomicBoolean.lazySet publishes (#1317)")
    void aBooleanLazySetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanLazySet, AtomicFlagBean::bumpAfterBoolean);
    }

    @Test
    @DisplayName("AtomicBoolean.setRelease publishes (#1317)")
    void aBooleanSetReleasePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanSetRelease, AtomicFlagBean::bumpAfterBoolean);
    }

    @Test
    @DisplayName("a won AtomicBoolean.compareAndExchange publishes (#1317)")
    void aBooleanCompareAndExchangePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanCompareAndExchange, AtomicFlagBean::bumpAfterBoolean);
    }

    @Test
    @DisplayName("a won AtomicBoolean.weakCompareAndSetVolatile publishes (#1317)")
    void aBooleanWeakCasVolatilePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanWeakCompareAndSetVolatile, AtomicFlagBean::bumpAfterBoolean);
    }

    @Test
    @DisplayName("a won AtomicBoolean.weakCompareAndSetRelease publishes (#1317)")
    void aBooleanWeakCasReleasePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanWeakCompareAndSetRelease, AtomicFlagBean::bumpAfterBoolean);
    }

    @Test
    @DisplayName("AtomicInteger.lazySet publishes (#1317)")
    void anIntLazySetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntLazySet, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("AtomicInteger.setRelease publishes (#1317)")
    void anIntSetReleasePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntSetRelease, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("a won AtomicInteger.compareAndExchange publishes (#1317)")
    void anIntCompareAndExchangePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntCompareAndExchange, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("a won AtomicInteger.compareAndExchangeRelease publishes (#1317)")
    void anIntCompareAndExchangeReleasePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntCompareAndExchangeRelease, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("a won AtomicInteger.weakCompareAndSetVolatile publishes (#1317)")
    void anIntWeakCasVolatilePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntWeakCompareAndSetVolatile, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("a won AtomicInteger.weakCompareAndSetRelease publishes (#1317)")
    void anIntWeakCasReleasePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntWeakCompareAndSetRelease, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("AtomicInteger.updateAndGet publishes (#1317)")
    void anIntUpdateAndGetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntUpdateAndGet, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("AtomicInteger.getAndUpdate publishes (#1317)")
    void anIntGetAndUpdatePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntGetAndUpdate, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("AtomicInteger.accumulateAndGet publishes (#1317)")
    void anIntAccumulateAndGetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntAccumulateAndGet, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("AtomicInteger.getAndAccumulate publishes (#1317)")
    void anIntGetAndAccumulatePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntGetAndAccumulate, AtomicFlagBean::bumpAfterCount);
    }

    @Test
    @DisplayName("AtomicLong.lazySet publishes (#1317)")
    void aLongLazySetPublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughLongLazySet, AtomicFlagBean::bumpAfterTotal);
    }

    @Test
    @DisplayName("AtomicLong.setRelease publishes (#1317)")
    void aLongSetReleasePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughLongSetRelease, AtomicFlagBean::bumpAfterTotal);
    }

    @Test
    @DisplayName("a won AtomicLong.weakCompareAndSetVolatile publishes (#1317)")
    void aLongWeakCasVolatilePublishes() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughLongWeakCompareAndSetVolatile, AtomicFlagBean::bumpAfterTotal);
    }

    @Test
    @DisplayName("the previous value of an AtomicBoolean.getAndSet acquires (#1317)")
    void aBooleanGetAndSetAcquires() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughBooleanSet, AtomicFlagBean::bumpAfterBooleanGetAndSet);
    }

    @Test
    @DisplayName("the previous value of an AtomicInteger.getAndSet acquires (#1317)")
    void anIntGetAndSetAcquires() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntSet, AtomicFlagBean::bumpAfterCountGetAndSet);
    }

    @Test
    @DisplayName("the witness of an AtomicInteger.compareAndExchange acquires (#1317)")
    void anIntExchangeWitnessAcquires() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntSet, AtomicFlagBean::bumpAfterCountExchange);
    }

    @Test
    @DisplayName("the value an AtomicInteger.getAndUpdate returns acquires (#1317)")
    void anIntUpdateResultAcquires() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntSet, AtomicFlagBean::bumpAfterCountUpdate);
    }

    @Test
    @DisplayName("the value an AtomicInteger.getAndAdd returns acquires (#1317)")
    void anIntAddResultAcquires() throws InterruptedException {
        assertOrdered(AtomicFlagBean::publishThroughIntSet, AtomicFlagBean::bumpAfterCountAdd);
    }

    @Test
    @DisplayName("setPlain orders nothing: a plain store is no release (#1317)")
    void aPlainStoreStillRaces() throws InterruptedException {
        assertRaced(AtomicFlagBean::publishThroughIntSetPlain, AtomicFlagBean::bumpAfterCount,
                "setPlain has no release semantics, so a get that saw it orders nothing");
    }

    @Test
    @DisplayName("an update with no read of the flag keeps its finding")
    void anUpdateThatReadNoFlagIsStillARace() throws InterruptedException {
        assertRaced(AtomicFlagBean::publishThroughBooleanSet, AtomicFlagBean::bumpWithoutAFlag,
                "no read of the atomic orders this update after the writer's");
    }

    @Test
    @DisplayName("a flag set before the update publishes nothing of the update")
    void aFlagSetBeforeTheUpdatePublishesNothingOfIt() throws InterruptedException {
        assertRaced(AtomicFlagBean::setTheFlagBeforeTheUpdate, AtomicFlagBean::bumpAfterBoolean,
                "the writer set the flag and then updated data, so the reader's read of the flag "
                        + "orders it after the set and not after the update");
    }

    @Test
    @DisplayName("a read that returned a value nobody stored acquires nothing")
    void aReadOfTheInitialValueAcquiresNothing() throws InterruptedException {
        assertRaced(AtomicFlagBean::publishThroughIntSet, AtomicFlagBean::bumpAfterSeeingTheFlagUnset,
                "the writer stored into count; the reader read done, which still held its "
                        + "initial value, so no store orders its update");
    }

    private static void assertOrdered(Consumer<AtomicFlagBean> writer, ToIntFunction<AtomicFlagBean> reader)
            throws InterruptedException {
        AtomicFlagBean bean = new AtomicFlagBean();
        int[] read = new int[1];
        List<String> findings = findings(() -> writer.accept(bean), () -> read[0] = reader.applyAsInt(bean));

        assertEquals(2, read[0], "the reader saw the flag and updated the data, or nothing was measured");
        assertFalse(findings.stream().anyMatch(f -> f.contains(".data")),
                "the writer updated data and then stored into the atomic; the reader read the "
                        + "stored value first, so its update is ordered after the writer's. "
                        + "Findings were: " + findings);
    }

    private static void assertRaced(Consumer<AtomicFlagBean> writer, ToIntFunction<AtomicFlagBean> reader,
                                    String why) throws InterruptedException {
        AtomicFlagBean bean = new AtomicFlagBean();
        int[] read = new int[1];
        List<String> findings = findings(() -> writer.accept(bean), () -> read[0] = reader.applyAsInt(bean));

        assertEquals(2, read[0], "the reader updated the data after the writer, or nothing was measured");
        assertTrue(findings.stream().anyMatch(f -> f.contains(".data")), why + ". Findings were: " + findings);
    }

    /** Runs the writer to completion, then the reader, on two workers the model does not order. */
    private static List<String> findings(Runnable writer, Runnable reader) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    writer.run();
                } finally {
                    written.countDown();
                    done.countDown();
                }
            }, "flag-writer").start();
            new Thread(() -> {
                workers.add(Thread.currentThread().threadId());
                try {
                    assertTrue(written.await(10, TimeUnit.SECONDS), "the writer never finished");
                    reader.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "flag-reader").start();
            assertTrue(done.await(10, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }
}
