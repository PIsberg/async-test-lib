package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.security.SecureRandom;
import java.util.Map;
import java.util.WeakHashMap;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.pause;
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
 * {@code examples/61-notify-without-monitor}, {@code examples/127-shared-secure-random},
 * {@code examples/96-weak-hashmap-shared}, {@code examples/54-jdbc-connection-shared}.
 */
class Phase13AdditionalCategoryDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "DaemonThreadHygieneDetector",
                    "NotifyWithoutMonitorDetector",
                    "SharedSecureRandomDetector",
                    "WeakHashMapSharedDetector");
            // JdbcConnectionSharedDetector stays reachability-only: this module declares no
            // driver, and feeding the detector a stand-in object would assert that it counts
            // identities rather than that a shared Connection is caught.
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.DAEMON_THREAD_HYGIENE})
    void daemonThreadHygiene() {
        reachable("daemonThreadHygieneDetector()", AsyncTestContext::daemonThreadHygieneDetector);

        // A non-daemon thread keeps the JVM alive after the suite finishes; the fixture
        // marks its own worker daemon and joins it.
        // The detector flags a non-daemon thread only while it is STILL ALIVE at analysis
        // time, which is correct: a non-daemon thread that has finished holds nothing open.
        // An earlier version of this fixture started a daemon thread and joined it, so the
        // detector rightly said nothing and the fixture asserted nothing.
        //
        // This one is genuinely non-daemon and deliberately not joined, so it is still running
        // when the round is analysed - exactly the hazard. It sleeps briefly and exits on its
        // own, so the worst case is that this fork waits a moment longer at exit rather than
        // hanging, which is the difference between demonstrating the hazard and causing it.
        Thread worker = new Thread(() -> pause(3_000), "fixture-non-daemon");
        worker.setDaemon(false);
        AsyncTestContext.daemonThreadHygieneDetector().recordThread(worker, "fixture-non-daemon");
        worker.start();
        spin(32);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.NOTIFY_WITHOUT_MONITOR})
    void notifyWithoutMonitor() {
        reachable("notifyWithoutMonitorDetector()",
            AsyncTestContext::notifyWithoutMonitorDetector);

        // notify() outside a synchronized block throws IllegalMonitorStateException — the
        // exact mistake this detector names, reproduced and contained.
        Object monitor = new Object();
        AsyncTestContext.notifyWithoutMonitorDetector()
                .recordNotifyAttempt(monitor, "fixture-monitor");
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
        AsyncTestContext.sharedSecureRandomDetector()
                .recordAccess(SHARED_SECURE_RANDOM, "shared-csprng", Thread.currentThread());
        SHARED_SECURE_RANDOM.nextInt(100);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.WEAK_HASH_MAP_SHARED})
    void weakHashMapShared() {
        reachable("weakHashMapSharedDetector()", AsyncTestContext::weakHashMapSharedDetector);

        // WeakHashMap is unsynchronised and its entries vanish on GC — sharing one across
        // threads corrupts it. The throw is the finding.
        AsyncTestContext.weakHashMapSharedDetector()
                .recordAccess(SHARED_WEAK_MAP, "shared-weak-map", Thread.currentThread());
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
