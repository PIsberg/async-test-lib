package se.deversity.asynctest.spi;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIExtensible;
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
 *   <li>Declare its identity: {@link #id()} for a detector of its own, which the
 *       {@code excludeIds} surface addresses, or {@link #type()} for one that stands
 *       for a built-in {@link DetectorType}, whose name is then its id.</li>
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
 * @since 1.6.0
 */
@AIPublicAPI
@AIContract(reason = "Public SPI interface. id(), type(), analyze(), onTestStart(), and onTestEnd() signatures are part of the stable extension contract — implementors bind to these exact names and parameter types. id() and type() are defaults so a detector can declare an identity of its own (#919); making either abstract again breaks every implementor that overrides only the other.")
@AIExtensible(AIExtensible.Strategy.STRATEGY_PATTERN)
@API(status = Status.STABLE)
public interface Detector {

    /**
     * The built-in detector this one stands for, so that {@code @AsyncTest(excludes = {...})}
     * and {@code Preset.enabled()} can address it, or {@code null} for a detector with an
     * identity of its own, which then overrides {@link #id()}.
     *
     * @return the constant identifying this detector, or {@code null}
     */
    default @Nullable DetectorType type() {
        return null;
    }

    /**
     * This detector's identity: the key the registry holds it under and the name
     * {@code @AsyncTest(excludeIds = {...})} switches it off by.
     *
     * <p>Defaults to {@code type().name()}. A genuinely new detector overrides it with an id of its
     * own, which should not collide with a {@link DetectorType} name; a reverse-DNS prefix such as
     * {@code "com.acme.pool-misuse"} keeps it apart from the built-ins and from other vendors.
     *
     * @return the id; never {@code null}
     * @throws IllegalStateException when the detector overrides neither this nor {@link #type()}
     * @since 2.0.0
     */
    default String id() {
        DetectorType type = type();
        if (type == null) {
            throw new IllegalStateException(getClass().getName()
                    + " declares no identity: override id() with an id of its own, or type()");
        }
        return type.name();
    }

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
    default void onTestStart() { /* no-op default */ }

    /**
     * Lifecycle hook called by the runner after the last invocation round.
     * Default no-op; override to release per-test resources.
     */
    default void onTestEnd() { /* no-op default */ }
}
