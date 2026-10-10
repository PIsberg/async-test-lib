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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

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
 * <p>JFR hands every event to every open stream, so two runs in parallel each see the other's
 * pins. Only events on a thread {@code isWorker} accepts reach the detector; the rest are counted
 * in {@link #unattributedEvents()}. The events arrive in batches, and none had arrived before
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
     * Starts streaming pinning events into {@code target}.
     *
     * @param target   the detector the attributed events go to
     * @param isWorker accepts the {@link Thread#threadId()} of a thread whose pins belong to this run
     * @param testName the test the stream serves, for the log
     * @return the open stream, or {@code null} when this JVM has no usable JFR (a runtime image
     *         without {@code jdk.jfr}, or JFR disabled); the run then has the recorded feed only
     */
    public static @Nullable JfrPinningStream start(VirtualThreadPinningDetector target, LongPredicate isWorker,
                                                   String testName) {
        try {
            return new JfrPinningStream(Live.start(target, isWorker, testName));
        } catch (LinkageError | RuntimeException e) { // NOPMD - any failure to start leaves the recorded feed, never the run
            if (UNAVAILABLE_LOGGED.compareAndSet(false, true)) {
                log.debug("pinning.jfr.unavailable test={} reason=\"{}\"", testName, e.toString());
            }
            return null;
        }
    }

    /** {@return how many pinning events fell on threads that are not this run's workers} */
    public long unattributedEvents() {
        return live.unattributed.get();
    }

    /**
     * Stops the stream, delivering every event it still holds, and releases it. Idempotent, and
     * never throws: a stream that fails to stop costs the run its observed pins, not its result.
     */
    @Override
    public void close() {
        live.close();
    }

    /** Holds every {@code jdk.jfr} reference, so loading the outer class never needs the module. */
    private static final class Live {
        private static final String EVENT = "jdk.VirtualThreadPinned";

        private final RecordingStream stream;
        private final String testName;
        private final AtomicLong unattributed = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        /** Thread id and site of each pin delivered: a timed park re-parks for its remainder, one event each. */
        private final Set<String> delivered = ConcurrentHashMap.newKeySet();

        private Live(RecordingStream stream, String testName) {
            this.stream = stream;
            this.testName = testName;
        }

        static Live start(VirtualThreadPinningDetector target, LongPredicate isWorker, String testName) {
            RecordingStream stream = new RecordingStream();
            try {
                stream.enable(EVENT).withThreshold(Duration.ZERO).withStackTrace();
                Live live = new Live(stream, testName);
                stream.onEvent(EVENT, event -> live.deliver(event, target, isWorker));
                stream.startAsync();
                return live;
            } catch (RuntimeException | Error e) {
                stream.close();
                throw e;
            }
        }

        private void deliver(RecordedEvent event, VirtualThreadPinningDetector target, LongPredicate isWorker) {
            try {
                RecordedThread thread = event.getThread();
                if (thread == null) {
                    return;
                }
                long threadId = thread.getJavaThreadId();
                if (!isWorker.test(threadId)) {
                    unattributed.incrementAndGet();
                    return;
                }
                StackTraceElement[] frames = frames(event.getStackTrace());
                if (!delivered.add(threadId + "@" + site(frames))) {
                    return;
                }
                String reason = reason(event, frames);
                target.recordObservedPinning(threadId, String.valueOf(thread.getJavaName()),
                        "JFR jdk.VirtualThreadPinned: " + reason, cause(reason),
                        event.getDuration().toMillis(), frames);
            } catch (RuntimeException e) { // NOPMD - an exception here would end the stream's dispatch
                log.debug("pinning.jfr.event-dropped test={} reason=\"{}\"", testName, e.toString());
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // Called from the runner's finally: anything thrown here would replace the run's own
            // failure, so neither step may throw.
            try {
                stream.stop();
            } catch (RuntimeException e) { // NOPMD - same policy as deliver: lose the events, not the run
                log.debug("pinning.jfr.stop-failed test={} reason=\"{}\"", testName, e.toString());
            }
            try {
                stream.close();
            } catch (RuntimeException e) { // NOPMD - same policy
                log.debug("pinning.jfr.close-failed test={} reason=\"{}\"", testName, e.toString());
            }
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
}
