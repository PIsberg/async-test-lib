package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.GradedFindings;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.diagnostics.TrustTier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
            entry(DetectorType.COMPLETABLE_FUTURE_COMPLETION_LEAKS, List.of(
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#completionLeakDetectorFiresOnAFutureThatIsNeverCompleted",
                    "se.deversity.asynctest.diagnostics.DetectorAccuracyEvalTest#completionLeakDetectorStaysSilentWhenTheFutureIsCompleted"))
    );
    /** Source of truth for what each detector type constructs: the registry's factory table (#916). */
    private static final String REGISTRY =
            "async-test-lib/src/main/java/se/deversity/asynctest/DetectorRegistry.java";

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
        Map<DetectorType, List<String>> corpus = corpusEvidence();
        List<String> unbacked = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            if (row.tier() == TrustTier.VERDICT
                    && !EVIDENCE.containsKey(row.type())
                    && !corpus.containsKey(row.type())) {
                unbacked.add(row.type().name());
            }
        }
        assertTrue(unbacked.isEmpty(),
                "VERDICT means a finding proves the code wrong, so it needs a case that fires on the "
                        + "bug and a case that stays silent on the correct twin. No evidence registered for: "
                        + unbacked + ". Either add both tests and register them in EVIDENCE, add a "
                        + "corpus pair and a line in " + CORPUS_EVIDENCE_RESOURCE + ", or classify "
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

    /**
     * A tier is capped by what the detector decides from, so a pair cannot promote past it.
     *
     * <p>The both-directions rule above is necessary and was never sufficient. A detector whose
     * finding is the author's own {@code record*} call fires on the body that makes the call and
     * stays silent on the one that does not, and so does one that counts threads or compares a
     * number with a threshold, given a pair on the right side of it. Such a pair is real evidence
     * that the detector separates two bodies, and no evidence that a finding means the code is
     * wrong. {@link DetectorTrust.Evidence} names what a detector decides from, and this is the
     * check that a row does not claim more than that can carry.
     */
    @Test
    @DisplayName("no row claims a tier its evidence class cannot carry")
    void everyTierIsWithinTheCapOfItsEvidence() {
        List<String> over = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            DetectorTrust.Evidence evidence = DetectorTrust.evidenceOf(row.type());
            if (row.tier().compareTo(evidence.cap()) > 0) {
                over.add(row.type() + " is " + row.tier() + " on " + evidence
                        + " evidence, capped at " + evidence.cap());
            }
        }
        assertTrue(over.isEmpty(),
                "VERDICT needs OBSERVED or CONTEXTUAL evidence, ASSERTED evidence caps at FACT, and "
                        + "CONTEXT_FREE or HEURISTIC evidence at PROMPT. A pair that separates a "
                        + "recorded bug from an unrecorded one does not lift the cap; changing what "
                        + "the detector decides from does. Over the cap: " + over);
    }

    /**
     * The catalog states how many detectors sit at each tier, and nothing compared that sentence
     * with the table, and it is exactly the kind of number a tier change moves: the evidence caps
     * took 32 of its 69 VERDICT rows down in one change.
     */
    @Test
    @DisplayName("the catalog's tier split is the one the table gives")
    void catalogTierSplitMatchesTheTable() {
        Map<TrustTier, Integer> counts = new EnumMap<>(TrustTier.class);
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            counts.merge(row.tier(), 1, Integer::sum);
        }
        String expected = String.format(Locale.ROOT, "The split is %d VERDICT, %d PROMPT, %d FACT and %d ADVISORY",
                counts.getOrDefault(TrustTier.VERDICT, 0), counts.getOrDefault(TrustTier.PROMPT, 0),
                counts.getOrDefault(TrustTier.FACT, 0), counts.getOrDefault(TrustTier.ADVISORY, 0));
        String catalog = read(repoRoot().resolve("docs/DETECTOR_CATALOG.md")).replaceAll("\\s+", " ");
        assertTrue(catalog.contains(expected),
                "docs/DETECTOR_CATALOG.md must say \"" + expected + "\", which is what DetectorTrust holds");
    }

    @Test
    @DisplayName("the evidence caps are the ones the tier definitions state")
    void evidenceCapsMatchTheTierDefinitions() {
        assertEquals(TrustTier.VERDICT, DetectorTrust.Evidence.OBSERVED.cap());
        assertEquals(TrustTier.VERDICT, DetectorTrust.Evidence.CONTEXTUAL.cap());
        assertEquals(TrustTier.FACT, DetectorTrust.Evidence.ASSERTED.cap());
        assertEquals(TrustTier.PROMPT, DetectorTrust.Evidence.CONTEXT_FREE.cap());
        assertEquals(TrustTier.PROMPT, DetectorTrust.Evidence.HEURISTIC.cap());
        assertEquals(DetectorTrust.Evidence.HEURISTIC, DetectorTrust.evidenceOf(null),
                "an unknown detector gets the weakest class, whose cap is the PROMPT tierOf gives it");
        assertEquals(TrustTier.PROMPT, DetectorTrust.capOfDetector("SomeThirdPartyDetector"));
    }

    @Test
    @DisplayName("a grade above its detector's cap is lowered to the cap, and nothing else changes")
    void clampLowersOnlyTheGradesAboveTheCap() {
        GradedFindings.Grade verdict = new GradedFindings.Grade(IssueSeverity.CRITICAL, TrustTier.VERDICT, "closed");
        GradedFindings.Grade prompt = new GradedFindings.Grade(IssueSeverity.MEDIUM, TrustTier.PROMPT, "owner");

        List<GradedFindings.Grade> clamped =
                DetectorTrust.clampToCap("SharedMemorySegmentRaceDetector", List.of(verdict, prompt));
        assertEquals(List.of(new GradedFindings.Grade(IssueSeverity.CRITICAL, TrustTier.FACT, "closed"), prompt),
                clamped, "an ASSERTED detector's VERDICT grade becomes FACT; severity and summary stay");

        List<GradedFindings.Grade> within = List.of(verdict, prompt);
        assertSame(within, DetectorTrust.clampToCap("RecordMutableComponentLeakDetector", within),
                "an OBSERVED detector's grades are within its cap and pass through untouched");
        assertEquals(TrustTier.PROMPT,
                DetectorTrust.clampToCap("SomeThirdPartyDetector", List.of(verdict)).get(0).tier(),
                "a detector the table does not know is capped at the PROMPT it resolves to");
    }

    /**
     * A detector classified by its strongest path must not lend that path's cap to a finding its
     * weaker path produced (#753). Before grades named their evidence the cap was per detector,
     * so a detector with one JVM-answered path and one recorded path had to be classified by the
     * recorded one, and the JVM-answered verdicts were clamped to FACT with it.
     */
    @Test
    @DisplayName("a grade is capped by the evidence it names as well as by its detector's")
    void clampActsPerGradeOnTheEvidenceEachNames() {
        GradedFindings.Grade recorded = new GradedFindings.Grade(IssueSeverity.CRITICAL, TrustTier.VERDICT,
                "closed", DetectorTrust.Evidence.ASSERTED);
        GradedFindings.Grade observed = new GradedFindings.Grade(IssueSeverity.CRITICAL, TrustTier.VERDICT,
                "refused", DetectorTrust.Evidence.OBSERVED);

        assertEquals(List.of(new GradedFindings.Grade(IssueSeverity.CRITICAL, TrustTier.FACT, "closed",
                                DetectorTrust.Evidence.ASSERTED), observed),
                DetectorTrust.clampToCap("RecordMutableComponentLeakDetector", List.of(recorded, observed)),
                "under an OBSERVED detector the recorded grade is capped at FACT and the observed one passes");
        assertEquals(TrustTier.FACT,
                DetectorTrust.clampToCap("SharedMemorySegmentRaceDetector", List.of(observed)).get(0).tier(),
                "a grade's own evidence can only lower its detector's cap, never lift it");
        assertEquals(TrustTier.PROMPT,
                DetectorTrust.clampToCap("SomeThirdPartyDetector", List.of(observed)).get(0).tier(),
                "a third-party grade claiming OBSERVED stays at the PROMPT an unknown detector gets");
    }

    @Test
    @DisplayName("each row names the detector class the registry actually constructs")
    void detectorClassNamesMatchTheRegistry() {
        Map<String, String> constructed = parseRegistry(read(repoRoot().resolve(REGISTRY)));

        List<String> wrong = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            String actual = constructed.get(row.type().name());
            if (actual == null) {
                wrong.add(row.type() + ": the registry has no factory row for it");
            } else if (!actual.equals(row.detectorClass())) {
                wrong.add(row.type() + ": table says " + row.detectorClass() + ", registry constructs " + actual);
            }
        }
        assertTrue(wrong.isEmpty(),
                "A row whose detector class name does not match the constructed detector stops resolving: "
                        + "the report map is keyed by that simple name (DetectorRegistry.ifIssue), so the "
                        + "finding silently loses its tier. Mismatches: " + wrong);
        assertEquals(DetectorType.values().length, constructed.size(),
                "every detector is constructed by one registry row; a change in that shape means this "
                        + "parse is reading less than it thinks");
    }

    /**
     * Reads the (DetectorType, detector class) pairs out of the registry's factory rows,
     * {@code field = create(DetectorType.TYPE, DetectorClass::new);}.
     */
    private static Map<String, String> parseRegistry(String source) {
        Map<String, String> out = new HashMap<>();
        java.util.regex.Matcher row = Pattern
                .compile("(?m)^\\s+\\w+\\s*= create\\(DetectorType\\.(\\w+),\\s*(\\w+)::new\\)")
                .matcher(source);
        while (row.find()) {
            out.putIfAbsent(row.group(1), row.group(2));
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
     * <p>Each is documented in {@code docs/DETECTOR_CATALOG.md} as graded higher on one path than
     * on another. A per-detector tier carries the weakest, so before per-finding grades a
     * gate on {@code minTrust = VERDICT} or {@code FACT} missed their stronger findings entirely. Dropping the
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
            "ConfinedArenaThreadEscapeDetector",
            // #754: a primary finding beside a threshold, a recorded error or an opt-in count.
            "LockLeakDetector",
            "BlockingQueueDetector",
            "ThreadLeakDetector",
            "CalendarDetector",
            "SimpleDateFormatDetector",
            "StringBuilderDetector",
            // #817: an A-B-A the agent took inside each operation beside one recorded by hand.
            "ABAProblemDetector");

    @Test
    @DisplayName("every split-tier detector grades its findings individually")
    void splitTierDetectorsGradeTheirFindings() {
        List<String> ungraded = new ArrayList<>();
        for (String detector : GRADED_DETECTORS) {
            String detectorClass = "se.deversity.asynctest.diagnostics." + detector;
            try {
                // The report is whatever analyze() returns, which is not always a class named Report.
                Class<?> report = Class.forName(detectorClass).getMethod("analyze").getReturnType();
                if (!GradedFindings.class.isAssignableFrom(report)) {
                    ungraded.add(detector);
                }
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                fail("No analyze() on " + detectorClass + ". If the detector or its report was "
                        + "renamed, this list and the catalog's trust-tier section both need to follow: " + e);
            }
        }
        assertTrue(ungraded.isEmpty(),
                "These detectors produce a stronger finding and a weaker one, so their reports "
                        + "must implement GradedFindings. Without it the whole detector is judged at "
                        + "its weakest tier and a minTrust gate stays green on findings the "
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


    /**
     * The resource that carries VERDICT evidence living outside this module's test sources.
     *
     * <p>{@link #EVIDENCE} is resolved by reflection, which reaches only test methods compiled
     * here. The corpus eval holds pairs of the same shape on unmodified third-party code, in a
     * downstream module that depends on this one and therefore cannot be depended on back. The
     * file is the seam: this gate requires every line in it to name a detector actually classified
     * VERDICT, and {@code CorpusGates} in that module requires every line to name two recording
     * subjects that exist and carry the stated expectations. Neither half can drift without a
     * build going red.
     */
    private static final String CORPUS_EVIDENCE_RESOURCE =
            "/META-INF/async-test/verdict-evidence-corpus";

    /** {@return the text of {@link #CORPUS_EVIDENCE_RESOURCE}} */
    private static String corpusEvidenceText() {
        try (java.io.InputStream in =
                     DetectorTrustCoverageTest.class.getResourceAsStream(CORPUS_EVIDENCE_RESOURCE)) {
            assertNotNull(in, CORPUS_EVIDENCE_RESOURCE + " is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + CORPUS_EVIDENCE_RESOURCE, e);
        }
    }

    /** {@return the corpus-backed evidence, detector to its two subject ids, fire first} */
    private static Map<DetectorType, List<String>> corpusEvidence() {
        Map<DetectorType, List<String>> parsed = new HashMap<>();
        for (String raw : corpusEvidenceText().split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            assertTrue(equals > 0, "malformed line in " + CORPUS_EVIDENCE_RESOURCE + ": " + line);
            String detector = line.substring(0, equals).strip();
            List<String> subjects = java.util.Arrays.stream(line.substring(equals + 1).split(","))
                    .map(String::strip)
                    .filter(part -> !part.isEmpty())
                    .toList();
            parsed.put(DetectorType.valueOf(detector), subjects);
        }
        return parsed;
    }

    @Test
    @DisplayName("every corpus-backed VERDICT line names a VERDICT detector and both directions")
    void corpusEvidenceIsWellFormedAndPointsAtVerdictRows() {
        Map<DetectorType, List<String>> corpus = corpusEvidence();
        assertFalse(corpus.isEmpty(),
                CORPUS_EVIDENCE_RESOURCE + " parsed to nothing, so the promotions it backs are "
                        + "unbacked and this gate is vouching for them by accident");

        for (Map.Entry<DetectorType, List<String>> entry : corpus.entrySet()) {
            assertEquals(2, entry.getValue().size(),
                    entry.getKey() + " must name exactly two corpus subjects, the one that fires "
                            + "on the bug and the one that stays silent on the twin. One direction "
                            + "alone proves nothing: a detector that fires on everything passes "
                            + "the first and one that was never wired up passes the second. Found: "
                            + entry.getValue());
            assertEquals(TrustTier.VERDICT, DetectorTrust.tierOf(entry.getKey()),
                    entry.getKey() + " has corpus evidence registered but is not classified "
                            + "VERDICT. Remove the stale line or raise the tier.");
            assertFalse(EVIDENCE.containsKey(entry.getKey()),
                    entry.getKey() + " is backed twice, here and in EVIDENCE. Keep the in-repo "
                            + "pair, which this gate can resolve, and drop the corpus line.");
        }
    }

    /** A {@code # held: TYPE} line: the paragraph under it says why the model keeps TYPE out. */
    private static final Pattern HELD_LINE = Pattern.compile("#\\s*held:\\s*([A-Z][A-Z0-9_]*)");

    /** A commented-out registration kept with the evidence class that capped it. */
    private static final Pattern CAPPED_LINE = Pattern.compile(
            "#\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*[^,\\s]+\\s*,\\s*[^,\\s]+\\s+\\[([A-Z_]+)\\]");

    /**
     * The evidence file names detectors it deliberately leaves out, and until #818 it did so only
     * in prose: its header argued that CONCURRENT_MAP_CHECK_THEN_ACT could not back a VERDICT while
     * a line further down registered it and the trust table rated it VERDICT, and nothing read the
     * header. The absences are now lines this parses, and a detector named as absent may be
     * neither registered nor classified VERDICT, so the argument and the tier cannot disagree.
     */
    @Test
    @DisplayName("a detector the corpus evidence file holds out is neither registered nor VERDICT")
    void aDetectorNamedAsAbsentIsNeitherRegisteredNorVerdict() {
        Map<DetectorType, List<String>> registered = corpusEvidence();
        Map<DetectorType, String> absent = new EnumMap<>(DetectorType.class);
        int held = 0;
        int capped = 0;
        for (String raw : corpusEvidenceText().split("\n")) {
            String line = raw.strip();
            Matcher heldLine = HELD_LINE.matcher(line);
            Matcher cappedLine = CAPPED_LINE.matcher(line);
            if (heldLine.matches()) {
                absent.put(DetectorType.valueOf(heldLine.group(1)), "held on its model");
                held++;
            } else if (cappedLine.matches()) {
                absent.put(DetectorType.valueOf(cappedLine.group(1)),
                        "kept as capped at " + cappedLine.group(2));
                capped++;
            }
        }
        assertTrue(held > 0 && capped > 0,
                CORPUS_EVIDENCE_RESOURCE + " parsed to " + held + " held and " + capped + " capped "
                        + "lines, so this gate is reading a format the file no longer uses");

        List<String> contradictions = new ArrayList<>();
        for (Map.Entry<DetectorType, String> entry : absent.entrySet()) {
            DetectorType type = entry.getKey();
            if (registered.containsKey(type)) {
                contradictions.add(type + " is " + entry.getValue() + " and also registered");
            } else if (DetectorTrust.tierOf(type) == TrustTier.VERDICT) {
                contradictions.add(type + " is " + entry.getValue() + " and DetectorTrust rates it VERDICT");
            }
        }
        assertTrue(contradictions.isEmpty(),
                CORPUS_EVIDENCE_RESOURCE + " argues these detectors out of VERDICT and vouches for "
                        + "them at the same time. Re-read the argument against the detector: if it "
                        + "still holds, remove the registration and lower the tier; if it no longer "
                        + "does, rewrite the paragraph to say what changed and drop the held or "
                        + "commented line: " + contradictions);
    }

    /**
     * A detector's structured findings name it the way {@link DetectorTrust} resolves it (#930).
     *
     * <p>{@code Violation.detector()} on a detector's own {@code structuredViolations} is a string
     * literal in its source. 21 detectors used a third spelling, neither the class name nor the
     * alias the table also accepts ({@code "BusyWait"} beside {@code "BusyWaiting"}), so
     * {@code tierOfDetector(v.detector())} answered PROMPT for them whatever their tier, and
     * {@code typeOfDetector} answered empty.
     */
    @Test
    @DisplayName("every structured violation names a detector DetectorTrust resolves to its own type")
    void everyStructuredViolationResolvesToItsOwnDetector() {
        Path diagnostics = repoRoot().resolve("async-test-lib/src/main/java/se/deversity/asynctest/diagnostics");
        Pattern literal = Pattern.compile("new Violation\\(\\s*\"(\\w+)\"");
        List<String> wrong = new ArrayList<>();
        int scanned = 0;
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            Path source = diagnostics.resolve(row.detectorClass() + ".java");
            if (!Files.isRegularFile(source)) {
                continue;
            }
            Matcher m = literal.matcher(read(source));
            while (m.find()) {
                scanned++;
                String name = m.group(1);
                if (!DetectorTrust.typeOfDetector(name).equals(java.util.Optional.of(row.type()))) {
                    wrong.add(row.detectorClass() + " reports as \"" + name + "\" (resolves to "
                            + DetectorTrust.typeOfDetector(name).map(Enum::name).orElse("nothing")
                            + ", alias is \"" + row.spiName() + "\")");
                }
            }
        }
        assertTrue(scanned > 100, "expected the scan to find the detectors' Violation literals, found " + scanned);
        assertTrue(wrong.isEmpty(), wrong.size() + " detectors name their structured findings with a "
                + "string DetectorTrust does not resolve to their own type, so a consumer asking for "
                + "the tier of one of those findings gets PROMPT:\n  " + String.join("\n  ", wrong));
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
