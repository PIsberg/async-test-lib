package se.deversity.asynctest.spi;

import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;

/**
 * {@link DetectorFactory} for {@link ExternalTestDetector}, registered in the test-scoped
 * {@code META-INF/services/se.deversity.asynctest.spi.DetectorFactory}.
 *
 * <p>Deliberately lives outside {@code se.deversity.asynctest.spi.adapters} so that
 * {@link DetectorRegistry#buildExternal(AsyncTestConfig)} treats it as third-party, exactly
 * as a user-supplied factory in another jar would be treated.
 */
public final class ExternalTestDetectorFactory implements DetectorFactory {

    @Override
    public DetectorType type() {
        return DetectorType.EXPLICIT_GC;
    }

    @Override
    public boolean isEnabledFor(AsyncTestConfig config) {
        return ExternalTestDetector.armed();
    }

    @Override
    public Detector create(AsyncTestConfig config) {
        return new ExternalTestDetector();
    }
}
