package se.deversity.asynctest;

import java.util.LinkedHashMap;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.diagnostics.ConstructorSafetyValidator;
import se.deversity.asynctest.diagnostics.SelfGuard;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks the agent inserts around a construction in a woven class, to tell the library what only
 * the constructor call knows.
 *
 * <p>A {@link LinkedHashMap}'s order is the first case (#807). A {@code get} on an
 * access-ordered map, the usual LRU cache, relinks the entry it returns, so it writes the map and
 * one read lock over it guards nothing. Whether a map is access-ordered is a private field of
 * {@code java.util}, which a default JVM does not open to the library, and the three-argument
 * constructor is the only way to set it. So the agent reads it there: before a woven
 * {@code LinkedHashMap(int, float, boolean)} call, which may be a {@code new LinkedHashMap} or a
 * subclass's superclass call, it hands the third argument to
 * {@link #linkedHashMapAccessOrder(boolean)}, and once the call returned it hands the built map to
 * {@link #linkedHashMapConstructed(LinkedHashMap)}.
 *
 * <p>The two halves pair up on one thread with nothing woven between them: the map's own
 * constructor runs in between, and it is JDK code. An argument whose constructor then threw is
 * left behind and overwritten by the next one, since a constructed-hook call is always the one
 * right after its argument's.
 *
 * <p>Unseen: a map built by a constructor reference or reflection, or in a class outside
 * {@code includes=}. Its order stays unknown, which judges its gets as the library did before.
 *
 * <p>A constructor's return is the other case (#791). {@link ConstructorSafetyValidator} is told
 * where a construction starts, by a record call from inside the constructor, and without an end
 * it judges a construction by whether a constructor of the class is still on the constructing
 * thread's stack, which a later instance's constructor answers wrongly. So the agent calls
 * {@link #constructorReturned(Object, String)} before every return of every constructor in a woven
 * class, and {@link #constructorResumed(Object, String)} after a {@code this(...)} delegation,
 * whose callee returned while construction goes on. These run for every object a woven class
 * builds, so they cost a check of an empty map unless a construction is being tracked.
 *
 * @since 1.12.3
 */
@API(status = Status.INTERNAL)
@AIContract(reason = "Called from bytecode the agent inserts, not from source: ConstructionWeaver matches these method names and erased signatures at weave time, so none can change independently of it. These are inserted calls, not substitutions: there is no original operation to perform, and each must hand back exactly what the stack held (linkedHashMapAccessOrder returns its argument unchanged, or the constructor would build a map in the wrong order). They run inside user constructors, so they must never throw. constructorReturned and constructorResumed run for every object any woven class builds: while no construction is tracked they must stay a check of an empty map, with no allocation and no stack walk.")
public final class AgentConstructionHooks {

    /**
     * The order argument of the three-argument {@code LinkedHashMap} constructor call this thread
     * is about to make, until the map it builds is handed over.
     */
    private static final ThreadLocal<Boolean> PENDING_ACCESS_ORDER = new ThreadLocal<>();

    private AgentConstructionHooks() {
    }

    /**
     * Called with the third argument of a woven {@code LinkedHashMap(int, float, boolean)} call,
     * right before the call.
     *
     * @param accessOrder the argument: {@code true} builds the map in access order
     * @return {@code accessOrder}, which the constructor call then consumes
     */
    public static boolean linkedHashMapAccessOrder(boolean accessOrder) {
        PENDING_ACCESS_ORDER.set(accessOrder);
        return accessOrder;
    }

    /**
     * Called with the map a woven {@code LinkedHashMap(int, float, boolean)} call just built, and
     * reports its order to {@link SelfGuard}.
     *
     * @param map the built map, a subclass instance for a superclass call; the weaver hands over the
     *            reference the constructor initialised, so it is never {@code null}
     */
    // The constructor call builds exactly this type, and the weaver matches the parameter.
    @SuppressWarnings("PMD.LooseCoupling")
    public static void linkedHashMapConstructed(LinkedHashMap<?, ?> map) {
        Boolean accessOrder = PENDING_ACCESS_ORDER.get();
        if (accessOrder != null) {
            SelfGuard.linkedHashMapBuilt(map, accessOrder);
        }
    }

    /**
     * Called right before a constructor of a woven class returns normally.
     *
     * @param instance       the object the constructor built, {@code this}
     * @param declaringClass the binary name of the class declaring the constructor; the object's
     *                       construction ends only when it is the object's own class, since a
     *                       superclass constructor returns before the subclass's body runs
     */
    public static void constructorReturned(Object instance, String declaringClass) {
        ConstructorSafetyValidator.constructorReturned(instance, declaringClass);
    }

    /**
     * Called right after a {@code this(...)} delegation in a constructor of a woven class: the
     * constructor delegated to has returned, and the one that delegated goes on constructing.
     *
     * @param instance       the object under construction, {@code this}
     * @param declaringClass the binary name of the class declaring both constructors
     */
    public static void constructorResumed(Object instance, String declaringClass) {
        ConstructorSafetyValidator.constructorResumed(instance, declaringClass);
    }
}
