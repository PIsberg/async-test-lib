package se.deversity.asynctest.agent;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.Attribute;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.TypePath;
import net.bytebuddy.pool.TypePool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Consumer;

/**
 * Finds a {@code catch (InterruptedException e)} that swallows the interrupt, and tells the library
 * when the method holding it runs.
 *
 * <p>An interrupt is the one cancellation signal a thread gets, and catching
 * {@code InterruptedException} clears it. A handler that neither rethrows nor calls
 * {@code Thread.currentThread().interrupt()} leaves every caller up the stack unable to see that
 * the thread was asked to stop. It is the most common concurrency defect static analysers report,
 * and the library's two detectors for it fired only when the body recorded the catch itself.
 *
 * <p>A handler runs only when an interrupt arrives, which a test seldom delivers, so waiting for one
 * would almost never report anything. The handler's shape is in the bytecode, though, so this
 * visitor reads it. A method whose code declares a handler for {@code InterruptedException} is
 * buffered whole, and each such handler is followed through the method's control flow from its
 * first instruction:
 * <ul>
 *   <li>a path that reaches an {@code ATHROW} hands the exception, or a wrapper of it, on;</li>
 *   <li>a call to a method named {@code interrupt}, or to a helper whose name mentions an
 *       interrupt other than {@code interrupted} and {@code isInterrupted}, which read the flag,
 *       restores it, and one reachable on any path from the handler answers for the whole
 *       handler. The walk goes on through the method's ordinary code for that reason: Guava's
 *       {@code Uninterruptibles} notes the interrupt, goes on waiting, and restores the flag in
 *       a {@code finally};</li>
 *   <li>a handler with no such call reachable, and a path to a return, swallows the interrupt:
 *       the caller never learns of it. A {@code run()} that returns is the one exception, a
 *       thread's own loop exiting on interrupt being its interruption policy.</li>
 * </ul>
 * A path the bounded walk does not finish is not reported, so the rule errs toward silence.
 *
 * <p>When at least one handler swallows, the method's code starts with an {@code LDC} of the
 * handlers' locations, {@code Owner.method:line} joined by commas, and a call to the hook, which
 * reports each location once per run when the method runs on a run's thread. One value and a static
 * call before the first instruction: no branch, no new frame, no member, so only {@code maxStack}
 * grows ({@code COMPUTE_MAXS}) and the class stays safe to retransform. A method with no such
 * handler is passed through after its handlers are read, so only a method that declares one is
 * buffered.
 */
final class InterruptHandlerWeaver implements AsmVisitorWrapper {

    /** The library class the inserted call lands in. */
    static final String HOOKS = "se.deversity.asynctest.AgentThreadHooks";

    /** Takes the comma-joined locations of the method's swallowing handlers. */
    static final String HOOK = "interruptSwallowingMethodEntered";

    private static final String HOOK_DESCRIPTOR = "(Ljava/lang/String;)V";

    private static final String INTERRUPTED_EXCEPTION = "java/lang/InterruptedException";

    /** How many instructions one handler's walk may visit before it gives up, unreported. */
    private static final int MAX_WALK = 4_096;

    private final String hookOwner;
    private final Module hookModule;

    private InterruptHandlerWeaver(Class<?> hooks) {
        this.hookOwner = hooks.getName().replace('.', '/');
        this.hookModule = hooks.getModule();
    }

