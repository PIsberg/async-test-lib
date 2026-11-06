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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides, for each atomic store in a class's own bytes, whether the stored value was built from an
 * atomic read (#1187). A store from a value that never came from a read, such as
 * {@code gauge.set(queue.size())} after a logged {@code gauge.get()}, cannot lose that read's update.
 *
 * <p>The analysis is a taint pass: every value on the operand stack or in a local carries the set of
 * sources it was built from, a read of an {@code AtomicInteger} or {@code AtomicLong} or one of the
 * method's own arguments, carried through every operation that consumes it. Types and sizes come from
 * ASM's {@link BasicInterpreter}, so {@code DUP2} and {@code POP2} see the same words the JVM does. It
 * is conservative where it can err: a value derived on one path into a merge stays derived, and a read
 * of any atomic taints a store, whichever atomic it stores to, so "not derived" is only given when no
 * read can reach the stored value.
 *
 * <p>A call into a method of the same class that no subclass can replace (static, private, final, a
 * constructor, or any method of a final class) is followed through a summary of that method: which of
 * its arguments reach its return value, and whether a read of its own does (#1190). The summaries are
 * the least fixed point over the class, so recursion settles. Any other call passes the sources of
 * every argument to its result, as before.
 *
 * <p>It works on the bytes of the class alone, through unshaded ASM, so it can run on a class before
 * the weaver touches it. A method the analysis cannot process is reported as derived, which keeps the
 * finding the rule reported before this analysis existed, and a call into one is taken to return a
 * read built from every argument.
 */
final class AtomicStoreTaintAnalysis {

    private static final String ATOMIC_INTEGER = "java/util/concurrent/atomic/AtomicInteger";
    private static final String ATOMIC_LONG = "java/util/concurrent/atomic/AtomicLong";

    /** The source bit of an atomic read. Bit {@code 1 + i} is argument {@code i}, receiver first. */
    private static final long READ = 1L;
    /** The first argument ordinal that shares the last bit with every argument after it. */
    private static final int OVERFLOW_ARG = 62;
    /** The summary of a callee the analysis could not process: a read and every argument. */
    private static final long UNKNOWN = -1L;
    /**
     * Rounds after which the summaries are abandoned. A round that changes nothing ends the loop, and
     * a summary only gains bits, so this is never reached in practice; it bounds the work regardless.
     */
    private static final int MAX_ROUNDS = 64;

    /**
     * One atomic store.
     *
     * @param method    the method's name and descriptor
     * @param insnIndex the store's index in that method's instruction list
     * @param owner     the atomic's class
     * @param derived   whether the stored value was built from an atomic read
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
        if (!hasAtomicStore(cn)) {
            return verdicts;
        }
        Map<String, Long> summaries = summarize(cn);
        for (MethodNode m : cn.methods) {
            if (m.instructions.size() == 0) {
                continue;
            }
            analyzeMethod(cn, m, summaries, verdicts);
        }
        return verdicts;
    }

    private static boolean hasAtomicStore(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && isAtomicStore(call.owner, call.name, call.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@return for each method of the class a call can be followed into, the sources that reach its
     * return value, keyed by {@code name + descriptor}}
     *
     * <p>Every summary starts empty and only gains bits, round by round, until a round changes none:
     * the least fixed point, so a recursive helper that never returns its argument is not taken to.
     */
    private static Map<String, Long> summarize(ClassNode cn) {
        List<MethodNode> followable = new ArrayList<>();
        Map<String, Long> summaries = new HashMap<>();
        for (MethodNode m : cn.methods) {
            if (followable(cn, m) && Type.getReturnType(m.desc).getSort() != Type.VOID) {
                followable.add(m);
                summaries.put(m.name + m.desc, 0L);
            }
        }
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean changed = false;
            for (MethodNode m : followable) {
                String key = m.name + m.desc;
                long before = summaries.getOrDefault(key, 0L);
                if (before == UNKNOWN) {
                    continue;
                }
                long after;
                try {
                    TaintInterpreter interpreter = new TaintInterpreter(cn, m, summaries);
                    new Analyzer<>(interpreter).analyze(cn.name, m);
                    after = before | interpreter.returned;
                } catch (AnalyzerException e) {
                    after = UNKNOWN;
                }
                if (after != before) {
                    summaries.put(key, after);
                    changed = true;
                }
            }
            if (!changed) {
                return summaries;
            }
        }
        return Map.of(); // never settled: follow no call, the rule before #1190
    }

    /** {@return whether a call into the method from this class can only run these bytes} */
    private static boolean followable(ClassNode cn, MethodNode m) {
        if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || m.instructions.size() == 0) {
            return false;
        }
        return (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0
                || (cn.access & Opcodes.ACC_FINAL) != 0
                || "<init>".equals(m.name);
    }

    private static void analyzeMethod(ClassNode cn, MethodNode m, Map<String, Long> summaries,
                                      List<StoreVerdict> out) {
        Frame<Taint>[] frames;
        try {
            frames = new Analyzer<>(new TaintInterpreter(cn, m, summaries)).analyze(cn.name, m);
        } catch (AnalyzerException e) {
            // Unanalysable: keep the verdict the rule gave before this analysis existed.
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn instanceof MethodInsnNode call && isAtomicStore(call.owner, call.name, call.desc)) {
                    out.add(new StoreVerdict(m.name + m.desc, i, call.owner, true));
                }
            }
            return;
        }
        for (int i = 0; i < m.instructions.size(); i++) {
            AbstractInsnNode insn = m.instructions.get(i);
            if (!(insn instanceof MethodInsnNode call) || !isAtomicStore(call.owner, call.name, call.desc)) {
                continue;
            }
            // A store the analysis never reaches (dead code) gets the conservative verdict, not no
            // verdict: the weaver matches stores to verdicts by ordinal, so a missing one would
            // hand every later store in the method the wrong verdict.
            Frame<Taint> before = frames[i];
            Taint stored = before == null ? null : before.getStack(before.getStackSize() - 1);
            out.add(new StoreVerdict(m.name + m.desc, i, call.owner, stored == null || stored.derived()));
        }
    }

    /**
     * {@return whether the call is a plain store to an {@code AtomicInteger} or {@code AtomicLong}}
     *
     * <p>This is the one predicate both the analysis and the weaver count stores with, so the
     * ordinal of a store within its method means the same thing to each.
     *
     * @param owner      the call's owner, in internal form
     * @param name       the method name
     * @param descriptor the method descriptor
     */
    static boolean isAtomicStore(String owner, String name, String descriptor) {
        if (!isAtomicLongOrInt(owner)) {
            return false;
        }
        String expected = ATOMIC_LONG.equals(owner) ? "(J)V" : "(I)V";
        return expected.equals(descriptor) && switch (name) {
            case "set", "lazySet", "setPlain", "setOpaque", "setRelease" -> true;
            default -> false;
        };
    }

    /**
     * {@return, for each method of the class that stores to an atomic, the verdict of each such store
     * in instruction order, keyed by {@code name + descriptor}}
     *
     * <p>The weaver matches a store to its verdict by the store's ordinal within its method, so a
     * method missing from this map, or a list shorter than the method's stores, means "derived".
     *
     * @param classBytes the class file's bytes, as the class loader supplied them
     */
    static Map<String, List<Boolean>> derivedByMethod(byte[] classBytes) {
        Map<String, List<Boolean>> byMethod = new HashMap<>();
        for (StoreVerdict v : analyze(classBytes)) {
            byMethod.computeIfAbsent(v.method(), k -> new ArrayList<>()).add(v.derived());
        }
        return byMethod;
    }

    /** A read of an atomic int or long: a {@code get} variant, whose result a store may build on. */
    private static boolean isAtomicRead(MethodInsnNode call) {
        return isAtomicLongOrInt(call.owner) && call.name.startsWith("get");
    }

    private static boolean isAtomicLongOrInt(String owner) {
        return ATOMIC_INTEGER.equals(owner) || ATOMIC_LONG.equals(owner);
    }

    /** {@return the source bit of argument {@code arg}, receiver first} */
    private static long argBit(int arg) {
        int bit = Math.min(arg, OVERFLOW_ARG) + 1;
        return 1L << bit;
    }

    /**
     * A value as ASM's basic analysis sees it (its type and size), and the sources it was built from:
     * {@link #READ} and the bits of the analysed method's own arguments.
     */
    private record Taint(BasicValue type, long sources) implements Value {
        @Override
        public int getSize() {
            return type.getSize();
        }

        boolean derived() {
            return (sources & READ) != 0;
        }
    }

    /**
     * The taint rules over ASM's basic types: each operation is delegated to {@link BasicInterpreter}
     * for the type and size of its result, and the sources are what the operation's inputs carried, a
     * read for an atomic {@code get}, or, for a call into a summarised method of the class, what its
     * summary says reaches its return value.
     */
    private static final class TaintInterpreter extends Interpreter<Taint> {

        private final BasicInterpreter basic = new BasicInterpreter();
        private final ClassNode cn;
        private final Map<String, Long> summaries;
        /** The argument ordinal held by each local slot on entry, receiver first; -1 for none. */
        private final int[] argOfLocal;
        /** The sources every return of the analysed method has carried so far. */
        long returned;

        TaintInterpreter(ClassNode cn, MethodNode m, Map<String, Long> summaries) {
            super(Opcodes.ASM9);
            this.cn = cn;
            this.summaries = summaries;
            boolean instance = (m.access & Opcodes.ACC_STATIC) == 0;
            Type[] args = Type.getArgumentTypes(m.desc);
            int slots = instance ? 1 : 0;
            for (Type arg : args) {
                slots += arg.getSize();
            }
            argOfLocal = new int[slots];
            Arrays.fill(argOfLocal, -1);
            int local = 0;
            int ordinal = 0;
            if (instance) {
                argOfLocal[0] = 0;
                local = 1;
                ordinal = 1;
            }
            for (Type arg : args) {
                argOfLocal[local] = ordinal;
                ordinal++;
                local += arg.getSize();
            }
        }

        @Override
        public @Nullable Taint newValue(@Nullable Type type) {
            BasicValue value = basic.newValue(type);
            return value == null ? null : new Taint(value, 0L);
        }

        @Override
        public @Nullable Taint newParameterValue(boolean isInstanceMethod, int local, Type type) {
            Taint value = newValue(type);
            if (value == null || local >= argOfLocal.length || argOfLocal[local] < 0) {
                return value;
            }
            return new Taint(value.type(), argBit(argOfLocal[local]));
        }

        @Override
        public Taint newOperation(AbstractInsnNode insn) throws AnalyzerException {
            return new Taint(basic.newOperation(insn), 0L);
        }

        @Override
        public Taint copyOperation(AbstractInsnNode insn, Taint value) throws AnalyzerException {
            return new Taint(basic.copyOperation(insn, value.type()), value.sources());
        }

        @Override
        public Taint unaryOperation(AbstractInsnNode insn, Taint value) throws AnalyzerException {
            return new Taint(basic.unaryOperation(insn, value.type()), value.sources());
        }

        @Override
        public Taint binaryOperation(AbstractInsnNode insn, Taint value1, Taint value2)
                throws AnalyzerException {
            return new Taint(basic.binaryOperation(insn, value1.type(), value2.type()),
                    value1.sources() | value2.sources());
        }

        @Override
        public Taint ternaryOperation(AbstractInsnNode insn, Taint value1, Taint value2, Taint value3)
                throws AnalyzerException {
            return new Taint(basic.ternaryOperation(insn, value1.type(), value2.type(), value3.type()),
                    value1.sources() | value2.sources() | value3.sources());
        }

        @Override
        public @Nullable Taint naryOperation(AbstractInsnNode insn, List<? extends Taint> values)
                throws AnalyzerException {
            List<BasicValue> types = new ArrayList<>(values.size());
            for (Taint value : values) {
                types.add(value.type());
            }
            BasicValue result = basic.naryOperation(insn, types);
            if (result == null) {
                return null;
            }
            Long summary = summaryOf(insn);
            long sources = summary == null ? 0L : summary & READ;
            for (int i = 0; i < values.size(); i++) {
                if (summary == null || (summary & argBit(i)) != 0) {
                    sources |= values.get(i).sources();
                }
            }
            if (insn instanceof MethodInsnNode call && isAtomicRead(call)) {
                sources |= READ;
            }
            return new Taint(result, sources);
        }

        /** {@return the summary of the method the call runs, or null when it is not one of this class's} */
        private @Nullable Long summaryOf(AbstractInsnNode insn) {
            if (!(insn instanceof MethodInsnNode call) || !cn.name.equals(call.owner)
                    || call.getOpcode() == Opcodes.INVOKEINTERFACE) {
                return null;
            }
            return summaries.get(call.name + call.desc);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, Taint value, Taint expected)
                throws AnalyzerException {
            basic.returnOperation(insn, value.type(), expected.type());
            returned |= value.sources();
        }

        @Override
        public Taint merge(Taint value1, Taint value2) {
            return new Taint(basic.merge(value1.type(), value2.type()),
                    value1.sources() | value2.sources());
        }
    }
}
