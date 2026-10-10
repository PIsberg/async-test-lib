package se.deversity.asynctest.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorFeed;
import se.deversity.asynctest.diagnostics.DetectorFeeds;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.DetectorTrust.Evidence;
import se.deversity.asynctest.diagnostics.TrustTier;

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
 * <p><strong>What is derived.</strong> Four facts about a detector, each read from its own source
 * or from {@link DetectorFeeds}:
 *
 * <ul>
 *   <li>whether it reads synchronization context: one of the lockset verdicts
 *       {@code SelfGuard.TrackedInstance} answers (which carry the round, the hand-off and the
 *       happens-before edges too), a monitor probe ({@code Thread.holdsLock},
 *       {@code SelfGuard.heldOn}), the declared or woven lockset ({@code HeldLocks}), or the
 *       happens-before model ({@code HappensBefore}). Recording into a {@code TrackedInstance}
 *       without asking it for a verdict is not consulting it;</li>
 *   <li>whether it asks a real JVM object for its state: one of the JDK queries in {@link #PROBE},
 *       such as {@code ReentrantLock.isLocked}, {@code Thread.isAlive}, {@code CyclicBarrier.isBroken},
 *       a {@code ThreadMXBean} dump, a {@code StackWalker} or reflection on the live instance;</li>
 *   <li>whether something other than the test's record calls feeds it: the agent's woven streams
 *       or the JVM and the harness ({@link DetectorFeed#AGENT}, {@link DetectorFeed#ZERO_CONFIG});</li>
 *   <li>whether it grades its findings, and which evidence classes those grades name; and whether
 *       it names a threshold, an identifier containing {@code threshold}.</li>
 * </ul>
 *
 * <p>From those, seven implications are checked. The class goes by the weakest finding path, except
 * in a report that grades its findings, where it goes by the strongest grade; each implication
 * that can be broken by a weaker path has a shrink-only exemption list naming that path.
 *
 * <ol>
 *   <li>A {@code CONTEXTUAL} detector reads context somewhere, since the class says it does.</li>
 *   <li>A detector that reads context is {@code CONTEXTUAL} or {@code OBSERVED} (whose cap is the
 *       same), or names in {@link #WEAKER_PATH} the finding path that decides without it. A lockset
 *       on one path does not earn the class; but a detector that has one must say why it is not
 *       enough, so one that gains a lockset on every path cannot keep a weaker class unnoticed.</li>
 *   <li>An {@code OBSERVED} detector is fed by the agent or the JVM, or asks a real object for its
 *       state. A detector that only reads what the test recorded cannot be observing anything.</li>
 *   <li>A detector fed by the agent or the JVM is {@code OBSERVED} or {@code CONTEXTUAL}, or names in
 *       {@link #FED_BELOW_OBSERVED} the path that keeps it lower: a record method the feed does not
 *       replace, or a threshold over what the feed delivers. This is the rule the catalog states as
 *       "an agent-fed detector is classified by its woven feed", with its exceptions written down.</li>
 *   <li>A detector that grades its findings has a grade naming its declared class, since that class
 *       is the one behind its strongest grade.</li>
 *   <li>A detector that names a threshold and does not grade its findings is capped at PROMPT
 *       ({@code HEURISTIC} or {@code CONTEXT_FREE}), since the threshold path is its weakest, or
 *       names in {@link #THRESHOLD_DECIDES_NO_FINDING} why the threshold never reaches a finding.</li>
 *   <li>A {@code HEURISTIC} detector names its threshold, since a threshold over what was recorded
 *       is what the class says decides it (#756). Written as a bare literal, the gate could not
 *       tell it from an {@code ASSERTED} or {@code CONTEXT_FREE} row, and rule 6 could not see it.</li>
 * </ol>
 *
 * <p><strong>What is not derived.</strong> A JDK query in a detector below {@code OBSERVED} is not
 * refused: many ask the recording thread whether it is virtual, or probe one path while a recorded
 * one decides another, so a probe does not show that the finding is observed. A threshold written
 * as a bare literal is not seen, which is why rule 7 requires a {@code HEURISTIC} row to name one;
 * {@code ASSERTED} is told apart from {@code HEURISTIC} only by a named threshold (rule 6) and from
 * {@code CONTEXT_FREE} not at all: whether a finding is the record call itself or a thread count
 * over records is not a token. The scan reads the detector's own file, nested classes included; a detector that
 * moved its lock query or its probe into a helper class would read as consulting nothing, which
 * fails rules 1 and 3 loudly rather than passing anything wrongly. It is a source pattern, like
 * {@code DetectorStateIsKeyedByIdentityTest}, and reads code only: comments and string literals are
 * removed first, because several detectors explain in prose the lock they do not probe.
 */
class DetectorEvidenceMatchesCodeTest {

    private static final Path DIAGNOSTICS = Path.of("src/main/java/se/deversity/asynctest/diagnostics");

    /** A read of synchronization context, as a call or a member access in code. */
    private static final Pattern CONTEXT = Pattern.compile(
            "\\b(?:sawUnguardedSharing|sawUnguardedRound|sawUnguardedAccess|sharedAndUnguarded"
                    + "|commonLockCount|holdsLock|heldOn)\\s*\\("
                    + "|\\b(?:HeldLocks|HappensBefore)\\s*\\.");

    /**
     * A JDK query that asks a live lock, synchronizer, thread, future, executor or class for its
     * state, as a call in code, or a {@code StackWalker}. Names generic enough to be a detector's
     * own accessor ({@code getState}, {@code getCount}) are left out. A new {@code OBSERVED}
     * detector whose probe is missing here fails rule 3; add the query, not an exemption.
     */
    private static final Pattern PROBE = Pattern.compile(
            "\\b(?:isLocked|isHeldByCurrentThread|getHoldCount|hasQueuedThreads?|getQueueLength"
                    + "|getWaitQueueLength|hasWaiters|isWriteLocked|isReadLocked|isWriteLockedByCurrentThread"
                    + "|getReadHoldCount|getReadLockCount|availablePermits|getNumberWaiting|isBroken"
                    + "|getRegisteredParties|getUnarrivedParties|getArrivedParties|holdsLock"
                    + "|isAlive|isDaemon|isVirtual|getUncaughtExceptionHandler|getThreadGroup"
                    + "|getAllStackTraces|activeCount|getThreadMXBean|findDeadlockedThreads|dumpAllThreads"
                    + "|getThreadInfo|isDone|isCompletedExceptionally|isCancelled|whenComplete"
                    + "|getThreadFactory|getMaximumPoolSize|isShutdown|isTerminated|remainingCapacity"
                    + "|getDeclaredFields?|getModifiers|getRecordComponents|getAccessor|isAccessibleBy"
                    + "|intern)\\s*\\("
                    + "|\\bStackWalker\\b");

    /** A threshold named in code: any identifier containing the word. */
    private static final Pattern THRESHOLD = Pattern.compile("(?i)threshold");

    /** A grade construction in code: the start of a {@code GradedFindings.Grade} argument list. */
    private static final Pattern GRADE = Pattern.compile("\\bnew\\s+(?:GradedFindings\\s*\\.\\s*)?Grade\\s*\\(");

    /**
     * Detectors that read synchronization context on one finding path and are classified below
     * {@code CONTEXTUAL} by another that reads none. Each reason names that other path, so a
     * reviewer can check it against the file. An entry leaves when its detector grades per finding
     * or loses the weaker path; the gate refuses an entry that no longer disagrees.
     */
    private static final Map<DetectorType, String> WEAKER_PATH = Map.ofEntries(
            entry(DetectorType.CONCURRENT_MODIFICATIONS, "the iteration and mutation findings read the "
                    + "lockset; a recordModificationDuringIteration call is reported whatever the "
                    + "collection type or lock, on the caller's word"),
            entry(DetectorType.ATOMICITY_VIOLATIONS, "field accesses are judged against the lockset and "
                    + "the happens-before model; a compound operation that read one value and wrote "
                    + "another, and detectCheckThenActViolation, compare values the caller passed"),
            entry(DetectorType.CACHE_CONCURRENCY, "the read/write finding reads the lockset; the "
                    + "stampede finding counts threads that wrote one key in one round, so a HashMap "
                    + "guarded by the caller's own lock draws it"));

    /**
     * How many entries {@link #WEAKER_PATH} may hold. Lower it when an entry leaves; raising it
     * means a detector gained a lockset and kept a weaker class, which is what this gate exists to
     * question.
     */
    private static final int WEAKER_PATH_CEILING = 3;

    /**
     * Detectors the agent or the JVM feeds that are classified below {@code OBSERVED} and
     * {@code CONTEXTUAL}, each with the path that holds them there. An entry leaves when the path
     * goes or the detector grades that path's findings apart from the fed one's; the gate refuses
     * an entry that no longer disagrees.
     */
    private static final Map<DetectorType, String> FED_BELOW_OBSERVED = Map.ofEntries(
            entry(DetectorType.ATOMICITY_VIOLATIONS, "the woven field accesses are judged against the "
                    + "lockset, while a recorded compound operation or detectCheckThenActViolation "
                    + "compares values the caller passed"),
            entry(DetectorType.BLOCKING_QUEUE, "the feed sees the offer, but its primary finding, a "
                    + "discarded false, is an event no probe of the queue at analysis confirms and a "
                    + "lossy queue makes by design, so its strongest grade is FACT on ASSERTED "
                    + "evidence; the 90% saturation grade is HEURISTIC"),
            entry(DetectorType.LIVELOCKS, "the runner feeds it thread dumps, but starvation is every "
                    + "recent snapshot BLOCKED or WAITING with flat CPU time and rapid cycling is five "
                    + "state changes in ten snapshots: thresholds over what the JVM showed"),
            entry(DetectorType.STATIC_INIT_DEADLOCK, "the live stack sample is graded FACT on OBSERVED "
                    + "evidence, since one slow initializer looks the same, and the cycle of recorded "
                    + "init requests is FACT on ASSERTED; with no grade above FACT it keeps ASSERTED"),
            entry(DetectorType.VIRTUAL_THREAD_PINNING, "the JFR feed reaches virtual workers only, "
                    + "and recordPinningEvent still adds a pin described by the caller, judged by its "
                    + "description; the report does not grade the two apart, so the row keeps "
                    + "ASSERTED until it does"));

    /** How many entries {@link #FED_BELOW_OBSERVED} may hold; lower it when an entry leaves. */
    private static final int FED_BELOW_OBSERVED_CEILING = 5;

    /**
     * Ungraded detectors above the PROMPT cap that name a threshold, each with why the threshold
     * decides no finding. All four print a warning section when a count crosses it, and their
     * report's {@code hasIssues()} does not read that section, so the warning never reaches the
     * {@code failOn} gate or the tier. An entry leaves when its warning becomes a finding (the
     * detector is then {@code HEURISTIC}, or grades the warning apart) or the threshold goes.
     */
    private static final Map<DetectorType, String> THRESHOLD_DECIDES_NO_FINDING = Map.ofEntries(
            entry(DetectorType.VIRTUAL_THREAD_CONTEXT_LEAKS, "HIGH_THREAD_LOCAL_COUNT_THRESHOLD fills "
                    + "highCountWarnings; hasIssues() reads the leaks and the inheritable-in-virtual issues only"),
            entry(DetectorType.SCOPED_VALUE, "HIGH_BINDING_COUNT_THRESHOLD fills highBindingWarnings; "
                    + "hasIssues() reads the unbound-get and rebind issues only"),
            entry(DetectorType.STABLE_VALUE_MISUSE, "SET_CONTENTION_THRESHOLD fills contentionWarnings; "
                    + "hasIssues() reads the read-before-set, double-set and reentrant issues only"),
            entry(DetectorType.LAZY_CONSTANT_MISUSE, "CONVOY_THRESHOLD fills convoyWarnings; hasIssues() "
                    + "reads the reentrant, null-value, multiple-compute and non-deterministic issues "
                    + "only"));

    /** How many entries {@link #THRESHOLD_DECIDES_NO_FINDING} may hold; lower it when an entry leaves. */
    private static final int THRESHOLD_DECIDES_NO_FINDING_CEILING = 4;

    /** Joins the reasons one row gives more than one rule to refuse it. */
    private static final String ALSO = "; also ";

    /** Each detector's source with comments and literals blanked, read once. */
    private static final Map<DetectorType, String> CODE = new EnumMap<>(DetectorType.class);

    @Test
    @DisplayName("every declared evidence class agrees with what the detector's code consults")
    void declaredEvidenceAgreesWithTheCode() {
        List<String> disagreements = disagreements(declared());
        assertTrue(disagreements.isEmpty(),
                "these rows declare an evidence class the detector's code contradicts. Read the "
                        + "detector's record path and analyze(), then either correct the class in "
                        + "DetectorTrust (a class change can move the tier cap: update the tier, the "
                        + "catalog and the CHANGELOG with it) or, for a detector that decides on one "
                        + "path differently from another, name the other path in the exemption list "
                        + "the line points at: " + disagreements);
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
    @DisplayName("a feed or threshold exemption names a row that still needs it, and each list only shrinks")
    void feedAndThresholdExemptionsAreCurrent() {
        Map<DetectorType, Evidence> declared = declared();
        List<String> stale = new ArrayList<>();
        for (DetectorType type : FED_BELOW_OBSERVED.keySet()) {
            if (!fedWithoutRecording(type)) {
                stale.add(type + " is no longer fed by the agent or the JVM");
            } else if (acceptsContext(declared.get(type))) {
                stale.add(type + " is now " + declared.get(type));
            }
        }
        for (DetectorType type : THRESHOLD_DECIDES_NO_FINDING.keySet()) {
            if (!namesThreshold(type)) {
                stale.add(type + " names no threshold");
            } else if (grades(type)) {
                stale.add(type + " grades its findings");
            } else if (!aboveThePromptCap(declared.get(type))) {
                stale.add(type + " is now " + declared.get(type));
            }
        }
        assertTrue(stale.isEmpty(),
                "these FED_BELOW_OBSERVED or THRESHOLD_DECIDES_NO_FINDING entries no longer disagree "
                        + "with their row, so each would only hide the next change to that detector; "
                        + "remove them and lower the list's ceiling: " + stale);
        assertTrue(FED_BELOW_OBSERVED.size() <= FED_BELOW_OBSERVED_CEILING,
                "FED_BELOW_OBSERVED grew past " + FED_BELOW_OBSERVED_CEILING + ". A detector the agent "
                        + "now feeds should be classified by its feed or grade the fed path apart, not "
                        + "be excused: " + FED_BELOW_OBSERVED.keySet());
        assertTrue(THRESHOLD_DECIDES_NO_FINDING.size() <= THRESHOLD_DECIDES_NO_FINDING_CEILING,
                "THRESHOLD_DECIDES_NO_FINDING grew past " + THRESHOLD_DECIDES_NO_FINDING_CEILING + ". A "
                        + "detector that gained a threshold is HEURISTIC or grades that finding apart: "
                        + THRESHOLD_DECIDES_NO_FINDING.keySet());
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
    @DisplayName("a wrong declaration under each feed, grade and threshold rule is refused by that rule alone")
    void aWrongDeclarationUnderEachLaterRuleIsRefused() {
        // Real detectors, sources as they are, each declaration within its row's tier cap so that
        // DetectorTrustCoverageTest would pass it. DOUBLE_CHECKED_LOCKING is fed only by record
        // calls and queries no JVM object, so OBSERVED would be a claim about nothing it saw.
        // EXPLICIT_GC is woven at every System.gc call site, so ASSERTED would keep a feed that
        // sees the call below its class without a reason. VIRTUAL_THREAD_POOLING grades its
        // findings OBSERVED and ASSERTED, so HEURISTIC names no grade it makes. BUSY_WAITING
        // decides by SPIN_THRESHOLD_ITERATIONS, so ASSERTED would lift a threshold to FACT.
        Map<DetectorType, Evidence> wrong = new EnumMap<>(declared());
        wrong.put(DetectorType.DOUBLE_CHECKED_LOCKING, Evidence.OBSERVED);
        wrong.put(DetectorType.EXPLICIT_GC, Evidence.ASSERTED);
        wrong.put(DetectorType.VIRTUAL_THREAD_POOLING, Evidence.HEURISTIC);
        wrong.put(DetectorType.BUSY_WAITING, Evidence.ASSERTED);

        List<String> disagreements = disagreements(wrong);

        assertEquals(4, disagreements.size(), String.valueOf(disagreements));
        assertOneRule(disagreements.get(0), "DOUBLE_CHECKED_LOCKING", "fed only by record calls");
        assertOneRule(disagreements.get(1), "BUSY_WAITING", "names a threshold");
        assertOneRule(disagreements.get(2), "EXPLICIT_GC", "FED_BELOW_OBSERVED");
        assertOneRule(disagreements.get(3), "VIRTUAL_THREAD_POOLING", "no grade names HEURISTIC");
    }

    /**
     * A grade that names no evidence falls back to its detector's class, which a detector with a
     * JVM-answered path and a recorded one has to set by the stronger path; the recorded path's
     * grade then rides on it. Three reports graded that way until #837, and nothing noticed.
     */
    @Test
    @DisplayName("every grade a built-in report constructs names the evidence of its path")
    void everyBuiltInGradeNamesItsEvidence() throws IOException {
        List<String> coarse = new ArrayList<>();
        try (var sources = Files.list(DIAGNOSTICS)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String code = codeOnly(Files.readString(source, StandardCharsets.UTF_8));
                var grade = GRADE.matcher(code);
                while (grade.find()) {
                    if (argumentCount(code, grade.end()) != 4) {
                        coarse.add(source.getFileName() + ": " + code.substring(grade.start(),
                                Math.min(code.length(), grade.end() + 60)).replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertTrue(coarse.isEmpty(),
                "these grades name no DetectorTrust.Evidence, so the report path caps them at the "
                        + "detector's class instead of the class of the path that produced them. "
                        + "Use the four-argument Grade constructor: " + coarse);
        assertEquals(3, argumentCount(codeOnly("new Grade(a, f(b, c), \"x, y\")"), "new Grade(".length()),
                "nested calls and literals are one argument each");
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
        assertTrue(PROBE.matcher(codeOnly("boolean held = lock.isLocked();")).find());
        assertFalse(PROBE.matcher(codeOnly("// asks lock.isLocked() at analysis\nvoid j() {}")).find(),
                "a probe named in a comment is not a probe");
        assertFalse(THRESHOLD.matcher(codeOnly("String n = \"above the threshold\";")).find(),
                "a threshold named in a message is not one the code compares against");
    }

    private static void assertOneRule(String line, String type, String rule) {
        assertTrue(line.startsWith(type) && line.contains(rule) && !line.contains(ALSO),
                "expected only the rule containing '" + rule + "' for " + type + ": " + line);
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
            DetectorType type = row.type();
            Evidence evidence = declared.get(type);
            String detector = row.detectorClass();
            List<String> reasons = new ArrayList<>();
            boolean consults = consultsContext(type);
            if (evidence == Evidence.CONTEXTUAL && !consults) {
                reasons.add("is CONTEXTUAL but " + detector
                        + " reads no lockset, monitor probe or happens-before edge");
            } else if (consults && !acceptsContext(evidence) && !WEAKER_PATH.containsKey(type)) {
                reasons.add("is " + evidence + " but " + detector
                        + " reads synchronization context and WEAKER_PATH names no path that does not");
            }
            if (evidence == Evidence.OBSERVED && !fedWithoutRecording(type) && !probes(type)) {
                reasons.add("is OBSERVED but " + detector + " is fed only by record calls and asks no "
                        + "JVM object for its state");
            }
            if (fedWithoutRecording(type) && !acceptsContext(evidence) && !FED_BELOW_OBSERVED.containsKey(type)) {
                reasons.add("is " + evidence + " but " + detector + " is fed by "
                        + DetectorFeeds.feedOf(type) + " and FED_BELOW_OBSERVED names no path that holds it lower");
            }
            if (grades(type) && !namesEvidence(type, evidence)) {
                reasons.add("is " + evidence + " but " + detector + " grades its findings and no grade names "
                        + evidence + ", the class of its strongest grade");
            }
            if (namesThreshold(type) && !grades(type) && aboveThePromptCap(evidence)
                    && !THRESHOLD_DECIDES_NO_FINDING.containsKey(type)) {
                reasons.add("is " + evidence + " but " + detector + " names a threshold, does not grade "
                        + "its findings, and THRESHOLD_DECIDES_NO_FINDING does not say why the threshold "
                        + "decides no finding");
            }
            if (evidence == Evidence.HEURISTIC && !namesThreshold(type)) {
                reasons.add("is HEURISTIC but " + detector + " names no threshold: give the number the "
                        + "finding turns on a name containing THRESHOLD (#756)");
            }
            if (!reasons.isEmpty()) {
                out.add(type + " " + String.join(ALSO, reasons));
            }
        }
        return out;
    }

    /** {@return whether a detector reading context is consistent with {@code evidence} on its own} */
    private static boolean acceptsContext(Evidence evidence) {
        return evidence == Evidence.CONTEXTUAL || evidence == Evidence.OBSERVED;
    }

    /** {@return whether {@code evidence} lets a finding claim more than PROMPT} */
    private static boolean aboveThePromptCap(Evidence evidence) {
        return evidence.cap().compareTo(TrustTier.PROMPT) > 0;
    }

    private static boolean fedWithoutRecording(DetectorType type) {
        return DetectorFeeds.feedOf(type) != DetectorFeed.RECORDING;
    }

    private static boolean consultsContext(DetectorType type) {
        return CONTEXT.matcher(code(type)).find();
    }

    private static boolean probes(DetectorType type) {
        return PROBE.matcher(code(type)).find();
    }

    private static boolean grades(DetectorType type) {
        return GRADE.matcher(code(type)).find();
    }

    private static boolean namesThreshold(DetectorType type) {
        return THRESHOLD.matcher(code(type)).find();
    }

    /** {@return whether the detector's code names {@code evidence} as a constant, which is how a grade names it} */
    private static boolean namesEvidence(DetectorType type, Evidence evidence) {
        return Pattern.compile("\\bEvidence\\s*\\.\\s*" + evidence.name() + "\\b").matcher(code(type)).find();
    }

    private static DetectorTrust.Row rowOf(DetectorType type) {
        return DetectorTrust.rows().stream()
                .filter(row -> row.type() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError(type + " has no DetectorTrust row"));
    }

    /** {@return the code of the detector class behind {@code type}, comments and literals blanked} */
    private static String code(DetectorType type) {
        return CODE.computeIfAbsent(type, key -> {
            Path source = DIAGNOSTICS.resolve(rowOf(key).detectorClass() + ".java");
            try {
                return codeOnly(Files.readString(source, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read " + source.toAbsolutePath()
                        + "; this test reads the module's own sources and runs from the module directory", e);
            }
        });
    }

    /** {@return how many top-level arguments the call whose argument list opens before {@code from} has} */
    private static int argumentCount(String code, int from) {
        int depth = 0;
        int commas = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return commas + 1;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            }
        }
        return commas + 1;
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
