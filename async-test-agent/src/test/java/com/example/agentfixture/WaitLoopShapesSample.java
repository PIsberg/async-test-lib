package com.example.agentfixture;

/**
 * One method per bytecode shape a {@code wait} can sit in, for the weaver-level back-edge gate.
 *
 * <p>Never executed. {@code MissedSignalBackEdgeWeavingTest} weaves this class and reads the
 * instructions back, which is the only way to pin the shapes that do not terminate
 * ({@link #endlessWait}) and the only way to see the decision rather than a detector's reading of
 * it. The runtime twins live in {@code LoopWaitHandOffBean} and its siblings.
 */
public class WaitLoopShapesSample {

    private final Object monitor = new Object();
    private boolean ready;
    private boolean bail;

    /** A predicate loop: the test is at the top and a goto closes it. One back-edge mark. */
    public void whileLoop() throws InterruptedException {
        synchronized (monitor) {
            long deadline = System.nanoTime() + 20_000_000L;
            while (!ready) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    break;
                }
                monitor.wait(leftMillis);
            }
        }
    }

    /** The same poll written as do/while: waits before it reads the predicate. No mark. */
    public void doWhileLoop() throws InterruptedException {
        synchronized (monitor) {
            long deadline = System.nanoTime() + 20_000_000L;
            do {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    break;
                }
                monitor.wait(leftMillis);
            } while (!ready);
        }
    }

    /**
     * The do/while {@code DoWhileWaitHandOffBean} runs: the deadline clamps the wait instead of
     * guarding it, so the wait is always reached. Still no mark.
     */
    public void doWhileAlwaysWaits() throws InterruptedException {
        synchronized (monitor) {
            long deadline = System.nanoTime() + 20_000_000L;
            do {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                monitor.wait(leftMillis > 0L ? leftMillis : 1L);
            } while (!ready && System.nanoTime() < deadline);
        }
    }

    /** Closed by continue, so the back-edge is a goto, but nothing is read first. No mark. */
    public void continueLoop() throws InterruptedException {
        synchronized (monitor) {
            long deadline = System.nanoTime() + 20_000_000L;
            while (true) {
                monitor.wait(10);
                if (!ready && System.nanoTime() < deadline) {
                    continue;
                }
                break;
            }
        }
    }

    /** A goto back-edge with no predicate anywhere. No mark, and no way to run it. */
    public void endlessWait() throws InterruptedException {
        synchronized (monitor) {
            while (true) {
                monitor.wait(10);
            }
        }
    }

    /** No loop at all: the shape the back-edge exists to tell apart. No mark. */
    public void ifGuarded() throws InterruptedException {
        synchronized (monitor) {
            if (!ready) {
                monitor.wait(20);
            }
        }
    }

    /**
     * A {@code break} compiled immediately in front of a {@code do}/{@code while}. No mark.
     *
     * <p>The one shape that separates the rotated-loop rule from a rule that merely looks for a
     * {@code goto} in front of the loop head: this has one, left by the {@code break}, but it
     * jumps clear of the loop instead of into its test. Loosening the rotated rule's range check
     * marks this {@code do}/{@code while} and stops reporting the bug it holds (#710).
     */
    public void breakThenDoWhile() throws InterruptedException {
        synchronized (monitor) {
            long deadline = System.nanoTime() + 20_000_000L;
            while (true) {
                if (bail) {
                    break;
                }
                do {
                    long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                    monitor.wait(leftMillis > 0L ? leftMillis : 1L);
                } while (!ready && System.nanoTime() < deadline);
                break;
            }
        }
    }

    /** The loop is here and the wait is in {@link #awaitOnce}. One mark, on this method. */
    public void crossMethodLoop() throws InterruptedException {
        synchronized (monitor) {
            long deadline = System.nanoTime() + 20_000_000L;
            while (!ready) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    break;
                }
                awaitOnce(leftMillis);
            }
        }
    }

    /** Declared after its caller on purpose: a single pass cannot know this waits. */
    private void awaitOnce(long millis) throws InterruptedException {
        monitor.wait(millis);
    }
}
