package se.deversity.asynctest.example;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.CompletableFutureCombinatorMisuseDetector;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.example.service.OrderWriter;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for OrderWriter.
 *
 * ========================================================================
 * DETECTOR: CompletableFutureCombinatorMisuseDetector
 *           (DetectorType.COMPLETABLE_FUTURE_COMBINATOR_MISUSE)
 * ========================================================================
 *
 * allOf() and anyOf() are constructors, not barriers. They wait for
 * nothing: they return a new future, and that future is the only thing
 * that knows when the group is done. Drop it, or read it with getNow()
 * instead of join(), and the code carries on while the writes are still
 * in flight - "the test passed but the rows were not there yet".
 *
 * anyOf() adds a second trap: once one constituent wins, a failure in
 * any of the others has nowhere to go.
 *
 * THE BUG:
 *   - allOf(...) called for its side effect and the result discarded
 *   - the group read with getNow(null) or isDone(), which wait for
 *     nothing at all
 *   - anyOf() losers failing into silence
 *
 * THE FIX:
 *   - join the combinator, or chain from it with thenApply, so the
 *     downstream work is attached to the group rather than racing it
 *   - attach whenComplete() to each anyOf constituent so a loser's
 *     failure is still logged
 *
 * WHY THE FINDING IS A FACT:
 *   an unawaited combinator is reported only when constituents were
 *   still outstanding at the end of the run, and an early read only
 *   when fewer constituents had completed than the combinator was
 *   given. A joined group that finished is silent.
 */
class OrderWriterTest {

    private static final int PARTS = 3;

    private OrderWriter writer;
    private CompletableFutureCombinatorMisuseDetector detector;

    @BeforeEach
    void setUp() {
        writer = new OrderWriter();
        detector = new CompletableFutureCombinatorMisuseDetector();
    }

    // -----------------------------------------------------------------------
    // Part 1: the fixed shape. Every write completes, then the group is
    // joined before anything reads the result.
    // -----------------------------------------------------------------------

    @Test
    void allOfJoinedBeforeReading_isClean() {
        CompletableFuture<String> row   = writer.beginWrite("row");
        CompletableFuture<String> audit = writer.beginWrite("audit");
        CompletableFuture<String> index = writer.beginWrite("index");
        CompletableFuture<Void> all = CompletableFuture.allOf(row, audit, index);
        detector.recordCombinator(all, "orderWrites", "allOf", PARTS, Thread.currentThread());

        completeWrite(all, row, "row");
        completeWrite(all, audit, "audit");
        completeWrite(all, index, "index");

        all.join();
        detector.recordAwait(all, "join", Thread.currentThread());

        assertTrue(writer.isFullyWritten(PARTS), "the reader sees a complete order");
        var report = detector.analyze();
        assertFalse(report.hasIssues(), () -> "a joined, finished group must be clean:\n" + report);
    }

    // -----------------------------------------------------------------------
    // Part 2: the buggy shape. allOf(...) is called for its side effect and
    // the future thrown away, so nothing ever waits for the last two writes.
    // -----------------------------------------------------------------------

    @Test
    void allOfResultDiscarded_isDetected() {
        CompletableFuture<String> row   = writer.beginWrite("row");
        CompletableFuture<String> audit = writer.beginWrite("audit");
        CompletableFuture<String> index = writer.beginWrite("index");
        CompletableFuture<Void> all = CompletableFuture.allOf(row, audit, index);
        detector.recordCombinator(all, "orderWrites", "allOf", PARTS, Thread.currentThread());

        completeWrite(all, row, "row");
        // audit and index never land, and nobody joins 'all'.

        assertFalse(writer.isFullyWritten(PARTS), "the order is incomplete");
        assertEquals(3, writer.started().size(), "though all three writes were started");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "a dropped allOf with writes in flight:\n" + report);
        assertEquals(IssueSeverity.HIGH, report.structuredViolations.get(0).severity());
        assertTrue(report.toString().contains("never awaited"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 3: the read that waits for nothing. getNow() returns immediately
    // whatever the group is doing, so the caller proceeds on a partial write.
    // -----------------------------------------------------------------------

    @Test
    void getNowBeforeTheGroupFinished_isDetected() {
        CompletableFuture<String> row   = writer.beginWrite("row");
        CompletableFuture<String> audit = writer.beginWrite("audit");
        CompletableFuture<String> index = writer.beginWrite("index");
        CompletableFuture<Void> all = CompletableFuture.allOf(row, audit, index);
        detector.recordCombinator(all, "orderWrites", "allOf", PARTS, Thread.currentThread());

        completeWrite(all, row, "row");

        all.getNow(null);   // returns instantly; the group is not done
        detector.recordAwait(all, "getNow", Thread.currentThread());

        completeWrite(all, audit, "audit");
        completeWrite(all, index, "index");

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "an early non-blocking read:\n" + report);
        assertTrue(report.toString().contains("only 1 of 3"), report.toString());
    }

    // -----------------------------------------------------------------------
    // Part 4: anyOf's quiet loser. The fast replica answered, the slow one
    // then failed, and that failure reaches no handler and no log.
    // -----------------------------------------------------------------------

    @Test
    void anyOfLoserFailure_isDetectedAsMedium() {
        CompletableFuture<String> fast = writer.beginWrite("fast-replica");
        CompletableFuture<String> slow = writer.beginWrite("slow-replica");
        CompletableFuture<Object> any = CompletableFuture.anyOf(fast, slow);
        detector.recordCombinator(any, "replicaRead", "anyOf", 2, Thread.currentThread());

        fast.complete("value");
        detector.recordConstituentCompleted(any, "fast-replica", false, Thread.currentThread());

        assertEquals("value", any.join());
        detector.recordAwait(any, "join", Thread.currentThread());

        slow.completeExceptionally(new IllegalStateException("replica corrupt"));
        detector.recordConstituentCompleted(any, "slow-replica", true, Thread.currentThread());

        var report = detector.analyze();
        assertTrue(report.hasIssues(), () -> "the loser's failure went nowhere:\n" + report);
        assertEquals(IssueSeverity.MEDIUM, report.structuredViolations.get(0).severity());
        assertTrue(report.toString().contains("slow-replica"), report.toString());
    }

    /** Completes one part and tells the detector its constituent landed. */
    private void completeWrite(CompletableFuture<?> group, CompletableFuture<String> part, String name) {
        writer.commit(name);
        part.complete(name);
        detector.recordConstituentCompleted(group, name, false, Thread.currentThread());
    }
}
