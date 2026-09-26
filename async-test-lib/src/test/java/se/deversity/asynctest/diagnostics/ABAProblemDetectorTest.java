package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ABAProblemDetectorTest {

    /** Runs {@code body} on another thread and waits for it: the "other thread" of an ABA. */
    private static void onAnotherThread(Runnable body) throws InterruptedException {
        Thread t = new Thread(body, "aba-other");
        t.start();
        t.join();
    }

    @Test
    void noRecordingsReturnNoIssues() {
        ABAProblemDetector detector = new ABAProblemDetector();
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertFalse(report.hasIssues());
    }

    @Test
    void noABACycleNoIssues() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("x", "A", "B");
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertFalse(report.hasIssues());
    }

    @Test
    void aCycleIsCountedAsContextButIsNotAFinding() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("x", "init", "A"); // establish A
        detector.recordValueChange("x", "A", "B");
        detector.recordValueChange("x", "B", "A");    // back to A: a cycle, no compare-and-set
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertTrue(report.variablesWithCycles.containsKey("x"), report.variablesWithCycles.toString());
        assertFalse(report.hasIssues(),
            "a value that goes A to B to A with no compare-and-set relying on A hurts nothing");
    }

    @Test
    void oneThreadTogglingThenCasingIsNotAnAba() {
        // Push then pop on one thread, then that thread's own CAS: nobody held a stale premise.
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordRead("head", "A");
        detector.recordValueChange("head", "A", "B");
        detector.recordValueChange("head", "B", "A");
        detector.recordCASAttempt("head", "A", "C", true, "A");
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertFalse(report.hasIssues(),
            "every change was made by the thread whose CAS expected A; that is not ABA: " + report);
    }

    @Test
    void casWhosePremiseWasReadAfterAnotherThreadsToggleIsSilent() throws InterruptedException {
        ABAProblemDetector detector = new ABAProblemDetector();
        onAnotherThread(() -> {
            detector.recordValueChange("head", "A", "B");
            detector.recordValueChange("head", "B", "A");
        });
        detector.recordRead("head", "A");                 // premise read after the toggle
        detector.recordCASAttempt("head", "A", "C", true, "A");
        assertFalse(detector.analyzeABA().hasIssues(),
            "the CAS expected the value it read after the toggle, which is a fresh premise");
    }

    @Test
    void casWhosePremiseWasReadBeforeAnotherThreadsToggleIsAnAba() throws InterruptedException {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordRead("head", "A");                 // this thread reads A ...
        onAnotherThread(() -> {                           // ... another swings A -> B -> A ...
            detector.recordValueChange("head", "A", "B");
            detector.recordValueChange("head", "B", "A");
        });
        detector.recordCASAttempt("head", "A", "C", true, "A"); // ... and the stale CAS succeeds
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertTrue(report.hasIssues(), report.toString());
        assertFalse(report.successfulABACases.isEmpty());
        assertTrue(report.toString().contains("HIGH"), report.toString());
    }

    @Test
    void everyStaleCasIsReportedHoweverManyThereAre() throws InterruptedException {
        // Well past the birthday bound for a 31-bit identity hash (about 54,000 objects), so
        // some twenty pairs of these attempts share a hash. An attempt keyed by its bare hash
        // replaced the one before it, and that one's finding was gone (#763).
        int batches = 30;
        int perBatch = 10_000;
        ABAProblemDetector detector = new ABAProblemDetector();
        for (int b = 0; b < batches; b++) {
            CountDownLatch read = new CountDownLatch(perBatch);
            CountDownLatch toggled = new CountDownLatch(1);
            List<Thread> casThreads = new ArrayList<>(perBatch);
            for (int i = 0; i < perBatch; i++) {
                String next = "n" + (b * perBatch + i);
                casThreads.add(Thread.ofVirtual().start(() -> {
                    detector.recordRead("head", "A");        // each reads A ...
                    read.countDown();
                    try {
                        toggled.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;                              // shows up as a missing finding
                    }
                    detector.recordCASAttempt("head", "A", next, true, "A"); // ... CAS succeeds
                }));
            }
            read.await();
            detector.recordValueChange("head", "A", "B");    // ... after this thread's A -> B -> A
            detector.recordValueChange("head", "B", "A");
            toggled.countDown();
            for (Thread t : casThreads) {
                t.join();
            }
        }
        assertEquals(batches * perBatch, detector.analyzeABA().successfulABACases.size(),
            "every stale compare-and-set set a distinct value, so each is its own finding");
    }

    @Test
    void aFailedCasIsNotAnAba() throws InterruptedException {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordRead("head", "A");
        onAnotherThread(() -> {
            detector.recordValueChange("head", "A", "B");
            detector.recordValueChange("head", "B", "A");
        });
        detector.recordCASAttempt("head", "A", "C", false, "B");
        assertFalse(detector.analyzeABA().hasIssues());
    }

    @Test
    void casWithNoRecordedReadDrawsNoVerdict() throws InterruptedException {
        // Without the read the detector cannot place the premise before or after the toggle.
        ABAProblemDetector detector = new ABAProblemDetector();
        onAnotherThread(() -> {
            detector.recordValueChange("counter", 1, 2);
            detector.recordValueChange("counter", 2, 1);
        });
        detector.recordCASAttempt("counter", 1, 3, true, 1);
        assertFalse(detector.analyzeABA().hasIssues());
    }

    @Test
    void reportToStringNoIssues() {
        ABAProblemDetector detector = new ABAProblemDetector();
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        String text = report.toString();
        assertNotNull(text);
        assertFalse(text.contains("ABA PROBLEM"));
    }

    @Test
    void reportToStringWithIssues() throws InterruptedException {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordRead("val", "A");
        onAnotherThread(() -> {
            detector.recordValueChange("val", "A", "B");
            detector.recordValueChange("val", "B", "A");
        });
        detector.recordCASAttempt("val", "A", "C", true, "A");
        String text = detector.analyzeABA().toString();
        assertTrue(text.contains("ABA PROBLEM"), text);
        assertTrue(text.contains("CAS succeeded despite ABA"), text);
    }

    @Test
    void resetClearsState() throws InterruptedException {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordRead("y", "A");
        onAnotherThread(() -> {
            detector.recordValueChange("y", "A", "B");
            detector.recordValueChange("y", "B", "A");
        });
        detector.recordCASAttempt("y", "A", "C", true, "A");
        assertTrue(detector.analyzeABA().hasIssues());
        detector.reset();
        assertFalse(detector.analyzeABA().hasIssues());
    }

    @Test
    void disabledSkipsRecording() throws InterruptedException {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.disable();
        detector.recordRead("z", "A");
        onAnotherThread(() -> {
            detector.recordValueChange("z", "A", "B");
            detector.recordValueChange("z", "B", "A");
        });
        detector.recordCASAttempt("z", "A", "C", true, "A");
        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertFalse(report.hasIssues());
        detector.enable();
    }

    @Test
    void analyze_delegatesToAnalyzeABA() {
        ABAProblemDetector detector = new ABAProblemDetector();
        detector.recordValueChange("x", "start", "A");
        detector.recordValueChange("x", "A", "B");
        detector.recordValueChange("x", "B", "A");

        ABAProblemDetector.ABAReport viaAnalyze = detector.analyze();
        ABAProblemDetector.ABAReport viaAnalyzeABA = detector.analyzeABA();

        assertEquals(viaAnalyzeABA.hasIssues(), viaAnalyze.hasIssues());
        assertEquals(viaAnalyzeABA.toString(), viaAnalyze.toString());
    }
}
