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
}
