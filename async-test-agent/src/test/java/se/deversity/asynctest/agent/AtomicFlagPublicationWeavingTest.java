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
