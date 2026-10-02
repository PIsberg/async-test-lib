package se.deversity.asynctest.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that the default mode, accessor Advice alone, weaves a class in an explicit named module into
 * code that still runs when the library sits on a module path (#862).
 *
 * <p>The sibling of {@link NamedModuleCollectionWeavingTest}, in a class of its own because
 * {@code selfAttach} is at-most-once per JVM and this one attaches with no options; see that class
 * for why the library copy has to be a named module for the test to prove anything.
 */
@Tag("e2e")
class NamedModuleAccessorWeavingTest {

    private static final String BEAN = NamedFixtureLayer.PACKAGE + ".NamedCollectionBean";

    private static Class<?> namedBean;

    /** The module of the library copy the fixture's loader resolves. */
    private static Module library;

    @BeforeAll
    static void attachThenLoadTheNamedModule() throws Exception {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=" + NamedFixtureLayer.PACKAGE);
        ClassLoader libraryLoader =
                NamedFixtureLayer.library().findLoader(NamedFixtureLayer.LIBRARY_MODULE);
        ModuleLayer layer = NamedFixtureLayer.fixture(libraryLoader);
        ClassLoader fixtureLoader = layer.findLoader(NamedFixtureLayer.MODULE);
        library = Class.forName(TelemetryRegistry.class.getName(), false, fixtureLoader).getModule();
        assertFalse(layer.findModule(NamedFixtureLayer.MODULE).orElseThrow().canRead(library),
                "the fixture module must start without the edge, or this proves nothing");
        namedBean = Class.forName(BEAN, true, fixtureLoader);
    }

    @Test
    @DisplayName("a woven accessor in a named module runs instead of throwing IllegalAccessError")
    void wovenAccessorsInANamedModuleLink() throws Exception {
        assertEquals(NamedFixtureLayer.LIBRARY_MODULE, library.getName(),
                "the fixture must resolve the library from a named module, or this proves nothing");
        Object bean = namedBean.getConstructor().newInstance();
        Object size;
        try {
            size = namedBean.getMethod("getSize").invoke(bean);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new AssertionError("a woven accessor in a named module must link to the library; "
                    + "only fields=true gave it a read edge to a module-path library (#862)", e.getCause());
        }
        assertEquals(0, size);
        assertTrue(namedBean.getModule().canRead(library),
                "the woven accessor links only through a read edge to the library copy it calls");
    }
}
