package se.deversity.asynctest.agent;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Formatter;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.StampedLock;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;

import se.deversity.vibetags.annotations.AIContract;

/**
 * Rewrites collection and lock calls in woven code so the detectors see the receiver itself.
 *
 * <h2>Why substitution rather than more field weaving</h2>
 *
 * <p>{@link FieldAccessWeaver} makes a field access visible, which is enough for a class that
 * mutates its own fields and blind for a class that keeps its state in a collection: the write that
 * races happens inside {@code java.util.HashMap}, and {@code java.} is on the ignore list for good
 * reasons that are not going to change. What the detectors need there is not the field but the
 * receiver of the call, and substituting the invocation with a static hook whose first parameter is
 * the receiver hands it over without spilling arguments: the hook consumes exactly the stack the
 * original call consumed and leaves the same value behind, so the operand stack shape at every
 * instruction is unchanged, no branch is introduced, and no member is added. That is what keeps
 * this safe under {@code disableClassFormatChanges()} on the retransformation path, on the same
 * reasoning the field weaver documents.
 *
 * <h2>Why a hand-rolled visitor rather than {@code MemberSubstitution}</h2>
 *
 * <p>Byte Buddy's {@code MemberSubstitution} performed the same rewrite, but its method visitor
 * also parses every {@code invokedynamic} instruction's bootstrap arguments into constants, and
 * as of Byte Buddy 1.18.12 that parsing reads a field method handle's descriptor as if it were a
 * method descriptor ({@code JavaConstant.MethodHandle.ofAsm} calls {@code Type.getMethodType} on
 * it unconditionally). Every Java record's {@code equals}/{@code hashCode}/{@code toString} calls
 * {@code ObjectMethods.bootstrap} with exactly such handles, so every record in a woven package
 * failed to instrument with a {@code StringIndexOutOfBoundsException}. This visitor rewrites the
 * one instruction kind it is about, {@code invokevirtual}/{@code invokeinterface} on a table
 * entry, and passes everything else through untouched, {@code invokedynamic} included.
 *
 * <p>The matching preserves the old semantics: the call site's method name and full descriptor
 * must equal the hook's, receiver excluded, and the owner must be a subtype of the entry's
 * interface, resolved through the type pool. A descriptor that differs, a covariant override for
 * an entry that does not pin its return type, or an owner the pool cannot resolve is skipped and
 * simply not recorded, which can only lose an observation, never invent one or change behaviour.
 * {@code invokespecial} is never rewritten: a decorator's {@code super.get(...)} must keep its
 * dispatch, or the substitution would re-dispatch virtually into the override and recurse.
 *
 * <h2>Why an explicit table</h2>
 *
 * <p>One entry per invocation shape, rather than a blanket match. A user can read this list and
 * know what is observed; the overhead is bounded by it; and the erased signature of each hook is
 * checked at build time by {@code CollectionAccessWeaverTest} rather than discovered as a
 * {@code NoSuchMethodError} inside somebody's suite.
 *
 * @since 1.9.8
 */
@AIContract(reason = "The hook class name and the method names here are the other half of AgentCollectionHooks and AgentLockHooks: they are matched by erased signature at weave time, so renaming a hook or changing a parameter type breaks weaving with a NoSuchMethodError inside user code rather than at compile time. Each substitution must consume exactly the stack its original invocation consumed - stack-shape-neutral and member-free is what keeps retransformation safe under disableClassFormatChanges(). The visitor must never touch invokedynamic: parsing its constants is what made every Java record fail to instrument when this went through MemberSubstitution. Collection weaving is opt-in (collections=true) because it instruments every listed call in every matched class.")
final class CollectionAccessWeaver {

    /**
     * The library's own package root, assembled rather than written as a literal for the reason
     * {@code FieldAccessWeaver.IGNORED_OWNERS} documents: the Shade plugin rewrites string literals
     * that look like relocated package names, and a silently rewritten prefix here would stop
     * excluding the very class it exists to protect.
     */
    private static final String LIBRARY_ROOT = String.join(".", "se", "deversity", "asynctest") + ".";

    /** The library-side class the substituted collection calls land in. */
    private static final String HOOKS = LIBRARY_ROOT + "AgentCollectionHooks";

