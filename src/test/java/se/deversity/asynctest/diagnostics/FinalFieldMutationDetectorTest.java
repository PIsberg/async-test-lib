package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FinalFieldMutationDetector}.
 */
class FinalFieldMutationDetectorTest {

    private FinalFieldMutationDetector detector;

    @BeforeEach
    void setUp() {
        detector = new FinalFieldMutationDetector();
    }

    // ---- Happy path ----

    @Test
    void noIssues_whenOnlyReadsRecorded() {
        Thread t = Thread.currentThread();
        detector.recordRead("Config.MAX_RETRIES", t);
        detector.recordRead("Config.MAX_RETRIES", t);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), "Reads alone are not a violation: " + report);
    }

    @Test
    void noIssues_whenNothingRecorded() {
        assertFalse(detector.analyze().hasIssues());
    }

    // ---- Mutation detection ----

    @Test
    void detectsSingleMutation() {
        detector.recordMutation("Config.MAX_RETRIES", Thread.currentThread());

        var report = detector.analyze();
        assertTrue(report.hasIssues());
        assertEquals(1, report.getMutationIssues().size());
        String issue = report.getMutationIssues().get(0);
        assertTrue(issue.contains("Config.MAX_RETRIES"), issue);
        assertTrue(issue.contains("JDK 26"), issue);
        assertTrue(report.getRacingReaderIssues().isEmpty());
        assertTrue(report.getConcurrentWriteIssues().isEmpty());
    }

    @Test
    void oneIssuePerField_regardlessOfMutationCount() {
        Thread t = Thread.currentThread();
        detector.recordMutation("F", t);
        detector.recordMutation("F", t);
        detector.recordMutation("F", t);

        var report = detector.analyze();
        assertEquals(1, report.getMutationIssues().size());
        assertTrue(report.getMutationIssues().get(0).contains("3 time(s)"));
    }

    @Test
    void separateIssues_forDistinctFields() {
        Thread t = Thread.currentThread();
        detector.recordMutation("A.x", t);
        detector.recordMutation("B.y", t);

        assertEquals(2, detector.analyze().getMutationIssues().size());
    }

    // ---- Racing readers ----

    @Test
    void detectsMutationRacingForeignReader() throws Exception {
        detector.recordMutation("Config.MAX_RETRIES", Thread.currentThread());

        Thread reader = new Thread(() ->
                detector.recordRead("Config.MAX_RETRIES", Thread.currentThread()),
                "reader-thread");
        reader.start();
        reader.join();

        var report = detector.analyze();
        assertEquals(1, report.getRacingReaderIssues().size());
        String issue = report.getRacingReaderIssues().get(0);
        assertTrue(issue.contains("reader-thread"), issue);
        assertTrue(issue.contains("happens-before"), issue);
    }

    @Test
    void noRacingReaderIssue_whenOnlyTheMutatorReads() {
        Thread t = Thread.currentThread();
        detector.recordMutation("F", t);
        detector.recordRead("F", t); // same thread — sees its own write

        assertTrue(detector.analyze().getRacingReaderIssues().isEmpty());
    }

    @Test
    void noRacingReaderIssue_forReadsOfAnUnmutatedField() throws Exception {
        detector.recordMutation("A.x", Thread.currentThread());
        Thread reader = new Thread(() ->
                detector.recordRead("B.y", Thread.currentThread()));
        reader.start();
        reader.join();

        assertTrue(detector.analyze().getRacingReaderIssues().isEmpty());
    }

    // ---- Concurrent mutators ----

    @Test
    void detectsConcurrentMutators() throws Exception {
        Runnable r = () -> detector.recordMutation("F", Thread.currentThread());
        Thread a = new Thread(r), b = new Thread(r);
        a.start(); b.start();
        a.join(); b.join();

        var report = detector.analyze();
        assertEquals(1, report.getConcurrentWriteIssues().size());
        assertTrue(report.getConcurrentWriteIssues().get(0).contains("2 distinct threads"));
    }

    @Test
    void noConcurrentWriteIssue_forSingleMutator() {
        Thread t = Thread.currentThread();
        detector.recordMutation("F", t);
        detector.recordMutation("F", t);

        assertTrue(detector.analyze().getConcurrentWriteIssues().isEmpty());
    }

    // ---- Null safety ----

    @Test
    void toleratesNullArguments() {
        assertDoesNotThrow(() -> {
            detector.recordMutation(null, Thread.currentThread());
            detector.recordMutation("F", null);
            detector.recordRead(null, Thread.currentThread());
            detector.recordRead("F", null);
        });
        assertFalse(detector.analyze().hasIssues());
    }

    // ---- toString ----

    @Test
    void toString_isClean_whenNoIssues() {
        assertTrue(detector.analyze().toString().contains("No final-field mutation"));
    }

    @Test
    void toString_showsHigh_forPlainMutation() {
        detector.recordMutation("F", Thread.currentThread());
        String str = detector.analyze().toString();
        assertTrue(str.contains("HIGH"), str);
        assertTrue(str.contains("LEARNING"), str);
        assertTrue(str.contains("JEP 500"), str);
    }

    @Test
    void toString_showsCritical_whenReadersRace() throws Exception {
        detector.recordMutation("F", Thread.currentThread());
        Thread reader = new Thread(() ->
                detector.recordRead("F", Thread.currentThread()));
        reader.start();
        reader.join();

        assertTrue(detector.analyze().toString().contains("CRITICAL"));
    }
}
