package se.deversity.asynctest.diagnostics;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Feeds {@link VirtualThreadPinningDetector} from the JVM's own {@code jdk.VirtualThreadPinned}
 * JFR event, so a pin is reported without the test body describing it.
 *
 * <p>Without this the detector saw only the pins a body passed to
 * {@link VirtualThreadPinningDetector#recordPinningEvent}, which are the pins its author already
 * knew about. The event is the JVM's verdict on the running JDK: it fires for a
 * {@code synchronized} block up to JDK 23 and not from 24 (JEP 491), and for a class initializer
 * or a native frame on every JDK, so an observed pin is never annotated as obsolete. A 2026-10-10
 * probe confirmed those three cases on JDK 21, 24 and 26.
 *
 * <p><strong>Only a site that pins in two rounds is reported.</strong> The first CI run of this
 * feed reported hundreds of pins on correct code: workers contending on
 * {@code ClassLoader.loadClass}, and a library's first use (Netty, Jackson, the detectors' own lazy
 * state), mostly 0-5 ms and up to 36 ms. Those are real pins, but a class loads and initializes
 * once per JVM, so they can only happen in one round, while a pin in the code under test recurs.
 * The site is the first frame outside the JDK, and a pin recurs only where it recurs at the same
 * site and the same top frame: two JDK classes first used under one call site in two rounds are
 * two one-time initializations, not one pin seen twice. A run of one round therefore gets no
 * observed finding, only {@link #singleRoundSites()}.
 *
 * <p><strong>A pin inside the agent's own transformer is not the code's.</strong> With the agent
 * attached, every class definition runs its transformer under the native {@code defineClass} frame,
 * and a contended monitor there (the agent's per-loader bookkeeping) pins the virtual thread that
 * is defining the class. The first corpus pair for this detector saw 235 such pins on a body that
 * defines a fresh class in every round, all with the agent's {@code UpdaterAccess.openTo} as the
 * first frame outside the JDK. Code in the agent's package runs only inside its transformer and
 * install (the woven hooks live in the library), so a pin whose site is there would not exist
 * without the agent; nor would one whose first frame outside the JDK is library code other than a
 * woven hook, such as the lockset bookkeeping of a woven monitor enter, which the corpus also met.
 * Both are counted in {@link #instrumentationEvents()} and not reported (#984).
 *
 * <p><strong>Nor is a JDK lock the JVM contended on for itself.</strong> While the JVM loads,
 * links or initializes a class it keeps the virtual thread from unmounting, a continuation
 * critical section, and only the JDK's own code can enter one otherwise ({@code Continuation.pin()}
 * is internal). A monitor enter it could not preempt there is reported on JDK 24-26 as
 * {@code Freeze or preempt failed (2) (Contended monitor enter)}, code 2 being that section; with
 * a JDK frame on top, the lock is the JDK's: a class's init lock taken at the JDK instruction that
 * first used the class ({@code CompletableFuture.waitingGet} creating a {@code Signaller}, on
 * correct code in Corpus Eval on JDK 25), or the application loader's per-name lock under
 * {@code Class.forName}, which probes on JDK 24 and 26 saw pin in every round of a body loading a
 * new class per round. Those are counted in {@link #criticalSectionEvents()} and not reported
 * (#1028). The same reason with the body's frame on top, a loader the code wrote taking a monitor
 * of its own, is the code's lock and goes through the two-round rule like any other pin.
 *
 * <p>JFR hands every event to every open stream, so two runs in parallel each see the other's
 * pins. Only events on a thread in the run's worker set, or on a thread one of the run's threads
 * started through a woven {@code Thread.start} (the agent records those, #983), are kept; the rest
 * are counted in {@link #unattributedEvents()}. The events arrive in batches, and none had arrived before
 * {@code stop()} in the probe while all had when it returned, so {@link #close()} is what makes
 * them visible to an analysis that follows it.
 *
 * <p>Internal: {@code ConcurrencyRunner} opens one per run. Public only because the runner lives
 * in another package.
 *
 * @since 1.13.2
 */
@API(status = Status.INTERNAL, since = "1.13.2")
public final class JfrPinningStream implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JfrPinningStream.class);
    private static final AtomicBoolean UNAVAILABLE_LOGGED = new AtomicBoolean();

    private final Live live;

    private JfrPinningStream(Live live) {
        this.live = live;
    }

    /**
     * Starts streaming pinning events for {@code target}.
     *
     * @param target   the detector the reported pins go to
     * @param workers  the {@link Thread#threadId()} of each of the run's workers, added by each
     *                 worker as it starts; read, never written
     * @param testName the test the stream serves, for the log
     * @return the open stream, or {@code null} when this JVM has no usable JFR (a runtime image
     *         without {@code jdk.jfr}, or JFR disabled); the run then has the recorded feed only
     */
    public static @Nullable JfrPinningStream start(VirtualThreadPinningDetector target, Set<Long> workers,
                                                   String testName) {
        return start(target, workers, Set.of(), testName);
    }

    /**
     * {@link #start(VirtualThreadPinningDetector, Set, String)} that also attributes a pin on a
     * thread in {@code spawned}, the threads the run's own threads started, which the agent records
     * from a woven {@code Thread.start} (#983). Such a thread takes the round its starter was in,
     * read at the same round boundaries as the workers.
     *
     * @param target   the run's pinning detector
     * @param workers  the run's worker thread ids, filled as workers start
     * @param spawned  the ids of threads the run's threads started, filled as they start
     * @param testName the test method, for log lines
     * @return the open stream, or {@code null} without a usable JFR
     */
    public static @Nullable JfrPinningStream start(VirtualThreadPinningDetector target, Set<Long> workers,
                                                   Set<Long> spawned, String testName) {
        try {
            return new JfrPinningStream(Live.start(target, workers, spawned, testName));
        } catch (LinkageError | RuntimeException e) { // NOPMD - any failure to start leaves the recorded feed, never the run
            if (UNAVAILABLE_LOGGED.compareAndSet(false, true)) {
                log.debug("pinning.jfr.unavailable test={} reason=\"{}\"", testName, e.toString());
            }
            return null;
        }
    }

    /**
     * Marks the start of a round. Call before the round's workers start: every worker already in
     * the set then belongs to an earlier round, because the runner joins a round before it starts
     * the next one.
     */
    public void markRound() {
        live.markRound();
    }

    /** {@return how many pinning events fell on threads that are not this run's workers} */
    public long unattributedEvents() {
        return live.unattributed.get();
    }

    /** {@return how many pinning events on this run's workers had the agent's transformer as their site} */
    public long instrumentationEvents() {
        return live.instrumentation.get();
    }

    /** {@return how many call sites, each with the frame it pinned in, pinned a worker in one round only, and so were not reported} */
    public int singleRoundSites() {
        return live.delivery.singleRoundSites();
    }

    /** {@return how many pins on this run's workers were a JDK lock the JVM contended on inside its own critical section, and so were not reported} */
    public int criticalSectionEvents() {
        return live.delivery.criticalSectionPins();
    }

    /**
     * Stops the stream, receives every event it still holds, and hands the detector the pins of
     * each site seen in two rounds or more. Idempotent, and never throws: a stream that fails to
     * stop costs the run its observed pins, not its result.
     */
    @Override
    public void close() {
        live.close();
    }

    /** Holds every {@code jdk.jfr} reference, so loading the outer class never needs the module. */
    private static final class Live {
        private static final String EVENT = "jdk.VirtualThreadPinned";
        /** Pins held per run; a body that pins on every park would otherwise grow without bound. */
        private static final int MAX_PINS = 10_000;

        private final RecordingStream stream;
        private final VirtualThreadPinningDetector target;
        private final Set<Long> workers;
        /** Threads the run's threads started through a woven {@code Thread.start} (#983). */
        private final Set<Long> spawned;
        private final String testName;
        private final AtomicLong unattributed = new AtomicLong();
        private final AtomicLong instrumentation = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        /** The round each worker ran in, filled in at each {@link #markRound()} and at close. */
        private final Map<Long, Integer> roundOf = new ConcurrentHashMap<>();
        private final AtomicInteger round = new AtomicInteger(-1);
        /** Pins on workers, held until close, when every worker's round is known. */
        private final Queue<Pin> pins = new ConcurrentLinkedQueue<>();
        private final AtomicInteger held = new AtomicInteger();
        /** Written once by close(), on the runner thread, and read after it on the same thread. */
        private Delivery delivery = new Delivery(0, 0);

        private Live(RecordingStream stream, VirtualThreadPinningDetector target, Set<Long> workers,
                     Set<Long> spawned, String testName) {
            this.stream = stream;
            this.target = target;
            this.workers = workers;
            this.spawned = spawned;
            this.testName = testName;
        }

        static Live start(VirtualThreadPinningDetector target, Set<Long> workers, Set<Long> spawned,
                          String testName) {
            RecordingStream stream = new RecordingStream();
            try {
                stream.enable(EVENT).withThreshold(Duration.ZERO).withStackTrace();
                Live live = new Live(stream, target, workers, spawned, testName);
                stream.onEvent(EVENT, live::receive);
                stream.startAsync();
                return live;
            } catch (RuntimeException | Error e) {
                stream.close();
                throw e;
            }
        }

        void markRound() {
            assignRound(round.getAndIncrement());
        }

        /** Gives every worker that has no round yet the round {@code completed}. */
        private void assignRound(int completed) {
            if (completed < 0) {
                return;
            }
            for (Long id : workers) {
                roundOf.putIfAbsent(id, completed);
            }
            for (Long id : spawned) {
                roundOf.putIfAbsent(id, completed);
            }
        }

        private void receive(RecordedEvent event) {
            try {
                RecordedThread thread = event.getThread();
                if (thread == null) {
                    return;
                }
                long threadId = thread.getJavaThreadId();
                if (!workers.contains(threadId) && !spawned.contains(threadId)) {
                    unattributed.incrementAndGet();
                    return;
                }
                if (held.incrementAndGet() > MAX_PINS) {
                    return;
                }
                StackTraceElement[] frames = frames(event.getStackTrace());
                String site = site(frames);
                if (instrumentation(frames)) {
                    instrumentation.incrementAndGet();
                    return;
                }
                String reason = reason(event, frames);
                pins.add(new Pin(threadId, String.valueOf(thread.getJavaName()), site,
                        "JFR jdk.VirtualThreadPinned: " + reason, cause(reason),
                        event.getDuration().toMillis(), frames));
            } catch (RuntimeException e) { // NOPMD - an exception here would end the stream's dispatch
                log.debug("pinning.jfr.event-dropped test={} reason=\"{}\"", testName, e.toString());
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // Called from the runner's finally: anything thrown here would replace the run's own
            // failure, so no step may throw.
            try {
                stream.stop();
            } catch (RuntimeException e) { // NOPMD - same policy as receive: lose the events, not the run
                log.debug("pinning.jfr.stop-failed test={} reason=\"{}\"", testName, e.toString());
            }
            try {
                stream.close();
            } catch (RuntimeException e) { // NOPMD - same policy
                log.debug("pinning.jfr.close-failed test={} reason=\"{}\"", testName, e.toString());
            }
            try {
                assignRound(round.get());
                deliver();
            } catch (RuntimeException e) { // NOPMD - same policy
                log.debug("pinning.jfr.deliver-failed test={} reason=\"{}\"", testName, e.toString());
            }
        }

        private void deliver() {
            delivery = JfrPinningStream.deliver(pins, roundOf, target);
        }

        /** The frames below the JDK's own parking machinery, which every event starts with. */
        private static StackTraceElement[] frames(@Nullable RecordedStackTrace trace) {
            if (trace == null) {
                return new StackTraceElement[0];
            }
            List<StackTraceElement> out = new ArrayList<>();
            for (RecordedFrame frame : trace.getFrames()) {
                String type = frame.getMethod().getType().getName();
                if (out.isEmpty() && ("java.lang.VirtualThread".equals(type) || type.startsWith("jdk.internal."))) {
                    continue;
                }
                out.add(new StackTraceElement(type, frame.getMethod().getName(), null, frame.getLineNumber()));
            }
            return out.toArray(new StackTraceElement[0]);
        }

        /** The agent's package, whose code runs only inside its transformer and install. */
        private static final String AGENT_PACKAGE = "se.deversity.asynctest.agent.";

        /**
         * The prefixes of the library classes a woven call site calls instead of the JDK method:
         * the {@code Agent*Hooks} and the telemetry registry. A blocking call the agent wove passes
         * through one of them, so as a site it would name the hook every such call shares.
         */
        private static final String[] WOVEN_HOOKS = {
            "se.deversity.asynctest.Agent", "se.deversity.asynctest.telemetry.TelemetryRegistry"
        };

        /** Per class name, whether it is this library's own code; see {@link #isLibraryCode}. */
        private static final Map<String, Boolean> LIBRARY_CODE = new ConcurrentHashMap<>();

        /**
         * {@return whether the pin happened in the instrumentation rather than the code under test}:
         * the first frame outside the JDK is the agent's own code, which runs only in its transformer
         * and install, or library code other than a woven hook, such as the lockset bookkeeping a
         * woven monitor enter does. A woven hook only delegates the user's own call (a sleep, a
         * lock), so a pin under one is the user's. The corpus pair met both: the agent's per-loader
         * bookkeeping and {@code LocksetRegistry.register} contended on a monitor while a class was
         * being defined, a native frame below them (#984).
         */
        private static boolean instrumentation(StackTraceElement[] frames) {
            for (StackTraceElement frame : frames) {
                String type = frame.getClassName();
                if (isJdk(type)) {
                    continue;
                }
                return type.startsWith(AGENT_PACKAGE) || !isWovenHook(type) && isLibraryCode(type);
            }
            return false;
        }

        /**
         * {@return whether {@code type} comes from the same jar or class directory as this class},
         * which tells the library's code from the user's even where the user's code shares the
         * library's package names, as this project's own tests do.
         */
        private static boolean isLibraryCode(String type) {
            return type.startsWith("se.deversity.asynctest.")
                    && LIBRARY_CODE.computeIfAbsent(type, Live::sharesThisCodeSource);
        }

        private static boolean sharesThisCodeSource(String type) {
            try {
                Class<?> found = Class.forName(type, false, JfrPinningStream.class.getClassLoader());
                java.security.CodeSource ours = JfrPinningStream.class.getProtectionDomain().getCodeSource();
                java.security.CodeSource theirs = found.getProtectionDomain().getCodeSource();
                return ours != null && theirs != null
                        && java.util.Objects.equals(ours.getLocation(), theirs.getLocation());
            } catch (ClassNotFoundException | LinkageError | RuntimeException e) { // NOPMD - unknown is the user's
                return false;
            }
        }

        /**
         * The first frame outside the JDK and the woven hooks, where the user's code made the
         * blocking call. Without skipping the hooks, every pin under the agent had the sleep hook as
         * its site, so two different call sites pinning in two different rounds read as one site
         * pinning in two (#984).
         */
        private static String site(StackTraceElement[] frames) {
            for (StackTraceElement frame : frames) {
                String type = frame.getClassName();
                if (!isJdk(type) && !isWovenHook(type)) {
                    return frame.toString();
                }
            }
            return frames.length == 0 ? "" : frames[0].toString();
        }

        private static boolean isWovenHook(String type) {
            for (String prefix : WOVEN_HOOKS) {
                if (type.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        /** JDK 24+ records why; JDK 21-23 record no reason, so the stack is all there is. */
        private static String reason(RecordedEvent event, StackTraceElement[] frames) {
            if (event.hasField("pinnedReason")) {
                String why = event.getString("pinnedReason");
                String op = event.hasField("blockingOperation") ? event.getString("blockingOperation") : null;
                return why + (op == null ? "" : " (" + op + ")");
            }
            for (StackTraceElement frame : frames) {
                if ("<clinit>".equals(frame.getMethodName())) {
                    return "class initializer " + frame.getClassName() + ".<clinit> on stack";
                }
            }
            return "a held monitor or a native frame on stack (this JDK does not record which)";
        }

        private static VirtualThreadPinningDetector.PinningCause cause(String reason) {
            String r = reason.toLowerCase(Locale.ROOT);
            if (r.contains("native")) {
                return VirtualThreadPinningDetector.PinningCause.NATIVE;
            }
            if (r.contains("<clinit>") || r.contains("initialization")) {
                return VirtualThreadPinningDetector.classInitCause(r);
            }
            return VirtualThreadPinningDetector.PinningCause.OTHER;
        }
    }

    private static boolean isJdk(String type) {
        return type.startsWith("java.") || type.startsWith("jdk.") || type.startsWith("sun.");
    }

    /** What {@link #deliver} withheld from the detector, for the run's DEBUG lines. */
    record Delivery(int singleRoundSites, int criticalSectionPins) {
    }

    /**
     * Hands {@code target} one pin per thread and site, for each site seen pinning at the same
     * frame in two rounds, where {@code roundOf} gives each worker's round, leaving out every pin
     * on a JDK lock the JVM contended on inside its own critical section, and {@return how many
     * site and frame pairs pinned in one round only, and how many pins were left out}.
     */
    static Delivery deliver(Iterable<Pin> pins, Map<Long, Integer> roundOf, VirtualThreadPinningDetector target) {
        Map<String, Set<Integer>> roundsByPin = new HashMap<>();
        int criticalSection = 0;
        for (Pin pin : pins) {
            if (jdkCriticalSectionEnter(pin)) {
                criticalSection++;
                continue;
            }
            roundsByPin.computeIfAbsent(recurrenceKey(pin), s -> new HashSet<>())
                    .add(roundOf.getOrDefault(pin.threadId, -1));
        }
        Set<String> delivered = new HashSet<>();
        for (Pin pin : pins) {
            // A timed park re-parks for its remainder, one event each: keep the first.
            if (roundsByPin.getOrDefault(recurrenceKey(pin), Set.of()).size() >= 2 && !jdkCriticalSectionEnter(pin)
                    && delivered.add(pin.threadId + "@" + pin.site)) {
                target.recordObservedPinning(pin.threadId, pin.threadName, pin.reason, pin.cause,
                        pin.durationMillis, pin.frames);
            }
        }
        int single = 0;
        for (Set<Integer> rounds : roundsByPin.values()) {
            if (rounds.size() < 2) {
                single++;
            }
        }
        return new Delivery(single, criticalSection);
    }

    /**
     * Whether the JVM pinned for itself: a monitor enter it could not preempt inside a
     * continuation critical section, {@code Continuation.Pinned} code 2, which JDK 24-26 report
     * as {@code Freeze or preempt failed (2)}, with a JDK frame on top, so the lock is the JDK's.
     * JDK 21-23 record no reason and so are never matched.
     */
    private static boolean jdkCriticalSectionEnter(Pin pin) {
        String reason = pin.reason.toLowerCase(Locale.ROOT);
        return (reason.contains("freeze or preempt failed (2)") || reason.contains("in critical section"))
                && reason.contains("contended monitor enter")
                && pin.frames.length > 0 && isJdk(pin.frames[0].getClassName());
    }

    /**
     * The site plus the frame the pin happened in, line included. A JDK class initializes once per
     * JVM, and waiting for it pins at the instruction that first used it, a JDK frame the site
     * skips: keyed by the site alone, two such classes first used under one call site in two
     * rounds read as one pin recurring. Correct StampedLock code did that on JDK 25, waiting for
     * WriterNode's initializer in one round and ReaderNode's in another, both under one readLock.
     */
    private static String recurrenceKey(Pin pin) {
        if (pin.frames.length == 0) {
            return pin.site;
        }
        StackTraceElement top = pin.frames[0];
        return pin.site + " at " + top.getClassName() + "." + top.getMethodName() + ":" + top.getLineNumber();
    }

    /** One observed pin on a worker, before its site's rounds are known. */
    record Pin(long threadId, String threadName, String site, String reason,
               VirtualThreadPinningDetector.PinningCause cause, long durationMillis,
               StackTraceElement[] frames) {
    }
}
