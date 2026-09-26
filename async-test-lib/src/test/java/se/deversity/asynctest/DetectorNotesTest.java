package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.SynchronizedNonFinalDetector;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A detector note that is not a finding reaches {@link AsyncTestContext#detectorNotes()}, from
 * which the runner logs it, because a report with no finding is never printed (#816).
 */
class DetectorNotesTest {

    /** A non-final instance lock field: several monitors are undecided without the instance. */
    private static final class Holder {
        private Object lock = new Object();
    }

    private static AsyncTestContext context() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectSynchronizedNonFinal(true).build());
    }

    @Test
    void aNoteInAReportWithNoFindingIsCollectedOnce() {
        AsyncTestContext ctx = context();
        SynchronizedNonFinalDetector detector = ctx.synchronizedNonFinalDetector;
        for (int i = 0; i < 3; i++) {
            Holder holder = new Holder();
            detector.recordLockObject(holder.lock, "lock", Holder.class);
        }

        Map<String, String> reports = ctx.analyzeAllNamed();
        assertTrue(reports.isEmpty(), "three instances with a lock each are no finding: " + reports);
        List<String> notes = ctx.detectorNotes().get("SynchronizedNonFinalDetector");
        assertEquals(1, notes == null ? 0 : notes.size(),
            "one undecided slot is one note, keyed by the detector that wrote it: " + ctx.detectorNotes());
        assertTrue(notes.get(0).contains("recordLockObject(lock, \"lock\", Holder.class, this)"),
            "the note names the call that decides the slot: " + notes);
    }

    @Test
    void aReportWithAFindingKeepsItsNotesInItsOwnText() {
        AsyncTestContext ctx = context();
        SynchronizedNonFinalDetector detector = ctx.synchronizedNonFinalDetector;
        Holder reassigned = new Holder();
        detector.recordLockObject(reassigned.lock, "lock", Holder.class, reassigned);
        reassigned.lock = new Object();
        detector.recordLockObject(reassigned.lock, "lock", Holder.class, reassigned);
        for (int i = 0; i < 3; i++) {
            Holder holder = new Holder();
            detector.recordLockObject(holder.lock, "lock", Holder.class);
        }

        Map<String, String> reports = ctx.analyzeAllNamed();
        String report = reports.get("SynchronizedNonFinalDetector");
        assertTrue(report != null && report.contains("NOT FINAL"),
            "one instance reassigning its lock is a finding, reported as before: " + reports);
        assertTrue(report.contains("recordLockObject(lock, \"lock\", Holder.class, this)"),
            "and the printed report carries the note beside it: " + report);
        assertTrue(ctx.detectorNotes().isEmpty(),
            "so the note is not collected a second time for the log: " + ctx.detectorNotes());
    }

    @Test
    void aRunWithNoNotesCollectsNone() {
        AsyncTestContext ctx = context();
        Holder holder = new Holder();
        ctx.synchronizedNonFinalDetector.recordLockObject(holder.lock, "lock", Holder.class);

        assertTrue(ctx.analyzeAllNamed().isEmpty());
        assertTrue(ctx.detectorNotes().isEmpty(), "one monitor is nothing to note: " + ctx.detectorNotes());
    }
}
