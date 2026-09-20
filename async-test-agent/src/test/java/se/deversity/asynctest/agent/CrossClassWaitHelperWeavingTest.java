package se.deversity.asynctest.agent;

import com.example.agentfixture.CrossClassWaitLoopSample;
import com.example.agentfixture.MonitorWaitGate;
import com.example.agentfixture.WaitHelperBase;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentMonitorHooks;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A wait helper in another class, reached by a name that is not the waiting class's (#709).
 *
 * <p>Within one class the answer is exact, because the monitor wrapper buffers every method before
 * emitting any of it, and {@code MissedSignalBackEdgeWeavingTest.crossMethodLoop} pins that. Across
 * classes the key is the owner written at the call site, so a helper declared on a supertype or
 * reached through an interface is missed even when its class was woven first. Both failures are
 * silent and land on the same side: the loop goes unmarked, the wait reads as an {@code if}, and a
 * correct bounded poll can be reported. That is one of the two readings keeping
 * {@code MISSED_SIGNAL} at {@code PROMPT}.
 *
 * <p>The weave order here is the one that already worked before this gate: the helper's class is
 * woven first, so nothing about ordering is being tested. What is left is the name. The other half
 * of #709, a caller woven before the class that declares its helper, is not fixable by resolving
 * the call site and is not asserted here; {@code docs/agent/attaching.md} states it.
 */
class CrossClassWaitHelperWeavingTest {

    private static Map<String, Integer> marksByMethod;

    @BeforeAll
    static void weaveTheHelpersThenTheirCaller() {
        // The helpers first, so their waiting methods are registered. A caller woven before them
        // is the ordering limit this gate deliberately leaves alone.
        weave(WaitHelperBase.class);
        weave(MonitorWaitGate.class);
        marksByMethod = countMarks(weave(CrossClassWaitLoopSample.class));
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

    @Test
    @DisplayName("a helper inherited from a supertype is recognised through the call site's type")
    void anInheritedHelperIsResolved() {
        assertEquals(1, marksByMethod.get("loopThroughInheritedHelper"),
                "the call site names WaitHelperSubclass because that is the declared type of the "
                        + "field, and awaitOnce is declared on WaitHelperBase, which is the class "
                        + "that was woven and registered. Matching the name alone misses it and "
                        + "leaves a correct poll reportable. Marks were " + marksByMethod);
    }

    @Test
    @DisplayName("a helper behind an interface is recognised through its implementation")
    void anImplementationBehindAnInterfaceIsResolved() {
        assertEquals(1, marksByMethod.get("loopThroughInterface"),
                "the call site names the WaitGate interface, which declares no body and can never "
                        + "wait; MonitorWaitGate is what waits and what was registered. Resolving "
                        + "this one goes the other way down the hierarchy than the inherited case. "
                        + "Marks were " + marksByMethod);
    }

    @Test
    @DisplayName("a loop around a call that never waits stays unmarked")
    void aSilentHelperIsNotResolved() {
        assertEquals(0, marksByMethod.get("loopThroughSilentHelper"),
                "monitor() is declared on the same supertype as awaitOnce and returns without "
                        + "waiting. Resolving a call site through the hierarchy must stay a "
                        + "question about one method, not about the class it is declared on: "
                        + "marking this would spare a real missed signal. Marks were "
                        + marksByMethod);
    }
}
