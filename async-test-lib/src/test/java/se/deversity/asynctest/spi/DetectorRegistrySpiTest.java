package se.deversity.asynctest.spi;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.Preset;
import se.deversity.asynctest.diagnostics.SharedMessageDigestDetector;
import se.deversity.asynctest.spi.adapters.SharedMessageDigestDetectorFactory;

import java.security.MessageDigest;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Detector SPI rails:
 *
 * <ul>
 *   <li>{@link ServiceLoader} discovers built-in factories from META-INF/services.</li>
 *   <li>{@link DetectorRegistry#build(AsyncTestConfig)} instantiates the enabled ones.</li>
 *   <li>The canary {@link SharedMessageDigestDetectorFactory} adapter forwards
 *       to the legacy detector and surfaces structured violations.</li>
 *   <li>Disabled factories (e.g. via {@code @AsyncTest(detectSharedMessageDigest=false)})
 *       are not instantiated.</li>
 * </ul>
 */
class DetectorRegistrySpiTest {

    @Test
    void builtInFactoriesAreListedOutsideServiceLoader() {
        // Built-ins are registered in META-INF/async-test/builtin-detector-factories, not in a
        // services file, so that ServiceLoader discovery at runtime does not have to load 127
        // classes it will then discard. Both halves of that are asserted here.
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        var viaRegistry = DetectorRegistry.build(cfg).all().stream()
                .map(d -> d.getClass().getName())
                .collect(Collectors.toSet());
        assertTrue(viaRegistry.stream().anyMatch(n -> n.contains("SharedMessageDigest")),
                "The built-in list must still reach DetectorRegistry.build; found: " + viaRegistry);

        var viaServiceLoader = StreamSupport
                .stream(ServiceLoader.load(DetectorFactory.class).spliterator(), false)
                .map(f -> f.getClass().getName())
                .filter(n -> n.startsWith("se.deversity.asynctest.spi.adapters."))
                .collect(Collectors.toSet());
        assertTrue(viaServiceLoader.isEmpty(),
                "No built-in factory may be registered for ServiceLoader discovery: loading them "
                        + "is the ~340 ms per forked JVM this arrangement exists to avoid. Found: "
                        + viaServiceLoader);
    }

    @Test
    void registryBuild_instantiatesEnabledFactories() {
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .detectSharedMessageDigest(true)
                .build();
        DetectorRegistry reg = DetectorRegistry.build(cfg);

        Detector d = reg.get(DetectorType.SHARED_MESSAGE_DIGEST);
        assertNotNull(d, "Enabled factory must produce a detector instance");
        assertEquals(DetectorType.SHARED_MESSAGE_DIGEST, d.type());
    }

    @Test
    void registryBuild_skipsDisabledFactories() {
        // detectAll=true with the SHARED_MESSAGE_DIGEST exclude is the legacy
        // way to disable a single detector. The factory's isEnabledFor reads
        // config.detectSharedMessageDigest, which build()'s excludes block
        // leaves false in that scenario.
        AsyncTestConfig cfg = AsyncTestConfig.builder()
                .detectAll(true)
                .excludes(new DetectorType[]{DetectorType.SHARED_MESSAGE_DIGEST})
                .build();
        DetectorRegistry reg = DetectorRegistry.build(cfg);
        assertNull(reg.get(DetectorType.SHARED_MESSAGE_DIGEST),
                "Excluded factory must NOT produce a detector");
    }

    @Test
    void adapter_surfacesStructuredViolationsThroughSpi() throws Exception {
        // Narrow the config to ONLY SHARED_MESSAGE_DIGEST so other detector
        // factories don't activate and emit unrelated findings (e.g.
        // UncommittedChangesDetector). detectAll=true plus excluding every
        // other type achieves that.
        AsyncTestConfig.Builder b = AsyncTestConfig.builder().detectAll(true);
        for (DetectorType t : DetectorType.values()) {
            if (t != DetectorType.SHARED_MESSAGE_DIGEST) {
                b.excludes(new DetectorType[]{t});
            }
        }
        AsyncTestConfig cfg = b.build();
        DetectorRegistry reg = DetectorRegistry.build(cfg);

        // Reach into the adapter and feed the wrapped detector some events.
        var adapter = (SharedMessageDigestDetectorFactory.Adapter)
                reg.get(DetectorType.SHARED_MESSAGE_DIGEST);
        SharedMessageDigestDetector legacy = adapter.delegate();

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        legacy.recordAccess(md, "spi-sha", Thread.currentThread());
        Thread t = new Thread(() -> legacy.recordAccess(md, "spi-sha", Thread.currentThread()));
        t.start();
        t.join();

        // Filter to the SharedMessageDigest violation specifically — the SPI
        // registry includes every active factory's findings.
        var violations = reg.analyzeAll().stream()
                .filter(v -> "SharedMessageDigest".equals(v.detector()))
                .toList();
        assertEquals(1, violations.size());
        var v = violations.get(0);
        assertEquals("SharedMessageDigest", v.detector());
        assertTrue(v.message().contains("spi-sha"));
    }

    @Test
    void presetNone_disablesAllSpiDetectors() {
        AsyncTestConfig cfg = AsyncTestConfig.from(stubAnnotationWithPreset(Preset.NONE));
        DetectorRegistry reg = DetectorRegistry.build(cfg);
        assertTrue(reg.all().isEmpty(),
                "Preset.NONE must produce a registry with zero detectors");
    }

    // ---- helper using the existing PresetResolutionTest stub via reflection ----

    private static se.deversity.asynctest.AsyncTest stubAnnotationWithPreset(Preset preset) {
        // PresetResolutionTest.AsyncTestStub is package-private; instantiate via reflection.
        try {
            Class<?> stubCls = Class.forName("se.deversity.asynctest.PresetResolutionTest$AsyncTestStub");
            var ctor = stubCls.getDeclaredConstructor(Preset.class, DetectorType[].class);
            ctor.setAccessible(true);
            return (se.deversity.asynctest.AsyncTest) ctor.newInstance(preset, new DetectorType[0]);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
