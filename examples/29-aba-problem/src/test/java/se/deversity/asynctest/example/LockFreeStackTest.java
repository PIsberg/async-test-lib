package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
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
 *   - But A.next now points to a stale or recycled node
 *   - The stack silently loses elements or links to freed memory
 *
 * WHY @Test PASSES:
 * Single-threaded tests never interleave push/pop operations, so the ABA
 * cycle (A → B → A) never occurs. Functional correctness is unaffected.
 *
 * WHY @AsyncTest DETECTS THE ISSUE:
 * ABAProblemDetector tracks value-change history and detects A → B → A cycles,
 * plus CAS operations that succeeded despite such a cycle having occurred.
 *
 * DETECTORS TRIGGERED:
 * ABAProblemDetector — standalone, instantiated directly in the test.
 *
 * FIX:
 * Replace AtomicReference with AtomicStampedReference. Each CAS compares
 * both the reference and a version stamp — an A → B → A cycle changes
 * the stamp, so the CAS correctly fails.
 */
class LockFreeStackTest {

    private LockFreeStack<String> stack;
    private final ABAProblemDetector detector = new ABAProblemDetector();

    @BeforeEach
    void setUp() {
        stack = new LockFreeStack<>();
        detector.reset();
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
    @AsyncTest(threads = 4, invocations = 50)
    void testPop_concurrent_detectsABAProblem() {
        // Simulate the A → B → A cycle on the "head" variable:
        // Step 1: head is NodeX (value "task-A")
        detector.recordValueChange("head", null, "task-A");
        // Step 2: head changes to NodeY (task-A popped, task-B pushed)
        detector.recordValueChange("head", "task-A", "task-B");
        // Step 3: head changes back to NodeX (task-B popped, task-A re-pushed)
        detector.recordValueChange("head", "task-B", "task-A");

        // Thread A's CAS: expected=task-A, new=null (A.next was null originally)
        // This succeeds because head == task-A — but the structure is now corrupt
        boolean casSucceeded = true; // in real code, CAS returns true here
        detector.recordCASAttempt("head", "task-A", null, casSucceeded, "task-A");

        ABAProblemDetector.ABAReport report = detector.analyzeABA();
        assertTrue(report.hasIssues(),
            "Expected ABA problem to be detected.\n" + report);
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
