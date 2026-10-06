package se.deversity.asynctest;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The sequential behaviour a concurrent object must be explainable by: what one operation does to
 * the object's state, and what it returns, when nothing else runs at the same time.
 *
 * <p>{@link OperationHistory#assertLinearizable(SequentialSpec)} searches for an order of a round's
 * operations, consistent with real time, in which applying them one at a time to this model returns
 * exactly what the workers saw. The search backtracks, so it works on copies: {@link #copy} must
 * return a state that later {@link #apply} calls cannot change through the original. A state should
 * also implement {@code equals} and {@code hashCode} by value, which lets the search skip a
 * (placed operations, state) pair it already ruled out; arrays are compared by content.
 *
 * @param <M> the model's state
 * @since 2.0.0
 */
@API(status = Status.EXPERIMENTAL, since = "2.0.0")
public interface SequentialSpec<M> {

    /** {@return a fresh model in the state the object under test starts in} */
    M initial();

    /**
     * {@return a copy of {@code state} that {@link #apply} on either cannot change in the other}
     *
     * @param state the state to copy
     */
    M copy(M state);

    /**
     * Applies one operation to {@code state}, as the object would if it ran alone, and returns the
     * result it would give. May mutate {@code state}; the search hands it a copy.
     *
     * @param state     the model's state, to update
     * @param operation the operation's name, as recorded
     * @param argument  the operation's argument, as recorded
     * @return the result the operation returns, compared with the recorded one by
     *         {@link Objects#equals}
     */
    @Nullable Object apply(M state, String operation, @Nullable Object argument);

    /**
     * {@return a spec built from three functions}
     *
     * @param <M>     the model's state
     * @param initial a fresh initial state per call
     * @param copy    a copy of a state
     * @param step    one operation applied to a state, returning its result
     */
    static <M> SequentialSpec<M> of(Supplier<M> initial, UnaryOperator<M> copy, Step<M> step) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(copy, "copy");
        Objects.requireNonNull(step, "step");
        return new SequentialSpec<>() {
            @Override
            public M initial() {
                return initial.get();
            }

            @Override
            public M copy(M state) {
                return copy.apply(state);
            }

            @Override
            public @Nullable Object apply(M state, String operation, @Nullable Object argument) {
                return step.apply(state, operation, argument);
            }
        };
    }

    /**
     * One operation applied to a model state.
     *
     * @param <M> the model's state
     */
    @FunctionalInterface
    interface Step<M> {
        /**
         * {@return the result of {@code operation} applied to {@code state}}
         *
         * @param state     the state, which the step may mutate
         * @param operation the operation's name
         * @param argument  the operation's argument
         */
        @Nullable Object apply(M state, String operation, @Nullable Object argument);
    }
}
