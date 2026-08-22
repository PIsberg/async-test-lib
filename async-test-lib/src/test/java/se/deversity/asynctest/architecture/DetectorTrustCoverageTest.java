package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.GradedFindings;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps the trust tiers honest.
 *
 * <p><strong>Why this exists.</strong> Before {@link DetectorTrust} the trust tier was prose: a
 * "Trust tier" line in {@code docs/DETECTOR_CATALOG.md}, present for 17 of the 142 entries and
 * enforced by nothing. Issue #285 had already shown what that costs. The accuracy-eval document
 * claimed the build could not go green if its table drifted, while one of its rows described a
 * detector the eval never constructed.
 *
 * <p>A confidence label nobody checks is worse than no label, because a reader acts on it. These
 * tests make the label a measurement:
 *
 * <ul>
 *   <li>every detector is classified, so a new one cannot arrive unlabelled;</li>
 *   <li>the table's detector class names match what the factories actually construct, so a rename
 *       cannot silently detach a tier from the finding it belongs to;</li>
 *   <li>and {@link TrustTier#VERDICT}, the only tier safe to fail a merge on, requires named
 *       both-directions tests that this gate resolves by reflection. Promotion without evidence
 *       does not get past here.</li>
 * </ul>
 */
class DetectorTrustCoverageTest {

    /**
     * The both-directions evidence behind every {@link TrustTier#VERDICT} classification.
     *
     * <p>Two test methods per detector: one that pins it firing on genuinely buggy code, one that
     * pins it silent on the correct twin. Both are resolved reflectively below, so an entry naming
     * a method that was renamed or deleted fails this gate instead of quietly vouching for a tier.
     *
     * <p>{@code DEADLOCKS} is the one split across two classes: its true positive needs a real
     * deadlock and a full run, which lives in {@code DetectionCoverageTest}, while the true
     * negative is a recording-level case in the eval.
     */
    private static final Map<DetectorType, List<String>> EVIDENCE = Map.ofEntries(
            entry(DetectorType.DEADLOCKS, List.of(
                    "se.deversity.asynctest.DetectionCoverageTest#deadlockIsReportedWithoutAnyInstrumentation",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#deadlockDetectorStaysSilentOnOrderedLocking")),
            entry(DetectorType.LOCK_ORDER, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#lockOrderValidatorFiresOnInversion",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#lockOrderValidatorStaysSilentOnConsistentOrdering")),
            entry(DetectorType.ATOMIC_NON_ATOMIC_UPDATE, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#nonAtomicUpdateDetectorFiresOnGetThenSet",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#nonAtomicUpdateDetectorStaysSilentOnCas")),
            entry(DetectorType.LOCK_LEAKS, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#lockLeakDetectorFiresOnAnUnreleasedLock",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#lockLeakDetectorStaysSilentWhenEveryAcquireIsReleased")),
            entry(DetectorType.COMPLETABLE_FUTURE_EXCEPTIONS, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#completableFutureExceptionDetectorFiresOnAnUnhandledFailure",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#completableFutureExceptionDetectorStaysSilentWhenTheFailureIsHandled")),
            entry(DetectorType.RESOURCE_LEAKS, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#resourceLeakDetectorFiresOnAnUnclosedResource",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#resourceLeakDetectorStaysSilentWhenEveryOpenIsClosed")),
            entry(DetectorType.INTERRUPT_MISHANDLING, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#interruptMonitorFiresWhenTheFlagIsNeverRestored",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#interruptMonitorStaysSilentWhenTheFlagIsRestored")),
            entry(DetectorType.UNCAUGHT_EXCEPTION_HANDLER, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#uncaughtExceptionHandlerDetectorFiresWhenNoHandlerIsInstalled",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#uncaughtExceptionHandlerDetectorStaysSilentWhenAHandlerIsInstalled")),
            entry(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#completionLeakDetectorFiresOnAFutureThatIsNeverCompleted",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#completionLeakDetectorStaysSilentWhenTheFutureIsCompleted")),
            entry(DetectorType.THREAD_LEAKS, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#threadLeakDetectorFiresOnAThreadStillAlive",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#threadLeakDetectorStaysSilentWhenTheThreadTerminated"))
    );
    /**
     * The one {@link DetectorType} with no row in {@code LegacyDetectorFactories}.
     *
     * <p>It has a dedicated typed adapter instead, {@code SharedMessageDigestDetectorFactory},
     * because it surfaces structured violations directly. Listed here so the parse below can tell
     * a deliberate omission from a detector that lost its factory.
     */
    private static final DetectorType FACTORY_EXEMPT = DetectorType.SHARED_MESSAGE_DIGEST;

    /** Source of truth for what each detector factory constructs. */
    private static final String FACTORIES =
            "async-test-lib/src/main/java/se/deversity/asynctest/spi/adapters/LegacyDetectorFactories.java";

    @Test
    @DisplayName("every detector is classified, exactly once, in declaration order")
    void everyDetectorHasExactlyOneRowInDeclarationOrder() {
        List<DetectorType> classified = DetectorTrust.rows().stream().map(DetectorTrust.Row::type).toList();
        List<DetectorType> declared = List.of(DetectorType.values());

        assertEquals(declared, classified,
                "DetectorTrust.ROWS must hold one row per DetectorType, in declaration order. "
                        + "A new detector needs a row; PROMPT is the correct tier until its "
                        + "silent-on-correct-code direction has been measured.");
        assertEquals(declared.size(), DetectorTrust.DETECTOR_COUNT,
                "DetectorTrust.DETECTOR_COUNT is quoted in Javadoc and must equal the enum's length");
    }

    @Test
    @DisplayName("VERDICT is only reachable with named both-directions tests that exist")
    void everyVerdictTierIsBackedByEvidenceThatResolves() {
        List<String> unbacked = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (row.tier() == TrustTier.VERDICT && !EVIDENCE.containsKey(row.type())) {
                unbacked.add(row.type().name());
            }
        }
        assertTrue(unbacked.isEmpty(),
                "VERDICT means a finding proves the code wrong, so it needs a case that fires on the "
                        + "bug and a case that stays silent on the correct twin. No evidence registered for: "
                        + unbacked + ". Either add both tests and register them in EVIDENCE, or classify "
                        + "the detector as PROMPT.");

        for (Map.Entry<DetectorType, List<String>> entry : EVIDENCE.entrySet()) {
            assertEquals(TrustTier.VERDICT, DetectorTrust.tierOf(entry.getKey()),
                    entry.getKey() + " has both-directions evidence registered but is not classified "
                            + "VERDICT. Remove the stale entry or raise the tier.");
            for (String reference : entry.getValue()) {
                assertTestMethodExists(reference);
            }
        }
    }

    @Test
    @DisplayName("each row names the detector class the factories actually construct")
    void detectorClassNamesMatchTheFactories() {
        Map<String, String[]> constructed = parseFactories(read(repoRoot().resolve(FACTORIES)));

        List<String> wrong = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (row.type() == FACTORY_EXEMPT) continue;
            String[] actual = constructed.get(row.type().name());
            if (actual == null) {
                wrong.add(row.type() + ": no factory constructs it");
            } else if (!actual[0].equals(row.detectorClass()) || !actual[1].equals(row.spiName())) {
                wrong.add(row.type() + ": table says " + row.detectorClass() + "/" + row.spiName()
                        + ", factory constructs " + actual[0] + "/" + actual[1]);
            }
        }
        assertTrue(wrong.isEmpty(),
                "A row whose detector class name does not match the constructed detector stops resolving: "
                        + "the report map is keyed by that simple name (DetectorRegistry.ifIssue), so the "
                        + "finding silently loses its tier. Mismatches: " + wrong);
        assertEquals(DetectorType.values().length - 1, constructed.size(),
                "every detector except " + FACTORY_EXEMPT + " is constructed by a legacy factory; a change "
                        + "in that shape means this parse is reading less than it thinks");
    }

    /**
     * Reads the (DetectorType, detector class, SPI name) triples out of the factory source.
     *
     * <p>A plain scan rather than a regular expression on purpose. The pattern needs four escaped
     * parentheses and an escaped quote, which is exactly the kind of line that rots quietly, and
     * this gate exists to catch drift rather than to be clever.
     */
    private static Map<String, String[]> parseFactories(String source) {
        String marker = "new LegacyDetectorAdapter<>(new ";
        String typeToken = "DetectorType.";
        Map<String, String[]> out = new HashMap<>();
        int at = source.indexOf(marker);
        while (at >= 0) {
            int cursor = at + marker.length();
            int classEnd = source.indexOf("()", cursor);
            int typeAt = source.indexOf(typeToken, cursor);
            int quoteOpen = source.indexOf('"', cursor);
            if (classEnd < 0 || typeAt < 0 || quoteOpen < 0) break;
            int typeEnd = source.indexOf(',', typeAt);
            int quoteClose = source.indexOf('"', quoteOpen + 1);
            if (typeEnd < 0 || quoteClose < 0) break;
            out.putIfAbsent(source.substring(typeAt + typeToken.length(), typeEnd).trim(),
                    new String[] {source.substring(cursor, classEnd),
                                  source.substring(quoteOpen + 1, quoteClose)});
            at = source.indexOf(marker, quoteClose);
        }
        return out;
    }

    @Test
    @DisplayName("no lookup key resolves to two different detectors")
    void lookupKeysAreUnambiguous() {
        Map<String, DetectorType> owner = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            for (String key : List.of(row.detectorClass(), row.spiName())) {
                DetectorType previous = owner.putIfAbsent(key, row.type());
                if (previous != null && previous != row.type()) {
                    ambiguous.add(key + " (" + previous + " and " + row.type() + ")");
                }
            }
        }
        assertTrue(ambiguous.isEmpty(),
                "tierOfDetector() resolves a finding by name, so a name owned by two detectors would "
                        + "hand one of them the other's tier: " + ambiguous);
    }

    @Test
    @DisplayName("an unknown detector name resolves to PROMPT, never to a tier nobody measured")
    void unknownDetectorsFallBackToPrompt() {
        assertEquals(TrustTier.PROMPT, DetectorTrust.tierOfDetector("SomeThirdPartyDetector"));
        assertEquals(TrustTier.PROMPT, DetectorTrust.tierOfDetector(null));
        assertEquals(TrustTier.PROMPT, DetectorTrust.tierOf(null));
        assertEquals(TrustTier.VERDICT, DetectorTrust.tierOfDetector("DeadlockDetector"),
                "the report map's key for a built-in is the detector class simple name");
        assertEquals(TrustTier.VERDICT, DetectorTrust.tierOfDetector("Deadlocks"),
                "the SPI path reports the adapter's short name instead");
    }

    /**
     * The detectors that produce findings of different grades, and therefore have to grade them.
     *
     * <p>Each is documented in {@code docs/DETECTOR_CATALOG.md} as verdict-grade on one path and
     * weaker on another. A per-detector tier carries the weakest, so before per-finding grades a
     * gate on {@code minTrust = VERDICT} missed their verdict-grade findings entirely. Dropping the
     * interface from one of these would restore that false negative silently, which is what this
     * list is here to prevent.
     */
    private static final List<String> GRADED_DETECTORS = List.of(
            "RecordMutableComponentLeakDetector",
            "PlatformThreadPerTaskDetector",
            "VirtualThreadPoolingDetector",
            "StaticInitDeadlockDetector",
            "VarHandleNonAtomicUpdateDetector",
            "SharedMemorySegmentRaceDetector",
            "ConfinedArenaThreadEscapeDetector");

    @Test
    @DisplayName("every split-tier detector grades its findings individually")
    void splitTierDetectorsGradeTheirFindings() {
        List<String> ungraded = new ArrayList<>();
        for (String detector : GRADED_DETECTORS) {
            String reportClass = "se.deversity.asynctest.diagnostics." + detector + "$Report";
            try {
                if (!GradedFindings.class.isAssignableFrom(Class.forName(reportClass))) {
                    ungraded.add(detector);
                }
            } catch (ClassNotFoundException e) {
                fail("No report class " + reportClass + ". If the report was renamed, this list and "
                        + "the catalog's trust-tier section both need to follow: " + e);
            }
        }
        assertTrue(ungraded.isEmpty(),
                "These detectors produce a verdict-grade finding and a weaker one, so their reports "
                        + "must implement GradedFindings. Without it the whole detector is judged at "
                        + "its weakest tier and a minTrust = VERDICT gate stays green on findings the "
                        + "library can stand behind: " + ungraded);
    }

    private static void assertTestMethodExists(String reference) {
        int hash = reference.indexOf('#');
        String className = reference.substring(0, hash);
        String methodName = reference.substring(hash + 1);
        try {
            Method method = Class.forName(className).getDeclaredMethod(methodName);
            assertNotNull(method.getAnnotation(Test.class),
                    reference + " is registered as trust-tier evidence but is not a @Test method");
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            fail("Trust-tier evidence " + reference + " does not exist. A VERDICT tier is only as good "
                    + "as the test behind it, so a renamed or deleted case must fail here: " + e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not find the reactor root above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
