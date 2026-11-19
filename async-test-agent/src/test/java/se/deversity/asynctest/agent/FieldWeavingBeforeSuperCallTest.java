package se.deversity.asynctest.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A constructor that builds an object before its own superclass call still verifies once woven
 * (#1412).
 *
 * <p>Until that call, {@code this} is {@code uninitializedThis}, and the verifier refuses to pass it
 * to any method, so the weaver must not hand it to a volatile-store hook. A helper's
 * {@code new Foo()} in the superclass call's arguments, or in a prologue before it (JEP 513, Kotlin,
 * generated code), ends with an {@code INVOKESPECIAL <init>} too, and taking that one for the
 * superclass call wove a hook that made the class fail to load with a {@code VerifyError}. The
 * classes are generated because Java 21 source cannot write a field of {@code this} before
 * {@code super(...)}.
 */
class FieldWeavingBeforeSuperCallTest {

    private static final String PARENT = "com/example/generated/PrologueParent";
    private static final String CHILD = "com/example/generated/PrologueChild";

    @Test
    @DisplayName("super(new Object(), this.v = 1) still loads and runs once woven")
    void aHelperBuiltInTheSuperCallArgumentsIsNotTheSuperCall() throws Exception {
        Object child = wovenChild(true).getConstructor().newInstance();

        assertEquals(1, child.getClass().getDeclaredField("v").getInt(child),
                "the store before the superclass call still happened");
    }

    @Test
    @DisplayName("Object o = new Object(); this.v = 1; super(o, 0) still loads and runs once woven")
    void aHelperBuiltInAPrologueIsNotTheSuperCall() throws Exception {
        Object child = wovenChild(false).getConstructor().newInstance();

        assertEquals(1, child.getClass().getDeclaredField("v").getInt(child),
                "the store before the superclass call still happened");
    }

    /** {@return the woven child, loaded and so verified} */
    private static Class<?> wovenChild(boolean storeInArguments) throws ClassNotFoundException {
        Map<String, byte[]> generated = Map.of(
                dotted(PARENT), parentBytes(),
                dotted(CHILD), childBytes(storeInArguments));
        ClassFileLocator locator = new ClassFileLocator.Compound(
                new ClassFileLocator.Simple(generated),
                ClassFileLocator.ForClassLoader.ofSystemLoader());
        byte[] woven = new ByteBuddy()
                .redefine(TypePool.Default.of(locator).describe(dotted(CHILD)).resolve(), locator)
                .visit(AsyncTestAgent.fieldWeaving())
                .make()
                .getBytes();
        ClassLoader loader = new ByteArrayClassLoader(
                FieldWeavingBeforeSuperCallTest.class.getClassLoader(),
                Map.of(dotted(PARENT), generated.get(dotted(PARENT)), dotted(CHILD), woven));
        return Class.forName(dotted(CHILD), true, loader);
    }

    /** {@code public class PrologueParent { public PrologueParent(Object a, int b) {} }} */
    private static byte[] parentBytes() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PARENT, null,
                "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "(Ljava/lang/Object;I)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * {@code public volatile int v;} and either {@code super(new Object(), this.v = 1)} or
     * {@code Object o = new Object(); this.v = 1; super(o, 0)}.
     */
    private static byte[] childBytes(boolean storeInArguments) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CHILD, null, PARENT, null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE, "v", "I", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        if (storeInArguments) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            newObject(init);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitInsn(Opcodes.ICONST_1);
            init.visitInsn(Opcodes.DUP_X1);
            init.visitFieldInsn(Opcodes.PUTFIELD, CHILD, "v", "I");
        } else {
            newObject(init);
            init.visitVarInsn(Opcodes.ASTORE, 1);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitInsn(Opcodes.ICONST_1);
            init.visitFieldInsn(Opcodes.PUTFIELD, CHILD, "v", "I");
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, 1);
            init.visitInsn(Opcodes.ICONST_0);
        }
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, PARENT, "<init>", "(Ljava/lang/Object;I)V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void newObject(MethodVisitor method) {
        method.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
