package se.deversity.asynctest.diagnostics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.TestFactory;

import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A counter every thread bumps must not hand its clock to every thread that bumps it.
 *
 * <p>An add or update releases before its store, so the counter's clock collects an entry for
 * every thread that ever bumped it, up to {@link HappensBefore#MAX_ENTRIES}. When the value the
 * bump read also acquired, each bumping thread took that whole clock, and every access it recorded
 * afterwards carried a clock of thousands of entries. A static counter bumped once per body
 * execution, which the corpus recorder has, filled a 2 GB heap within seconds that way (#1317).
 */
class AtomicCounterClockTest {

    private static final int EARLIER_BUMPERS = 200;

    @FunctionalInterface
    private interface Bump {
        void on(AtomicInteger count, AtomicLong total);
    }

    private static Stream<Named<Bump>> bumps() {
        return Stream.of(
                Named.of("AtomicInteger.incrementAndGet",
                        (count, total) -> TelemetryRegistry.incrementAndGetAtomicInteger(count)),
                Named.of("AtomicInteger.getAndIncrement",
                        (count, total) -> TelemetryRegistry.getAndIncrementAtomicInteger(count)),
                Named.of("AtomicInteger.addAndGet",
                        (count, total) -> TelemetryRegistry.addAndGetAtomicInteger(count, 2)),
                Named.of("AtomicInteger.getAndAdd",
                        (count, total) -> TelemetryRegistry.getAndAddAtomicInteger(count, 2)),
                Named.of("AtomicInteger.decrementAndGet",
                        (count, total) -> TelemetryRegistry.decrementAndGetAtomicInteger(count)),
                Named.of("AtomicInteger.getAndUpdate",
                        (count, total) -> TelemetryRegistry.getAndUpdateAtomicInteger(count, c -> c + 3)),
                Named.of("AtomicInteger.updateAndGet",
                        (count, total) -> TelemetryRegistry.updateAndGetAtomicInteger(count, c -> c + 3)),
                Named.of("AtomicInteger.getAndAccumulate",
                        (count, total) -> TelemetryRegistry.getAndAccumulateAtomicInteger(count, 3, Integer::sum)),
                Named.of("AtomicInteger.accumulateAndGet",
                        (count, total) -> TelemetryRegistry.accumulateAndGetAtomicInteger(count, 3, Integer::sum)),
                Named.of("AtomicLong.incrementAndGet",
                        (count, total) -> TelemetryRegistry.incrementAndGetAtomicLong(total)),
                Named.of("AtomicLong.getAndAdd",
                        (count, total) -> TelemetryRegistry.getAndAddAtomicLong(total, 2L)));
    }

    @TestFactory
    @DisplayName("a bump does not take the clock of every thread that bumped before it")
    Stream<DynamicTest> aBumpKeepsTheBumpersClockSmall() {
        return DynamicTest.stream(bumps(), AtomicCounterClockTest::assertTheLastBumperKnowsLittle);
    }

    private static void assertTheLastBumperKnowsLittle(Bump bump) throws InterruptedException {
        AtomicInteger count = new AtomicInteger(10);
        AtomicLong total = new AtomicLong(10L);
        for (int i = 0; i < EARLIER_BUMPERS; i++) {
            Thread earlier = new Thread(() -> bump.on(count, total));
            earlier.start();
            earlier.join();
        }

        AtomicReference<HappensBefore.Stamp> after = new AtomicReference<>();
        Thread last = new Thread(() -> {
            bump.on(count, total);
            after.set(HappensBefore.current());
        });
        last.start();
        last.join();

        int size = after.get().size();
        assertTrue(size < EARLIER_BUMPERS / 10,
                "the last bumper's clock knows " + size + " threads after " + EARLIER_BUMPERS
                        + " others bumped the counter; it should know little beyond itself");
    }
}
