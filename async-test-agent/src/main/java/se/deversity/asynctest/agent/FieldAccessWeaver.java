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

import java.util.List;
import java.util.Map;

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
 * <p>The call-site rewrites keep the same rules. A substituted atomic, updater or {@code VarHandle}
 * call becomes one {@code INVOKESTATIC} that consumes exactly the operands the call consumed and
 * returns its result, plus one {@code POP} where a {@code VarHandle} call site declared no result,
 * and one {@code CHECKCAST} to the declared type after a {@code VarHandle} reference
 * {@code getAndSet} (#664). A JCTools {@code offer} is preceded by {@code DUP2, SWAP} and a static
 * call consuming the two copies; a {@code poll} is bracketed by a {@code DUP} of the queue before it
 * and {@code DUP_X1, SWAP} and a static call after it, which leave exactly the polled element. None
 * of them branch.
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

    /** Internal names of the call-site owners whose spinlock calls are substituted (#554, #558). */
    private static final String VAR_HANDLE = "java/lang/invoke/VarHandle";
    private static final String INT_UPDATER = "java/util/concurrent/atomic/AtomicIntegerFieldUpdater";
    private static final String ATOMIC_BOOLEAN = "java/util/concurrent/atomic/AtomicBoolean";
    private static final String ATOMIC_INTEGER = "java/util/concurrent/atomic/AtomicInteger";

    /** Descriptors of the int functional forms, which the update and accumulate calls take. */
    private static final String UNARY = "Ljava/util/function/IntUnaryOperator;";
    private static final String BINARY = "Ljava/util/function/IntBinaryOperator;";

    /**
     * The {@code AtomicIntegerFieldUpdater} calls substituted, by name, with the exact descriptor
     * each is matched on; the hook is the name plus {@code IntUpdater} (#558, #658, #667).
     */
    private static final Map<String, String> INT_UPDATER_FORMS = Map.ofEntries(
            Map.entry("compareAndSet", "(Ljava/lang/Object;II)Z"),
            Map.entry("weakCompareAndSet", "(Ljava/lang/Object;II)Z"),
            Map.entry("set", "(Ljava/lang/Object;I)V"),
            Map.entry("lazySet", "(Ljava/lang/Object;I)V"),
            Map.entry("getAndSet", "(Ljava/lang/Object;I)I"),
            Map.entry("getAndAdd", "(Ljava/lang/Object;I)I"),
            Map.entry("addAndGet", "(Ljava/lang/Object;I)I"),
            Map.entry("getAndDecrement", "(Ljava/lang/Object;)I"),
            Map.entry("decrementAndGet", "(Ljava/lang/Object;)I"),
            Map.entry("getAndUpdate", "(Ljava/lang/Object;" + UNARY + ")I"),
            Map.entry("updateAndGet", "(Ljava/lang/Object;" + UNARY + ")I"),
            Map.entry("getAndAccumulate", "(Ljava/lang/Object;I" + BINARY + ")I"),
            Map.entry("accumulateAndGet", "(Ljava/lang/Object;I" + BINARY + ")I"));

    /** The {@code AtomicBoolean} calls substituted; the hook is the name plus {@code AtomicBoolean}. */
    private static final Map<String, String> ATOMIC_BOOLEAN_FORMS = Map.ofEntries(
            Map.entry("compareAndSet", "(ZZ)Z"),
            Map.entry("getAndSet", "(Z)Z"),
            Map.entry("set", "(Z)V"),
            Map.entry("lazySet", "(Z)V"),
            Map.entry("setPlain", "(Z)V"),
            Map.entry("setOpaque", "(Z)V"),
            Map.entry("setRelease", "(Z)V"),
            Map.entry("compareAndExchange", "(ZZ)Z"),
            Map.entry("compareAndExchangeAcquire", "(ZZ)Z"),
            Map.entry("compareAndExchangeRelease", "(ZZ)Z"),
            Map.entry("weakCompareAndSet", "(ZZ)Z"),
            Map.entry("weakCompareAndSetPlain", "(ZZ)Z"),
            Map.entry("weakCompareAndSetVolatile", "(ZZ)Z"),
            Map.entry("weakCompareAndSetAcquire", "(ZZ)Z"),
            Map.entry("weakCompareAndSetRelease", "(ZZ)Z"));

    /** The {@code AtomicInteger} calls substituted; the hook is the name plus {@code AtomicInteger}. */
    private static final Map<String, String> ATOMIC_INTEGER_FORMS = Map.ofEntries(
            Map.entry("compareAndSet", "(II)Z"),
            Map.entry("set", "(I)V"),
            Map.entry("lazySet", "(I)V"),
            Map.entry("setPlain", "(I)V"),
            Map.entry("setOpaque", "(I)V"),
            Map.entry("setRelease", "(I)V"),
            Map.entry("getAndSet", "(I)I"),
            Map.entry("getAndAdd", "(I)I"),
            Map.entry("addAndGet", "(I)I"),
            Map.entry("getAndDecrement", "()I"),
            Map.entry("decrementAndGet", "()I"),
            Map.entry("compareAndExchange", "(II)I"),
            Map.entry("compareAndExchangeAcquire", "(II)I"),
            Map.entry("compareAndExchangeRelease", "(II)I"),
            Map.entry("weakCompareAndSet", "(II)Z"),
            Map.entry("weakCompareAndSetPlain", "(II)Z"),
            Map.entry("weakCompareAndSetVolatile", "(II)Z"),
            Map.entry("weakCompareAndSetAcquire", "(II)Z"),
            Map.entry("weakCompareAndSetRelease", "(II)Z"),
            Map.entry("getAndUpdate", "(" + UNARY + ")I"),
            Map.entry("updateAndGet", "(" + UNARY + ")I"),
            Map.entry("getAndAccumulate", "(I" + BINARY + ")I"),
            Map.entry("accumulateAndGet", "(I" + BINARY + ")I"));

    /** Internal names of the reference slots whose offers and takes are substituted (#664). */
    private static final String ATOMIC_REFERENCE = "java/util/concurrent/atomic/AtomicReference";
    private static final String REFERENCE_UPDATER =
            "java/util/concurrent/atomic/AtomicReferenceFieldUpdater";
    private static final String REFERENCE_ARRAY = "java/util/concurrent/atomic/AtomicReferenceArray";

    /** The erased {@code Object} descriptor element the reference-slot tables are written in. */
    private static final String OBJECT = "Ljava/lang/Object;";

    /** The {@code AtomicReference} calls substituted; the hook is the name plus {@code AtomicReference}. */
    private static final Map<String, String> ATOMIC_REFERENCE_FORMS = Map.of(
            "set", "(" + OBJECT + ")V",
            "lazySet", "(" + OBJECT + ")V",
            "setRelease", "(" + OBJECT + ")V",
            "compareAndSet", "(" + OBJECT + OBJECT + ")Z",
            "getAndSet", "(" + OBJECT + ")" + OBJECT);

    /** The {@code AtomicReferenceFieldUpdater} calls substituted; the hook is the name plus {@code ReferenceUpdater}. */
    private static final Map<String, String> REFERENCE_UPDATER_FORMS = Map.of(
            "set", "(" + OBJECT + OBJECT + ")V",
            "lazySet", "(" + OBJECT + OBJECT + ")V",
            "compareAndSet", "(" + OBJECT + OBJECT + OBJECT + ")Z",
            "getAndSet", "(" + OBJECT + OBJECT + ")" + OBJECT);

    /** The {@code AtomicReferenceArray} calls substituted; the hook is the name plus {@code ReferenceArray}. */
    private static final Map<String, String> REFERENCE_ARRAY_FORMS = Map.of(
            "set", "(I" + OBJECT + ")V",
            "lazySet", "(I" + OBJECT + ")V",
            "setRelease", "(I" + OBJECT + ")V",
            "compareAndSet", "(I" + OBJECT + OBJECT + ")Z",
            "getAndSet", "(I" + OBJECT + ")" + OBJECT);

    /**
     * Owner prefixes (in internal, slash-separated form) whose fields are never woven: the
     * slash view of {@link AsyncTestAgent#IGNORED_PREFIXES}, derived so the two cannot drift
     * (#699). Deriving at class init also keeps the Shade-safe runtime assembly that list uses.
     */
    private static final String[] IGNORED_OWNERS = slashForm(AsyncTestAgent.IGNORED_PREFIXES);

    /** A plain loop, not a stream: a lambda here would spin a class during weaver class init. */
    private static String[] slashForm(List<String> dottedPrefixes) {
        String[] owners = new String[dottedPrefixes.size()];
        for (int i = 0; i < owners.length; i++) {
            owners[i] = dottedPrefixes.get(i).replace('.', '/');
        }
        return owners;
    }

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

                /** The class being woven, and whether its version supports {@code ldc} of a class. */
                private String internalName = instrumentedType.getInternalName();
                private boolean classConstantsUsable = true;

                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    internalName = name;
                    // ldc of a class constant needs a 49.0 (Java 5) class file. Older files get
                    // null where a class object would have gone, which loses only the probes that
                    // need one; everything else is unchanged.
                    classConstantsUsable = (version & 0xFFFF) >= Opcodes.V1_5;
                    super.visit(version, access, name, signature, superName, interfaces);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate =
                            super.visitMethod(access, name, descriptor, signature, exceptions);
                    boolean typeInitializer = "<clinit>".equals(name);
                    return new FieldAccessMethodVisitor(delegate,
                            weaveFieldInstructions && !typeInitializer, typePool,
                            "<init>".equals(name),
                            (access & Opcodes.ACC_SYNCHRONIZED) != 0,
                            (access & Opcodes.ACC_STATIC) != 0,
                            internalName, classConstantsUsable);
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

    /** A call substitution: the registry hook and the static descriptor it is invoked with. */
    record Substitution(String hook, String descriptor) { }

    /**
     * {@return the substitution the weaver makes for an {@code invokevirtual} of
     * {@code owner.name(descriptor)}, or {@code null} when the call is left alone}
     *
     * <p>For tests: the hook is emitted by name and descriptor, so a table entry whose hook the
     * registry lacks compiles, weaves, and fails only when the user's code runs the call, with a
     * {@code NoSuchMethodError}.
     */
    static @Nullable Substitution spinLockSubstitution(String owner, String name,
                                                       String descriptor) {
        String hook = FieldAccessMethodVisitor.spinLockHook(Opcodes.INVOKEVIRTUAL, owner, name,
                descriptor);
        return hook == null ? null : new Substitution(hook,
                FieldAccessMethodVisitor.spinLockHookDescriptor(owner, hook, descriptor));
    }

    /**
     * {@return the reference-slot substitution the weaver makes for an {@code invokevirtual} of
     * {@code owner.name(descriptor)}, or {@code null} when the call is left alone (#664)}
     *
     * <p>For tests, for the reason {@link #spinLockSubstitution} gives.
     */
    static @Nullable Substitution referenceSlotSubstitution(String owner, String name,
                                                            String descriptor) {
        String hook = FieldAccessMethodVisitor.referenceSlotHook(Opcodes.INVOKEVIRTUAL, owner, name,
                descriptor);
        return hook == null ? null : new Substitution(hook,
                FieldAccessMethodVisitor.referenceSlotHookDescriptor(owner, hook, descriptor));
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
        private final List<String> plainWritesInThisMethod = new java.util.ArrayList<>();

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

        /** Whether the enclosing method is {@code synchronized}, and how its monitor is named. */
        private final boolean methodSynchronized;
        private final boolean methodStatic;
        private final String classInternalName;
        private final boolean classConstantsUsable;

        FieldAccessMethodVisitor(MethodVisitor delegate, boolean weaveFieldInstructions,
                                 TypePool typePool, boolean constructor,
                                 boolean methodSynchronized, boolean methodStatic,
                                 String classInternalName, boolean classConstantsUsable) {
            super(Opcodes.ASM9, delegate);
            this.weaveFieldInstructions = weaveFieldInstructions;
            this.typePool = typePool;
            this.thisIsUninitialised = constructor;
            this.insideConstructor = constructor;
            this.methodSynchronized = methodSynchronized;
            this.methodStatic = methodStatic;
            this.classInternalName = classInternalName;
            this.classConstantsUsable = classConstantsUsable;
        }

        /**
         * Pushes the monitor an {@code ACC_SYNCHRONIZED} method holds, or {@code null}.
         *
         * <p>A {@code synchronized} method compiles to a flag and no instruction, so the monitor
         * weaving that sees every {@code synchronized} block sees nothing here. Being inside the
         * method is proof the monitor is held, which is why no probe is needed: {@code this} for
         * an instance method, the declaring class for a static one. Constructors cannot carry the
         * flag, so {@code ALOAD 0} here never loads an uninitialised {@code this}.
         */
        private void pushMethodMonitor() {
            if (!methodSynchronized) {
                super.visitInsn(Opcodes.ACONST_NULL);
            } else if (!methodStatic) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
            } else if (classConstantsUsable) {
                super.visitLdcInsn(Type.getObjectType(classInternalName));
            } else {
                super.visitInsn(Opcodes.ACONST_NULL);
            }
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
        private @Nullable String noteAtomicBinding(String name) {
            if (!"findVarHandle".equals(name) && !"newUpdater".equals(name)
                    && !"getDeclaredField".equals(name)) {
                return null;
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
                return field;
            }
            return null;
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
            String boundField = noteAtomicBinding(name);
            String hook = weaveFieldInstructions ? spinLockHook(opcode, owner, name, descriptor)
                    : null;
            String slotHook = weaveFieldInstructions && hook == null
                    ? referenceSlotHook(opcode, owner, name, descriptor) : null;
            boolean messagePassingOffer = weaveFieldInstructions
                    && isMessagePassingQueueCall(opcode, owner, name, descriptor, true);
            boolean messagePassingPoll = weaveFieldInstructions
                    && isMessagePassingQueueCall(opcode, owner, name, descriptor, false);
            if (messagePassingOffer) {
                // [queue, element] -> DUP2 -> SWAP -> [queue, element, element, queue]: the offer is
                // published with its container before the queue is asked to accept it (#630, #664),
                // and the call below still sees exactly its own two operands.
                super.visitInsn(Opcodes.DUP2);
                super.visitInsn(Opcodes.SWAP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "ownershipOffered",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
            }
            if (messagePassingPoll) {
                // A copy of the queue waits under the call, for the take below to name it.
                super.visitInsn(Opcodes.DUP);
            }
            if (hook != null) {
                // Same arguments, same result, one static call instead of the virtual one.
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, hook,
                        spinLockHookDescriptor(owner, hook, descriptor), false);
                dropResultOfVoidCallSite(owner, hook, descriptor);
            } else if (slotHook != null) {
                // The same substitution for a reference slot (#664): the hook publishes the offer or
                // the take with the slot in hand, and returns what the call returned.
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, slotHook,
                        referenceSlotHookDescriptor(owner, slotHook, descriptor), false);
                castToCallSiteResult(owner, descriptor);
            } else {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
            if (messagePassingPoll) {
                // [queue, taken] -> DUP_X1 -> SWAP -> [taken, taken, queue] -> call -> [taken].
                super.visitInsn(Opcodes.DUP_X1);
                super.visitInsn(Opcodes.SWAP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "ownershipTaken",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
            }
            if (boundField != null && "findVarHandle".equals(name)
                    && descriptor.endsWith(")Ljava/lang/invoke/VarHandle;")) {
                // The handle is on top of the stack: tell the registry which field it reaches, so
                // a spinlock acquired through it can be released by a write to that field (#554).
                super.visitInsn(Opcodes.DUP);
                super.visitLdcInsn(boundField);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "varHandleBound",
                        "(Ljava/lang/Object;Ljava/lang/String;)V", false);
            }
            if (boundField != null && "newUpdater".equals(name)
                    && descriptor.endsWith(")L" + INT_UPDATER + ";")) {
                // The updater counterpart (#558): an updater has no way to name its field, so this
                // is the only place a spinlock taken through it can learn which flag it swaps.
                super.visitInsn(Opcodes.DUP);
                super.visitLdcInsn(boundField);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "atomicUpdaterBound",
                        "(Ljava/lang/Object;Ljava/lang/String;)V", false);
            }
            if (weaveFieldInstructions && slotHook == null
                    && isReferenceTake(opcode, owner, name, descriptor)) {
                // The returned reference is on top of the stack: hand a copy to the registry and
                // leave the original where the caller expects it. Stack-neutral and branch-free.
                super.visitInsn(Opcodes.DUP);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "ownershipTaken",
                        "(Ljava/lang/Object;)V", false);
            }
            recentConstants.clear();
            if (thisIsUninitialised && opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                thisIsUninitialised = false;
            }
        }

        /**
         * {@return the registry hook that replaces this call, or {@code null}}
         *
         * <p>A compare-and-swap on an {@code int} flag is how a spinlock is taken and released,
         * and the stores are how it is released too (#554). Four shapes reach one:
         * <ul>
         *   <li>a {@code VarHandle} on an {@code int} instance field. Only one object coordinate and
         *       an {@code int} qualify: a static field has no receiver to own the lock, and an array
         *       element is not a flag field. The descriptor is the call site's own, since
         *       {@code VarHandle} methods are signature-polymorphic;</li>
         *   <li>an {@code AtomicIntegerFieldUpdater}: {@code compareAndSet}, {@code set},
         *       {@code lazySet} (#558);</li>
         *   <li>an {@code AtomicBoolean} used as the lock: {@code compareAndSet},
         *       {@code getAndSet}, {@code set}, {@code lazySet} (#558);</li>
         *   <li>an {@code AtomicInteger} used as the lock: {@code compareAndSet}, {@code set},
         *       {@code lazySet} (#558).</li>
         * </ul>
         * The value-returning releases are substituted too (#658, #667), because a release the
         * weaver does not see leaves a window re-confirmation cannot close (see {@code SpinLocks}):
         * on the handle every {@code getAnd} form ({@code getAndSet}, {@code getAndAdd} and the
         * {@code getAndBitwise} forms, each with its {@code Acquire} and {@code Release} variant),
         * every {@code compareAndExchange} (all with an {@code int} or void call-site result) and
         * every weak swap; on the atomics and the updater the forms their tables list, which
         * include {@code setPlain}, {@code setOpaque}, {@code setRelease}, the update and
         * accumulate forms and the deprecated {@code weakCompareAndSet}. Every hook consumes exactly
         * the stack the original call did and returns what it returned. A release through any other
         * call is not substituted; the registry re-confirms a hold against the flag instead of
         * relying on seeing it, and {@code SpinLocks} lists those forms.
         */
        private static @Nullable String spinLockHook(int opcode, String owner, String name,
                                                     String descriptor) {
            if (opcode != Opcodes.INVOKEVIRTUAL) {
                return null;
            }
            return switch (owner) {
                case VAR_HANDLE -> varHandleHook(name, descriptor);
                case INT_UPDATER -> atomicHook(INT_UPDATER_FORMS, name, descriptor, "IntUpdater");
                case ATOMIC_BOOLEAN -> atomicHook(ATOMIC_BOOLEAN_FORMS, name, descriptor, "AtomicBoolean");
                case ATOMIC_INTEGER -> atomicHook(ATOMIC_INTEGER_FORMS, name, descriptor, "AtomicInteger");
                default -> null;
            };
        }

        /**
         * {@return {@code name + suffix}, the registry hook for this atomic call, when its descriptor
         * is the one {@code forms} lists for {@code name}, else {@code null}}
         *
         * <p>Every hook on an atomic is named for the method it replaces and the type it replaces it
         * on, so the tables below hold only the exact descriptor each form is matched on. An exact
         * match is what makes the substitution stack-neutral: the hook takes the receiver and then
         * exactly these parameters, and returns exactly this result.
         */
        private static @Nullable String atomicHook(Map<String, String> forms, String name,
                                                   String descriptor, String suffix) {
            return descriptor.equals(forms.get(name)) ? name + suffix : null;
        }

        /** {@return the {@code VarHandle} hook for an {@code int} instance-field call, or {@code null}} */
        private static @Nullable String varHandleHook(String name, String descriptor) {
            if (!descriptor.startsWith("(L")) {
                return null;
            }
            int firstSemicolon = descriptor.indexOf(';');
            String tail = descriptor.substring(firstSemicolon + 1);
            // compareAndSet and the weak swaps are declared boolean, so their descriptor is fixed.
            // The getAnd forms and compareAndExchange are declared Object: the call site says
            // (int) when the result is used and void when it is a statement (#658); any other
            // result type is left alone.
            boolean intOrVoid = tail.endsWith(")I") || tail.endsWith(")V");
            return switch (name) {
                case "compareAndSet", "weakCompareAndSet", "weakCompareAndSetPlain",
                     "weakCompareAndSetAcquire", "weakCompareAndSetRelease" ->
                        "II)Z".equals(tail) ? name + "Int" : null;
                case "compareAndExchange", "compareAndExchangeAcquire", "compareAndExchangeRelease" ->
                        tail.startsWith("II)") && intOrVoid ? name + "Int" : null;
                case "getAndSet", "getAndSetAcquire", "getAndSetRelease",
                     "getAndAdd", "getAndAddAcquire", "getAndAddRelease",
                     "getAndBitwiseOr", "getAndBitwiseOrAcquire", "getAndBitwiseOrRelease",
                     "getAndBitwiseAnd", "getAndBitwiseAndAcquire", "getAndBitwiseAndRelease",
                     "getAndBitwiseXor", "getAndBitwiseXorAcquire", "getAndBitwiseXorRelease" ->
                        tail.startsWith("I)") && intOrVoid ? name + "Int" : null;
                case "set", "setVolatile", "setRelease", "setOpaque" ->
                        "I)V".equals(tail) ? name + "Int" : null;
                default -> null;
            };
        }

        /**
         * {@return the static descriptor of {@code hook}: the virtual call's own, with the receiver
         * it was invoked on as the first parameter}
         *
         * <p>A {@code VarHandle} call site declares whatever types it pushed, so its hooks take the
         * erased {@code Object} receiver and {@code int} values the registry declares instead.
         */
        private static String spinLockHookDescriptor(String owner, String hook, String descriptor) {
            if (VAR_HANDLE.equals(owner)) {
                if (hook.startsWith("compareAndSet") || hook.startsWith("weakCompareAndSet")) {
                    return "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;II)Z";
                }
                if (hook.startsWith("compareAndExchange")) {
                    return "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;II)I";
                }
                if (hook.startsWith("getAnd")) {
                    return "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;I)I";
                }
                return "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;I)V";
            }
            return "(L" + owner + ";" + descriptor.substring(1);
        }
        /**
         * Pops the value a {@code VarHandle} hook returns when its call site declared no result.
         *
         * <p>{@code getAndSet}, {@code getAndAdd} and {@code compareAndExchange} are declared to
         * return {@code Object}, so a call used as a statement compiles with a {@code void}
         * descriptor, while the hook that replaces it returns the {@code int}. One {@code POP}
         * leaves the stack exactly as the void call would have: stack-neutral and branch-free
         * (#658).
         */
        private void dropResultOfVoidCallSite(String owner, String hook, String descriptor) {
            if (descriptor.endsWith(")V")
                    && !spinLockHookDescriptor(owner, hook, descriptor).endsWith(")V")) {
                super.visitInsn(Opcodes.POP);
            }
        }

        /**
         * {@return the registry hook that replaces this reference-slot call, or {@code null}}
         *
         * <p>A reference slot is a container of one, and netty moves a chunk between magazines
         * through one (#555): stored with {@code set}, {@code lazySet} or a compare-and-set, and
         * taken with {@code getAndSet}. Substituting both ends puts the slot in the registry's hand
         * at each, so a take-first generation can be matched to the offer that filled the slot
         * (#664, #692). The atomic calls are matched on their exact erased descriptors. A
         * {@code VarHandle} call qualifies on an instance field, a static field, or an array
         * element, with each operand a reference except the array element index.
         */
        private static @Nullable String referenceSlotHook(int opcode, String owner, String name,
                                                          String descriptor) {
            if (opcode != Opcodes.INVOKEVIRTUAL) {
                return null;
            }
            return switch (owner) {
                case ATOMIC_REFERENCE -> atomicHook(ATOMIC_REFERENCE_FORMS, name, descriptor,
                        "AtomicReference");
                case REFERENCE_UPDATER -> atomicHook(REFERENCE_UPDATER_FORMS, name, descriptor,
                        "ReferenceUpdater");
                case REFERENCE_ARRAY -> atomicHook(REFERENCE_ARRAY_FORMS, name, descriptor,
                        "ReferenceArray");
                case VAR_HANDLE -> referenceHandleHook(name, descriptor);
                default -> null;
            };
        }

        /** {@return the {@code VarHandle} hook for a reference slot call, or {@code null}} */
        private static @Nullable String referenceHandleHook(String name, String descriptor) {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            Type result = Type.getReturnType(descriptor);
            if (arguments.length >= 2 && isReference(arguments[0]) && arguments[1].getSort() == Type.INT) {
                for (int i = 2; i < arguments.length; i++) {
                    if (!isReference(arguments[i])) {
                        return null;
                    }
                }
                boolean matches = switch (name) {
                    case "set", "setVolatile", "setRelease", "setOpaque" ->
                            arguments.length == 3 && result.getSort() == Type.VOID;
                    case "compareAndSet" -> arguments.length == 4 && result.getSort() == Type.BOOLEAN;
                    case "getAndSet" -> arguments.length == 3 && isReference(result);
                    default -> false;
                };
                return matches ? name + "ArrayReferenceHandle" : null;
            }

            for (Type argument : arguments) {
                if (!isReference(argument)) {
                    return null;
                }
            }
            boolean instanceMatches = switch (name) {
                case "set", "setVolatile", "setRelease", "setOpaque" ->
                        arguments.length == 2 && result.getSort() == Type.VOID;
                case "compareAndSet" -> arguments.length == 3 && result.getSort() == Type.BOOLEAN;
                case "getAndSet" -> arguments.length == 2 && isReference(result);
                default -> false;
            };
            if (instanceMatches) {
                return name + "ReferenceHandle";
            }
            boolean staticMatches = switch (name) {
                case "set", "setVolatile", "setRelease", "setOpaque" ->
                        arguments.length == 1 && result.getSort() == Type.VOID;
                case "compareAndSet" -> arguments.length == 2 && result.getSort() == Type.BOOLEAN;
                case "getAndSet" -> arguments.length == 1 && isReference(result);
                default -> false;
            };
            return staticMatches ? name + "StaticReferenceHandle" : null;
        }

        private static boolean isReference(Type type) {
            return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
        }

        /**
         * {@return the static descriptor of a reference-slot hook}
         *
         * <p>An atomic's hook takes the atomic and then the call's own erased parameters. A
         * {@code VarHandle} hook takes the handle and one {@code Object} per operand (plus the
         * {@code int} index for an array element), and returns {@code Object} for a
         * {@code getAndSet}, which {@link #castToCallSiteResult} narrows back.
         */
        private static String referenceSlotHookDescriptor(String owner, String hook,
                                                          String descriptor) {
            if (!VAR_HANDLE.equals(owner)) {
                return "(L" + owner + ";" + descriptor.substring(1);
            }
            String result = hook.startsWith("getAndSet") ? "Ljava/lang/Object;"
                    : hook.startsWith("compareAndSet") ? "Z" : "V";
            if (hook.endsWith("ArrayReferenceHandle")) {
                return "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;I"
                        + "Ljava/lang/Object;".repeat(Type.getArgumentTypes(descriptor).length - 2)
                        + ")" + result;
            }
            return "(Ljava/lang/invoke/VarHandle;"
                    + "Ljava/lang/Object;".repeat(Type.getArgumentTypes(descriptor).length)
                    + ")" + result;
        }

        /**
         * Casts a {@code VarHandle} reference hook's {@code Object} result to the type the call
         * site declared.
         *
         * <p>A signature-polymorphic call site is verified against its own declared result, and no
         * cast follows it in the bytecode, so the substituted {@code Object} must be narrowed where
         * the call was. The handle's own invocation makes the same checked conversion, so the cast
         * fails exactly when the original call would have. {@code CHECKCAST} is stack-neutral and
         * branch-free, adds a constant-pool entry and no member, and resolves its class when it
         * runs, never inside the agent. An atomic's hook already returns what the call returned.
         */
        private void castToCallSiteResult(String owner, String descriptor) {
            if (!VAR_HANDLE.equals(owner)) {
                return;
            }
            Type result = Type.getReturnType(descriptor);
            if (isReference(result) && !"java/lang/Object".equals(result.getInternalName())) {
                super.visitTypeInsn(Opcodes.CHECKCAST, result.getInternalName());
            }
        }

        /**
         * {@return whether this is an offer ({@code offering}) or a poll on JCTools'
         * {@code MessagePassingQueue}}
         *
         * <p>JCTools' lock-free queue interface, which netty and others shade under their own
         * package, so it is recognised by the tail of its name. netty's buffer recycler hands a
         * pooled buffer to the next thread through it. Only calls typed by the interface match,
         * with {@code offer}/{@code relaxedOffer} taking one element and {@code poll}/
         * {@code relaxedPoll} returning one, which is the erased shape of every JCTools queue.
         */
        private static boolean isMessagePassingQueueCall(int opcode, String owner, String name,
                                                         String descriptor, boolean offering) {
            if (opcode != Opcodes.INVOKEINTERFACE
                    || !owner.endsWith("jctools/queues/MessagePassingQueue")) {
                return false;
            }
            return offering
                    ? ("offer".equals(name) || "relaxedOffer".equals(name))
                            && "(Ljava/lang/Object;)Z".equals(descriptor)
                    : ("poll".equals(name) || "relaxedPoll".equals(name))
                            && "()Ljava/lang/Object;".equals(descriptor);
        }

        /**
         * {@return whether this call swaps a reference out of a {@code VarHandle} slot the
         * substitution above does not cover}
         *
         * <p>{@code getAndSet} returns the value it replaced, and that value is no longer in the
         * slot, so the slot hands it to this thread and to no other (#555). The atomic slots and a
         * {@code VarHandle} on an instance field, a static field, or an array element are substituted
         * and report the take with their container (#664, #692); what is left is any {@code getAndSet}
         * the substitution does not cover, where the take is still reported, without a container. A
         * {@code VarHandle} call is signature-polymorphic, so its descriptor is whatever the call site
         * declared; the return type is what decides.
         */
        private static boolean isReferenceTake(int opcode, String owner, String name,
                                                String descriptor) {
            return opcode == Opcodes.INVOKEVIRTUAL && "getAndSet".equals(name)
                    && VAR_HANDLE.equals(owner) && isReference(Type.getReturnType(descriptor));
        }
        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            noteConstant(null);
            super.visitVarInsn(opcode, varIndex);
        }


        /**
         * Leaves the receiver and, for a reference store, the value being stored on top.
         *
         * <p>Which instance a field belongs to is the difference between six threads racing on one
         * object and six threads each using their own. Without it a per-call object, a hasher, a
         * matcher, an iterator, aggregates by field name and reads as shared, which is a false
         * positive on code that is not even concurrent.
         *
         * <p>The stored value answers a different question (#326). An access stream that carries
         * no values cannot tell an idempotent value apart from a side effect, so a double-submit
         * shaped like a view cache converges on the field exactly like a cache and is excused for
         * it. An instance reference store is the shape where the value is already on the stack in
         * the right place - {@code DUP2} leaves receiver and value in argument order with nothing
         * to undo - so it costs two instructions and no scratch slot. A primitive or category-2
         * store passes {@code null} here, and the analysis reads that as "not known" rather than
         * as evidence. The static reference store reaches its value too, one instruction further
         * along; see {@link #liftStaticReceiver}.
         *
         * <p>Done with stack manipulation rather than a scratch local on purpose: a local would
         * grow {@code maxLocals} and put a write and a read of an undeclared slot into a method
         * whose stack map frames the weaver deliberately does not recompute. Every sequence here is
         * branch-free and returns the stack to the exact shape the field instruction expects, so
         * only {@code maxStack} moves, which is what {@code COMPUTE_MAXS} is for.
         */
        private void liftReceiver(boolean isWrite, String descriptor) {
            if (!isWrite) {
                super.visitInsn(Opcodes.DUP);                 // obj -> obj, obj
                super.visitInsn(Opcodes.ACONST_NULL);         //     -> obj, obj, null
            } else if (isCategoryTwo(descriptor)) {
                super.visitInsn(Opcodes.DUP2_X1);             // obj, v1, v2 -> v1, v2, obj, v1, v2
                super.visitInsn(Opcodes.POP2);                //             -> v1, v2, obj
                super.visitInsn(Opcodes.DUP);                 //             -> v1, v2, obj, obj
                super.visitInsn(Opcodes.ACONST_NULL);         //             -> ..., obj, obj, null
            } else if (isReference(descriptor)) {
                // The value is already where the hook wants it: DUP2 leaves (receiver, stored)
                // in argument order, and the field instruction's own operands are untouched
                // beneath, so unlike every other write shape this one needs no restore.
                super.visitInsn(Opcodes.DUP2);                // obj, v -> obj, v, obj, v
            } else {
                super.visitInsn(Opcodes.DUP2);                // obj, v -> obj, v, obj, v
                super.visitInsn(Opcodes.POP);                 //        -> obj, v, obj
                super.visitInsn(Opcodes.ACONST_NULL);         //        -> obj, v, obj, null
            }
        }

        /**
         * Leaves the declaring class and, for a reference store, the value being stored on top.
         *
         * <p>The static counterpart of {@link #liftReceiver}. There is no receiver to lift: the
         * declaring class stands in for it, its identity stays 0 on the hook side exactly as
         * before, and its monitor is what a static {@code synchronized} method somewhere up the
         * stack would be holding.
         *
         * <p>The value is the part that used to be given up (#337). A static store has only the
         * value on the stack, with the class constant pushed above it, so unlike {@code PUTFIELD}
         * the two are in the wrong order and {@code DUP2} cannot fix it. {@code DUP; class; SWAP}
         * can: it copies the value, pushes the class above the copy, and exchanges the top two,
         * leaving the original value untouched at the bottom for the {@code PUTSTATIC} that
         * follows. Both exchanged operands are references, so they are category 1 and
         * {@code SWAP} is legal on them; without that guarantee the instruction would be
         * rejected by the verifier, which is why only reference descriptors take this path.
         *
         * <p>Everything else keeps passing {@code null}: a primitive store's value is not an
         * object to identify, a category-2 store cannot be swapped at all, and a
         * {@code GETSTATIC} has no value on the stack yet. The analysis reads {@code null} as
         * "not known" rather than as evidence, so those shapes keep the answer they had.
         */
        private void liftStaticReceiver(String owner, boolean isWrite, String descriptor) {
            boolean liftValue = isWrite && isReference(descriptor);
            if (liftValue) {
                super.visitInsn(Opcodes.DUP);                 // v -> v, v
            }
            if (classConstantsUsable) {
                super.visitLdcInsn(Type.getObjectType(owner));
            } else {
                super.visitInsn(Opcodes.ACONST_NULL);
            }
            if (liftValue) {
                super.visitInsn(Opcodes.SWAP);                // v, v, class -> v, class, v
            } else {
                super.visitInsn(Opcodes.ACONST_NULL);
            }
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

        /** {@return whether {@code descriptor} names a reference type, so its value is an Object} */
        private static boolean isReference(String descriptor) {
            return descriptor.charAt(0) == 'L' || descriptor.charAt(0) == '[';
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
                    liftStaticReceiver(owner, isWrite, descriptor);
                } else {
                    liftReceiver(isWrite, descriptor);
                }
                pushMethodMonitor();
                super.visitMethodInsn(Opcodes.INVOKESTATIC, THREAD, "currentThread",
                        "()Ljava/lang/Thread;", false);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THREAD, "threadId", "()J", false);
                super.visitLdcInsn(identifier);
                super.visitInsn(isWrite ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitInsn(isVolatile(owner, name) ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitLdcInsn(tag);
                super.visitInsn(ownersWithVolatileReadInThisMethod.contains(owner)
                        ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitInsn(isStatic ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "recordAccess",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                                + "JLjava/lang/String;ZZIZZ)V", false);
                if (isWrite && !isStatic) {
                    restoreReceiverBelowValue(descriptor);
                }
                notePublication(owner, name, isWrite, isVolatile(owner, name));
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }
    }
}
