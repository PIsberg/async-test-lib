package se.deversity.asynctest.agent;

import com.example.unwovenfixture.AtomicInternalsProbe;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins who {@code java.util.concurrent.atomic} is opened to (#668): the module of the library copy
 * a woven loader resolves, and nobody else.
 *
 * <p>Each case builds loaders of its own and hands one to {@link UpdaterAccess#openTo} as if a class
 * it defined were about to be woven. The probe asks from inside that loader whether its code can
 * reflect into the package; the library copy is asked whether it still resolves an updater nothing
 * bound, which is the read the opening exists for. Openings are permanent for the JVM, so no two
 * cases share a loader, and {@code reuseForks=false} gives the class a JVM of its own.
 */
class UpdaterAccessTest {

    private static final String PROBE = AtomicInternalsProbe.class.getName();

    private static final String SPIN_LOCKS = "se.deversity.asynctest.telemetry.SpinLocks";

    private static Instrumentation inst;

    /** A flag the updaters under test swap. */
    static final class Flag {
        volatile int busy;
    }

    @BeforeAll
    static void instrumentation() {
        Instrumentation handle;
        try {
            handle = ByteBuddyAgent.install();
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            handle = null;
        }
        assumeTrue(handle != null, "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");
        inst = handle;
    }

    @Test
    @DisplayName("a woven loader that reaches the class-path library is not itself opened; the library is")
    void aDelegatingWovenLoaderIsNotOpened() throws Exception {
        ClassLoader test = UpdaterAccessTest.class.getClassLoader();
        SplitLoader woven = new SplitLoader("woven", PROBE::equals, test, test);

        UpdaterAccess.openTo(inst, woven);

        assertFalse(canReflect(woven),
                "the woven loader's unnamed module holds no library copy and must not be opened");
        assertEquals(Flag.class.getName() + ".busy", resolveIn(test),
                "the library copy the woven loader reaches must still read the updater's target");
    }

    @Test
    @DisplayName("a library copy in a sibling loader, outside the woven loader's chain, is opened (OSGi-style)")
    void aLibraryCopyOutsideTheWovenChainIsOpened() throws Exception {
        ClassLoader test = UpdaterAccessTest.class.getClassLoader();
        SplitLoader library = new SplitLoader("library", UpdaterAccessTest::isLibrary, test, null);
        SplitLoader woven = new SplitLoader("woven", PROBE::equals, test, library);
        assertNotSame(TelemetryRegistry.class,
                Class.forName(TelemetryRegistry.class.getName(), false, woven),
                "the woven loader must reach the sibling's copy, or this proves nothing");

        UpdaterAccess.openTo(inst, woven);

        assertEquals(Flag.class.getName() + ".busy", resolveIn(library),
                "the copy the woven class calls lives in a loader that is not its ancestor, and "
                        + "that copy is the one reading the updater");
        assertFalse(canReflect(woven), "the woven loader itself must not be opened");
    }

    @Test
    @DisplayName("a library copy in a named module, as on a module path, is opened")
    void aLibraryCopyInANamedModuleIsOpened() throws Exception {
        ClassLoader test = UpdaterAccessTest.class.getClassLoader();
        ClassLoader library = NamedFixtureLayer.library().findLoader(NamedFixtureLayer.LIBRARY_MODULE);
        SplitLoader woven = new SplitLoader("modular", PROBE::equals, test, library);
        Module libraryModule = Class.forName(TelemetryRegistry.class.getName(), false, woven).getModule();
        assertEquals(NamedFixtureLayer.LIBRARY_MODULE, libraryModule.getName(),
                "the woven loader must reach the named copy, or this proves nothing");

        UpdaterAccess.openTo(inst, woven);

        assertEquals(Flag.class.getName() + ".busy", resolveIn(library),
                "the copy the woven class calls is a named module, not any loader's unnamed one");
        assertFalse(canReflect(woven), "the woven loader itself must not be opened");
    }

    @Test
    @DisplayName("a woven loader that cannot reach the library opens nothing and throws nothing")
    void aLoaderWithoutTheLibraryOpensNothing() throws Exception {
        SplitLoader woven = new SplitLoader("orphan", PROBE::equals,
                ClassLoader.getPlatformClassLoader(), null);

        assertDoesNotThrow(() -> UpdaterAccess.openTo(inst, woven));

        assertFalse(canReflect(woven), "nothing there resolves updaters, so nothing is opened");
    }

    @Test
    @DisplayName("an instrumentation that refuses the redefinition leaves openTo silent")
    void aRefusedRedefinitionIsSilent() {
        Instrumentation refusing = (Instrumentation) Proxy.newProxyInstance(
                UpdaterAccessTest.class.getClassLoader(), new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                    if ("redefineModule".equals(method.getName())) {
                        throw new UnsupportedOperationException("simulated");
                    }
                    return method.invoke(inst, args);
                });
        ClassLoader test = UpdaterAccessTest.class.getClassLoader();
        SplitLoader library = new SplitLoader("refused-library", UpdaterAccessTest::isLibrary, test, null);
        SplitLoader woven = new SplitLoader("refused", PROBE::equals, test, library);

        assertDoesNotThrow(() -> UpdaterAccess.openTo(refusing, woven),
                "openTo runs from premain and from inside class transformation; it must never throw");
    }

    private static boolean isLibrary(String name) {
        return name.startsWith("se.deversity.asynctest.") && !name.startsWith("se.deversity.asynctest.agent.");
    }

    /** {@return what the probe copy {@code loader} defines says about reflecting into the package} */
    private static boolean canReflect(ClassLoader loader) throws Exception {
        Class<?> probe = Class.forName(PROBE, true, loader);
        assertNotSame(AtomicInternalsProbe.class, probe, "the probe must be the loader's own copy");
        return (boolean) probe.getMethod("canReadUpdaterInternals").invoke(null);
    }

    /** {@return the field the {@code SpinLocks} copy {@code loader} resolves names for a fresh updater} */
    private static @Nullable String resolveIn(ClassLoader loader) throws Exception {
        Method fieldOf = Class.forName(SPIN_LOCKS, true, loader)
                .getDeclaredMethod("fieldOf", AtomicIntegerFieldUpdater.class);
        fieldOf.setAccessible(true);
        return (String) fieldOf.invoke(null, AtomicIntegerFieldUpdater.newUpdater(Flag.class, "busy"));
    }

    /**
     * Defines the classes {@code own} names from the test's class path entries, sends library
     * classes to {@code library} when given, and delegates everything else to {@code parent}.
     */
    private static final class SplitLoader extends URLClassLoader {

        private final Predicate<String> own;

        private final @Nullable ClassLoader library;

        SplitLoader(String name, Predicate<String> own, ClassLoader parent, @Nullable ClassLoader library) {
            super(name, new URL[] {location(TelemetryRegistry.class), location(UpdaterAccessTest.class)}, parent);
            this.own = own;
            this.library = library;
        }

        private static URL location(Class<?> type) {
            return type.getProtectionDomain().getCodeSource().getLocation();
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (own.test(name)) {
                        loaded = findClass(name);
                    } else if (library != null && isLibrary(name)) {
                        loaded = library.loadClass(name);
                    } else if (isLibrary(name) && getParent() == ClassLoader.getPlatformClassLoader()) {
                        throw new ClassNotFoundException(name);
                    } else {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
