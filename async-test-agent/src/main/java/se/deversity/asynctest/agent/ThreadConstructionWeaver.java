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
 * <p>And one more, before the constructor rather than after (#1350): the {@code INVOKESPECIAL} of a
 * {@code new Thread(task)} or {@code new Thread(task, name)} gets a copy of {@code task} handed to
 * the hook's {@code threadTarget(Object)} first, a {@code DUP} for the first shape and
 * {@code DUP2, POP} for the second. {@code new Thread(group, task)} and
 * {@code new Thread(group, task, name)} keep the task in the same place, so they get the same
 * copies, and {@code new Thread(group, task, name, stackSize)} gets {@code DUP2_X2, POP2, DUP2_X2,
 * POP}, which lifts the task out from under the two-slot {@code long} and leaves the arguments as
 * they were (#1401). The superclass call of a direct {@code Thread} subclass, {@code super(task)}
 * and the rest, runs the same constructors on {@code this} and gets the same copies (#1401); a
 * deeper subclass is handed over by its {@code Thread} ancestor's constructor, when that is woven.
 * A {@code FutureTask} run that way is run by {@code Thread.run} inside the JDK, so the target is
 * the only way the library learns which thread ran it. The constructor that also takes
 * {@code inheritThreadLocals} is left alone: its task sits five slots deep, below what a stack
 * instruction reaches, and copying it would need a local. A library without {@code threadTarget}
 * gets none of these calls.
 *
 * <p>All of them insert values and a static call that consumes them, around one instruction and
 * before whatever follows: no branch, no new frame, only {@code maxStack} grows, which
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

    /** The name of the hook handed a new thread's target before its constructor (#1350). */
    static final String TARGET_HOOK = "threadTarget";

    private static final String TARGET_HOOK_DESCRIPTOR = "(Ljava/lang/Object;)V";

    /** The constructor {@code new Thread(task)} calls. */
    private static final String RUNNABLE_CONSTRUCTOR = "(Ljava/lang/Runnable;)V";

    /** The constructor {@code new Thread(task, name)} calls. */
    private static final String NAMED_RUNNABLE_CONSTRUCTOR = "(Ljava/lang/Runnable;Ljava/lang/String;)V";

    /** The constructor {@code new Thread(group, task)} calls (#1401). */
    private static final String GROUP_RUNNABLE_CONSTRUCTOR =
            "(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;)V";

    /** The constructor {@code new Thread(group, task, name, stackSize)} calls (#1401). */
    private static final String SIZED_RUNNABLE_CONSTRUCTOR =
            "(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;J)V";

    /** The constructor {@code new Thread(group, task, name)} calls (#1401). */
    private static final String GROUP_NAMED_RUNNABLE_CONSTRUCTOR =
            "(Ljava/lang/ThreadGroup;Ljava/lang/Runnable;Ljava/lang/String;)V";

    /** The internal name of the class holding {@link #HOOK}. */
    private final String hookOwner;

    /** Whether that class has {@link #TARGET_HOOK}; a library from before #1350 does not. */
    private final boolean takesTargets;

    private ThreadConstructionWeaver(String hookOwner, boolean takesTargets) {
        this.hookOwner = hookOwner;
        this.takesTargets = takesTargets;
    }

    /**
     * {@return the visitor, calling {@code threadConstructed(Thread)} on {@code threadHooks}}
     *
     * @param threadHooks the class holding the hook, resolved in the weaving class loader; one
     *                    without a public static {@code threadConstructed(Thread)} is a version
     *                    skew between agent and library, refused here rather than woven into a
     *                    call that would fail inside user code; one without
     *                    {@code threadTarget(Object)} is an older library, and gets the marker only
     */
    static ThreadConstructionWeaver of(Class<?> threadHooks) {
        try {
            threadHooks.getMethod(HOOK, Thread.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no hook " + HOOK + "(Thread) on "
                    + threadHooks.getName() + "; agent and library versions disagree", e);
        }
        return new ThreadConstructionWeaver(Type.getInternalName(threadHooks), hasTargetHook(threadHooks));
    }

    private static boolean hasTargetHook(Class<?> threadHooks) {
        try {
            threadHooks.getMethod(TARGET_HOOK, Object.class);
            return true;
        } catch (NoSuchMethodException olderLibrary) {
            return false;
        }
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
                return new MarkingVisitor(delegate, hookOwner, takesTargets,
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

        /** Whether to hand a new thread's target to the hook before its constructor (#1350). */
        private final boolean takesTargets;

        /** The superclass whose constructor call marks {@code this}, or null for none. */
        private final @Nullable String markThisAfter;

        /** {@code new Thread} expressions opened by a {@code NEW} and not yet initialised. */
        private int openThreads;

        /** {@code new} expressions of {@link #markThisAfter} opened and not yet initialised. */
        private int openSuperclassInstances;

        /** Whether this constructor still has its superclass call, and {@code this}, to mark. */
        private boolean thisUnmarked;

        MarkingVisitor(MethodVisitor delegate, String hookOwner, boolean takesTargets,
                       @Nullable String markThisAfter) {
            super(Opcodes.ASM9, delegate);
            this.hookOwner = hookOwner;
            this.takesTargets = takesTargets;
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
            // Any store there, not only a reference: an int in local 0 is not this either (#1416).
            if (varIndex == 0 && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                thisUnmarked = false;
            }
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            boolean initialisesANewThread = isConstructorCall(opcode, name)
                    && THREAD.equals(owner) && openThreads > 0;
            // A direct subclass's super(task, ...) runs the same constructor on this (#1401).
            boolean initialisesThisAsAThread = isConstructorCall(opcode, name)
                    && THREAD.equals(owner) && !initialisesANewThread
                    && thisUnmarked && THREAD.equals(markThisAfter);
            if (takesTargets && (initialisesANewThread || initialisesThisAsAThread)) {
                handOverTarget(descriptor);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (!isConstructorCall(opcode, name)) {
                return;
            }
            if (initialisesANewThread) {
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

        private static boolean isConstructorCall(int opcode, String name) {
            return opcode == Opcodes.INVOKESPECIAL && CONSTRUCTOR.equals(name);
        }

        /**
         * Hands a copy of the target of a {@code new Thread(task)} or {@code new Thread(task,
         * name)} to the hook, leaving the constructor's arguments as they were (#1350).
         */
        private void handOverTarget(String descriptor) {
            if (RUNNABLE_CONSTRUCTOR.equals(descriptor)
                    || GROUP_RUNNABLE_CONSTRUCTOR.equals(descriptor)) {
                super.visitInsn(Opcodes.DUP);                 // task -> task, task
            } else if (NAMED_RUNNABLE_CONSTRUCTOR.equals(descriptor)
                    || GROUP_NAMED_RUNNABLE_CONSTRUCTOR.equals(descriptor)) {
                super.visitInsn(Opcodes.DUP2);                // task, name -> task, name, task, name
                super.visitInsn(Opcodes.POP);                 //            -> task, name, task
            } else if (SIZED_RUNNABLE_CONSTRUCTOR.equals(descriptor)) {
                // task, name, size(2) -> size, task, name, size -> size, task, name
                super.visitInsn(Opcodes.DUP2_X2);
                super.visitInsn(Opcodes.POP2);
                // size, task, name -> task, name, size, task, name -> task, name, size, task
                super.visitInsn(Opcodes.DUP2_X2);
                super.visitInsn(Opcodes.POP);
            } else {
                return;
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, TARGET_HOOK, TARGET_HOOK_DESCRIPTOR,
                    false);
        }
    }
}