    /** The library-side class the substituted lock calls land in. */
    private static final String LOCK_HOOKS = LIBRARY_ROOT + "AgentLockHooks";

    /** The library-side class holding the shared-instance hooks. */
    private static final String SHARED_HOOKS = LIBRARY_ROOT + "AgentSharedInstanceHooks";

    /** The library-side class holding the coordination-primitive hooks. */
    private static final String CONCURRENCY_HOOKS = LIBRARY_ROOT + "AgentConcurrencyUtilHooks";

    /** The library-side class holding the static-call hooks. */
    private static final String STATIC_HOOKS = LIBRARY_ROOT + "AgentSleepHooks";

    /** The library-side class holding the explicit-GC hook. */
    private static final String GC_HOOKS = LIBRARY_ROOT + "AgentGcHooks";

    private CollectionAccessWeaver() {
    }

    /**
     * One weave entry: which invocation to replace, and which hook replaces it.
     *
     * <p>{@code returning} narrows the match to one return type when set. The read-write lock
     * views need it because {@code ReentrantReadWriteLock.readLock()} declares a covariant return
     * type: a call site compiled against the concrete class expects a {@code ReadLock} on the
     * stack and a call site compiled against the interface expects a {@code Lock}, so each needs
     * a hook returning exactly what the instruction it replaces produced.
     */
    private record Entry(Class<?> declaredBy, String method, String hook,
                         @org.jspecify.annotations.Nullable Class<?> returning,
                         boolean isStatic,
                         @org.jspecify.annotations.Nullable String synchronizedHook,
                         Class<?>... parameters) {

        Entry(Class<?> declaredBy, String method, String hook,
              @org.jspecify.annotations.Nullable Class<?> returning, boolean isStatic,
              Class<?>... parameters) {
            this(declaredBy, method, hook, returning, isStatic, null, parameters);
        }

        /** An entry matched by name and arguments alone, whatever the call returns. */
        static Entry call(Class<?> declaredBy, String method, String hook, Class<?>... parameters) {
            return new Entry(declaredBy, method, hook, null, false, parameters);
        }

        /** A no-argument entry matched by its exact return type as well. */
        static Entry view(Class<?> declaredBy, String method, String hook, Class<?> returning) {
            return new Entry(declaredBy, method, hook, returning, false);
        }

        /**
         * A static invocation, matched on its owner exactly.
         *
         * <p>Simpler than the virtual case rather than riskier: there is no receiver on the
         * stack, so the hook's descriptor is the call site's descriptor unchanged, and a static
         * does not dispatch on subtype, so the owner must be the declaring class itself.
         */
        static Entry staticCall(Class<?> declaredBy, String method, String hook,
                                Class<?>... parameters) {
            return new Entry(declaredBy, method, hook, null, true, parameters);
        }

        /**
         * The hook to call instead when the enclosing method is {@code synchronized}.
         *
         * <p>Only meaningful where holding a monitor changes the answer, which today is the
         * sleep. It takes the same arguments plus the monitor, and the weaver loads that monitor
         * at the call site: {@code this} for an instance method, the class for a static one.
         */
        Entry whenSynchronized(String hook) {
            return new Entry(declaredBy, method, this.hook, returning, isStatic, hook, parameters);
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            Entry.call(Map.class, "put", "mapPut", Object.class, Object.class),
            Entry.call(Map.class, "get", "mapGet", Object.class),
            Entry.call(Map.class, "remove", "mapRemove", Object.class),
            Entry.call(Map.class, "containsKey", "mapContainsKey", Object.class),
            Entry.call(Collection.class, "add", "collectionAdd", Object.class),
            Entry.call(Collection.class, "remove", "collectionRemove", Object.class),
            Entry.call(Collection.class, "contains", "collectionContains", Object.class),
            Entry.call(Collection.class, "clear", "collectionClear"),
            Entry.call(List.class, "get", "listGet", int.class),
            Entry.call(List.class, "set", "listSet", int.class, Object.class),
            Entry.call(Queue.class, "offer", "queueOffer", Object.class),
            Entry.call(Queue.class, "poll", "queuePoll"),
            Entry.call(Queue.class, "peek", "queuePeek"));

    /**
     * The lock table. {@code java.util.concurrent.locks.Lock} is an interface whose implementations
     * live in {@code java.util.concurrent.locks}, where nothing is woven, so the call site is the
     * only place a lock acquisition can be observed at all.
     */
    private static final List<Entry> LOCK_ENTRIES = List.of(
            Entry.call(Lock.class, "lock", "lock"),
            Entry.call(Lock.class, "lockInterruptibly", "lockInterruptibly"),
            Entry.call(Lock.class, "tryLock", "tryLock"),
            Entry.call(Lock.class, "tryLock", "tryLock", long.class, TimeUnit.class),
            Entry.call(Lock.class, "unlock", "unlock"),
            // The views. Resolving readLock()/writeLock() at the call site is the only place the
            // owner and its view are both in hand; the hooks remember the pair so that acquiring
            // a view records the owner, in shared mode for the read side. The concrete class
            // declares covariant return types, so it needs its own pair of entries.
            Entry.view(ReadWriteLock.class, "readLock", "readLock", Lock.class),
            Entry.view(ReadWriteLock.class, "writeLock", "writeLock", Lock.class),
            Entry.view(ReentrantReadWriteLock.class, "readLock", "readLock",
                    ReentrantReadWriteLock.ReadLock.class),
            Entry.view(ReentrantReadWriteLock.class, "writeLock", "writeLock",
                    ReentrantReadWriteLock.WriteLock.class),
            // StampedLock implements no locking interface and hands back a long, so the concrete
            // class anchors every entry; the hooks record the lock object itself, exclusive for a
            // write stamp and shared for a read stamp. tryOptimisticRead and validate are absent
            // on purpose: an optimistic read holds nothing a lockset could record.
            Entry.call(StampedLock.class, "writeLock", "writeLock"),
            Entry.call(StampedLock.class, "readLock", "readLock"),
            Entry.call(StampedLock.class, "writeLockInterruptibly", "writeLockInterruptibly"),
            Entry.call(StampedLock.class, "readLockInterruptibly", "readLockInterruptibly"),
            Entry.call(StampedLock.class, "tryWriteLock", "tryWriteLock"),
            Entry.call(StampedLock.class, "tryReadLock", "tryReadLock"),
            Entry.call(StampedLock.class, "tryWriteLock", "tryWriteLock", long.class, TimeUnit.class),
            Entry.call(StampedLock.class, "tryReadLock", "tryReadLock", long.class, TimeUnit.class),
            Entry.call(StampedLock.class, "unlockWrite", "unlockWrite", long.class),
            Entry.call(StampedLock.class, "unlockRead", "unlockRead", long.class),
            Entry.call(StampedLock.class, "unlock", "unlock", long.class),
            Entry.call(StampedLock.class, "tryConvertToWriteLock", "tryConvertToWriteLock", long.class),
            Entry.call(StampedLock.class, "tryConvertToReadLock", "tryConvertToReadLock", long.class),
            Entry.call(StampedLock.class, "tryConvertToOptimisticRead", "tryConvertToOptimisticRead",
                    long.class),
            Entry.view(StampedLock.class, "asReadLock", "asReadLock", Lock.class),
            Entry.view(StampedLock.class, "asWriteLock", "asWriteLock", Lock.class));

    /**
     * The shared-instance table: JDK types that keep mutable state and are not thread safe.
     *
     * <p>Each of these is routinely cached in a static field because constructing one is
     * expensive, which is exactly how a confined object becomes a shared one. The detectors that
     * report them were reachable only by a hand-written {@code record} call, so the library could
     * see the bug only when the test author already suspected it.
     *
     * <p>The receiver types are concrete and free of thread-safe subclasses on purpose. A call
     * site holding a {@code ThreadLocalRandom} through a {@code Random} reference is the standing
     * counter-example, and it is why {@code Random} is not in this table: substituting there would
     * record instances that were safe all along.
     */
    private static final List<Entry> SHARED_INSTANCE_ENTRIES = List.of(
            Entry.call(SimpleDateFormat.class, "format", "format", Date.class),
            Entry.call(SimpleDateFormat.class, "parse", "parse", String.class),
            Entry.call(Matcher.class, "find", "find"),
            Entry.call(Matcher.class, "matches", "matches"),
            Entry.call(Matcher.class, "group", "group"),
            Entry.call(MessageDigest.class, "update", "update", byte[].class),
            Entry.call(MessageDigest.class, "digest", "digest"),
            Entry.call(MessageDigest.class, "digest", "digest", byte[].class),
            Entry.call(Calendar.class, "get", "get", int.class),
            Entry.call(Calendar.class, "set", "set", int.class, int.class),
            // StringBuilder is final and its two commonest appends carry the whole pattern.
            // String concatenation does not reach here: javac has compiled that to
            // invokedynamic since JDK 9, so only an explicit builder a user shared is woven.
            Entry.call(StringBuilder.class, "append", "append", String.class),
            Entry.call(StringBuilder.class, "append", "append", int.class),
            // NumberFormat rather than DecimalFormat: the abstract parent is what a field is
            // usually typed as, and neither it nor any JDK subclass is thread safe.
            Entry.call(NumberFormat.class, "format", "format", double.class),
            Entry.call(Formatter.class, "format", "format", String.class, Object[].class));

    /**
     * The coordination table: {@code java.util.concurrent} primitives whose protocol can be
     * misused.
     *
     * <p>Sharing is the point of these objects, so unlike the shared-instance table what matters
     * is the operation and its outcome. {@code offer} and the timed {@code await} return a
     * boolean that callers routinely discard, and that discarded boolean is the whole finding.
     *
     * <p>These are plumbing rather than domain types: a test author does not think to instrument
     * a latch three layers down in the class under test, which is why the detectors for them were
     * effectively unreachable before the substitution existed.
     */
    private static final List<Entry> CONCURRENCY_ENTRIES = List.of(
            Entry.call(Semaphore.class, "acquire", "acquire"),
            Entry.call(Semaphore.class, "tryAcquire", "tryAcquire"),
            Entry.call(Semaphore.class, "release", "release"),
            Entry.call(CountDownLatch.class, "countDown", "countDown"),
            Entry.call(CountDownLatch.class, "await", "await"),
            Entry.call(CountDownLatch.class, "await", "await", long.class, TimeUnit.class),
            Entry.call(BlockingQueue.class, "offer", "offer", Object.class),
            Entry.call(BlockingQueue.class, "poll", "poll"),
            Entry.call(BlockingQueue.class, "put", "put", Object.class));

    /**
     * The static table: calls a detector's input maps onto that are not invoked on a receiver.
     *
     * <p>{@code Thread.sleep} is the reason this path exists. Whether a sleep is a bug depends
     * entirely on whether a lock was held, which the lockset already knows and a stack trace
     * never did, so the two halves only had to be introduced.
     */
    private static final List<Entry> STATIC_ENTRIES = List.of(
            Entry.staticCall(Thread.class, "sleep", "sleep", long.class)
                    .whenSynchronized("sleepHoldingMonitor"));

    /**
     * The explicit-GC table.
     *
     * <p>{@code System.gc()} is the second user of the static path and the argument for having
     * built it rather than special-casing one call: one more table entry, no more mechanism. It
     * takes no arguments and returns nothing, which makes it the simplest substitution here.
     *
     * <p>{@code Runtime.getRuntime().gc()} is deliberately absent. It is an {@code invokevirtual}
     * on a receiver, so it belongs in a receiver table rather than this one, and listing it here
     * would claim a coverage the static path does not have.
     */
    private static final List<Entry> GC_ENTRIES = List.of(
            Entry.staticCall(System.class, "gc", "gc"));

    /**
     * One resolved rewrite: the call shape to match and the hook invocation that replaces it.
     *
     * <p>{@code callSiteDescriptor} is the hook's descriptor with the receiver parameter removed,
     * which is exactly the descriptor the original instruction must carry: matching on it makes
     * the return types equal by construction, so the value the hook leaves on the stack is the
     * value the following bytecode was verified against.
     */
    private record Target(String methodName, String callSiteDescriptor,
                          TypeDescription receiverType, String hookOwnerInternalName,
                          String hookMethodName, String hookDescriptor, boolean isStatic,
                          @org.jspecify.annotations.Nullable String synchronizedHookName,
                          @org.jspecify.annotations.Nullable String synchronizedHookDescriptor) {

        /** {@return whether this target has a variant for use inside a synchronized method} */
        boolean hasSynchronizedVariant() {
            return synchronizedHookName != null;
        }
    }

    private static List<Target> targets(List<Entry> entries, Class<?> hooks) {
        List<Target> targets = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            Method hook = hookMethod(hooks, entry);
            Type hookType = Type.getType(hook);
            Type[] hookArguments = hookType.getArgumentTypes();
            // A virtual hook takes the receiver as its first parameter and the call site does
            // not, so the call-site descriptor is the hook's with that parameter removed. A
            // static hook has no receiver, so the two descriptors are the same.
            Type[] callSiteArguments;
            if (entry.isStatic()) {
                callSiteArguments = hookArguments;
            } else {
                callSiteArguments = new Type[hookArguments.length - 1];
                System.arraycopy(hookArguments, 1, callSiteArguments, 0, callSiteArguments.length);
            }
            targets.add(new Target(
                    entry.method(),
                    Type.getMethodDescriptor(hookType.getReturnType(), callSiteArguments),
                    TypeDescription.ForLoadedType.of(entry.declaredBy()),
                    Type.getInternalName(hooks),
                    hook.getName(),
                    hookType.getDescriptor(),
                    entry.isStatic(),
                    entry.synchronizedHook(),
                    synchronizedDescriptorFor(hooks, entry)));
        }
        return targets;
    }

