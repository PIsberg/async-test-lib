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

import java.util.Set;

/**
 * Tells the library each time a Kotlin {@code runBlocking} is entered, so one called inside a
 * coroutine, on a dispatcher worker, is reported with nothing recorded (#1007).
 *
 * <p>Kotlin compiles {@code runBlocking { }} to a static call on the facade class
 * {@code kotlinx.coroutines.BuildersKt}, under one of the four names in {@link #METHODS}: kotlinx
 * 1.11 compiles new code to {@code runBlockingK}, or {@code runBlockingK$default} without a context
 * (checked with Kotlin 2.4.21), and keeps {@code runBlocking} and {@code runBlocking$default} for
 * code compiled against an older kotlinx. Neither that class nor the Kotlin types in its descriptor
 * are on the agent's class path, so, as with {@link PersistenceHandleWeaver}, the call is matched by
 * the owner and name written at the call site and nothing is substituted. Before each such
 * {@code INVOKESTATIC} this inserts one {@code INVOKESTATIC} of a {@code ()V} hook: it takes nothing
 * off the stack and leaves nothing on it, adds no branch, so frames stay valid and not even
 * {@code maxStack} grows. The call that follows is the original, made unchanged.
 *
 * <p>Only call sites in woven classes are seen, so the code that calls {@code runBlocking} must be
 * in the agent's {@code includes=}. kotlinx's own classes are never rewritten here: its part class
 * calls back through the facade ({@code runBlockingK$default} calls {@code BuildersKt.runBlockingK}),
 * and weaving that would make one user call two hook calls when kotlinx is in {@code includes=}, as
 * it must be for its dispatcher workers to be attributed to the run.
 */
final class RunBlockingWeaver implements AsmVisitorWrapper {

    /** The facade class a Kotlin {@code runBlocking} call site names as its owner. */
    static final String OWNER = "kotlinx/coroutines/BuildersKt";

    /**
     * The names a {@code runBlocking} call site can name: with a context, and the {@code $default}
     * bridge a call without one compiles to, for kotlinx 1.11 and for the binary-compatible
     * original. {@code runBlockingImpl} is internal and never a user call site.
     */
    static final Set<String> METHODS = Set.of(
            "runBlockingK", "runBlockingK$default", "runBlocking", "runBlocking$default");

    /** The dotted package prefix of kotlinx.coroutines, whose own classes this leaves alone. */
    static final String KOTLINX_PACKAGE = "kotlinx.coroutines.";

    /** The hook each call lands in first. */
    static final String HOOK = "runBlockingEntered";

    private final String hookOwner;

    private RunBlockingWeaver(String hookOwner) {
        this.hookOwner = hookOwner;
    }

    /**
     * {@return the weaver, calling the hook on {@code hooks}}
     *
     * @param hooks the class holding the hook, resolved in the weaving class loader; one missing it
     *              is a version skew between agent and library, refused here rather than woven into
     *              a call that would fail inside user code
     */
    static RunBlockingWeaver of(Class<?> hooks) {
        try {
            hooks.getMethod(HOOK);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no runBlocking hook on " + hooks.getName()
                    + "; agent and library versions disagree", e);
        }
        return new RunBlockingWeaver(Type.getInternalName(hooks));
    }

    /** {@return whether a call is a Kotlin {@code runBlocking} as compiled at a call site} */
    static boolean isRunBlocking(int opcode, String owner, String name) {
        return opcode == Opcodes.INVOKESTATIC && OWNER.equals(owner) && METHODS.contains(name);
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
        if (instrumentedType.getName().startsWith(KOTLINX_PACKAGE)) {
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
                        if (isRunBlocking(opcode, owner, method)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, HOOK, "()V", false);
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                    }
                };
            }
        };
    }
}
