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
        // Deliberately unguarded: this detector carries the Thread.holdsLock guard-on-self
        // probe, so synchronized(SHARED_MAC) would - correctly - produce nothing to assert.
        AsyncTestContext.sharedStatefulCryptoDetector()
                .recordAccess(SHARED_MAC, "shared-hmac", Thread.currentThread());
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
        // containsKey-then-put on a ConcurrentMap is check-then-act: each call is atomic, the
        // pair is not, and the map must be shared for two workers to interleave in the window.
        // A fixture-local map is touched by one thread and shows the detector nothing.
        AsyncTestContext.nonAtomicConcurrentMapUpdateDetector()
                .recordCheckThenAct(SHARED_MAP, "k", "containsKey+put", Thread.currentThread());
        if (!SHARED_MAP.containsKey("k")) {
            SHARED_MAP.put("k", 1);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.SHARED_DEFLATER})
    void sharedDeflater() {
        reachable("sharedDeflaterDetector()", AsyncTestContext::sharedDeflaterDetector);

        // Deflater holds native state and must not be shared. A fixture-local instance is
        // created and end()ed so no native memory leaks out of the round.
        // Shared across the round: a Deflater carries compression state between calls, so a
        // per-invocation instance is shared with nothing and gives the detector nothing to see.
        // end() is deliberately not called - the other worker is still using it.
        AsyncTestContext.sharedDeflaterDetector()
                .recordAccess(SHARED_DEFLATER, "shared-deflater", Thread.currentThread());
        synchronized (SHARED_DEFLATER) {
            SHARED_DEFLATER.reset();
            SHARED_DEFLATER.setInput("payload".getBytes(StandardCharsets.UTF_8));
            SHARED_DEFLATER.finish();
            SHARED_DEFLATER.deflate(new byte[64]);
        }
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THIS_ESCAPE})
    void thisEscape() {
        reachable("thisEscapeDetector()", AsyncTestContext::thisEscapeDetector);

        // The constructor publishes `this` before the object is fully built — another
        // thread reading ESCAPED can see a partially-initialised instance.
        // Escaper publishes `this` from its constructor before its final field is assigned,
        // so another thread can reach a half-built instance through ESCAPED.
        var escapeDetector = AsyncTestContext.thisEscapeDetector();
        Escaper escaper = new Escaper(7);
        escapeDetector.recordConstructorEscape(escaper, "stored into a static AtomicReference",
                Thread.currentThread());
        Escaper seen = ESCAPED.get();
        if (seen != null) {
            escapeDetector.recordExternalAccess(seen, Thread.currentThread());
        }
        escapeDetector.recordConstructionComplete(escaper);
        spin(escaper.value());
    }

    @AsyncTest(threads = 2, invocations = 1, timeoutMs = 20_000, licenseMockMode = true,
               includes = {DetectorType.THREAD_LOCAL_RANDOM_MISUSE})
    void threadLocalRandomMisuse() {
        reachable("threadLocalRandomMisuseDetector()",
            AsyncTestContext::threadLocalRandomMisuseDetector);

        // The misuse is caching ThreadLocalRandom.current() in a field and reusing it from
        // another thread; the correct form calls current() on the thread that uses it.
        // The misuse is holding on to the instance: ThreadLocalRandom.current() must be called
        // on the thread that uses it, and a cached reference is shared across workers.
        var tlrDetector = AsyncTestContext.threadLocalRandomMisuseDetector();
        tlrDetector.recordObtain(SHARED_TLR, "cached-tlr", Thread.currentThread());
        tlrDetector.recordUse(SHARED_TLR, Thread.currentThread());
        spin(SHARED_TLR.nextInt(100));
    }

    private static final Mac SHARED_MAC = newMac();

    private static final ConcurrentMap<String, Integer> SHARED_MAP = new ConcurrentHashMap<>();

    private static final Deflater SHARED_DEFLATER = new Deflater();

    /** Obtained once and cached - the misuse ThreadLocalRandom's javadoc warns against. */
    private static final ThreadLocalRandom SHARED_TLR = ThreadLocalRandom.current();

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