    /**
     * {@return the descriptor of the entry's synchronized-method variant, or {@code null}}
     *
     * <p>Resolved here rather than assumed, for the same reason {@code hookMethod} resolves the
     * ordinary hook: a name that does not exist on the hooks class is a version skew between the
     * agent and the library, and it should fail while the table is being built rather than
     * produce an invocation of a method that is not there.
     */
    private static @org.jspecify.annotations.Nullable String synchronizedDescriptorFor(
            Class<?> hooks, Entry entry) {
        if (entry.synchronizedHook() == null) {
            return null;
        }
        Class<?>[] signature = new Class<?>[entry.parameters().length + 1];
        System.arraycopy(entry.parameters(), 0, signature, 0, entry.parameters().length);
        signature[signature.length - 1] = Object.class;
        try {
            return Type.getType(hooks.getMethod(entry.synchronizedHook(), signature))
                    .getDescriptor();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "no synchronized-method hook " + hooks.getName() + "."
                            + entry.synchronizedHook() + " for " + entry.declaredBy().getName()
                            + "." + entry.method() + "; agent and library versions disagree", e);
        }
    }

    private static Method hookMethod(Class<?> hooks, Entry entry) {
        // A virtual hook takes the receiver first; a static one takes only the call's own
        // arguments, because there is no receiver to hand over.
        Class<?>[] signature;
        if (entry.isStatic()) {
            signature = entry.parameters().clone();
        } else {
            signature = new Class<?>[entry.parameters().length + 1];
            signature[0] = entry.declaredBy();
            System.arraycopy(entry.parameters(), 0, signature, 1, entry.parameters().length);
        }
        try {
            return hooks.getMethod(entry.hook(), signature);
        } catch (NoSuchMethodException e) {
            // The table and the hook class are compiled together and pinned by a test; reaching
            // this means the two were shipped out of step, and weaving with a half-built table
            // would be worse than telling the user which entry is missing.
            throw new IllegalStateException(
                    "no hook " + entry.hook() + " for " + entry.declaredBy().getName()
                            + "." + entry.method() + "; agent and library versions disagree", e);
        }
    }

    /**
     * {@return the collection substitutions, as one visitor over the whole table}
     *
     * @param hooks the class holding the hook methods, resolved in the weaving class loader
     */
    static List<AsmVisitorWrapper> substitutions(Class<?> hooks) {
        return List.of(new SubstitutionWrapper(targets(ENTRIES, hooks)));
    }

    /**
     * {@return the lock substitutions, in table order}
     *
     * <p>Feeds the same per-thread lockset that woven {@code MONITORENTER} instructions feed, so a
     * field or collection guarded by a {@code ReentrantLock} stops reading as unguarded.
     *
     * @param lockHooks the class holding the lock hooks, resolved in the weaving class loader
     */
    static List<AsmVisitorWrapper> lockSubstitutions(Class<?> lockHooks) {
        return List.of(new SubstitutionWrapper(targets(LOCK_ENTRIES, lockHooks)));
    }

    /**
     * {@return the shared-instance substitutions, in table order}
     *
     * @param sharedHooks the class holding the shared-instance hooks, resolved in the weaving
     *                    class loader
     */
    static List<AsmVisitorWrapper> sharedInstanceSubstitutions(Class<?> sharedHooks) {
        return List.of(new SubstitutionWrapper(targets(SHARED_INSTANCE_ENTRIES, sharedHooks)));
    }

    /**
     * {@return the coordination-primitive substitutions, in table order}
     *
     * @param concurrencyHooks the class holding the hooks, resolved in the weaving class loader
     */
    static List<AsmVisitorWrapper> concurrencySubstitutions(Class<?> concurrencyHooks) {
        return List.of(new SubstitutionWrapper(targets(CONCURRENCY_ENTRIES, concurrencyHooks)));
    }

    /**
     * {@return the static-call substitutions, in table order}
     *
     * @param staticHooks the class holding the hooks, resolved in the weaving class loader
     */
    static List<AsmVisitorWrapper> staticSubstitutions(Class<?> staticHooks) {
        return List.of(new SubstitutionWrapper(targets(STATIC_ENTRIES, staticHooks)));
    }

    /**
     * {@return the explicit-GC substitution}
     *
     * <p>Separate from {@link #staticSubstitutions} because the hook classes are one per concern
     * rather than one per invocation kind: a sleep means nothing until the lockset is consulted,
     * and a collection means the same thing wherever it was called from.
     *
     * @param gcHooks the class holding the hook, resolved in the weaving class loader
     */
    static List<AsmVisitorWrapper> gcSubstitutions(Class<?> gcHooks) {
        return List.of(new SubstitutionWrapper(targets(GC_ENTRIES, gcHooks)));
    }

    /** {@return the hook class name the substituted collection calls land in} */
    static String hooksClassName() {
        return HOOKS;
    }

    /** {@return the hook class name the substituted lock calls land in} */
    static String lockHooksClassName() {
        return LOCK_HOOKS;
    }

    /** {@return the hook class name the substituted shared-instance calls land in} */
    static String sharedHooksClassName() {
        return SHARED_HOOKS;
    }

    /** {@return the hook class name the substituted coordination calls land in} */
    static String concurrencyHooksClassName() {
        return CONCURRENCY_HOOKS;
    }

    /** {@return the hook class name the substituted static calls land in} */
    static String staticHooksClassName() {
        return STATIC_HOOKS;
    }

    /** {@return the hook class name the substituted System.gc() calls land in} */
    static String gcHooksClassName() {
        return GC_HOOKS;
    }

    /** Applies one table of {@link Target}s to every method of a woven class. */
    private record SubstitutionWrapper(List<Target> targets) implements AsmVisitorWrapper {

        @Override
        public int mergeWriter(int flags) {
            return flags;
        }

        @Override
        public int mergeReader(int flags) {
            return flags;
        }

        @Override
        public ClassVisitor wrap(TypeDescription instrumentedType,
                                 ClassVisitor classVisitor,
                                 Implementation.Context implementationContext,
                                 TypePool typePool,
                                 FieldList<FieldDescription.InDefinedShape> fields,
                                 MethodList<?> methods,
                                 int writerFlags,
                                 int readerFlags) {
            // Never substitute inside the library itself. AgentCollectionHooks.mapPut ends by
            // calling Map.put, so weaving it would replace that call with a call to itself. The
            // agent's global ignore already excludes the root; this guard keeps the property
            // local to the class that depends on it.
            if (instrumentedType.getName().startsWith(LIBRARY_ROOT)) {
                return classVisitor;
            }
            // Owner-to-entry assignability answers, per woven class: owners repeat heavily
            // inside one class, and the pool lookup is the only non-trivial cost here.
            Map<String, Boolean> assignable = new HashMap<>();
            return new ClassVisitor(Opcodes.ASM9, classVisitor) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate =
                            super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new SubstitutingMethodVisitor(delegate, targets, typePool, assignable,
                            access, instrumentedType.getInternalName());
                }
            };
        }
    }

    /** Rewrites matching virtual and interface invocations; passes every other instruction through. */
    private static final class SubstitutingMethodVisitor extends MethodVisitor {

        private final List<Target> targets;
        private final TypePool typePool;
        private final Map<String, Boolean> assignable;

        /** Whether the method being visited takes a monitor from its {@code ACC_SYNCHRONIZED} flag. */
        private final boolean enclosingIsSynchronized;

        /** Whether that monitor is the class rather than {@code this}. */
        private final boolean enclosingIsStatic;

        /** The class being woven, for loading its {@code Class} as a static method's monitor. */
        private final String owningClassInternalName;

        SubstitutingMethodVisitor(MethodVisitor delegate, List<Target> targets,
                                  TypePool typePool, Map<String, Boolean> assignable,
                                  int access, String owningClassInternalName) {
            super(Opcodes.ASM9, delegate);
            this.targets = targets;
            this.typePool = typePool;
            this.assignable = assignable;
            this.enclosingIsSynchronized = (access & Opcodes.ACC_SYNCHRONIZED) != 0;
            this.enclosingIsStatic = (access & Opcodes.ACC_STATIC) != 0;
            this.owningClassInternalName = owningClassInternalName;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            // Virtual and interface invocations only. A super.get() call is INVOKESPECIAL on
            // purpose: replacing it with a static that calls receiver.get() would re-dispatch
            // virtually, land back in the overriding subclass, and recurse until the stack ran
            // out. That is not hypothetical - it is what the corpus eval's PassiveExpiringMap
            // subject did, because a decorator that extends its own abstraction calls super on
            // every operation.
            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                for (Target target : targets) {
                    if (!target.isStatic()
                            && name.equals(target.methodName())
                            && descriptor.equals(target.callSiteDescriptor())
                            && ownerIsAssignable(owner, target)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                target.hookOwnerInternalName(), target.hookMethodName(),
                                target.hookDescriptor(), false);
                        return;
                    }
                }
            }
            // A static invocation. The owner is compared exactly rather than through
            // assignability: a static does not dispatch on subtype, so Thread.sleep called
            // through a subclass name is still Thread.sleep and any other owner is a different
            // method that happens to share a name. No receiver is on the stack, so the
            // substitution is stack-shape-neutral for the same reason the virtual one is, with
            // one fewer thing to get wrong.
            if (opcode == Opcodes.INVOKESTATIC) {
                for (Target target : targets) {
                    if (target.isStatic()
                            && name.equals(target.methodName())
                            && descriptor.equals(target.callSiteDescriptor())
                            && owner.equals(target.receiverType().getInternalName())) {
                        // Inside a synchronized method the monitor is held and no instruction
                        // says so: ACC_SYNCHRONIZED is an access flag, so HeldLocks cannot know.
                        // The weaver does know, statically, so it names the monitor instead of
                        // asking - this for an instance method, the class for a static one.
                        //
                        // One extra value on the stack, no branch and no exception handler, which
                        // is exactly what COMPUTE_MAXS without COMPUTE_FRAMES allows. Teaching
                        // the lockset instead would need a push on entry and a pop on every exit
                        // including the exceptional one, and that needs frames.
                        if (enclosingIsSynchronized && target.hasSynchronizedVariant()) {
                            if (enclosingIsStatic) {
                                super.visitLdcInsn(Type.getObjectType(owningClassInternalName));
                            } else {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                            }
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    target.hookOwnerInternalName(), target.synchronizedHookName(),
                                    target.synchronizedHookDescriptor(), false);
                            return;
                        }
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                target.hookOwnerInternalName(), target.hookMethodName(),
                                target.hookDescriptor(), false);
                        return;
                    }
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /**
         * {@return whether the invocation's owner is a subtype of the target's receiver type}
         *
         * <p>An owner the pool cannot resolve is treated as not assignable: the call is left
         * untouched and simply not recorded, which can only lose an observation. Resolving is
         * name-based and never loads the class, the same constraint the field weaver's
         * volatile lookup documents.
         */
        private boolean ownerIsAssignable(String owner, Target target) {
            if (owner.charAt(0) == '[') {
                return false;
            }
            String key = owner + '>' + target.receiverType().getName();
            Boolean cached = assignable.get(key);
            if (cached != null) {
                return cached;
            }
            boolean answer;
            try {
                TypePool.Resolution resolution = typePool.describe(owner.replace('/', '.'));
                answer = resolution.isResolved()
                        && resolution.resolve().isAssignableTo(target.receiverType());
            } catch (RuntimeException resolutionFailed) {
                answer = false;
            }
            assignable.put(key, answer);
            return answer;
        }
    }
}
