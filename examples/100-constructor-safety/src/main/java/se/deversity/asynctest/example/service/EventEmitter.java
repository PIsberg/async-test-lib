package se.deversity.asynctest.example.service;

import java.util.ArrayList;
import java.util.List;

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

    private String name;
    private List<String> listeners;

    /**
     * Constructs an EventEmitter.
     * Bug: registers 'this' before fields are initialized.
     */
    public EventEmitter(String name) {
        // BUG: this escapes before name and listeners are set
        EventRegistry.register(this);
        this.name = name;
        this.listeners = new ArrayList<>();
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
