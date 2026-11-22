package se.deversity.asynctest.agent;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the one call that runs every coroutine step, {@code invokeSuspend} in kotlin stdlib's
 * {@code BaseContinuationImpl.resumeWith}, with a hook that marks the step on the thread for its
 * length (#1007).
 *
 * <p>A field rule keyed by thread cannot tell two coroutines on one thread apart; the library's
 * hook can, once it knows which coroutine each step belongs to. The hook takes the receiver and the
 * argument and returns the result, the call's own stack, and makes the call through a handle in a
 * {@code try}/{@code finally} of its own, so the weaver adds no handler and no frame: one
 * {@code INVOKEVIRTUAL} becomes one {@code INVOKESTATIC}.
 *
 * <p>Applied to that class alone, whatever {@code includes=} says, and only when its module is
 * unnamed, where the library can open the protected method to call it.
 */
final class CoroutineStepWeaver implements AsmVisitorWrapper {

    /** The stdlib class whose {@code resumeWith} runs every coroutine step. */
    static final String BASE_CONTINUATION = "kotlin.coroutines.jvm.internal.BaseContinuationImpl";

    private static final String INTERNAL_NAME = BASE_CONTINUATION.replace('.', '/');
    private static final String INVOKE_SUSPEND = "invokeSuspend";
    private static final String STEP_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";

    /** The hook's name, on {@code se.deversity.asynctest.AgentCoroutineHooks}. */
    static final String HOOK = "coroutineStep";

    /** The hook's descriptor: the continuation and the argument, then the result. */
    static final String HOOK_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

    private final String hookOwner;

    private CoroutineStepWeaver(String hookOwner) {
        this.hookOwner = hookOwner;
    }

    /**
     * {@return the weaver, calling the hook on {@code hooks}}
     *
     * @param hooks the class holding {@value #HOOK}; one without it is a version skew between agent
     *              and library, refused here rather than woven into every coroutine
     */
    static CoroutineStepWeaver of(Class<?> hooks) {
        try {
            hooks.getMethod(HOOK, Object.class, Object.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no " + HOOK + " on " + hooks.getName()
                    + "; agent and library versions disagree", e);
        }
        return new CoroutineStepWeaver(Type.getInternalName(hooks));
    }

    @Override
    public int mergeWriter(int flags) {
        return flags;
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
        return BASE_CONTINUATION.equals(instrumentedType.getName()) ? visitor(classVisitor) : classVisitor;
    }

    /**
     * {@return a visitor that substitutes the {@code invokeSuspend} call in {@code resumeWith} of the
     * class it visits, and changes nothing else}; package-private for the weaver test.
     */
    ClassVisitor visitor(ClassVisitor classVisitor) {
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"resumeWith".equals(name)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc,
                                                boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && INTERNAL_NAME.equals(owner)
                                && INVOKE_SUSPEND.equals(method) && STEP_DESCRIPTOR.equals(desc)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, HOOK, HOOK_DESCRIPTOR,
                                    false);
                        } else {
                            super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                        }
                    }
                };
            }
        };
    }
}
