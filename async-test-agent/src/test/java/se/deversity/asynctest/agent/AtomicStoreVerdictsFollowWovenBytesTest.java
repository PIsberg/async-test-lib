package se.deversity.asynctest.agent;

import com.example.agentfixture.ResetThenIncrementBean;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The verdict that a store was not built from a read must come from the bytes the weaver is handed,
 * not from the class file its loader holds (#1191). When an earlier transformer has added a store,
 * verdicts read from the loader's copy are matched to the wrong stores by ordinal, and a store that
 * really is a lost update is woven as unrelated: a missed finding nobody sees.
 */
class AtomicStoreVerdictsFollowWovenBytesTest {

    private static final String ATOMIC_INTEGER = "java/util/concurrent/atomic/AtomicInteger";
    private static final String BEAN = "com/example/agentfixture/ResetThenIncrementBean";
    private static final String TELEMETRY = "se/deversity/asynctest/telemetry/TelemetryRegistry";

    @Test
    void aStoreAnEarlierTransformerAddedDoesNotShiftTheVerdictsOfTheOthers() throws IOException {
        byte[] rewritten = withDerivedStorePrepended(originalBytes());

        byte[] woven = new ByteBuddy()
                .redefine(TypeDescription.ForLoadedType.of(ResetThenIncrementBean.class),
                        ClassFileLocator.Simple.of(ResetThenIncrementBean.class.getName(), rewritten))
                .visit(AsyncTestAgent.fieldWeaving())
                .make()
                .getBytes();

        assertEquals(List.of("setAtomicInteger", "setAtomicIntegerUnrelated", "setAtomicInteger"),
                storeHooksIn(woven, "resetThenIncrement"),
                "the woven method holds the added set(get() + 1), then set(0), then set(get() + 1): only "
                        + "the constant store is unrelated. Verdicts taken from the loader's two-store copy "
                        + "would give the added store the constant's verdict and hide a lost update.");
    }

    private static byte[] originalBytes() throws IOException {
        try (InputStream in = ResetThenIncrementBean.class.getClassLoader()
                .getResourceAsStream(BEAN + ".class")) {
            assertNotNull(in, "fixture class must be on the test class path");
            return in.readAllBytes();
        }
    }

    /** What an earlier transformer might do: prepend {@code gauge.set(gauge.get() + 1)} to the method. */
    private static byte[] withDerivedStorePrepended(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"resetThenIncrement".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitFieldInsn(Opcodes.GETFIELD, BEAN, "gauge", "L" + ATOMIC_INTEGER + ";");
                        super.visitInsn(Opcodes.DUP);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ATOMIC_INTEGER, "get", "()I", false);
                        super.visitInsn(Opcodes.ICONST_1);
                        super.visitInsn(Opcodes.IADD);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ATOMIC_INTEGER, "set", "(I)V", false);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    /** {@return the store hooks the woven method calls, in order} */
    private static List<String> storeHooksIn(byte[] woven, String method) {
        List<String> hooks = new ArrayList<>();
        new ClassReader(woven).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (!method.equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String hook, String desc,
                                                boolean isInterface) {
                        if (TELEMETRY.equals(owner) && hook.startsWith("setAtomicInteger")) {
                            hooks.add(hook);
                        }
                    }
                };
            }
        }, 0);
        return hooks;
    }
}
