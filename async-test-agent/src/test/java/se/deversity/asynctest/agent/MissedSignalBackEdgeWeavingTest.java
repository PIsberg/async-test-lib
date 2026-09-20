package se.deversity.asynctest.agent;

import com.example.agentfixture.WaitLoopShapesSample;
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
 * Which wait shapes get a loop back-edge mark, read off the woven bytecode (#707).
 *
 * <p>The marks decide whether {@code MissedSignalDetector} reads a wait as a predicate loop's or as
 * the missed-signal bug, and the rule has two halves that no runtime fixture can separate. A wait
 * whose loop never comes back over it is reported whether or not the back-edge is marked, because
 * nothing ever reaches the mark; and the shape the second half exists for,
 * {@link WaitLoopShapesSample#endlessWait}, does not terminate, so it cannot be a fixture at all.
 * Counting the emitted {@code loopBackEdge} calls sees the decision directly.
 *
 * <p>The two halves, each with the shape that fails without it:
 *
 * <ul>
 *   <li>the back-edge is an unconditional {@code goto}. javac puts a {@code while} loop's test at
 *       the top and closes the loop with a {@code goto}; {@code do { wait(); } while (!ready)}
 *       closes it with the test itself. Without this, {@code doWhileLoop} is marked.</li>
 *   <li>a conditional jump stands between the loop's head and the wait, so the thread reads
 *       something before it blocks. Without this, {@code continueLoop} and {@code endlessWait}
 *       are marked.</li>
 * </ul>
 *
 * <p>The runtime consequences are pinned by {@code LoopWaitHandOffBean} and its siblings; this is
 * the gate that keeps the weaver's own answer honest when a shape is added.
 */
class MissedSignalBackEdgeWeavingTest {

    private static Map<String, Integer> marksByMethod;

    @BeforeAll
    static void weaveTheShapes() {
        byte[] woven = new ByteBuddy()
                .redefine(WaitLoopShapesSample.class)
                .visit(CollectionAccessWeaver.monitorSubstitutions(AgentMonitorHooks.class).get(0))
                .make()
                .getBytes();

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
        marksByMethod = counts;
    }

    @Test
    @DisplayName("a predicate loop around a wait is marked, in the same method and across one")
    void predicateLoopsAreMarked() {
        assertEquals(1, marksByMethod.get("whileLoop"),
                "while (!ready) { ...; wait(); } is the shape a correct wait belongs in: its "
                        + "back-edge is the goto that closes the loop, and the predicate is read "
                        + "before the wait. Marks were " + marksByMethod);
        assertEquals(1, marksByMethod.get("crossMethodLoop"),
                "the loop is here and the wait is in awaitOnce, which is declared afterwards. "
                        + "Recognising it is the whole reason the monitor wrapper buffers a class "
                        + "before emitting it; a single pass marks nothing. Marks were "
                        + marksByMethod);
    }

    @Test
    @DisplayName("a wait entered before the predicate is read is not marked, in any shape")
    void waitsAheadOfTheirPredicateAreNotMarked() {
        assertEquals(0, marksByMethod.get("doWhileLoop"),
                "do { ...; wait(); } while (!ready) enters wait before it has read ready, so a "
                        + "notify nobody heard strands the first wait of the round. Its back-edge "
                        + "is the predicate test, a conditional jump, not a goto. Marks were "
                        + marksByMethod);
        assertEquals(0, marksByMethod.get("doWhileAlwaysWaits"),
                "the same shape with the deadline clamping the wait instead of guarding it, "
                        + "which is what DoWhileWaitHandOffBean runs so that it reaches wait on "
                        + "every run. The ternary is a conditional jump in front of the wait, so "
                        + "only the goto rule refuses this one. Marks were " + marksByMethod);
        assertEquals(0, marksByMethod.get("continueLoop"),
                "a loop closed by continue does have a goto back-edge, but nothing is read "
                        + "between the loop's head and the wait. Marks were " + marksByMethod);
        assertEquals(0, marksByMethod.get("endlessWait"),
                "while (true) { wait(); } has the goto and no predicate at all. Nothing runs this "
                        + "shape, which is why the gate reads the bytecode. Marks were "
                        + marksByMethod);
        assertEquals(0, marksByMethod.get("ifGuarded"),
                "if (!ready) wait() has no backward jump: the case the mark exists to tell apart. "
                        + "Marks were " + marksByMethod);
        assertEquals(0, marksByMethod.get("awaitOnce"),
                "the helper holds the wait and no loop; the mark belongs to its caller. Marks "
                        + "were " + marksByMethod);
    }
}
