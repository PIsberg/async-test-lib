package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 18, JDK 25/26 GA-era group — {@code LAZY_CONSTANT_MISUSE},
 * {@code FINAL_FIELD_MUTATION}, {@code SHARED_KDF}.
 *
 * <p>Corresponding examples: {@code examples/117-lazy-constant-misuse},
 * {@code examples/118-final-field-mutation}, {@code examples/119-shared-kdf}.
 */
class Phase18GaEraDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "LazyConstantMisuseDetector",
                    "FinalFieldMutationDetector",
                    "SharedKdfDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LAZY_CONSTANT_MISUSE})
    void lazyConstantMisuse() {
        reachable("lazyConstantMisuseDetector()", AsyncTestContext::lazyConstantMisuseDetector);

        // A "constant" computed on first use without safe publication: every worker can
        // compute its own, and a reader can see a half-built one.
        // Two workers each racing the null check and computing their own "constant" is the
        // misuse; the detector reports a lazy constant computed from more than one thread.
        var lazyDetector = AsyncTestContext.lazyConstantMisuseDetector();
        lazyDetector.recordGet("LazyConstant.cached", Thread.currentThread());
        lazyDetector.recordComputeStart("LazyConstant.cached", Thread.currentThread());
        String value = LazyConstant.value();
        lazyDetector.recordComputeEnd("LazyConstant.cached", Thread.currentThread(), value);
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FINAL_FIELD_MUTATION})
    void finalFieldMutation() {
        reachable("finalFieldMutationDetector()", AsyncTestContext::finalFieldMutationDetector);

        // The hazard is reflective or Unsafe writes to a final field, which break the JMM's
        // final-field freeze guarantee. The fixture reads the field rather than performing
        // the write: doing it for real needs --add-opens and would break the round on a
        // stricter JDK, and the detector's subject is the field, not the mechanism.
        var finalFieldDetector = AsyncTestContext.finalFieldMutationDetector();
        FrozenConfig config = new FrozenConfig(7);
        finalFieldDetector.recordRead("FrozenConfig.limit", Thread.currentThread());
        finalFieldDetector.recordMutation("FrozenConfig.limit", Thread.currentThread());
        spin(config.limit());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_KDF})
    void sharedKdf() {
        reachable("sharedKdfDetector()", AsyncTestContext::sharedKdfDetector);

        // SecretKeyFactory derives keys from mutable per-call state; sharing one instance
        // across workers is the hazard. Iterations kept low so the round stays short.
        AsyncTestContext.sharedKdfDetector().recordAccess(
                SHARED_KDF, "PBKDF2WithHmacSHA256", "generateSecret", Thread.currentThread());
        try {
            SecretKeyFactory factory = SHARED_KDF;
            factory.generateSecret(
                new PBEKeySpec("password".toCharArray(), new byte[] {1, 2, 3, 4}, 1_000, 128));
        } catch (InvalidKeySpecException e) {
            throw new AssertionError("a PBEKeySpec with a salt and 1000 iterations must derive", e);
        } catch (RuntimeException expected) {
            // A shared key factory losing a race is the point of this fixture.
        }
    }

    /** One key factory for the whole round: the sharing is what the detector reports. */
    private static final SecretKeyFactory SHARED_KDF = newKdf();

    private static SecretKeyFactory newKdf() {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is required by every JRE", e);
        }
    }

    /** Lazily built without volatile or a holder class — the misuse. */
    private static final class LazyConstant {
        private static String cached;

        static String value() {
            if (cached == null) {
                cached = "derived-constant";
            }
            return cached;
        }
    }

    private static final class FrozenConfig {
        private final int limit;

        FrozenConfig(int limit) {
            this.limit = limit;
        }

        int limit() {
            return limit;
        }
    }
}
