package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accuracy eval for the {@code Shared*} family: for each of the 19 detectors that watch a
 * non-thread-safe JDK type, does it fire on genuinely unsafe sharing, and what does it do with
 * the correctly guarded twin?
 *
 * <p><strong>Why this exists.</strong> {@link DetectorAccuracyEvalTest} answers "when a detector
 * fires, was the code wrong?" for eight detectors. This extends the same question to the largest
 * cluster in the catalogue, and it earned its place immediately: the true-positive direction was
 * red for {@code SharedRandomDetector} and {@code SimpleDateFormatDetector} before it was
 * written. Both auto-registered per-instance state with a get-then-put, so two threads racing on
 * a first access each built a state object and the second {@code put} discarded the first. Each
 * thread then counted itself alone, {@code analyze()}'s "more than one thread" test never
 * tripped, and the detector went silent under exactly the contention it exists to find. Their
 * unit tests passed throughout, because a single-threaded test never loses the race.
 *
 * <p>Both directions are asserted for every detector, because a detector that fires on
 * everything is as useless as one that fires on nothing, and only the pair distinguishes them.
 *
 * <h4>The guard-on-self column is a measurement, not an aspiration</h4>
 *
 * <p>Most of this family reduces its input to "how many distinct threads touched this instance"
 * and carries no representation of locks, so a correctly synchronized twin records the identical
 * event stream and produces the identical finding. That is a false positive, and pinning it here
 * is the honest way to hold it: the number is visible, and it can only shrink. Two detectors
 * ({@code SharedMessageDigestDetector}, {@code SharedStatefulCryptoDetector}) carry the
 * {@code Thread.holdsLock} guard-on-self probe and recognise the {@code synchronized(instance)}
 * idiom. When that probe rolls out to the rest, this test will fail on the newly-silent
 * detector, which is the signal to move it into {@link #GUARD_ON_SELF_AWARE} and update
 * {@code docs/analysis/detector-accuracy-eval.md} in the same change.
 */
@DisplayName("Accuracy eval: the Shared* family, unsafe sharing vs its guarded twin")
class SharedTypeAccuracyEvalTest {

    /**
     * Detectors that recognise {@code synchronized(sharedInstance)} and stay silent for it.
     *
     * <p>Everything else in the family fires on the guarded twin. This set is the ratchet: it may
     * grow as the {@code holdsLock} probe rolls out, and a detector leaving it is a regression.
     */
    private static final Set<String> GUARD_ON_SELF_AWARE = new TreeSet<>(Set.of(
            "SharedMessageDigestDetector",
            "SharedStatefulCryptoDetector"));

    /** One detector under evaluation: record an access (optionally guarded), then ask the report. */
    private record Probe(Consumer<Boolean> access, BooleanSupplier hasIssues) { }

    @Test
    @DisplayName("every Shared* detector fires when the instance is shared without a guard")
    void everySharedDetectorFiresOnUnsynchronizedSharing() {
        List<String> silent = new ArrayList<>();
        cases().forEach((name, factory) -> {
            Probe probe = factory.get();
            onTwoThreads(() -> probe.access().accept(false));
            if (!probe.hasIssues().getAsBoolean()) {
                silent.add(name);
            }
        });

        assertTrue(silent.isEmpty(),
                "These detectors watched two threads share a non-thread-safe instance with no "
                        + "synchronization at all and reported nothing:\n  "
                        + String.join("\n  ", silent)
                        + "\n\nThat is the false negative that matters: the hazard is real, the "
                        + "detector is enabled, and the user is told their code is fine. "
                        + "SharedRandomDetector and SimpleDateFormatDetector both failed here "
                        + "because auto-registration used get-then-put, so the two racing "
                        + "threads each kept a private state and neither saw the other. If a "
                        + "detector lands in this list, check its record path for a "
                        + "check-then-act on the per-instance map before assuming the fixture "
                        + "is wrong.");
    }

    @Test
    @DisplayName("the guard-on-self twin: two detectors recognise it, the rest still fire")
    void guardOnSelfTwinOutcomeIsPinnedPerDetector() {
        List<String> nowSilent = new ArrayList<>();
        List<String> regressed = new ArrayList<>();

        cases().forEach((name, factory) -> {
            Probe probe = factory.get();
            onTwoThreads(() -> probe.access().accept(true));
            boolean fired = probe.hasIssues().getAsBoolean();

            if (GUARD_ON_SELF_AWARE.contains(name)) {
                if (fired) {
                    regressed.add(name);
                }
            } else if (!fired) {
                nowSilent.add(name);
            }
        });

        assertTrue(regressed.isEmpty(),
                "These detectors carry the Thread.holdsLock guard-on-self probe and must stay "
                        + "silent when every access held the shared instance's own monitor - "
                        + "synchronized(instance) is the most common correct guarding idiom in "
                        + "Java, and flagging it makes the fix look as broken as the bug:\n  "
                        + String.join("\n  ", regressed));

        assertTrue(nowSilent.isEmpty(),
                "Good news, and a deliberate failure: these detectors went silent on the "
                        + "correctly guarded twin, which they did not do before:\n  "
                        + String.join("\n  ", nowSilent)
                        + "\n\nThat means the guard-on-self probe reached them and a pinned "
                        + "false positive is gone. Add them to GUARD_ON_SELF_AWARE and update "
                        + "docs/analysis/detector-accuracy-eval.md in the same change, so the "
                        + "published table cannot drift from the code.");
    }

    // ---- harness ----

    /** Runs {@code body} on two freshly started threads released together by a barrier. */
    private static void onTwoThreads(Runnable body) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Runnable sync = () -> {
            try {
                barrier.await();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            body.run();
        };
        Thread t1 = new Thread(sync, "shared-eval-1");
        Thread t2 = new Thread(sync, "shared-eval-2");
        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Wraps {@code body} in {@code synchronized (target)} when {@code guarded}. */
    private static void guard(boolean guarded, Object target, Runnable body) {
        if (guarded) {
            synchronized (target) {
                body.run();
            }
        } else {
            body.run();
        }
    }

    /**
     * {@return every Shared* detector, paired with a factory for a fresh probe}
     *
     * <p>A factory rather than an instance because each direction needs a detector that has seen
     * nothing: reusing one across the guarded and unguarded runs would carry the first run's
     * findings into the second and make the second assertion meaningless.
     */
    private static Map<String, Supplier<Probe>> cases() {
        Map<String, Supplier<Probe>> cases = new LinkedHashMap<>();

        cases.put("SharedByteBufferDetector", () -> {
            SharedByteBufferDetector d = new SharedByteBufferDetector();
            ByteBuffer b = ByteBuffer.allocate(64);
            return new Probe(g -> guard(g, b, () -> d.recordPositionalAccess(b, "get")),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedCharsetCoderDetector", () -> {
            SharedCharsetCoderDetector d = new SharedCharsetCoderDetector();
            var encoder = StandardCharsets.UTF_8.newEncoder();
            return new Probe(
                    g -> guard(g, encoder,
                            () -> d.recordAccess(encoder, "encode", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedChecksumDetector", () -> {
            SharedChecksumDetector d = new SharedChecksumDetector();
            CRC32 crc = new CRC32();
            return new Probe(
                    g -> guard(g, crc, () -> d.recordAccess(crc, "update", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedCollectionDetector", () -> {
            SharedCollectionDetector d = new SharedCollectionDetector();
            List<String> list = new ArrayList<>();
            return new Probe(g -> guard(g, list, () -> d.recordWrite(list, "list", "add")),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedDecimalFormatDetector", () -> {
            SharedDecimalFormatDetector d = new SharedDecimalFormatDetector();
            DecimalFormat format = new DecimalFormat("#,##0.00");
            return new Probe(
                    g -> guard(g, format,
                            () -> d.recordAccess(format, "amount", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedDeflaterDetector", () -> {
            SharedDeflaterDetector d = new SharedDeflaterDetector();
            Deflater deflater = new Deflater();
            return new Probe(
                    g -> guard(g, deflater,
                            () -> d.recordAccess(deflater, "gzip", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedFormatterDetector", () -> {
            SharedFormatterDetector d = new SharedFormatterDetector();
            Object formatter = new java.util.Formatter();
            return new Probe(
                    g -> guard(g, formatter,
                            () -> d.recordAccess(formatter, "report", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedIteratorDetector", () -> {
            SharedIteratorDetector d = new SharedIteratorDetector();
            Iterator<String> it = List.of("a", "b").iterator();
            return new Probe(g -> guard(g, it, () -> d.recordAccess(it, "next")),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedJsonMapperReconfigDetector", () -> {
            SharedJsonMapperReconfigDetector d = new SharedJsonMapperReconfigDetector();
            Object mapper = new Object();
            return new Probe(g -> guard(g, mapper, () -> {
                d.recordUse(mapper);
                d.recordConfigMutation(mapper, "enable(FAIL_ON_UNKNOWN_PROPERTIES)");
            }), () -> d.analyze().hasIssues());
        });
        cases.put("SharedKdfDetector", () -> {
            SharedKdfDetector d = new SharedKdfDetector();
            Object kdf = new Object();
            return new Probe(
                    g -> guard(g, kdf,
                            () -> d.recordAccess(kdf, "HKDF", "deriveKey", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedMatcherDetector", () -> {
            SharedMatcherDetector d = new SharedMatcherDetector();
            Object matcher = java.util.regex.Pattern.compile("[a-z]+").matcher("");
            return new Probe(
                    g -> guard(g, matcher,
                            () -> d.recordAccess(matcher, "word", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedMessageDigestDetector", () -> {
            SharedMessageDigestDetector d = new SharedMessageDigestDetector();
            MessageDigest digest = sha256();
            return new Probe(
                    g -> guard(g, digest,
                            () -> d.recordAccess(digest, "sha256", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedRandomDetector", () -> {
            SharedRandomDetector d = new SharedRandomDetector();
            Random random = new Random();
            return new Probe(
                    g -> guard(g, random, () -> d.recordRandomAccess(random, "rng", "nextInt")),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedSecureRandomDetector", () -> {
            SharedSecureRandomDetector d = new SharedSecureRandomDetector();
            SecureRandom random = new SecureRandom();
            return new Probe(
                    g -> guard(g, random,
                            () -> d.recordAccess(random, "csprng", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedSplittableRandomDetector", () -> {
            SharedSplittableRandomDetector d = new SharedSplittableRandomDetector();
            SplittableRandom random = new SplittableRandom();
            return new Probe(
                    g -> guard(g, random, () -> d.recordAccess(random, "rng", "nextInt")),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedStatefulCryptoDetector", () -> {
            SharedStatefulCryptoDetector d = new SharedStatefulCryptoDetector();
            Mac mac = hmac();
            return new Probe(
                    g -> guard(g, mac, () -> d.recordAccess(mac, "hmac", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedTimeZoneDetector", () -> {
            SharedTimeZoneDetector d = new SharedTimeZoneDetector();
            TimeZone zone = TimeZone.getDefault();
            return new Probe(
                    g -> guard(g, zone,
                            () -> d.recordMutation(zone, "setRawOffset", Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SharedXmlParserDetector", () -> {
            SharedXmlParserDetector d = new SharedXmlParserDetector();
            Object factory = DocumentBuilderFactory.newInstance();
            return new Probe(
                    g -> guard(g, factory,
                            () -> d.recordAccess(factory, "DocumentBuilderFactory",
                                    Thread.currentThread())),
                    () -> d.analyze().hasIssues());
        });
        cases.put("SimpleDateFormatDetector", () -> {
            SimpleDateFormatDetector d = new SimpleDateFormatDetector();
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
            return new Probe(g -> guard(g, format, () -> {
                format.format(new Date());
                d.recordFormat(format, "isoDate");
            }), () -> d.analyze().hasIssues());
        });

        return cases;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }

    private static Mac hmac() {
        try {
            return Mac.getInstance("HmacSHA256");
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is required by every JRE", e);
        }
    }
}
