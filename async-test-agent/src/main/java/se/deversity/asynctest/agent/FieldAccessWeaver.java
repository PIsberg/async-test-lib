package se.deversity.asynctest.agent;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Weaves an observation call in front of every field instruction in a method body, so a field
 * touched directly — the {@code count++} inside a method, which is the most common shape of a
 * real race — produces telemetry the detectors can see.
 *
 * <p><strong>Why this is not an {@link net.bytebuddy.asm.Advice}.</strong> {@code Advice} binds to
 * a <em>method</em> entry or exit, which is why the accessor weaving in
 * {@link AsyncTestAgent.ReadAccessAdvice} can only observe JavaBean getters and setters: a
 * {@code GETFIELD} in the middle of a method body is not a method call and has no entry to bind to.
 * Observing it requires visiting the instruction stream itself, which is what this wrapper does.
 *
 * <h4>Why this stays safe under retransformation</h4>
 * The inserted sequence pushes four stack slots ({@code long} thread id, the identifier
 * {@code String}, the {@code boolean} write flag) and consumes all of them in the
 * {@code recordAccess} call, so the operand stack is exactly as it was when the original field
 * instruction executes. Crucially it introduces <strong>no branches</strong>, so every stack map
 * frame in the method remains valid and only {@code maxStack} can grow. That is why
 * {@link ClassWriter#COMPUTE_MAXS} is sufficient and {@code COMPUTE_FRAMES} — which would have to
 * load classes to compute common supertypes, from inside an agent, on the class-loading path — is
 * never requested. It also adds no fields, methods or interfaces, which is the invariant that keeps
 * {@code disableClassFormatChanges()} valid on the dynamic-attach path.
 *
 * <h4>What is deliberately not woven</h4>
 * <ul>
 *   <li>Fields whose <em>owner</em> is the JDK, Byte Buddy or this library. Without this a
 *       {@code System.out} read in user code would emit an event per call, and a field access
 *       inside {@code TelemetryRegistry} itself would recurse.</li>
 *   <li>{@code <clinit>}. Emitting from a static initialiser can force {@code TelemetryRegistry}
 *       to initialise in the middle of another class's initialisation, and circular class
 *       initialisation deadlocks rather than failing.</li>
 * </ul>
 *
 * @since 1.9.2
 */
final class FieldAccessWeaver {

    /** Internal name of the telemetry sink the woven call targets. */
    private static final String REGISTRY =
            "se/deversity/asynctest/telemetry/TelemetryRegistry";

    /** Internal name of {@link Thread}. */
    private static final String THREAD = "java/lang/Thread";

    /**
     * Owner prefixes (in internal, slash-separated form) whose fields are never woven.
     *
     * <p>Assembled at runtime rather than written as literals for the same reason
     * {@link AsyncTestAgent#ignoreMatcher()} does it: the Shade plugin rewrites string literals
     * that look like relocated package names, which would silently change what is skipped.
     */
    private static final String[] IGNORED_OWNERS = {
            "java/", "jdk/", "sun/", "com/sun/",
            String.join("/", "net", "bytebuddy") + "/",
            "se/deversity/asynctest/",
    };

    private FieldAccessWeaver() {}

    /**
     * {@return an ASM visitor wrapper that instruments every field instruction in every declared
     * method}
     *
     * <p>{@link ClassWriter#COMPUTE_MAXS} is merged into the writer flags because the inserted
     * sequence raises the operand-stack high-water mark; see the class javadoc for why frames do
     * not need recomputing.
     */
    static AsmVisitorWrapper visitor() {
        return new AsmVisitorWrapper.ForDeclaredMethods()
                .method(ElementMatchers.not(ElementMatchers.isTypeInitializer()), new Wrapper())
                .writerFlags(ClassWriter.COMPUTE_MAXS);
    }

    /**
     * {@return whether a field owned by {@code owner} should be observed}
     *
     * @param owner the field owner in internal, slash-separated form
     */
    static boolean shouldWeave(String owner) {
        for (String ignored : IGNORED_OWNERS) {
            if (owner.startsWith(ignored)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return the identifier reported for {@code owner.name}}
     *
     * <p>Dotted form, matching what {@code TelemetryBridge.fieldIdentifier} expects, so a direct
     * field access and a woven accessor for the same field land under one key and a detector can
     * correlate a read on one thread with a write on another. Computed at weave time and emitted
     * as a constant-pool string, so the hot path allocates nothing.
     */
    static String identifier(String owner, String name) {
        return owner.replace('/', '.') + '.' + name;
    }

    /** Binds {@link FieldAccessMethodVisitor} to every method the matcher selects. */
    private static final class Wrapper
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {

        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool,
                                  int writerFlags,
                                  int readerFlags) {
            return new FieldAccessMethodVisitor(methodVisitor);
        }
    }

    /**
     * Emits {@code TelemetryRegistry.recordAccess(Thread.currentThread().threadId(), id, isWrite)}
     * immediately before each field instruction, leaving the original instruction in place.
     */
    private static final class FieldAccessMethodVisitor extends MethodVisitor {

        FieldAccessMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        /**
         * Declares the monitor a {@code synchronized} block is about to take or release.
         *
         * <p>Without this the agent-fed detectors have no lock model at all: weaving captures
         * which field was touched and on which thread, and {@code synchronized} emits no callback
         * of its own, so a field guarded by a monitor in production code recorded identically to
         * a racing one. The objectref is already on the stack at both instructions, which is why
         * this needs no field-owner capture: {@code DUP} it, hand it to the registry, and let the
         * per-thread lockset answer for every access inside the block.
         *
         * <p>Stack-neutral and branch-free, like the field weaving below: {@code DUP} pushes one
         * slot and the {@code void} call consumes it, so the monitor instruction that follows
         * still sees exactly its own objectref, every stack map frame stays valid, and only
         * {@code maxStack} grows. The call is emitted <em>before</em> the instruction in both
         * cases, so a lock reads as held just before it truly is and released just before it
         * truly is; the declaring thread cannot record an access inside either window, because it
         * is the thread executing these instructions. The compiler's exception-path
         * {@code MONITOREXIT} is woven the same way, so an exception leaving a synchronized block
         * still releases.
         */
        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                String hook = opcode == Opcodes.MONITORENTER ? "monitorEntered" : "monitorExited";
                super.visitInsn(Opcodes.DUP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, hook,
                        "(Ljava/lang/Object;)V", false);
            }
            super.visitInsn(opcode);
        }
        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (shouldWeave(owner)) {
                boolean isWrite = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, THREAD, "currentThread",
                        "()Ljava/lang/Thread;", false);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD, "threadId", "()J", false);
                super.visitLdcInsn(identifier(owner, name));
                super.visitInsn(isWrite ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "recordAccess",
                        "(JLjava/lang/String;Z)V", false);
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }
    }
}
