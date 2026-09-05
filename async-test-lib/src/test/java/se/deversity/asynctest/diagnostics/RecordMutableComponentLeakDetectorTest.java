package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RecordMutableComponentLeakDetector}.
 *
 * <p>The two findings are tested separately because they carry different weight: an observed
 * mutation is a fact about this run, a mutable component type is a hole that may never have been
 * written through. Both are tested against the {@code List.copyOf} twin that closes them.
 */
class RecordMutableComponentLeakDetectorTest {

    record Order(String id, List<String> items) { }

    record SafeOrder(String id, List<String> items) {
        SafeOrder {
            items = List.copyOf(items);
        }
    }

    record Bytes(String id, byte[] payload) { }

    record Plain(String id, int quantity) { }

    record Concurrent(String id, Map<String, String> attributes) { }

    private RecordMutableComponentLeakDetector detector;
    private Thread threadA;
    private Thread threadB;

    @BeforeEach
    void setUp() {
        detector = new RecordMutableComponentLeakDetector();
        threadA = new Thread(() -> { }, "record-a");
        threadB = new Thread(() -> { }, "record-b");
    }

    @Test
    void mutationOfASharedComponentIsFlaggedAsObserved() {
        List<String> items = new ArrayList<>(List.of("a"));
        Order order = new Order("o-1", items);

        detector.recordShared(order, "order", threadA);
        detector.recordShared(order, "order", threadB);
        items.add("b");                       // BUG: the record looked immutable and was not

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A mutated shared record component must be flagged");
        assertTrue(report.toString().contains("changed contents while shared"),
                "The observed-mutation wording must be used: " + report);
        assertTrue(report.toString().contains("HIGH"),
                "An observed mutation is a verdict, reported at HIGH: " + report);
    }

    @Test
    void mutableComponentWithoutMutationIsFlaggedAsStructuralRisk() {
        Order order = new Order("o-2", new ArrayList<>(List.of("a")));

        detector.recordShared(order, "order", threadA);
        detector.recordShared(order, "order", threadB);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "A shared record holding an ArrayList must be flagged");
        assertTrue(report.toString().contains("MEDIUM"),
                "An unexercised hole is a prompt, reported at MEDIUM: " + report);
        assertTrue(report.toString().contains("java.util.ArrayList"),
                "The report must name the offending runtime type: " + report);
    }

    @Test
    void listCopyOfIsTheCorrectTwinAndStaysSilent() {
        List<String> items = new ArrayList<>(List.of("a"));
        SafeOrder order = new SafeOrder("o-3", items);

        detector.recordShared(order, "order", threadA);
        detector.recordShared(order, "order", threadB);
        items.add("b");         // mutating the caller's list cannot reach the record's copy

        var report = detector.analyze();
        assertFalse(report.hasIssues(),
                "List.copyOf in a compact constructor closes the hole and must stay silent: " + report);
    }

    @Test
    void arrayComponentIsFlagged() {
        Bytes b = new Bytes("b-1", new byte[]{1, 2, 3});

        detector.recordShared(b, "payload", threadA);
        detector.recordShared(b, "payload", threadB);

        var report = detector.analyze();
        assertTrue(report.hasIssues(), "An array component has no immutable form and must be flagged");
        assertTrue(report.toString().contains("byte[] of length 3"),
                "The report must describe the array: " + report);
    }

    @Test
    void arrayContentMutationIsObserved() {
        byte[] payload = {1, 2, 3};
        Bytes b = new Bytes("b-2", payload);

        detector.recordShared(b, "payload", threadA);
        detector.recordShared(b, "payload", threadB);
        payload[0] = 9;

        assertTrue(detector.analyze().toString().contains("changed contents while shared"),
                "Writing through the aliased array must be observed, not merely predicted");
    }

    @Test
    void immutableComponentsAreClean() {
        Plain p = new Plain("p-1", 42);

        detector.recordShared(p, "plain", threadA);
        detector.recordShared(p, "plain", threadB);

        assertFalse(detector.analyze().hasIssues(),
                "A record of a String and an int has nothing to leak");
    }

    @Test
    void concurrentCollectionsAreNotReported() {
        Concurrent c = new Concurrent("c-1", new ConcurrentHashMap<>());

        detector.recordShared(c, "concurrent", threadA);
        detector.recordShared(c, "concurrent", threadB);

        assertFalse(detector.analyze().hasIssues(),
                "A java.util.concurrent component is the correct answer to shared mutability, "
                + "not a defect; flagging it would train users to ignore the detector");
    }

    @Test
    void singleThreadedRecordIsNotReported() {
        Order order = new Order("o-4", new ArrayList<>(List.of("a")));

        detector.recordShared(order, "order", threadA);

        assertFalse(detector.analyze().hasIssues(),
                "A record only one thread ever touched is outside this detector's claim");
    }

    @Test
    void nonRecordArgumentsAreIgnored() {
        Object notARecord = new ArrayList<>(List.of("a"));

        detector.recordShared(notARecord, "nope", threadA);
        detector.recordShared(notARecord, "nope", threadB);

        assertFalse(detector.analyze().hasIssues(), "Non-records must be ignored, not reported");
    }

    @Test
    void nullArgumentsAreIgnored() {
        detector.recordShared(null, "null", threadA);
        detector.recordShared(new Order("o-5", List.of()), "order", null);

        assertFalse(detector.analyze().hasIssues(), "Null arguments must be ignored, not reported");
    }

    @Test
    void analyzeIsIdempotent() {
        Order order = new Order("o-6", new ArrayList<>(List.of("a")));
        detector.recordShared(order, "order", threadA);
        detector.recordShared(order, "order", threadB);

        assertEquals(detector.analyze().toString(), detector.analyze().toString(),
                "Repeated analyze() on quiescent state must produce identical reports");
    }

    @Test
    void mutatedConcurrentComponentIsNotReported() {
        Map<String, String> attributes = new ConcurrentHashMap<>();
        Concurrent c = new Concurrent("c-2", attributes);

        detector.recordShared(c, "concurrent", threadA);
        detector.recordShared(c, "concurrent", threadB);
        attributes.put("k", "v");   // the recommended fix in action: a CHM mutated while shared

        RecordMutableComponentLeakDetector.Report report = detector.analyze();
        assertFalse(report.hasIssues(),
            "Mutating a java.util.concurrent component is what the Fix text recommends; "
                + "reporting it as an observed mutation contradicts the detector's own advice: " + report);
    }
}
