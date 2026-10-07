package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * The per-instance scaffolding of a detector that tracks state for each object it is told about,
 * written once (#918).
 *
 * <p>Most detectors carried their own copy: an identity-keyed map, a record path that looks the
 * instance up and registers it on first sight, a label for an object the test gave no name. The
 * copies were where defects kept recurring. #564 replaced identity-hash keys that merged two
 * objects one map at a time, and a get, null check, put on a record path handed two racing
 * threads two states and lost one thread's events. Here the lookup is a {@code get} with the
 * thread's reused {@link IdentityKey#lookup} key, which allocates nothing on the common path
 * (#812), and the first sighting is one {@code computeIfAbsent}, so racing threads get one state.
 *
 * <p><strong>Weak keys.</strong> The map holds each instance through an {@link IdentityKey.Weak},
 * so a detector does not keep alive an object the code under test dropped. The entry is never
 * removed: once the instance is collected its key equals only itself, the state stays, and so does
 * any finding the state already latched. What is released is the object, never the evidence.
 * An unnamed instance's label is kept by {@link UnnamedLabels}, which keys it weakly too (#929).
 *
 * <p>Subclasses stay in this package; the class is not API.
 *
 * @param <S> the state kept per tracked instance
 */
abstract class AbstractInstanceDetector<S> {

    /** Labels for objects the test gave no name, numbered per kind within this detector (#860). */
    private final UnnamedLabels unnamedLabels = new UnnamedLabels();

    /** Per-instance state, keyed weakly by identity; see the class javadoc. */
    private final ConcurrentMap<Object, S> states = new ConcurrentHashMap<>();

    /**
     * {@return the state for {@code instance}, registered on first sight}
     *
     * <p>An unnamed instance is labelled by its class's simple name.
     *
     * @param instance the tracked object; callers skip {@code null} before they get here
     * @param name     the test's label for it, or {@code null} for one of its kind
     */
    final S stateFor(Object instance, @Nullable String name) {
        S state = states.get(IdentityKey.lookup(instance));
        return state != null ? state : register(instance, name, null, null);
    }

    /**
     * {@return the state for {@code instance}, registered on first sight}
     *
     * @param instance the tracked object; callers skip {@code null} before they get here
     * @param name     the test's label for it, or {@code null} for one of its kind
     * @param kind     what an unnamed instance is labelled as, such as {@code "executor"}
     */
    final S stateFor(Object instance, @Nullable String name, String kind) {
        S state = states.get(IdentityKey.lookup(instance));
        return state != null ? state : register(instance, name, kind, null);
    }

    /**
     * {@return the state for {@code instance}, registered on first sight with {@code factory}}
     *
     * <p>For a registration path whose state needs more than a label, such as a capacity the
     * caller declares. The factory, usually a capturing lambda, is allocated on every call, so
     * this belongs on a path called once per instance, not on a record path.
     *
     * @param instance the tracked object; callers skip {@code null} before they get here
     * @param name     the test's label for it, or {@code null} for one of its kind
     * @param kind     what an unnamed instance is labelled as
     * @param factory  builds the state from the label; called at most once per instance
     */
    final S stateFor(Object instance, @Nullable String name, String kind, Function<String, ? extends S> factory) {
        S state = states.get(IdentityKey.lookup(instance));
        return state != null ? state : register(instance, name, kind, factory);
    }

    /**
     * {@return the state for {@code instance}, or {@code null} when it was never registered}
     *
     * <p>For a record path that only counts against an instance the test registered first.
     *
     * @param instance the object to look up; callers skip {@code null} before they get here
     */
    final @Nullable S trackedState(Object instance) {
        return states.get(IdentityKey.lookup(instance));
    }

    /** Forgets every registered state, for a detector's {@code reset()}. */
    final void clearStates() {
        states.clear();
    }

    private S register(Object instance, @Nullable String name, @Nullable String kind,
            @Nullable Function<String, ? extends S> factory) {
        // The label, and the weak key, are built only when the instance is first seen.
        return states.computeIfAbsent(new IdentityKey.Weak(instance, null), key -> {
            String label = label(instance, name, kind);
            return factory != null ? factory.apply(label) : newState(instance, label);
        });
    }

    /**
     * {@return a fresh state for an instance seen for the first time}
     *
     * <p>Called by the {@code stateFor} overloads without a factory. A detector whose state
     * always needs more than the instance and its label registers through the factory overload
     * and does not override this.
     *
     * @param instance the instance, for a subclass that reads something from it once
     * @param label    the test's name for it, or a label of its kind
     * @throws UnsupportedOperationException unless overridden
     */
    S newState(Object instance, String label) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " registers its states through a factory");
    }

    /** {@return every state registered so far, as a live read-only view} */
    final Collection<S> states() {
        return Collections.unmodifiableCollection(states.values());
    }

    private String label(Object instance, @Nullable String name, @Nullable String kind) {
        if (name != null) return name;
        return unnamedLabels.of(instance, kind != null ? kind : instance.getClass().getSimpleName());
    }
}
