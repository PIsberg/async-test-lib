package se.deversity.asynctest.agent;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Hands each Kotlin coroutine {@code Mutex} lock, unlock and tryLock to the library, so a mutex
 * locked again by the coroutine that holds it is reported (#1007).
 *
 * <p>{@code mutex.withLock(owner) { }} is inline, so its {@code lock} and {@code unlock} are
 * compiled into the caller as {@code INVOKEINTERFACE kotlinx/coroutines/sync/Mutex.lock} and
 * {@code unlock}; a bare {@code mutex.lock()}, {@code unlock()} or {@code tryLock()} compiles to
 * the interface's static {@code $default} bridge. Each of the six is replaced by an
 * {@code INVOKESTATIC} of a hook that takes the same stack, the Kotlin types written as
 * {@code Object}, and returns the same result: the receiver and owner, the continuation for a lock,
 * and for a bridge its mask and marker. The hook makes the original call through a handle, so no
 * frame or stack depth changes. Neither {@code Mutex} nor the Kotlin types in its descriptors are on
 * the agent's class path, so, as with {@link RunBlockingWeaver}, a call is matched by the owner,
 * name and descriptor written at the call site; a descriptor from some other kotlinx is left alone
 * rather than substituted by a hook that could not make it.
 *
 * <p>Only call sites in woven classes are seen, so the code that locks must be in the agent's
 * {@code includes=}. kotlinx's own classes are never rewritten: its {@code withLock} is inlined into
 * the caller, and its internal mutex use is not the caller's.
 */
final class KotlinMutexWeaver implements AsmVisitorWrapper {

    /** The interface a Kotlin {@code Mutex} call site names as its owner. */
    static final String OWNER = "kotlinx/coroutines/sync/Mutex";

    private static final String OBJ = "Ljava/lang/Object;";
    private static final String MUTEX = "L" + OWNER + ";";
    private static final String CONTINUATION = "Lkotlin/coroutines/Continuation;";

    /** One substituted call: what the call site says, and the hook it becomes. */
    record Row(int opcode, String name, String descriptor, String hook, String hookDescriptor,
               List<Class<?>> hookParameters) {
    }

    /** The six call shapes, as kotlinx 1.11 compiles them. */
    static final List<Row> ROWS = List.of(
            new Row(Opcodes.INVOKEINTERFACE, "lock", "(" + OBJ + CONTINUATION + ")" + OBJ,
                    "kotlinMutexLock", "(" + OBJ + OBJ + OBJ + ")" + OBJ,
                    List.of(Object.class, Object.class, Object.class)),
            new Row(Opcodes.INVOKESTATIC, "lock$default", "(" + MUTEX + OBJ + CONTINUATION + "I" + OBJ + ")" + OBJ,
                    "kotlinMutexLockDefault", "(" + OBJ + OBJ + OBJ + "I" + OBJ + ")" + OBJ,
                    List.of(Object.class, Object.class, Object.class, int.class, Object.class)),
            new Row(Opcodes.INVOKEINTERFACE, "unlock", "(" + OBJ + ")V",
                    "kotlinMutexUnlock", "(" + OBJ + OBJ + ")V",
                    List.of(Object.class, Object.class)),
            new Row(Opcodes.INVOKESTATIC, "unlock$default", "(" + MUTEX + OBJ + "I" + OBJ + ")V",
                    "kotlinMutexUnlockDefault", "(" + OBJ + OBJ + "I" + OBJ + ")V",
                    List.of(Object.class, Object.class, int.class, Object.class)),
            new Row(Opcodes.INVOKEINTERFACE, "tryLock", "(" + OBJ + ")Z",
                    "kotlinMutexTryLock", "(" + OBJ + OBJ + ")Z",
                    List.of(Object.class, Object.class)),
            new Row(Opcodes.INVOKESTATIC, "tryLock$default", "(" + MUTEX + OBJ + "I" + OBJ + ")Z",
                    "kotlinMutexTryLockDefault", "(" + OBJ + OBJ + "I" + OBJ + ")Z",
                    List.of(Object.class, Object.class, int.class, Object.class)));

    private final String hookOwner;

    private KotlinMutexWeaver(String hookOwner) {
        this.hookOwner = hookOwner;
    }

    /**
     * {@return the weaver, calling the hooks on {@code hooks}}
     *
     * @param hooks the class holding the hooks, resolved in the weaving class loader; one missing
     *              any of them is a version skew between agent and library, refused here rather
     *              than woven into a call that would fail inside user code
     */
    static KotlinMutexWeaver of(Class<?> hooks) {
        for (Row row : ROWS) {
            try {
                hooks.getMethod(row.hook(), row.hookParameters().toArray(Class<?>[]::new));
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("no Mutex hook " + row.hook() + " on " + hooks.getName()
                        + "; agent and library versions disagree", e);
            }
        }
        return new KotlinMutexWeaver(Type.getInternalName(hooks));
    }

    /** {@return the row a call site matches, or {@code null} for any other call} */
    static @Nullable Row match(int opcode, String owner, String name, String descriptor) {
        if (!OWNER.equals(owner)) {
            return null;
        }
        for (Row row : ROWS) {
            if (row.opcode() == opcode && row.name().equals(name) && row.descriptor().equals(descriptor)) {
                return row;
            }
        }
        return null;
    }

    @Override
    public int mergeWriter(int flags) {
        return flags | ClassWriter.COMPUTE_MAXS;
    }

    @Override
    public int mergeReader(int flags) {
        return flags;
    }

    @Override
    public ClassVisitor wrap(TypeDescription instrumentedType,
                             ClassVisitor classVisitor,
                             Implementation.Context implementationContext,
                             TypePool typePool,
                             FieldList<FieldDescription.InDefinedShape> fields,
                             MethodList<?> methods,
                             int writerFlags,
                             int readerFlags) {
        if (instrumentedType.getName().startsWith(RunBlockingWeaver.KOTLINX_PACKAGE)) {
            return classVisitor;
        }
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc,
                                                boolean isInterface) {
                        Row row = match(opcode, owner, method, desc);
                        if (row != null) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, row.hook(),
                                    row.hookDescriptor(), false);
                        } else {
                            super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                        }
                    }
                };
            }
        };
    }
}
