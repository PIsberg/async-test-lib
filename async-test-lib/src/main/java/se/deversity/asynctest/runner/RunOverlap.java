package se.deversity.asynctest.runner;

/**
 * Whether another run was in progress at some moment of this one (#1385).
 *
 * <p>A deadlock is visible JVM-wide, and a run can tell its own only by its threads. A thread the
 * body starts is known to the run only with the agent, so a cycle with no known member is still the
 * run's when nothing else ran beside it, and someone else's otherwise. This is that "nothing else".
 *
 * <p>{@link #enter()} and {@link #exit()} pair in a {@code finally}, like
 * {@code BodyThreadFailures}. The answer is frozen at {@link #exit()}, because a passing run is
 * analysed after it, and a run that starts later did not overlap this one.
 */
final class RunOverlap {

    private static final Object LOCK = new Object();

    /** Runs between enter and exit. Guarded by LOCK. */
    private static int inProgress;

    /** Runs ever entered. Guarded by LOCK. */
    private static long entered;

    private final boolean othersInProgressAtEntry;
    private final long enteredAs;
    private boolean exited;
    private boolean overlappedAtExit;

    private RunOverlap(boolean othersInProgressAtEntry, long enteredAs) {
        this.othersInProgressAtEntry = othersInProgressAtEntry;
        this.enteredAs = enteredAs;
    }

    /** {@return a run that has just started; pair with {@link #exit()} in a {@code finally}} */
    static RunOverlap enter() {
        synchronized (LOCK) {
            boolean others = inProgress > 0;
            inProgress++;
            entered++;
            return new RunOverlap(others, entered);
        }
    }

    /**
     * {@return whether another run was in progress at some moment between {@link #enter()} and now,
     * or {@link #exit()} once that has been called}
     */
    boolean overlapped() {
        synchronized (LOCK) {
            return exited ? overlappedAtExit : othersInProgressAtEntry || entered != enteredAs;
        }
    }

    /** Ends this run. A second call does nothing. */
    void exit() {
        synchronized (LOCK) {
            if (exited) {
                return;
            }
            overlappedAtExit = othersInProgressAtEntry || entered != enteredAs;
            exited = true;
            left();
        }
    }

    /** One run fewer in progress. Called under LOCK. */
    private static void left() {
        inProgress--;
    }
}
