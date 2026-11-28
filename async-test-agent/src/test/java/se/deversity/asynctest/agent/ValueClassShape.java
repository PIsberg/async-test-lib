package se.deversity.asynctest.agent;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A class in the shape kotlinc gives a generic value class (#1467): a static {@code -impl} method
 * whose generic signature names the class's own type variable, as
 * {@code kotlinx.coroutines.internal.SegmentOrClosed.getSegment-impl} does. The JVM accepts it;
 * Byte Buddy cannot resolve {@code S} from a static method, and the class was left unwoven with
 * {@code Cannot resolve S from public static final ?}.
 */
final class ValueClassShape {

    static final String PACKAGE = "com.example.valueclass";
    static final String NAME = PACKAGE + ".SegmentOrClosed";

    private ValueClassShape() {
    }

    /**
     * Defines the class in a loader of its own while the agent is installed, and returns what the
     * agent printed meanwhile.
     */
    static String loadAndCaptureAgentOutput() throws Exception {
        byte[] bytes = bytes();
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Class<?> type = new ClassLoader(ValueClassShape.class.getClassLoader()) {
                Class<?> define() {
                    return defineClass(NAME, bytes, 0, bytes.length);
                }
            }.define();
            // Runs the static method, so a class that failed verification would throw here.
            type.getMethod("getSegment-impl", Object.class).invoke(null, "segment");
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static byte[] bytes() {
        String internal = NAME.replace('.', '/');
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internal,
                "<S:Ljava/lang/Object;>Ljava/lang/Object;", "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "value", "Ljava/lang/Object;", null, null)
                .visitEnd();
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "getSegment-impl", "(Ljava/lang/Object;)Ljava/lang/Object;", "(Ljava/lang/Object;)TS;", null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        MethodVisitor read = cw.visitMethod(Opcodes.ACC_PUBLIC, "read", "()Ljava/lang/Object;", null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitFieldInsn(Opcodes.GETFIELD, internal, "value", "Ljava/lang/Object;");
        read.visitInsn(Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
