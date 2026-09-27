package com.example.agentfixture;

/**
 * Every shape a constructor returns or delegates in, for the constructor-return marking (#791) to
 * be checked against: each must verify once woven, and each return and delegation must be reported
 * once, with {@code this} and the declaring class.
 */
public class ConstructorReturnSample {

    /** Set on every path, so each return is reached with {@code this} initialised. */
    public final int value;

    /**
     * Two returns: an early one for a negative value, and the one at the end.
     *
     * @param value the value, or a negative one for zero
     */
    public ConstructorReturnSample(int value) {
        if (value < 0) {
            this.value = 0;
            return;
        }
        this.value = value;
    }

    /** Delegates, so the constructor it calls returns first and this one goes on. */
    public ConstructorReturnSample() {
        this(7);
    }

    /**
     * Delegates with an argument that builds another instance of the class, so one constructor call
     * of the class inside this constructor is a construction and the other a delegation.
     *
     * @param nested only tells this constructor apart from the others
     */
    public ConstructorReturnSample(boolean nested) {
        this(new ConstructorReturnSample(1).value + 1);
    }

    /** A subclass, whose instances end their construction at its own constructor's return. */
    public static final class Child extends ConstructorReturnSample {

        /** Calls the superclass constructor, then returns. */
        public Child() {
            super(3);
        }
    }
}
