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

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Hands a {@code ThreadPoolExecutor} subclass's {@code beforeExecute} and {@code afterExecute}, and
 * a {@code RejectedExecutionHandler}'s {@code rejectedExecution}, the task the caller gave rather
 * than the wrapper a woven {@code execute} queues around it (#1173).
 *
 * <p>A woven {@code execute} wraps a task for a {@code ThreadPoolExecutor} inside a run, so the task
 * starts ordered after the call and its thread is attributed to the run. A pool whose own methods
 * are handed the task would see the wrapper, so the library leaves such a pool's tasks alone. At the
 * entry of each of these three methods, in a woven class, this weaver inserts
 * {@code ALOAD task; INVOKESTATIC poolTask(Runnable)Runnable; ASTORE task}: the hook returns the
 * task the wrapper holds, or its argument unchanged, and the method then runs as written. The
 * parameter keeps its type, so frames stay valid and only maxStack grows.
 *
 * <p>The weaver also reports each class it rewrites to the library ({@code poolTaskOverrideWoven}),
 * which only then counts these overrides as seeing no wrapper. A class outside {@code includes=},
 * or one whose report fails, is never reported, and its pool stays unwrapped: the direction that
 * cannot report correct code.
 */
final class PoolTaskWeaver implements AsmVisitorWrapper {

    /** The hook inserted at each method's entry. */
    static final String HOOK = "poolTask";
    static final String HOOK_DESCRIPTOR = "(Ljava/lang/Runnable;)Ljava/lang/Runnable;";

    /** The library method told of each rewritten class. */
    static final String REPORT = "poolTaskOverrideWoven";

    /** One rewritten method: its name, descriptor and the local holding the task. */
    record Site(String name, String descriptor, int slot) {
    }

    /** The methods of a {@code ThreadPoolExecutor} subclass the pool hands each task. */
    static final List<Site> POOL_SITES = List.of(
            new Site("beforeExecute", "(Ljava/lang/Thread;Ljava/lang/Runnable;)V", 2),
            new Site("afterExecute", "(Ljava/lang/Runnable;Ljava/lang/Throwable;)V", 1));

    /** The method of a {@code RejectedExecutionHandler} the pool hands a refused task. */
    static final List<Site> HANDLER_SITES = List.of(
            new Site("rejectedExecution", "(Ljava/lang/Runnable;Ljava/util/concurrent/ThreadPoolExecutor;)V", 1));

    private final String hookOwner;
    private final Method report;

    private PoolTaskWeaver(String hookOwner, Method report) {
        this.hookOwner = hookOwner;
        this.report = report;
    }

    /**
     * {@return the weaver, calling the hooks on {@code hooks}}
     *
     * @param hooks the class holding the hooks, resolved in the weaving class loader; one missing
     *              either is a version skew between agent and library, refused here rather than
     *              woven into a call that would fail inside user code
     */
    static PoolTaskWeaver of(Class<?> hooks) {
        try {
            hooks.getMethod(HOOK, Runnable.class);
            return new PoolTaskWeaver(Type.getInternalName(hooks), hooks.getMethod(REPORT, String.class));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no pool task hooks on " + hooks.getName()
                    + "; agent and library versions disagree", e);
        }
    }

    /**
     * {@return the site a method of a class is, or {@code null}}
     *
     * @param pool    whether the class is a {@code ThreadPoolExecutor} subclass
     * @param handler whether it is a {@code RejectedExecutionHandler}
     */
    static @Nullable Site site(boolean pool, boolean handler, int access, String name, String descriptor) {
        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) {
            return null;
        }
        for (Site site : pool ? POOL_SITES : List.<Site>of()) {
            if (site.name().equals(name) && site.descriptor().equals(descriptor)) {
                return site;
            }
        }
        for (Site site : handler ? HANDLER_SITES : List.<Site>of()) {
            if (site.name().equals(name) && site.descriptor().equals(descriptor)) {
                return site;
            }
        }
        return null;
    }

    /** {@return whether {@code type} is a concrete subtype of {@code of}; one that cannot be resolved is not} */
    private static boolean isA(TypeDescription type, Class<?> of) {
        try {
            return !type.isInterface() && !type.represents(of) && type.isAssignableTo(of);
        } catch (RuntimeException | LinkageError unresolvable) { // NOPMD - an unresolvable type is left alone
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
        boolean pool = isA(instrumentedType, ThreadPoolExecutor.class);
        boolean handler = isA(instrumentedType, RejectedExecutionHandler.class);
        if (!pool && !handler) {
            return classVisitor;
        }
        String className = instrumentedType.getName();
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            private boolean reported;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                Site site = site(pool, handler, access, name, descriptor);
                if (site == null) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, site.slot());
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, HOOK, HOOK_DESCRIPTOR, false);
                        super.visitVarInsn(Opcodes.ASTORE, site.slot());
                        if (!reported) {
                            reported = true;
                            report(className);
                        }
                    }
                };
            }
        };
    }

    /** Tells the library {@code className} was rewritten; silently, since weaving must never throw. */
    private void report(String className) {
        try {
            report.invoke(null, className);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { // NOPMD - unreported keeps the pool unwrapped
            // The pool then stays unwrapped, as before #1173.
        }
    }
}
