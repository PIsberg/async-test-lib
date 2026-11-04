package se.deversity.asynctest.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides, for each atomic store in a class's own bytes, whether the stored value was built from an
 * atomic read in the same method (#1187). A store from a value that never came from a read, such as
 * {@code gauge.set(queue.size())} after a logged {@code gauge.get()}, cannot lose that read's update.
 *
 * <p>The analysis is a taint pass: every value on the operand stack or in a local carries one bit,
 * set by a read of an {@code AtomicInteger} or {@code AtomicLong} and carried through every operation
 * that consumes it. Types and sizes come from ASM's {@link BasicInterpreter}, so {@code DUP2} and
 * {@code POP2} see the same words the JVM does. It is conservative where it can err: a value derived
 * on one path into a merge stays derived, and a read of any atomic taints a store, whichever atomic it
 * stores to, so "not derived" is only given when no read can reach the stored value.
 *
 * <p>It works on the bytes of the class alone, through unshaded ASM, so it can run on a class before
 * the weaver touches it. A method the analysis cannot process is reported as derived, which keeps the
 * finding the rule reported before this analysis existed.
 */
final class AtomicStoreTaintAnalysis {

    private static final String ATOMIC_INTEGER = "java/util/concurrent/atomic/AtomicInteger";
    private static final String ATOMIC_LONG = "java/util/concurrent/atomic/AtomicLong";

    /**
     * One atomic store.
     *
     * @param method    the method's name and descriptor
     * @param insnIndex the store's index in that method's instruction list
     * @param owner     the atomic's class
     * @param derived   whether the stored value was built from an atomic read in the same method
     */
    record StoreVerdict(String method, int insnIndex, String owner, boolean derived) {}

    private AtomicStoreTaintAnalysis() {}

    /**
     * {@return a verdict for every atomic store in the class, in method order}
     *
     * @param classBytes the class file's bytes, as the class loader supplied them
     */
    static List<StoreVerdict> analyze(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        List<StoreVerdict> verdicts = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (m.instructions.size() == 0) {
                continue;
            }
            analyzeMethod(cn.name, m, verdicts);
        }
        return verdicts;
    }

    private static void analyzeMethod(String owner, MethodNode m, List<StoreVerdict> out) {
        Frame<Taint>[] frames;
        try {
            frames = new Analyzer<>(new TaintInterpreter()).analyze(owner, m);
        } catch (AnalyzerException e) {
            // Unanalysable: keep the verdict the rule gave before this analysis existed.
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn instanceof MethodInsnNode call && isAtomicStore(call)) {
                    out.add(new StoreVerdict(m.name + m.desc, i, call.owner, true));
                }
            }
            return;
        }
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (!(insn instanceof MethodInsnNode call) || !isAtomicStore(call) || frames[i] == null) {
                continue;
            }
            Frame<Taint> before = frames[i];
            Taint stored = before.getStack(before.getStackSize() - 1);
            out.add(new StoreVerdict(m.name + m.desc, i, call.owner, stored != null && stored.derived()));
        }
    }

    /** A {@code set}-family call on an atomic int or long: the stored value is its one argument. */
    private static boolean isAtomicStore(MethodInsnNode call) {
        return isAtomicLongOrInt(call.owner) && switch (call.name) {
            case "set", "lazySet", "setRelease", "setOpaque" -> true;
            default -> false;
        };
    }

    /** A read of an atomic int or long: a {@code get} variant, whose result a store may build on. */
    private static boolean isAtomicRead(MethodInsnNode call) {
        return isAtomicLongOrInt(call.owner) && call.name.startsWith("get");
    }

    private static boolean isAtomicLongOrInt(String owner) {
        return ATOMIC_INTEGER.equals(owner) || ATOMIC_LONG.equals(owner);
    }

    /**
     * A value as ASM's basic analysis sees it (its type and size), and whether it was built from an
     * atomic read.
     */
    private record Taint(BasicValue type, boolean derived) implements Value {
        @Override
        public int getSize() {
            return type.getSize();
        }
    }

    /**
     * The taint rules over ASM's basic types: each operation is delegated to {@link BasicInterpreter}
     * for the type and size of its result, and the taint is what the operation's inputs carried, or
     * the read itself for an atomic {@code get}.
     */
    private static final class TaintInterpreter extends Interpreter<Taint> {

        private final BasicInterpreter basic = new BasicInterpreter();

        TaintInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public @Nullable Taint newValue(Type type) {
            BasicValue value = basic.newValue(type);
            return value == null ? null : new Taint(value, false);
        }

        @Override
        public Taint newOperation(AbstractInsnNode insn) throws AnalyzerException {
            return new Taint(basic.newOperation(insn), false);
        }

        @Override
        public Taint copyOperation(AbstractInsnNode insn, Taint value) throws AnalyzerException {
            return new Taint(basic.copyOperation(insn, value.type()), value.derived());
        }

        @Override
        public Taint unaryOperation(AbstractInsnNode insn, Taint value) throws AnalyzerException {
            return new Taint(basic.unaryOperation(insn, value.type()), value.derived());
        }

        @Override
        public Taint binaryOperation(AbstractInsnNode insn, Taint value1, Taint value2)
                throws AnalyzerException {
            return new Taint(basic.binaryOperation(insn, value1.type(), value2.type()),
                    value1.derived() || value2.derived());
        }

        @Override
        public Taint ternaryOperation(AbstractInsnNode insn, Taint value1, Taint value2, Taint value3)
                throws AnalyzerException {
            return new Taint(basic.ternaryOperation(insn, value1.type(), value2.type(), value3.type()),
                    value1.derived() || value2.derived() || value3.derived());
        }

        @Override
        public @Nullable Taint naryOperation(AbstractInsnNode insn, List<? extends Taint> values)
                throws AnalyzerException {
            List<BasicValue> types = new ArrayList<>(values.size());
            boolean derived = false;
            for (Taint value : values) {
                types.add(value.type());
                derived |= value.derived();
            }
            BasicValue result = basic.naryOperation(insn, types);
            if (result == null) {
                return null;
            }
            if (insn instanceof MethodInsnNode call && isAtomicRead(call)) {
                derived = true;
            }
            return new Taint(result, derived);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, Taint value, Taint expected)
                throws AnalyzerException {
            basic.returnOperation(insn, value.type(), expected.type());
        }

        @Override
        public Taint merge(Taint value1, Taint value2) {
            return new Taint(basic.merge(value1.type(), value2.type()),
                    value1.derived() || value2.derived());
        }
    }
}
