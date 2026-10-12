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
 * Hands the receiver of each call on a JPA {@code EntityManager} or a Hibernate {@code Session} to
 * the library, so a persistence handle two threads use at once is reported with nothing recorded
 * (#992).
 *
 * <p>The shared-instance rows of {@link CollectionAccessWeaver} replace a call with a hook that
 * makes it, and their owners are {@code Class} literals. Neither works here: the persistence types
 * are not on the agent's class path and must not be, and a hook could not make a call on a type the
 * library cannot name. So this weaver substitutes nothing. Before a call whose owner, as written at
 * the call site, is one of {@link #OWNERS}, it copies the receiver and calls
 * {@code persistenceHandleUsed(Object)}, or {@code persistenceHandleClosed(Object)} before a
 * {@code close()}, leaving the operand stack as it found it: a {@code DUP} with no arguments, a
 * {@code SWAP}, {@code DUP_X1} with one, a {@code DUP2_X1}, {@code POP2}, {@code DUP_X2} with two
 * one-slot arguments. A call with a {@code long} or {@code double} argument, or with more than two,
 * is left alone; none of the handle methods the misuse goes through needs them. No branch is added,
 * so frames stay valid and only {@code maxStack} grows.
 *
 * <p>The owner is matched by name, not assignability: a call through a project's own subtype of
 * {@code EntityManager} is not seen.
 */
final class PersistenceHandleWeaver implements AsmVisitorWrapper {

    /** The call-site owners whose receiver is a persistence handle. */
    static final Set<String> OWNERS = Set.of(
            "jakarta/persistence/EntityManager", "javax/persistence/EntityManager",
            "org/hibernate/Session", "org/hibernate/StatelessSession");

    /** The hook a call other than {@code close()} lands in. */
    static final String USED_HOOK = "persistenceHandleUsed";

    /** The hook a {@code close()} lands in: the handle is let go, the per-transaction hand-off. */
    static final String CLOSED_HOOK = "persistenceHandleClosed";

    private static final String HOOK_DESCRIPTOR = "(Ljava/lang/Object;)V";

    private final String hookOwner;

    private PersistenceHandleWeaver(String hookOwner) {
        this.hookOwner = hookOwner;
    }

    /**
     * {@return the weaver, calling the hooks on {@code hooks}}
     *
     * @param hooks the class holding the hooks, resolved in the weaving class loader; one missing a
     *              hook is a version skew between agent and library, refused here rather than woven
     *              into a call that would fail inside user code
     */
    static PersistenceHandleWeaver of(Class<?> hooks) {
        try {
            hooks.getMethod(USED_HOOK, Object.class);
            hooks.getMethod(CLOSED_HOOK, Object.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no persistence handle hook on " + hooks.getName()
                    + "; agent and library versions disagree", e);
        }
        return new PersistenceHandleWeaver(Type.getInternalName(hooks));
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
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new ObservingVisitor(
                        super.visitMethod(access, name, descriptor, signature, exceptions), hookOwner);
            }
        };
    }

    /** Inserts the receiver copy and hook call before each call on a persistence handle. */
    private static final class ObservingVisitor extends MethodVisitor {

        private final String hookOwner;

        ObservingVisitor(MethodVisitor delegate, String hookOwner) {
            super(Opcodes.ASM9, delegate);
            this.hookOwner = hookOwner;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                    && OWNERS.contains(owner)) {
                observeReceiver(Type.getArgumentTypes(descriptor),
                        "close".equals(name) && "()V".equals(descriptor) ? CLOSED_HOOK : USED_HOOK);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /** Copies the receiver from under {@code arguments} and hands it to {@code hook}, stack-neutrally. */
        private void observeReceiver(Type[] arguments, String hook) {
            for (Type argument : arguments) {
                if (argument.getSize() != 1) {
                    return;
                }
            }
            switch (arguments.length) {
                case 0 -> super.visitInsn(Opcodes.DUP);
                case 1 -> {
                    super.visitInsn(Opcodes.SWAP);
                    super.visitInsn(Opcodes.DUP_X1);
                }
                case 2 -> {
                    super.visitInsn(Opcodes.DUP2_X1);
                    super.visitInsn(Opcodes.POP2);
                    super.visitInsn(Opcodes.DUP_X2);
                }
                default -> {
                    return;
                }
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hook, HOOK_DESCRIPTOR, false);
        }
    }
}
