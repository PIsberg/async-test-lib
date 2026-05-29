package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.EventListenerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for EventListenerService.
 *
 * ========================================================================
 * DETECTOR: ThisEscapeDetector
 * ========================================================================
 *
 * THE BUG:
 * EventListenerService's constructor publishes `this` into a shared registry
 * (sharedRegistry.add(this)) before the constructor finishes assigning its
 * fields. Another thread iterating the registry can observe the instance while
 * `ready`/`config` are still unset. The fields are non-final, so there is no
 * final-field visibility guarantee — a reader may see a partially constructed object.
 *
 * WHY @Test PASSES:
 * Single-threaded tests construct the object and only read it afterwards, on the
 * same thread. The escape window never overlaps with a concurrent read, so the
 * object always looks fully built.
 *
 * WHY @AsyncTest DETECTS:
 * ThisEscapeDetector records the constructor escape and flags any object whose
 * `this` reference was published before construction completed — regardless of
 * whether a concurrent reader actually observed the partial state.
 *
 * FIX:
 * Never let `this` escape a constructor. Use a static factory that constructs the
 * object fully, then registers it via a separate init()/start() step.
 */
class EventListenerServiceTest {

    private List<Object> registry;

    @BeforeEach
    void setUp() {
        registry = new CopyOnWriteArrayList<>();
    }

    // -------------------------------------------------------------------------
    // Part 1: @Test — passes but gives false confidence
    // -------------------------------------------------------------------------

    @Test
    void testConstruct_singleThread_isReady() {
        EventListenerService service = new EventListenerService(registry);
        assertTrue(service.isReady());
    }

    @Test
    void testConstruct_registersSelf() {
        new EventListenerService(registry);
        assertEquals(1, registry.size());
    }

    @Test
    void testConstruct_registeredInstanceIsReady() {
        EventListenerService service = new EventListenerService(registry);
        assertSame(service, registry.get(0));
        assertTrue(((EventListenerService) registry.get(0)).isReady());
    }

    // -------------------------------------------------------------------------
    // Part 2: @AsyncTest — exposes the this-escape bug
    // -------------------------------------------------------------------------

    /**
     * Eight threads each construct a new EventListenerService whose constructor
     * publishes `this` into a shared registry before completing. ThisEscapeDetector
     * records the escape and reports the unsafe publication.
     *
     * To see the detection:
     * 1. Remove @Disabled
     * 2. Run this test
     * 3. To fix: use a static factory that registers the instance after construction
     */
    @Disabled("Remove @Disabled to see the bug detected by ThisEscapeDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectThisEscape = true)
    void test_concurrent_detectsThisEscape() {
        Thread thread = Thread.currentThread();
        List<Object> registry = new CopyOnWriteArrayList<>();
        var detector = AsyncTestContext.thisEscapeDetector();
        EventListenerService service = new EventListenerService(registry);
        // model the escape that happened inside the constructor:
        detector.recordConstructorEscape(service, "sharedRegistry.add(this)", thread);
        detector.recordConstructionComplete(service);
        assertTrue(service.isReady());
    }
}
