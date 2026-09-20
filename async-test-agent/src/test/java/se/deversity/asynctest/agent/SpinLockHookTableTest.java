package se.deversity.asynctest.agent;

import net.bytebuddy.jar.asm.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that every spinlock and reference-slot call the weaver substitutes names a hook the registry
 * really has, and that the release forms #667 and the offer and take forms #664 added are among
 * them.
 *
 * <p>The weaver emits each hook by name and descriptor. A table entry whose hook is missing, or
 * whose descriptor differs by one parameter, weaves without complaint and fails only when the
 * user's code runs the call, with a {@code NoSuchMethodError} inside it. Enumerating the real
 * overloads of each receiver type, rather than the table, is what makes a typo in either visible.
 */
class SpinLockHookTableTest {

    private static final String REGISTRY_TYPE = Type.getInternalName(TelemetryRegistry.class);

    @Test
    @DisplayName("every substituted atomic or updater call names a registry hook with its exact stack shape")
    void everyAtomicSubstitutionResolvesToARegistryHook() {
        Set<String> substituted = new TreeSet<>();
        for (Class<?> owner : List.of(AtomicInteger.class, AtomicBoolean.class,
                AtomicIntegerFieldUpdater.class)) {
            for (Method method : owner.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String descriptor = Type.getMethodDescriptor(method);
                FieldAccessWeaver.Substitution substitution = FieldAccessWeaver
                        .spinLockSubstitution(Type.getInternalName(owner), method.getName(), descriptor);
                if (substitution == null) {
                    continue;
                }
                assertHookExists(substitution);
                assertEquals(Type.getReturnType(descriptor), Type.getReturnType(substitution.descriptor()),
                        owner.getSimpleName() + "." + method.getName() + " must return what the call returned");
                substituted.add(owner.getSimpleName() + "." + method.getName());
            }
        }
        for (String form : List.of("AtomicInteger.setPlain", "AtomicInteger.setOpaque",
                "AtomicInteger.setRelease", "AtomicInteger.getAndUpdate", "AtomicInteger.updateAndGet",
                "AtomicInteger.getAndAccumulate", "AtomicInteger.accumulateAndGet",
                "AtomicInteger.weakCompareAndSet", "AtomicInteger.weakCompareAndSetAcquire",
                "AtomicInteger.weakCompareAndSetRelease", "AtomicInteger.compareAndExchangeAcquire",
                "AtomicInteger.compareAndExchangeRelease", "AtomicBoolean.setPlain",
                "AtomicBoolean.setOpaque", "AtomicBoolean.setRelease", "AtomicBoolean.weakCompareAndSet",
                "AtomicBoolean.weakCompareAndSetAcquire", "AtomicBoolean.weakCompareAndSetRelease",
                "AtomicBoolean.compareAndExchangeAcquire", "AtomicBoolean.compareAndExchangeRelease",
                "AtomicIntegerFieldUpdater.getAndUpdate", "AtomicIntegerFieldUpdater.updateAndGet",
                "AtomicIntegerFieldUpdater.getAndAccumulate",
                "AtomicIntegerFieldUpdater.accumulateAndGet")) {
            assertTrue(substituted.contains(form), form + " is a release the weaver observes since #667; "
                    + "substituted: " + substituted);
        }
    }

    @Test
    @DisplayName("every substituted int VarHandle call names a registry hook, for int and void call sites")
    void everyVarHandleSubstitutionResolvesToARegistryHook() {
        Set<String> substituted = new TreeSet<>();
        List<String> shapes = List.of("(Ljava/lang/Object;I)I", "(Ljava/lang/Object;I)V",
                "(Ljava/lang/Object;II)I", "(Ljava/lang/Object;II)V", "(Ljava/lang/Object;II)Z");
        List<String> names = new ArrayList<>();
        for (Method method : VarHandle.class.getMethods()) {
            if (method.isVarArgs() && Modifier.isNative(method.getModifiers())) {
                names.add(method.getName());
            }
        }
        String owner = Type.getInternalName(VarHandle.class);
        for (String name : names) {
            for (String shape : shapes) {
                FieldAccessWeaver.Substitution substitution =
                        FieldAccessWeaver.spinLockSubstitution(owner, name, shape);
                if (substitution != null) {
                    assertHookExists(substitution);
                    substituted.add(name);
                }
            }
        }
        for (String form : List.of("getAndSetAcquire", "getAndSetRelease", "getAndAddAcquire",
                "getAndAddRelease", "compareAndExchangeAcquire", "compareAndExchangeRelease",
                "weakCompareAndSetAcquire", "weakCompareAndSetRelease", "getAndBitwiseOr",
                "getAndBitwiseOrAcquire", "getAndBitwiseOrRelease", "getAndBitwiseAnd",
                "getAndBitwiseAndAcquire", "getAndBitwiseAndRelease", "getAndBitwiseXor",
                "getAndBitwiseXorAcquire", "getAndBitwiseXorRelease")) {
            assertTrue(substituted.contains(form), "VarHandle." + form
                    + " is a release the weaver observes since #667; substituted: " + substituted);
        }
    }

