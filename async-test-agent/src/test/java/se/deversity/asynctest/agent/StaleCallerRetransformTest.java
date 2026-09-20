package se.deversity.asynctest.agent;

import com.example.agentfixture.StaleCallerSample;
import com.example.agentfixture.StaleWaitHelper;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentMonitorHooks;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A caller woven before the class its wait helper lives in is woven again once that class
 * registers, so the mark no longer depends on the order the two happen to load (#715).
 *
 * <p><strong>Why this order and not the other.</strong> {@code CrossClassWaitHelperWeavingTest}
 * weaves the helpers first, which is the order that always worked: the weaver records a class's
 * waiting methods when that class is woven and reads them when a caller is woven. Load-time
 * weaving delivers the other order almost every time. A class named only inside a method body is
 * resolved lazily, on first execution of the instruction that names it, so when the caller is
 * woven at load its helper has not been loaded at all. Measured before this fix, same JVM, same
 * two classes: caller first gave 0 marks on both cross-class loops, helpers first gave 1 each.
 *
 * <p>Nothing about that is visible to a user. The loop goes unmarked, the wait reads as
 * {@code if (!ready) wait()}, and a correct bounded poll is reported as {@code MISSED_SIGNAL}.
 *
 * <p><strong>What is asserted.</strong> The end behaviour, through the seam the production path
 * uses: when a newly woven class registers a waiting method, the weaver hands the internal names
 * of the callers that could not resolve it to a {@link StaleCallerRetransformer.Retransformer}.
 * In the JVM that is {@code Instrumentation.retransformClasses}; here it re-weaves in process and
 * the test reads the marks off what comes back. Substituting the action rather than asserting on
 * a queue keeps the assertion on "the caller ends up marked", which is the thing that matters.
 */
class StaleCallerRetransformTest {

    private static final String SAMPLE =
            StaleCallerSample.class.getName().replace('.', '/');

    @AfterEach
    void restoreTheProductionAction() {
        StaleCallerRetransformer.useRetransformer(null);
    }

    @Test
    @DisplayName("a caller woven before its helper is re-woven and marked once the helper registers")
    void aStaleCallerIsRewovenWhenItsHelperRegisters() {
        Set<String> asked = new LinkedHashSet<>();
        StaleCallerRetransformer.useRetransformer(asked::addAll);

        // The order load-time weaving delivers: the caller first, when nothing about its helper
        // is known yet.
        Map<String, Integer> beforeHelpers = countMarks(weave(StaleCallerSample.class));
        assertEquals(0, beforeHelpers.get("loopThroughHelper"),
                "the premise of this test: with the helper unknown, the loop cannot be marked. "
                        + "If this is already 1 the weaver learned the helper some other way and "
                        + "the rest of the test proves nothing. Marks were " + beforeHelpers);

        // Loading the helper classes is what registers their waiting methods.
        weave(StaleWaitHelper.class);

        assertTrue(asked.contains(SAMPLE),
                "registering a waiting method has to hand back the callers that could not resolve "
                        + "it, or nothing re-weaves them and the mark depends on load order for "
                        + "good. Classes handed back were " + asked);

        // What the production action does with that name, done here in process.
        Map<String, Integer> afterHelpers = countMarks(weave(StaleCallerSample.class));
        assertEquals(1, afterHelpers.get("loopThroughHelper"),
                "re-weaving the caller after its helper registered must produce the mark the "
                        + "helper-first order produces. Marks were " + afterHelpers);
    }

    /** {@return the class woven with the monitor table, which is the table carrying the hook} */
    private static byte[] weave(Class<?> type) {
        return new ByteBuddy()
                .redefine(type)
                .visit(CollectionAccessWeaver.monitorSubstitutions(AgentMonitorHooks.class).get(0))
                .make()
                .getBytes();
    }

    /** {@return how many loop back-edge marks the woven class carries, per method} */
    private static Map<String, Integer> countMarks(byte[] woven) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        new ClassReader(woven).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                counts.putIfAbsent(name, 0);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && "loopBackEdge".equals(method) && "()V".equals(desc)) {
                            counts.merge(name, 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return counts;
    }
}
