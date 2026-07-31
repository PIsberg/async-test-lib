package se.deversity.asynctest.fixture.detectors;

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

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.EXECUTOR_SHUTDOWN})
    void executorShutdown() {
        reachable("executorShutdownDetector()", AsyncTestContext::executorShutdownDetector);

        // shutdown() without awaitTermination() is the half-done shutdown the detector
        // reports; the fixture does the complete version.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> spin(32));
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
        Map<MutableKey, String> map = new HashMap<>();
        MutableKey key = new MutableKey("before");
        map.put(key, "value");
        key.rename("after");
        map.get(key);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.NESTED_MONITOR_LOCKOUT})
    void nestedMonitorLockout() {
        reachable("nestedMonitorLockoutDetector()",
            AsyncTestContext::nestedMonitorLockoutDetector);

        // Waiting on the inner monitor while holding the outer one is the lockout; the
        // fixture uses a timed wait so no worker can be stranded.
        synchronized (OUTER) {
            synchronized (INNER) {
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
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.writeLock().lock();
        try {
            rw.readLock().lock();
        } finally {
            rw.writeLock().unlock();
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
        INHERITED.set("tenant-a");
        try {
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
