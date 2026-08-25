package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Emits named events to registered listeners.
 *
 * <p><strong>Bug:</strong> The constructor calls {@link EventRegistry#register(EventEmitter) EventRegistry.register(this)}
 * before setting {@code this.name} and {@code this.listeners}. Other threads that
 * access the registry immediately after registration will see a partially constructed
 * object: {@code name} is {@code null} and {@code listeners} is {@code null}, causing
 * {@link NullPointerException} at runtime.
 *
 * <p><strong>Fix:</strong> Initialize all fields first, then register {@code this} as
 * the very last statement in the constructor. Alternatively, use a static factory method
 * that registers only after the constructor returns.
 */
public class EventEmitter {

    private static volatile Consumer<EventEmitter> onConstructionStart = emitter -> { };

    private static volatile Consumer<EventEmitter> onConstructionEnd = emitter -> { };

    private String name;
    private List<String> listeners;

    /**
     * Constructs an EventEmitter.
     * Bug: registers 'this' before fields are initialized.
     */
    public EventEmitter(String name) {
        onConstructionStart.accept(this);
        // BUG: this escapes before name and listeners are set. Anything the registry does with
        // the reference from here on is looking at a half-built object.
        EventRegistry.register(this);
        this.name = name;
        this.listeners = new ArrayList<>();
        onConstructionEnd.accept(this);
    }

    /**
     * Installs the hooks ConstructorSafetyValidator needs. No-ops by default, so production
     * behaviour is unchanged whether or not a test is watching.
     *
     * <p>Static, because the object does not exist yet when the first one has to fire.
     *
     * @param start called with {@code this} at the top of the constructor
     * @param end   called with {@code this} once every field is set
     */
    public static void observeConstruction(Consumer<EventEmitter> start,
                                           Consumer<EventEmitter> end) {
        onConstructionStart = start;
        onConstructionEnd = end;
    }

    /** Emits an event to all listeners. */
    public void emit(String event) {
        // NullPointerException if listeners is still null (partially constructed)
        listeners.add(event);
    }

    /** Returns the emitter name. */
    public String getName() {
        return name;
    }

    /** Returns the list of emitted events. */
    public List<String> getListeners() {
        return listeners;
    }
}
