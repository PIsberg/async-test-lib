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
 * Pins that {@code collections=true} without {@code fields=true} weaves a class in an explicit named
 * module into code that still runs (#862).
 *
 * <p>The module requires nothing but {@code java.base}, and the library it resolves is a copy in a
 * named module, {@code se.deversity.asynctest} as its jar declares it on a module path. The
 * substituted collection calls, the monitor hooks and the construction hooks link to that copy only
 * through a read edge. The JVM gives a transformed class's module one to the unnamed module of the
 * agent's loader, which covers a class-path library and nothing else; only {@code fields=true} added
 * one to the copy the woven loader resolves, so under {@code collections=true} alone the first woven
 * call threw {@link IllegalAccessError} out of user code.
 *
 * <p>Separate class because {@code selfAttach} is at-most-once per JVM and this class needs
 * {@code collections=true} without {@code fields=true}; {@code reuseForks=false} gives it its own
 * fork.
 */
@Tag("e2e")
class NamedModuleCollectionWeavingTest {

    private static final String BEAN = NamedFixtureLayer.PACKAGE + ".NamedCollectionBean";

    private static Class<?> namedBean;

    /** The module of the library copy the fixture's loader resolves. */
    private static Module library;

    /** Whether the fixture module read the library before the bean was woven. */
    private static boolean readBeforeWeaving;

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

        AsyncTestAgent.selfAttach("includes=" + NamedFixtureLayer.PACKAGE + ",collections=true");
        ClassLoader libraryLoader =
                NamedFixtureLayer.library().findLoader(NamedFixtureLayer.LIBRARY_MODULE);
        ModuleLayer layer = NamedFixtureLayer.fixture(libraryLoader);
        ClassLoader fixtureLoader = layer.findLoader(NamedFixtureLayer.MODULE);
        library = Class.forName(TelemetryRegistry.class.getName(), false, fixtureLoader).getModule();
        Module module = layer.findModule(NamedFixtureLayer.MODULE).orElseThrow();
        readBeforeWeaving = module.canRead(library);
        namedBean = Class.forName(BEAN, true, fixtureLoader);
    }

    @Test
    @DisplayName("a woven collection call in a named module runs instead of throwing IllegalAccessError")
    void wovenCollectionCallsInANamedModuleLink() throws Exception {
        Module module = namedBean.getModule();
        assertTrue(module.isNamed(), "the fixture must be in a named module, or this proves nothing");
        assertEquals(NamedFixtureLayer.MODULE, module.getName());
        assertEquals(NamedFixtureLayer.LIBRARY_MODULE, library.getName(),
                "the fixture must resolve the library from a named module, or the JVM's own edge to "
                        + "the agent loader's unnamed module covers it and this proves nothing");
        assertFalse(readBeforeWeaving, "the fixture module must start without the edge, or this proves nothing");

        Object bean = namedBean.getConstructor().newInstance();
        Object first;
        Object second;
        try {
            first = namedBean.getMethod("record", String.class).invoke(bean, "key");
            second = namedBean.getMethod("record", String.class).invoke(bean, "key");
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new AssertionError("a woven call in a named module must link to the library; "
                    + "collections=true alone gave it no read edge to a module-path library (#862)",
                    e.getCause());
        }
        assertEquals(1, first);
        assertEquals(2, second, "the substituted put and get still do what they did");
    }

    @Test
    @DisplayName("the named module gains a read edge to the library, not access to java.util.concurrent.atomic")
    void theNamedModuleReadsTheLibraryAndNothingIsOpened() {
        Module module = namedBean.getModule();
        assertTrue(module.canRead(library),
                "the woven call sites link only through a read edge to the library copy they call");
        assertFalse(Object.class.getModule().isOpen("java.util.concurrent.atomic", module),
                "the fixture module holds no library copy and must not be opened");
    }
}
