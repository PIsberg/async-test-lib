package se.deversity.asynctest.agent;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.HeldLocks;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a woven class in an explicit named module takes part in spinlock resolution (#668).
 *
 * <p>The module requires nothing but {@code java.base}, and the library sits on the class path, in
 * an unnamed module the named one does not read. Its updater is bound before the attach, so only
 * the retransformed call site sees it, and that call site links to the library only if the agent
 * gave the module a read edge; the updater resolves only if the library copy it links to was opened.
 *
 * <p>Separate class because {@code selfAttach} is at-most-once per JVM and this class needs
 * {@code fields=true}; {@code reuseForks=false} gives it its own fork.
 */
@Tag("e2e")
class NamedModuleUpdaterWeavingTest {

    private static final String BEAN = NamedFixtureLayer.PACKAGE + ".NamedUpdaterSpinLockBean";

    private static Class<?> namedBean;

    /** Whether the fixture module read the library before anything was woven. */
    private static boolean readBeforeAttach;

    @BeforeAll
    static void attachAfterTheNamedModuleInitialised() throws Exception {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        ModuleLayer layer = NamedFixtureLayer.fixture();
        namedBean = Class.forName(BEAN, true, layer.findLoader(NamedFixtureLayer.MODULE));
        readBeforeAttach = namedBean.getModule().canRead(TelemetryRegistry.class.getModule());
        AsyncTestAgent.selfAttach("includes=" + NamedFixtureLayer.PACKAGE + ",fields=true");
    }

    @AfterEach
    void clearLocks() {
        HeldLocks.clear();
    }

    @Test
    @DisplayName("a pre-attach updater in an explicit named module is declared a spinlock when taken")
    void preAttachUpdaterInANamedModuleResolves() throws Exception {
        Module module = namedBean.getModule();
        assertTrue(module.isNamed(), "the fixture must be in a named module, or this proves nothing");
        assertEquals(NamedFixtureLayer.MODULE, module.getName());
        assertSame(TelemetryRegistry.class,
                Class.forName(TelemetryRegistry.class.getName(), false, namedBean.getClassLoader()),
                "the named module must reach the class-path library this test reads");

        Object bean = namedBean.getConstructor().newInstance();
        assertTrue((boolean) namedBean.getMethod("acquire").invoke(bean));
        assertTrue(HeldLocks.anyHeld(),
                "the updater was bound before the attach in a named module; the woven call site "
                        + "must link to the library and the library must read the updater's target");
        assertTrue((boolean) namedBean.getMethod("release").invoke(bean));
        assertFalse(HeldLocks.anyHeld(), "the swap back releases it");
    }

    @Test
    @DisplayName("the named module gains a read edge to the library, not access to java.util.concurrent.atomic")
    void theNamedModuleIsNotOpenedToTheAtomicPackage() {
        Module module = namedBean.getModule();
        assertFalse(readBeforeAttach, "the fixture module must start without the edge, or this proves nothing");
        assertTrue(module.canRead(TelemetryRegistry.class.getModule()),
                "without the read edge the woven call sites cannot link to the library");
        assertFalse(Object.class.getModule().isOpen("java.util.concurrent.atomic", module),
                "the fixture module holds no library copy and must not be opened");
    }
}
