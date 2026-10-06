package se.deversity.asynctest.spi;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;
import org.jspecify.annotations.Nullable;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.vibetags.annotations.AIContract;
import se.deversity.vibetags.annotations.AIPublicAPI;

/**
 * Factory that produces a fresh {@link Detector} instance per {@code AsyncTestContext}.
 *
 * <p>Detectors are stateful (per-test access maps, threadId sets, etc.) so they
 * cannot be shared across tests. The factory layer lets {@link java.util.ServiceLoader}
 * yield a single discovery-time object that can mint per-test instances on demand.
 *
 * <p>Register via {@code META-INF/services/se.deversity.asynctest.spi.DetectorFactory}
 * or {@code @AutoService(DetectorFactory.class)} at build time.
 *
 * @since 1.6.0
 */
@AIPublicAPI
@AIContract(reason = "Public SPI interface for ServiceLoader-based detector discovery. id(), type(), isEnabledFor(), and create() signatures are part of the stable factory contract — implementors bind to these exact names and parameter types. id(), type() and isEnabledFor() are defaults (#919); making one abstract again breaks every factory that relies on it.")
@API(status = Status.STABLE)
public interface DetectorFactory {

    /**
     * The built-in detector this factory's detectors stand for, or {@code null} for a detector
     * with an identity of its own, whose factory then overrides {@link #id()}. Must match
     * {@link Detector#type()} of the instances returned by {@link #create(AsyncTestConfig)}.
     *
     * @return the constant identifying the detector this factory produces, or {@code null}
     */
    default @Nullable DetectorType type() {
        return null;
    }

    /**
     * The identity of the detector this factory produces: the key the registry holds it under,
     * and the name {@code @AsyncTest(excludeIds = {...})} switches it off by. Must match
     * {@link Detector#id()} of the instances returned by {@link #create(AsyncTestConfig)}.
     * Defaults to {@code type().name()}.
     *
     * @return the id; never {@code null}
     * @throws IllegalStateException when the factory overrides neither this nor {@link #type()}
     * @since 1.13.0
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
     * Whether this detector is active for the given test configuration.
     *
     * <p>Defaults to {@link AsyncTestConfig#isEnabled(String) config.isEnabled(id())}: a built-in
     * id follows its {@link DetectorType}'s selection, and an id of the factory's own is enabled
     * unless the test excludes it by id. The registry never builds a detector whose id the test
     * lists in {@code excludeIds}, whatever this returns.
     *
     * @param config the resolved configuration for the test about to run
     * @return {@code true} to build and install this detector for that test
     */
    default boolean isEnabledFor(AsyncTestConfig config) {
        return config.isEnabled(id());
    }

    /**
     * Construct a fresh detector instance for one {@code @AsyncTest} method's
     * invocation rounds.
     *
     * @param config the resolved configuration for the test about to run
     * @return a new detector, not shared with any other test
     */
    Detector create(AsyncTestConfig config);
}
