package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code suspiciousReorderings} flagged any thread that wrote one location and then touched a
 * different location within the next two operations:
 *
 * <pre>{@code
 * recordWrite("a", 1);
 * recordWrite("b", 2);   // -> "Write to a followed by access to b (possible reordering)"
 * }</pre>
 *
 * <p>That is not a bug, it is ordinary code — and the finding counted toward
 * {@code hasIssues()}, so any instrumented method that touches two fields produced a violation.
 *
 * <p>The rule was also unsound in principle. The access log records each thread's own program
 * order; a reordering is by definition only observable from <em>another</em> thread seeing the
 * writes out of order. A per-thread log cannot witness one. The detector already has the signal
 * that can: {@code staleCoreads} — a thread reading a location and not seeing the value another
 * thread wrote to it.
 *
 * <p>The existing test was written around the false positive: it wrote two fields and then
 * asserted only {@code assertNotNull(report.suspiciousReorderings)}, pointedly never asserting
 * that the report was clean.
 */
class MemoryOrderingNoFalsePositiveTest {

    @Test
    void writingTwoDifferentFieldsIsNotAMemoryOrderingViolation() {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();

        // Perfectly ordinary, correct code: one thread sets two fields.
        monitor.recordWrite("config.host", "localhost");
        monitor.recordWrite("config.port", 8080);
        monitor.recordRead("config.host", "localhost");

        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();

        assertTrue(report.suspiciousReorderings.isEmpty(),
            "touching two different fields in sequence is not a reordering: "
                + report.suspiciousReorderings);
        assertFalse(report.hasIssues(),
            "correct single-threaded field writes must produce a clean report:\n" + report);
    }

    /** The real signal must still work: a reader that does not see another thread's write. */
    @Test
    void aStaleReadAcrossThreadsIsStillReported() throws InterruptedException {
        MemoryOrderingMonitor monitor = new MemoryOrderingMonitor();

        // T1 writes the new value...
        Thread writer = new Thread(() -> monitor.recordWrite("flag", true));
        writer.start();
        writer.join();

        // ...and T2 reads the location but still sees the old one.
        Thread reader = new Thread(() -> monitor.recordRead("flag", false));
        reader.start();
        reader.join();

        MemoryOrderingMonitor.MemoryOrderingReport report = monitor.analyzeOrdering();

        assertFalse(report.staleCoreads.isEmpty(),
            "a thread that misses another thread's write is a real visibility violation");
        assertTrue(report.hasIssues(), "the report must claim issues");
    }
}
