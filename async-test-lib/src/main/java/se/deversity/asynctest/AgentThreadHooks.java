package se.deversity.asynctest;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector;
import se.deversity.asynctest.diagnostics.HappensBefore;
import se.deversity.asynctest.telemetry.TelemetryRegistry;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for {@link Thread#start()}, {@link Thread#join()}, {@link Thread#isAlive()} and
 * {@link Thread#setDaemon(boolean)}, for the starts through {@link Thread.Builder#start} and
 * {@link Thread#startVirtualThread}, for a platform builder's {@code daemon} decision and
 * {@link Thread.Builder#unstarted}, and for a thread constructed in woven code, making thread
 * starts, joins and explicit daemon decisions visible to detectors.
 *
 * <h2>Why these need the agent</h2>
 *
 * <p>A platform thread inherits the daemon flag of the thread that created it. The runner's
 * workers are daemon threads in both platform and virtual modes (#479), so a thread constructed
 * inside a test body is daemon whether or not the body called {@code setDaemon(true)}, and
 * {@link Thread#isDaemon()} cannot tell the two apart. That left
 * {@link DaemonThreadHygieneDetector} and
 * {@link se.deversity.asynctest.diagnostics.ThreadFactoryDetector} nothing to judge for such a
 * thread (#731). Woven, these hooks record the decision itself rather than the flag.
 *
 * <h2>What counts as a decision</h2>
 *
 * <p>A {@code setDaemon} call in a woven class, or {@code daemon(...)} on a platform
 * {@code Thread.Builder} in a woven class, carried to the threads that builder then makes. The
 * absence of one is only evidence for a thread the agent saw constructed (#737): a
 * {@code new Thread}, a {@code Thread} subclass's constructor, or a builder's {@code unstarted} or
 * {@code start}, in a woven class. A thread constructed anywhere else, inside the JDK or in a class
 * outside {@code includes=}, may have been decided where nothing watched, so
 * {@link #constructedInWovenCode(Thread)} is {@code false} for it and it is judged by its flag
 * alone. A decision made where nothing is woven on a thread woven code constructed is still
 * invisible. {@link #isThreadWeavingInstalled()} gates every use of the absence of a decision, so
 * without the agent nothing changes.
 *
 * <h2>Ordering</h2>
 *
 * <p>A start and a returned join are the two happens-before edges the Java memory model gives a
 * thread's lifecycle, and both are reported to {@link HappensBefore}: the start as a fork before
 * the thread runs, the join as a join once the thread has finished. A timed join that returned
 * with the thread still alive orders nothing and reports nothing. An {@code isAlive} that returned
 * {@code false} is the same edge as a returned join, since everything a terminated thread did
 * happens before another thread learns that it terminated, so a parent that polls instead of
 * joining is ordered too (#834); one that returned {@code true} orders nothing.
 *
 * @since 1.12.3
 */
@API(status = Status.INTERNAL)
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures of the hooks CollectionAccessWeaver.THREAD_ENTRIES substitutes (threadStart, threadJoin, threadIsAlive, threadSetDaemon, threadStartVirtual and the builder's threadBuilderStart, threadBuilderDaemon and threadBuilderUnstarted) and of threadConstructed, which ThreadConstructionWeaver inserts after each thread a woven class constructs, are matched at weave time, and threadWeavingInstalled is invoked by name from AsyncTestAgent after the transformer is installed; none of them can change independently of the agent. Every substituting hook must perform the original call on the receiver, or what the JDK does for it (a builder's start is unstarted then start), and propagate what it throws unchanged. threadConstructed replaces nothing and runs inside user constructors, so it performs no call and must not throw. threadSetDaemon records the decision only after setDaemon returned, so a call that throws (an already started thread) records nothing.")
public final class AgentThreadHooks {

    /** Explicit {@code setDaemon} decisions seen by a woven call site, by thread identity. */
    private static final Map<Thread, Boolean> EXPLICIT_DAEMON =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Platform builders a woven call site gave a {@code daemon} decision, by builder identity;
     * copied to each thread the builder then makes, as the builder itself applies it to them.
     */
    private static final Map<Thread.Builder, Boolean> BUILDER_DAEMON =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Platform threads the agent saw constructed in a woven class, by identity. */
    private static final Set<Thread> CONSTRUCTED_IN_WOVEN_CODE =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** Whether the agent installed the thread substitutions in this JVM. */
    private static volatile boolean threadWeavingInstalled;

    private AgentThreadHooks() {
    }

    /**
     * Called by the agent once the transformer carrying the thread substitutions is installed.
     *
     * <p>Not {@code TelemetryRegistry.start()}: the registry starts for every attach, including
     * one without {@code collections=true}, where {@code setDaemon} is not woven and the absence
     * of a recorded decision says nothing.
     */
    public static void threadWeavingInstalled() {
        threadWeavingInstalled = true;
    }

    /**
     * {@return whether woven {@code setDaemon} calls are being recorded in this JVM}
     *
     * <p>Until they are, a missing decision must be read as "not observed", never as "not made".
     */
    public static boolean isThreadWeavingInstalled() {
        return threadWeavingInstalled;
    }

    /**
     * Weaves {@link Thread#start()}.
     *
     * <p>Besides the edge, the start tells the telemetry bridge that the child works for this
     * thread, so a child a worker starts is attributed to the worker's run (#745).
     *
     * <p>Only a thread the agent also saw constructed is an observed start for
     * {@link DaemonThreadHygieneDetector}: one constructed where nothing is woven may have been
     * given {@code setDaemon(true)} there, so it is recorded the way a hand recording is, and its
     * flag speaks for it (#737).
     *
     * @param receiver the thread to start
     */
    public static void threadStart(Thread receiver) {
        DaemonThreadHygieneDetector detector = AsyncTestContext.currentDaemonThreadHygieneDetector();
        if (detector != null) {
            if (CONSTRUCTED_IN_WOVEN_CODE.contains(receiver)) {
                detector.recordObservedStart(receiver);
            } else {
                detector.recordThread(receiver, null);
            }
        }
        forkAndAttribute(receiver);
        receiver.start();
    }

    /**
     * Called right after a woven class constructs a platform thread (#737): after a
     * {@code new Thread(...)}, and after the superclass constructor call in the constructor of a
     * {@code Thread} subclass. An inserted call rather than a substitution, so there is no
     * original call to perform.
     *
     * @param thread the thread just constructed; never {@code null}, since the weaver hands over
     *               the reference the constructor initialised
     */
    public static void threadConstructed(Thread thread) {
        CONSTRUCTED_IN_WOVEN_CODE.add(thread);
    }

    /**
     * {@return whether the agent saw {@code thread} constructed in a woven class, which is what
     * makes a missing daemon decision on it evidence rather than a blind spot}
     *
     * @param thread the thread to check
     */
    public static boolean constructedInWovenCode(Thread thread) {
        return CONSTRUCTED_IN_WOVEN_CODE.contains(thread);
    }

    /**
     * Weaves {@link Thread.Builder.OfPlatform#daemon(boolean)} (#737). The JDK keeps the decision
     * on the builder and applies it inside {@code unstarted}, where nothing is woven, so it is
     * kept here as well and carried to the thread by {@link #threadBuilderUnstarted}.
     *
     * @param receiver the builder the call site invoked
     * @param on       whether the threads it makes from now on are daemon
     * @return what the builder returned, which is the builder itself
     */
    public static Thread.Builder.OfPlatform threadBuilderDaemon(Thread.Builder.OfPlatform receiver,
                                                                boolean on) {
        Thread.Builder.OfPlatform result = receiver.daemon(on);
        BUILDER_DAEMON.put(receiver, on);
        return result;
    }

    /**
     * Weaves {@link Thread.Builder.OfPlatform#daemon()}, which is {@code daemon(true)}.
     *
     * @param receiver the builder the call site invoked
     * @return what the builder returned, which is the builder itself
     */
    public static Thread.Builder.OfPlatform threadBuilderDaemon(Thread.Builder.OfPlatform receiver) {
        Thread.Builder.OfPlatform result = receiver.daemon();
        BUILDER_DAEMON.put(receiver, Boolean.TRUE);
        return result;
    }

    /**
     * Weaves {@link Thread.Builder#unstarted(Runnable)} (#737): the thread counts as constructed
     * in the woven class that called it, and takes as its own the daemon decision a woven call
     * site gave the builder, if any.
     *
     * @param receiver the builder the call site invoked
     * @param task     what the thread will run; the builder rejects {@code null}
     * @return the unstarted thread
     */
    public static Thread threadBuilderUnstarted(Thread.Builder receiver, Runnable task) {
        Thread thread = receiver.unstarted(task);
        if (!thread.isVirtual()) {
            Boolean daemon = BUILDER_DAEMON.get(receiver);
            if (daemon != null) {
                EXPLICIT_DAEMON.put(thread, daemon);
            }
            CONSTRUCTED_IN_WOVEN_CODE.add(thread);
        }
        return thread;
    }

    /**
     * The start's edge and attribution, for a thread about to be started.
     *
     * <p>Only a thread that has not started yet: a second start throws, and a fork recorded for a
     * thread already running would be taken up by it as an edge that never existed.
     */
    private static void forkAndAttribute(Thread thread) {
        if (thread.getState() == Thread.State.NEW) {
            HappensBefore.fork(thread);
            TelemetryRegistry.threadStarting(thread);
        }
    }

    /**
     * Weaves {@link Thread.Builder#start(Runnable)} (#834), which the JDK implements as
     * {@code unstarted(task)} followed by {@code start()}: this does the same through the woven
     * forms of both, so the start carries the fork and the attribution, and the thread carries the
     * daemon decision a woven call site gave the builder (#737). {@code Thread.Builder} is sealed
     * to the JDK's two builders.
     *
     * @param receiver the builder the call site invoked
     * @param task     what the thread runs; the builder rejects {@code null}
     * @return the started thread
     */
    public static Thread threadBuilderStart(Thread.Builder receiver, Runnable task) {
        Thread thread = threadBuilderUnstarted(receiver, task);
        threadStart(thread);
        return thread;
    }

    /**
     * Weaves {@link Thread#startVirtualThread(Runnable)} (#834): an unnamed virtual thread from
     * the default builder, which is what the JDK method creates, forked and attributed like a
     * woven {@code Thread.start} and then started.
     *
     * @param task what the thread runs; {@code null} is rejected as by the JDK method
     * @return the started thread
     */
    public static Thread threadStartVirtual(Runnable task) {
        Thread thread = Thread.ofVirtual().unstarted(task);
        forkAndAttribute(thread);
        thread.start();
        return thread;
    }

    /**
     * Weaves {@link Thread#isAlive()} (#834). A {@code false} answer about a thread that ran is an
     * acquire of everything it did, as a returned join is; a {@code true} one orders nothing, and
     * {@link HappensBefore#join} ignores a thread that never started.
     *
     * @param receiver the thread asked about
     * @return whether it is alive
     */
    public static boolean threadIsAlive(Thread receiver) {
        boolean alive = receiver.isAlive();
        if (!alive) {
            HappensBefore.join(receiver);
        }
        return alive;
    }

    /**
     * Weaves {@link Thread#join()}.
     *
     * @param receiver the thread to join
     * @throws InterruptedException if interrupted while waiting
     */
    public static void threadJoin(Thread receiver) throws InterruptedException {
        receiver.join();
        HappensBefore.join(receiver);
    }

    /**
     * Weaves {@link Thread#join(long)}, which may return with the thread still running; the
     * model then ignores the join.
     *
     * @param receiver the thread to join
     * @param millis   how long to wait
     * @throws InterruptedException if interrupted while waiting
     */
    public static void threadJoin(Thread receiver, long millis) throws InterruptedException {
        receiver.join(millis);
        HappensBefore.join(receiver);
    }

    /**
     * Weaves {@link Thread#join(long, int)}.
     *
     * @param receiver the thread to join
     * @param millis   how long to wait
     * @param nanos    additional nanoseconds to wait
     * @throws InterruptedException if interrupted while waiting
     */
    public static void threadJoin(Thread receiver, long millis, int nanos)
            throws InterruptedException {
        receiver.join(millis, nanos);
        HappensBefore.join(receiver);
    }

    /**
     * Weaves {@link Thread#join(Duration)}.
     *
     * @param receiver the thread to join
     * @param duration how long to wait
     * @return whether the thread terminated
     * @throws InterruptedException if interrupted while waiting
     */
    public static boolean threadJoin(Thread receiver, Duration duration)
            throws InterruptedException {
        boolean terminated = receiver.join(duration);
        if (terminated) {
            HappensBefore.join(receiver);
        }
        return terminated;
    }

    /**
     * Weaves {@link Thread#setDaemon(boolean)}.
     *
     * @param receiver the thread
     * @param daemon   whether the thread should be daemon
     */
    public static void threadSetDaemon(Thread receiver, boolean daemon) {
        receiver.setDaemon(daemon);
        EXPLICIT_DAEMON.put(receiver, daemon);
    }

    /**
     * {@return the flag a woven {@code setDaemon} call last set on {@code thread}, or
     * {@code null} if none was observed}
     *
     * @param thread the thread to check
     */
    public static @Nullable Boolean explicitDaemonSetting(Thread thread) {
        return EXPLICIT_DAEMON.get(thread);
    }
}
