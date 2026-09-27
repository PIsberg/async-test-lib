package com.example.agentfixture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Fixture for work a test body hands to threads of its own (#745): a child thread it starts, a task
 * it submits to an executor, and a child that outlives the run that started it.
 *
 * <p>Every call site is in this class, so the weaver substitutes the {@code start}, the
 * {@code join}, the {@code submit} and the {@code get}, and reports each access to
 * {@link Box#value}, the plain field a detector judges. The stored value is always a parameter,
 * because a store of a constant is judged as an idempotent flag.
 */
public class SpawnedWorkBean {

    /** What the spawned side writes and the spawning side reads. */
    public static final class Box {

        int value;
    }

    /** Starts a child that writes a fresh box, joins it, and reads the box. */
    public int childReadAfterJoin(int value) throws InterruptedException {
        Box box = new Box();
        Thread child = new Thread(() -> box.value = value, "spawned-child");
        child.start();
        child.join();
        return box.value;
    }

    /**
     * Starts the same child and reads the box once {@code awaitWritten} returns, before the join;
     * the child runs {@code written} after its write, through code the weaver does not see.
     */
    public int childReadBeforeJoin(int value, Runnable written, Runnable awaitWritten)
            throws InterruptedException {
        Box box = new Box();
        Thread child = new Thread(() -> {
            box.value = value;
            written.run();
        }, "spawned-child");
        child.start();
        awaitWritten.run();
        int read = box.value;
        child.join();
        return read;
    }

    /**
     * Starts {@code task} the way {@code how} names (#834): 0 through
     * {@code Thread.ofPlatform().start}, 1 through {@code Thread.ofVirtual().start}, 2 through
     * {@code Thread.startVirtualThread}.
     */
    private static Thread startBy(int how, Runnable task) {
        return switch (how) {
            case 0 -> Thread.ofPlatform().name("built-child").start(task);
            case 1 -> Thread.ofVirtual().name("built-child").start(task);
            default -> Thread.startVirtualThread(task);
        };
    }

    /** As {@link #childReadAfterJoin}, with the child started the way {@code how} names. */
    public int builtChildReadAfterJoin(int how, int value) throws InterruptedException {
        Box box = new Box();
        Thread child = startBy(how, () -> box.value = value);
        child.join();
        return box.value;
    }

    /** As {@link #childReadBeforeJoin}, with the child started the way {@code how} names. */
    public int builtChildReadBeforeJoin(int how, int value, Runnable written,
                                        Runnable awaitWritten) throws InterruptedException {
        Box box = new Box();
        Thread child = startBy(how, () -> {
            box.value = value;
            written.run();
        });
        awaitWritten.run();
        int read = box.value;
        child.join();
        return read;
    }

    /** Submits a task that writes a fresh box, gets its future, and reads the box. */
    public int taskReadAfterGet(ExecutorService executor, int value)
            throws InterruptedException, ExecutionException {
        Box box = new Box();
        Future<?> task = executor.submit(() -> {
            box.value = value;
        });
        task.get();
        return box.value;
    }

    /**
     * Submits the same task and reads the box once {@code awaitWritten} returns, before the get;
     * the task runs {@code written} after its write, through code the weaver does not see.
     */
    public int taskReadBeforeGet(ExecutorService executor, int value, Runnable written,
                                 Runnable awaitWritten)
            throws InterruptedException, ExecutionException {
        Box box = new Box();
        Future<?> task = executor.submit(() -> {
            box.value = value;
            written.run();
        });
        awaitWritten.run();
        int read = box.value;
        task.get();
        return read;
    }

    /** {@return a fresh box} */
    public Box newBox() {
        return new Box();
    }

    /**
     * Starts a child that waits for {@code release} to return and then writes {@code box}; the
     * caller joins it through code the weaver does not see.
     */
    public Thread startLingering(Box box, int value, Runnable release) {
        Thread child = new Thread(() -> {
            release.run();
            box.value = value;
        }, "lingering-child");
        child.start();
        return child;
    }

    /** {@return what {@code box} holds} */
    public int read(Box box) {
        return box.value;
    }
}