    @Test
    @DisplayName("every substituted reference-slot call names a registry hook with its exact stack shape (#664)")
    void everyReferenceSlotSubstitutionResolvesToARegistryHook() {
        Set<String> substituted = new TreeSet<>();
        for (Class<?> owner : List.of(AtomicReference.class, AtomicReferenceFieldUpdater.class,
                AtomicReferenceArray.class)) {
            for (Method method : owner.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String descriptor = Type.getMethodDescriptor(method);
                FieldAccessWeaver.Substitution substitution = FieldAccessWeaver
                        .referenceSlotSubstitution(Type.getInternalName(owner), method.getName(),
                                descriptor);
                if (substitution == null) {
                    continue;
                }
                assertHookExists(substitution);
                assertEquals(Type.getReturnType(descriptor), Type.getReturnType(substitution.descriptor()),
                        owner.getSimpleName() + "." + method.getName() + " must return what the call returned");
                substituted.add(owner.getSimpleName() + "." + method.getName());
            }
        }
        String handle = Type.getInternalName(VarHandle.class);
        String chunk = "Lcom/example/Chunk;";
        String receiver = "Lcom/example/Magazine;";
        for (String[] call : List.of(
                new String[] {"set", "(" + receiver + chunk + ")V"},
                new String[] {"setVolatile", "(" + receiver + chunk + ")V"},
                new String[] {"setRelease", "(" + receiver + chunk + ")V"},
                new String[] {"setOpaque", "(" + receiver + chunk + ")V"},
                new String[] {"compareAndSet", "(" + receiver + chunk + chunk + ")Z"},
                new String[] {"getAndSet", "(" + receiver + chunk + ")" + chunk},
                new String[] {"set", "(" + chunk + ")V"},
                new String[] {"setVolatile", "(" + chunk + ")V"},
                new String[] {"setRelease", "(" + chunk + ")V"},
                new String[] {"setOpaque", "(" + chunk + ")V"},
                new String[] {"compareAndSet", "(" + chunk + chunk + ")Z"},
                new String[] {"getAndSet", "(" + chunk + ")" + chunk},
                new String[] {"set", "([Ljava/lang/Object;I" + chunk + ")V"},
                new String[] {"setVolatile", "([Ljava/lang/Object;I" + chunk + ")V"},
                new String[] {"setRelease", "([Ljava/lang/Object;I" + chunk + ")V"},
                new String[] {"setOpaque", "([Ljava/lang/Object;I" + chunk + ")V"},
                new String[] {"compareAndSet", "([Ljava/lang/Object;I" + chunk + chunk + ")Z"},
                new String[] {"getAndSet", "([Ljava/lang/Object;I" + chunk + ")" + chunk})) {
            FieldAccessWeaver.Substitution substitution =
                    FieldAccessWeaver.referenceSlotSubstitution(handle, call[0], call[1]);
            assertTrue(substitution != null, "VarHandle." + call[0] + call[1] + " must be substituted");
            assertHookExists(substitution);
            substituted.add("VarHandle." + call[0]);
        }
        for (String[] untouched : List.<String[]>of(
                new String[] {"set", "(" + receiver + "I)V"})) {
            assertTrue(FieldAccessWeaver.referenceSlotSubstitution(handle, untouched[0], untouched[1]) == null,
                    "an int value is not a reference slot: VarHandle." + untouched[0] + untouched[1]);
        }
        for (String form : List.of("AtomicReference.set", "AtomicReference.lazySet",
                "AtomicReference.setRelease", "AtomicReference.compareAndSet",
                "AtomicReference.getAndSet", "AtomicReferenceFieldUpdater.set",
                "AtomicReferenceFieldUpdater.lazySet", "AtomicReferenceFieldUpdater.compareAndSet",
                "AtomicReferenceFieldUpdater.getAndSet", "AtomicReferenceArray.set",
                "AtomicReferenceArray.lazySet", "AtomicReferenceArray.setRelease",
                "AtomicReferenceArray.compareAndSet", "AtomicReferenceArray.getAndSet")) {
            assertTrue(substituted.contains(form), form + " is an offer or take the weaver observes "
                    + "since #664; substituted: " + substituted);
        }
    }

    private static void assertHookExists(FieldAccessWeaver.Substitution substitution) {
        for (Method candidate : TelemetryRegistry.class.getMethods()) {
            if (candidate.getName().equals(substitution.hook())
                    && Modifier.isStatic(candidate.getModifiers())
                    && Type.getMethodDescriptor(candidate).equals(substitution.descriptor())) {
                return;
            }
        }
        throw new AssertionError("the weaver would emit " + REGISTRY_TYPE + "." + substitution.hook()
                + substitution.descriptor() + ", which the registry does not declare");
    }
}
