package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.EventEmitter;
import se.deversity.asynctest.example.service.EventRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for EventEmitter.
 *
 * ========================================================================
 * DETECTOR: ConstructorSafetyValidator
 * ========================================================================
 *
 * THE BUG:
 * EventEmitter's constructor calls EventRegistry.register(this) as its first
 * action, before initializing this.name and this.listeners. Other threads that
 * poll EventRegistry between construction start and completion will see a
 * partially initialized object. Calling getName() returns null; calling emit()
 * throws NullPointerException because listeners is still null.
 *
 * WHY @Test PASSES:
 * Single-threaded construction completes atomically from the test's point of
 * view — no other thread can interleave. The object is always fully initialized
 * by the time any single-threaded test uses it.
 *
 * WHY @AsyncTest DETECTS:
 * With multiple threads each constructing an EventEmitter concurrently,
 * ConstructorSafetyValidator tracks construction start vs. completion and
 * field access timestamps, reporting objects whose fields were accessed before
 * construction was marked complete.
 *
 * FIX:
 * Move EventRegistry.register(this) to the last line of the constructor, after
 * all fields are initialized.
 */
class EventEmitterTest {

    @BeforeEach
    void setUp() {
        EventRegistry.clear();
    }

    @AfterEach
    void tearDown() {
        EventRegistry.clear();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testConstructor_nameIsSet() {
        EventEmitter emitter = new EventEmitter("test-emitter");
        assertEquals("test-emitter", emitter.getName());
    }

    @Test
    void testEmit_addsToListeners() {
        EventEmitter emitter = new EventEmitter("producer");
        emitter.emit("event-1");
        assertTrue(emitter.getListeners().contains("event-1"));
    }

    @Test
    void testRegistry_containsAfterConstruction() {
        EventEmitter emitter = new EventEmitter("registered");
        assertTrue(EventRegistry.getAll().contains(emitter));
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the this-escape
    // -------------------------------------------------------------------------

    /**
     * Multiple threads each construct an EventEmitter and then access its fields.
     * ConstructorSafetyValidator records construction start/end and field access
     * timestamps, detecting when a field was accessed before construction finished.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: move EventRegistry.register(this) to the last line of the constructor
     */
    @Disabled("Remove @Disabled to see the bug detected by ConstructorSafetyValidator")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, validateConstructorSafety = true)
    void test_concurrent_detectsThisEscape() {
        var validator = AsyncTestContext.constructorSafetyValidator();

        // Instrument: mark construction start BEFORE calling new EventEmitter()
        // We instrument a sentinel object to represent the about-to-be-constructed emitter
        Object sentinel = new Object();
        validator.recordConstructionStart(sentinel);

        EventEmitter emitter = new EventEmitter("emitter-" + Thread.currentThread().getName());

        // Instrument: record field access while construction may be incomplete
        validator.recordFieldAccess(sentinel, "name", System.nanoTime());
        String name = emitter.getName(); // could be null if accessed too early

        // Instrument: mark construction end
        validator.recordConstructionEnd(sentinel);

        if (name != null) {
            emitter.emit("event");
        }
    }
}
