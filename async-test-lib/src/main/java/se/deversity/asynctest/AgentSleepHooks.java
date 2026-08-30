package se.deversity.asynctest;

import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.asynctest.diagnostics.SleepInLockDetector;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for the static calls a detector's input maps onto.
 *
 * <h2>Why a sleep needs the lockset rather than a stack walk</h2>
 *
 * <p>{@code SleepInLockDetector} reports a {@code Thread.sleep} performed while a lock is held:
 * throughput collapses to one caller per sleep and every other caller queues behind it. Answering
 * "was a lock held" has always been the hard half - a stack trace does not say, and asking
 * {@code Thread.holdsLock} requires already knowing which object to ask about.
 *
 * <p>The agent has the answer for free. {@link se.deversity.asynctest.AgentLockHooks} maintains the
 * per-thread lockset that the woven {@code MONITORENTER} instructions and the substituted
 * {@code Lock.lock()} calls both feed, so {@link HeldLocks#topHeld()} is an array read on the
 * calling thread. This hook is the two halves meeting: the sleep is seen by substitution, and
 * whether it mattered is answered by the lockset that was already there.
 *
 * <h2>What is deliberately not recorded</h2>
 *
 * <p>A sleep with no lock held is not the bug and records nothing. Rate limiting, back-off and
 * polling are all ordinary uses of {@code Thread.sleep}, and reporting them would make the finding
 * worthless.
 *
 * @since 1.10.0
 */
@AIContract(reason = "Called from bytecode the agent rewrites, through the static substitution path: the method name and erased signature here are matched by CollectionAccessWeaver.STATIC_ENTRIES and cannot change independently of it. The guard is the point - recording only when HeldLocks.topHeld() returns a lock is what separates the bug from rate limiting, back-off and polling, which are ordinary uses of Thread.sleep and vastly more common. Record before sleeping, not after: a sleep interrupted mid-way still held the lock for as long as it lasted, and the finding is about the holding rather than the completing. The hook must perform the original sleep and propagate InterruptedException unchanged. It must pass the monitor to recordSleep rather than calling the single-argument overload: that one resolves the held monitors through ThreadMXBean, which does not report virtual threads, so on the runner's default workers it answers none and the detector never fires.")
public final class AgentSleepHooks {

    private AgentSleepHooks() {
    }

    /**
     * Weaves {@code Thread.sleep(long)}.
     *
     * @param millis how long to sleep
     * @throws InterruptedException if interrupted while sleeping
     */
    public static void sleep(long millis) throws InterruptedException {
        Object held = HeldLocks.topHeld();
        if (held != null) {
            SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
            if (detector != null) {
                // The two-argument overload, not recordSleep(millis). That one asks ThreadMXBean
                // which monitors the thread holds, and ThreadMXBean does not report virtual
                // threads - which the runner uses by default, so it would answer "none" for
                // every worker and the finding would never fire. Naming the monitor routes it
                // through Thread.holdsLock instead, which has no such blind spot.
                detector.recordSleep(millis, held);
            }
        }
        Thread.sleep(millis);
    }

    /**
     * Weaves {@code Thread.sleep(long)} inside a {@code synchronized} method.
     *
     * <p>A {@code synchronized} method takes its monitor from the {@code ACC_SYNCHRONIZED} access
     * flag, not from an instruction, so nothing in the body tells {@link HeldLocks} the lock is
     * held and {@link HeldLocks#topHeld()} answers {@code null} inside one. That is #388, and the
     * reason it looked unfixable was the assumption that the lockset had to learn the monitor:
     * pushing on method entry and popping on every exit needs a handler and a branch, which needs
     * new stack map frames, which {@code AsyncTestAgent} rules out.
     *
     * <p>The lockset does not have to learn it. The weaver already knows, at weave time, both that
     * the enclosing method is synchronized and what it locks - {@code this} for an instance method
     * and the class for a static one - so it loads that monitor and calls this overload instead.
     * One more value on the stack, no branch and no handler, which is what
     * {@code COMPUTE_MAXS, never COMPUTE_FRAMES} permits.
     *
     * <p>There is no guard here, and there must not be: this method is only ever reached from
     * inside a {@code synchronized} method, so the monitor is held by construction. Consulting the
     * lockset would ask the question the caller already answered, and get {@code null}.
     *
     * @param millis  how long to sleep
     * @param monitor the monitor the enclosing synchronized method holds
     * @throws InterruptedException if interrupted while sleeping
     * @since 1.11.0
     */
    public static void sleepHoldingMonitor(long millis, Object monitor) throws InterruptedException {
        SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
        if (detector != null) {
            detector.recordSleep(millis, monitor);
        }
        Thread.sleep(millis);
    }
}
