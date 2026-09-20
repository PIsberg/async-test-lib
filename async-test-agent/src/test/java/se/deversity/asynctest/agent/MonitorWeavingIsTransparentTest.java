package se.deversity.asynctest.agent;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.AgentMonitorHooks;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class the monitor table does not match comes out of the weaver unchanged (#707).
 *
 * <p>Recognising a loop around a wait in another method costs a buffering pass: the wrapper records
 * every method as replayable actions and plays them back once the class has been read. Every woven
 * class goes through it, including the overwhelming majority that never call {@code wait}, so the
 * buffer has to be a faithful tape. A visit method it fails to record is dropped rather than
 * delegated, and the instruction is gone from the user's class.
 *
 * <p>{@link BufferedMethodReplaysEverythingTest} catches an override that is missing. This catches
 * one that is present and records the wrong thing, which reflection cannot see. The three subjects
 * are chosen for what they put through the buffer: a record, whose {@code ObjectMethods} bootstrap
 * is the constant shape that broke instrumentation before; a method reference, which is an
 * {@code invokedynamic} carrying a {@link Handle}; and a sample full of branches and try/catch.
 */
class MonitorWeavingIsTransparentTest {

    @Test
    @DisplayName("weaving a class with no wait, notify or notifyAll changes none of its instructions")
    void nonMonitorClassesComeOutUnchanged() throws IOException {
        for (Class<?> subject : List.of(com.example.agentfixture.MeasuredSpanRecord.class,
                com.example.agentfixture.ConfinedThroughMethodReferenceBean.class,
                com.example.agentfixture.OfferShapesSample.class)) {
            byte[] woven = new ByteBuddy()
                    .redefine(subject)
                    .visit(CollectionAccessWeaver.monitorSubstitutions(AgentMonitorHooks.class)
                            .get(0))
                    .make()
                    .getBytes();

            Map<String, List<String>> before = instructionsOf(bytesOf(subject));
            Map<String, List<String>> after = instructionsOf(woven);

            assertTrue(before.size() >= 2,
                    subject.getSimpleName() + " has too little in it to be evidence of anything: "
                            + before.keySet());
            assertEquals(before.keySet(), after.keySet(),
                    "the buffer replays every method it was given, in the class it was given it "
                            + "for. " + subject.getSimpleName());
            for (Map.Entry<String, List<String>> method : before.entrySet()) {
                assertEquals(method.getValue(), after.get(method.getKey()),
                        "the monitor table matches nothing in " + subject.getSimpleName() + "."
                                + method.getKey() + ", so the buffering pass is the only thing "
                                + "that touched it and the instructions must come back "
                                + "identical. A difference here is bytecode the buffer dropped or "
                                + "reordered, which is a class that behaves differently only when "
                                + "the agent is attached.");
            }
        }
    }

    private static byte[] bytesOf(Class<?> subject) throws IOException {
        String resource = subject.getName().replace('.', '/') + ".class";
        try (InputStream in = subject.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no class file for " + subject);
            }
            return in.readAllBytes();
        }
    }

    /**
     * {@return every method's visit calls, as text, keyed by name and descriptor}
     *
     * <p>Labels are numbered in the order they are visited rather than by identity, so two readings
     * of equivalent bytecode compare equal while a moved jump target does not. Frames and debug
     * information are skipped: the writer recomputes them, and neither is where a dropped
     * instruction shows up.
     */
    private static Map<String, List<String>> instructionsOf(byte[] classFile) {
        Map<String, List<String>> perMethod = new HashMap<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                List<String> calls = new ArrayList<>();
                perMethod.put(name + descriptor, calls);
                return new InstructionText(calls);
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return perMethod;
    }

    /** Appends one line per visit call, with labels numbered in visit order. */
    private static final class InstructionText extends MethodVisitor {

        private final List<String> calls;
        private final Map<Label, Integer> numbering = new HashMap<>();

        InstructionText(List<String> calls) {
            super(Opcodes.ASM9);
            this.calls = calls;
        }

        private String at(Label label) {
            return "L" + numbering.computeIfAbsent(label, unused -> numbering.size());
        }

        @Override
        public void visitInsn(int opcode) {
            calls.add("insn " + opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            calls.add("int " + opcode + " " + operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            calls.add("var " + opcode + " " + varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            calls.add("type " + opcode + " " + type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            calls.add("field " + opcode + " " + owner + "." + name + descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            calls.add("call " + opcode + " " + owner + "." + name + descriptor + " " + isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                           Object... arguments) {
            calls.add("indy " + name + descriptor + " " + bootstrap + " "
                    + Arrays.toString(arguments));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            calls.add("jump " + opcode + " " + at(label));
        }

        @Override
        public void visitLabel(Label label) {
            calls.add("label " + at(label));
        }

        @Override
        public void visitLdcInsn(Object value) {
            calls.add("ldc " + value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            calls.add("iinc " + varIndex + " " + increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            StringBuilder text = new StringBuilder("tableswitch ").append(min).append(" ")
                    .append(max).append(" ").append(at(dflt));
            for (Label label : labels) {
                text.append(" ").append(at(label));
            }
            calls.add(text.toString());
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            StringBuilder text = new StringBuilder("lookupswitch ").append(at(dflt)).append(" ")
                    .append(Arrays.toString(keys));
            for (Label label : labels) {
                text.append(" ").append(at(label));
            }
            calls.add(text.toString());
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            calls.add("multianewarray " + descriptor + " " + numDimensions);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            calls.add("trycatch " + at(start) + " " + at(end) + " " + at(handler) + " " + type);
        }
    }
}
