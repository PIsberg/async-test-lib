package se.deversity.asynctest;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks for {@link Thread#start()} and {@link Thread#setDaemon(boolean)}, making thread starts
 * and explicit daemon decisions visible to detectors.
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
 * <p>Only a {@code setDaemon} call in a woven class. A daemon flag set anywhere the agent does not
 * weave, by {@code Thread.Builder.OfPlatform.daemon()}, inside the JDK or in a class outside
 * {@code includes=}, is invisible here, so a thread configured that way and started from woven
 * code reads as undecided. {@link #isThreadWeavingInstalled()} gates every use of the absence of
 * a decision, so without the agent nothing changes.
 *
 * @since 1.12.3
 */
@API(status = Status.INTERNAL)
@AIContract(reason = "Called from bytecode the agent rewrites: method names and erased signatures of threadStart and threadSetDaemon are matched by CollectionAccessWeaver.THREAD_ENTRIES, and threadWeavingInstalled is invoked by name from AsyncTestAgent after the transformer is installed; none of them can change independently of the agent. Every hook must perform the original call on the receiver and propagate what it throws unchanged. threadSetDaemon records the decision only after setDaemon returned, so a call that throws (an already started thread) records nothing.")
public final class AgentThreadHooks {

    /** Explicit {@code setDaemon} decisions seen by a woven call site, by thread identity. */
    private static final Map<Thread, Boolean> EXPLICIT_DAEMON =
            Collections.synchronizedMap(new WeakHashMap<>());

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
     * @param receiver the thread to start
     */
    public static void threadStart(Thread receiver) {
        DaemonThreadHygieneDetector detector = AsyncTestContext.currentDaemonThreadHygieneDetector();
        if (detector != null) {
            detector.recordObservedStart(receiver);
        }
        receiver.start();
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
