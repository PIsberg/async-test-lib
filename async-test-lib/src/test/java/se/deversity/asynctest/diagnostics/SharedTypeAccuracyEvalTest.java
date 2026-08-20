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
 * <p>A detector that reduces its input to "how many distinct threads touched this instance" and
 * carries no representation of locks cannot tell the guarded twin from the bug: both record the
 * identical event stream. When this eval was first written, 17 of the 19 were in exactly that
 * position and fired on correct code.
 *
 * <p>They now share one probe. {@code SelfGuard.TrackedInstance} asks
 * {@link Thread#holdsLock(Object)} on the record path and remembers whether any access ran
 * without the instance's own monitor held; {@code analyze()} reports only when one did. 17 of 19
 * recognise the {@code synchronized(instance)} twin as a result, and the remaining two are in
 * {@link #CONTENTION_NOTE_BY_DESIGN} because for them the twin is not a false positive at all.
 *
 * <p>What the probe still cannot see is a guard on any other lock object - a
 * {@code ReentrantLock}, or a private lock - because nothing in these recording APIs names the
 * lock the caller chose. Closing that direction needs a per-thread held-lock set rather than a
 * single-object probe, and until it exists those twins are expected to fire.
 */
@DisplayName("Accuracy eval: the Shared* family, unsafe sharing vs its guarded twin")
class SharedTypeAccuracyEvalTest {

    /**
     * Detectors that recognise {@code synchronized(sharedInstance)} and stay silent for it.
     *
     * <p>This set is the ratchet. It may grow as the probe reaches detectors that do not carry it
     * yet, and a detector leaving it is a regression: the guarded twin is correct code, and a
     * detector that flags it makes the fix look as broken as the bug.
     */
    private static final Set<String> GUARD_ON_SELF_AWARE = new TreeSet<>(Set.of(
            "SharedByteBufferDetector",
            "SharedCharsetCoderDetector",
            "SharedChecksumDetector",
            "SharedCollectionDetector",
            "SharedDecimalFormatDetector",
            "SharedDeflaterDetector",
            "SharedFormatterDetector",
            "SharedIteratorDetector",
            "SharedJsonMapperReconfigDetector",
            "SharedKdfDetector",
            "SharedMatcherDetector",
            "SharedMessageDigestDetector",
            "SharedSplittableRandomDetector",
            "SharedStatefulCryptoDetector",
            "SharedTimeZoneDetector",
            "SharedXmlParserDetector",
            "SimpleDateFormatDetector"));

    /**
     * Detectors for which firing on the guarded twin is the correct answer, not a false positive.
     *
     * <p>{@code java.util.Random} and {@code SecureRandom} are both thread-safe. These two
     * detectors do not claim that concurrent access corrupts the instance; they report that a
     * single generator is being contended by several threads, which costs throughput. Wrapping
     * the instance in {@code synchronized(instance)} does not make that untrue - it serializes
     * the callers a second time, on top of the internal synchronization the type already has, so
     * the contention the finding describes gets worse rather than going away.
     *
     * <p>They are therefore held here rather than left looking like unfinished work: the guarded
     * twin must keep firing, and a probe that silenced one of them would be a defect.
     */
    private static final Set<String> CONTENTION_NOTE_BY_DESIGN = new TreeSet<>(Set.of(
            "SharedRandomDetector",
            "SharedSecureRandomDetector"));

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
    @DisplayName("the guard-on-self twin: 17 detectors recognise it, 2 contention notes still fire")
    void guardOnSelfTwinOutcomeIsPinnedPerDetector() {
        List<String> stillFires = new ArrayList<>();
        List<String> contentionNoteLost = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();

        cases().forEach((name, factory) -> {
            Probe probe = factory.get();
            onTwoThreads(() -> probe.access().accept(true));
            boolean fired = probe.hasIssues().getAsBoolean();

            if (GUARD_ON_SELF_AWARE.contains(name)) {
                if (fired) {
                    stillFires.add(name);
                }
            } else if (CONTENTION_NOTE_BY_DESIGN.contains(name)) {
                if (!fired) {
                    contentionNoteLost.add(name);
                }
            } else {
                unclassified.add(name);
            }
        });

        assertTrue(stillFires.isEmpty(),
                "These detectors carry the SelfGuard.TrackedInstance guard-on-self probe and must "
                        + "stay silent when every access held the shared instance's own monitor - "
                        + "synchronized(instance) is the most common correct guarding idiom in "
                        + "Java, and flagging it makes the fix look as broken as the bug:\n  "
                        + String.join("\n  ", stillFires));

        assertTrue(contentionNoteLost.isEmpty(),
                "These detectors went silent on the guarded twin, and for them that is wrong, not "
                        + "progress:\n  "
                        + String.join("\n  ", contentionNoteLost)
                        + "\n\nThey watch a type that is already thread-safe, so their finding is a "
                        + "contention note rather than a corruption claim. Wrapping the instance "
                        + "in synchronized(instance) does not falsify that note - it adds a second "
                        + "layer of serialization on top of the one the type already has, which "
                        + "makes the contention worse. If a guard-on-self probe reached one of "
                        + "these, take it back out rather than moving the detector into "
                        + "GUARD_ON_SELF_AWARE.");

        assertTrue(unclassified.isEmpty(),
                "These Shared* detectors are in neither set, so nothing pins what their guarded "
                        + "twin should do:\n  "
                        + String.join("\n  ", unclassified)
                        + "\n\nDecide which they are. If the finding claims that unsynchronized "
                        + "access corrupts the instance, extend SelfGuard.TrackedInstance in the "
                        + "detector's state class, call noteAccess(instance) on the record path, "
                        + "gate analyze() on sawUnguardedAccess(), and add the detector here to "
                        + "GUARD_ON_SELF_AWARE. If the type is thread-safe and the finding is "
                        + "about contention, add it to CONTENTION_NOTE_BY_DESIGN with the reason. "
                        + "Update docs/analysis/detector-accuracy-eval.md either way, so the "
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
