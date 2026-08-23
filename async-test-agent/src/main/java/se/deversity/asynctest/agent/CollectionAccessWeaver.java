package se.deversity.asynctest.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import se.deversity.vibetags.annotations.AIContract;

/**
 * Rewrites collection calls in woven code so the detectors see the collection itself.
 *
 * <h2>Why substitution rather than more field weaving</h2>
 *
 * <p>{@link FieldAccessWeaver} makes a field access visible, which is enough for a class that
 * mutates its own fields and blind for a class that keeps its state in a collection: the write that
 * races happens inside {@code java.util.HashMap}, and {@code java.} is on the ignore list for good
 * reasons that are not going to change. What the detectors need there is not the field but the
 * receiver of the call, and the receiver is buried under the arguments at the point the weaver sees
 * the invocation.
 *
 * <p>Byte Buddy's {@link MemberSubstitution} solves exactly that: it replaces the invocation with a
 * call to a static method whose first parameter is the receiver, generating the argument handling
 * itself, including the two-slot cases a hand-written spill would have to special-case. Each
 * substitution consumes the same stack the original call consumed and leaves the same value behind,
 * so the operand stack shape at every instruction is unchanged, no branch is introduced, and no
 * member is added. That is what keeps this safe under
 * {@code disableClassFormatChanges()} on the retransformation path, on the same reasoning the
 * field weaver documents.
 *
 * <h2>Why an explicit table</h2>
 *
 * <p>One entry per invocation shape, rather than a blanket match. A user can read this list and
 * know what is observed; the overhead is bounded by it; and the erased signature of each hook is
 * checked at build time by {@code CollectionAccessWeaverTest} rather than discovered as a
 * {@code NoSuchMethodError} inside somebody's suite.
 *
 * @since 1.10.0
 */
@AIContract(reason = "The hook class name and the method names here are the other half of AgentCollectionHooks: they are matched by erased signature at weave time, so renaming a hook or changing a parameter type breaks weaving with a NoSuchMethodError inside user code rather than at compile time. Each entry must consume exactly the stack its original invocation consumed - substitution stays stack-shape-neutral and member-free, which is what keeps retransformation safe under disableClassFormatChanges(). Collection weaving is opt-in (collections=true) because it instruments every listed call in every matched class.")
final class CollectionAccessWeaver {

    /**
     * The library's own package root, assembled rather than written as a literal for the reason
     * {@code FieldAccessWeaver.IGNORED_OWNERS} documents: the Shade plugin rewrites string literals
     * that look like relocated package names, and a silently rewritten prefix here would stop
     * excluding the very class it exists to protect.
     */
    private static final String LIBRARY_ROOT = String.join(".", "se", "deversity", "asynctest") + ".";

    /** The library-side class the substituted calls land in. */
    private static final String HOOKS = LIBRARY_ROOT + "AgentCollectionHooks";

    private CollectionAccessWeaver() {
    }

    /** One weave entry: which invocation to replace, and which hook replaces it. */
    private record Entry(Class<?> declaredBy, String method, String hook, Class<?>... parameters) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry(Map.class, "put", "mapPut", Object.class, Object.class),
            new Entry(Map.class, "get", "mapGet", Object.class),
            new Entry(Map.class, "remove", "mapRemove", Object.class),
            new Entry(Map.class, "containsKey", "mapContainsKey", Object.class),
            new Entry(Collection.class, "add", "collectionAdd", Object.class),
            new Entry(Collection.class, "remove", "collectionRemove", Object.class),
            new Entry(Collection.class, "contains", "collectionContains", Object.class),
            new Entry(Collection.class, "clear", "collectionClear"),
            new Entry(List.class, "get", "listGet", int.class),
            new Entry(List.class, "set", "listSet", int.class, Object.class),
            new Entry(Queue.class, "offer", "queueOffer", Object.class),
            new Entry(Queue.class, "poll", "queuePoll"),
            new Entry(Queue.class, "peek", "queuePeek"));

    /**
     * {@return the substitutions to apply, in table order}
     *
     * @param hooks the class holding the hook methods, resolved in the weaving class loader
     */
    static List<AsmVisitorWrapper> substitutions(Class<?> hooks) {
        List<AsmVisitorWrapper> substitutions = new ArrayList<>(ENTRIES.size());
        for (Entry entry : ENTRIES) {
            substitutions.add(MemberSubstitution.relaxed()
                    .method(invocationMatcher(entry))
                    // Virtual and interface invocations only. A super.get() call is INVOKESPECIAL
                    // on purpose: replacing it with a static that calls receiver.get() would
                    // re-dispatch virtually, land back in the overriding subclass, and recurse
                    // until the stack ran out. That is not hypothetical - it is what the corpus
                    // eval's PassiveExpiringMap subject did, because a decorator that extends its
                    // own abstraction calls super on every operation.
                    .onVirtualCall()
                    .replaceWith(hookMethod(hooks, entry))
                    // Never substitute inside the library itself. AgentCollectionHooks.mapPut ends
                    // by calling Map.put, so weaving it would replace that call with a call to
                    // itself: the first shared map a test touched died with a StackOverflowError
                    // before this matcher existed. Everything the detectors use internally is under
                    // the same root, so one exclusion covers the whole recording path.
                    .on(ElementMatchers.not(
                            ElementMatchers.isDeclaredBy(
                                    ElementMatchers.nameStartsWith(LIBRARY_ROOT)))));
        }
        return substitutions;
    }

    /**
     * Matches the invocation by declaring type and erased parameter list, so a call through the
     * interface and a call through the concrete implementation are both caught, and an unrelated
     * {@code add} on a type that is not a collection is not.
     */
    private static ElementMatcher.Junction<MethodDescription> invocationMatcher(Entry entry) {
        return ElementMatchers.isDeclaredBy(ElementMatchers.isSubTypeOf(entry.declaredBy()))
                .<MethodDescription>and(ElementMatchers.named(entry.method()))
                .and(ElementMatchers.takesArguments(entry.parameters()));
    }

    private static java.lang.reflect.Method hookMethod(Class<?> hooks, Entry entry) {
        Class<?>[] signature = new Class<?>[entry.parameters().length + 1];
        signature[0] = entry.declaredBy();
        System.arraycopy(entry.parameters(), 0, signature, 1, entry.parameters().length);
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

    /** {@return the hook class name the substituted calls land in} */
    static String hooksClassName() {
        return HOOKS;
    }
}
