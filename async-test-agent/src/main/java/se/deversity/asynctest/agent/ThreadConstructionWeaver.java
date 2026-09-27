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

/**
 * Marks every platform thread a woven class constructs, so that a missing daemon decision on it
 * means something (#737).
 *
 * <p>A thread inherits its daemon flag from the thread that constructs it, and a runner worker is
 * daemon, so {@code DaemonThreadHygieneDetector} judges a woven start by whether a woven
 * {@code setDaemon} was seen rather than by the flag. That is only fair to a thread whose
 * configuration the agent could have seen: one constructed in a class outside {@code includes=}
 * may have been given {@code setDaemon(true)} there, and reporting it as undecided reports the
 * code that decided. This visitor tells the library which threads the agent did see constructed.
 *
 * <p>Two shapes, both a call to the hook's {@code threadConstructed(Thread)} with the new thread:
 * <ul>
 *   <li>After the {@code INVOKESPECIAL java/lang/Thread.<init>} of a {@code new Thread(...)}: a
 *       {@code DUP} of the initialised reference the {@code NEW}/{@code DUP} pair left beneath the
 *       constructor's arguments, then the call. Which {@code <init>} belongs to a {@code NEW} is
 *       counted, not guessed: a {@code NEW java/lang/Thread} opens one, and the constructor calls
 *       nest, so the innermost open one completes first. A {@code Thread.<init>} with none open is
 *       a subclass's superclass constructor call and gets no {@code DUP}, because nothing is left
 *       beneath it.</li>
 *   <li>In each constructor of a {@code Thread} subclass, after its superclass constructor call:
 *       an {@code ALOAD 0} of the now initialised {@code this}, then the call. A {@code this(...)}
 *       delegation is left alone, since the constructor it delegates to marks, and a constructor
 *       that has stored to local 0 is left alone, since local 0 may no longer be {@code this}.</li>
 * </ul>
 *
 * <p>Both insert one value and a static call that consumes it, after an instruction and before
 * whatever follows: no branch, no new frame, only {@code maxStack} grows, which
 * {@code COMPUTE_MAXS} covers. No member is added, so this is safe under retransformation with
 * {@code disableClassFormatChanges()}. The counting relies on a {@code new} expression's
 * {@code NEW ... <init>} being emitted in order in one method, which every Java compiler does; a
 * miscounted {@code DUP} would duplicate something that is not the thread and fail verification.
 *
 * <p>Deliberately unseen: a subclass instance constructed where nothing is woven still runs its
 * woven constructor and is marked, and a thread constructed in woven code but configured by a call
 * into unwoven code has that decision missed, unless it changed the flag the thread inherited,
 * which the library compares at the start (#856). Both are narrower than the blind spot this
 * closes.
 */
final class ThreadConstructionWeaver implements AsmVisitorWrapper {

    private static final String THREAD = "java/lang/Thread";

    private static final String OBJECT = "java/lang/Object";

    private static final String CONSTRUCTOR = "<init>";

    /** The name of the hook the inserted call lands in. */
    static final String HOOK = "threadConstructed";

    private static final String HOOK_DESCRIPTOR = "(Ljava/lang/Thread;)V";

    /** The internal name of the class holding {@link #HOOK}. */
    private final String hookOwner;

    private ThreadConstructionWeaver(String hookOwner) {
        this.hookOwner = hookOwner;
    }

    /**
     * {@return the visitor, calling {@code threadConstructed(Thread)} on {@code threadHooks}}
     *
     * @param threadHooks the class holding the hook, resolved in the weaving class loader; one
     *                    without a public static {@code threadConstructed(Thread)} is a version
     *                    skew between agent and library, refused here rather than woven into a
     *                    call that would fail inside user code
     */
    static ThreadConstructionWeaver of(Class<?> threadHooks) {
        try {
            threadHooks.getMethod(HOOK, Thread.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no hook " + HOOK + "(Thread) on "
                    + threadHooks.getName() + "; agent and library versions disagree", e);
        }
        return new ThreadConstructionWeaver(Type.getInternalName(threadHooks));
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

            /** The superclass, when this class is a {@code Thread} subclass; otherwise null. */
            private @Nullable String threadSuperclass;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                if (superName != null && isThreadSubclass(superName, instrumentedType)) {
                    threadSuperclass = superName;
                }
                super.visit(version, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MarkingVisitor(delegate, hookOwner,
                        CONSTRUCTOR.equals(name) ? threadSuperclass : null);
            }
        };
    }

    /**
     * {@return whether a class with this superclass is a {@code Thread}}
     *
     * <p>A direct subclass needs no lookup, and a class extending {@code Object} directly, which is
     * nearly every class, is answered without one. Anything else asks the pool once per class; a
     * superclass it cannot resolve answers no, which leaves that class's threads unmarked and so
     * judged by their flag, the direction that cannot report correct code.
     */
    private static boolean isThreadSubclass(String superName, TypeDescription instrumentedType) {
        if (THREAD.equals(superName)) {
            return true;
        }
        if (OBJECT.equals(superName)) {
            return false;
        }
        try {
            return instrumentedType.isAssignableTo(Thread.class);
        } catch (RuntimeException unresolvable) { // NOPMD - any resolution failure means "not known to be a Thread"
            return false;
        }
    }

    /** Inserts the hook call after each thread construction in one method. */
    private static final class MarkingVisitor extends MethodVisitor {

        private final String hookOwner;

        /** The superclass whose constructor call marks {@code this}, or null for none. */
        private final @Nullable String markThisAfter;

        /** {@code new Thread} expressions opened by a {@code NEW} and not yet initialised. */
        private int openThreads;

        /** {@code new} expressions of {@link #markThisAfter} opened and not yet initialised. */
        private int openSuperclassInstances;

        /** Whether this constructor still has its superclass call, and {@code this}, to mark. */
        private boolean thisUnmarked;

        MarkingVisitor(MethodVisitor delegate, String hookOwner, @Nullable String markThisAfter) {
            super(Opcodes.ASM9, delegate);
            this.hookOwner = hookOwner;
            this.markThisAfter = markThisAfter;
            this.thisUnmarked = markThisAfter != null;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) {
                if (THREAD.equals(type)) {
                    openThreads++;
                } else if (thisUnmarked && type.equals(markThisAfter)) {
                    openSuperclassInstances++;
                }
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            if (varIndex == 0 && opcode == Opcodes.ASTORE) {
                thisUnmarked = false;
            }
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (opcode != Opcodes.INVOKESPECIAL || !CONSTRUCTOR.equals(name)) {
                return;
            }
            if (THREAD.equals(owner) && openThreads > 0) {
                openThreads--;
                super.visitInsn(Opcodes.DUP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, HOOK, HOOK_DESCRIPTOR,
                        false);
            } else if (thisUnmarked && owner.equals(markThisAfter)) {
                if (openSuperclassInstances > 0) {
                    openSuperclassInstances--;
                    return;
                }
                thisUnmarked = false;
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, HOOK, HOOK_DESCRIPTOR,
                        false);
            }
        }
    }
}
