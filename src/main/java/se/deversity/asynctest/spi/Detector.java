package se.deversity.asynctest.spi;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AIPublicAPI;

import java.util.List;

/**
 * Service Provider Interface for {@code @AsyncTest} detectors.
 *
 * <p>The original detector architecture required each new detector to add
 * synchronized changes across five files: the {@link DetectorType} enum, a
 * field on {@code AsyncTest}, a builder field and default on
 * {@code AsyncTestConfig}, both branches of {@code AsyncTestConfig.build()},
 * and a registration arm in {@code DetectorRegistry}. The Detector SPI
 * collapses that into a single class plus a {@code META-INF/services} entry
 * (or {@code @AutoService} for build-time wiring).
 *
 * <p>A Detector's responsibilities:
 *
 * <ol>
 *   <li>Declare its identity via {@link #type()} — must return a value from
 *       the {@link DetectorType} enum so it remains addressable from the
 *       existing {@code excludes} / {@code preset.enabled()} surface.</li>
 *   <li>Record runtime events through whatever API the detector exposes to
 *       user test bodies (e.g. {@code recordAccess(...)}).</li>
 *   <li>Produce {@link Violation}s on {@link #analyze()}, called by the
 *       runner at the end of each test invocation.</li>
 * </ol>
 *
 * <p>Detectors discovered via {@link java.util.ServiceLoader} are instantiated
 * once per {@code AsyncTestContext} and live for the duration of one
 * {@code @AsyncTest} method's invocation rounds.
 *
 * <p>This SPI ships alongside the legacy {@code DetectorRegistry} for the
 * 1.0.0 cutover; existing detectors continue to work unchanged. New detectors
 * can be implemented either way.
 *
 * @since 1.0.0
 */
@AIPublicAPI
public interface Detector {

    /**
     * Identity of this detector. Must be a value from the {@link DetectorType}
     * enum so that {@code @AsyncTest(excludes = {...})} and
     * {@code Preset.enabled()} can address it.
     */
    DetectorType type();

    /**
     * Produces the violations found during the just-finished invocation round.
     * Called by the runner before reports are flushed to listeners; must be
     * idempotent across multiple calls within the same context lifetime (the
     * runner may invoke it once per round and once at end-of-test).
     *
     * @return the violations produced; never {@code null} (use {@link List#of()}
     *         for "no findings").
     */
    List<Violation> analyze();

    /**
     * Lifecycle hook called by the runner before the first invocation round.
     * Default no-op; override to capture initial state (e.g. baseline thread
     * counts).
     */
    default void onTestStart() {}

    /**
     * Lifecycle hook called by the runner after the last invocation round.
     * Default no-op; override to release per-test resources.
     */
    default void onTestEnd() {}
}
