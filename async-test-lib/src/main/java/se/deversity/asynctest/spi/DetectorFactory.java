package se.deversity.asynctest.spi;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

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
@AIContract(reason = "Public SPI interface for ServiceLoader-based detector discovery. type(), isEnabledFor(), and create() signatures are part of the stable factory contract — implementors bind to these exact names and parameter types.")
@API(status = Status.STABLE)
public interface DetectorFactory {

    /**
     * Identity of the detector this factory produces. Must match
     * {@link Detector#type()} of the instances returned by {@link #create(AsyncTestConfig)}.
     *
     * @return the constant identifying the detector this factory produces
     */
    DetectorType type();

    /**
     * Whether this detector is active for the given test configuration.
     *
     * <p>{@code AsyncTestConfig} carries one boolean field per legacy detector
     * (135 fields) — each factory's adapter consults its own flag here. There is
     * no automatic mapping from {@link DetectorType} to a boolean field, so this
     * method must be implemented by every concrete factory. Returning {@code true}
     * unconditionally is acceptable for detectors that should always run.
     *
     * @param config the resolved configuration for the test about to run
     * @return {@code true} to build and install this detector for that test
     */
    boolean isEnabledFor(AsyncTestConfig config);

    /**
     * Construct a fresh detector instance for one {@code @AsyncTest} method's
     * invocation rounds.
     *
     * @param config the resolved configuration for the test about to run
     * @return a new detector, not shared with any other test
     */
    Detector create(AsyncTestConfig config);
}
