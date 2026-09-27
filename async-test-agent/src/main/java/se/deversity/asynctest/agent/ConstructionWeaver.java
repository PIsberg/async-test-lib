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

/**
 * Tells the library what only a constructor in a woven class knows: the order a
 * {@code LinkedHashMap} is built in (#807), and when a constructor returns (#791).
 *
 * <p>A {@code get} on an access-ordered map relinks the entry, so it writes the map, and the order
 * is a private field the library can read only with {@code java.util} opened to it. The
 * three-argument constructor is the one way to set it, so this visitor reads it there, around
 * every {@code INVOKESPECIAL java/util/LinkedHashMap.<init>(IFZ)V}:
 * <ul>
 *   <li>Before the call, a call to the hook's {@code linkedHashMapAccessOrder(Z)Z}, which consumes
 *       the {@code boolean} argument on top of the stack and pushes it back.</li>
 *   <li>After the call, the built map and a call to {@code linkedHashMapConstructed}. For a
 *       {@code new LinkedHashMap(...)} the map is a {@code DUP} of the initialised reference the
 *       {@code NEW}/{@code DUP} pair left beneath the arguments; which call belongs to a
 *       {@code NEW} is counted, as {@link ThreadConstructionWeaver} counts thread constructions.
 *       A call with no {@code NEW} open is a subclass constructor's superclass call, the usual LRU
 *       cache, and the map is an {@code ALOAD 0} of the now initialised {@code this}, unless the
 *       constructor has stored to local 0, where it may no longer be {@code this}; then neither
 *       call is inserted.</li>
 * </ul>
 *
 * <p>A constructor's return ends the construction of its object only when it is the constructor of
 * the object's own class and not one another constructor of that class delegated to, and nothing
 * but the bytecode can say which: {@code ConstructorSafetyValidator} otherwise judges a
 * construction by whether a constructor of the class is still on the constructing thread's stack,
 * which a later instance's constructor answers wrongly. So in every constructor:
 * <ul>
 *   <li>Before each {@code RETURN}, an {@code ALOAD 0} of {@code this}, an {@code LDC} of the
 *       class's binary name and a call to {@code constructorReturned}, which does nothing unless
 *       the object is of exactly that class.</li>
 *   <li>After a {@code this(...)} delegation, the same three with {@code constructorResumed}: the
 *       constructor delegated to has returned, and this one goes on constructing. A
 *       {@code new} of the class itself inside its constructor is counted like the map's, so its
 *       constructor call is not taken for a delegation.</li>
 * </ul>
 * Both stop at a store to local 0, like the map's superclass call, and the order of the checks
 * relies on it: a {@code RETURN} emitted before such a store but reached after it would load
 * something that is not {@code this}. No Java compiler stores to local 0 in a constructor.
 *
 * <p>Each insertion is at most three values and a static call after or before an instruction: no
 * branch, no new frame, no member, so only {@code maxStack} grows ({@code COMPUTE_MAXS}) and the
 * class stays safe to retransform under {@code disableClassFormatChanges()}. The hook class is
 * resolved when the visitor is built, so a library without it fails the install rather than a
 * woven class. A class in a named module that cannot read the hook class's module is left alone
 * ({@link #reachableFrom(Module)}): every one of its constructors would otherwise throw
 * {@code IllegalAccessError}.
 */
final class ConstructionWeaver implements AsmVisitorWrapper {

    /** The library class the inserted calls land in. */
    static final String HOOKS = "se.deversity.asynctest.AgentConstructionHooks";

    /** Takes the order argument before the constructor call and hands it back. */
    static final String ACCESS_ORDER_HOOK = "linkedHashMapAccessOrder";

    /** Takes the built map after the constructor call. */
    static final String MAP_CONSTRUCTED_HOOK = "linkedHashMapConstructed";

    /** Takes {@code this} and the class name before a constructor's {@code RETURN}. */
    static final String RETURNED_HOOK = "constructorReturned";

    /** Takes {@code this} and the class name after a constructor's {@code this(...)} call. */
    static final String RESUMED_HOOK = "constructorResumed";

    private static final String LINKED_HASH_MAP = "java/util/LinkedHashMap";

    private static final String ORDERED_CONSTRUCTOR = "(IFZ)V";

    private static final String CONSTRUCTOR = "<init>";

    private static final String ACCESS_ORDER_DESCRIPTOR = "(Z)Z";

    private static final String MAP_CONSTRUCTED_DESCRIPTOR = "(Ljava/util/LinkedHashMap;)V";

