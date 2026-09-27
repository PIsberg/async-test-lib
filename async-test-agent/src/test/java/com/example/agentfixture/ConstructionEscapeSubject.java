package com.example.agentfixture;

import se.deversity.asynctest.diagnostics.ConstructorSafetyValidator;

import java.util.function.Consumer;

/**
 * A class that records its own construction start for {@link ConstructorSafetyValidator} and never
 * its end, which is how a caller that instruments only the start feeds it; the woven return of its
 * constructor is what records the end (#791).
 *
 * <p>Each constructor hands {@code this} to a callback at a different point, which is where a
 * constructor that leaks its reference does so, or where a pooled thread is busy inside a later
 * construction while another thread reads an earlier, finished instance.
 */
public class ConstructionEscapeSubject {

    /** Assigned last, so a reader that escaped the constructor can see it unset. */
    public String name;

    /**
     * Records its start, then runs {@code duringConstruction}.
     *
     * @param validator          where the start is recorded
     * @param duringConstruction runs with {@code this} after the start is recorded
     */
    public ConstructionEscapeSubject(ConstructorSafetyValidator validator,
                                     Consumer<Object> duringConstruction) {
        validator.recordConstructionStart(this);
        duringConstruction.accept(this);
        this.name = "built";
    }

    /**
     * Runs {@code beforeStart} and only then records its start: a read made in between is the window
     * the stack alone cannot place.
     *
     * @param beforeStart runs with {@code this} before the start is recorded
     * @param validator   where the start is recorded
     */
    public ConstructionEscapeSubject(Consumer<Object> beforeStart,
                                     ConstructorSafetyValidator validator) {
        beforeStart.accept(this);
        validator.recordConstructionStart(this);
        this.name = "built";
    }

    /**
     * Records nothing at all.
     *
     * @param duringConstruction runs with {@code this}
     */
    public ConstructionEscapeSubject(Consumer<Object> duringConstruction) {
        duringConstruction.accept(this);
        this.name = "built";
    }

    /**
     * Delegates to the recording constructor, whose return is not this constructor's, then runs
     * {@code afterDelegation}: a leak there is still inside construction.
     *
     * @param validator       where the delegated-to constructor records the start
     * @param afterDelegation runs with {@code this} once the delegation returned
     * @param marker          only tells this constructor apart from the others
     */
    public ConstructionEscapeSubject(ConstructorSafetyValidator validator,
                                     Consumer<Object> afterDelegation, int marker) {
        this(validator, self -> { });
        afterDelegation.accept(this);
    }

    /** A subclass that leaks itself after its superclass constructor returned. */
    public static final class LeakingSubclass extends ConstructionEscapeSubject {

        /**
         * @param validator     where the superclass constructor records the start
         * @param afterSuper    runs with {@code this} once the superclass constructor returned
         */
        public LeakingSubclass(ConstructorSafetyValidator validator, Consumer<Object> afterSuper) {
            super(validator, self -> { });
            afterSuper.accept(this);
        }
    }
}
