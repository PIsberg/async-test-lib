package se.deversity.asynctest.agent;

import com.example.agentfixture.FlowSensitiveFieldSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the field weaver remembers between instructions must hold on the path an access runs on
 * (#1416): the constant a write stores, the plain writes a volatile write publishes, and the
 * volatile reads a plain read comes after. And a {@code synchronized} instance method, or a
 * {@code Thread} subclass constructor, that stores something else into local 0 still loads.
 */
class FieldWeavingFlowTest {

    private static final String REGISTRY = "se/deversity/asynctest/telemetry/TelemetryRegistry";
    private static final String SAMPLE = "com.example.agentfixture.FlowSensitiveFieldSample";

    /** One woven recordAccess call: the field, the constant tag, and the after-volatile-read flag. */
    record Access(String field, int tag, boolean afterVolatileRead) {
    }

    @Test
    @DisplayName("a constant that is not the stored value does not tag the write")
    void aConstantThatIsNotTheStoredValueIsNotATag() {
        assertEquals(List.of(new Access(SAMPLE + ".array", Integer.MIN_VALUE, false)),
                accesses("storeArray"), "the 1 is the array's length");
        List<Access> copy = accesses("copyAfterConstant");
        assertEquals(new Access(SAMPLE + ".a", 1, false), copy.get(0), "a stores the constant 1");
        assertEquals(new Access(SAMPLE + ".b", Integer.MIN_VALUE, false), copy.get(2),
                "b stores what was read from c, not the 1 stored before it: " + copy);
    }

    @Test
    @DisplayName("a plain write on a path that returns first is not published by a later volatile write")
    void anEarlyReturnPublishesNothing() {
        assertEquals(List.of(), published("writeThenReturnEarly"),
                "the plain write never precedes the volatile write on any path");
        assertEquals(List.of(SAMPLE + ".plain"), published("publish"), "the idiom still publishes");
    }

    @Test
    @DisplayName("a plain read on a path that never read the volatile is not ordered by it")
    void anEarlyVolatileReadOrdersNothingAfterTheBranch() {
        List<Access> reads = accesses("readAfterEarlyVolatileRead");
        Access plain = reads.stream().filter(r -> r.field().endsWith(".plain")).findFirst().orElseThrow();
        assertFalse(plain.afterVolatileRead(), "only the other path read the volatile: " + reads);

        List<Access> memoized = accesses("memoized");
        Access last = memoized.get(memoized.size() - 1);
        assertEquals(SAMPLE + ".plain", last.field());
        assertTrue(last.afterVolatileRead(), "every path to the final read reads the volatile first: "
                + memoized);
    }

    @Test
    @DisplayName("a synchronized instance method that stores an int into local 0 still loads")
    void aSynchronizedMethodThatOverwritesLocalZeroLoads() throws Exception {
        String name = "com/example/generated/OverwritesThis";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED, "run", "()I",
                null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ICONST_5);
        m.visitVarInsn(Opcodes.ISTORE, 0);
        m.visitVarInsn(Opcodes.ILOAD, 0);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        writer.visitEnd();

        Class<?> loaded = loadWoven(name, writer.toByteArray(), AsyncTestAgent.fieldWeaving());
        Object instance = loaded.getConstructor().newInstance();
        assertEquals(5, loaded.getMethod("run").invoke(instance), "the method still runs as written");
    }

    @Test
    @DisplayName("a Thread subclass constructor that stores an int into local 0 still loads")
    void aThreadSubclassThatOverwritesLocalZeroLoads() throws Exception {
        String name = "com/example/generated/OverwritingThread";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Thread", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitInsn(Opcodes.ICONST_0);
        init.visitVarInsn(Opcodes.ISTORE, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Thread", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();

        Class<?> loaded = loadWoven(name, writer.toByteArray(),
                ThreadConstructionWeaver.of(ThreadConstructionWeaverTest.Recorder.class));
        loaded.getConstructor().newInstance();
    }

    private static Class<?> loadWoven(String internalName, byte[] bytes,
                                      net.bytebuddy.asm.AsmVisitorWrapper wrapper) throws ClassNotFoundException {
        String dotted = internalName.replace('/', '.');
        ClassFileLocator locator = new ClassFileLocator.Compound(
                new ClassFileLocator.Simple(Map.of(dotted, bytes)),
                ClassFileLocator.ForClassLoader.ofSystemLoader());
        byte[] woven = new ByteBuddy()
                .redefine(TypePool.Default.of(locator).describe(dotted).resolve(), locator)
                .visit(wrapper)
                .make()
                .getBytes();
        return Class.forName(dotted, true, new ByteArrayClassLoader(
                FieldWeavingFlowTest.class.getClassLoader(), Map.of(dotted, woven)));
    }

    private static MethodNode wovenMethod(String method) {
        byte[] woven = new ByteBuddy()
                .redefine(FlowSensitiveFieldSample.class)
                .visit(AsyncTestAgent.fieldWeaving())
                .make()
                .getBytes();
        // Loading verifies it, as the agent's own load would.
        new ByteBuddy().redefine(FlowSensitiveFieldSample.class).visit(AsyncTestAgent.fieldWeaving())
                .make().load(FieldWeavingFlowTest.class.getClassLoader(), ClassLoadingStrategy.Default.CHILD_FIRST);
        ClassNode node = new ClassNode();
        new ClassReader(woven).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
    }

    /** {@return the instructions of {@code method} that execute, labels and frames left out} */
    private static List<AbstractInsnNode> code(String method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode insn : wovenMethod(method).instructions) {
            if (insn.getOpcode() >= 0) {
                code.add(insn);
            }
        }
        return code;
    }

    /** {@return the recordAccess calls in {@code method}, in order} */
    private static List<Access> accesses(String method) {
        List<AbstractInsnNode> code = code(method);
        List<Access> found = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode call && REGISTRY.equals(call.owner)
                    && "recordAccess".equals(call.name)) {
                // ... LDC field, ICONST write, ICONST volatile, LDC tag, ICONST afterRead, ICONST static
                String field = (String) ((LdcInsnNode) code.get(i - 6)).cst;
                int tag = (Integer) ((LdcInsnNode) code.get(i - 3)).cst;
                boolean after = ((InsnNode) code.get(i - 2)).getOpcode() == Opcodes.ICONST_1;
                found.add(new Access(field, tag, after));
            }
        }
        return found;
    }

    /** {@return the fields {@code method} reports published by a volatile write, in order} */
    private static List<String> published(String method) {
        List<AbstractInsnNode> code = code(method);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i) instanceof MethodInsnNode call && REGISTRY.equals(call.owner)
                    && "publishedByVolatile".equals(call.name)) {
                found.add((String) ((LdcInsnNode) code.get(i - 1)).cst);
            }
        }
        return found;
    }
}
