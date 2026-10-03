package se.deversity.asynctest.telemetry;

import org.jspecify.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.ConstantDescs;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Names the reference slots the happens-before model keeps a volatile clock for, other than an
 * {@code AtomicReference}'s own value (#741): the field an {@code AtomicReferenceFieldUpdater} or a
 * {@code VarHandle} reaches, and an array element's index.
 *
 * <p>A field is named {@code declaringClass.field}, the name the weaver gives a direct access to it
 * (#813), so a store through an updater and a read through the field meet on one clock. A slot
 * whose field cannot be told keeps no clock: its accesses stay unordered, which can report an
 * access and never hide one.
 */
final class ReferenceSlots {

    /**
     * The JDK's one {@code AtomicReferenceFieldUpdater} implementation, the only shape read.
     * {@code JdkUpdaterShapeCanaryTest} fails the build on a JDK where it, or the members
     * {@link #describe(AtomicReferenceFieldUpdater)} reads, change shape.
     */
    static final String UPDATER_IMPL =
            "java.util.concurrent.atomic.AtomicReferenceFieldUpdater$AtomicReferenceFieldUpdaterImpl";

    /** Which field each updater or handle reaches; the empty string for one that cannot say. */
    private static final Map<Object, String> FIELDS = new ConcurrentHashMap<>();

    /** Element names handed out without allocating, for the indexes most arrays stay within. */
    private static final String[] ELEMENTS = new String[256];

    static {
        for (int i = 0; i < ELEMENTS.length; i++) {
            ELEMENTS[i] = Integer.toString(i);
        }
    }

    private ReferenceSlots() {
    }

    /** {@return the name an array element's clock is kept under: its index} */
    static String element(int index) {
        return index >= 0 && index < ELEMENTS.length ? ELEMENTS[index] : Integer.toString(index);
    }

    /** {@return the field {@code updater} reaches, as {@code declaringClass.field}, or {@code null}} */
    static @Nullable String fieldOf(AtomicReferenceFieldUpdater<?, ?> updater) {
        String field = FIELDS.get(updater);
        if (field == null) {
            field = describe(updater);
            FIELDS.put(updater, field);
        }
        return field.isEmpty() ? null : field;
    }

    /** {@return the instance field {@code handle} reaches, as {@code declaringClass.field}, or {@code null}} */
    static @Nullable String fieldOf(VarHandle handle) {
        String field = FIELDS.get(handle);
        if (field == null) {
            field = describe(handle);
            FIELDS.put(handle, field);
        }
        return field.isEmpty() ? null : field;
    }

    /**
     * {@return {@code declaringClass.field} for a direct instance-field handle of any type, else ""}
     *
     * <p>Its nominal descriptor names the field and the class it was looked up in. An adapted
     * handle, a static field, an array element or a hidden class describes itself as nothing.
     */
    static String describe(VarHandle handle) {
        try {
            if (handle.coordinateTypes().size() != 1) {
                return "";
            }
            Optional<VarHandle.VarHandleDesc> described = handle.describeConstable();
            if (described.isEmpty()
                    || !ConstantDescs.BSM_VARHANDLE_FIELD.equals(described.get().bootstrapMethod())) {
                return "";
            }
            VarHandle.VarHandleDesc desc = described.get();
            ConstantDesc declaring = desc.bootstrapArgsList().get(0);
            if (!(declaring instanceof ClassDesc type) || !type.isClassOrInterface()) {
                return "";
            }
            String descriptor = type.descriptorString();
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.')
                    + '.' + desc.constantName();
        } catch (RuntimeException e) { // NOPMD - an undescribable handle is only an unresolved one
            return "";
        }
    }

    /**
     * {@return {@code targetClass.field} for the field {@code updater} really reaches, else ""}
     *
     * <p>Read from the JDK's implementation, as {@code SpinLocks} reads an int updater (#659): its
     * target class and field offset, matched against an updater made here for each volatile
     * reference field of that class. Needs {@code java.util.concurrent.atomic} open to this module,
     * which the agent arranges with {@code fields=true}; otherwise "" and the slot keeps no clock.
     */
    @SuppressWarnings("ReferenceEquality")
    private static String describe(AtomicReferenceFieldUpdater<?, ?> updater) {
        try {
            Class<?> impl = updater.getClass();
            if (!UPDATER_IMPL.equals(impl.getName()) || impl.getClassLoader() != null) {
                return "";
            }
            Field offsetField = impl.getDeclaredField(SpinLocks.UPDATER_OFFSET);
            Field targetField = impl.getDeclaredField(SpinLocks.UPDATER_TARGET);
            Constructor<?> make = impl.getDeclaredConstructor(Class.class, Class.class, String.class, Class.class);
            if (!offsetField.trySetAccessible() || !targetField.trySetAccessible()
                    || !make.trySetAccessible()) {
                return "";
            }
            long offset = offsetField.getLong(updater);
            Class<?> target = (Class<?>) targetField.get(updater);
            String found = null;
            for (Field candidate : target.getDeclaredFields()) {
                int modifiers = candidate.getModifiers();
                if (candidate.getType().isPrimitive() || !Modifier.isVolatile(modifiers)
                        || Modifier.isStatic(modifiers)) {
                    continue;
                }
                Object twin = make.newInstance(target, candidate.getType(), candidate.getName(), target);
                if (offsetField.getLong(twin) == offset) {
                    if (found != null) {
                        return "";
                    }
                    found = candidate.getName();
                }
            }
            return found == null ? "" : target.getName() + '.' + found;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { // NOPMD - unresolved is the safe answer
            return "";
        }
    }
}
