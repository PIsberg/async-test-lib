package com.example.agentfixture;

/**
 * Predicate loops whose wait is in another class, reached by a name that is not the waiting
 * class's (#709).
 *
 * <p>Never executed. Both methods are the shape {@code WaitLoopShapesSample.crossMethodLoop}
 * pins inside one class: the test is at the top, a conditional stands between the loop's head and
 * the call, and a {@code goto} closes the loop. The only difference is where the wait lives, so a
 * missing mark here is about resolving the callee and nothing else.
 *
 * <p>Both go unmarked when the call site is matched on the name it carries. That is silent, and it
 * lands on the reporting side: the wait then reads as {@code if (!ready) wait()} and a correct
 * bounded poll can be reported.
 */
public class CrossClassWaitLoopSample {

    private final WaitHelperSubclass inherited = new WaitHelperSubclass();
    private final WaitGate gate = new MonitorWaitGate();
    private boolean ready;

    /**
     * The call site names {@link WaitHelperSubclass}; the wait is declared on
     * {@link WaitHelperBase}. One mark.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void loopThroughInheritedHelper() throws InterruptedException {
        synchronized (inherited.monitor()) {
            long deadline = System.nanoTime() + 20_000_000L;
            while (!ready) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
                if (leftMillis <= 0) {
                    break;
                }
                inherited.awaitOnce(leftMillis);
            }
        }
    }

    /**
     * The call site names {@link WaitGate}; the wait is in {@link MonitorWaitGate}. One mark.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void loopThroughInterface() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000L;
        while (!ready) {
            long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMillis <= 0) {
                break;
            }
            gate.awaitSignal(leftMillis);
        }
    }

    /**
     * A loop around a call that goes nowhere near a wait. No mark, in any resolution.
     *
     * @throws InterruptedException never; declared to match the others
     */
    public void loopThroughSilentHelper() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000L;
        while (!ready) {
            long leftMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMillis <= 0) {
                break;
            }
            inherited.monitor();
        }
    }
}
