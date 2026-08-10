package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.RecordMutableComponentLeakDetector;
import se.deversity.asynctest.example.service.OrderBook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for OrderBook.
 *
 * ========================================================================
 * DETECTOR: RecordMutableComponentLeakDetector
 *           (DetectorType.RECORD_MUTABLE_COMPONENT_LEAK)
 * ========================================================================
 *
 * Records are *shallowly* immutable. The fields cannot be reassigned; what
 * they point at can be an ArrayList that every holder can still mutate.
 * "It's a record, so it's safe to share" holds only when every component
 * is itself immutable.
 *
 * THE BUG:
 *   - record Order(String id, List<String> lines) is constructed from a
 *     list the caller keeps, then shared across threads. The snapshot is
 *     not a snapshot: it is a live view that can change mid-read, and
 *     ArrayList is not thread-safe while it does.
 *
 * THE FIX:
 *   - a compact constructor with List.copyOf(lines). It copies and returns
 *     something genuinely unmodifiable, so neither the caller's later add
 *     nor a consumer can reach the record's state.
 *
 * TWO SEVERITIES, AND WHY THEY DIFFER:
 *   - HIGH  when the detector *observed* the component change while the
 *     record was shared. That is a verdict: it happened.
 *   - lower when the component is merely mutable and shared but was not
 *     seen to change. That is a structural risk, reported as a prompt —
 *     the next release of the calling code may well mutate it.
 *
 * The detector fingerprints each component when the record is shared and
 * compares on analyze(), which is how it can tell those two cases apart.
 */
class OrderBookTest {

    private RecordMutableComponentLeakDetector detector;
    private Thread threadA;
    private Thread threadB;

    @BeforeEach
    void setUp() {
        detector = new RecordMutableComponentLeakDetector();
        threadA = new Thread(() -> { }, "order-consumer-a");
        threadB = new Thread(() -> { }, "order-consumer-b");
    }

    // -----------------------------------------------------------------------
    // Part 1: the copying record. Every component is immutable, so sharing it
    // across threads is exactly as safe as it looks.
    // -----------------------------------------------------------------------

    @Test
    void recordWithCopiedComponents_isClean() {
        var book = new OrderBook();
        List<String> caller = new ArrayList<>(List.of("widget"));
        var order = book.publishSafely("o-1", caller);

        detector.recordShared(order, "safeOrder", threadA);
        detector.recordShared(order, "safeOrder", threadB);
        caller.add("late-item");          // cannot reach the record

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "A fully immutable record must stay silent:\n" + report);
        assertEquals(List.of("widget"), order.lines());
        assertThrows(UnsupportedOperationException.class, () -> order.lines().add("nope"));
    }

    // -----------------------------------------------------------------------
    // Part 2: the leak actually happens. The caller mutates the list while two
    // threads hold the record — HIGH, because it was observed, not inferred.
    // -----------------------------------------------------------------------

    @Test
    void mutationOfASharedComponent_isDetectedAtHigh() {
        var book = new OrderBook();
        List<String> caller = new ArrayList<>(List.of("widget"));
        var order = book.publish("o-2", caller);

        detector.recordShared(order, "order", threadA);
        detector.recordShared(order, "order", threadB);
        caller.add("late-item");          // BUG: reaches straight into the record

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "A mutated shared record component must be flagged:\n" + report);
        assertTrue(report.toString().contains("changed contents while shared"),
                () -> "The observed-mutation wording must be used:\n" + report);
        assertTrue(report.toString().contains("HIGH"),
                () -> "An observed mutation is a verdict, reported at HIGH:\n" + report);
        assertEquals(2, order.lines().size(), "the record saw the caller's add");
    }

    // -----------------------------------------------------------------------
    // Part 3: nothing mutated it — yet. Still reported, as a structural risk
    // rather than a verdict, because the component is mutable and shared.
    // -----------------------------------------------------------------------

    @Test
    void mutableComponentWithoutMutation_isDetectedAsStructuralRisk() {
        var book = new OrderBook();
        var order = book.publish("o-3", new ArrayList<>(List.of("widget")));

        detector.recordShared(order, "order", threadA);
        detector.recordShared(order, "order", threadB);

        var report = detector.analyze();
        assertTrue(report.hasIssues(),
                () -> "A mutable, shared component is a risk even unmutated:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 4: a record whose components are all immutable values needs no
    // copying discipline at the call site at all.
    // -----------------------------------------------------------------------

    @Test
    void recordOfImmutableValues_isClean() {
        var quote = new OrderBook.Quote("ACME", 10_150L, Map.of("venue", "XSTO"));

        detector.recordShared(quote, "quote", threadA);
        detector.recordShared(quote, "quote", threadB);

        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "Immutable components are safe to share:\n" + report);
    }
}