    private static final String CONSTRUCTOR_HOOK_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/String;)V";

    /** The internal name of the class holding the hooks. */
    private final String hookOwner;

    /** The module of the class holding the hooks, which a woven class has to read. */
    private final Module hookModule;

    private ConstructionWeaver(Class<?> hooks) {
        this.hookOwner = Type.getInternalName(hooks);
        this.hookModule = hooks.getModule();
    }

    /**
     * {@return whether a class in {@code module} can link to the hooks}
     *
     * <p>An unnamed module reads every module. A named one reads the library's only through an
     * edge, which {@code UpdaterAccess} adds under {@code fields=true} and nothing adds otherwise;
     * such a class is not woven here rather than woven into constructors that cannot link.
     *
     * @param module the module of the class about to be woven, or {@code null} where the JVM has
     *               none to name, which is treated as unnamed
     */
    boolean reachableFrom(@org.jspecify.annotations.Nullable Module module) {
        return module == null || module.canRead(hookModule);
    }

    /**
     * {@return the visitor, calling the construction hooks on {@code hooks}}
     *
     * @param hooks the class holding the hooks, resolved in the weaving class loader; one missing a
     *              hook, or declaring it with another shape, is a version skew between agent and
     *              library, refused here rather than woven into a call that would fail inside user
     *              code
     */
    static ConstructionWeaver of(Class<?> hooks) {
        try {
            require(hooks.getMethod(ACCESS_ORDER_HOOK, boolean.class), boolean.class);
            require(hooks.getMethod(MAP_CONSTRUCTED_HOOK, java.util.LinkedHashMap.class),
                    void.class);
            require(hooks.getMethod(RETURNED_HOOK, Object.class, String.class), void.class);
            require(hooks.getMethod(RESUMED_HOOK, Object.class, String.class), void.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no construction hook on " + hooks.getName()
                    + "; agent and library versions disagree", e);
        }
        return new ConstructionWeaver(hooks);
    }

    private static void require(java.lang.reflect.Method hook, Class<?> returns) {
        if (hook.getReturnType() != returns
                || !java.lang.reflect.Modifier.isStatic(hook.getModifiers())) {
            throw new IllegalStateException("construction hook " + hook
                    + " must be static and return " + returns.getName()
                    + "; agent and library versions disagree");
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
        String internalName = instrumentedType.getInternalName();
        String binaryName = instrumentedType.getName();
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MarkingVisitor(delegate, hookOwner, CONSTRUCTOR.equals(name),
                        internalName, binaryName);
            }
        };
    }

    /**
     * Inserts the hook calls around each ordered {@code LinkedHashMap} construction in a method,
     * and at the returns and delegations of a constructor.
     */
    private static final class MarkingVisitor extends MethodVisitor {

        private final String hookOwner;

        /** Whether this method is a constructor. */
        private final boolean constructor;

        /** The internal name of the class whose method this is. */
        private final String ownInternalName;

        /** Its binary name, which {@code Class.getName()} answers for its instances. */
        private final String ownBinaryName;

        /** {@code new LinkedHashMap} expressions opened by a {@code NEW} and not yet initialised. */
        private int openMaps;

        /** {@code new} expressions of this class itself opened and not yet initialised. */
        private int openOwnInstances;

        /** Whether local 0 still holds {@code this}: in a constructor, until a store to it. */
        private boolean thisInLocalZero;

        MarkingVisitor(MethodVisitor delegate, String hookOwner, boolean constructor,
                       String ownInternalName, String ownBinaryName) {
            super(Opcodes.ASM9, delegate);
            this.hookOwner = hookOwner;
            this.constructor = constructor;
            this.ownInternalName = ownInternalName;
            this.ownBinaryName = ownBinaryName;
            this.thisInLocalZero = constructor;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) {
                if (LINKED_HASH_MAP.equals(type)) {
                    openMaps++;
                } else if (constructor && ownInternalName.equals(type)) {
                    openOwnInstances++;
                }
            }
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN && constructor && thisInLocalZero) {
                callWithThis(RETURNED_HOOK);
            }
            super.visitInsn(opcode);
        }

        /** Inserts {@code ALOAD 0}, the class's name and a call to {@code hook}. */
        private void callWithThis(String hook) {
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitLdcInsn(ownBinaryName);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hook,
                    CONSTRUCTOR_HOOK_DESCRIPTOR, false);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            if (varIndex == 0 && opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                thisInLocalZero = false;
            }
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            if (opcode == Opcodes.INVOKESPECIAL && CONSTRUCTOR.equals(name) && constructor
                    && ownInternalName.equals(owner)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                if (openOwnInstances > 0) {
                    openOwnInstances--;
                } else if (thisInLocalZero) {
                    callWithThis(RESUMED_HOOK);
                }
                return;
            }
            if (opcode != Opcodes.INVOKESPECIAL || !CONSTRUCTOR.equals(name)
                    || !LINKED_HASH_MAP.equals(owner)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                return;
            }
            // Every LinkedHashMap constructor closes the innermost open NEW, whatever its
            // arguments, so the count stays right for the ordered ones.
            boolean newExpression = openMaps > 0;
            if (newExpression) {
                openMaps--;
            }
            boolean ordered = ORDERED_CONSTRUCTOR.equals(descriptor)
                    && (newExpression || thisInLocalZero);
            if (ordered) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, ACCESS_ORDER_HOOK,
                        ACCESS_ORDER_DESCRIPTOR, false);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (!ordered) {
                return;
            }
            if (newExpression) {
                super.visitInsn(Opcodes.DUP);
            } else {
                super.visitVarInsn(Opcodes.ALOAD, 0);
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, MAP_CONSTRUCTED_HOOK,
                    MAP_CONSTRUCTED_DESCRIPTOR, false);
        }
    }
}
