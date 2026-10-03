package se.deversity.asynctest.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Follows identity hashes into map and set keys through compiled classes (#803).
 *
 * <p>An identity hash is not unique, so a map or set keyed by one merges two objects whose hashes
 * collide. {@code DetectorStateIsKeyedByIdentityTest} in the library refuses that shape in the
 * source text, and so it sees one statement, one local and a one-line helper. This scanner reads
 * the bytecode instead and follows the value wherever the class files send it: through locals and
 * the operand stack, arithmetic, casts, boxing, string building and records, through fields, and
 * through methods of the scanned set in both directions, a helper that returns the hash and a
 * helper whose parameter becomes a key. Lambdas count as calls with their captured values as the
 * leading arguments.
 *
 * <p>A value is an identity hash when it comes from {@code System.identityHashCode}, or from
 * {@code hashCode()} called on a receiver whose static type is a class that inherits
 * {@code Object}'s or {@code Enum}'s implementation. A receiver typed {@code Object} or as an
 * interface is not counted, because its runtime class may override it; a non-final class that
 * does not override it is, although a subclass could.
 *
 * <p>The analysis is flow-sensitive inside a method, using the class file's stack map frames at
 * every branch target, and summary-based across methods: each method records whether it returns
 * a hash, and which of its parameters reach a key, a return or a field. Fields are one taint per
 * declared field, whatever instance holds it, and a hash stored into an array a field holds taints
 * that field. Calls resolve to the first declaring class up the superclass chain, and a virtual or
 * interface call also takes the summary of every override in the scanned set; a JDK method passes a
 * hash through only when it is listed in {@link #passesThrough}. {@code Object.toString()} is a
 * source too, on a type that inherits it and {@code Object.hashCode()}: its text ends in the identity
 * hash, whether called, passed to {@code String.valueOf} or built into a string (#803).
 *
 * <p>This is a test utility for the library's own gate, not part of the published artifact: it
 * lives in this module's test sources because ASM may not leave this module (invariant 5).
 */
final class IdentityHashKeyScanner {

    /**
     * One map or set call whose key carries an identity hash.
     *
     * @param className  internal name of the class holding the call, used to group findings
     * @param sourceFile the class's {@code SourceFile} attribute, or its simple name when absent
     * @param method     name of the method holding the call
     * @param line       source line of the call, or 0 when the class has no line table
     * @param sink       the keyed call, or the helper whose parameter becomes a key inside it
     * @param origin     where the hash came from, naming its first source
     */
    record Finding(String className, String sourceFile, String method, int line, String sink, String origin) {

        @Override
        public String toString() {
            return sourceFile + ":" + line + " " + simpleName(className) + "." + method + " keys " + sink
                    + " by " + origin;
        }
    }

    private static final Set<String> MAP_KEY_METHODS = Set.of("get", "put", "putIfAbsent", "computeIfAbsent",
            "computeIfPresent", "compute", "remove", "containsKey", "merge", "getOrDefault", "replace");

    private static final Set<String> SET_KEY_METHODS = Set.of("add", "contains", "remove");

    /** JDK value types whose every method returns something computed from its arguments. */
    private static final Set<String> VALUE_TYPES = Set.of("java/lang/Integer", "java/lang/Long",
            "java/lang/Short", "java/lang/Byte", "java/lang/Character", "java/lang/Number",
            "java/lang/Double", "java/lang/Float", "java/lang/String", "java/lang/StringBuilder",
            "java/lang/StringBuffer", "java/lang/AbstractStringBuilder", "java/lang/Math");

    /** The value types above whose instance methods change the receiver itself. */
    private static final Set<String> BUILDERS = Set.of("java/lang/StringBuilder", "java/lang/StringBuffer",
            "java/lang/AbstractStringBuilder");

    /** A fixed point is reached in a handful of passes; this bound only turns a bug into a failure. */
    private static final int MAX_PASSES = 200;

    private final ClassLoader loader;
    private final Map<String, ClassInfo> index = new HashMap<>();
    private final Map<String, String> taintedFields = new HashMap<>();
    private final Map<String, Summary> summaries = new HashMap<>();
    private final Map<String, Snapshot> labelStates = new HashMap<>();
    private final Map<String, Optional<Class<?>>> loaded = new HashMap<>();
    private Set<Finding> findings = new LinkedHashSet<>();
    private boolean changed;

    private IdentityHashKeyScanner(ClassLoader loader) {
        this.loader = loader;
    }

    /**
     * {@return every keyed call in {@code classes} whose key carries an identity hash}
     *
     * @param classes class file bytes of the whole set to analyse; fields, helpers and summaries
     *                are followed only within it, so a caller and its helper must be scanned together
     * @param loader  resolves the types outside the set, such as {@code java.util.Map}, by
     *                reflection without initializing them
     */
    static List<Finding> scan(Iterable<byte[]> classes, ClassLoader loader) {
        IdentityHashKeyScanner scanner = new IdentityHashKeyScanner(loader);
        Map<String, ClassReader> readers = new TreeMap<>();
        for (byte[] bytes : classes) {
            ClassReader reader = new ClassReader(bytes);
            ClassInfo info = new ClassInfo(reader.getClassName(), reader.getSuperName(), reader.getInterfaces(),
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0);
            reader.accept(info.collector(), ClassReader.SKIP_CODE);
            scanner.index.put(info.name, info);
            readers.put(info.name, reader);
        }
        int passes = 0;
        do {
            if (++passes > MAX_PASSES) {
                throw new IllegalStateException("no fixed point after " + MAX_PASSES + " passes");
            }
            scanner.changed = false;
            scanner.findings = new LinkedHashSet<>();
            for (ClassReader reader : readers.values()) {
                reader.accept(scanner.new ClassScan(), ClassReader.EXPAND_FRAMES);
            }
        } while (scanner.changed);
        return List.copyOf(scanner.findings);
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private static long bit(int ordinal) {
        return ordinal < 64 ? 1L << ordinal : 0L;
    }

    // ---------------------------------------------------------------------------------------------
    // Class hierarchy, from the scanned set first and the class loader for everything else
    // ---------------------------------------------------------------------------------------------

    private static final class ClassInfo {
        final String name;
        final String superName;
        final String[] interfaces;
        final boolean isInterface;
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();

        ClassInfo(String name, String superName, String[] interfaces, boolean isInterface) {
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces;
            this.isInterface = isInterface;
        }

        ClassVisitor collector() {
            return new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String n, String d, String s, Object v) {
                    fields.add(n);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
                    methods.add(n + d);
                    return null;
                }
            };
        }
    }

    private Optional<Class<?>> load(String internalName) {
        return loaded.computeIfAbsent(internalName, n -> {
            try {
                return Optional.of(Class.forName(n.replace('/', '.'), false, loader));
            } catch (ClassNotFoundException | LinkageError e) {
                return Optional.empty();
            }
        });
    }

    private boolean isSubtypeOf(String internalName, Class<?> type) {
        ClassInfo info = index.get(internalName);
        if (info == null) {
            return load(internalName).map(type::isAssignableFrom).orElse(false);
        }
        if (info.superName != null && isSubtypeOf(info.superName, type)) {
            return true;
        }
        for (String itf : info.interfaces) {
            if (isSubtypeOf(itf, type)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRecord(String internalName) {
        ClassInfo info = index.get(internalName);
        return info != null ? "java/lang/Record".equals(info.superName)
                : load(internalName).map(Class::isRecord).orElse(false);
    }

    /** {@return whether {@code hashCode()} on a receiver of this static type is an identity hash} */
    private boolean inheritsIdentityHash(String internalName) {
        if ("java/lang/Object".equals(internalName)) {
            return false;
        }
        String cls = internalName;
        ClassInfo info = index.get(cls);
        while (info != null) {
            if (info.isInterface || info.methods.contains("hashCode()I")) {
                return false;
            }
            cls = info.superName;
            info = index.get(cls);
        }
        Optional<Class<?>> type = load(cls);
        if (type.isEmpty() || type.get().isInterface()) {
            return false;
        }
        try {
            Class<?> declaring = type.get().getMethod("hashCode").getDeclaringClass();
            return declaring == Object.class || declaring == Enum.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * {@return whether {@code internalName}'s {@code toString()} is {@code Object}'s and its hash the
     * identity hash, so its text ends in the identity hash}
     */
    private boolean rendersIdentityHash(String internalName) {
        if (!inheritsIdentityHash(internalName)) {
            return false;
        }
        String cls = internalName;
        ClassInfo info = index.get(cls);
        while (info != null) {
            if (info.methods.contains("toString()Ljava/lang/String;")) {
                return false;
            }
            cls = info.superName;
            info = index.get(cls);
        }
        Optional<Class<?>> type = load(cls);
        if (type.isEmpty()) {
            return false;
        }
        try {
            return type.get().getMethod("toString").getDeclaringClass() == Object.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private final Map<String, List<String>> overridesOf = new HashMap<>();

    /**
     * {@return the summary keys of every scanned class below {@code owner}, by superclass or
     * interface, that declares {@code nameAndDesc}}
     */
    private List<String> overrides(String owner, String nameAndDesc) {
        return overridesOf.computeIfAbsent(owner + "." + nameAndDesc, k -> {
            List<String> found = new ArrayList<>();
            for (ClassInfo info : index.values()) {
                if (!info.name.equals(owner) && info.methods.contains(nameAndDesc) && isBelow(info, owner)) {
                    found.add(info.name + "." + nameAndDesc);
                }
            }
            return found;
        });
    }

    /** {@return whether {@code info} extends or implements {@code owner}, within the scanned set} */
    private boolean isBelow(ClassInfo info, String owner) {
        ClassInfo current = info;
        java.util.Deque<ClassInfo> pending = new java.util.ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pending.push(current);
        while (!pending.isEmpty()) {
            current = pending.pop();
            if (!seen.add(current.name)) {
                continue;
            }
            if (owner.equals(current.superName)) {
                return true;
            }
            for (String implemented : current.interfaces) {
                if (owner.equals(implemented)) {
                    return true;
                }
                ClassInfo up = index.get(implemented);
                if (up != null) {
                    pending.push(up);
                }
            }
            ClassInfo up = index.get(current.superName);
            if (up != null) {
                pending.push(up);
            }
        }
        return false;
    }

    /** {@return the summary key of the method a call resolves to, walking up the scanned superclasses} */
    private String resolveMethod(String owner, String name, String desc) {
        String cls = owner;
        ClassInfo info = index.get(cls);
        while (info != null) {
            if (info.methods.contains(name + desc)) {
                return cls + "." + name + desc;
            }
            cls = info.superName;
            info = index.get(cls);
        }
        return owner + "." + name + desc;
    }

    private String resolveField(String owner, String name) {
        String cls = owner;
        ClassInfo info = index.get(cls);
        while (info != null) {
            if (info.fields.contains(name)) {
                return cls + "." + name;
            }
            cls = info.superName;
            info = index.get(cls);
        }
        return owner + "." + name;
    }

    /** {@return whether a JDK call passes a hash in its receiver or arguments on to its result} */
    private static boolean passesThrough(String owner, String name, boolean isStatic) {
        if (VALUE_TYPES.contains(owner)) {
            return true;
        }
        return switch (owner) {
            case "java/util/Objects" -> Set.of("hash", "hashCode", "toString", "requireNonNull",
                    "requireNonNullElse").contains(name);
            case "java/util/Arrays" -> Set.of("hashCode", "deepHashCode", "asList", "toString", "copyOf")
                    .contains(name);
            case "java/util/List", "java/util/Set", "java/util/Map" ->
                    isStatic && Set.of("of", "copyOf", "entry").contains(name);
            default -> false;
        };
    }

    private void taintField(String field, String origin) {
        if (taintedFields.putIfAbsent(field, origin) == null) {
            changed = true;
        }
    }

    private Summary summary(String methodKey) {
        return summaries.computeIfAbsent(methodKey, k -> new Summary());
    }

    // ---------------------------------------------------------------------------------------------
    // Values and per-method summaries
    // ---------------------------------------------------------------------------------------------

    /**
     * An abstract value: which hash it carries, which parameters of its method it derives from, and
     * its static type where the bytecode states one.
     */
    private static final class Val {
        String origin;
        long params;
        final boolean wide;
        String type;
        /** The field this value was loaded from, so a store into it as an array taints the field. */
        String field;

        Val(String origin, long params, boolean wide) {
            this.origin = origin;
            this.params = params;
            this.wide = wide;
        }

        static Val clean(boolean wide) {
            return new Val(null, 0, wide);
        }

        boolean tainted() {
            return origin != null || params != 0;
        }

        void absorb(Val other) {
            if (origin == null) {
                origin = other.origin;
            }
            params |= other.params;
        }

        Val copy(boolean asWide) {
            Val copied = new Val(origin, params, asWide).typed(type);
            copied.field = field;
            return copied;
        }

        Val typed(String internalName) {
            type = internalName;
            return this;
        }

        static String typeOf(Type t) {
            return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY ? t.getInternalName() : null;
        }
    }

    /** What a method does with identity hashes, as seen by its callers. */
    private final class Summary {
        String returnOrigin;
        long returnParams;
        final Map<Integer, String> keyParams = new TreeMap<>();
        final Map<Integer, Set<String>> fieldParams = new TreeMap<>();

        void returns(Val v) {
            if (v.origin != null && returnOrigin == null) {
                returnOrigin = v.origin;
                changed = true;
            }
            if ((returnParams | v.params) != returnParams) {
                returnParams |= v.params;
                changed = true;
            }
        }

        void keys(long params, String sink) {
            for (int i = 0; i < 64; i++) {
                if ((params & bit(i)) != 0 && keyParams.putIfAbsent(i, sink) == null) {
                    changed = true;
                }
            }
        }

        void stores(long params, String field) {
            for (int i = 0; i < 64; i++) {
                if ((params & bit(i)) != 0 && fieldParams.computeIfAbsent(i, k -> new HashSet<>()).add(field)) {
                    changed = true;
                }
            }
        }
    }

    /** The tainted part of a method's state where control flow joins; absent means clean. */
    private static final class Snapshot {
        final Map<Integer, Val> locals = new HashMap<>();
        List<Val> stack;
    }

    // ---------------------------------------------------------------------------------------------
    // The walk
    // ---------------------------------------------------------------------------------------------

    private final class ClassScan extends ClassVisitor {
        private String className = "";
        private String sourceFile = "";

        ClassScan() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            className = name;
            sourceFile = simpleName(name) + ".class";
        }

        @Override
        public void visitSource(String source, String debug) {
            if (source != null) {
                sourceFile = source;
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                         String[] exceptions) {
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return null;
            }
            return new MethodScan(className, sourceFile, access, name, desc);
        }
    }

    private final class MethodScan extends MethodVisitor {
        private final String owner;
        private final String sourceFile;
        private final String name;
        private final String key;
        private Map<Integer, Val> locals = new HashMap<>();
        private List<Val> stack = new ArrayList<>();
        private Map<Integer, Val> lastLocals = new HashMap<>();
        private boolean reachable = true;
        private int line;
        private final Map<Label, Integer> labels = new IdentityHashMap<>();

        MethodScan(String owner, String sourceFile, int access, String name, String desc) {
            super(Opcodes.ASM9);
            this.owner = owner;
            this.sourceFile = sourceFile;
            this.name = name;
            this.key = owner + "." + name + desc;
            int slot = 0;
            int ordinal = 0;
            if ((access & Opcodes.ACC_STATIC) == 0) {
                locals.put(slot++, new Val(null, bit(ordinal++), false).typed(owner));
            }
            for (Type arg : Type.getArgumentTypes(desc)) {
                locals.put(slot, new Val(null, bit(ordinal++), arg.getSize() == 2).typed(Val.typeOf(arg)));
                slot += arg.getSize();
            }
        }

        // --- state plumbing ---

        private String here() {
            return simpleName(owner) + "." + name + ":" + line;
        }

        private void push(Val v) {
            stack.add(v);
        }

        private Val pop() {
            return stack.isEmpty() ? Val.clean(false) : stack.remove(stack.size() - 1);
        }

        private Val local(int slot, boolean wide) {
            Val v = locals.get(slot);
            if (v == null) {
                v = Val.clean(wide);
                locals.put(slot, v);
            }
            return v.wide == wide ? v : v.copy(wide);
        }

        private void unreachable() {
            if (reachable) {
                lastLocals = locals;
            }
            reachable = false;
        }

        private String labelKey(Label label) {
            return key + "#" + labels.computeIfAbsent(label, l -> labels.size());
        }

        /** Merges the current state into what flows to {@code target}. */
        private void jump(Label target) {
            Snapshot s = labelStates.computeIfAbsent(labelKey(target), k -> new Snapshot());
            locals.forEach((slot, v) -> {
                if (v.tainted()) {
                    Val known = s.locals.get(slot);
                    if (known == null) {
                        s.locals.put(slot, v.copy(v.wide));
                        changed = true;
                    } else if (merges(known, v)) {
                        changed = true;
                    }
                }
            });
            if (s.stack == null) {
                s.stack = new ArrayList<>();
                for (Val v : stack) {
                    s.stack.add(v.copy(v.wide));
                    changed |= v.tainted();
                }
            } else if (s.stack.size() == stack.size()) {
                for (int i = 0; i < stack.size(); i++) {
                    changed |= merges(s.stack.get(i), stack.get(i));
                }
            }
        }

        private boolean merges(Val into, Val from) {
            String origin = into.origin;
            long params = into.params;
            into.absorb(from);
            return !java.util.Objects.equals(origin, into.origin) || params != into.params;
        }

        @Override
        public void visitLabel(Label label) {
            Snapshot s = labelStates.get(labelKey(label));
            if (s != null && reachable) {
                jump(label);
            }
            if (s == null) {
                if (!reachable) {
                    // An exception handler: no jump reaches it, and its locals are those of the
                    // protected range, approximated by the state where the last block ended.
                    locals = new HashMap<>(lastLocals);
                    stack = new ArrayList<>();
                    reachable = true;
                }
                return;
            }
            locals = new HashMap<>();
            s.locals.forEach((slot, v) -> locals.put(slot, v.copy(v.wide)));
            stack = new ArrayList<>();
            if (s.stack != null) {
                s.stack.forEach(v -> stack.add(v.copy(v.wide)));
            }
            reachable = true;
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] frameStack) {
            Map<Integer, Val> live = new HashMap<>();
            int slot = 0;
            for (int i = 0; i < numLocal; i++) {
                boolean wide = local[i] == Opcodes.LONG || local[i] == Opcodes.DOUBLE;
                if (local[i] != Opcodes.TOP) {
                    Val v = locals.containsKey(slot) ? locals.get(slot) : Val.clean(wide);
                    live.put(slot, local[i] instanceof String t ? v.typed(t) : v);
                }
                slot += wide ? 2 : 1;
            }
            locals = live;
            List<Val> shaped = new ArrayList<>();
            for (int i = 0; i < numStack; i++) {
                boolean wide = frameStack[i] == Opcodes.LONG || frameStack[i] == Opcodes.DOUBLE;
                Val v = stack.size() == numStack ? stack.get(i) : Val.clean(wide);
                shaped.add(frameStack[i] instanceof String t ? v.typed(t) : v);
            }
            stack = shaped;
            reachable = true;
        }

        @Override
        public void visitLineNumber(int lineNumber, Label start) {
            line = lineNumber;
        }

        // --- instructions ---

        @Override
        public void visitInsn(int opcode) {
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.NOP -> { }
                case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.FCONST_0, Opcodes.FCONST_1,
                     Opcodes.FCONST_2 -> push(Val.clean(false));
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 -> push(Val.clean(true));
                case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD,
                     Opcodes.SALOAD, Opcodes.LALOAD, Opcodes.DALOAD -> {
                    pop();
                    Val array = pop();
                    push(array.copy(opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD));
                }
                case Opcodes.IASTORE, Opcodes.LASTORE, Opcodes.FASTORE, Opcodes.DASTORE, Opcodes.AASTORE,
                     Opcodes.BASTORE, Opcodes.CASTORE, Opcodes.SASTORE -> {
                    Val value = pop();
                    pop();
                    Val array = pop();
                    array.absorb(value);
                    if (array.field != null) {
                        // An element of an array a field holds is the field's state (#803).
                        if (value.origin != null) {
                            taintField(array.field, value.origin);
                        }
                        if (value.params != 0) {
                            summary(key).stores(value.params, array.field);
                        }
                    }
                }
                case Opcodes.POP -> pop();
                case Opcodes.POP2 -> {
                    if (!pop().wide) {
                        pop();
                    }
                }
                case Opcodes.DUP -> {
                    Val v = pop();
                    push(v);
                    push(v);
                }
                case Opcodes.DUP_X1 -> {
                    Val v1 = pop();
                    Val v2 = pop();
                    pushAll(v1, v2, v1);
                }
                case Opcodes.DUP_X2 -> {
                    Val v1 = pop();
                    Val v2 = pop();
                    if (v2.wide) {
                        pushAll(v1, v2, v1);
                    } else {
                        Val v3 = pop();
                        pushAll(v1, v3, v2, v1);
                    }
                }
                case Opcodes.DUP2 -> {
                    Val v1 = pop();
                    if (v1.wide) {
                        pushAll(v1, v1);
                    } else {
                        Val v2 = pop();
                        pushAll(v2, v1, v2, v1);
                    }
                }
                case Opcodes.DUP2_X1 -> {
                    Val v1 = pop();
                    if (v1.wide) {
                        Val v2 = pop();
                        pushAll(v1, v2, v1);
                    } else {
                        Val v2 = pop();
                        Val v3 = pop();
                        pushAll(v2, v1, v3, v2, v1);
                    }
                }
                case Opcodes.DUP2_X2 -> dup2x2();
                case Opcodes.SWAP -> {
                    Val v1 = pop();
                    Val v2 = pop();
                    pushAll(v1, v2);
                }
                case Opcodes.INEG, Opcodes.FNEG -> push(pop().copy(false));
                case Opcodes.LNEG, Opcodes.DNEG -> push(pop().copy(true));
                case Opcodes.I2L, Opcodes.I2D, Opcodes.L2D, Opcodes.F2L, Opcodes.F2D, Opcodes.D2L ->
                        push(pop().copy(true));
                case Opcodes.I2F, Opcodes.L2I, Opcodes.L2F, Opcodes.F2I, Opcodes.D2I, Opcodes.D2F, Opcodes.I2B,
                     Opcodes.I2C, Opcodes.I2S -> push(pop().copy(false));
                case Opcodes.LCMP, Opcodes.FCMPL, Opcodes.FCMPG, Opcodes.DCMPL, Opcodes.DCMPG -> {
                    pop();
                    pop();
                    push(Val.clean(false));
                }
                case Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN -> {
                    summary(key).returns(pop());
                    unreachable();
                }
                case Opcodes.RETURN -> unreachable();
                case Opcodes.ARRAYLENGTH -> {
                    pop();
                    push(Val.clean(false));
                }
                case Opcodes.ATHROW -> {
                    pop();
                    unreachable();
                }
                case Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> pop();
                default -> binary(opcode);
            }
        }

        /** IADD to DREM and ISHL to LXOR: the result carries both operands. */
        private void binary(int opcode) {
            Val b = pop();
            Val a = pop();
            boolean wide = switch (opcode) {
                case Opcodes.LADD, Opcodes.DADD, Opcodes.LSUB, Opcodes.DSUB, Opcodes.LMUL, Opcodes.DMUL,
                     Opcodes.LDIV, Opcodes.DDIV, Opcodes.LREM, Opcodes.DREM, Opcodes.LSHL, Opcodes.LSHR,
                     Opcodes.LUSHR, Opcodes.LAND, Opcodes.LOR, Opcodes.LXOR -> true;
                default -> false;
            };
            Val result = a.copy(wide);
            result.absorb(b);
            push(result);
        }

        private void dup2x2() {
            Val v1 = pop();
            Val v2 = pop();
            if (v1.wide && v2.wide) {
                pushAll(v1, v2, v1);
            } else if (v1.wide) {
                Val v3 = pop();
                pushAll(v1, v3, v2, v1);
            } else {
                Val v3 = pop();
                if (v3.wide) {
                    pushAll(v2, v1, v3, v2, v1);
                } else {
                    Val v4 = pop();
                    pushAll(v2, v1, v4, v3, v2, v1);
                }
            }
        }

        private void pushAll(Val... values) {
            for (Val v : values) {
                push(v);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (!reachable) {
                return;
            }
            if (opcode == Opcodes.NEWARRAY) {
                pop();
            }
            push(Val.clean(false));
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> push(local(varIndex, false));
                case Opcodes.LLOAD, Opcodes.DLOAD -> push(local(varIndex, true));
                case Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE, Opcodes.LSTORE, Opcodes.DSTORE -> {
                    Val v = pop();
                    locals.put(varIndex, v);
                    if (v.wide) {
                        locals.remove(varIndex + 1);
                    }
                }
                default -> { } // RET: no javac since 1.6 emits it
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.NEW -> push(Val.clean(false).typed(type));
                case Opcodes.ANEWARRAY, Opcodes.INSTANCEOF -> {
                    pop();
                    push(Val.clean(false));
                }
                default -> { // CHECKCAST keeps the value and narrows its type
                    if (!stack.isEmpty()) {
                        stack.get(stack.size() - 1).typed(type);
                    }
                }
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String descriptor) {
            if (!reachable) {
                return;
            }
            String field = resolveField(fieldOwner, fieldName);
            boolean wide = Type.getType(descriptor).getSize() == 2;
            switch (opcode) {
                case Opcodes.GETSTATIC, Opcodes.GETFIELD -> {
                    if (opcode == Opcodes.GETFIELD) {
                        pop();
                    }
                    Val loaded = new Val(taintedFields.get(field), 0, wide).typed(Val.typeOf(Type.getType(descriptor)));
                    loaded.field = field;
                    push(loaded);
                }
                default -> {
                    Val v = pop();
                    if (opcode == Opcodes.PUTFIELD) {
                        pop();
                    }
                    if (v.origin != null) {
                        taintField(field, v.origin);
                    }
                    if (v.params != 0) {
                        summary(key).stores(v.params, field);
                    }
                }
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String callOwner, String callName, String desc, boolean itf) {
            if (!reachable) {
                return;
            }
            boolean isStatic = opcode == Opcodes.INVOKESTATIC;
            Type[] args = Type.getArgumentTypes(desc);
            Val[] in = new Val[args.length + (isStatic ? 0 : 1)];
            for (int i = in.length - 1; i >= 0; i--) {
                in[i] = pop();
            }
            Type ret = Type.getReturnType(desc);
            Val result = Val.clean(ret.getSize() == 2).typed(Val.typeOf(ret));
            // javac names Object as the owner of an Object method the receiver's type inherits,
            // so the receiver's own static type is what says whose hashCode() runs.
            String receiverType = !isStatic && "java/lang/Object".equals(callOwner) && in[0].type != null
                    ? in[0].type : callOwner;

            if ("java/lang/System".equals(callOwner) && "identityHashCode".equals(callName)) {
                result.origin = "System.identityHashCode at " + here();
            } else if ("hashCode".equals(callName) && "()I".equals(desc) && !isStatic
                    && inheritsIdentityHash(receiverType)) {
                result.origin = simpleName(receiverType) + ".hashCode(), Object's identity hash, at " + here();
            } else if ("toString".equals(callName) && "()Ljava/lang/String;".equals(desc) && !isStatic
                    && rendersIdentityHash(receiverType)) {
                result.origin = simpleName(receiverType) + ".toString(), Object's, ending in the identity hash, at "
                        + here();
            } else if ("java/lang/String".equals(callOwner) && "valueOf".equals(callName)
                    && "(Ljava/lang/Object;)Ljava/lang/String;".equals(desc) && in[0].type != null
                    && rendersIdentityHash(in[0].type)) {
                result.origin = simpleName(in[0].type) + ".toString(), Object's, ending in the identity hash, at "
                        + here();
            } else {
                if (!isStatic && in.length > 1 && isKeyed(callOwner, callName)) {
                    String sink = simpleName(callOwner) + "." + callName;
                    if (in[1].origin != null) {
                        findings.add(new Finding(owner, sourceFile, name, line, sink, in[1].origin));
                    }
                    if (in[1].params != 0) {
                        summary(key).keys(in[1].params, sink);
                    }
                }
                if (passesThrough(callOwner, callName, isStatic)
                        || ("<init>".equals(callName) && isRecord(callOwner))) {
                    for (Val v : in) {
                        result.absorb(v);
                    }
                    if (!isStatic && in.length > 1 && ("<init>".equals(callName) || BUILDERS.contains(callOwner))) {
                        // StringBuilder.append, and a record or builder being constructed
                        for (int i = 1; i < in.length; i++) {
                            in[0].absorb(in[i]);
                        }
                    }
                }
                applyCallee(resolveMethod(callOwner, callName, desc), simpleName(callOwner) + "." + callName,
                        in, result, true);
                if ((opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE)
                        && index.containsKey(callOwner)) {
                    // Any override in the set may be the one that runs (#803). A call through a JDK
                    // type such as Object keeps the JDK's own summary, or every hashCode() would
                    // take a scanned override's.
                    for (String override : overrides(callOwner, callName + desc)) {
                        applyCallee(override, simpleName(callOwner) + "." + callName, in, result, true);
                    }
                }
            }
            if (ret.getSort() != Type.VOID) {
                push(result);
            }
        }

        private boolean isKeyed(String type, String method) {
            return MAP_KEY_METHODS.contains(method) && isSubtypeOf(type, Map.class)
                    || SET_KEY_METHODS.contains(method) && isSubtypeOf(type, Set.class);
        }

        /** Applies a scanned method's summary to the values a call passes it. */
        private void applyCallee(String calleeKey, String calleeName, Val[] in, Val result, boolean returns) {
            Summary callee = summaries.get(calleeKey);
            if (callee == null) {
                return;
            }
            for (int i = 0; i < in.length && i < 64; i++) {
                String sink = callee.keyParams.get(i);
                if (sink != null) {
                    String via = sink.startsWith("the key of ") ? sink : "the key of " + sink;
                    if (in[i].origin != null) {
                        findings.add(new Finding(owner, sourceFile, name, line, via + " inside " + calleeName,
                                in[i].origin));
                    }
                    if (in[i].params != 0) {
                        summary(key).keys(in[i].params, via + " inside " + calleeName);
                    }
                }
                for (String field : callee.fieldParams.getOrDefault(i, Set.of())) {
                    if (in[i].origin != null) {
                        taintField(field, in[i].origin);
                    }
                    if (in[i].params != 0) {
                        summary(key).stores(in[i].params, field);
                    }
                }
                if (returns && (callee.returnParams & bit(i)) != 0) {
                    result.absorb(in[i]);
                }
            }
            if (returns && callee.returnOrigin != null && result.origin == null) {
                result.origin = callee.returnOrigin;
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String indyName, String desc, Handle bootstrap, Object... bsmArgs) {
            if (!reachable) {
                return;
            }
            Type[] args = Type.getArgumentTypes(desc);
            Val[] in = new Val[args.length];
            for (int i = in.length - 1; i >= 0; i--) {
                in[i] = pop();
            }
            Type ret = Type.getReturnType(desc);
            Val result = Val.clean(ret.getSize() == 2);
            if ("java/lang/invoke/StringConcatFactory".equals(bootstrap.getOwner())) {
                for (int i = 0; i < in.length; i++) {
                    result.absorb(in[i]);
                    String type = in[i].type != null ? in[i].type : Val.typeOf(args[i]);
                    if (result.origin == null && type != null && rendersIdentityHash(type)) {
                        result.origin = simpleName(type) + ".toString(), Object's, ending in the identity hash, "
                                + "built into a string at " + here();
                    }
                }
            } else if ("java/lang/invoke/LambdaMetafactory".equals(bootstrap.getOwner()) && bsmArgs.length > 1
                    && bsmArgs[1] instanceof Handle impl && impl.getTag() != Opcodes.H_NEWINVOKESPECIAL) {
                // The captured values are the implementation method's leading arguments.
                applyCallee(resolveMethod(impl.getOwner(), impl.getName(), impl.getDesc()),
                        "a lambda (" + impl.getName() + ")", in, result, false);
            }
            if (ret.getSort() != Type.VOID) {
                push(result);
            }
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            if (!reachable) {
                return;
            }
            switch (opcode) {
                case Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE,
                     Opcodes.IFNULL, Opcodes.IFNONNULL -> pop();
                case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE,
                     Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE, Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                    pop();
                    pop();
                }
                default -> { } // GOTO; JSR is not emitted since 1.6
            }
            jump(label);
            if (opcode == Opcodes.GOTO) {
                unreachable();
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (!reachable) {
                return;
            }
            boolean wide = value instanceof Long || value instanceof Double
                    || value instanceof ConstantDynamic c && c.getSize() == 2;
            push(Val.clean(wide));
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... targets) {
            switchTo(dflt, targets);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] targets) {
            switchTo(dflt, targets);
        }

        private void switchTo(Label dflt, Label[] targets) {
            if (!reachable) {
                return;
            }
            pop();
            jump(dflt);
            for (Label target : targets) {
                jump(target);
            }
            unreachable();
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            if (!reachable) {
                return;
            }
            for (int i = 0; i < numDimensions; i++) {
                pop();
            }
            push(Val.clean(false));
        }
    }
}
