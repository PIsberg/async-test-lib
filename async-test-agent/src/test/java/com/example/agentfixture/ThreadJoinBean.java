package com.example.agentfixture;

import java.time.Duration;

/**
 * Fixture for {@code Thread.join} (#743): a child thread writes a plain field of a fresh box, and
 * the thread that started it reads the field, after a join or with no join in between.
 *
 * <p>Every call site is in this class, so the weaver substitutes the {@code start} and each
 * {@code join} overload, and reports the child's write and the parent's read. {@link Box#value} is
 * the plain field a detector judges.
 */
public class ThreadJoinBean {

    /** What the child writes and the parent reads. */
    public static final class Box {

        int value;
    }

    /**
     * Starts a child that writes {@code value} into a fresh box, joins it with the overload
     * {@code overload} names, and reads the box: 0 for {@code join()}, 1 for {@code join(long)}, 2
     * for {@code join(long, int)} and 3 for {@code join(Duration)}. The value is a parameter
     * rather than a literal because a store of a constant is judged as an idempotent flag.
     */
    public int readAfterJoin(int value, int overload) throws InterruptedException {
        Box box = new Box();
        Thread child = new Thread(() -> box.value = value, "join-child");
        child.start();
        switch (overload) {
            case 0 -> child.join();
            case 1 -> child.join(10_000L);
            case 2 -> child.join(10_000L, 0);
            default -> {
                if (!child.join(Duration.ofSeconds(10))) {
                    throw new IllegalStateException("the child did not finish");
                }
            }
        }
        if (child.isAlive()) {
            throw new IllegalStateException("the child did not finish");
        }
        return box.value;
    }

    /**
     * Starts the same child, spins until {@code isAlive()} returns {@code false} instead of
     * joining it, and reads the box (#834). A terminated thread's actions happen before another
     * thread's observation that it is no longer alive, so this is as ordered as a join.
     */
    public int readAfterIsAliveSpin(int value) {
        Box box = new Box();
        Thread child = new Thread(() -> box.value = value, "join-child");
        child.start();
        while (child.isAlive()) {
            Thread.onSpinWait();
        }
        return box.value;
    }

    /**
     * Starts a child that writes the box, runs {@code written} and then waits for
     * {@code release}, and reads the box once {@code awaitWritten} returns and {@code isAlive()}
     * said the child is still running (#834): a live answer orders nothing. Joins the child after
     * releasing it.
     */
    public int readWhileAlive(int value, Runnable written, Runnable awaitWritten,
                              Runnable release, Runnable awaitRelease) throws InterruptedException {
        Box box = new Box();
        Thread child = new Thread(() -> {
            box.value = value;
            written.run();
            awaitRelease.run();
        }, "join-child");
        child.start();
        awaitWritten.run();
        if (!child.isAlive()) {
            throw new IllegalStateException("the child finished before the read");
        }
        int read = box.value;
        release.run();
        child.join();
        return read;
    }

    /**
     * Starts the same child and reads the box once {@code awaitWritten} returns, before any join:
     * the child runs {@code written} after its write, and the caller pairs the two through code
     * the weaver does not see, so the only ordering the agent could know about is a join.
     */
    public int readBeforeJoin(int value, Runnable written, Runnable awaitWritten)
            throws InterruptedException {
        Box box = new Box();
        Thread child = new Thread(() -> {
            box.value = value;
            written.run();
        }, "join-child");
        child.start();
        awaitWritten.run();
        int read = box.value;
        child.join();
        return read;
    }
}
