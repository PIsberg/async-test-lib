package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.security.SecureRandom;
import java.util.Map;
import java.util.WeakHashMap;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 13, additional-category group — {@code DAEMON_THREAD_HYGIENE} through
 * {@code JDBC_CONNECTION_SHARED}.
 *
 * <p>The JDBC fixture asserts reachability only: the consumer fixture declares no driver,
 * and a fixture that pulled one in would be testing the driver's connection handling rather
 * than the library's detector surface.
 *
 * <p>Corresponding examples: {@code examples/46-daemon-thread},
 * {@code examples/61-notify-without-monitor}, {@code examples/72-shared-random} (secure
 * variant), {@code examples/96-weak-hashmap-shared},
 * {@code examples/54-jdbc-connection-shared}.
 */
class Phase13AdditionalCategoryDetectorsFixtureTest {

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.DAEMON_THREAD_HYGIENE})
    void daemonThreadHygiene() {
        reachable("daemonThreadHygieneDetector()", AsyncTestContext::daemonThreadHygieneDetector);

        // A non-daemon thread keeps the JVM alive after the suite finishes; the fixture
        // marks its own worker daemon and joins it.
        Thread worker = new Thread(() -> spin(32), "fixture-daemon");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.NOTIFY_WITHOUT_MONITOR})
    void notifyWithoutMonitor() {
        reachable("notifyWithoutMonitorDetector()",
            AsyncTestContext::notifyWithoutMonitorDetector);

        // notify() outside a synchronized block throws IllegalMonitorStateException — the
        // exact mistake this detector names, reproduced and contained.
        Object monitor = new Object();
        try {
            monitor.notifyAll();
        } catch (IllegalMonitorStateException expected) {
            // The bug being demonstrated.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_SECURE_RANDOM})
    void sharedSecureRandom() {
        reachable("sharedSecureRandomDetector()", AsyncTestContext::sharedSecureRandomDetector);

        // SecureRandom is thread-safe but its internal lock serialises every caller — the
        // contention a shared instance creates is the finding.
        SHARED_SECURE_RANDOM.nextInt(100);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.WEAK_HASH_MAP_SHARED})
    void weakHashMapShared() {
        reachable("weakHashMapSharedDetector()", AsyncTestContext::weakHashMapSharedDetector);

        // WeakHashMap is unsynchronised and its entries vanish on GC — sharing one across
        // threads corrupts it. The throw is the finding.
        try {
            synchronized (SHARED_WEAK_MAP) {
                SHARED_WEAK_MAP.put(new Object(), "value");
                SHARED_WEAK_MAP.size();
            }
        } catch (RuntimeException expected) {
            // A shared WeakHashMap losing a race is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.JDBC_CONNECTION_SHARED})
    void jdbcConnectionShared() {
        reachable("jdbcConnectionSharedDetector()", AsyncTestContext::jdbcConnectionSharedDetector);
    }

    private static final SecureRandom SHARED_SECURE_RANDOM = new SecureRandom();

    private static final Map<Object, String> SHARED_WEAK_MAP = new WeakHashMap<>();
}
