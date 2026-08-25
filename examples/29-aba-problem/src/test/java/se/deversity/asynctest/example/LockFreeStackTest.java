package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.diagnostics.ABAProblemDetector;
import se.deversity.asynctest.example.service.LockFreeStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicStampedReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for LockFreeStack.
 *
 * ========================================================================
 * DETECTOR: ABAProblemDetector
 * ========================================================================
 *
 * This test demonstrates the ABA problem in a lock-free stack: a CAS
 * operation succeeds despite the underlying value having cycled through
 * A → B → A, leaving the data structure in a corrupt state.
 *
 * THE BUG:
 * LockFreeStack.pop() reads the head node (A), computes head.next, then
 * CAS-swaps head from A to head.next. If another thread pops A, pops B,
 * then pushes A back before the first thread's CAS executes:
 *   - The first thread's CAS sees head == A (matches expected) — succeeds
 *   - But A.next now points somewhere else entirely
 *   - The stack silently loses elements
 *
 * WHY THE FREE LIST MATTERS:
 * A stack that allocates a fresh node on every push cannot produce ABA in Java.
 * The popped node is garbage and the next push returns a reference that has
 * never been head, so the stale CAS fails and retries, which is correct. ABA
 * needs the same node back, and the usual reason it comes back is an
 * allocation-avoidance pool. LockFreeStack has one, and
 * testPop_recyclesTheNode_soPushHandsTheSameOneBack pins that it works.
 *
 * WHY @Test PASSES:
 * Single-threaded tests never interleave push/pop across threads, so no thread
 * is holding a stale head across a cycle. Functional correctness is unaffected.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * ABAProblemDetector is recording-fed: it tracks the value history it is handed
 * and looks for A → B → A cycles, plus CAS operations that succeeded despite
 * one. LockFreeStack.observeHead reports each successful head CAS from inside
 * push() and pop(), so the history is the stack's own, not a script.
 *
 * DETECTOR ENABLED HERE:
 * ABAProblemDetector — a head that returned to a reference it just left. It is
 * the only one this demonstration switches on, so it is the only one that can
 * report.
 *
 * FIX:
 * Replace AtomicReference with AtomicStampedReference. Each CAS compares
 * both the reference and a version stamp — an A → B → A cycle changes
 * the stamp, so the CAS correctly fails.
 */
class LockFreeStackTest {

    private LockFreeStack<String> stack;

    @BeforeEach
    void setUp() {
        stack = new LockFreeStack<>();
    }

    /**
     * The detector's positive direction, driven by the real stack: a push followed by a pop
     * takes the head from A to B and back to A, which is the cycle it looks for.
     */
    @Test
    void testPushThenPop_headReturnsToItsPreviousNode_reports() {
        ABAProblemDetector detector = new ABAProblemDetector();
        stack.push("bottom");
        stack.observeHead(
                (from, to) -> detector.recordValueChange("head", from, to),
                (expected, updated) ->
                        detector.recordCASAttempt("head", expected, updated, true, expected));

        stack.push("top");
        stack.pop();

        assertFalse(detector.analyzeABA().variablesWithCycles.isEmpty(),
                "head went A to B and back to A, which is the cycle");
    }

    /**
     * And the other direction: a head that only ever moves forward has no cycle to report.
     */
    @Test
    void testPushesOnly_headNeverReturns_isSilent() {
        ABAProblemDetector detector = new ABAProblemDetector();
        stack.observeHead(
                (from, to) -> detector.recordValueChange("head", from, to),
                (expected, updated) ->
                        detector.recordCASAttempt("head", expected, updated, true, expected));

        stack.push("a");
        stack.push("b");
        stack.push("c");

        assertFalse(detector.analyzeABA().hasIssues(),
                "a head that only moves forward is not an ABA cycle");
    }

