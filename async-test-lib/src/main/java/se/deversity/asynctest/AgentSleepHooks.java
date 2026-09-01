package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

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

    /**
     * Weaves {@code Thread.sleep(Duration)}.
     *
     * <p>The form new code writes since JDK 19, and the reason #440 existed: the entry it needs
     * carries a {@code whenSynchronized} variant, so an overload is not a copy of the plain
     * pattern. Everything else is {@link #sleep(long)} - the guard is what separates a sleep
     * under a lock from rate limiting, back-off and polling.
     *
     * <p>The sleep itself is delegated as a {@code Duration}, not converted to milliseconds
     * first: {@code Thread.sleep(Duration)} rounds sub-millisecond durations up to one
     * millisecond and this hook must not compute something different from the call it replaced.
     *
     * @param duration how long to sleep
     * @throws InterruptedException if interrupted while sleeping
     * @since 1.11.1
     */
    public static void sleep(Duration duration) throws InterruptedException {
        Object held = HeldLocks.topHeld();
        if (held != null) {
            SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
            if (detector != null) {
                detector.recordSleep(recordableMillis(duration), held);
            }
        }
        Thread.sleep(duration);
    }

    /**
     * Weaves {@code Thread.sleep(Duration)} inside a {@code synchronized} method.
     *
     * <p>No guard, for the reason {@link #sleepHoldingMonitor(long, Object)} gives: this is only
     * reached from inside a {@code synchronized} method, so the monitor is held by construction.
     *
     * @param duration how long to sleep
     * @param monitor  the monitor the enclosing synchronized method holds
     * @throws InterruptedException if interrupted while sleeping
     * @since 1.11.1
     */
    public static void sleepHoldingMonitor(Duration duration, Object monitor)
            throws InterruptedException {
        SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
        if (detector != null) {
            detector.recordSleep(recordableMillis(duration), monitor);
        }
        Thread.sleep(duration);
    }

    /**
     * Weaves {@code Thread.sleep(long, int)}.
     *
     * @param millis how long to sleep
     * @param nanos  the additional nanoseconds
     * @throws InterruptedException if interrupted while sleeping
     * @since 1.11.1
     */
    public static void sleep(long millis, int nanos) throws InterruptedException {
        Object held = HeldLocks.topHeld();
        if (held != null) {
            SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
            if (detector != null) {
                detector.recordSleep(recordableMillis(millis, nanos), held);
            }
        }
        Thread.sleep(millis, nanos);
    }

    /**
     * Weaves {@code Thread.sleep(long, int)} inside a {@code synchronized} method.
     *
     * @param millis  how long to sleep
     * @param nanos   the additional nanoseconds
     * @param monitor the monitor the enclosing synchronized method holds
     * @throws InterruptedException if interrupted while sleeping
     * @since 1.11.1
     */
    public static void sleepHoldingMonitor(long millis, int nanos, Object monitor)
            throws InterruptedException {
        SleepInLockDetector detector = AsyncTestContext.currentSleepInLockDetector();
        if (detector != null) {
            detector.recordSleep(recordableMillis(millis, nanos), monitor);
        }
        Thread.sleep(millis, nanos);
    }

    /**
     * {@return the duration to record for a sleep of {@code millis} plus {@code nanos}}
     *
     * <p>Rounded up rather than truncated. {@code recordSleep} drops anything at or below zero,
     * so truncating would make {@code sleep(0, 500_000)} under a lock record nothing at all -
     * silence indistinguishable from a sleep that never happened, which is the failure mode this
     * whole weaving surface exists to avoid. The detector's model is milliseconds, so a sleep
     * shorter than one is reported as one; the alternative is not reporting it.
     *
     * @param millis the milliseconds the caller asked for
     * @param nanos  the additional nanoseconds
     */
    private static long recordableMillis(long millis, int nanos) {
        return millis > 0 || nanos <= 0 ? millis : 1L;
    }

    /**
     * {@return the duration to record for a sleep of {@code duration}}
     *
     * <p>Rounded up for the reason {@link #recordableMillis(long, int)} gives, and null-tolerant
     * because a {@code null} here is the caller's {@code NullPointerException} to receive from
     * {@code Thread.sleep}, not this hook's to throw first from a recording path.
     *
     * @param duration the duration the caller asked for
     */
    private static long recordableMillis(@Nullable Duration duration) {
        if (duration == null) {
            return 0L;
        }
        long millis = duration.toMillis();
        return millis > 0 || duration.isZero() || duration.isNegative() ? millis : 1L;
    }
}
