package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.RaceConditionDetector;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The static detector accessors on a thread the test body starts (#1388).
 *
 * <p>The documented way to record by hand from a child thread ({@code HappensBefore.fork}, start,
 * join, {@code HappensBefore.join}) threw {@code IllegalStateException} as soon as the child called
 * {@code AsyncTestContext.raceConditionDetector()}: the accessors read the worker's thread-local,
 * and a child has none. The child died and its write was never recorded.
 */
class ChildThreadAccessorTest {

    private static AsyncTestConfig.Builder config() {
        return AsyncTestConfig.builder().licenseMockMode(true)
                .includes(new DetectorType[] {DetectorType.RACE_CONDITIONS}).failOn(FailOn.NONE);
    }

    /** Fails if a body-started thread cannot reach the run's detector through the static accessor. */
    @Test
    void aThreadTheBodyStartsGetsTheRunsDetector() throws Throwable {
        AtomicReference<Object> workerSaw = new AtomicReference<>();
        AtomicReference<Object> childSaw = new AtomicReference<>();
        AtomicReference<Throwable> childThrew = new AtomicReference<>();

        AsyncTestRunner.run(config().threads(1).invocations(1).build(), () -> {
            workerSaw.set(AsyncTestContext.raceConditionDetector());
            Thread child = new Thread(() -> {
                try {
                    childSaw.set(AsyncTestContext.raceConditionDetector());
                } catch (Throwable t) {
                    childThrew.set(t);
                }
            });
            child.start();
            child.join();
        });

        assertNull(childThrew.get(), "the child is the run's, so the accessor must answer: " + childThrew.get());
        assertInstanceOf(RaceConditionDetector.class, childSaw.get());
        assertSame(workerSaw.get(), childSaw.get(), "and with the run's own detector, not another");
    }

    /** Fails if a thread outliving its run still reaches the run's detectors. */
    @Test
    void aThreadStartedAfterItsRunEndedIsRefused() throws Throwable {
        AtomicReference<Thread> late = new AtomicReference<>();
        AtomicReference<Throwable> lateThrew = new AtomicReference<>();

        AsyncTestRunner.run(config().threads(1).invocations(1).build(), () ->
                late.set(new Thread(() -> {
                    try {
                        AsyncTestContext.raceConditionDetector();
                    } catch (Throwable t) {
                        lateThrew.set(t);
                    }
                })));
        Thread thread = late.get();
        thread.start();
        thread.join();

        assertInstanceOf(IllegalStateException.class, lateThrew.get(),
                "the run was analysed before this thread ran, so recording into it would be lost");
    }

    /** The accessor outside any run keeps refusing, as before. */
    @Test
    void outsideAnyRunTheAccessorRefuses() {
        assertThrows(IllegalStateException.class, AsyncTestContext::raceConditionDetector);
    }
}
