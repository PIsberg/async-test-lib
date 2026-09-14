package se.deversity.asynctest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import se.deversity.asynctest.diagnostics.TrustTier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reader-heavy read-write lock is a performance note, and {@code minTrust} treats it as one
 * (#569).
 *
 * <p>{@code ReadWriteLockMonitor} fires on a read-to-write count ratio above ten, never looks at
 * the lock, and its own report calls the lock "behaving correctly". That is the definition of
 * {@link TrustTier#ADVISORY}: firing says nothing about correctness. Classified PROMPT, it failed a
 * build gated on {@code minTrust = PROMPT}, which is the floor a team picks to mean "findings that
 * may be bugs".
 */
@E2E
class ReadWriteLockFairnessTierTest {

    @Test
    @DisplayName("a reader-heavy lock does not fail a build that gates on findings that may be bugs")
    void sparedByThePromptFloor() {
        run(ReaderHeavyUnderPromptFloorDummy.class)
                .assertStatistics(s -> s.started(1).succeeded(1).failed(0));
    }

    @Test
    @DisplayName("with the default floor the same finding still fails a build, so it is still reported")
    void stillFailsWithTheDefaultFloor() {
        Events tests = run(ReaderHeavyDummy.class);
        tests.assertStatistics(s -> s.started(1).failed(1));
        List<String> messages = tests.failed().stream()
                .map(event -> event.getRequiredPayload(TestExecutionResult.class))
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .filter(Objects::nonNull)
                .toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("at or above failOn=")
                        && m.contains("ReadWriteLockMonitor")),
                "the failure must be the failOn gate naming the detector: " + messages);
    }

    private static Events run(Class<?> fixture) {
        return EngineTestKit.engine("junit-jupiter")
                .selectors(DiscoverySelectors.selectClass(fixture))
                .execute()
                .testEvents();
    }

    private static void readerHeavy(ReentrantReadWriteLock lock) {
        var monitor = AsyncTestContext.readWriteLockMonitor();
        monitor.registerLock(lock, "reader-heavy");
        for (int i = 0; i < 11; i++) {
            monitor.recordReadLockAcquired(lock, 0L);
            monitor.recordReadLockReleased(lock);
        }
        monitor.recordWriteLockAcquired(lock, 0L);
        monitor.recordWriteLockReleased(lock);
    }

    public static class ReaderHeavyDummy {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.LOW,
                   detectAll = false, monitorReadWriteLockFairness = true)
        void readerHeavy() {
            ReadWriteLockFairnessTierTest.readerHeavy(lock);
        }
    }

    public static class ReaderHeavyUnderPromptFloorDummy {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        @AsyncTest(threads = 2, invocations = 2, failOn = FailOn.LOW, minTrust = TrustTier.PROMPT,
                   detectAll = false, monitorReadWriteLockFairness = true)
        void readerHeavy() {
            ReadWriteLockFairnessTierTest.readerHeavy(lock);
        }
    }
}
