package se.deversity.asynctest;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.LinearizabilityChecker.Op;
import se.deversity.asynctest.LinearizabilityChecker.Verdict;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static se.deversity.asynctest.LinearizabilityChecker.NO_RESPONSE;

/**
 * The linearizability search on hand-built histories, where the right answer is known (#924).
 *
 * <p>A register holding one int: {@code write(v)} returns {@code null}, {@code read()} returns the
 * value. Tickets are the real-time order: an operation with a lower response ticket than another's
 * invocation ticket finished before the other began.
 */
class LinearizabilityCheckerTest {

    private static final SequentialSpec<int[]> REGISTER = SequentialSpec.of(
            () -> new int[1],
            int[]::clone,
            (state, op, arg) -> {
                if (op.equals("write")) {
                    state[0] = (Integer) arg;
                    return null;
                }
                return state[0];
            });

    private static Op write(int value, long invoke, long response) {
        return new Op("t", "write", value, null, invoke, response);
    }

    private static Op read(int result, long invoke, long response) {
        return new Op("t", "read", null, result, invoke, response);
    }

    private static Verdict check(Op... ops) {
        return LinearizabilityChecker.check(List.of(ops), REGISTER, LinearizabilityChecker.DEFAULT_BUDGET);
    }

    @Test
    void aSequentialHistoryThatMatchesTheModelIsLinearizable() {
        assertEquals(Verdict.LINEARIZABLE, check(write(1, 1, 2), read(1, 3, 4), write(2, 5, 6), read(2, 7, 8)));
    }

    @Test
    void aStaleReadAfterTheWriteCompletedIsNot() {
        // write(1) returned (ticket 2) before the read began (ticket 3), so the read must see 1.
        assertEquals(Verdict.NOT_LINEARIZABLE, check(write(1, 1, 2), read(0, 3, 4)));
    }

    @Test
    void aReadOverlappingTheWriteMaySeeEitherValue() {
        assertEquals(Verdict.LINEARIZABLE, check(write(1, 1, 4), read(0, 2, 3)));
        assertEquals(Verdict.LINEARIZABLE, check(write(1, 1, 4), read(1, 2, 3)));
    }

    @Test
    void realTimeOrderBetweenTwoReadsIsEnforced() {
        // The write overlaps both reads, but the reads do not overlap each other: a read that saw
        // the new value cannot be followed by one that sees the old value.
        assertEquals(Verdict.NOT_LINEARIZABLE, check(write(1, 1, 10), read(1, 2, 3), read(0, 4, 5)));
        assertEquals(Verdict.LINEARIZABLE, check(write(1, 1, 10), read(0, 2, 3), read(1, 4, 5)));
    }

    @Test
    void aPendingCallMayTakeEffectOrNot() {
        // The write threw: the read may or may not see it.
        assertEquals(Verdict.LINEARIZABLE, check(write(1, 1, NO_RESPONSE), read(1, 2, 3)));
        assertEquals(Verdict.LINEARIZABLE, check(write(1, 1, NO_RESPONSE), read(0, 2, 3)));
        // But not before it was invoked.
        assertEquals(Verdict.NOT_LINEARIZABLE, check(read(1, 1, 2), write(1, 3, NO_RESPONSE)));
    }

    @Test
    void twoOverlappingIncrementsCannotBothReturnOne() {
        SequentialSpec<int[]> counter = SequentialSpec.of(() -> new int[1], int[]::clone, (s, op, arg) -> ++s[0]);
        List<Op> lost = List.of(new Op("a", "inc", null, 1, 1, 3), new Op("b", "inc", null, 1, 2, 4));
        List<Op> fine = List.of(new Op("a", "inc", null, 1, 1, 3), new Op("b", "inc", null, 2, 2, 4));
        assertEquals(Verdict.NOT_LINEARIZABLE, LinearizabilityChecker.check(lost, counter, 1_000));
        assertEquals(Verdict.LINEARIZABLE, LinearizabilityChecker.check(fine, counter, 1_000));
    }

    @Test
    void aSearchThatRunsOutOfBudgetIsUndecided_notLinearizable() {
        // 12 concurrent writes and an impossible final read: refuting it needs more than 10 steps.
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ops.add(write(i, i + 1, 100));
        }
        ops.add(read(99, 101, 102));
        assertEquals(Verdict.UNDECIDED, LinearizabilityChecker.check(ops, REGISTER, 10));
        assertEquals(Verdict.NOT_LINEARIZABLE, LinearizabilityChecker.check(ops, REGISTER, LinearizabilityChecker.DEFAULT_BUDGET));
    }

    @Test
    void moreThanSixtyFourOperationsAreRefused() {
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < 65; i++) {
            ops.add(write(i, 2L * i + 1, 2L * i + 2));
        }
        assertThrows(IllegalArgumentException.class,
                () -> LinearizabilityChecker.check(ops, REGISTER, LinearizabilityChecker.DEFAULT_BUDGET));
    }
}
