package se.deversity.asynctest.spi;

import se.deversity.asynctest.AsyncTestConfig;

/**
 * {@link DetectorFactory} for {@link NovelIdTestDetector}: declares an id and no type, and keeps the
 * default id-keyed enablement, gated on the detector being armed.
 */
public final class NovelIdTestDetectorFactory implements DetectorFactory {

    @Override
    public String id() {
        return NovelIdTestDetector.ID;
    }

    @Override
    public boolean isEnabledFor(AsyncTestConfig config) {
        return NovelIdTestDetector.armed() && DetectorFactory.super.isEnabledFor(config);
    }

    @Override
    public Detector create(AsyncTestConfig config) {
        return new NovelIdTestDetector();
    }
}
