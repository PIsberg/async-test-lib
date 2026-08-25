package com.example.corpus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.collections4.map.LRUMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.util.ConcurrentReferenceHashMap;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;

import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * The recording lane: the same libraries, with test bodies that cooperate.
 *
 * <p><strong>Why this is a separate measurement.</strong> The corpus's headline claim is that no
 * line of the subject and no line of the test cooperates with a detector, and that is exactly
 * what makes 137 of the 142 detectors unreachable there: they are fed by the {@code record*} and
 * {@code register*} calls the corpus refuses to make. The result was a report in which "no false
 * positive from detector X" and "X never ran" were the same row for 96% of the roster. This lane
 * fixes that for a named handful by doing what a user following {@code AsyncTestContext} does,
 * and its numbers must never be merged into the unmodified lanes'.
 *
 * <p><strong>Why its assertions are stronger.</strong> {@link CorpusEvalTest} gates only at the
 * group level, because whether one particular race is observed in one particular run is
 * probabilistic. Here it is not. A recording-fed detector's verdict is a function of the calls
 * the body made, so each subject states {@code MUST_FIRE} or {@code MUST_STAY_SILENT} and
 * {@link CorpusGates} holds it to that. Every subject is half of a pair: a MUST_FIRE row alone
 * would pass for a detector that fires on everything, and a MUST_STAY_SILENT row alone would
 * pass for one that was never wired up.
 *
 * <p>The receivers are still unmodified third-party classes. What changed is the test body.
 */
@ExtendWith(SubjectTracking.class)
class CorpusRecordingLaneTest {

    static final int THREADS = 6;
    static final int INVOCATIONS = 40;

    private static final Map<String, String> PAYLOAD = Map.of("key", "value");

    /** Configured once and never again: the pattern Jackson's own javadoc asks for. */
    private final ObjectMapper configuredMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Reconfigured while other threads write through it: the exception that javadoc names. */
    private final ObjectMapper reconfiguredMapper = new ObjectMapper();

    /** Documented not synchronised, used as a cache the way the detector's javadoc shows. */
    private final LRUMap<String, String> lruMap = new LRUMap<>(64);

    /** Documented thread-safe, recorded identically to the row above. */
    private final Cache<String, String> caffeineCache = Caffeine.newBuilder().maximumSize(64).build();

    /** A second Caffeine instance, so the computeIfAbsent row cannot borrow the other's state. */
    private final Cache<String, String> caffeineAtomicCache =
            Caffeine.newBuilder().maximumSize(64).build();

    /** Documented thread-safe, and used with a check-then-act anyway: the defect is the usage. */
    private final ConcurrentReferenceHashMap<String, String> referenceMap =
            new ConcurrentReferenceHashMap<>();

    @BeforeAll
    static void installRecorder() {
        CorpusRecorder.install();
    }

    @AfterAll
    static void reportAndGate() {
        CorpusRecorder.uninstall();
        CorpusLane lane = CorpusLane.current();
        Path report = CorpusReport.writeRecording(
                CorpusRecorder.findings(), THREADS, INVOCATIONS, lane);
        System.out.println("Corpus recording-lane report written to " + report.toAbsolutePath());
        System.out.println(CorpusReport.recordingSummary(CorpusRecorder.findings(), lane));
        CorpusGates.checkRecordingLane(CorpusRecorder.findings(), lane);
    }

    // --- SharedJsonMapperReconfig ------------------------------------------------------------

    /**
     * Records a use, then a config mutation, from every thread.
     *
     * <p>The detector fires when a mutation is recorded after the instance has been used by two
     * or more threads, or from a thread that never used it. Six threads on a barrier all record
     * a use in the first round, so from the second round on the precondition is met by
     * construction rather than by luck - which is why the gate can require a finding.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_objectMapper_reconfigureWhileWriting() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedJsonMapperReconfigDetector().recordUse(reconfiguredMapper);
        AsyncTestContext.sharedJsonMapperReconfigDetector()
                .recordConfigMutation(reconfiguredMapper, "setDateFormat");
        try {
            reconfiguredMapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd"));
            reconfiguredMapper.writeValueAsString(PAYLOAD);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records uses and never a mutation: config-then-use, which is the documented safe pattern. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_objectMapper_configuredThenShared() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.sharedJsonMapperReconfigDetector().recordUse(configuredMapper);
        try {
            configuredMapper.writeValueAsString(PAYLOAD);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- CacheConcurrency --------------------------------------------------------------------

    /**
     * A documented-unsafe map used as a cache, recorded the way the detector's javadoc shows.
     *
     * <p>{@code registerCache} is called once for the whole run rather than per worker: a
     * per-thread register scatters one shared subject across duplicate entries and the
     * cross-thread contention the detector measures becomes invisible exactly when it is real.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_lruMap_getAndPut() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.cacheConcurrencyDetector().recordPut(lruMap, "lru-cache", "key", "value");
        lruMap.put("key", "value");
        AsyncTestContext.cacheConcurrencyDetector().recordGet(lruMap, "lru-cache", "key");
        lruMap.get("key");
    }

    /**
     * The same recorded calls against a receiver whose javadoc promises a thread-safe map.
     *
     * <p>The detector is handed identical evidence for both rows and has only the receiver to
     * separate them, which is the whole test: Caffeine's view implements {@code ConcurrentMap}
     * and keeps the contract, so a finding here is noise on correct code.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_caffeineAsMap_getAndPut() {
        CorpusRecorder.countBodyExecution();
        ConcurrentMap<String, String> view = caffeineCache.asMap();
        AsyncTestContext.cacheConcurrencyDetector().recordPut(view, "caffeine-cache", "key", "value");
        view.put("key", "value");
        AsyncTestContext.cacheConcurrencyDetector().recordGet(view, "caffeine-cache", "key");
        view.get("key");
    }

    // --- ConcurrentMapCheckThenAct -----------------------------------------------------------

    /**
     * Get-then-put on one key from six threads, on a map documented as thread-safe.
     *
     * <p>Each call is atomic and the pair is not, so this is the lost update the detector
     * reports. The row exists to make the lane's ground truth explicit: here the class is right
     * and the caller is wrong, which is the opposite of what the unmodified lanes measure.
     */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_concurrentReferenceHashMap_checkThenAct() {
        CorpusRecorder.countBodyExecution();
        AsyncTestContext.nonAtomicConcurrentMapUpdateDetector()
                .recordCheckThenAct(referenceMap, "key", "get-then-put", Thread.currentThread());
        String existing = referenceMap.get("key");
        referenceMap.put("key", existing == null ? "first" : existing + "+");
    }

    /** The atomic primitive that fixes the row above, so there is no check-then-act to record. */
    @AsyncTest(threads = THREADS, invocations = INVOCATIONS, timeoutMs = 20_000)
    void recorded_caffeineAsMap_computeIfAbsent() {
        CorpusRecorder.countBodyExecution();
        caffeineAtomicCache.asMap().computeIfAbsent("key", key -> "computed");
    }
}
