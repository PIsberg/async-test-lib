package com.example.agentfixture;

/**
 * Starts a thread it never joins, which fails an assertion a little later: after a one-round run
 * has made its last check (#1379). Woven, so the start is the agent's to attribute.
 */
public final class LateDyingChild {

    /** The assertion the child fails with. */
    public static final String MESSAGE = "a body-started thread failed after the run's last check";

    private LateDyingChild() {
    }

    /** Starts the child and returns at once. */
    public static void startUnjoined() {
        new Thread(() -> {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError(MESSAGE);
        }, "late-dying-child").start();
    }
}
