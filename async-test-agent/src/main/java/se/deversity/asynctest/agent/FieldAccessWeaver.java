package se.deversity.asynctest.agent;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import org.jspecify.annotations.Nullable;

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

    /** Tag meaning "this write did not put a knowable constant in the field". */
    static final int NOT_A_CONSTANT_WRITE = Integer.MIN_VALUE;

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
        return visitor(true);
    }

    /**
     * {@return a visitor wrapper that always weaves monitor instructions, and weaves field
     * instructions only when asked}
     *
     * <p>The two are separable because they answer to different options and to different
     * questions. Field instructions are what {@code fields=true} buys. Monitor instructions are
     * what makes any lock-aware detector able to tell guarded code from racing code, so every mode
     * that records an access needs them: {@code collections=true} without them would report a
     * {@code HashMap} guarded by a {@code synchronized} block as unguarded, and reporting correct
     * code is the failure mode a stress-test library can least afford.
     *
     * @param weaveFieldInstructions whether {@code GETFIELD} and {@code PUTFIELD} are observed too
     */
    static AsmVisitorWrapper visitor(boolean weaveFieldInstructions) {
        return new ClassLevelWrapper(weaveFieldInstructions);
    }

    /**
     * Visits every method of a type, including its type initializer.
     *
     * <p>{@code AsmVisitorWrapper.ForDeclaredMethods} does not offer the type initializer, and that
     * is the one place a class binds a {@code VarHandle} or an atomic updater to a field. Missing
     * it means missing the only static evidence that a field belongs to a lock-free protocol, which
     * is the difference between staying quiet about such a field and reporting every access to it.
     *
     * <p>The initializer is visited and never woven: class initialisation already runs under the
     * JVM's own lock, so recording accesses there buys nothing.
     */
    private record ClassLevelWrapper(boolean weaveFieldInstructions) implements AsmVisitorWrapper {

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
                    MethodVisitor delegate =
                            super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean typeInitializer = "<clinit>".equals(name);
                    return new FieldAccessMethodVisitor(delegate,
                            weaveFieldInstructions && !typeInitializer, typePool,
                            "<init>".equals(name));
                }
            };
        }
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

    /**
     * Emits {@code TelemetryRegistry.recordAccess(Thread.currentThread().threadId(), id, isWrite)}
     * immediately before each field instruction, leaving the original instruction in place.
     */
    private static final class FieldAccessMethodVisitor extends MethodVisitor {

        private final boolean weaveFieldInstructions;

        private final TypePool typePool;

        /**
         * Fields this method has already read, and the constant sitting on the stack, if any.
         *
         * <p>Together they answer the only question worth asking about a constant write: could it
         * be the "act" half of a check-then-act? A method that writes {@code true} without ever
         * having read the field cannot be checking it, so no interleaving of such writes can
         * change what any thread decides. A method that read the field first might well be
         * {@code if (!initialized) initialized = true}, which is a real bug, so seeing the read
         * disqualifies the write. The visitor is per method and instructions arrive in order,
         * which is what makes the read-before-write question answerable while streaming.
         */
        private final java.util.Set<String> fieldsReadInThisMethod = new java.util.HashSet<>();

        /** The int-valued constant the previous instruction pushed, or {@code null}. */
        private @Nullable Integer pendingConstant;

        /** Non-volatile fields this method has written, in order, awaiting a volatile write. */
        private final java.util.List<String> plainWritesInThisMethod = new java.util.ArrayList<>();

        /** Whether this method has already read a volatile field of the same owner. */
        private final java.util.Set<String> ownersWithVolatileReadInThisMethod =
                new java.util.HashSet<>();

        /** Recent class and string constants, for reading atomic-updater bindings off the stack. */
        private final java.util.Deque<Object> recentConstants = new java.util.ArrayDeque<>();

        /**
         * Whether {@code this} is still uninitialised, which is true inside a constructor until the
         * super constructor has run.
         *
         * <p>Before that call the verifier types {@code this} as {@code uninitializedThis} and
         * refuses to pass it to any method, so lifting its identity there produces a class that
         * will not load. Guava's {@code Joiner$3} constructor is one of many that writes a field in
         * that window. Those writes record identity 0, the same "not known" every non-agent caller
         * uses, and everything after the super call records normally.
         */
        private boolean thisIsUninitialised;

        /**
         * Whether this method is a constructor, in which case its writes to {@code this} are
         * construction, not mutation.
         *
         * <p>A constructor's writes happen before the object is published, so they cannot be the
         * "act" of a check-then-act and no other thread can have observed the field's earlier
         * value. Recording them makes every immutable object look mutated: {@code seed} on a hash
         * function and {@code elements} on an ImmutableSet are final fields written once and then
         * read by every thread, which reads as one writer and six readers on shared state.
         *
         * <p>What this gives up is the unsafe-publication bug, where a reference escapes mid
         * construction. That is a different defect from the one this detector claims to find, and
         * paying for it with a finding on every immutable object is not a trade worth making.
         */
        private final boolean insideConstructor;

        FieldAccessMethodVisitor(MethodVisitor delegate, boolean weaveFieldInstructions,
                                 TypePool typePool, boolean constructor) {
            super(Opcodes.ASM9, delegate);
            this.weaveFieldInstructions = weaveFieldInstructions;
            this.typePool = typePool;
            this.thisIsUninitialised = constructor;
            this.insideConstructor = constructor;
        }

        /**
         * {@return whether {@code owner.name} is declared {@code volatile}}
         *
         * <p>Resolved here, at weave time, and emitted as a constant, so the hot path pays
         * nothing. A field that cannot be resolved reads as non-volatile, which keeps the previous
         * behaviour: the flag can only ever suppress a finding, so failing to find it must leave
         * the finding standing.
         */
        private boolean isVolatile(String owner, String name) {
            try {
                TypeDescription type = typePool.describe(owner.replace('/', '.')).resolve();
                for (TypeDefinition current = type; current != null; current = current.getSuperClass()) {
                    for (FieldDescription.InDefinedShape field
                            : current.asErasure().getDeclaredFields()) {
                        if (field.getName().equals(name)) {
                            return field.isVolatile();
                        }
                    }
                }
            } catch (RuntimeException e) { // NOPMD - an unresolvable type is not a weaving failure
                return false;
            }
            return false;
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
            noteConstant(opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5
                    ? opcode - Opcodes.ICONST_0 : null);
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                String hook = opcode == Opcodes.MONITORENTER ? "monitorEntered" : "monitorExited";
                super.visitInsn(Opcodes.DUP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, hook,
                        "(Ljava/lang/Object;)V", false);
            }
            super.visitInsn(opcode);
        }
        @Override
        public void visitIntInsn(int opcode, int operand) {
            noteConstant(opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH ? operand : null);
            super.visitIntInsn(opcode, operand);
        }

        /**
         * Notices a field being bound to a {@code VarHandle} or an atomic field updater.
         *
         * <p>Such a field is mutated by a lock-free protocol whose correctness comes from
         * compare-and-swap and from the algorithm's own reasoning, not from any lock. Guava's
         * waiter list is the case that made this necessary and says so in its own source: "non-
         * volatile write to the next field. Should be made visible by a subsequent CAS". An Eraser
         * lockset has nothing to intersect there and no basis for a verdict, so the honest answer
         * is to say nothing about that field rather than to report every access to it.
         *
         * <p>The binding is read from the constants the call site pushes: the field name is the
         * string, and the owner is the first class literal, which holds for
         * {@code findVarHandle(Owner.class, "f", Type.class)},
         * {@code newUpdater(Owner.class, Type.class, "f")} and
         * {@code Owner.class.getDeclaredField("f")} alike.
         */
        private void noteAtomicBinding(String name) {
            if (!"findVarHandle".equals(name) && !"newUpdater".equals(name)
                    && !"getDeclaredField".equals(name)) {
                return;
            }
            String fieldName = null;
            String ownerType = null;
            for (Object constant : recentConstants) {
                if (constant instanceof String text && fieldName == null) {
                    fieldName = text;
                } else if (constant instanceof Type type
                        && type.getSort() == Type.OBJECT) {
                    ownerType = type.getInternalName();
                }
            }
            if (fieldName != null && ownerType != null) {
                String field = identifier(ownerType, fieldName);
                // Both, because neither alone is enough. The emitted call resolves the registry
                // from the woven class's own loader, which is the copy the detectors read, but it
                // only fires if that class initialises after the agent attached. The weave-time
                // call always happens but lands in whichever copy the agent's loader sees, and
                // under a test runner with an isolated classloader that is a different one.
                super.visitLdcInsn(field);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "atomicallyManaged",
                        "(Ljava/lang/String;)V", false);
                AtomicFieldRegistry.record(field);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            recentConstants.addFirst(value);
            while (recentConstants.size() > 4) {
                recentConstants.removeLast();
            }
            // Only int-shaped constants are trusted. A String or a long could collide or tear the
            // reasoning, and the flag may only ever suppress a finding, so anything unclear must
            // read as "not a constant".
            noteConstant(value instanceof Integer i ? i : null);
            super.visitLdcInsn(value);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            noteConstant(null);
            noteAtomicBinding(name);
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            recentConstants.clear();
            if (thisIsUninitialised && opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                thisIsUninitialised = false;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            noteConstant(null);
            super.visitVarInsn(opcode, varIndex);
        }


        /**
         * Leaves {@code System.identityHashCode(receiver)} on top of the stack, with the operands
         * the field instruction needs still beneath it, in their original order.
         *
         * <p>Which instance a field belongs to is the difference between six threads racing on one
         * object and six threads each using their own. Without it a per-call object, a hasher, a
         * matcher, an iterator, aggregates by field name and reads as shared, which is a false
         * positive on code that is not even concurrent.
         *
         * <p>Done with stack manipulation rather than a scratch local on purpose: a local would
         * grow {@code maxLocals} and put a write and a read of an undeclared slot into a method
         * whose stack map frames the weaver deliberately does not recompute. Every sequence here is
         * branch-free and returns the stack to the exact shape the field instruction expects, so
         * only {@code maxStack} moves, which is what {@code COMPUTE_MAXS} is for.
         */
        private void liftReceiverIdentity(boolean isWrite, String descriptor) {
            if (!isWrite) {
                super.visitInsn(Opcodes.DUP);                 // obj -> obj, obj
            } else if (isCategoryTwo(descriptor)) {
                super.visitInsn(Opcodes.DUP2_X1);             // obj, v1, v2 -> v1, v2, obj, v1, v2
                super.visitInsn(Opcodes.POP2);                //             -> v1, v2, obj
                super.visitInsn(Opcodes.DUP);                 //             -> v1, v2, obj, obj
            } else {
                super.visitInsn(Opcodes.DUP2);                // obj, v -> obj, v, obj, v
                super.visitInsn(Opcodes.POP);                 //        -> obj, v, obj
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode",
                    "(Ljava/lang/Object;)I", false);
        }

        /**
         * Puts the receiver back underneath a two-slot value after the recording call.
         *
         * <p>Only the category-2 write needs it: lifting the receiver out from under a {@code long}
         * or {@code double} is the one case that cannot be undone by duplication alone.
         */
        private void restoreReceiverBelowValue(String descriptor) {
            if (isCategoryTwo(descriptor)) {
                super.visitInsn(Opcodes.DUP_X2);              // v1, v2, obj -> obj, v1, v2, obj
                super.visitInsn(Opcodes.POP);                 //             -> obj, v1, v2
            }
        }

        private static boolean isCategoryTwo(String descriptor) {
            return "J".equals(descriptor) || "D".equals(descriptor);
        }


        /**
         * Tracks the "write it, then publish it with a volatile write" idiom inside one method.
         *
         * <p>Guava's memoizing supplier is the canonical case: it assigns {@code value} under its
         * own monitor and then assigns the <em>volatile</em> {@code delegate}, and every reader
         * reads {@code delegate} before {@code value}. The plain field is safely published by that
         * ordering, and reporting it means telling the author of correct code to fix it.
         *
         * <p>Both halves are visible here, in program order, which is what makes the rule checkable
         * rather than assumed: a volatile write publishes the plain writes this method already made
         * to the same owner, and a plain read only counts as ordered when this method has already
         * read a volatile field of that owner. A class that writes the plain field and never
         * publishes it, or reads it without reading the volatile guard first, is untouched by this
         * and keeps its finding.
         */
        private void notePublication(String owner, String name, boolean isWrite, boolean isVolatile) {
            String identifier = identifier(owner, name);
            if (isVolatile) {
                if (isWrite) {
                    for (String published : plainWritesInThisMethod) {
                        super.visitLdcInsn(published);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "publishedByVolatile",
                                "(Ljava/lang/String;)V", false);
                    }
                    plainWritesInThisMethod.clear();
                } else {
                    ownersWithVolatileReadInThisMethod.add(owner);
                }
            } else if (isWrite) {
                plainWritesInThisMethod.add(identifier);
            }
        }

        private void noteConstant(@Nullable Integer constant) {
            pendingConstant = constant;
        }

        /**
         * {@return the tag describing what this write puts in the field}
         *
         * <p>{@link #NOT_A_CONSTANT_WRITE} unless the value came from a constant instruction and
         * this method has not read the field, in which case the constant itself is the tag.
         */
        private int constantTag(boolean isWrite, String identifier) {
            if (!isWrite || pendingConstant == null || fieldsReadInThisMethod.contains(identifier)) {
                return NOT_A_CONSTANT_WRITE;
            }
            return pendingConstant;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            String identifier = identifier(owner, name);
            boolean write = opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC;
            int tag = constantTag(write, identifier);
            if (!write) {
                fieldsReadInThisMethod.add(identifier);
            }
            boolean isStaticAccess = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
            // An instance field touched before the super constructor has run belongs to an object
            // that cannot have escaped yet: no other thread can hold a reference to something still
            // being constructed, so there is nothing to observe. Recording it anyway would be worse
            // than useless, because the verifier forbids passing uninitializedThis to
            // identityHashCode, so the access would land in the identity-0 bucket and merge with
            // every other instance. javac writes captured fields there in every inner class.
            boolean constructionWrite = write && !isStaticAccess && insideConstructor;
            if (weaveFieldInstructions && shouldWeave(owner) && !constructionWrite
                    && (isStaticAccess || !thisIsUninitialised)) {
                boolean isWrite = write;
                boolean isStatic = isStaticAccess;
                if (isStatic) {
                    // No receiver: static state is shared by definition, and 0 is the identity
                    // every static access shares, which is exactly the old behaviour.
                    super.visitInsn(Opcodes.ICONST_0);
                } else {
                    liftReceiverIdentity(isWrite, descriptor);
                }
                super.visitMethodInsn(Opcodes.INVOKESTATIC, THREAD, "currentThread",
                        "()Ljava/lang/Thread;", false);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD, "threadId", "()J", false);
                super.visitLdcInsn(identifier);
                super.visitInsn(isWrite ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitInsn(isVolatile(owner, name) ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitLdcInsn(tag);
                super.visitInsn(ownersWithVolatileReadInThisMethod.contains(owner)
                        ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "recordAccess",
                        "(IJLjava/lang/String;ZZIZ)V", false);
                if (isWrite && !isStatic) {
                    restoreReceiverBelowValue(descriptor);
                }
                notePublication(owner, name, isWrite, isVolatile(owner, name));
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }
    }
}
