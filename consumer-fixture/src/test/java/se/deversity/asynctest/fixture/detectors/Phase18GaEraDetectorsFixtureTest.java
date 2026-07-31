package se.deversity.asynctest.fixture.detectors;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

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

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.LAZY_CONSTANT_MISUSE})
    void lazyConstantMisuse() {
        reachable("lazyConstantMisuseDetector()", AsyncTestContext::lazyConstantMisuseDetector);

        // A "constant" computed on first use without safe publication: every worker can
        // compute its own, and a reader can see a half-built one.
        LazyConstant.value();
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.FINAL_FIELD_MUTATION})
    void finalFieldMutation() {
        reachable("finalFieldMutationDetector()", AsyncTestContext::finalFieldMutationDetector);

        // The hazard is reflective or Unsafe writes to a final field, which break the JMM's
        // final-field freeze guarantee. The fixture reads the field rather than performing
        // the write: doing it for real needs --add-opens and would break the round on a
        // stricter JDK, and the detector's subject is the field, not the mechanism.
        FrozenConfig config = new FrozenConfig(7);
        spin(config.limit());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_KDF})
    void sharedKdf() {
        reachable("sharedKdfDetector()", AsyncTestContext::sharedKdfDetector);

        // SecretKeyFactory derives keys from mutable per-call state; sharing one instance
        // across workers is the hazard. Iterations kept low so the round stays short.
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            factory.generateSecret(
                new PBEKeySpec("password".toCharArray(), new byte[] {1, 2, 3, 4}, 1_000, 128));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new AssertionError("PBKDF2WithHmacSHA256 is required by every JRE", e);
        } catch (RuntimeException expected) {
            // A shared key factory losing a race is the point of this fixture.
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