    /**
     * {@return the visitor, calling the hook on {@code hooks}}
     *
     * @param hooks the class holding the hook, resolved in the weaving class loader; one without the
     *              hook, or declaring it with another shape, is a version skew between agent and
     *              library, refused here rather than woven into a call that would fail in user code
     */
    static InterruptHandlerWeaver of(Class<?> hooks) {
        try {
            if (hooks.getMethod(HOOK, String.class).getReturnType() != void.class) {
                throw new IllegalStateException(hooks.getName() + "." + HOOK + " must return void");
            }
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no hook " + hooks.getName() + "." + HOOK
                    + "(String); agent and library versions disagree", e);
        }
        return new InterruptHandlerWeaver(hooks);
    }

    /**
     * {@return whether a class in {@code module} can call the hook}: a class in a named module that
     * cannot read the library's module would throw {@code IllegalAccessError} from the inserted
     * call, so it is left alone, as {@link ConstructionWeaver} leaves it.
     */
    boolean reachableFrom(@org.jspecify.annotations.Nullable Module module) {
        return module == null || module.canRead(hookModule);
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
        String binaryName = instrumentedType.getName();
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                if (delegate == null || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return delegate;
                }
                return new HandlerReadingVisitor(delegate, hookOwner,
                        binaryName + "." + name, "run".equals(name) && "()V".equals(descriptor));
            }
        };
    }

    /**
     * Buffers a method that declares an {@code InterruptedException} handler, judges its handlers
     * at the end, and replays it with the hook call in front when one swallows. Until the first
     * instruction it buffers everything, since a class reader visits every handler before any
     * instruction; a method that declared none by then is flushed and passed through.
     */
    static final class HandlerReadingVisitor extends MethodVisitor {

        private final MethodVisitor target;
        private final String hookOwner;
        private final String methodName;
        private final boolean threadRun;

        /** Replays of every visit since visitCode, while buffering. */
        private final List<Consumer<MethodVisitor>> actions = new ArrayList<>();

        /** How many of {@link #actions} precede the first instruction: where the hook goes. */
        private int prologue = -1;

        private boolean buffering = true;
        private boolean sawInterruptHandler;

        /** The instruction model the walk reads. */
        private final List<Insn> insns = new ArrayList<>();
        private final Map<Label, Integer> labelAt = new HashMap<>();
        private final Map<Label, Integer> lineOf = new HashMap<>();
        private final List<Block> blocks = new ArrayList<>();

        HandlerReadingVisitor(MethodVisitor target, String hookOwner, String methodName,
                              boolean threadRun) {
            super(Opcodes.ASM9);
            this.target = target;
            this.hookOwner = hookOwner;
            this.methodName = methodName;
            this.threadRun = threadRun;
        }

        /** One instruction, as much of it as the walk needs. */
        private record Insn(int opcode, Label[] targets, String owner, String name) {
            static final Label[] NONE = new Label[0];
        }

        /** One protected range and its handler. */
        private record Block(Label start, Label end, Label handler, String type) { }

        private void action(Consumer<MethodVisitor> action) {
            if (buffering) {
                actions.add(action);
            } else {
                action.accept(target);
            }
        }

        /** Marks the first instruction, and stops buffering a method that declared no handler. */
        private void instruction(int opcode, Label[] targets, String owner, String name) {
            if (prologue < 0) {
                prologue = actions.size();
                if (!sawInterruptHandler) {
                    flush();
                }
            }
            if (buffering) {
                insns.add(new Insn(opcode, targets, owner, name));
            }
        }

        private void flush() {
            for (Consumer<MethodVisitor> action : actions) {
                action.accept(target);
            }
            actions.clear();
            buffering = false;
        }

        // --- Visits outside the code: straight through ---------------------------------------

        @Override
        public void visitParameter(String name, int access) {
            target.visitParameter(name, access);
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return target.visitAnnotationDefault();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            return target.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
            target.visitAnnotableParameterCount(parameterCount, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
                                                          boolean visible) {
            return target.visitParameterAnnotation(parameter, descriptor, visible);
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            target.visitAttribute(attribute);
        }

        @Override
        public void visitCode() {
            target.visitCode();
        }

        // --- The code: buffered while buffering ----------------------------------------------

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (buffering && INTERRUPTED_EXCEPTION.equals(type)) {
                sawInterruptHandler = true;
            }
            if (buffering) {
                blocks.add(new Block(start, end, handler, type == null ? "" : type));
            }
            action(mv -> mv.visitTryCatchBlock(start, end, handler, type));
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                         String descriptor, boolean visible) {
            if (buffering) {
                CollectionAccessWeaver.BufferedAnnotationVisitor nested =
                        new CollectionAccessWeaver.BufferedAnnotationVisitor();
                actions.add(mv -> nested.replay(
                        mv.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible)));
                return nested;
            }
            return target.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            if (!buffering) {
                target.visitFrame(type, numLocal, local, numStack, stack);
                return;
            }
            // A class reader reuses these arrays for its next frame, so a buffered frame keeps
            // copies; without them every frame replays as the last one read, which is a
            // VerifyError at the first exception handler.
            Object[] locals = local == null ? null : local.clone();
            Object[] stackItems = stack == null ? null : stack.clone();
            actions.add(mv -> mv.visitFrame(type, numLocal, locals, numStack, stackItems));
        }

        @Override
        public void visitLabel(Label label) {
            if (buffering) {
                labelAt.put(label, insns.size());
            }
            action(mv -> mv.visitLabel(label));
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            if (buffering) {
                lineOf.put(start, line);
            }
            action(mv -> mv.visitLineNumber(line, start));
        }

        @Override
        public void visitInsn(int opcode) {
            instruction(opcode, Insn.NONE, "", "");
            action(mv -> mv.visitInsn(opcode));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            instruction(opcode, Insn.NONE, "", "");
            action(mv -> mv.visitIntInsn(opcode, operand));
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            instruction(opcode, Insn.NONE, "", "");
            action(mv -> mv.visitVarInsn(opcode, varIndex));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            instruction(opcode, Insn.NONE, "", "");
            action(mv -> mv.visitTypeInsn(opcode, type));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            instruction(opcode, Insn.NONE, "", "");
            action(mv -> mv.visitFieldInsn(opcode, owner, name, descriptor));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            instruction(opcode, Insn.NONE, owner, name);
            action(mv -> mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
            instruction(Opcodes.INVOKEDYNAMIC, Insn.NONE, "", "");
            Object[] arguments = bootstrapMethodArguments.clone();
            action(mv -> mv.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle,
                    arguments));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            instruction(opcode, new Label[] {label}, "", "");
            action(mv -> mv.visitJumpInsn(opcode, label));
        }

        @Override
        public void visitLdcInsn(Object value) {
            instruction(Opcodes.LDC, Insn.NONE, "", "");
            action(mv -> mv.visitLdcInsn(value));
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            instruction(Opcodes.IINC, Insn.NONE, "", "");
            action(mv -> mv.visitIincInsn(varIndex, increment));
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            Label[] targets = new Label[labels.length + 1];
            targets[0] = dflt;
            System.arraycopy(labels, 0, targets, 1, labels.length);
            instruction(Opcodes.TABLESWITCH, targets, "", "");
            Label[] cases = labels.clone();
            action(mv -> mv.visitTableSwitchInsn(min, max, dflt, cases));
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            Label[] targets = new Label[labels.length + 1];
            targets[0] = dflt;
            System.arraycopy(labels, 0, targets, 1, labels.length);
            instruction(Opcodes.LOOKUPSWITCH, targets, "", "");
            int[] caseKeys = keys.clone();
            Label[] cases = labels.clone();
            action(mv -> mv.visitLookupSwitchInsn(dflt, caseKeys, cases));
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            instruction(Opcodes.MULTIANEWARRAY, Insn.NONE, "", "");
            action(mv -> mv.visitMultiANewArrayInsn(descriptor, numDimensions));
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            if (buffering) {
                CollectionAccessWeaver.BufferedAnnotationVisitor nested =
                        new CollectionAccessWeaver.BufferedAnnotationVisitor();
                actions.add(mv -> nested.replay(
                        mv.visitInsnAnnotation(typeRef, typePath, descriptor, visible)));
                return nested;
            }
            return target.visitInsnAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {
            action(mv -> mv.visitLocalVariable(name, descriptor, signature, start, end, index));
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                              Label[] start, Label[] end, int[] index,
                                                              String descriptor, boolean visible) {
            if (buffering) {
                CollectionAccessWeaver.BufferedAnnotationVisitor nested =
                        new CollectionAccessWeaver.BufferedAnnotationVisitor();
                Label[] starts = start.clone();
                Label[] ends = end.clone();
                int[] indexes = index.clone();
                actions.add(mv -> nested.replay(mv.visitLocalVariableAnnotation(typeRef, typePath,
                        starts, ends, indexes, descriptor, visible)));
                return nested;
            }
            return target.visitLocalVariableAnnotation(typeRef, typePath, start, end, index,
                    descriptor, visible);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            if (buffering) {
                String swallowing = swallowingHandlers();
                for (int i = 0; i < actions.size(); i++) {
                    if (i == prologue && swallowing != null) {
                        target.visitLdcInsn(swallowing);
                        target.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, HOOK,
                                HOOK_DESCRIPTOR, false);
                    }
                    actions.get(i).accept(target);
                }
                actions.clear();
                buffering = false;
            }
            target.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitEnd() {
            if (buffering) {
                // A method visited without visitMaxs is not code this visitor could judge.
                flush();
            }
            target.visitEnd();
        }

        // --- The walk -----------------------------------------------------------------------

        /** {@return the swallowing handlers' locations, comma-joined, or {@code null} for none} */
        @org.jspecify.annotations.Nullable String swallowingHandlers() {
            if (!sawInterruptHandler || insns.isEmpty()) {
                return null;
            }
            StringJoiner found = new StringJoiner(",");
            BitSet judged = new BitSet();
            for (Block block : blocks) {
                if (!INTERRUPTED_EXCEPTION.equals(block.type())) {
                    continue;
                }
                Integer handler = labelAt.get(block.handler());
                if (handler == null || judged.get(handler)) {
                    continue;
                }
                judged.set(handler);
                if (swallows(handler)) {
                    found.add(methodName + ":" + lineAt(handler));
                }
            }
            return found.length() == 0 ? null : found.toString();
        }

        /**
         * {@return the source line of a handler starting at {@code index}, or 0 when unknown}: the
         * first line entry at or after it, since javac gives the handler's own store no line and
         * the catch block's first statement one; the last entry before it when there is none.
         */
        private int lineAt(int index) {
            int after = Integer.MAX_VALUE;
            int lineAfter = 0;
            int before = -1;
            int lineBefore = 0;
            for (Map.Entry<Label, Integer> entry : lineOf.entrySet()) {
                Integer at = labelAt.get(entry.getKey());
                if (at == null) {
                    continue;
                }
                if (at >= index && at < after) {
                    after = at;
                    lineAfter = entry.getValue();
                } else if (at < index && at > before) {
                    before = at;
                    lineBefore = entry.getValue();
                }
            }
            return after != Integer.MAX_VALUE ? lineAfter : lineBefore;
        }

        /**
         * {@return whether the method can return from {@code handler} with the interrupt lost}
         *
         * <p>The walk follows every path from the handler, through the method's ordinary code as
         * well, since a handler may only note the interrupt and leave the restoring to a
         * {@code finally}: Guava's {@code Uninterruptibles} sets a flag, goes on waiting, and calls
         * {@code interrupt()} once the wait is over. A path ends at an {@code ATHROW}, which hands
         * the exception or a wrapper of it on, and at a call that restores the flag, which
         * anywhere on any path from the handler is taken to answer for the whole handler: the
         * check is not path-sensitive, and it errs toward silence. A handler swallows when no
         * such call is reachable and some path reaches a return, the caller then never learning
         * of the interrupt. A {@code run()} that returns is a thread's own policy, and a walk that
         * runs past its bound reports nothing.
         */
        private boolean swallows(int handler) {
            BitSet seen = new BitSet();
            Deque<Integer> work = new ArrayDeque<>();
            work.push(handler);
            int steps = 0;
            boolean returns = false;
            while (!work.isEmpty()) {
                steps++;
                if (steps > MAX_WALK) {
                    return false;
                }
                int at = work.pop();
                if (at < 0 || at >= insns.size() || seen.get(at)) {
                    continue;
                }
                seen.set(at);
                Insn insn = insns.get(at);
                int opcode = insn.opcode();
                if (restoresTheFlag(insn)) {
                    return false;
                }
                if (opcode == Opcodes.ATHROW) {
                    continue;
                }
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    returns |= !threadRun;
                    continue;
                }
                for (int next : successors(at)) {
                    work.push(next);
                }
            }
            return returns;
        }

        /**
         * {@return whether {@code insn} is a call that sets the interrupt flag again}: a method named
         * {@code interrupt}, or one whose name mentions an interrupt and does not just read the flag,
         * such as a project's own {@code restoreInterrupt} helper. A call named for interrupts that
         * does something else is taken at its word, which errs toward not reporting.
         */
        private static boolean restoresTheFlag(Insn insn) {
            if (insn.opcode() < Opcodes.INVOKEVIRTUAL || insn.opcode() > Opcodes.INVOKEINTERFACE) {
                return false;
            }
            String name = insn.name();
            return "interrupt".equals(name)
                    || name.contains("nterrupt") // interrupt or Interrupt, with no case mapping
                    && !"interrupted".equals(name) && !"isInterrupted".equals(name);
        }

        /** {@return the indexes control can pass to after the instruction at {@code at}} */
        private List<Integer> successors(int at) {
            List<Integer> next = new ArrayList<>(2);
            Insn insn = insns.get(at);
            int opcode = insn.opcode();
            for (Label label : insn.targets()) {
                Integer index = labelAt.get(label);
                if (index != null) {
                    next.add(index);
                }
            }
            boolean ends = opcode == Opcodes.GOTO || opcode == Opcodes.ATHROW
                    || opcode == Opcodes.TABLESWITCH || opcode == Opcodes.LOOKUPSWITCH
                    || (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN);
            if (!ends) {
                next.add(at + 1);
            }
            for (Block block : blocks) {
                if (INTERRUPTED_EXCEPTION.equals(block.type())) {
                    continue;
                }
                Integer start = labelAt.get(block.start());
                Integer end = labelAt.get(block.end());
                Integer handler = labelAt.get(block.handler());
                if (start != null && end != null && handler != null && at >= start && at < end) {
                    next.add(handler);
                }
            }
            return next;
        }
    }
}
