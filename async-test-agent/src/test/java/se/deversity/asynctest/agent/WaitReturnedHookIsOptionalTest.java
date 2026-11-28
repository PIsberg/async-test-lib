package se.deversity.asynctest.agent;

import com.example.agentfixture.WaitLoopShapesSample;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wait-returned call is optional (#1555): a library older than the agent has no
 * {@code loopWaitReturned}, and the weave must go on without the call rather than refuse the table
 * or emit a call to a method that is not there. The back-edge marks are woven as before.
 */
class WaitReturnedHookIsOptionalTest {

    /** The monitor table's hooks as a library before #1555 had them: no loopWaitReturned. */
    @SuppressWarnings("unused") // resolved by reflection, never called: the woven class is only read
    public static final class MonitorHooksWithoutWaitReturned {
        private MonitorHooksWithoutWaitReturned() {
        }

        public static void monitorWait(Object receiver) { }

        public static void monitorWait(Object receiver, long timeoutMillis) { }

        public static void monitorWait(Object receiver, long timeoutMillis, int nanos) { }

        public static void monitorNotify(Object receiver) { }

        public static void monitorNotifyAll(Object receiver) { }

        public static void conditionAwait(Condition receiver) { }

        public static boolean conditionAwait(Condition receiver, long time, TimeUnit unit) {
            return false;
        }

        public static long conditionAwaitNanos(Condition receiver, long nanosTimeout) {
            return 0L;
        }

        public static void conditionAwaitUninterruptibly(Condition receiver) { }

        public static boolean conditionAwaitUntil(Condition receiver, Date deadline) {
            return false;
        }

        public static void conditionSignal(Condition receiver) { }

        public static void conditionSignalAll(Condition receiver) { }

        public static void loopBackEdge() { }

        public static boolean conditionAwaitResultTested(boolean signalled) {
            return signalled;
        }
    }

    @Test
    @DisplayName("a library without loopWaitReturned is woven without the call, marks unchanged (#1555)")
    void aLibraryWithoutTheHookIsWovenWithoutTheCall() {
        byte[] woven = new ByteBuddy()
                .redefine(WaitLoopShapesSample.class)
                .visit(CollectionAccessWeaver.monitorSubstitutions(MonitorHooksWithoutWaitReturned.class).get(0))
                .make()
                .getBytes();
        Map<String, Integer> marks = new HashMap<>();
        Map<String, Integer> returns = new HashMap<>();
        new ClassReader(woven).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method,
                                                String desc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && "loopBackEdge".equals(method)) {
                            marks.merge(name, 1, Integer::sum);
                        }
                        if (opcode == Opcodes.INVOKESTATIC && "loopWaitReturned".equals(method)) {
                            returns.merge(name, 1, Integer::sum);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        assertEquals(Map.of(), returns, "no call to a hook the library does not have");
        assertEquals(1, marks.getOrDefault("whileLoop", 0), "the back-edge marks are woven as before: " + marks);
        assertEquals(1, marks.getOrDefault("deadlineLoopWithMoreBody", 0), "marks were " + marks);
    }
}
