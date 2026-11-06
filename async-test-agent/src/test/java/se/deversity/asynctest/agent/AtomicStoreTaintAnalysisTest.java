package se.deversity.asynctest.agent;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The side analysis reads a class's own bytes and says, for each atomic store, whether the stored
 * value was built from an atomic read (#1187). A store from an unrelated size is not a lost update;
 * one built from the read is.
 */
class AtomicStoreTaintAnalysisTest {

    private static byte[] classBytes(String internalName) throws IOException {
        try (InputStream in = AtomicStoreTaintAnalysisTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertTrue(in != null, "fixture class must be on the test class path: " + internalName);
            return in.readAllBytes();
        }
    }

    private static AtomicStoreTaintAnalysis.StoreVerdict onlyStoreIn(List<AtomicStoreTaintAnalysis.StoreVerdict> all,
                                                                    String method) {
        List<AtomicStoreTaintAnalysis.StoreVerdict> here = all.stream()
                .filter(v -> v.method().startsWith(method + "(")).toList();
        assertEquals(1, here.size(), "exactly one atomic store in " + method + ": " + all);
        return here.get(0);
    }

    @Test
    void aStoreFromAnUnrelatedSizeIsNotDerivedFromTheRead() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/ReadThenUnrelatedStoreBean"));

        // The stores in sample: set (#1187), lazySet, and a set from a helper given the read (#1190),
        // each after a read whose value it does not store.
        var stores = verdicts.stream().filter(v -> v.method().startsWith("sample(")).toList();
        assertEquals(3, stores.size(), "set, lazySet and the helper's set in sample: " + verdicts);
        for (var store : stores) {
            assertEquals("java/util/concurrent/atomic/AtomicInteger", store.owner());
            assertFalse(store.derived(),
                    "gauge.set(queue.size()) builds nothing from the gauge's read, so no update is lost");
        }
    }

    @Test
    void aStoreBuiltFromTheReadIsDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/ReadThenDerivedStoreBean"));

        assertTrue(onlyStoreIn(verdicts, "increment").derived(),
                "counter.set(v + 1) with v read from counter is the lost update the rule exists for");
    }

    @Test
    void aTwoWordValueBuiltFromTheReadIsDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertTrue(onlyStoreIn(verdicts, "longIncrement").derived(),
                "total.set(v + 1L) with a long v read from total is derived, and the analysis must keep "
                        + "the long's second word through the add to see it");
    }

    @Test
    void aConstantStoreAfterAnIgnoredReadIsNotDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertFalse(onlyStoreIn(verdicts, "constantAfterAnIgnoredRead").derived(),
                "the read's result is dropped, so set(0) builds on nothing the read returned");
    }

    @Test
    void aReadThatReachesTheStoreOnOnePathIsDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertTrue(onlyStoreIn(verdicts, "readOnOneBranch").derived(),
                "the merge keeps a value derived on any path into it: a lost update on that path is still one");
    }

    @Test
    void aReadOfOneAtomicStoredIntoAnotherIsDerivedByTheConservativeRule() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertTrue(onlyStoreIn(verdicts, "readOfOneAtomicStoredInAnother").derived(),
                "a read of any atomic taints a store into any other: the analysis does not track which atomic");
    }

    @Test
    void aReadPassedToAHelperThatReturnsSomethingElseIsNotDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertFalse(onlyStoreIn(verdicts, "readPassedToAHelperThatIgnoresIt").derived(),
                "gauge.set(sizeAfterNoting(gauge.get())): the helper returns queue.size(), not the read (#1190)");
    }

    @Test
    void aReadPassedToAHelperThatBuildsOnItIsDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertTrue(onlyStoreIn(verdicts, "readPassedToAHelperThatBuildsOnIt").derived(),
                "gauge.set(plusOne(gauge.get())) stores read + 1: the lost update, through a helper");
    }

    @Test
    void aReadInsideAHelperWhoseResultIsStoredIsDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertTrue(onlyStoreIn(verdicts, "readInsideAHelper").derived(),
                "gauge.set(current() + 1) where current() returns gauge.get() builds on the read");
    }

    @Test
    void aReadPassedToAnOverridableMethodStaysDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertTrue(onlyStoreIn(verdicts, "readPassedToAnOverridableMethod").derived(),
                "a subclass may override the callee and return its argument, so this class's bytes "
                        + "cannot clear the store");
    }

    @Test
    void aReadPassedToARecursiveHelperThatNeverReturnsItIsNotDerived() throws IOException {
        var verdicts = AtomicStoreTaintAnalysis.analyze(
                classBytes("com/example/agentfixture/AtomicStoreShapesBean"));

        assertFalse(onlyStoreIn(verdicts, "readPassedToARecursiveHelper").derived(),
                "countDown returns 0 or itself, never its first argument; the summary must settle on that");
    }

    /**
     * The weaver matches each store to its verdict by ordinal within the method, so a store the
     * analysis cannot reach must still get a verdict. javac never emits dead code, so this class is
     * built with ASM: a reachable set, then a second set after a {@code return}.
     */
    @Test
    void aStoreInUnreachableCodeKeepsItsVerdictSoOrdinalsStayAligned() {
        byte[] bytes = classWithDeadStoreAfterReturn();

        var byMethod = AtomicStoreTaintAnalysis.derivedByMethod(bytes);

        assertEquals(List.of(false, true),
                byMethod.get("m(Ljava/util/concurrent/atomic/AtomicInteger;)V"),
                "the reachable constant store is not derived; the dead store gets the conservative verdict");
    }

    private static byte[] classWithDeadStoreAfterReturn() {
        String atomicInteger = "java/util/concurrent/atomic/AtomicInteger";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, "Unreachable", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m",
                "(Ljava/util/concurrent/atomic/AtomicInteger;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, atomicInteger, "set", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, atomicInteger, "set", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
