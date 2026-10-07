package se.deversity.asynctest.spi;

import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AsyncTestConfig;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SPI registry is the third-party path, and only that (#922).
 *
 * <p>Detectors used to be wired twice: the runner's own {@code se.deversity.asynctest.DetectorRegistry}
 * built and analysed the built-ins, and 146 bridge factories in {@code spi.adapters} built a second,
 * blind copy of each for an all-inclusive {@code build(config)} view that only tests called. The
 * bridge cost every new detector two more edit sites and observed nothing. 1.13.0 deletes it: the
 * built-ins have one registry, and {@link DetectorRegistry#buildExternal} carries the detectors a
 * user adds.
 */
class DetectorRegistrySpiTest {

    @Test
    void theLibraryShipsNoDetectorFactoryOfItsOwn() {
        var libraryFactories = StreamSupport
                .stream(ServiceLoader.load(DetectorFactory.class).spliterator(), false)
                .map(f -> f.getClass().getName())
                .filter(n -> n.startsWith("se.deversity.asynctest.") && !n.startsWith("se.deversity.asynctest.spi.")
                        || n.startsWith("se.deversity.asynctest.spi.adapters."))
                .collect(Collectors.toSet());
        assertTrue(libraryFactories.isEmpty(), "built-in detectors have one registry: " + libraryFactories);
    }

    @Test
    void theBuiltInFactoryListAndTheBridgeAreGone() {
        assertNull(DetectorRegistry.class.getClassLoader().getResource("META-INF/async-test/builtin-detector-factories"),
                "the bridge's factory list must not ship");
        assertFalse(classExists("se.deversity.asynctest.spi.adapters.LegacyDetectorFactories"));
        assertFalse(classExists("se.deversity.asynctest.spi.adapters.LegacyDetectorAdapter"));
        assertFalse(classExists("se.deversity.asynctest.spi.adapters.SharedMessageDigestDetectorFactory"));
    }

    @Test
    void theRegistryOffersOnlyTheExternalView() {
        String statics = Arrays.stream(DetectorRegistry.class.getMethods())
                .filter(m -> java.lang.reflect.Modifier.isStatic(m.getModifiers()))
                .map(Method::getName)
                .sorted()
                .collect(Collectors.joining(","));
        assertTrue(statics.equals("buildExternal"),
                "build(config), the view that added the 146 blind bridges, must be gone: " + statics);
    }

    @Test
    void withNoThirdPartyDetectorArmedTheExternalViewIsEmpty() {
        AsyncTestConfig cfg = AsyncTestConfig.builder().detectAll(true).build();
        assertTrue(DetectorRegistry.buildExternal(cfg).all().isEmpty(),
                "every built-in is the runner registry's; the SPI view holds only what a user adds");
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, DetectorRegistry.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
