package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 8, lifecycle &amp; structural-correctness group — {@code EXECUTOR_SHUTDOWN} through
 * {@code INHERITABLE_THREAD_LOCAL}.
 *
 * <p>Corresponding examples: {@code examples/49-executor-shutdown},
 * {@code examples/59-mutable-map-key}, {@code examples/60-nested-monitor-lockout},
 * {@code examples/56-lock-downgrade}, {@code examples/53-inheritable-thread-local}.
 */
class Phase08LifecycleDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "ExecutorShutdownDetector",
                    "MutableMapKeyDetector",
                    "NestedMonitorLockoutDetector",
                    "InheritableThreadLocalMisuseDetector");
            // lockDowngrade() below performs a proper write-to-read downgrade, which is the
            // correct idiom. What LockDowngradeDetector actually reports is the opposite - a
            // thread holding a read lock that then asks for the write lock - so silence here
            // is the behaviour worth pinning. The firing direction belongs to the upgrade
            // fixture in Phase15, which is what LockUpgradeDeadlockDetector watches.
            assertNoneReported(findings, "LockDowngradeDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.EXECUTOR_SHUTDOWN})
    void executorShutdown() {
        reachable("executorShutdownDetector()", AsyncTestContext::executorShutdownDetector);

        // shutdown() without awaitTermination() is the half-done shutdown the detector
        // reports; the fixture does the complete version.
        // An executor shut down without awaitTermination abandons whatever is still running.
        // The fixture waits properly below, so the shutdown recorded here is the one without
        // the wait - really abandoning tasks would leak them into the next round.
        var shutdownDetector = AsyncTestContext.executorShutdownDetector();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        shutdownDetector.recordExecutorCreated(pool, "lifecycle-pool");
        shutdownDetector.recordTaskSubmitted(pool);
        pool.execute(() -> spin(32));
        shutdownDetector.recordShutdownCalled(pool, false);
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.MUTABLE_MAP_KEY})
    void mutableMapKey() {
        reachable("mutableMapKeyDetector()", AsyncTestContext::mutableMapKeyDetector);

        // A key whose hashCode changes after insertion becomes unreachable.
        // A key mutated after insertion lands in the wrong bucket: the entry is still in the
        // map but can never be found again. Shared across the round so both workers meet it.
        var keyDetector = AsyncTestContext.mutableMapKeyDetector();
        MutableKey key = new MutableKey("before");
        synchronized (SHARED_KEY_MAP) {
            SHARED_KEY_MAP.put(key, "value");
            keyDetector.recordKeyInserted(SHARED_KEY_MAP, key, "shared-key-map");
            key.rename("after");
            keyDetector.recordKeyMutation(key, "name", "before", "after");
            SHARED_KEY_MAP.get(key);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.NESTED_MONITOR_LOCKOUT})
    void nestedMonitorLockout() {
        reachable("nestedMonitorLockoutDetector()",
            AsyncTestContext::nestedMonitorLockoutDetector);

        // Waiting on the inner monitor while holding the outer one is the lockout; the
        // fixture uses a timed wait so no worker can be stranded.
        // wait() releases only the monitor it is called on, so the outer one stays held and
        // nobody can get in to do the notifying - the nested monitor lockout.
        var lockoutDetector = AsyncTestContext.nestedMonitorLockoutDetector();
        synchronized (OUTER) {
            lockoutDetector.recordMonitorAcquired(OUTER);
            synchronized (INNER) {
                lockoutDetector.recordMonitorAcquired(INNER);
                lockoutDetector.recordBlockingOperationAttempted("Object.wait()");
                try {
                    INNER.wait(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LOCK_DOWNGRADE})
    void lockDowngrade() {
        reachable("lockDowngradeDetector()", AsyncTestContext::lockDowngradeDetector);

        // Correct downgrade: acquire read while still holding write, then release write.
        // Downgrading write to read is legal and useful; the detector tracks the acquire and
        // release ordering so it can tell a downgrade from an upgrade.
        var downgradeDetector = AsyncTestContext.lockDowngradeDetector();
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.writeLock().lock();
        downgradeDetector.recordWriteLockAcquired(rw, "downgrade-lock");
        try {
            rw.readLock().lock();
            downgradeDetector.recordReadLockAcquired(rw, "downgrade-lock");
        } finally {
            rw.writeLock().unlock();
            downgradeDetector.recordWriteLockReleased(rw, "downgrade-lock");
        }
        try {
            spin(32);
        } finally {
            rw.readLock().unlock();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.INHERITABLE_THREAD_LOCAL})
    void inheritableThreadLocal() {
        reachable("inheritableThreadLocalMisuseDetector()",
            AsyncTestContext::inheritableThreadLocalMisuseDetector);

        // Inherited by children — including pooled ones that outlive the parent request.
        // An InheritableThreadLocal is copied into every thread created from this one, so a
        // pooled thread carries one tenant context into the next tenant task.
        var itlDetector = AsyncTestContext.inheritableThreadLocalMisuseDetector();
        itlDetector.registerPoolThread(Thread.currentThread());
        itlDetector.recordSet(INHERITED, "tenant", "tenant-a");
        INHERITED.set("tenant-a");
        try {
            itlDetector.recordGet(INHERITED, "tenant");
            Thread child = new Thread(() -> spin(32));
            child.setDaemon(true);
            child.start();
            child.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            INHERITED.remove();
        }
    }

    private static final Map<MutableKey, String> SHARED_KEY_MAP = new HashMap<>();

    private static final Object OUTER = new Object();

    private static final Object INNER = new Object();

    private static final InheritableThreadLocal<String> INHERITED =
        new InheritableThreadLocal<>();

    /** Mutable key: {@code hashCode()} changes after the map has bucketed it. */
    private static final class MutableKey {
        private String name;

        MutableKey(String name) {
            this.name = name;
        }

        void rename(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MutableKey key && Objects.equals(name, key.name);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(name);
        }
    }
}
