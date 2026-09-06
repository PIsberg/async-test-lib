package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.diagnostics.ConstructorSafetyValidator;
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
 * ConstructorSafetyValidator compares the accessing thread against the
 * constructing one, and reports a field touched by a different thread before the
 * constructor finished. That comparison is why this example used to report
 * nothing: it recorded against a sentinel Object that the emitter knew nothing
 * about, and did every recording on one thread, so a single-threaded sequence
 * could never be unsafe publication. See issue #346.
 *
 * The registration listener is what makes it real. EventRegistry notifies
 * listeners when an emitter registers, the emitter registers from inside its own
 * constructor, and the listener reads the emitter from another thread - which is
 * a thread seeing a fully-typed reference whose fields are still null.
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

    /**
     * The bug itself, with no detector involved: a registration listener that reads the emitter
     * from another thread sees a fully-typed reference whose name is null. The constructor has
     * not reached the assignment yet.
     */
    @Test
    void testConstructor_registrationListenerSeesAHalfBuiltObject() {
        String[] observed = {"not yet read"};
        EventRegistry.observeRegistrations(emitter -> readFrom(emitter, name -> observed[0] = name));

        new EventEmitter("fully-built");

        assertNull(observed[0],
                "the listener held a reference to an EventEmitter whose name field was still null");
    }

    /**
     * The validator's positive direction: a thread other than the constructing one touching a
     * field before construction completes.
     */
    @Test
    void testConstructorSafetyValidator_accessDuringConstruction_reports() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        EventEmitter.observeConstruction(
                validator::recordConstructionStart, validator::recordConstructionEnd);
        EventRegistry.observeRegistrations(emitter -> readFrom(emitter, name -> {
            validator.recordFieldAccess(emitter, "name", System.nanoTime());
        }));

        new EventEmitter("escaping");

        assertTrue(validator.validateConstructorSafety().hasIssues(),
                "another thread reached the object before its constructor finished");
    }

    /**
     * And the other direction: the same field, read by another thread, after the constructor
     * has returned. Nothing was published early, so there is nothing to report.
     *
     * <p>The assertion is on {@code hasIssues()}, which is the whole report. It used to be on
     * {@code unsafeObjects} alone, because {@code possiblyIncompleteConstructions} flagged any
     * construction that completed in under a microsecond - and an empty constructor completes in
     * under a microsecond, so the validator produced a finding on correct code every time. That
     * rule is gone (issue #357), and the stronger assertion is now the honest one.
     */
    @Test
    void testConstructorSafetyValidator_accessAfterConstruction_isNotUnsafePublication() {
        ConstructorSafetyValidator validator = new ConstructorSafetyValidator();
        EventEmitter.observeConstruction(
                validator::recordConstructionStart, validator::recordConstructionEnd);

        EventEmitter emitter = new EventEmitter("settled");
        readFrom(emitter, name -> {
            validator.recordFieldAccess(emitter, "name", System.nanoTime());
            assertEquals("settled", name, "by now the constructor has finished");
        });

        assertFalse(validator.validateConstructorSafety().hasIssues(),
                "a read after the constructor returned is not unsafe publication: "
                        + validator.validateConstructorSafety());
    }

    /**
     * Reads {@code emitter.getName()} on a different thread and hands the result to
     * {@code sink}, waiting for it. Different thread on purpose: unsafe publication is about who
     * can see the object, and the constructing thread can always see its own.
     */
    private static void readFrom(EventEmitter emitter, java.util.function.Consumer<String> sink) {
        Thread reader = new Thread(() -> sink.accept(emitter.getName()), "registry-listener");
        reader.start();
        try {
            reader.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the reader", e);
        }
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
    /**
     * The bug: every thread constructs an emitter that registers itself before it is built, and
     * the registration listener reads it from somewhere else.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test — it fails with
     *      EventEmitter: Accessed by 1 thread(s) during construction, 1 access(es) in total
     * 3. Fix: move EventRegistry.register(this) to the last line of the constructor
     */
    @Disabled("Remove @Disabled to see the bug detected by ConstructorSafetyValidator")
    @AsyncTest(threads = 8, invocations = 5, detectAll = false,
            validateConstructorSafety = true, failOn = FailOn.LOW)
    void test_concurrent_detectsThisEscape() {
        // This demonstration used to record against a sentinel Object created in the test body,
        // never touching the emitter, and did every recording on one thread. The validator
        // compares the accessing thread against the constructing one, so a single-threaded
        // sequence can never be unsafe publication and the report was empty. See issue #346.
        ConstructorSafetyValidator validator = AsyncTestContext.constructorSafetyValidator();
        EventEmitter.observeConstruction(
                validator::recordConstructionStart, validator::recordConstructionEnd);
        EventRegistry.observeRegistrations(emitter -> readFrom(emitter, name ->
                validator.recordFieldAccess(emitter, "name", System.nanoTime())));

        // The constructor registers `this` before assigning its fields, so the listener above
        // runs - on another thread - while name is still null.
        new EventEmitter("emitter-" + Thread.currentThread().threadId());
    }
}
