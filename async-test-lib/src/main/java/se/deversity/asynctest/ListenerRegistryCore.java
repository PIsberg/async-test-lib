package se.deversity.asynctest;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.diagnostics.DetectorDefaultSeverity;
import se.deversity.asynctest.diagnostics.DetectorTrust;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One listener set and everything done to it. {@link AsyncTestListenerRegistry} is the JVM-wide
 * static face of exactly one of these.
 *
 * <p>The split exists so this behaviour can be exercised at all. The registry is process-wide, so
 * a test putting registration, firing and snapshot restore under contention on it would be
 * delivering its own synthetic events to the listeners of the very run driving the test. An
 * instance nothing else can see has no such reach.
 *
 * <p>The listener set is one immutable list in a {@code volatile} field rather than a
 * {@code CopyOnWriteArrayList}. That is not a style preference. Restoring a snapshot used to be
 * {@code clear()} followed by {@code addAll()}, which is two mutations, and a fire landing between
 * them iterated an empty registry and dropped the finding without a word. Here every mutation
 * publishes a whole list in one write, so a reader sees the set before or the set after and never
 * a gap, and iteration walks a list that cannot change underneath it.
 *
 * <p>Writers take {@link #writeLock} so two of them cannot read the same set and each publish a
 * copy missing the other's change. Readers never take it, which keeps firing exactly as lock-free
 * as it was. Registration happens once per listener, so ordering writers costs nothing that
 * matters.
 *
 * <p>The logger is deliberately named for {@link AsyncTestListenerRegistry}: these warnings were
 * always attributed to the registry, and a listener that throws should not start appearing under a
 * class name no caller has heard of.
 */
final class ListenerRegistryCore {

    private static final Logger log = LoggerFactory.getLogger(AsyncTestListenerRegistry.class);

    /** ANSI SGR sequences, as {@link IssueSeverity#format()} renders severity markers. */
    private static final Pattern ANSI = Pattern.compile("\\e\\[[;\\d]*m");

    /** Guards the writes below. Readers never take it. */
    private final Object writeLock = new Object();

    /** The listener set: immutable, replaced wholesale, read without locking. */
    private volatile List<AsyncTestListener> listeners = List.of();

    void register(AsyncTestListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        synchronized (writeLock) {
            List<AsyncTestListener> next = new ArrayList<>(listeners);
            next.add(listener);
            listeners = List.copyOf(next);
        }
    }

    /**
     * {@return whether a listener was removed}
     *
     * <p>Removes one occurrence, which is what the {@code CopyOnWriteArrayList.remove} this
     * replaced did, and registration has never rejected duplicates.
     */
    boolean unregister(AsyncTestListener listener) {
        synchronized (writeLock) {
            List<AsyncTestListener> current = listeners;
            int index = current.indexOf(listener);
            if (index < 0) {
                return false;
            }
            List<AsyncTestListener> next = new ArrayList<>(current);
            next.remove(index);
            listeners = List.copyOf(next);
            return true;
        }
    }

    void clearAll() {
        synchronized (writeLock) {
            listeners = List.of();
        }
    }

    /** {@return the current set, already immutable and safe to hold} */
    List<AsyncTestListener> snapshot() {
        return listeners;
    }

    /** Puts {@code snapshot} back in one write, so no reader can observe a half-restored set. */
    void restore(List<AsyncTestListener> snapshot) {
        synchronized (writeLock) {
            listeners = snapshot;
        }
    }

    int listenerCount() {
        return listeners.size();
    }

    void fireInvocationStarted(int round, int threads) {
        for (AsyncTestListener listener : listeners) {
            try {
                listener.onInvocationStarted(round, threads);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onInvocationStarted threw: {}", e.toString(), e);
            }
        }
    }

    void fireInvocationCompleted(int round, long durationMs) {
        for (AsyncTestListener listener : listeners) {
            try {
                listener.onInvocationCompleted(round, durationMs);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onInvocationCompleted threw: {}", e.toString(), e);
            }
        }
    }

    void fireTestFailed(Throwable cause) {
        for (AsyncTestListener listener : listeners) {
            try {
                listener.onTestFailed(cause);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onTestFailed threw: {}", e.toString(), e);
            }
        }
    }

    void fireDetectorReport(String detectorName, String report) {
        // One read of the field, used for the emptiness check and the walk both: reading it twice
        // could skip the work for an empty set and then iterate a non-empty one, or the reverse.
        List<AsyncTestListener> current = listeners;
        // No Violation built when nobody is listening: with no listeners this has no observable
        // effect anyway.
        if (current.isEmpty()) {
            return;
        }
        IssueSeverity severity = DetectorDefaultSeverity.of(detectorName, report);
        Violation violation = toViolation(detectorName, severity, report);
        for (AsyncTestListener listener : current) {
            try {
                listener.onDetectorReport(detectorName, report);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onDetectorReport threw: {}", e.toString(), e);
            }
            try {
                listener.onStructuredReport(detectorName, severity, report);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onStructuredReport threw: {}", e.toString(), e);
            }
            if (violation != null) {
                notifyViolation(listener, violation);
            }
        }
    }

    void fireViolation(@Nullable Violation violation) {
        if (violation == null) {
            return;
        }
        for (AsyncTestListener listener : listeners) {
            notifyViolation(listener, violation);
        }
    }

    void fireTimeout(long timeoutMs) {
        for (AsyncTestListener listener : listeners) {
            try {
                listener.onTimeout(timeoutMs);
            } catch (RuntimeException e) {
                log.warn("AsyncTestListener.onTimeout threw: {}", e.toString(), e);
            }
        }
    }

    /** One listener's {@code onViolation}, contained: a thrower must not silence its peers. */
    private static void notifyViolation(AsyncTestListener listener, Violation violation) {
        try {
            listener.onViolation(violation);
        } catch (RuntimeException e) {
            log.warn("AsyncTestListener.onViolation threw: {}", e.toString(), e);
        }
    }

    /**
     * Builds the structured form of a text report: the detector as reported, the parsed
     * severity, the report's first non-blank line as the message (severity markers are
     * rendered with ANSI colour, which is stripped), and the whole report kept under the
     * {@code "report"} attribute so nothing is lost in the conversion.
     *
     * @return the violation, or {@code null} if one could not be built — a listener callback
     *         is not worth failing a test run over
     */
    private static @Nullable Violation toViolation(String detectorName, IssueSeverity severity,
                                                   String report) {
        try {
            String detector = (detectorName == null || detectorName.isBlank())
                    ? "UnknownDetector" : detectorName;
            String text = (report == null) ? "" : report;
            String message = firstMeaningfulLine(text);
            return new Violation(
                    detector,
                    severity,
                    message.isEmpty() ? detector + " reported a finding" : message,
                    List.of(),
                    Map.of("report", text,
                           "trustTier", DetectorTrust.tierOfDetector(detector).name()),
                    Instant.now());
        } catch (RuntimeException e) {
            log.warn("Could not build a Violation for detector {}: {}", detectorName, e.toString(), e);
            return null;
        }
    }

    private static String firstMeaningfulLine(String report) {
        for (String line : report.split("\n", -1)) {
            String stripped = ANSI.matcher(line).replaceAll("").trim();
            if (!stripped.isEmpty()) {
                return stripped;
            }
        }
        return "";
    }
}
