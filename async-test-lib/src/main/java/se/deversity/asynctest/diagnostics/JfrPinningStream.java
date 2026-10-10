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
 * The site is the first frame outside the JDK. A run of one round therefore gets no observed
 * finding, only {@link #singleRoundSites()}.
 *
 * <p>JFR hands every event to every open stream, so two runs in parallel each see the other's
 * pins. Only events on a thread in the run's worker set are kept; the rest are counted in
 * {@link #unattributedEvents()}. The events arrive in batches, and none had arrived before
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
        try {
            return new JfrPinningStream(Live.start(target, workers, testName));
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

    /** {@return how many call sites pinned a worker in one round only, and so were not reported} */
    public int singleRoundSites() {
        return live.singleRoundSites;
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
        private final String testName;
        private final AtomicLong unattributed = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        /** The round each worker ran in, filled in at each {@link #markRound()} and at close. */
        private final Map<Long, Integer> roundOf = new ConcurrentHashMap<>();
        private final AtomicInteger round = new AtomicInteger(-1);
        /** Pins on workers, held until close, when every worker's round is known. */
        private final Queue<Pin> pins = new ConcurrentLinkedQueue<>();
        private final AtomicInteger held = new AtomicInteger();
        /** Written once by close(), on the runner thread, and read after it on the same thread. */
        private int singleRoundSites;

        private Live(RecordingStream stream, VirtualThreadPinningDetector target, Set<Long> workers,
                     String testName) {
            this.stream = stream;
            this.target = target;
            this.workers = workers;
            this.testName = testName;
        }

        static Live start(VirtualThreadPinningDetector target, Set<Long> workers, String testName) {
            RecordingStream stream = new RecordingStream();
            try {
                stream.enable(EVENT).withThreshold(Duration.ZERO).withStackTrace();
                Live live = new Live(stream, target, workers, testName);
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
        }

        private void receive(RecordedEvent event) {
            try {
                RecordedThread thread = event.getThread();
                if (thread == null) {
                    return;
                }
                long threadId = thread.getJavaThreadId();
                if (!workers.contains(threadId)) {
                    unattributed.incrementAndGet();
                    return;
                }
                if (held.incrementAndGet() > MAX_PINS) {
                    return;
                }
                StackTraceElement[] frames = frames(event.getStackTrace());
                String reason = reason(event, frames);
                pins.add(new Pin(threadId, String.valueOf(thread.getJavaName()), site(frames),
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

        /** Hands the detector one pin per thread and site, for each site seen in two rounds. */
        private void deliver() {
            Map<String, Set<Integer>> roundsBySite = new HashMap<>();
            for (Pin pin : pins) {
                roundsBySite.computeIfAbsent(pin.site, s -> new HashSet<>())
                        .add(roundOf.getOrDefault(pin.threadId, -1));
            }
            Set<String> delivered = new HashSet<>();
            for (Pin pin : pins) {
                // A timed park re-parks for its remainder, one event each: keep the first.
                if (roundsBySite.getOrDefault(pin.site, Set.of()).size() >= 2 && delivered.add(pin.threadId + "@" + pin.site)) {
                    target.recordObservedPinning(pin.threadId, pin.threadName, pin.reason, pin.cause,
                            pin.durationMillis, pin.frames);
                }
            }
            int single = 0;
            for (Set<Integer> rounds : roundsBySite.values()) {
                if (rounds.size() < 2) {
                    single++;
                }
            }
            singleRoundSites = single;
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

        /** The first frame outside the JDK, where the user's code made the blocking call. */
        private static String site(StackTraceElement[] frames) {
            for (StackTraceElement frame : frames) {
                String type = frame.getClassName();
                if (!type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("sun.")) {
                    return frame.toString();
                }
            }
            return frames.length == 0 ? "" : frames[0].toString();
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
                return VirtualThreadPinningDetector.PinningCause.CLASS_INIT;
            }
            return VirtualThreadPinningDetector.PinningCause.OTHER;
        }
    }

    /** One observed pin on a worker, before its site's rounds are known. */
    private record Pin(long threadId, String threadName, String site, String reason,
                       VirtualThreadPinningDetector.PinningCause cause, long durationMillis,
                       StackTraceElement[] frames) {
    }
}
