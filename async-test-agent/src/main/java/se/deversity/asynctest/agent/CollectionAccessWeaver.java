package se.deversity.asynctest.agent;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Deque;
import java.time.Duration;
import java.util.Formatter;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.BlockingDeque;
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
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.Attribute;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.jar.asm.TypePath;
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
 * instruction kinds it is about, {@code invokevirtual}/{@code invokeinterface} on a table entry and
 * a lambda factory's implementation handle that names one (#550), and passes everything else through
 * untouched, every other {@code invokedynamic} included.
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
@AIContract(reason = "The hook class name and the method names here are the other half of AgentCollectionHooks and AgentLockHooks: they are matched by erased signature at weave time, so renaming a hook or changing a parameter type breaks weaving with a NoSuchMethodError inside user code rather than at compile time. Each substitution must consume exactly the stack its original invocation consumed - stack-shape-neutral and member-free is what keeps retransformation safe under disableClassFormatChanges(). The visitor changes exactly one kind of invokedynamic: a LambdaMetafactory metafactory or non-serializable altMetafactory whose implementation handle matches a table entry is pointed at that entry's hook, so a method reference such as builder::append is observed (#550). Every other bootstrap, ObjectMethods for records above all, must pass through as the same argument array, read only through ASM's Handle: parsing bootstrap constants is what made every Java record fail to instrument when this went through MemberSubstitution, and a rewritten serializable lambda would fail to deserialize. Collection weaving is opt-in (collections=true) because it instruments every listed call in every matched class. The one-instruction lookahead behind whenResultDiscarded is a flag meaning the instruction just emitted was a substituted call whose result may be discarded: visitInsn(POP) is its only consumer and every other visit method must clear it, because a stale flag would turn an unrelated POP into a call whose parameter does not match the value on the stack, which is a VerifyError in the user's class at load time. SubstitutingVisitorClearsLookaheadEverywhereTest enumerates MethodVisitor to keep that override list complete. The one instruction the visitor inserts rather than substitutes is the loop back-edge call in front of a jump that comes back over a woven Object.wait (#694): it must stay a static ()V call, because the jump's operands are already on the stack beneath it and anything that took or left a value, or added a branch, would need the frames COMPUTE_MAXS does not recompute.")
final class CollectionAccessWeaver {

    /**
     * The library's own package root, assembled rather than written as a literal for the reason
     * {@code FieldAccessWeaver.IGNORED_OWNERS} documents: the Shade plugin rewrites string literals
     * that look like relocated package names, and a silently rewritten prefix here would stop
     * excluding the very class it exists to protect.
     */
    private static final String LIBRARY_ROOT = String.join(".", "se", "deversity", "asynctest") + ".";

    /** The same root in internal form, for owner names read off a call site (#715). */
    private static final String LIBRARY_ROOT_INTERNAL = LIBRARY_ROOT.replace(".", "/");

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

