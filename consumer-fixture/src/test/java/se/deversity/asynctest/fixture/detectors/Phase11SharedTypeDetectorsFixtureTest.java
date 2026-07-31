package se.deversity.asynctest.fixture.detectors;

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

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 11, additional-shared-types group — {@code SHARED_MATCHER} through
 * {@code SHARED_MESSAGE_DIGEST}.
 *
 * <p>{@code Pattern} is thread-safe, {@code Matcher} is not; {@code DecimalFormat} and
 * {@code MessageDigest} carry mutable state across calls. Each fixture shares the unsafe
 * one on purpose and tolerates the resulting throw — that throw is the finding.
 *
 * <p>Corresponding examples: {@code examples/10-shared-non-thread-safe-types}, which covers
 * the matcher, decimal-format and message-digest cases;
 * {@code examples/97-weak-reference-race}; {@code examples/76-stateful-lambda}.
 */
class Phase11SharedTypeDetectorsFixtureTest {

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_MATCHER})
    void sharedMatcher() {
        reachable("sharedMatcherDetector()", AsyncTestContext::sharedMatcherDetector);

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

        // get() can return null between the null check and the use — the race.
        WeakReference<List<String>> ref = new WeakReference<>(List.of("payload"));
        List<String> strong = ref.get();
        if (strong != null) {
            spin(strong.size());
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.STATEFUL_LAMBDA})
    void statefulLambda() {
        reachable("statefulLambdaDetector()", AsyncTestContext::statefulLambdaDetector);

        // A lambda closing over mutable state and used from a stream is the trap; here the
        // accumulation is local to the worker so the fixture stays deterministic.
        int[] accumulator = new int[1];
        List.of(1, 2, 3).forEach(value -> accumulator[0] += value);
        spin(accumulator[0]);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_MESSAGE_DIGEST})
    void sharedMessageDigest() {
        reachable("sharedMessageDigestDetector()", AsyncTestContext::sharedMessageDigestDetector);

        try {
            SHARED_DIGEST.digest("payload".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException expected) {
            // MessageDigest accumulates state between update() and digest().
        }
    }

    private static final Matcher SHARED_MATCHER = Pattern.compile("[a-z]+").matcher("");

    private static final DecimalFormat SHARED_DECIMAL_FORMAT = new DecimalFormat("#,##0.00");

    private static final MessageDigest SHARED_DIGEST = newDigest();

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }
}
