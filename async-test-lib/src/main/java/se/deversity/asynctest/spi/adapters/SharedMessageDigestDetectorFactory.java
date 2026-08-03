package se.deversity.asynctest.spi.adapters;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.SharedMessageDigestDetector;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.spi.Detector;
import se.deversity.asynctest.spi.DetectorFactory;

import java.util.List;

/**
 * SPI factory for {@link SharedMessageDigestDetector}. Demonstrates the migration
 * pattern: existing detector classes are unchanged, an adapter exposes them to
 * the SPI's {@link Detector} contract by mapping {@code analyze()} →
 * structured {@link Violation}s.
 *
 * <p>Registered via {@code META-INF/services/se.deversity.asynctest.spi.DetectorFactory}.
 *
 * @since 1.6.0
 */
public final class SharedMessageDigestDetectorFactory implements DetectorFactory {

    @Override
    public DetectorType type() {
        return DetectorType.SHARED_MESSAGE_DIGEST;
    }

    @Override
    public boolean isEnabledFor(AsyncTestConfig config) {
        return config.detectSharedMessageDigest;
    }

    @Override
    public Detector create(AsyncTestConfig config) {
        return new Adapter(new SharedMessageDigestDetector());
    }

    /**
     * Thin adapter that exposes the legacy detector through the SPI Detector
     * interface. The underlying detector continues to own its own state and
     * accessor APIs; users that need to record events still call
     * {@code AsyncTestContext.sharedMessageDigestDetector().recordAccess(...)}.
     */
    public static final class Adapter implements Detector {
        private final SharedMessageDigestDetector delegate;

        public Adapter(SharedMessageDigestDetector delegate) {
            this.delegate = delegate;
        }

        @Override
        public DetectorType type() {
            return DetectorType.SHARED_MESSAGE_DIGEST;
        }

        @Override
        public List<Violation> analyze() {
            return List.copyOf(delegate.analyze().structuredViolations);
        }

        /**
         * Exposed for legacy users that need direct access to the wrapped detector.
         *
         * @return the delegate
         */
        public SharedMessageDigestDetector delegate() {
            return delegate;
        }
    }
}
