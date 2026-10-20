package com.example.agentfixture;

/**
 * A worker that races the thread it starts: both increment one field with no lock, then the worker
 * joins the child. Unmodified code; under field weaving each read and write is a scheduling point.
 */
public final class ChildRaceBean {

    private int count;

    /** Starts a child that increments, increments itself, then joins the child. */
    public void raceWithChild() throws InterruptedException {
        Thread child = new Thread(this::increment, "child");
        child.start();
        increment();
        child.join();
    }

    private void increment() {
        count = count + 1;
    }

    /** {@return the count, read after the round} */
    public int count() {
        return count;
    }
}
