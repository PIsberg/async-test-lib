package se.deversity.asynctest;

import java.util.LinkedHashMap;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import se.deversity.asynctest.diagnostics.SelfGuard;
import se.deversity.vibetags.annotations.AIContract;

/**
 * Hooks the agent inserts around a construction in a woven class, to tell the library what only
 * the constructor call knows.
 *
 * <p>A {@link LinkedHashMap}'s order is the case this exists for (#807). A {@code get} on an
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
 * @since 1.12.3
 */
@API(status = Status.INTERNAL)
@AIContract(reason = "Called from bytecode the agent inserts, not from source: ConstructionWeaver matches these method names and erased signatures at weave time, so none can change independently of it. These are inserted calls, not substitutions: there is no original operation to perform, and each must hand back exactly what the stack held (linkedHashMapAccessOrder returns its argument unchanged, or the constructor would build a map in the wrong order). They run inside user constructors, so they must never throw and must not allocate per call once a thread has made its first map.")
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
}
