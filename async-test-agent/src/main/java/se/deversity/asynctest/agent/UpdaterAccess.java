package se.deversity.asynctest.agent;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Lets the library read which field an {@code AtomicIntegerFieldUpdater} really swaps (#659).
 *
 * <p>An updater bound before the agent attached never ran the woven binding call, so the library
 * resolves it from the updater itself: its target class and field offset, which live in private
 * fields of the JDK's implementation in {@code java.util.concurrent.atomic}. That needs the
 * package open to the module of the library copy doing the reading, and which copy that is depends
 * on the woven class: its call sites reach the {@code TelemetryRegistry} its own loader resolves.
 * So the package is opened to the unnamed module of every loader whose classes are woven, and of
 * that loader's ancestors, which is where a delegating loader finds the library. This replaces the
 * weave-time records of #619, which always landed in the copy the agent's own loader sees and so
 * missed a runner that loads the library in an isolated classloader.
 *
 * <p>The cost is that code in those unnamed modules can also reflect into
 * {@code java.util.concurrent.atomic} for the rest of the run. Nothing else is opened, and nothing
 * is opened unless field weaving is on, the only mode whose spinlock hooks need it.
 *
 * <p>Failure is silent, like {@link AtomicFieldRegistry}: without the opening an updater bound
 * before the attach stays unresolved and its spinlock undeclared, which reports rather than hides,
 * while an exception here would cost a class its weaving or abort JVM startup from premain.
 */
final class UpdaterAccess {

    private static final String ATOMIC_PACKAGE = "java.util.concurrent.atomic";

    /** Loaders already handled, weakly, so a discarded test classloader is not retained. */
    private static final Set<ClassLoader> OPENED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private UpdaterAccess() {
    }

    /**
     * Opens {@code java.util.concurrent.atomic} to the unnamed module of {@code loader} and of each
     * of its ancestors below the platform loader.
     *
     * @param inst   the agent's instrumentation
     * @param loader the loader of a class about to be woven; {@code null} (bootstrap) opens nothing
     */
    @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
    static void openTo(Instrumentation inst, @Nullable ClassLoader loader) {
        if (loader == null || OPENED.contains(loader)) {
            return;
        }
        // Every ancestor is checked on every first sight of a loader, and the loader is marked
        // only afterwards: marking it first would let a concurrent transform of another class in
        // the same loader skip the opening and run woven code before it landed. Opening twice is
        // harmless; an updater resolved too early would stay unresolved for the rest of the run.
        ClassLoader platform = ClassLoader.getPlatformClassLoader();
        Module javaBase = Object.class.getModule();
        for (ClassLoader current = loader; current != null && current != platform;
             current = current.getParent()) {
            try {
                Module unnamed = current.getUnnamedModule();
                if (!javaBase.isOpen(ATOMIC_PACKAGE, unnamed)) {
                    inst.redefineModule(javaBase, Set.of(), Map.of(),
                            Map.of(ATOMIC_PACKAGE, Set.of(unnamed)), Set.of(), Map.of());
                }
            } catch (RuntimeException | LinkageError e) { // NOPMD - see the class javadoc
                // Unresolved updaters report rather than hide; see the class javadoc.
            }
        }
        OPENED.add(loader);
    }
}
