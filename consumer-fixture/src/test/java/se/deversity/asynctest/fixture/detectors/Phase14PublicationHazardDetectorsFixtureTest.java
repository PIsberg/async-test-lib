package se.deversity.asynctest.fixture.detectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import se.deversity.asynctest.AsyncFindings;
import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.DetectorType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;

import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertAllReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.assertNoneReported;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.reachable;
import static se.deversity.asynctest.fixture.detectors.DetectorFixtureSupport.spin;

/**
 * Phase 14, thread-unsafe primitives &amp; publication hazards — {@code SHARED_STATEFUL_CRYPTO}
 * through {@code THREAD_LOCAL_RANDOM_MISUSE}.
 *
 * <p>Corresponding examples: {@code examples/104-shared-stateful-crypto},
 * {@code examples/105-concurrent-map-check-then-act}, {@code examples/106-shared-deflater},
 * {@code examples/107-this-escape}, {@code examples/108-thread-local-random-misuse}.
 */
class Phase14PublicationHazardDetectorsFixtureTest {

    private static AsyncFindings findings;

    @BeforeAll
    static void collectFindings() {
        findings = AsyncFindings.collect();
    }

    @AfterAll
    static void everyFedDetectorReported() {
        try {
            assertAllReported(findings,
                    "SharedStatefulCryptoDetector",
                    "NonAtomicConcurrentMapUpdateDetector",
                    "SharedDeflaterDetector",
                    "ThisEscapeDetector",
                    "ThreadLocalRandomMisuseDetector");
        } finally {
            findings.close();
        }
    }


    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_STATEFUL_CRYPTO})
    void sharedStatefulCrypto() {
        reachable("sharedStatefulCryptoDetector()", AsyncTestContext::sharedStatefulCryptoDetector);

        // Mac accumulates state between update() and doFinal(); one shared instance means
        // two workers interleave into the same digest.
        try {
            SHARED_MAC.doFinal("payload".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException expected) {
            // A shared Mac losing a race is the point of this fixture.
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.CONCURRENT_MAP_CHECK_THEN_ACT})
    void concurrentMapCheckThenAct() {
        reachable("nonAtomicConcurrentMapUpdateDetector()",
            AsyncTestContext::nonAtomicConcurrentMapUpdateDetector);

        // containsKey() then put() is two operations on a map that only makes each one
        // atomic — putIfAbsent() is the single-operation fix.
        ConcurrentMap<String, Integer> map = new ConcurrentHashMap<>();
        if (!map.containsKey("k")) {
            map.put("k", 1);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_DEFLATER})
    void sharedDeflater() {
        reachable("sharedDeflaterDetector()", AsyncTestContext::sharedDeflaterDetector);

        // Deflater holds native state and must not be shared. A fixture-local instance is
        // created and end()ed so no native memory leaks out of the round.
        Deflater deflater = new Deflater();
        try {
            deflater.setInput("payload".getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            deflater.deflate(new byte[64]);
        } finally {
            deflater.end();
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THIS_ESCAPE})
    void thisEscape() {
        reachable("thisEscapeDetector()", AsyncTestContext::thisEscapeDetector);

        // The constructor publishes `this` before the object is fully built — another
        // thread reading ESCAPED can see a partially-initialised instance.
        Escaper escaper = new Escaper(7);
        spin(escaper.value());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_LOCAL_RANDOM_MISUSE})
    void threadLocalRandomMisuse() {
        reachable("threadLocalRandomMisuseDetector()",
            AsyncTestContext::threadLocalRandomMisuseDetector);

        // The misuse is caching ThreadLocalRandom.current() in a field and reusing it from
        // another thread; the correct form calls current() on the thread that uses it.
        ThreadLocalRandom.current().nextInt(100);
    }

    private static final Mac SHARED_MAC = newMac();

    /** Deliberately published from its own constructor. */
    static final AtomicReference<Escaper> ESCAPED = new AtomicReference<>();

    static final class Escaper {
        private final int value;

        Escaper(int value) {
            ESCAPED.set(this);       // escapes before the field below is assigned
            this.value = value;
        }

        int value() {
            return value;
        }
    }

    private static Mac newMac() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("fixture-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by every JRE", e);
        }
    }
}