    /**
     * The free list is the reason ABA is reachable here at all, so it is worth pinning: a
     * popped node really does come back on the next push.
     */
    @Test
    void testPop_recyclesTheNode_soPushHandsTheSameOneBack() {
        stack.push("first");

        Object[] popped = new Object[1];
        stack.observeHead((from, to) -> { }, (expected, updated) -> popped[0] = expected);
        stack.pop();

        Object[] pushedHead = new Object[1];
        stack.observeHead((from, to) -> pushedHead[0] = to, (expected, updated) -> { });
        stack.push("second");

        assertNotNull(popped[0], "the pop reported the node it removed");
        assertSame(popped[0], pushedHead[0],
                "the node popped came back off the free list, which is what makes ABA possible");
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — functional correctness passes in single-threaded use
    // -------------------------------------------------------------------------

    @Test
    void testPushPop_singleThread_lifoOrder() {
        stack.push("first");
        stack.push("second");
        stack.push("third");

        assertEquals("third",  stack.pop());
        assertEquals("second", stack.pop());
        assertEquals("first",  stack.pop());
        assertNull(stack.pop());
    }

    @Test
    void testPop_emptyStack_returnsNull() {
        assertNull(stack.pop());
    }

    @Test
    void testIsEmpty_afterAllPopped_returnsTrue() {
        stack.push("x");
        stack.pop();
        assertTrue(stack.isEmpty());
    }

    @Test
    void testPush_multipleValues_stackNotEmpty() {
        stack.push("a");
        stack.push("b");
        assertFalse(stack.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes ABA cycle via ABAProblemDetector
    // -------------------------------------------------------------------------

    /**
     * The bug: Thread A reads head=NodeX, is preempted. Thread B pops NodeX,
     * pops NodeY, pushes NodeX back. Thread A's CAS sees head==NodeX and
     * succeeds — but NodeX.next now points to null instead of NodeY.
     * The stack has silently lost NodeY.
     *
     * ABAProblemDetector records the A→B→A cycle and flags the CAS that
     * succeeded despite the cycle.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — ABAProblemDetector will flag the ABA cycle
     * 3. Fix: use AtomicStampedReference<Node<T>> instead of AtomicReference
     */
    @Disabled("Remove @Disabled to see ABA problem detected by ABAProblemDetector")
    @AsyncTest(threads = 4, invocations = 20, detectAll = false,
            detectABAProblem = true, failOn = FailOn.LOW)
    void testPop_concurrent_detectsABAProblem() {
        // This demonstration used to write out three recordValueChange calls and one
        // recordCASAttempt by hand, into a locally constructed detector, and assert on the
        // result. LockFreeStack was never touched, and the detector detectABAProblem creates
        // received nothing, so failOn had no finding to gate on. See issue #346.
        ABAProblemDetector detector = AsyncTestContext.abaProblemDetector();
        stack.observeHead(
                (from, to) -> detector.recordValueChange("head", from, to),
                (expected, updated) ->
                        detector.recordCASAttempt("head", expected, updated, true, expected));

        // Four threads pushing and popping the same shared stack. Nodes come back off the free
        // list, so the head really does return to a reference it held a moment ago.
        stack.push("task-" + Thread.currentThread().threadId());
        stack.pop();
    }

    /**
     * Fixed version: AtomicStampedReference pairs each node with a version stamp.
     * An A → B → A cycle increments the stamp twice; the CAS comparing both
     * reference and stamp correctly fails.
     */
    @Test
    void testPop_fixedWithStampedReference_noABAProblem() {
        // Demonstrate AtomicStampedReference preventing the ABA problem
        String nodeA = "task-A";
        String nodeB = "task-B";
        AtomicStampedReference<String> stampedHead =
                new AtomicStampedReference<>(nodeA, 0);

        int[] stampHolder = new int[1];
        String observed = stampedHead.get(stampHolder);
        int observedStamp = stampHolder[0];
        assertEquals(nodeA, observed);
        assertEquals(0, observedStamp);

        // Simulate A → B → A cycle (stamp increments each time)
        stampedHead.compareAndSet(nodeA, nodeB, 0, 1); // A→B, stamp 0→1
        stampedHead.compareAndSet(nodeB, nodeA, 1, 2); // B→A, stamp 1→2

        // Original thread tries to CAS with old stamp=0 — must fail
        boolean result = stampedHead.compareAndSet(observed, null, observedStamp, observedStamp + 1);
        assertFalse(result, "CAS with stale stamp must fail even though value matches");
        assertEquals(nodeA, stampedHead.getReference(), "head still points to nodeA");
        assertEquals(2, stampedHead.getStamp(), "stamp is now 2, not 0");
    }
}
