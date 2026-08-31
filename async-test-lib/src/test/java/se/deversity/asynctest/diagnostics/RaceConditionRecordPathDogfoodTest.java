package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.AfterAll;
import se.deversity.asynctest.AsyncTest;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dogfoods {@link RaceConditionDetector}'s record path with {@code @AsyncTest}.
 *
 * <p>Why this exists: {@code recordFieldWrite} runs on the racing threads themselves, between the
 * very accesses it is hunting. Its own concurrency is therefore load bearing — a lost record is a
 * detector that goes quiet on a real bug, and the two {@code computeIfAbsent} calls plus the
 * lock-free queue behind it have exactly the shape that loses records when several threads reach
 * a brand new key at the same instant. The existing {@code RaceConditionDetectorTest} drives that
 * path from a single thread or from threads that are never made to collide.
 *
 * <p>So the harness is pointed at the detector instead of at a fixture: {@link #THREADS} workers
 * are released together by the runner's barrier and immediately record against the same detector,
 * the same object and the same two fields, {@link #ROUNDS} times over. The detector under test is
 * a plain instance owned by this class — it is not the one the enclosing run installs, so nothing
 * here perturbs the report of the run that drives it.
 *
 * <p>Both directions are asserted, as a detector change is required to ship. The unguarded field
 * must still be reported after all that contention, and the field every thread wrote under the
 * subject's own monitor must stay absent from the report.
 */
class RaceConditionRecordPathDogfoodTest {

    private static final int THREADS = 4;
    private static final int ROUNDS = 200;

    /** The object the detector tracks by identity. Its fields are written for real, not simulated. */
    static final class Subject {
        int unguarded;
        int guarded;
    }

    private static final RaceConditionDetector DETECTOR = new RaceConditionDetector();
    private static final Subject SUBJECT = new Subject();

    @AsyncTest(threads = THREADS, invocations = ROUNDS, timeoutMs = 20_000)
    void everyWorkerRecordsAgainstTheSameDetectorAtTheSameInstant() {
        // Unguarded: nothing serialises these, and the detector is told so by an empty lock
        // fingerprint.
        DETECTOR.recordFieldWrite(SUBJECT, "unguarded");
        SUBJECT.unguarded++;

        // Guarded by the tracked object's own monitor, which every worker holds, so every access
        // carries the same non-zero fingerprint.
        synchronized (SUBJECT) {
            DETECTOR.recordFieldWrite(SUBJECT, "guarded");
            SUBJECT.guarded++;
        }
    }

    @AfterAll
    static void theRecordPathKeptWhatItWasToldUnderContention() {
        RaceConditionDetector.RaceConditionReport report = DETECTOR.analyzeRaceConditions();

        assertTrue(mentions(report, ".unguarded"),
                "the unguarded field was written by " + THREADS + " threads " + ROUNDS
                        + " times and went unreported. Report: " + report);

        // The report states how many writes it actually paired. Every one of them was handed to
        // recordFieldWrite, so the count is the whole no-lost-records claim, in a number the
        // detector itself produced: one dropped record moves it.
        assertEquals(THREADS * ROUNDS, writesReportedFor(report, ".unguarded"),
                "the detector paired fewer writes than were recorded: the record path lost "
                        + "records under contention. Report: " + report);

        assertFalse(mentions(report, ".guarded"),
                "the field every thread wrote while holding the subject's own monitor was "
                        + "reported as a race. Report: " + report);
    }

    private static boolean mentions(RaceConditionDetector.RaceConditionReport report, String suffix) {
        return findingsOf(report).anyMatch(finding -> finding.contains(suffix + ":"));
    }

    /**
     * {@return the write count the report states for the field ending in {@code suffix}, or -1}
     *
     * <p>Reads the {@code "<field>: N writes observed across M threads"} line that
     * {@code potentialRaces} carries.
     */
    private static int writesReportedFor(RaceConditionDetector.RaceConditionReport report,
                                         String suffix) {
        Matcher matcher = report.potentialRaces.stream()
                .filter(race -> race.contains(suffix + ":"))
                .map(WRITES_OBSERVED::matcher)
                .filter(Matcher::find)
                .findFirst()
                .orElse(null);
        return matcher == null ? -1 : Integer.parseInt(matcher.group(1));
    }

    private static final Pattern WRITES_OBSERVED = Pattern.compile("(\\d+) writes observed");

    private static Stream<String> findingsOf(RaceConditionDetector.RaceConditionReport report) {
        Set<String> races = report.potentialRaces;
        Set<String> unsafe = report.unsafeAccesses;
        return Stream.concat(races.stream(), unsafe.stream());
    }
}
