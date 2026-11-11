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
import net.bytebuddy.pool.TypePool;

/**
 * Weaves every array element load and store, with {@code arrays=true} (#1250).
 *
 * <p>No array instruction was woven before, so a lost update on {@code counter[0]} drew nothing
 * while the same update on a field was reported. Before each of the eight loads
 * ({@code IALOAD} to {@code SALOAD}) the weaver inserts {@code DUP2}, an element-kind constant and
 * {@code INVOKESTATIC TelemetryRegistry.arrayRead(Object, int, int)V}; before each of the eight
 * stores it copies the array and index above the value and calls {@code arrayWrite} the same way:
 * {@code DUP_X2; POP; DUP2_X1} for a one-slot value, {@code DUP2_X2; POP2; DUP2_X2} for a
 * {@code long} or {@code double}. Every insertion is stack-neutral and branch-free, so frames stay
 * valid and only {@code maxStack} grows ({@code COMPUTE_MAXS}, never {@code COMPUTE_FRAMES}).
 *
 * <p>The hook records the element as an access named for its kind and index, {@code int[3]}, on
 * the array as receiver, so it is judged per element by the same lockset and happens-before rules
 * as a field. The type initializer is not woven, as for fields: class initialisation runs under the
 * JVM's own lock.
 */
final class ArrayAccessWeaver {

    /** The library class whose static hooks the woven code calls. */
    static final String REGISTRY = "se/deversity/asynctest/telemetry/TelemetryRegistry";

    /** The descriptor of {@code arrayRead} and {@code arrayWrite}: array, index, element kind. */
    static final String HOOK_DESCRIPTOR = "(Ljava/lang/Object;II)V";

    private ArrayAccessWeaver() {
    }

    /**
     * {@return the visitor wrapper that weaves array loads and stores in every method of a type}
     *
     * <p>A class-level wrapper, not {@code AsmVisitorWrapper.ForDeclaredMethods}: that one is not
     * handed a lambda's synthetic body, so {@code () -> counter[0]++} went unwoven while the same
     * line in a named method was seen. Every method but the type initializer is woven.
     */
    static AsmVisitorWrapper visitor() {
        return new ClassLevelWrapper();
    }

    /** Wraps each method a class visitor is handed, the type initializer excepted. */
    private static final class ClassLevelWrapper implements AsmVisitorWrapper {

        @Override
        public int mergeWriter(int flags) {
            return flags | ClassWriter.COMPUTE_MAXS;
        }

        @Override
        public int mergeReader(int flags) {
            return flags;
        }

        @Override
        public ClassVisitor wrap(TypeDescription instrumentedType, ClassVisitor classVisitor,
                                 Implementation.Context implementationContext, TypePool typePool,
                                 FieldList<FieldDescription.InDefinedShape> fields,
                                 MethodList<?> methods, int writerFlags, int readerFlags) {
            return new ClassVisitor(Opcodes.ASM9, classVisitor) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate =
                            super.visitMethod(access, name, descriptor, signature, exceptions);
                    return delegate == null || "<clinit>".equals(name)
                            ? delegate : new ArrayMethodVisitor(delegate);
                }
            };
        }
    }

    /** Inserts the hook calls before each array element instruction. */
    static final class ArrayMethodVisitor extends MethodVisitor {

        ArrayMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) {
                // array, index -> array, index, array, index
                super.visitInsn(Opcodes.DUP2);
                call("arrayRead", opcode - Opcodes.IALOAD);
            } else if (opcode == Opcodes.LASTORE || opcode == Opcodes.DASTORE) {
                // array, index, value2 -> value2, array, index, value2 -> value2, array, index
                // -> array, index, value2, array, index
                super.visitInsn(Opcodes.DUP2_X2);
                super.visitInsn(Opcodes.POP2);
                super.visitInsn(Opcodes.DUP2_X2);
                call("arrayWrite", opcode - Opcodes.IASTORE);
            } else if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
                // array, index, value -> value, array, index, value -> value, array, index
                // -> array, index, value, array, index
                super.visitInsn(Opcodes.DUP_X2);
                super.visitInsn(Opcodes.POP);
                super.visitInsn(Opcodes.DUP2_X1);
                call("arrayWrite", opcode - Opcodes.IASTORE);
            }
            super.visitInsn(opcode);
        }

        /** Pushes the element kind (0 to 7, in opcode order) and calls {@code hook}. */
        private void call(String hook, int kind) {
            if (kind <= 5) {
                super.visitInsn(Opcodes.ICONST_0 + kind);
            } else {
                super.visitIntInsn(Opcodes.BIPUSH, kind);
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, hook, HOOK_DESCRIPTOR, false);
        }
    }
}
