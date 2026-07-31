package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SharedJsonMapperReconfigDetector;
import se.deversity.asynctest.example.service.JsonSerializationService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for JsonSerializationService.
 *
 * ========================================================================
 * DETECTOR: SharedJsonMapperReconfigDetector
 *           (DetectorType.SHARED_JSON_MAPPER_RECONFIG)
 * ========================================================================
 *
 * Sharing the mapper is the RECOMMENDED practice — ObjectMapper is
 * documented as thread-safe for readValue/writeValue and its serializer
 * caches only pay off when it is long-lived. This detector does not report
 * sharing.
 *
 * THE BUG:
 *   - reconfiguring the mapper after it is already visible to other threads.
 *     configure()/setDateFormat()/registerModule() mutate fields the
 *     serialization path reads unsynchronized, and drop caches other threads
 *     are using.
 *
 * THE FIX:
 *   - configure once at construction, then never mutate. Per-request
 *     settings go through a derived copy: ObjectMapper.copy(), or an
 *     ObjectWriter/ObjectReader view.
 *
 * The rule the detector encodes: a mutation is a violation when the mapper
 * is used by two or more threads, OR when it is mutated by a thread that is
 * not the one using it. Configuration before first use is always fine, and
 * so is a single thread reconfiguring a mapper only it uses.
 */
class JsonSerializationServiceTest {

    private static final Map<String, Object> BODY = Map.of("id", "42");

    private SharedJsonMapperReconfigDetector detector;

    @BeforeEach
    void setUp() {
        detector = new SharedJsonMapperReconfigDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: configure first, then share. The recommended lifecycle.
    // -----------------------------------------------------------------------

    @Test
    void configureBeforeSharing_isClean() throws Exception {
        var service = new JsonSerializationService();
        var mapper = service.sharedMapper();

        // Configuration happens before anybody serializes: no use recorded yet.
        detector.recordConfigMutation(mapper, "setDateFormat(ISO-8601)");

        Runnable worker = () -> {
            detector.recordUse(mapper);
            service.serialize(BODY);
        };
        Thread a = new Thread(worker, "request-a");
        Thread b = new Thread(worker, "request-b");
        a.start();
        b.start();
        a.join();
        b.join();

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "Sharing a mapper is recommended, not a finding:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: reconfigured while two threads are using it — flagged.
    // -----------------------------------------------------------------------

    @Test
    void reconfiguredWhileSharedAcrossThreads_isDetected() throws Exception {
        var service = new JsonSerializationService();
        var mapper = service.sharedMapper();

        Runnable worker = () -> {
            detector.recordUse(mapper);
            service.serialize(BODY);
        };
        Thread a = new Thread(worker, "request-a");
        Thread b = new Thread(worker, "request-b");
        a.start();
        b.start();
        a.join();
        b.join();

        // Now a per-request date format lands on the already-shared mapper.
        detector.recordConfigMutation(mapper, "setDateFormat(dd/MM/yyyy)");
        service.serializeWithDateFormat(BODY, "dd/MM/yyyy");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "Expected reconfiguration violation:\n" + report);
        String violation = report.violations.get(0);
        assertTrue(violation.contains("setDateFormat(dd/MM/yyyy)"), violation);
        assertTrue(violation.contains("2 thread"), violation);
        assertTrue(violation.contains("request-a"), violation);
    }

    // -----------------------------------------------------------------------
    // Part 3: one user thread, mutated by a different thread — also flagged.
    // A background refresh reconfiguring the mapper a request thread is on.
    // -----------------------------------------------------------------------

    @Test
    void reconfiguredByANonUsingThread_isDetected() throws Exception {
        var service = new JsonSerializationService();
        var mapper = service.sharedMapper();

        Thread user = new Thread(() -> detector.recordUse(mapper), "request-a");
        user.start();
        user.join();

        Thread configRefresh = new Thread(
                () -> detector.recordConfigMutation(mapper, "registerModule(JavaTimeModule)"),
                "config-refresh");
        configRefresh.start();
        configRefresh.join();

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "A mapper reconfigured from off-thread is still racing:\n" + report);
        assertTrue(report.violations.get(0).contains("config-refresh"), report.violations.get(0));
    }

    // -----------------------------------------------------------------------
    // Part 4: one thread, its own mapper, reconfigured by itself. Clean —
    // there is nobody to race with.
    // -----------------------------------------------------------------------

    @Test
    void singleThreadReconfiguringItsOwnMapper_isClean() {
        var service = new JsonSerializationService();
        var mapper = service.sharedMapper();

        detector.recordUse(mapper);
        detector.recordConfigMutation(mapper, "setDateFormat(dd/MM/yyyy)");

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                () -> "No other thread can observe this mutation:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 5: the damage — reconfiguration drops the serializer cache other
    // threads are mid-flight in, and changes what they emit.
    // -----------------------------------------------------------------------

    @Test
    void reconfiguration_dropsTheCacheAndChangesInFlightOutput() {
        var service = new JsonSerializationService();
        var mapper = service.sharedMapper();

        String before = service.serialize(Map.of("id", "42"));
        assertTrue(before.contains("\"_dateFormat\":\"yyyy-MM-dd\""), before);
        assertTrue(mapper.cacheSize() > 0, "serializing warmed the cache");

        String after = service.serializeWithDateFormat(Map.of("id", "42"), "dd/MM/yyyy");

        assertTrue(after.contains("\"_dateFormat\":\"dd/MM/yyyy\""), after);
        assertEquals(1, mapper.cacheSize(),
                "the reconfiguration cleared the cache every other thread was reading, and "
                        + "only this call's entry has been rebuilt");

        // The fix leaves the shared mapper untouched.
        String derived = service.serializeWithDateFormatSafely(Map.of("id", "42"), "MM-dd-yyyy");
        assertTrue(derived.contains("\"_dateFormat\":\"MM-dd-yyyy\""), derived);
        assertEquals("dd/MM/yyyy", mapper.dateFormat(),
                "a derived copy does not reconfigure the shared instance");
    }
}