    /** The library-side class holding the wait/notify hooks and the loop back-edge hook. */
    private static final String MONITOR_HOOKS = LIBRARY_ROOT + "AgentMonitorHooks";

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
                         @org.jspecify.annotations.Nullable String resultDiscardedHook,
                         @org.jspecify.annotations.Nullable String loopBackEdgeHook,
                         Class<?>... parameters) {

        Entry(Class<?> declaredBy, String method, String hook,
              @org.jspecify.annotations.Nullable Class<?> returning, boolean isStatic,
              Class<?>... parameters) {
            this(declaredBy, method, hook, returning, isStatic, null, null, null, parameters);
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
            return new Entry(declaredBy, method, this.hook, returning, isStatic, hook,
                    resultDiscardedHook, loopBackEdgeHook, parameters);
        }

        /**
         * The hook to hand the result to when the call site discards it.
         *
         * <p>Only meaningful where a discarded return value is itself the defect, which today is
         * {@code BlockingQueue.offer}: a {@code false} nobody read is an element dropped on the
         * floor, where a {@code false} the caller branched on is backpressure working. The
         * bytecode tells them apart - a discarded result is a {@code POP} immediately after the
         * invocation, a checked one is {@code IFNE}, {@code ISTORE} or {@code IRETURN} - so the
         * weaver replaces that {@code POP}, and only that {@code POP}, with a call to this hook.
         * It takes the popped value and returns nothing, which makes it stack-identical to the
         * instruction it replaces and keeps it inside the no-frames constraint (#454).
         */
        Entry whenResultDiscarded(String hook) {
            return new Entry(declaredBy, method, this.hook, returning, isStatic, synchronizedHook,
                    hook, loopBackEdgeHook, parameters);
        }

        /**
         * The hook to call at the back-edge of a loop around this call.
         *
         * <p>Only meaningful where the loop around a call decides what the call means, which today
         * is {@code Object.wait}: {@code while (!ready) wait()} is the idiom and
         * {@code if (!ready) wait()} is the missed-signal bug, and the two call sites are the same
         * instruction (#694). A single pass cannot know at the call whether a later jump comes back
         * over it, so the fact is delivered where it becomes known: in front of every jump whose
         * target label was visited before this call, the weaver inserts a call to this hook. It
         * takes nothing and returns nothing, so it is stack-neutral whatever operands the jump is
         * about to consume, adds no branch and no frame, and runs whether or not a conditional
         * jump is then taken, which is what a loop with its test at the bottom needs.
         */
        Entry whenInsideLoop(String hook) {
            return new Entry(declaredBy, method, this.hook, returning, isStatic, synchronizedHook,
                    resultDiscardedHook, hook, parameters);
        }
    }

    /**
     * {@return every substituted call site, as {@code owner#method(paramType, ...)}}
     *
     * <p>Exists for {@code WovenOverloadCoverageTest}, which is the gate behind #434. The weaver
     * matches an exact descriptor, so an overload that is not in a table here is not woven, is
     * not observed, and produces silence indistinguishable from correct code. That is invisible
     * from the outside: nothing in a build fails, and the detector for it simply never speaks.
     * Handing the table out lets a test enumerate the receiver's real overloads and insist each
     * one is either woven or excused by name.
     *
     * <p>Deliberately a formatted string rather than the {@code Entry} record. The record is an
     * implementation detail that carries hook names and dispatch flags a coverage test has no
     * business reading, and a test that matched on it would break on every internal change.
     */
    static Set<String> wovenCallSites() {
        Set<String> sites = new LinkedHashSet<>();
        for (List<Entry> table : List.of(ENTRIES, SHARED_INSTANCE_ENTRIES, CONCURRENCY_ENTRIES,
                MONITOR_ENTRIES, STATIC_ENTRIES, GC_ENTRIES)) {
            for (Entry entry : table) {
                StringBuilder site = new StringBuilder(entry.declaredBy().getName())
                        .append('#').append(entry.method()).append('(');
                for (int i = 0; i < entry.parameters().length; i++) {
                    if (i > 0) {
                        site.append(", ");
                    }
                    site.append(entry.parameters()[i].getName());
                }
                sites.add(site.append(')').toString());
            }
        }
        return sites;
    }

    private static final List<Entry> ENTRIES = List.of(
            Entry.call(Map.class, "put", "mapPut", Object.class, Object.class),
            Entry.call(Map.class, "get", "mapGet", Object.class),
            Entry.call(Map.class, "remove", "mapRemove", Object.class),
            // The conditional two-argument removal, which is a mutation like its sibling (#440).
            Entry.call(Map.class, "remove", "mapRemove", Object.class, Object.class),
            Entry.call(Map.class, "containsKey", "mapContainsKey", Object.class),
            Entry.call(Collection.class, "add", "collectionAdd", Object.class),
            Entry.call(Collection.class, "remove", "collectionRemove", Object.class),
            Entry.call(Collection.class, "contains", "collectionContains", Object.class),
            Entry.call(Collection.class, "clear", "collectionClear"),
            Entry.call(List.class, "get", "listGet", int.class),
            Entry.call(List.class, "set", "listSet", int.class, Object.class),
            Entry.call(Queue.class, "offer", "queueOffer", Object.class),
            Entry.call(Queue.class, "poll", "queuePoll"),
            Entry.call(Queue.class, "peek", "queuePeek"),
            // The entry and removal forms #664 left unwoven (#692). An element that went in
            // through one of these had no recorded offer, so every thread got the #557 excuse,
            // and one that came out through one of these was never a take, so the remover's own
            // accesses read as an alias's.
            Entry.call(Collection.class, "addAll", "collectionAddAll", Collection.class),
            Entry.call(Collection.class, "removeIf", "collectionRemoveIf", Predicate.class),
            Entry.call(Queue.class, "remove", "queueRemove"),
            Entry.call(Deque.class, "offerFirst", "dequeOfferFirst", Object.class),
            Entry.call(Deque.class, "offerLast", "dequeOfferLast", Object.class),
            Entry.call(Deque.class, "addFirst", "dequeAddFirst", Object.class),
            Entry.call(Deque.class, "addLast", "dequeAddLast", Object.class),
            Entry.call(Deque.class, "push", "dequePush", Object.class),
            Entry.call(Deque.class, "pollFirst", "dequePollFirst"),
            Entry.call(Deque.class, "pollLast", "dequePollLast"),
            Entry.call(Deque.class, "removeFirst", "dequeRemoveFirst"),
            Entry.call(Deque.class, "removeLast", "dequeRemoveLast"),
            Entry.call(Deque.class, "pop", "dequePop"),
            // The blocking and timed forms only BlockingDeque declares. Its untimed offerFirst,
            // offerLast, pollFirst and pollLast are Deque's, woven by the entries above.
            Entry.call(BlockingDeque.class, "putFirst", "blockingDequePutFirst", Object.class),
            Entry.call(BlockingDeque.class, "putLast", "blockingDequePutLast", Object.class),
            Entry.call(BlockingDeque.class, "offerFirst", "blockingDequeOfferFirst",
                    Object.class, long.class, TimeUnit.class),
            Entry.call(BlockingDeque.class, "offerLast", "blockingDequeOfferLast",
                    Object.class, long.class, TimeUnit.class),
            Entry.call(BlockingDeque.class, "takeFirst", "blockingDequeTakeFirst"),
            Entry.call(BlockingDeque.class, "takeLast", "blockingDequeTakeLast"),
            Entry.call(BlockingDeque.class, "pollFirst", "blockingDequePollFirst",
                    long.class, TimeUnit.class),
            Entry.call(BlockingDeque.class, "pollLast", "blockingDequePollLast",
                    long.class, TimeUnit.class));

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
            Entry.call(SimpleDateFormat.class, "parse", "parse",
                    String.class, java.text.ParsePosition.class),
            // The same three calls reached through DateFormat, which is how library code holds a
            // SimpleDateFormat: Jackson keeps them in DateFormat fields, and an owner that is the
            // supertype matched none of the entries above (#542). Listed after them so that a call
            // whose owner is SimpleDateFormat keeps its own hook. DateFormat has stateless and
            // synchronized subclasses too, so these hooks record only a SimpleDateFormat receiver.
            Entry.call(java.text.DateFormat.class, "format", "format", Date.class),
            Entry.call(java.text.DateFormat.class, "parse", "parse", String.class),
            Entry.call(java.text.DateFormat.class, "parse", "parse",
                    String.class, java.text.ParsePosition.class),
            Entry.call(Matcher.class, "find", "find"),
            Entry.call(Matcher.class, "matches", "matches"),
            Entry.call(Matcher.class, "group", "group"),
            // find() then group(1) is the standard idiom, so the group-taking overloads were the
            // common path and the zero-argument one the exception (#434).
            Entry.call(Matcher.class, "group", "group", int.class),
            Entry.call(Matcher.class, "group", "group", String.class),
            Entry.call(Matcher.class, "find", "find", int.class),
            Entry.call(MessageDigest.class, "update", "update", byte[].class),
            Entry.call(MessageDigest.class, "digest", "digest"),
            Entry.call(MessageDigest.class, "digest", "digest", byte[].class),
            Entry.call(MessageDigest.class, "update", "update", byte.class),
            Entry.call(MessageDigest.class, "update", "update",
                    byte[].class, int.class, int.class),
            Entry.call(MessageDigest.class, "update", "update", java.nio.ByteBuffer.class),
            Entry.call(MessageDigest.class, "digest", "digest",
                    byte[].class, int.class, int.class),
            Entry.call(Calendar.class, "get", "get", int.class),
            Entry.call(Calendar.class, "set", "set", int.class, int.class),
            // Populating a calendar by date is how calendars are actually built; the
            // one-field-at-a-time form is the rarer shape and was the only one woven (#434).
            Entry.call(Calendar.class, "set", "set", int.class, int.class, int.class),
            Entry.call(Calendar.class, "set", "set",
                    int.class, int.class, int.class, int.class, int.class),
            Entry.call(Calendar.class, "set", "set",
                    int.class, int.class, int.class, int.class, int.class, int.class),
            // StringBuilder is final, and every append overload reads count, writes the array
            // and writes count back. The weaver matches an exact descriptor, so listing only two
            // of them left a shared builder appended to with a char - or an Object, or a
            // CharSequence - completely unobserved while the String form was seen. That was found
            // by a corpus row that had to fire and did not (#434). The remaining overloads
            // (char[], float, StringBuffer, and the three-argument forms) are still unwoven and
            // are listed in WovenOverloadCoverageTest rather than left to be rediscovered.
            // String concatenation does not reach here: javac has compiled that to
            // invokedynamic since JDK 9, so only an explicit builder a user shared is woven.
            Entry.call(StringBuilder.class, "append", "append", String.class),
            Entry.call(StringBuilder.class, "append", "append", int.class),
            Entry.call(StringBuilder.class, "append", "append", char.class),
            Entry.call(StringBuilder.class, "append", "append", long.class),
            Entry.call(StringBuilder.class, "append", "append", double.class),
            Entry.call(StringBuilder.class, "append", "append", boolean.class),
            Entry.call(StringBuilder.class, "append", "append", Object.class),
            Entry.call(StringBuilder.class, "append", "append", CharSequence.class),
            // Appends reached through Appendable, which is how a library writes to a builder the
            // caller hands it - Guava's Joiner.appendTo(StringBuilder, ...) delegates to exactly
            // these (#542). The descriptors return Appendable, so a call whose owner is
            // StringBuilder never matches them. Appendable is also StringBuffer and every Writer,
            // so the hooks record only a StringBuilder receiver.
            Entry.call(Appendable.class, "append", "append", CharSequence.class),
            Entry.call(Appendable.class, "append", "append", char.class),
            Entry.call(Appendable.class, "append", "append", CharSequence.class, int.class,
                    int.class),
            // NumberFormat rather than DecimalFormat: the abstract parent is what a field is
            // usually typed as, and neither it nor any JDK subclass is thread safe.
            Entry.call(NumberFormat.class, "format", "format", double.class),
            Entry.call(NumberFormat.class, "format", "format", long.class),
            // Parsing drives the same internal state, and a utility that parses with a format the
            // caller supplies (Spring's NumberUtils.parseNumber) was invisible while only format
            // was woven (#542).
            Entry.call(NumberFormat.class, "parse", "parse", String.class),
            Entry.call(NumberFormat.class, "parse", "parse", String.class,
                    java.text.ParsePosition.class),
            Entry.call(Formatter.class, "format", "format", String.class, Object[].class),
            // The locale-taking overload is what an internationalised codebase calls, and it was
            // invisible while its sibling was woven (#434).
            Entry.call(Formatter.class, "format", "format",
                    Locale.class, String.class, Object[].class));

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
            // The permit-count and timed overloads. A pool sized in permits was invisible, and
            // each permit is recorded separately so that acquire(3)/release(1) reads as the leak
            // of two that it is (#434).
            Entry.call(Semaphore.class, "acquire", "acquire", int.class),
            Entry.call(Semaphore.class, "tryAcquire", "tryAcquire", int.class),
            Entry.call(Semaphore.class, "tryAcquire", "tryAcquire", long.class, TimeUnit.class),
            Entry.call(Semaphore.class, "tryAcquire", "tryAcquire",
                    int.class, long.class, TimeUnit.class),
            Entry.call(Semaphore.class, "release", "release", int.class),
            Entry.call(CountDownLatch.class, "countDown", "countDown"),
            Entry.call(CountDownLatch.class, "await", "await"),
            Entry.call(CountDownLatch.class, "await", "await", long.class, TimeUnit.class),
            // The boolean a caller discards is the finding (#454): the POP after either offer
            // becomes a call to offerResultDiscarded, and a branch, store or return is left alone.
            Entry.call(BlockingQueue.class, "offer", "offer", Object.class)
                    .whenResultDiscarded("offerResultDiscarded"),
            Entry.call(BlockingQueue.class, "poll", "poll"),
            Entry.call(BlockingQueue.class, "put", "put", Object.class),
            // The timed forms are what production code reaches for, and neither was woven (#434).
            Entry.call(BlockingQueue.class, "offer", "offer",
                    Object.class, long.class, TimeUnit.class)
                    .whenResultDiscarded("offerResultDiscarded"),
            Entry.call(BlockingQueue.class, "poll", "poll", long.class, TimeUnit.class),
            // take hands the head over like poll, and drainTo empties the queue without naming
            // the elements; unwoven, both left stale offers on record (#664).
            Entry.call(BlockingQueue.class, "take", "take"),
            Entry.call(BlockingQueue.class, "drainTo", "drainTo", Collection.class),
            Entry.call(BlockingQueue.class, "drainTo", "drainTo", Collection.class,
                    int.class));

    /**
     * The monitor table: {@code Object.wait}, {@code notify} and {@code notifyAll} (#694).
     *
     * <p>All five are {@code final} on {@code Object}, so a call site carrying one of these names
     * and descriptors is that method whatever owner it was compiled against, and every resolvable
     * owner is assignable to the entry's type. The hooks record on {@code MissedSignalDetector},
     * which until this table existed could only judge what the body said about itself. Each wait
     * carries the loop back-edge hook, because the loop around a wait is what separates the idiom
     * from the bug.
     */
    private static final List<Entry> MONITOR_ENTRIES = List.of(
            Entry.call(Object.class, "wait", "monitorWait").whenInsideLoop("loopBackEdge"),
            Entry.call(Object.class, "wait", "monitorWait", long.class)
                    .whenInsideLoop("loopBackEdge"),
            Entry.call(Object.class, "wait", "monitorWait", long.class, int.class)
                    .whenInsideLoop("loopBackEdge"),
            Entry.call(Object.class, "notify", "monitorNotify"),
            Entry.call(Object.class, "notifyAll", "monitorNotifyAll"));

    /**
     * The static table: calls a detector's input maps onto that are not invoked on a receiver.
     *
     * <p>{@code Thread.sleep} is the reason this path exists. Whether a sleep is a bug depends
     * entirely on whether a lock was held, which the lockset already knows and a stack trace
     * never did, so the two halves only had to be introduced.
     */
    private static final List<Entry> STATIC_ENTRIES = List.of(
            Entry.staticCall(Thread.class, "sleep", "sleep", long.class)
                    .whenSynchronized("sleepHoldingMonitor"),
            // The Duration form is what new code writes since JDK 19, and the two-argument form
            // is the older precision variant. Both were unwoven while the plain long was woven,
            // so a sleep holding a monitor was invisible purely because of how its duration was
            // spelled (#440). The conditional-hook path generalises without change: the weaver
            // derives the monitor-taking descriptor by appending Object to the entry's own
            // parameters, so each needs only its sleepHoldingMonitor overload to exist.
            Entry.staticCall(Thread.class, "sleep", "sleep", Duration.class)
                    .whenSynchronized("sleepHoldingMonitor"),
            Entry.staticCall(Thread.class, "sleep", "sleep", long.class, int.class)
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
                          @org.jspecify.annotations.Nullable String synchronizedHookDescriptor,
                          @org.jspecify.annotations.Nullable String discardHookName,
                          @org.jspecify.annotations.Nullable String discardHookDescriptor,
                          @org.jspecify.annotations.Nullable String loopBackEdgeHookName) {

        /** {@return whether a loop around this target's call site gets a back-edge hook} */
        boolean hasLoopBackEdgeHook() {
            return loopBackEdgeHookName != null;
        }

        /** {@return whether this target has a variant for use inside a synchronized method} */
        boolean hasSynchronizedVariant() {
            return synchronizedHookName != null;
        }

        /** {@return whether this target has a hook for a result the call site discards} */
        boolean hasDiscardVariant() {
            return discardHookName != null;
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
                    synchronizedDescriptorFor(hooks, entry),
                    entry.resultDiscardedHook(),
                    discardDescriptorFor(hooks, entry, hook),
                    loopBackEdgeHookFor(hooks, entry)));
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
        // Held in a local rather than re-read. Two calls to the accessor are two reads to a
        // static analyser, and it cannot know the second returns what the first did, so the
        // guard above proves nothing about the use below.
        String hookName = entry.synchronizedHook();
        if (hookName == null) {
            return null;
        }
        Class<?>[] signature = new Class<?>[entry.parameters().length + 1];
        System.arraycopy(entry.parameters(), 0, signature, 0, entry.parameters().length);
        signature[signature.length - 1] = Object.class;
        try {
            return Type.getType(hooks.getMethod(hookName, signature)).getDescriptor();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "no synchronized-method hook " + hooks.getName() + "." + hookName
                            + " for " + entry.declaredBy().getName() + "." + entry.method()
                            + "; agent and library versions disagree", e);
        }
    }

    /**
     * {@return the descriptor of the entry's discarded-result hook, or {@code null}}
     *
     * <p>The hook takes exactly the value the ordinary hook leaves on the stack and returns
     * nothing, so it consumes what the {@code POP} it replaces would have consumed. Checked here
     * rather than trusted, for the reason the other two resolutions are: a missing or mis-shaped
     * hook is a version skew between the agent and the library, and it has to fail while the
     * table is built rather than emit a call to a method that is not there or a class that will
     * not verify.
     *
     * @param hooks the hooks class the table resolves against
     * @param entry the entry, whose {@code resultDiscardedHook} may be unset
     * @param hook  the entry's ordinary hook, whose return type is what gets discarded
     */
    private static @org.jspecify.annotations.Nullable String discardDescriptorFor(
            Class<?> hooks, Entry entry, Method hook) {
        String hookName = entry.resultDiscardedHook();
        if (hookName == null) {
            return null;
        }
        Class<?> discarded = hook.getReturnType();
        if (discarded == void.class || discarded == long.class || discarded == double.class) {
            // A void call leaves nothing to pop, and a long or double is popped by POP2. Either
            // would need a different instruction than the one this lookahead watches for.
            throw new IllegalStateException(
                    "whenResultDiscarded on " + entry.declaredBy().getName() + "." + entry.method()
                            + " needs a category-1 result, not " + discarded.getName());
        }
        try {
            Method discard = hooks.getMethod(hookName, discarded);
            if (discard.getReturnType() != void.class) {
                throw new IllegalStateException(
                        "discarded-result hook " + hooks.getName() + "." + hookName
                                + " must return void: it stands in for a POP");
            }
            return Type.getType(discard).getDescriptor();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "no discarded-result hook " + hooks.getName() + "." + hookName + "("
                            + discarded.getName() + ") for " + entry.declaredBy().getName() + "."
                            + entry.method() + "; agent and library versions disagree", e);
        }
    }

    /**
     * {@return the entry's loop back-edge hook name, or {@code null}}
     *
     * <p>Resolved for the reason the other variants are. The weaver emits it with the descriptor
     * {@code ()V} in front of a jump, so anything else on the hooks class under that name is a
     * version skew that has to fail here rather than as a {@code VerifyError} in a woven class.
     */
    private static @org.jspecify.annotations.Nullable String loopBackEdgeHookFor(
            Class<?> hooks, Entry entry) {
        String hookName = entry.loopBackEdgeHook();
        if (hookName == null) {
            return null;
        }
        try {
            Method backEdge = hooks.getMethod(hookName);
            if (backEdge.getReturnType() != void.class
                    || !java.lang.reflect.Modifier.isStatic(backEdge.getModifiers())) {
                throw new IllegalStateException(
                        "loop back-edge hook " + hooks.getName() + "." + hookName
                                + " must be static and return void: it stands in front of a jump");
            }
            return hookName;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "no loop back-edge hook " + hooks.getName() + "." + hookName + "() for "
                            + entry.declaredBy().getName() + "." + entry.method()
                            + "; agent and library versions disagree", e);
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

    /**
     * {@return the wait/notify substitutions}
     *
     * @param monitorHooks the class holding the hooks, resolved in the weaving class loader
     */
    static List<AsmVisitorWrapper> monitorSubstitutions(Class<?> monitorHooks) {
        return List.of(new SubstitutionWrapper(targets(MONITOR_ENTRIES, monitorHooks)));
    }

    /** {@return the hook class name the substituted wait/notify calls land in} */
    static String monitorHooksClassName() {
        return MONITOR_HOOKS;
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

    /**
     * Methods that directly or indirectly invoke {@code Object.wait}, keyed as
     * {@code "ownerInternalName.nameDescriptor"}, so that a loop around a call into one of them
     * is recognised as a loop around a wait (#707).
     *
     * <p>Within one class the answer is exact: the monitor wrapper buffers every method before
     * emitting any of it, so a helper declared after its caller is still resolved. Across classes
     * it is best-effort in one direction only. An entry is added when the class that declares the
     * helper is woven, which helps callers woven afterwards and cannot help one woven before. The
     * name at the call site no longer decides it: {@link #WAITING_OWNERS_BY_SIGNATURE} resolves a
     * helper reached through a supertype or an interface (#709). What is left is weave order, and
     * it is silent: the loop goes unmarked, the wait reads as an {@code if}, and a correct bounded
     * poll can be reported. That is why {@code MISSED_SIGNAL} stays {@code PROMPT}.
     *
     * <p>It is never cleared, which is what lets it outlive one class's weave. It holds one string
     * per waiting method of every class woven in this JVM, so it grows with classes woven rather
     * than with anything a test does.
     */
    private static final Set<String> KNOWN_WAITING_METHODS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The same methods indexed by { name + descriptor} alone, to the internal names of the
     * classes that declare them, so a call site can be matched against a type it is related to
     * rather than only the one it names (#709).
     *
     * <p>A call site carries the declared type, which is not where the wait has to be. A helper
     * inherited from a supertype is called under the subtype's name, and one reached through an
     * interface under the interface's, where no body exists at all. Both are resolved by asking
     * whether either type is assignable to the other, which covers the two directions with one
     * question. It stays a question about one signature: a sibling method on the same class that
     * does not wait is not made to wait by this.
     *
     * <p>Bounded like its sibling, by the waiting methods of the classes woven in this JVM.
     */
    private static final Map<String, Set<String>> WAITING_OWNERS_BY_SIGNATURE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@return whether a call site reaches a method already known to wait}
     *
     * <p>The exact name first, which is the common case and costs a set lookup. Failing that, the
     * same signature declared on a type related to the one the call site names (#709): a helper
     * inherited from a supertype is called under the subtype's name, and one reached through an
     * interface under a name with no body behind it at all. Asking whether either type is
     * assignable to the other answers both directions with one question.
     *
     * <p>Asked from two places that must agree: the buffering pass, which decides whether a
     * method waits and so whether its own callers do, and the emitting pass, which decides
     * whether a call is the one a loop is closing around. A rule applied in only one of them
     * would register a helper nobody marks, or mark a loop around a helper nobody registered.
     *
     * <p>{@code java.lang.Object} is excluded as the call site's type because every class is
     * assignable to it, so a single woven {@code toString} that happened to wait would make every
     * {@code Object.toString()} call read as a wait. A mark on a loop with no wait in it is not
     * harmless: the hook would attribute a back-edge to whatever that thread waited on last, and
     * spare a report that should have been made.
     *
     * @param owner     the internal name written at the call site
     * @param signature the callee's name and descriptor
     * @param typePool  the pool the weaving pass resolves types through
     * @param related   the per-class cache of assignability answers
     */
    private static boolean reachesWaitingMethod(String owner, String signature,
                                                TypePool typePool,
                                                Map<String, Boolean> related) {
        if (KNOWN_WAITING_METHODS.contains(owner + "." + signature)) {
            return true;
        }
        if (owner.isEmpty() || "java/lang/Object".equals(owner) || owner.charAt(0) == '[') {
            return false;
        }
        Set<String> declaringOwners = WAITING_OWNERS_BY_SIGNATURE.get(signature);
        if (declaringOwners == null) {
            return false;
        }
        for (String declaring : declaringOwners) {
            if (relatedByHierarchy(declaring, owner, typePool, related)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return whether either type is assignable to the other}
     *
     * <p>Cached per woven class, like the substitution table's own assignability answers: a call
     * site's owner repeats heavily inside one class and the pool lookup is the only non-trivial
     * cost here. A type that will not resolve answers no, because a weaver that throws is worse
     * than one that marks nothing.
     *
     * @param declaring the internal name of the class declaring the waiting method
     * @param owner     the internal name written at the call site
     * @param typePool  the pool the weaving pass resolves types through
     * @param related   the per-class cache of assignability answers
     */
    private static boolean relatedByHierarchy(String declaring, String owner,
                                              TypePool typePool,
                                              Map<String, Boolean> related) {
        String key = declaring + "<>" + owner;
        Boolean cached = related.get(key);
        if (cached != null) {
            return cached;
        }
        boolean answer = false;
        try {
            TypePool.Resolution declared = typePool.describe(declaring.replace('/', '.'));
            TypePool.Resolution called = typePool.describe(owner.replace('/', '.'));
            if (declared.isResolved() && called.isResolved()) {
                answer = declared.resolve().isAssignableTo(called.resolve())
                        || called.resolve().isAssignableTo(declared.resolve());
            }
        } catch (RuntimeException resolutionFailed) {
            answer = false;
        }
        related.put(key, answer);
        return answer;
    }

    /**
     * {@return whether a call to this owner could still turn out to wait once its class is woven}
     *
     * <p>Excludes the caller's own class, which the buffering pass resolves exactly whichever
     * order the methods are declared in, and the platform, which is never woven and whose one
     * waiting method is already in the table by name. What is left is user and library code, the
     * only code whose weaving can add an entry later (#715).
     *
     * @param owner the invocation's owner, in internal form
     * @param callerInternalName the class being woven
     */
    private static boolean couldBeWovenLater(String owner, String callerInternalName) {
        if (owner.isEmpty() || owner.charAt(0) == '[' || owner.equals(callerInternalName)) {
            return false;
        }
        return !owner.startsWith("java/") && !owner.startsWith("javax/")
                && !owner.startsWith("jdk/") && !owner.startsWith("sun/")
                && !owner.startsWith("com/sun/") && !owner.startsWith(LIBRARY_ROOT_INTERNAL);
    }

    /** Buffers annotation visitor actions so they can be replayed to another visitor. */
    private static final class BufferedAnnotationVisitor extends AnnotationVisitor {
        private final List<Consumer<AnnotationVisitor>> actions = new ArrayList<>();

        BufferedAnnotationVisitor() {
            super(Opcodes.ASM9);
        }

        void replay(AnnotationVisitor target) {
            if (target != null) {
                for (Consumer<AnnotationVisitor> action : actions) {
                    action.accept(target);
                }
            }
        }

        @Override
        public void visit(String name, Object value) {
            actions.add(av -> av.visit(name, value));
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            actions.add(av -> av.visitEnum(name, descriptor, value));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            BufferedAnnotationVisitor nested = new BufferedAnnotationVisitor();
            actions.add(av -> nested.replay(av.visitAnnotation(name, descriptor)));
            return nested;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            BufferedAnnotationVisitor nested = new BufferedAnnotationVisitor();
            actions.add(av -> nested.replay(av.visitArray(name)));
            return nested;
        }

        @Override
        public void visitEnd() {
            actions.add(AnnotationVisitor::visitEnd);
        }
    }

    /** Buffers method visitor actions and records calls to wait and local methods. */
    private static final class BufferedMethod extends MethodVisitor {
        private final int access;
        private final String name;
        private final String descriptor;
        private final @org.jspecify.annotations.Nullable String signature;
        private final String @org.jspecify.annotations.Nullable [] exceptions;
        private final String ownerInternalName;
        private final TypePool typePool;
        private final Map<String, Boolean> related;

        private boolean directlyWaits;
        private final Set<String> calledLocalMethods = new HashSet<>();
        private final List<Consumer<MethodVisitor>> actions = new ArrayList<>();

        BufferedMethod(int access, String name, String descriptor,
                       @org.jspecify.annotations.Nullable String signature,
                       String @org.jspecify.annotations.Nullable [] exceptions,
                       String ownerInternalName,
                       TypePool typePool,
                       Map<String, Boolean> related) {
            super(Opcodes.ASM9);
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            this.signature = signature;
            this.exceptions = exceptions == null ? null : exceptions.clone();
            this.ownerInternalName = ownerInternalName;
            this.typePool = typePool;
            this.related = related;
        }

        boolean directlyWaits() {
            return directlyWaits;
        }

        Set<String> calledLocalMethods() {
            return calledLocalMethods;
        }

        String methodKey() {
            return name + descriptor;
        }

        int access() {
            return access;
        }

        String name() {
            return name;
        }

        String descriptor() {
            return descriptor;
        }

        @org.jspecify.annotations.Nullable String signature() {
            return signature;
        }

        String @org.jspecify.annotations.Nullable [] exceptions() {
            return exceptions == null ? null : exceptions.clone();
        }

        void replay(MethodVisitor target) {
            for (Consumer<MethodVisitor> action : actions) {
                action.accept(target);
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            if ("java/lang/Object".equals(owner) && "wait".equals(name)) {
                directlyWaits = true;
            } else if (owner.equals(ownerInternalName)) {
                calledLocalMethods.add(name + descriptor);
            } else if (reachesWaitingMethod(owner, name + descriptor, typePool, related)) {
                directlyWaits = true;
            }
            actions.add(mv -> mv.visitMethodInsn(opcode, owner, name, descriptor, isInterface));
        }

        @Override
        public void visitInsn(int opcode) {
            actions.add(mv -> mv.visitInsn(opcode));
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            actions.add(mv -> mv.visitIntInsn(opcode, operand));
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            actions.add(mv -> mv.visitVarInsn(opcode, varIndex));
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            actions.add(mv -> mv.visitTypeInsn(opcode, type));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            actions.add(mv -> mv.visitFieldInsn(opcode, owner, name, descriptor));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                                           Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
            Object[] clonedArguments = bootstrapMethodArguments == null
                    ? null : bootstrapMethodArguments.clone();
            actions.add(mv -> mv.visitInvokeDynamicInsn(name, descriptor,
                    bootstrapMethodHandle, clonedArguments));
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            actions.add(mv -> mv.visitJumpInsn(opcode, label));
        }

        @Override
        public void visitLabel(Label label) {
            actions.add(mv -> mv.visitLabel(label));
        }

        @Override
        public void visitLdcInsn(Object value) {
            actions.add(mv -> mv.visitLdcInsn(value));
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            actions.add(mv -> mv.visitIincInsn(varIndex, increment));
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            Label[] clonedLabels = labels == null ? null : labels.clone();
            actions.add(mv -> mv.visitTableSwitchInsn(min, max, dflt, clonedLabels));
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            int[] clonedKeys = keys == null ? null : keys.clone();
            Label[] clonedLabels = labels == null ? null : labels.clone();
            actions.add(mv -> mv.visitLookupSwitchInsn(dflt, clonedKeys, clonedLabels));
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            actions.add(mv -> mv.visitMultiANewArrayInsn(descriptor, numDimensions));
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            actions.add(mv -> mv.visitTryCatchBlock(start, end, handler, type));
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {
            actions.add(mv -> mv.visitLocalVariable(name, descriptor, signature, start, end, index));
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            actions.add(mv -> mv.visitLineNumber(line, start));
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            actions.add(mv -> mv.visitMaxs(maxStack, maxLocals));
        }

        @Override
        public void visitEnd() {
            actions.add(MethodVisitor::visitEnd);
        }

        @Override
        public void visitParameter(String name, int access) {
            actions.add(mv -> mv.visitParameter(name, access));
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
            actions.add(mv -> mv.visitAnnotableParameterCount(parameterCount, visible));
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            actions.add(mv -> mv.visitAttribute(attribute));
        }

        @Override
        public void visitCode() {
            actions.add(MethodVisitor::visitCode);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            Object[] clonedLocal = local == null ? null : local.clone();
            Object[] clonedStack = stack == null ? null : stack.clone();
            actions.add(mv -> mv.visitFrame(type, numLocal, clonedLocal, numStack, clonedStack));
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            BufferedAnnotationVisitor av = new BufferedAnnotationVisitor();
            actions.add(mv -> av.replay(mv.visitAnnotationDefault()));
            return av;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            BufferedAnnotationVisitor av = new BufferedAnnotationVisitor();
            actions.add(mv -> av.replay(mv.visitAnnotation(descriptor, visible)));
            return av;
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            BufferedAnnotationVisitor av = new BufferedAnnotationVisitor();
            actions.add(mv -> av.replay(mv.visitTypeAnnotation(typeRef, typePath, descriptor, visible)));
            return av;
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
                                                          boolean visible) {
            BufferedAnnotationVisitor av = new BufferedAnnotationVisitor();
            actions.add(mv -> av.replay(mv.visitParameterAnnotation(parameter, descriptor, visible)));
            return av;
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            BufferedAnnotationVisitor av = new BufferedAnnotationVisitor();
            actions.add(mv -> av.replay(mv.visitInsnAnnotation(typeRef, typePath, descriptor, visible)));
            return av;
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                         String descriptor, boolean visible) {
            BufferedAnnotationVisitor av = new BufferedAnnotationVisitor();
            actions.add(mv -> av.replay(mv.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible)));
            return av;
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                              Label[] start, Label[] end,
                                                              int[] index, String descriptor,
                                                              boolean visible) {
            BufferedAnnotationVisitor av = new BufferedAnnotationVisitor();
            Label[] clonedStart = start == null ? null : start.clone();
            Label[] clonedEnd = end == null ? null : end.clone();
            int[] clonedIndex = index == null ? null : index.clone();
            actions.add(mv -> av.replay(mv.visitLocalVariableAnnotation(typeRef, typePath,
                    clonedStart, clonedEnd, clonedIndex, descriptor, visible)));
            return av;
        }
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
            // Only the monitor table carries a back-edge hook, and only it pays for buffering.
            // Every other table emits each method as it arrives.
            Target loopHook = null;
            for (Target target : targets) {
                if (target.hasLoopBackEdgeHook()) {
                    loopHook = target;
                    break;
                }
            }
            if (loopHook == null) {
                return new ClassVisitor(Opcodes.ASM9, classVisitor) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        MethodVisitor delegate =
                                super.visitMethod(access, name, descriptor, signature, exceptions);
                        return new SubstitutingMethodVisitor(delegate, targets, typePool, assignable,
                                access, instrumentedType.getInternalName(), Collections.emptySet(), null);
                    }
                };
            }
            // Past here the whole class is buffered and replayed at visitEnd, because a loop
            // around a wait can be in a method declared before the one that waits (#707) and a
            // MethodVisitor learns of the callee only as it passes. The cheaper shapes are both
            // unavailable: an AsmVisitorWrapper is handed a ClassVisitor and never the class
            // bytes, so there is nothing to run a second ClassReader over, and Byte Buddy's
            // shaded ASM ships no tree API to hold a method in. Buffering is bounded by one
            // class, which the format already bounds; what it costs is paid once per woven class.
            Target finalLoopHook = loopHook;
            List<BufferedMethod> bufferedMethods = new ArrayList<>();
            return new ClassVisitor(Opcodes.ASM9, classVisitor) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    BufferedMethod bm = new BufferedMethod(access, name, descriptor, signature,
                            exceptions, instrumentedType.getInternalName(), typePool, assignable);
                    bufferedMethods.add(bm);
                    return bm;
                }

                @Override
                public void visitEnd() {
                    Set<String> waitingMethods = new HashSet<>();
                    for (BufferedMethod bm : bufferedMethods) {
                        if (bm.directlyWaits()) {
                            waitingMethods.add(bm.methodKey());
                        }
                    }
                    boolean changed = true;
                    while (changed) {
                        changed = false;
                        for (BufferedMethod bm : bufferedMethods) {
                            String key = bm.methodKey();
                            if (!waitingMethods.contains(key)) {
                                for (String callee : bm.calledLocalMethods()) {
                                    if (waitingMethods.contains(callee)) {
                                        waitingMethods.add(key);
                                        changed = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    String ownerPrefix = instrumentedType.getInternalName() + ".";
                    for (String m : waitingMethods) {
                        KNOWN_WAITING_METHODS.add(ownerPrefix + m);
                        boolean newToTheIndex = WAITING_OWNERS_BY_SIGNATURE
                                .computeIfAbsent(m, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                                .add(instrumentedType.getInternalName());
                        if (newToTheIndex) {
                            // Callers woven before this class could not resolve the signature and
                            // carry no mark. Hand them over to be woven again (#715).
                            StaleCallerRetransformer.signatureBecameWaiting(
                                    m, instrumentedType.getInternalName());
                        }
                    }
                    for (BufferedMethod bm : bufferedMethods) {
                        MethodVisitor downstream = super.visitMethod(bm.access(), bm.name(),
                                bm.descriptor(), bm.signature(), bm.exceptions());
                        SubstitutingMethodVisitor smv = new SubstitutingMethodVisitor(
                                downstream, targets, typePool, assignable, bm.access(),
                                instrumentedType.getInternalName(), waitingMethods, finalLoopHook);
                        bm.replay(smv);
                    }
                    super.visitEnd();
                }
            };
        }
    }

    /** Rewrites matching virtual and interface invocations; passes every other instruction through. */
    private static final class SubstitutingMethodVisitor extends MethodVisitor {

        /** The bootstrap owner whose implementation handles a method reference can name (#550). */
        private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

        /** {@code LambdaMetafactory.FLAG_SERIALIZABLE}, which marks a lambda that must be left alone. */
        private static final int FLAG_SERIALIZABLE = 1;

        private final List<Target> targets;
        private final TypePool typePool;
        private final Map<String, Boolean> assignable;

        /** Whether the method being visited takes a monitor from its {@code ACC_SYNCHRONIZED} flag. */
        private final boolean enclosingIsSynchronized;

        /** Whether that monitor is the class rather than {@code this}. */
        private final boolean enclosingIsStatic;

        /** The class being woven, for loading its {@code Class} as a static method's monitor. */
        private final String owningClassInternalName;

        /** Set of method keys (name + descriptor) in this class known to invoke Object.wait. */
        private final Set<String> waitingMethods;

        /** Target supplying the loop back-edge hook when a waiting method is called. */
        private final @org.jspecify.annotations.Nullable Target loopHookTarget;

        /**
         * Signatures this method called that could not be resolved to a waiting method, kept
         * until the method ends (#715). Under load-time weaving an unresolved call usually means
         * the callee's class has not been loaded yet, not that it never waits.
         */
        private final List<String> unresolvedCalls = new ArrayList<>();

        /**
         * Whether any jump in this method went backwards, which is the only thing that can turn
         * an unresolved call into a missing mark. A method without one is not worth remembering.
         */
        private boolean sawBackwardJump;

        /** Positions where conditional jump instructions were visited. */
        private final List<Integer> conditionalJumpsAt = new ArrayList<>();

        /**
         * The target label of each unconditional {@code goto}, keyed by the position it was
         * visited at, so the instruction in front of a label can be asked whether it was one
         * (#710). Only a loop-rotating compiler emits the shape it answers
         * for, so under javac the lookup runs and never leads to a mark.
         */
        private final Map<Integer, Label> unconditionalJumpsAt = new HashMap<>();

        /**
         * The target whose substituted call was the instruction just emitted, when that target
         * has a discarded-result hook; {@code null} after anything else.
         *
         * <p>A one-instruction lookahead written as a one-instruction memory. Its only consumer
         * is {@link #visitInsn}: a {@code POP} arriving while it is set is the caller discarding
         * the result, and is replaced by the hook. Every other visit method clears it, including
         * the ones that emit no instruction, because a label or a frame between the call and a
         * {@code POP} means the {@code POP} is reachable another way and may not be consuming
         * this call's result at all. A stale value here would rewrite an unrelated {@code POP}
         * into a call whose parameter does not match what is on the stack, which is a
         * {@code VerifyError} in somebody else's class at load time - so the override list is
         * gated by {@code SubstitutingVisitorClearsLookaheadEverywhereTest}.
         */
        private @org.jspecify.annotations.Nullable Target justSubstituted;

        /**
         * The most recent substituted target that wants a loop back-edge hook; {@code null} until
         * there is one, which keeps a method without such a call free of the check in
         * {@link #visitJumpInsn}.
         */
        private @org.jspecify.annotations.Nullable Target loopTracked;

        /** A count of labels and tracked calls visited so far: the order, not an offset. */
        private int position;

        /** {@link #position} of the latest tracked call. */
        private int loopTrackedAt;

        /** Where each label was visited, so a jump to one already seen reads as backward. */
        private final Map<Label, Integer> labelsVisitedAt = new HashMap<>();

        SubstitutingMethodVisitor(MethodVisitor delegate, List<Target> targets,
                                  TypePool typePool, Map<String, Boolean> assignable,
                                  int access, String owningClassInternalName,
                                  Set<String> waitingMethods,
                                  @org.jspecify.annotations.Nullable Target loopHookTarget) {
            super(Opcodes.ASM9, delegate);
            this.targets = targets;
            this.typePool = typePool;
            this.assignable = assignable;
            this.enclosingIsSynchronized = (access & Opcodes.ACC_SYNCHRONIZED) != 0;
            this.enclosingIsStatic = (access & Opcodes.ACC_STATIC) != 0;
            this.owningClassInternalName = owningClassInternalName;
            this.waitingMethods = waitingMethods;
            this.loopHookTarget = loopHookTarget;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            justSubstituted = null;
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
                        justSubstituted = target.hasDiscardVariant() ? target : null;
                        if (target.hasLoopBackEdgeHook()) {
                            loopTracked = target;
                            loopTrackedAt = position;
                            position++;
                        }
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
            if (loopHookTarget != null
                    && ((owner.equals(owningClassInternalName) && waitingMethods.contains(name + descriptor))
                    || reachesWaitingMethod(owner, name + descriptor, typePool, assignable))) {
                loopTracked = loopHookTarget;
                loopTrackedAt = position;
                position++;
            } else if (loopHookTarget != null && couldBeWovenLater(owner, owningClassInternalName)) {
                // Not resolvable now, which under load-time weaving usually means the callee's
                // class has not been loaded yet rather than that it does not wait (#715). Kept
                // until this method ends, and handed over only if the method turned out to have a
                // loop in it: a call with no backward jump anywhere near it can never produce a
                // mark, so remembering it would only cost strings.
                unresolvedCalls.add(name + descriptor);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInsn(int opcode) {
            Target discarded = justSubstituted;
            justSubstituted = null;
            if (opcode == Opcodes.POP && discarded != null) {
                // The instruction after a substituted offer is a POP exactly when the caller
                // discarded the boolean, which is the defect itself (#454). The hook takes the one
                // category-1 value the POP would have taken and returns nothing, so the frame
                // after it is the frame after the POP: no branch, no handler, no new frames.
                super.visitMethodInsn(Opcodes.INVOKESTATIC, discarded.hookOwnerInternalName(),
                        discarded.discardHookName(), discarded.discardHookDescriptor(), false);
                return;
            }
            super.visitInsn(opcode);
        }

        // --- Everything below clears the lookahead and delegates. The list is every visit method
        //     MethodVisitor declares, minus the deprecated four-argument visitMethodInsn (which
        //     ASM routes through the five-argument one above) - and a test enumerates the class
        //     to keep it that way, because a method missing here is the stale-flag VerifyError.

        @Override
        public void visitParameter(String name, int access) {
            justSubstituted = null;
            super.visitParameter(name, access);
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            justSubstituted = null;
            return super.visitAnnotationDefault();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            justSubstituted = null;
            return super.visitAnnotation(descriptor, visible);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            justSubstituted = null;
            return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
            justSubstituted = null;
            super.visitAnnotableParameterCount(parameterCount, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
            justSubstituted = null;
            return super.visitParameterAnnotation(parameter, descriptor, visible);
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            justSubstituted = null;
            super.visitAttribute(attribute);
        }

        @Override
        public void visitCode() {
            justSubstituted = null;
            super.visitCode();
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            justSubstituted = null;
            super.visitFrame(type, numLocal, local, numStack, stack);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            justSubstituted = null;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            justSubstituted = null;
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            justSubstituted = null;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            justSubstituted = null;
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            justSubstituted = null;
            Target target = methodReferenceTarget(bootstrapMethodHandle, bootstrapMethodArguments);
            if (target == null) {
                super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle,
                        bootstrapMethodArguments);
                return;
            }
            Object[] redirected = bootstrapMethodArguments.clone();
            redirected[1] = new Handle(Opcodes.H_INVOKESTATIC, target.hookOwnerInternalName(),
                    target.hookMethodName(), target.hookDescriptor(), false);
            super.visitInvokeDynamicInsn(name, capturingReceiverAs(descriptor, target),
                    bootstrapMethodHandle, redirected);
        }

        /**
         * {@return the table entry a method reference in this {@code invokedynamic} names, or {@code null}}
         *
         * <p>A method reference such as {@code builder::append} is not an {@code invokevirtual}.
         * javac emits an {@code invokedynamic} to {@code LambdaMetafactory} whose second bootstrap
         * argument is a handle to {@code StringBuilder.append(String)}, and the JVM makes the call
         * from a hidden class it spins at link time, which no agent can weave. So the call was
         * never observed, whatever the table said (#550). Replacing that handle with a static
         * handle to the hook moves the call back into view: the factory accepts a static
         * implementation whose first parameter is the receiver for both a bound reference
         * ({@code builder::append}, receiver captured) and an unbound one
         * ({@code StringBuilder::append}, receiver passed), and the hook's descriptor is the
         * matched call's with the receiver prepended, so the lambda's shape is unchanged.
         *
         * <p>Deliberately narrow, because this is the one {@code invokedynamic} the visitor
         * changes. Only {@code LambdaMetafactory} bootstraps are read, and only through ASM's
         * {@link Handle}, never a constant parser: parsing every bootstrap's constants is what made
         * every Java record fail to instrument under {@code MemberSubstitution}, and
         * {@code ObjectMethods}, {@code StringConcatFactory} and everything else pass through as
         * the same array. A serializable lambda is left alone too: its generated
         * {@code $deserializeLambda$} compares the implementation method with the one it was
         * compiled against, so a rewritten handle would make it fail to deserialize.
         *
         * @param bootstrap the bootstrap method handle
         * @param arguments the bootstrap arguments as ASM decoded them
         */
        private @org.jspecify.annotations.Nullable Target methodReferenceTarget(Handle bootstrap,
                                                                               Object[] arguments) {
            if (!LAMBDA_METAFACTORY.equals(bootstrap.getOwner())
                    || arguments.length < 3
                    || !(arguments[1] instanceof Handle implementation)) {
                return null;
            }
            boolean alternate = "altMetafactory".equals(bootstrap.getName());
            if (!alternate && !"metafactory".equals(bootstrap.getName())) {
                return null;
            }
            if (alternate && arguments.length > 3 && arguments[3] instanceof Integer flags
                    && (flags & FLAG_SERIALIZABLE) != 0) {
                return null;
            }
            return targetForReference(implementation);
        }

        /**
         * {@return the {@code invokedynamic} descriptor, with a captured receiver typed as the hook takes it}
         *
         * <p>{@code LambdaMetafactory} requires each captured argument's type to equal the
         * implementation's parameter type exactly, not merely be assignable to it. A bound
         * {@code lock::lock} captures a {@code ReentrantLock} and the hook takes a {@code Lock}, so
         * the factory refused the rewritten handle with "Type mismatch in captured lambda parameter
         * 0" until the captured receiver was declared as the hook's type. The value on the stack is
         * unchanged and is a subtype of that type, which the verifier accepts. An unbound
         * reference captures nothing, and the factory adapts a non-captured argument leniently, so
         * its descriptor is returned as it was.
         *
         * @param descriptor the call site's {@code invokedynamic} descriptor
         * @param target     the entry the reference was redirected to
         */
        private String capturingReceiverAs(String descriptor, Target target) {
            if (target.isStatic()) {
                return descriptor;
            }
            Type[] captured = Type.getArgumentTypes(descriptor);
            if (captured.length == 0 || captured[0].getSort() != Type.OBJECT) {
                return descriptor;
            }
            Type receiver = Type.getObjectType(target.receiverType().getInternalName());
            if (captured[0].equals(receiver)) {
                return descriptor;
            }
            captured[0] = receiver;
            return Type.getMethodDescriptor(Type.getReturnType(descriptor), captured);
        }

        /**
         * {@return the table entry a method-reference handle names, or {@code null}}
         *
         * <p>The same matching as an invocation instruction: name and full descriptor, and an
         * owner assignable to the entry's type for a virtual or interface handle, or the exact
         * owner for a static one.
         *
         * @param implementation the lambda factory's implementation handle
         */
        private @org.jspecify.annotations.Nullable Target targetForReference(Handle implementation) {
            int tag = implementation.getTag();
            boolean virtual = tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE;
            boolean isStaticHandle = tag == Opcodes.H_INVOKESTATIC;
            if (!virtual && !isStaticHandle) {
                return null;
            }
            for (Target target : targets) {
                if (!implementation.getName().equals(target.methodName())
                        || !implementation.getDesc().equals(target.callSiteDescriptor())) {
                    continue;
                }
                if (virtual && !target.isStatic()
                        && ownerIsAssignable(implementation.getOwner(), target)) {
                    return target;
                }
                if (isStaticHandle && target.isStatic()
                        && implementation.getOwner().equals(target.receiverType().getInternalName())) {
                    return target;
                }
            }
            return null;
        }

        private static boolean isConditionalJump(int opcode) {
            return (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE)
                    || opcode == Opcodes.IFNULL
                    || opcode == Opcodes.IFNONNULL;
        }

        private boolean hasConditionalJumpBetween(int from, int to) {
            for (int pos : conditionalJumpsAt) {
                if (pos > from && pos < to) {
                    return true;
                }
            }
            return false;
        }

        /**
         * {@return whether a conditional back-edge closes a rotated predicate loop}
         *
         * <p>A compiler that rotates loops emits {@code goto test; body; test: if (...) goto
         * body}, so a correct {@code while} closes with the test itself and puts it after the
         * wait - the shape the goto half of the rule was written to refuse (#710). What tells the
         * two apart is the jump into the loop: a rotated loop reads its predicate before it runs
         * the body, so an unconditional {@code goto} sits immediately in front of the loop head
         * and lands on the test. A {@code do}/{@code while} falls straight into its body and has
         * no such jump, which is why it stays unmarked here as it does under javac.
         *
         * <p>The entry jump also has to land inside the loop, after the wait. A {@code break}
         * compiled in front of a {@code do}/{@code while} leaves a {@code goto} in front of the
         * loop head too, but one that jumps clear of the loop: its target is still ahead of the
         * reader and so has no recorded position, which is what tells the two apart. Every label
         * that does have one was visited before this back-edge, so being after the wait is the
         * whole of the range.
         *
         * @param headAt the position the back-edge target was visited at
         */
        private boolean closesRotatedLoop(int headAt) {
            Label entry = unconditionalJumpsAt.get(headAt - 1);
            if (entry == null) {
                return false;
            }
            Integer testAt = labelsVisitedAt.get(entry);
            return testAt != null && testAt > loopTrackedAt;
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            justSubstituted = null;
            if (labelsVisitedAt.containsKey(label)) {
                sawBackwardJump = true;
            }
            Target tracked = loopTracked;
            if (tracked != null) {
                // A jump back to a label visited before the tracked call comes over it, and
                // marks a loop around a wait (#694). Two things have to hold for that loop to
                // be the predicate loop a correct wait belongs in, and each rules out one shape
                // that waits before it has ever read the predicate (#707):
                //
                //   - the back-edge is an unconditional goto. javac puts a while loop's test at
                //     the top and closes the loop with a goto; in do { wait(); } while (!ready)
                //     the back-edge is the test itself, a conditional jump. That is
                //     DoWhileWaitHandOffBean, the twin of LoopWaitHandOffBean.
                //   - a conditional jump stands between the loop's head and the wait, so the
                //     thread reads something before it blocks. A loop closed by continue has the
                //     goto but not that test. WaitLoopShapesSample.continueLoop, and
                //     endlessWait, which no fixture can run.
                //
                // A compiler that rotates loops closes a correct while with the test itself, so
                // the goto half would refuse it and report the wait. ECJ does exactly that, and
                // closesRotatedLoop is the third case: a conditional back-edge whose loop head is
                // entered by a goto that lands on the test (#710). javac and kotlinc 2.4.10 do
                // not rotate, so for them that case never fires.
                //
                // The hook goes in front of the jump and takes nothing, so a conditional jump
                // still finds its operands, no branch or frame is added, and it runs whether or
                // not the jump is taken.
                Integer visitedAt = labelsVisitedAt.get(label);
                boolean marks = visitedAt != null && visitedAt < loopTrackedAt
                        && (opcode == Opcodes.GOTO
                                ? hasConditionalJumpBetween(visitedAt, loopTrackedAt)
                                : isConditionalJump(opcode)
                                        && closesRotatedLoop(visitedAt));
                if (marks) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, tracked.hookOwnerInternalName(),
                            tracked.loopBackEdgeHookName(), "()V", false);
                }
            }
            if (isConditionalJump(opcode)) {
                conditionalJumpsAt.add(position);
                position++;
            } else if (opcode == Opcodes.GOTO) {
                unconditionalJumpsAt.put(position, label);
                position++;
            }
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(Label label) {
            justSubstituted = null;
            labelsVisitedAt.put(label, position);
            position++;
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            justSubstituted = null;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            justSubstituted = null;
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            justSubstituted = null;
            conditionalJumpsAt.add(position);
            position++;
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            justSubstituted = null;
            conditionalJumpsAt.add(position);
            position++;
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            justSubstituted = null;
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            justSubstituted = null;
            return super.visitInsnAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            justSubstituted = null;
            super.visitTryCatchBlock(start, end, handler, type);
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String descriptor, boolean visible) {
            justSubstituted = null;
            return super.visitTryCatchAnnotation(typeRef, typePath, descriptor, visible);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            justSubstituted = null;
            super.visitLocalVariable(name, descriptor, signature, start, end, index);
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String descriptor, boolean visible) {
            justSubstituted = null;
            return super.visitLocalVariableAnnotation(typeRef, typePath, start, end, index, descriptor, visible);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            justSubstituted = null;
            super.visitLineNumber(line, start);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            justSubstituted = null;
            super.visitMaxs(maxStack, maxLocals);
        }

        @Override
        public void visitEnd() {
            justSubstituted = null;
            if (sawBackwardJump) {
                // Only now is it known that this method has a loop at all, which is what makes an
                // unresolved call worth remembering (#715).
                for (String signature : unresolvedCalls) {
                    StaleCallerRetransformer.recordUnresolvedCall(owningClassInternalName, signature);
                }
            }
            super.visitEnd();
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
