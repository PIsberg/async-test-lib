package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.DetectorTrust.Evidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.util.Map.entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks each detector's declared {@link Evidence} class against what its source actually consults
 * (#756).
 *
 * <p><strong>Why this exists.</strong> The class is a column in {@link DetectorTrust}, written by
 * hand, and it caps the trust tier. A detector that gains a lockset keeps the class that held it at
 * PROMPT until someone rereads its row, and one that loses its lockset keeps a {@code CONTEXTUAL}
 * that lets it claim VERDICT on a thread count. Neither made anything else go red.
 *
 * <p><strong>What is derived.</strong> Only whether the detector's class reads synchronization
 * context: one of the lockset verdicts {@code SelfGuard.TrackedInstance} answers (which carry the
 * round, the hand-off and the happens-before edges too), a monitor probe ({@code Thread.holdsLock},
 * {@code SelfGuard.heldOn}), the declared or woven lockset ({@code HeldLocks}), or the
 * happens-before model ({@code HappensBefore}). Recording into a {@code TrackedInstance} without
 * asking it for a verdict is not consulting it. From that one fact two implications are checked:
 *
 * <ul>
 *   <li>a {@code CONTEXTUAL} detector reads context somewhere, since the class says it does;</li>
 *   <li>a detector that reads context is {@code CONTEXTUAL} or {@code OBSERVED} (whose cap is the
 *       same), or names in {@link #WEAKER_PATH} the finding path that decides without it. The class
 *       goes by the weakest path, so a lockset on one path does not earn the class; but a detector
 *       that has one must say why it is not enough, so one that gains a lockset on every path cannot
 *       keep a weaker class unnoticed.</li>
 * </ul>
 *
 * <p><strong>What is not derived.</strong> {@code OBSERVED}, {@code ASSERTED} and
 * {@code HEURISTIC} are not told apart: whether a detector asks the real object for its state or
 * trusts a recorded argument is not a token in its source. The scan reads the detector's own file,
 * nested classes included; a detector that moved its lock query into a helper class would read as
 * consulting nothing, which fails the first rule loudly rather than passing anything wrongly. It is
 * a source pattern, like {@code DetectorStateIsKeyedByIdentityTest}, and reads code only: comments
 * and string literals are removed first, because several detectors explain in prose the lock they
 * do not probe.
 */
class DetectorEvidenceMatchesCodeTest {

    private static final Path DIAGNOSTICS = Path.of("src/main/java/se/deversity/asynctest/diagnostics");

    /** A read of synchronization context, as a call or a member access in code. */
    private static final Pattern CONTEXT = Pattern.compile(
            "\\b(?:sawUnguardedSharing|sawUnguardedRound|sawUnguardedAccess|sharedAndUnguarded"
                    + "|commonLockCount|holdsLock|heldOn)\\s*\\("
                    + "|\\b(?:HeldLocks|HappensBefore)\\s*\\.");

    /**
     * Detectors that read synchronization context on one finding path and are classified below
     * {@code CONTEXTUAL} by another that reads none. Each reason names that other path, so a
     * reviewer can check it against the file. An entry leaves when its detector grades per finding
     * or loses the weaker path; the gate refuses an entry that no longer disagrees.
     */
    private static final Map<DetectorType, String> WEAKER_PATH = Map.ofEntries(
            entry(DetectorType.CALENDAR, "the shared-calendar finding reads the lockset; the "
                    + "calendar-errors finding is any recorded error, on one thread or many"),
            entry(DetectorType.CONCURRENT_MODIFICATIONS, "the iteration and mutation findings read the "
                    + "lockset; a recordModificationDuringIteration call is reported whatever the "
                    + "collection type or lock, on the caller's word"),
            entry(DetectorType.ATOMICITY_VIOLATIONS, "field accesses are judged against the lockset and "
                    + "the happens-before model; a compound operation that read one value and wrote "
                    + "another, and detectCheckThenActViolation, compare values the caller passed"),
            entry(DetectorType.CACHE_CONCURRENCY, "the read/write finding reads the lockset; the "
                    + "stampede finding counts threads that wrote one key in one round, so a HashMap "
                    + "guarded by the caller's own lock draws it"),
            entry(DetectorType.SIMPLE_DATE_FORMAT, "the shared-formatter finding reads the lockset; the "
                    + "formatting-errors finding needs only a recorded error and more than one thread"),
            entry(DetectorType.STRING_BUILDER, "the shared-builder finding reads the lockset; the "
                    + "builder-errors finding needs only a recorded exception and more than one thread "
                    + "in one round"));

    /**
     * How many entries {@link #WEAKER_PATH} may hold. Lower it when an entry leaves; raising it
     * means a detector gained a lockset and kept a weaker class, which is what this gate exists to
     * question.
     */
    private static final int WEAKER_PATH_CEILING = 6;

    @Test
    @DisplayName("every declared evidence class agrees with what the detector's code consults")
    void declaredEvidenceAgreesWithTheCode() {
        List<String> disagreements = disagreements(declared());
        assertTrue(disagreements.isEmpty(),
                "these rows declare an evidence class the detector's code contradicts. Read the "
                        + "detector's record path and analyze(), then either correct the class in "
                        + "DetectorTrust (a class change can move the tier cap: update the tier, the "
                        + "catalog and the CHANGELOG with it) or, for a detector that reads context on "
                        + "one path and not on another, name the other path in WEAKER_PATH: "
                        + disagreements);
    }

    @Test
    @DisplayName("an exemption names a detector that still reads context below CONTEXTUAL, and the list only shrinks")
    void weakerPathExemptionsAreCurrent() {
        Map<DetectorType, Evidence> declared = declared();
        List<String> stale = new ArrayList<>();
        for (DetectorType type : WEAKER_PATH.keySet()) {
            Evidence evidence = declared.get(type);
            if (!consultsContext(type)) {
                stale.add(type + " no longer reads synchronization context");
            } else if (acceptsContext(evidence)) {
                stale.add(type + " is now " + evidence);
            }
        }
        assertTrue(stale.isEmpty(),
                "these WEAKER_PATH entries no longer disagree with their row, so each would only "
                        + "hide the next change to that detector; remove them and lower "
                        + "WEAKER_PATH_CEILING: " + stale);
        assertTrue(WEAKER_PATH.size() <= WEAKER_PATH_CEILING,
                "WEAKER_PATH grew past " + WEAKER_PATH_CEILING + ". A detector that gained a lockset "
                        + "should be reclassified or grade its findings, not be excused: " + WEAKER_PATH.keySet());
    }

    @Test
    @DisplayName("a wrong declaration in either direction is refused")
    void aWrongDeclarationIsRefused() {
        // Both detectors are real and their sources are read as they are; only the declaration is
        // wrong. LOCK_CONTENTION compares a recorded contention ratio with a 20% threshold and reads
        // no lockset, so CONTEXTUAL would let a threshold claim VERDICT. SHARED_CHECKSUM asks its lockset for
        // every finding, so CONTEXT_FREE would hold a lock-aware detector at PROMPT.
        Map<DetectorType, Evidence> wrong = new EnumMap<>(declared());
        wrong.put(DetectorType.LOCK_CONTENTION, Evidence.CONTEXTUAL);
        wrong.put(DetectorType.SHARED_CHECKSUM, Evidence.CONTEXT_FREE);

        List<String> disagreements = disagreements(wrong);

        assertEquals(2, disagreements.size(), String.valueOf(disagreements));
        assertTrue(disagreements.get(0).startsWith("LOCK_CONTENTION"), disagreements.get(0));
        assertTrue(disagreements.get(1).startsWith("SHARED_CHECKSUM"), disagreements.get(1));
    }

    @Test
    @DisplayName("the scan reads code, not comments or string literals")
    void theScanReadsCodeOnly() {
        assertTrue(readsContext("boolean f() { return state.sawUnguardedSharing(); }"));
        assertTrue(readsContext("void g(Object o) { if (Thread.holdsLock(o)) { n++; } }"));
        assertTrue(readsContext("HappensBefore.Stamp s = HappensBefore.current();"));
        assertTrue(readsContext("int[] l = HeldLocks.intersect(c, o, true);"));
        assertFalse(readsContext("// no object reference to probe holdsLock on\nvoid h() {}"),
                "a line comment is not code");
        assertFalse(readsContext("/** Uses {@link HappensBefore}. */ void i() {}"), "javadoc is not code");
        assertFalse(readsContext("String m = \"verify with HeldLocks.declare(...)\";"),
                "a message is not code");
        assertFalse(readsContext("String t = \"\"\"\n  sharedAndUnguarded() in a text block\n  \"\"\";"),
                "a text block is not code");
        assertFalse(readsContext("static final class State extends SelfGuard.TrackedInstance { }"),
                "recording into a tracked instance without asking for its verdict consults nothing");
    }

    /** {@return every row's declared class, keyed by detector} */
    private static Map<DetectorType, Evidence> declared() {
        Map<DetectorType, Evidence> out = new EnumMap<>(DetectorType.class);
        for (DetectorType type : DetectorType.values()) {
            out.put(type, DetectorTrust.evidenceOf(type));
        }
        return out;
    }

    /** {@return one line per row whose declared class the detector's code contradicts, in declaration order} */
    private static List<String> disagreements(Map<DetectorType, Evidence> declared) {
        List<String> out = new ArrayList<>();
        for (DetectorTrust.Row row : DetectorTrust.rows()) {
            Evidence evidence = declared.get(row.type());
            boolean consults = consultsContext(row.type());
            if (evidence == Evidence.CONTEXTUAL && !consults) {
                out.add(row.type() + " is CONTEXTUAL but " + row.detectorClass()
                        + " reads no lockset, monitor probe or happens-before edge");
            } else if (consults && !acceptsContext(evidence) && !WEAKER_PATH.containsKey(row.type())) {
                out.add(row.type() + " is " + evidence + " but " + row.detectorClass()
                        + " reads synchronization context and WEAKER_PATH names no path that does not");
            }
        }
        return out;
    }

    /** {@return whether a detector reading context is consistent with {@code evidence} on its own} */
    private static boolean acceptsContext(Evidence evidence) {
        return evidence == Evidence.CONTEXTUAL || evidence == Evidence.OBSERVED;
    }

    private static DetectorTrust.Row rowOf(DetectorType type) {
        return DetectorTrust.rows().stream()
                .filter(row -> row.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError(type + " has no DetectorTrust row"));
    }

    private static boolean consultsContext(DetectorType type) {
        Path source = DIAGNOSTICS.resolve(rowOf(type).detectorClass() + ".java");
        try {
            return readsContext(Files.readString(source, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + source.toAbsolutePath()
                    + "; this test reads the module's own sources and runs from the module directory", e);
        }
    }

    private static boolean readsContext(String source) {
        return CONTEXT.matcher(codeOnly(source)).find();
    }

    /** {@return {@code source} with comments, string and char literals and text blocks blanked} */
    private static String codeOnly(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                i = end(source.indexOf('\n', i), source);
            } else if (source.startsWith("/*", i)) {
                i = end(source.indexOf("*/", i + 2), source) + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                i = end(source.indexOf("\"\"\"", i + 3), source) + 3;
                code.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i = closingQuote(source, i) + 1;
                code.append("\"\"");
            } else {
                code.append(c);
                i++;
            }
        }
        return code.toString();
    }

    private static int end(int index, String source) {
        return index < 0 ? source.length() : index;
    }

    /** {@return the index of the quote closing the string or char literal opened at {@code open}} */
    private static int closingQuote(String source, int open) {
        char quote = source.charAt(open);
        for (int i = open + 1; i < source.length(); i++) {
            if (source.charAt(i) == '\\') {
                i++;
            } else if (source.charAt(i) == quote) {
                return i;
            }
        }
        return source.length();
    }
}
