package se.deversity.asynctest.agent;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

/**
 * Lets the library read which field an {@code AtomicIntegerFieldUpdater} really swaps (#659, #668).
 *
 * <p>An updater bound before the agent attached never ran the woven binding call, so the library
 * resolves it from the updater itself: its target class and field offset, which live in private
 * fields of the JDK's implementation in {@code java.util.concurrent.atomic}. That needs the
 * package open to the module of the library copy doing the reading, and which copy that is depends
 * on the woven class: its call sites reach the {@code TelemetryRegistry} its own loader resolves.
 * So for each loader whose classes are woven, this resolves {@code TelemetryRegistry} through that
 * loader, exactly as the woven call sites will, and opens the package to that class's module and to
 * nothing else. That is the unnamed module of whichever loader defined the copy, which need not be
 * the woven loader or one of its ancestors (a sibling bundle loader, OSGi-style), or the named
 * module the copy sits in on a module path ({@code se.deversity.asynctest}).
 *
 * <p>The opening is per module, so every class in the library copy's module can reflect into
 * {@code java.util.concurrent.atomic} for the rest of the run, not only the class that reads it. On
 * a plain class path that module is the application loader's unnamed module, which also holds the
 * test classes; a finer grant does not exist. A woven loader that merely delegates to the library,
 * such as an isolated test classloader, is not opened. Nothing is opened unless field weaving is on,
 * the only mode whose spinlock hooks need it.
 *
 * <p>A woven class in a named module must also read the library's module for its woven call sites
 * to link, so such a module gets that read edge, and no access to {@code java.util.concurrent.atomic}.
 *
 * <p>The bound: a loader that cannot resolve {@code TelemetryRegistry} at all (a bundle that
 * reaches the library only through a thread context loader or a service lookup) opens nothing, and
 * its woven call sites could not link to the library either. A loader whose delegation changes
 * after its first woven class is not followed: the answer is taken once per loader.
 *
 * <p>Failure is silent, like {@link AtomicFieldRegistry}: without the opening an updater bound
 * before the attach stays unresolved and its spinlock undeclared, which reports rather than hides,
 * while an exception here would cost a class its weaving or abort JVM startup from premain.
 */
final class UpdaterAccess {

    private static final String ATOMIC_PACKAGE = "java.util.concurrent.atomic";

    /**
     * The library class every woven hook call goes through. Named, not referenced as a class
     * literal: it must be resolved through each woven loader, never through the agent's own.
     */
    private static final String LIBRARY_ENTRY = "se.deversity.asynctest.telemetry.TelemetryRegistry";

    /** Loaders already handled, weakly, so a discarded test classloader is not retained. */
    private static final Set<ClassLoader> HANDLED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Named woven modules already given their read edge, weakly for the same reason. */
    private static final Set<Module> READING =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private UpdaterAccess() {
    }

    /**
     * Opens {@code java.util.concurrent.atomic} to the module of the library copy {@code loader}
     * resolves.
     *
     * @param inst   the agent's instrumentation
     * @param loader the loader of a class about to be woven; {@code null} (bootstrap) opens nothing
     */
    static void openTo(Instrumentation inst, @Nullable ClassLoader loader) {
        openTo(inst, loader, null);
    }

    /**
     * Opens {@code java.util.concurrent.atomic} to the module of the library copy {@code loader}
     * resolves and, when {@code woven} is a named module, lets it read that copy's module.
     *
     * @param inst   the agent's instrumentation
     * @param loader the loader of a class about to be woven; {@code null} (bootstrap) opens nothing
     * @param woven  the module of that class, or {@code null} when not known
     */
    static void openTo(Instrumentation inst, @Nullable ClassLoader loader, @Nullable Module woven) {
        if (loader == null) {
            return;
        }
        Module named = woven != null && woven.isNamed() ? woven : null;
        if (HANDLED.contains(loader) && (named == null || READING.contains(named))) {
            return;
        }
        // The loader is marked only afterwards: marking it first would let a concurrent transform
        // of another class in the same loader skip the opening and run woven code before it
        // landed. Opening twice is harmless; an updater resolved too early would stay unresolved
        // for the rest of the run.
        try {
            // Loads without initialising, through the same delegation the woven call sites use, so
            // the module found is the one whose SpinLocks will do the reading.
            Module library = Class.forName(LIBRARY_ENTRY, false, loader).getModule();
            Module javaBase = Object.class.getModule();
            if (!javaBase.isOpen(ATOMIC_PACKAGE, library)) {
                inst.redefineModule(javaBase, Set.of(), Map.of(),
                        Map.of(ATOMIC_PACKAGE, Set.of(library)), Set.of(), Map.of());
            }
            if (named != null && !named.canRead(library)) {
                inst.redefineModule(named, Set.of(library), Map.of(), Map.of(), Set.of(), Map.of());
            }
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) { // NOPMD - see the class javadoc
            // Unresolved updaters report rather than hide; see the class javadoc.
        }
        HANDLED.add(loader);
        if (named != null) {
            READING.add(named);
        }
    }
}
