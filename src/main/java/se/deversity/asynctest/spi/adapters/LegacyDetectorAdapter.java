package se.deversity.asynctest.spi.adapters;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.IssueSeverity;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.spi.Detector;
import se.deversity.vibetags.annotations.AIPerformance;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Generic SPI {@link Detector} that wraps a legacy detector instance and projects
 * its {@code analyze()} output into a structured {@link Violation}.
 *
 * <p>Legacy detectors do not share a common base interface — each one has its own
 * {@code analyze()} returning a bespoke {@code XxxReport} inner class. To avoid
 * writing 95 hand-tailored adapters, this class uses reflection to invoke
 * {@code delegate.analyze()} and the resulting report's {@code hasIssues()} /
 * {@code toString()}.
 *
 * <p>Detectors whose report does not follow the canonical shape
 * ({@code analyze() → Report{hasIssues(), toString()}}) silently return an empty
 * list — they continue to work via the legacy {@code DetectorRegistry} path; the
 * SPI registry simply has no structured view of them.
 *
 * <p>When a detector class is later migrated to expose a {@code structuredViolations}
 * field (the {@link se.deversity.asynctest.diagnostics.SharedMessageDigestDetector}
 * pattern), it gets its own dedicated factory with a typed adapter; this generic
 * fallback is reserved for the long tail.
 *
 * @param <D> legacy detector type
 *
 * @since 1.5.0
 */
@AIPerformance(constraint = "analyze() does Method.getMethod + invoke each call; only invoked once per round per detector, not on the hot recordAccess path. If profiling shows reflection overhead, cache the Method handles in the constructor.")
public final class LegacyDetectorAdapter<D> implements Detector {

    private final D delegate;
    private final DetectorType type;
    private final String detectorName;

    public LegacyDetectorAdapter(D delegate, DetectorType type, String detectorName) {
        this.delegate = delegate;
        this.type = type;
        this.detectorName = detectorName;
    }

    @Override
    public DetectorType type() {
        return type;
    }

    @Override
    public List<Violation> analyze() {
        try {
            Method analyze = delegate.getClass().getMethod("analyze");
            Object report = analyze.invoke(delegate);
            if (report == null) return List.of();

            Method hasIssues = findHasIssues(report.getClass());
            if (hasIssues == null) return List.of();
            boolean has = (boolean) hasIssues.invoke(report);
            if (!has) return List.of();

            return List.of(new Violation(
                    detectorName,
                    IssueSeverity.HIGH,
                    String.valueOf(report),
                    List.of(),
                    Map.of(),
                    Instant.now()));
        } catch (NoSuchMethodException e) {
            // Detector doesn't follow the canonical shape; legacy path still works.
            return List.of();
        } catch (ReflectiveOperationException e) {
            // analyze() / hasIssues() threw — don't poison the rest of the SPI sweep.
            return List.of();
        }
    }

    /** Exposed for callers that need direct access to the wrapped legacy detector. */
    public D delegate() {
        return delegate;
    }

    /**
     * Walks the report class hierarchy looking for a {@code boolean hasIssues()}
     * method. Some reports declare it on a base type / interface.
     */
    private static Method findHasIssues(Class<?> reportClass) {
        for (Class<?> c = reportClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getMethod("hasIssues");
                if (m.getReturnType() == boolean.class) return m;
            } catch (NoSuchMethodException ignored) {
                // try parent
            }
        }
        return null;
    }
}
