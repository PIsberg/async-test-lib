package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 11, additional-shared-types group - {@code SHARED_MATCHER} through
 * {@code SHARED_MESSAGE_DIGEST}.
 *
 * <p>{@code Pattern} is thread-safe, {@code Matcher} is not; {@code DecimalFormat} and
 * {@code MessageDigest} carry mutable state across calls. Each fixture shares the unsafe one on
 * purpose, records the access through the detector's public API, and the class asserts in
 * {@code @AfterAll} that the finding came back out through {@link AsyncFindings}.
 *
 * <p>Note that {@code SharedMessageDigestDetector} carries the {@code Thread.holdsLock}
 * guard-on-self probe, so its fixture must not wrap the recording in
 * {@code synchronized (SHARED_DIGEST)}: that is the idiom the detector correctly recognises as
 * guarded, and the fixture would then be asserting a finding that should not exist.
 *
 * <p>Corresponding examples: {@code examples/10-shared-non-thread-safe-types}, which covers
 * the matcher, decimal-format and message-digest cases;
 * {@code examples/97-weak-reference-race}; {@code examples/76-stateful-lambda}.
 */
class Phase11SharedTypeDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "SharedMatcherDetector",
                    "SharedDecimalFormatDetector",
                    "WeakReferenceRaceDetector",
                    "StatefulLambdaDetector",
                    "SharedMessageDigestDetector");
        } finally {
            findings.close();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_MATCHER})
    void sharedMatcher() {
        reachable("sharedMatcherDetector()", AsyncTestContext::sharedMatcherDetector);

        AsyncTestContext.sharedMatcherDetector()
                .recordAccess(SHARED_MATCHER, "word-matcher", Thread.currentThread());
        try {
            SHARED_MATCHER.reset("abc123");
            SHARED_MATCHER.find();
        } catch (RuntimeException expected) {
            // A Matcher shared across threads is the hazard being demonstrated.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_DECIMAL_FORMAT})
    void sharedDecimalFormat() {
        reachable("sharedDecimalFormatDetector()", AsyncTestContext::sharedDecimalFormatDetector);

        AsyncTestContext.sharedDecimalFormatDetector()
                .recordAccess(SHARED_DECIMAL_FORMAT, "amount", Thread.currentThread());
        try {
            SHARED_DECIMAL_FORMAT.format(1234.5678d);
        } catch (RuntimeException expected) {
            // DecimalFormat inherits SimpleDateFormat's mutable-state problem.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.WEAK_REFERENCE_RACE})
    void weakReferenceRace() {
        reachable("weakReferenceRaceDetector()", AsyncTestContext::weakReferenceRaceDetector);

        // get() can return null between the null check and the use - the race. The referent is
        // held strongly so the fixture is deterministic, which means the clearing half cannot
        // be produced by waiting for a GC that may never come; it is recorded directly.
        var detector = AsyncTestContext.weakReferenceRaceDetector();
        List<String> alive = STRONG_PAYLOAD;
        List<String> seen = WEAK_PAYLOAD.get();
        detector.recordGet(WEAK_PAYLOAD, "payload", seen, Thread.currentThread());
        if (seen != null) {
            spin(seen.size());
        }
        detector.recordNullDereference(WEAK_PAYLOAD, "payload", Thread.currentThread());
        spin(alive.size());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STATEFUL_LAMBDA})
    void statefulLambda() {
        reachable("statefulLambdaDetector()", AsyncTestContext::statefulLambdaDetector);

        // One lambda instance, shared across workers, closing over mutable state: the detector
        // reports when a lambda executed on more than one thread also mutated what it captured.
        var detector = AsyncTestContext.statefulLambdaDetector();
        detector.recordExecution(SHARED_ACCUMULATOR, "accumulator", Thread.currentThread());
        SHARED_ACCUMULATOR.run();
        detector.recordCapturedMutation(SHARED_ACCUMULATOR, "total", Thread.currentThread());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_MESSAGE_DIGEST})
    void sharedMessageDigest() {
        reachable("sharedMessageDigestDetector()", AsyncTestContext::sharedMessageDigestDetector);

        // Deliberately unguarded: this detector recognises synchronized(digest) as correct, so
        // wrapping the recording would - correctly - produce no finding to assert.
        AsyncTestContext.sharedMessageDigestDetector()
                .recordAccess(SHARED_DIGEST, "sha256", Thread.currentThread());
        try {
            SHARED_DIGEST.digest("payload".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException expected) {
            // MessageDigest accumulates state between update() and digest().
        }
    }

    private static final Matcher SHARED_MATCHER = Pattern.compile("[a-z]+").matcher("");

    private static final DecimalFormat SHARED_DECIMAL_FORMAT = new DecimalFormat("#,##0.00");

    private static final MessageDigest SHARED_DIGEST = newDigest();

    private static final List<String> STRONG_PAYLOAD = List.of("payload");

    private static final WeakReference<List<String>> WEAK_PAYLOAD =
            new WeakReference<>(STRONG_PAYLOAD);

    private static final int[] TOTAL = new int[1];

    /** One shared lambda closing over {@link #TOTAL} - the captured mutable state. */
    private static final Runnable SHARED_ACCUMULATOR = () -> TOTAL[0]++;

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }
}
