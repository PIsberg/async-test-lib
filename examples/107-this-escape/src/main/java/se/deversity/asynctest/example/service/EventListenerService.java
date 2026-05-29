package se.deversity.asynctest.example.service;

import java.util.List;

/**
 * Service that registers itself with a shared listener registry.
 *
 * <p><strong>Bug:</strong> The constructor publishes {@code this} into a shared
 * collection ({@code sharedRegistry.add(this)}) <em>before</em> construction has
 * completed. Another thread iterating the registry can observe this instance while
 * {@code ready} and {@code config} are still being assigned. Because the fields are
 * non-final, there is no happens-before / final-field visibility guarantee, so a
 * concurrent reader may see a partially constructed object (e.g. {@code ready == false}
 * or {@code config == 0}).
 *
 * <p><strong>Fix:</strong> Never let {@code this} escape from a constructor. Use a
 * static factory that constructs the object fully, then registers it via a separate
 * {@code init()}/{@code start()} step once all fields are set:
 * <pre>{@code
 * public static EventListenerService create(List<Object> registry) {
 *     EventListenerService s = new EventListenerService(); // fully constructed
 *     registry.add(s);                                     // safe: publish after build
 *     return s;
 * }
 * }</pre>
 */
public class EventListenerService {

    private volatile boolean ready;
    private int config;

    /**
     * Constructs the service and registers it with the shared registry.
     *
     * <p>Not safe for concurrent use: {@code this} escapes before the fields below
     * are assigned.
     *
     * @param sharedRegistry registry shared across threads
     */
    public EventListenerService(List<Object> sharedRegistry) {
        sharedRegistry.add(this); // BUG: this escapes before construction completes
        this.config = 42;
        this.ready = true; // assigned AFTER the escape — readers may miss this
    }

    /** Returns whether construction completed. */
    public boolean isReady() {
        return ready;
    }
}
