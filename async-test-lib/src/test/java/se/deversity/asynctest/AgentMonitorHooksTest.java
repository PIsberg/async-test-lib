package se.deversity.asynctest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hooks behind a woven {@code Object.wait}, {@code notify} and {@code notifyAll} (#694).
 *
 * <p>Two things are asserted directly rather than through a woven fixture. A hook must make the
 * call it replaced, exceptions included, because it runs in place of the caller's own instruction.
 * And what it records must be enough for the detector to separate an {@code if} wait from a loop's
 * with nothing else said, which is the sequence the agent module's {@code MissedSignalWeavingTest}
 * and its twin then produce from real bytecode.
 */
class AgentMonitorHooksTest {

    /** Long enough that a dropped delegation cannot be mistaken for a coarse clock. */
    private static final long WAIT_MILLIS = 50L;

    /** The floor asserted against, below the requested time only to absorb clock granularity. */
    private static final long FLOOR_MILLIS = 30L;

    private final Object monitor = new Object();

    @Test
    @DisplayName("the timed wait hooks wait for at least the time they were asked for")
    void timedWaitHooksWait() throws InterruptedException {
        long start = System.nanoTime();
        synchronized (monitor) {
            AgentMonitorHooks.monitorWait(monitor, WAIT_MILLIS);
        }
        long first = (System.nanoTime() - start) / 1_000_000L;

        start = System.nanoTime();
        synchronized (monitor) {
            AgentMonitorHooks.monitorWait(monitor, WAIT_MILLIS, 0);
        }
        long second = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(first >= FLOOR_MILLIS, "wait(long) returned after " + first + "ms");
        assertTrue(second >= FLOOR_MILLIS, "wait(long, int) returned after " + second + "ms");
    }

    @Test
    @DisplayName("the notify hooks wake a thread waiting through the wait hook")
    void notifyHooksWakeAWaiter() throws InterruptedException {
        for (boolean all : new boolean[] {false, true}) {
            CountDownLatch woken = new CountDownLatch(1);
            Thread waiter = new Thread(() -> {
                synchronized (monitor) {
                    try {
                        AgentMonitorHooks.monitorWait(monitor);
                        woken.countDown();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            waiter.start();
            while (waiter.getState() != Thread.State.WAITING) {
                Thread.onSpinWait();
            }
            synchronized (monitor) {
                if (all) {
                    AgentMonitorHooks.monitorNotifyAll(monitor);
                } else {
                    AgentMonitorHooks.monitorNotify(monitor);
                }
            }
            assertTrue(woken.await(10, TimeUnit.SECONDS),
                    (all ? "monitorNotifyAll" : "monitorNotify") + " must make the call it replaced");
            waiter.join();
        }
    }

    @Test
    @DisplayName("a call made without the monitor throws what the original call throws")
    void withoutTheMonitorTheHooksThrow() {
        assertThrows(IllegalMonitorStateException.class, () -> AgentMonitorHooks.monitorWait(monitor));
        assertThrows(IllegalMonitorStateException.class,
                () -> AgentMonitorHooks.monitorWait(monitor, 1L));
        assertThrows(IllegalMonitorStateException.class,
                () -> AgentMonitorHooks.monitorWait(monitor, 1L, 0));
        assertThrows(IllegalMonitorStateException.class,
                () -> AgentMonitorHooks.monitorNotify(monitor));
        assertThrows(IllegalMonitorStateException.class,
                () -> AgentMonitorHooks.monitorNotifyAll(monitor));
    }

    @Test
    @DisplayName("a wait with no back-edge after a lost notify is reported, and a loop's is not")
    void theRecordedSequenceSeparatesAnIfFromALoop() throws InterruptedException {
        assertTrue(missedSignalAfter(false), "if (!ready) wait() after a notify nobody heard");
        assertFalse(missedSignalAfter(true), "while (!ready) wait(): the back-edge confirms the loop");
    }

    /** Runs notify, then a timed wait, then optionally the back-edge, and {@return whether it is a finding}. */
    private boolean missedSignalAfter(boolean loop) throws InterruptedException {
        AsyncTestContext context =
                new AsyncTestContext(AsyncTestConfig.builder().detectMissedSignals(true).build());
        AsyncTestContext.install(context);
        try {
            context.markInvocationStart();
            synchronized (monitor) {
                AgentMonitorHooks.monitorNotifyAll(monitor);
            }
            synchronized (monitor) {
                AgentMonitorHooks.monitorWait(monitor, 1L);
                if (loop) {
                    AgentMonitorHooks.loopBackEdge();
                }
            }
            return AsyncTestContext.missedSignalDetector().analyze().hasIssues();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("with no context installed the back-edge hook does nothing")
    void backEdgeWithoutAContextIsANoOp() {
        AgentMonitorHooks.loopBackEdge();
    }
}
