package com.example.agentfixture;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * One method per way of handling {@link InterruptedException}, for the handler weaver to judge.
 *
 * <p>The swallowing shapes neither rethrow nor restore the flag and go on, or return to a caller
 * that never learns of the interrupt. The handled shapes rethrow, wrap and throw, restore the flag
 * directly or through a helper. Every method runs without an interrupt arriving: the judgement is
 * of the handler's code, not of a handler that ran.
 */
public class InterruptHandlingShapes {

    private static final Logger LOG = Logger.getLogger(InterruptHandlingShapes.class.getName());

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    /** Counts the handled-shape calls, so a test can see they ran. */
    public int calls;

    /** Swallows: an empty handler, and execution goes on. */
    public void swallowEmpty() {
        try {
            Thread.sleep(0);
        } catch (InterruptedException e) {
            // nothing
        }
        calls++;
    }

    /** Swallows: logs and goes on. */
    public void swallowLogged() {
        try {
            queue.poll(1, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOG.warning("interrupted: " + e);
        }
        calls++;
    }

    /**
     * Swallows: returns a default to a caller that never learns the thread was interrupted.
     *
     * @return the polled element, or {@code "none"}
     */
    public String swallowByReturning() {
        try {
            String polled = queue.poll(1, TimeUnit.MILLISECONDS);
            return polled == null ? "none" : polled;
        } catch (InterruptedException e) {
            return "none";
        }
    }

    /** Swallows in a multi-catch that also names another exception. */
    public void swallowInAMultiCatch() {
        try {
            sleepOrTimeOut();
        } catch (InterruptedException | TimeoutException e) {
            LOG.fine("gave up");
        }
        calls++;
    }

    private static void sleepOrTimeOut() throws InterruptedException, TimeoutException {
        Thread.sleep(0);
    }

    /**
     * Swallows on one path: the flag is restored only when {@code calls} is negative, which it
     * never is, and the other path returns with the interrupt lost (#996).
     */
    public void restoreOnOnePathOnly() {
        try {
            Thread.sleep(0);
        } catch (InterruptedException e) {
            if (calls < 0) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Swallows: the only call in the handler names an interrupt, {@code acquireUninterruptibly},
     * and sets no flag (#996).
     */
    public void callAnUninterruptibleMethod() {
        try {
            Thread.sleep(0);
        } catch (InterruptedException e) {
            PERMITS.acquireUninterruptibly();
            PERMITS.release();
        }
    }

    private static final java.util.concurrent.Semaphore PERMITS = new java.util.concurrent.Semaphore(1);

    /** Handled: restores the flag. */
    public void restoreTheFlag() {
        try {
            Thread.sleep(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        calls++;
    }

    /** Handled: restores the flag through a helper whose name says so. */
    public void restoreThroughAHelper() {
        try {
            Thread.sleep(0);
        } catch (InterruptedException e) {
            restoreInterrupt();
        }
        calls++;
    }

    private static void restoreInterrupt() {
        Thread.currentThread().interrupt();
    }

    /** Handled: wraps and throws. */
    public void wrapAndThrow() {
        try {
            Thread.sleep(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        calls++;
    }

    /**
     * Handled: rethrows the exception itself.
     *
     * @throws InterruptedException when interrupted
     */
    public void rethrow() throws InterruptedException {
        try {
            Thread.sleep(0);
        } catch (InterruptedException e) {
            LOG.fine("interrupted, passing it on");
            throw e;
        }
        calls++;
    }

    /**
     * Handled: Guava's uninterruptible idiom. The handler notes the interrupt and the loop goes on
     * waiting; the flag is restored in {@code finally} once the wait is over.
     */
    public void restoreLaterInFinally() {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    Thread.sleep(0);
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        calls++;
    }

    /** Handled: a catch of something else entirely is not this rule's concern. */
    public void catchesSomethingElse() {
        try {
            Integer.parseInt("x");
        } catch (NumberFormatException e) {
            LOG.fine("not a number");
        }
        calls++;
    }

    /** A thread's own loop: exiting {@code run()} on an interrupt is its interruption policy. */
    public static class Worker implements Runnable {

        private final BlockingQueue<String> work = new LinkedBlockingQueue<>(java.util.List.of("one"));

        /** Takes work until there is none, and stops when interrupted. */
        @Override
        public void run() {
            while (!work.isEmpty()) {
                try {
                    work.take();
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }
}
