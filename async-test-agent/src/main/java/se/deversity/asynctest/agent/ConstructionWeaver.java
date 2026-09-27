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
 * Tells the library what only a constructor call in a woven class knows: the order a
 * {@code LinkedHashMap} is built in (#807).
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
 * <p>Each insertion is one value and a static call after or before an instruction: no branch, no
 * new frame, no member, so only {@code maxStack} grows ({@code COMPUTE_MAXS}) and the class stays
 * safe to retransform under {@code disableClassFormatChanges()}. The hook class is resolved when
 * the visitor is built, so a library without it fails the install rather than a woven class.
 */
final class ConstructionWeaver implements AsmVisitorWrapper {

    /** The library class the inserted calls land in. */
    static final String HOOKS = "se.deversity.asynctest.AgentConstructionHooks";

    /** Takes the order argument before the constructor call and hands it back. */
    static final String ACCESS_ORDER_HOOK = "linkedHashMapAccessOrder";

    /** Takes the built map after the constructor call. */
    static final String MAP_CONSTRUCTED_HOOK = "linkedHashMapConstructed";

    private static final String LINKED_HASH_MAP = "java/util/LinkedHashMap";

    private static final String ORDERED_CONSTRUCTOR = "(IFZ)V";

    private static final String CONSTRUCTOR = "<init>";

    private static final String ACCESS_ORDER_DESCRIPTOR = "(Z)Z";

    private static final String MAP_CONSTRUCTED_DESCRIPTOR = "(Ljava/util/LinkedHashMap;)V";

    /** The internal name of the class holding the hooks. */
    private final String hookOwner;

    private ConstructionWeaver(String hookOwner) {
        this.hookOwner = hookOwner;
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
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("no construction hook on " + hooks.getName()
                    + "; agent and library versions disagree", e);
        }
        return new ConstructionWeaver(Type.getInternalName(hooks));
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
        return new ClassVisitor(Opcodes.ASM9, classVisitor) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate =
                        super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MarkingVisitor(delegate, hookOwner, CONSTRUCTOR.equals(name));
            }
        };
    }

    /** Inserts the hook calls around each ordered {@code LinkedHashMap} construction in a method. */
    private static final class MarkingVisitor extends MethodVisitor {

        private final String hookOwner;

        /** {@code new LinkedHashMap} expressions opened by a {@code NEW} and not yet initialised. */
        private int openMaps;

        /** Whether local 0 still holds {@code this}: in a constructor, until a store to it. */
        private boolean thisInLocalZero;

        MarkingVisitor(MethodVisitor delegate, String hookOwner, boolean constructor) {
            super(Opcodes.ASM9, delegate);
            this.hookOwner = hookOwner;
            this.thisInLocalZero = constructor;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW && LINKED_HASH_MAP.equals(type)) {
                openMaps++;
            }
            super.visitTypeInsn(opcode, type);
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
