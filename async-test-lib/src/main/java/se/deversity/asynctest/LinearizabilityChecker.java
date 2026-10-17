package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Wing and Gong's search for a linearization of one round's history, with memoisation of the
 * (placed operations, model state) pairs already ruled out (#924).
 *
 * <p>An operation may be placed next when its invocation precedes the response of every operation
 * not yet placed: nothing that had already returned when it was called is still waiting. A pending
 * operation (its action threw, so it has no response) may be placed anywhere after its invocation
 * with any result, or left out.
 */
final class LinearizabilityChecker {

    /** The most operations one round may record; the placed set is a {@code long}. */
    static final int MAX_OPERATIONS = 64;

    /** The most (placed set, state) pairs one search visits before it gives up undecided. */
    static final long DEFAULT_BUDGET = 1_000_000L;

    /** Response ticket of an operation that never returned. */
    static final long NO_RESPONSE = Long.MAX_VALUE;

    /**
     * One recorded call.
     *
     * @param thread    the name of the thread that made it
     * @param operation the operation's name
     * @param argument  its argument
     * @param result    what it returned; meaningless when it is pending
     * @param invoke    the ticket taken before the call
     * @param response  the ticket taken after it returned, or {@link #NO_RESPONSE}
     */
    record Op(String thread, String operation, @Nullable Object argument, @Nullable Object result,
              long invoke, long response) {

        boolean pending() {
            return response == NO_RESPONSE;
        }
    }

    /** What a search concluded. */
    enum Verdict { LINEARIZABLE, NOT_LINEARIZABLE, UNDECIDED }

    private LinearizabilityChecker() { /* static search */ }

    /**
     * {@return whether some real-time-consistent order of {@code ops} gives their results under
     * {@code spec}}
     *
     * @param ops    one round's operations, at most {@link #MAX_OPERATIONS}
     * @param spec   the sequential model
     * @param budget the most states to visit before answering {@link Verdict#UNDECIDED}
     */
    static <M> Verdict check(List<Op> ops, SequentialSpec<M> spec, long budget) {
        if (ops.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException(ops.size() + " operations; the search takes at most "
                    + MAX_OPERATIONS + " per round");
        }
        Search<M> search = new Search<>(ops.toArray(new Op[0]), spec, budget);
        try {
            return search.from(0L, spec.initial()) ? Verdict.LINEARIZABLE : Verdict.NOT_LINEARIZABLE;
        } catch (BudgetExhausted e) {
            return Verdict.UNDECIDED;
        }
    }

    /**
     * {@return the response ticket at which the shortest prefix of {@code ops} with no linearization
     * ends, or {@code -1} when no prefix is proven to have none}
     *
     * <p>Linearizability is prefix-closed: every prefix of a linearizable history is linearizable.
     * So the prefix this finds is a counterexample in its own right, observed by the workers, and
     * not an artefact of cutting: the history became unexplainable the moment that response was
     * seen. Only a response can make a history unexplainable, since a call still running may be
     * left out, so the cuts tried are the response tickets, by binary search over them. A prefix
     * whose search runs out of budget counts as not proven, so the cut returned is always one the
     * search proved, if possibly not the shortest.
     *
     * @param ops    one round's operations, at most {@link #MAX_OPERATIONS}
     * @param spec   the sequential model
     * @param budget the most states each search visits
     */
    static <M> long shortestViolatingCut(List<Op> ops, SequentialSpec<M> spec, long budget) {
        long[] responses = ops.stream().filter(op -> !op.pending()).mapToLong(Op::response).sorted().toArray();
        long found = -1L;
        int low = 0;
        int high = responses.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (check(prefix(ops, responses[mid]), spec, budget) == Verdict.NOT_LINEARIZABLE) {
                found = responses[mid];
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return found;
    }

    /**
     * {@return what the workers had seen of {@code ops} at ticket {@code cut}: every operation invoked
     * before it, in their order, with those that had not returned by then turned pending}
     *
     * @param ops one round's operations
     * @param cut a response ticket
     */
    static List<Op> prefix(List<Op> ops, long cut) {
        return ops.stream()
                .filter(op -> op.invoke() < cut)
                .map(op -> op.response() <= cut ? op
                        : new Op(op.thread(), op.operation(), op.argument(), op.result(), op.invoke(), NO_RESPONSE))
                .toList();
    }

    private static final class BudgetExhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExhausted() {
            super(null, null, false, false);
        }
    }

    private static final class Search<M> {
        private final Op[] ops;
        private final SequentialSpec<M> spec;
        private final long completed;
        private final Set<Key> ruledOut = new HashSet<>();
        private long budget;

        Search(Op[] ops, SequentialSpec<M> spec, long budget) {
            this.ops = ops;
            this.spec = spec;
            this.budget = budget;
            long mask = 0L;
            for (int i = 0; i < ops.length; i++) {
                if (!ops[i].pending()) {
                    mask |= 1L << i;
                }
            }
            this.completed = mask;
        }

        boolean from(long placed, M state) {
            if ((placed & completed) == completed) {
                return true;
            }
            budget--;
            if (budget < 0) {
                throw new BudgetExhausted();
            }
            Key key = new Key(placed, state);
            if (ruledOut.contains(key)) {
                return false;
            }
            long firstResponse = NO_RESPONSE;
            for (int i = 0; i < ops.length; i++) {
                if ((placed & (1L << i)) == 0) {
                    firstResponse = Math.min(firstResponse, ops[i].response());
                }
            }
            for (int i = 0; i < ops.length; i++) {
                Op op = ops[i];
                if ((placed & (1L << i)) != 0 || op.invoke() > firstResponse) {
                    continue;
                }
                M next = spec.copy(state);
                Object result = spec.apply(next, op.operation(), op.argument());
                if ((op.pending() || Objects.equals(result, op.result()))
                        && from(placed | (1L << i), next)) {
                    return true;
                }
            }
            ruledOut.add(key);
            return false;
        }
    }

    /** A (placed set, state) pair, with arrays in the state compared by content. */
    private static final class Key {
        private final long placed;
        private final @Nullable Object state;
        private final int hash;

        Key(long placed, @Nullable Object state) {
            this.placed = placed;
            this.state = state;
            this.hash = 31 * Long.hashCode(placed) + Arrays.deepHashCode(new Object[]{state});
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that && that.placed == placed && Objects.deepEquals(that.state, state);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
