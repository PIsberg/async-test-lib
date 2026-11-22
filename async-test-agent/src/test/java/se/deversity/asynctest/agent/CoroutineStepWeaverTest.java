package se.deversity.asynctest.agent;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What {@link CoroutineStepWeaver} rewrites (#1007): the {@code invokeSuspend} call in
 * {@code BaseContinuationImpl.resumeWith}, and nothing else. The class is built here with the
 * shape kotlin stdlib compiles to, since the agent's tests have no Kotlin on the class path.
 */
class CoroutineStepWeaverTest {

    private static final String BASE = "kotlin/coroutines/jvm/internal/BaseContinuationImpl";
    private static final String HOOKS = "se/deversity/asynctest/AgentCoroutineHooks";

    @Test
    @DisplayName("resumeWith's invokeSuspend becomes the hook, with the same stack")
    void resumeWithCallsTheHook() {
        Map<String, List<String>> calls = weave(BASE);

        assertEquals(List.of("INVOKESTATIC " + HOOKS + ".coroutineStep"
                        + CoroutineStepWeaver.HOOK_DESCRIPTOR), calls.get("resumeWith"),
                "the one call that runs a step goes through the hook");
        assertEquals(List.of("INVOKEVIRTUAL " + BASE + ".invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;"),
                calls.get("other"), "a call outside resumeWith is left alone");
    }

    @Test
    @DisplayName("a refused hook class fails at install, not in every coroutine")
    void aLibraryWithoutTheHookIsRefused() {
        assertThrows(IllegalStateException.class, () -> CoroutineStepWeaver.of(Object.class));
    }

    /** Builds the stdlib-shaped class, weaves it, and returns each method's calls. */
    private static Map<String, List<String>> weave(String name) {
        ClassWriter source = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        source.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null,
                "java/lang/Object", null);
        source.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "invokeSuspend",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null).visitEnd();
        for (String method : List.of("resumeWith", "other")) {
            MethodVisitor code = source.visitMethod(Opcodes.ACC_PUBLIC, method, "(Ljava/lang/Object;)V",
                    null, null);
            code.visitCode();
            code.visitVarInsn(Opcodes.ALOAD, 0);
            code.visitVarInsn(Opcodes.ALOAD, 1);
            code.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, "invokeSuspend",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
            code.visitInsn(Opcodes.POP);
            code.visitInsn(Opcodes.RETURN);
            code.visitMaxs(0, 0);
            code.visitEnd();
        }
        source.visitEnd();

        ClassWriter woven = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(source.toByteArray()).accept(
                weaver().visitor(woven), 0);

        Map<String, List<String>> calls = new LinkedHashMap<>();
        new ClassReader(woven.toByteArray()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor,
                                             String signature, String[] exceptions) {
                List<String> seen = new ArrayList<>();
                calls.put(method, seen);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String desc,
                                                boolean isInterface) {
                        seen.add((opcode == Opcodes.INVOKESTATIC ? "INVOKESTATIC " : "INVOKEVIRTUAL ")
                                + owner + "." + called + desc);
                    }
                };
            }
        }, 0);
        return calls;
    }

    private static CoroutineStepWeaver weaver() {
        try {
            return CoroutineStepWeaver.of(Class.forName(HOOKS.replace('/', '.')));
        } catch (ClassNotFoundException e) {
            throw new AssertionError("the library is on the agent's test class path", e);
        }
    }
}
