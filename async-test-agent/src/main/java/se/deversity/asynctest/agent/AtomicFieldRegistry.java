package se.deversity.asynctest.agent;

import java.lang.reflect.Method;

import org.jspecify.annotations.Nullable;

import se.deversity.vibetags.annotations.AIContract;

/**
 * Tells the library, at weave time, which fields belong to a lock-free protocol.
 *
 * <p>Reflection rather than a direct call because the agent module must not depend on the library
 * ({@code ArchitectureTest} pins that), and by name rather than by emitted bytecode because the
 * binding this reports sits in a static initializer: a class initialised before the agent attached
 * would never run an emitted call, and those are exactly the classes that hold this machinery.
 *
 * <p>Failure is silent by design. Everything this adds is a reason to stay quiet about a field, so
 * losing it costs a false positive, never a missed defect, and an agent that throws while weaving
 * would cost the user their whole test run.
 */
@AIContract(reason = "Resolved reflectively so async-test-agent keeps its zero-dependency boundary on async-test-lib, which ArchitectureTest enforces in both directions. The method name and signature must match TelemetryRegistry.atomicallyManaged(String). Every failure path here must stay silent: this only ever suppresses findings, so losing it degrades precision rather than correctness, while throwing out of a class transformation would fail the user's test run.")
final class AtomicFieldRegistry {

    private static final @Nullable Method RECORD = resolve();
    private static final @Nullable Method RECORD_INT_UPDATER = resolveIntUpdater();
    private static final @Nullable Method RECORD_SCANNED = resolveScanned();

    private AtomicFieldRegistry() {
    }

    private static @Nullable Method resolve() {
        try {
            Class<?> registry = Class.forName("se.deversity.asynctest.telemetry.TelemetryRegistry",
                    false, AtomicFieldRegistry.class.getClassLoader());
            return registry.getMethod("atomicallyManaged", String.class);
        } catch (ReflectiveOperationException | LinkageError e) { // NOPMD - see the class javadoc
            return null;
        }
    }

    private static @Nullable Method resolveIntUpdater() {
        try {
            Class<?> registry = Class.forName("se.deversity.asynctest.telemetry.TelemetryRegistry",
                    false, AtomicFieldRegistry.class.getClassLoader());
            return registry.getMethod("atomicUpdaterFieldRecorded", String.class, String.class);
        } catch (ReflectiveOperationException | LinkageError e) { // NOPMD - see the class javadoc
            return null;
        }
    }

    private static @Nullable Method resolveScanned() {
        try {
            Class<?> registry = Class.forName("se.deversity.asynctest.telemetry.TelemetryRegistry",
                    false, AtomicFieldRegistry.class.getClassLoader());
            return registry.getMethod("atomicUpdaterClassScanned", String.class);
        } catch (ReflectiveOperationException | LinkageError e) { // NOPMD - see the class javadoc
            return null;
        }
    }

    /**
     * Records that {@code qualifiedName} is mutated through a {@code VarHandle} or atomic updater.
     *
     * @param qualifiedName the field, as {@code declaringClass.field}
     */
    static void record(String qualifiedName) {
        Method record = RECORD;
        if (record == null) {
            return;
        }
        try {
            record.invoke(null, qualifiedName);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - see class javadoc
            // Silence is the safe direction here; see the class javadoc.
        }
    }

    /**
     * Records that {@code ownerClass} binds {@code field} through an
     * {@code AtomicIntegerFieldUpdater.newUpdater} call (#619).
     *
     * @param ownerClass the owner class passed to {@code newUpdater}
     * @param field      the qualified field name, as {@code declaringClass.field}
     */
    static void recordIntUpdater(String ownerClass, String field) {
        Method record = RECORD_INT_UPDATER;
        if (record == null) {
            return;
        }
        try {
            record.invoke(null, ownerClass, field);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - see class javadoc
            // Silence is the safe direction here; see the class javadoc.
        }
    }

    /**
     * Records that the weaver has scanned every method of {@code className} (#619).
     *
     * <p>Losing this record is also the safe direction: a pre-attach updater whose hierarchy is
     * not known to be fully scanned stays unresolved.
     *
     * @param className the scanned class, as a qualified name
     */
    static void recordScanned(String className) {
        Method record = RECORD_SCANNED;
        if (record == null) {
            return;
        }
        try {
            record.invoke(null, className);
        } catch (ReflectiveOperationException | RuntimeException e) { // NOPMD - see class javadoc
            // Silence is the safe direction here; see the class javadoc.
        }
    }
}
