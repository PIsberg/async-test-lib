package se.deversity.asynctest.agent;

import com.example.agentfixture.WideOperandSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Category-2 operands survive every weaver the agent installs, together (#1147).
 *
 * <p>The weavers keep frames valid by inserting only stack-neutral, branch-free sequences, which is
 * what lets them use {@code COMPUTE_MAXS} instead of {@code COMPUTE_FRAMES}. A {@code long} or
 * {@code double} is the case that breaks such a sequence quietly in review and loudly at run time:
 * the copy instructions for a two-slot value are different ones ({@code DUP2_X1}, {@code DUP_X2},
 * {@code POP2}), and the wrong one is a {@code VerifyError} the first time the class links, in a
 * user's build, with the agent attached.
 *
 * <p>{@link WideOperandSample} puts wide and narrow values, volatile and plain, static and instance,
 * in every place a weaver inserts code. It is woven here with the exact chain {@code install} uses
 * with {@code fields=true,collections=true} (the field weaver, then
 * {@code AsyncTestAgent.collectionSubstitutions()} read reflectively so the two cannot drift),
 * loaded child-first so the JVM verifies it, and run beside an unwoven copy: verifying is not
 * enough, the woven class must also compute the same answer.
 */
class WideOperandWeavingTest {

    @Test
    @DisplayName("a class full of wide-operand shapes verifies and computes the same answer woven")
    void wideOperandsSurviveTheFullWeaverChain() throws Exception {
        Class<?> plain = load(new ByteBuddy().redefine(WideOperandSample.class));
        DynamicType.Builder<?> chain = new ByteBuddy().redefine(WideOperandSample.class)
                .visit(FieldAccessWeaver.visitor());
        for (AsmVisitorWrapper substitution : agentSubstitutions()) {
            chain = chain.visit(substitution);
        }
        DynamicType.Unloaded<?> unloaded = chain.make();
        byte[] wovenBytes = unloaded.getBytes();
        Class<?> woven = unloaded.load(WideOperandWeavingTest.class.getClassLoader(),
                ClassLoadingStrategy.Default.CHILD_FIRST).getLoaded();

        List<String> volatileHooks = hookCalls(wovenBytes, "volatile");
        assertTrue(volatileHooks.stream().anyMatch(h -> h.contains("volatileStore") && h.contains(";J"))
                        && volatileHooks.stream().anyMatch(h -> h.contains("volatileStore") && h.contains(";D"))
                        && volatileHooks.stream().anyMatch(h -> h.contains("volatileLoad") && h.contains(";J"))
                        && volatileHooks.stream().anyMatch(h -> h.contains("volatileLoad") && h.contains(";D")),
                "the weaver did not reach the wide volatile accesses, so nothing below tests them: "
                        + volatileHooks);
        assertEquals(3, hookCalls(wovenBytes, PersistenceHandleWeaver.USED_HOOK).size(),
                "the persistence weaver did not reach the three EntityManager calls beside the wide "
                        + "values, so the test does not cover its receiver copies");

        for (long x : new long[] {2, 5, -3, Long.MAX_VALUE / 4}) {
            double y = x * 0.75;
            assertEquals(run(plain, x, y), run(woven, x, y),
                    "the woven class verified but computed a different answer for x=" + x
                            + ": a copy sequence moved or dropped a value");
        }
    }

    private static Class<?> load(DynamicType.Builder<?> builder) {
        return builder.make().load(WideOperandWeavingTest.class.getClassLoader(),
                ClassLoadingStrategy.Default.CHILD_FIRST).getLoaded();
    }

    /** What {@code install} adds after the field weaver when {@code collections=true}. */
    @SuppressWarnings("unchecked")
    private static List<AsmVisitorWrapper> agentSubstitutions() throws Exception {
        Method all = AsyncTestAgent.class.getDeclaredMethod("collectionSubstitutions");
        all.setAccessible(true);
        List<AsmVisitorWrapper> substitutions = (List<AsmVisitorWrapper>) all.invoke(null);
        assertTrue(substitutions.size() >= 10,
                "the agent's substitution chain looks truncated: " + substitutions.size());
        return substitutions;
    }

    private static String run(Class<?> sample, long x, double y) throws Exception {
        sample.getMethod("resetStatics").invoke(null);
        Object instance = sample.getConstructor().newInstance();
        return (String) sample.getMethod("exercise", long.class, double.class)
                .invoke(instance, x, y);
    }

    /** {@return the name and descriptor of every call whose name starts with {@code prefix}} */
    private static List<String> hookCalls(byte[] classFile, String prefix) {
        List<String> calls = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String hook,
                                                String hookDescriptor, boolean isInterface) {
                        if (hook.startsWith(prefix)) {
                            calls.add(hook + hookDescriptor);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return calls;
    }
}
