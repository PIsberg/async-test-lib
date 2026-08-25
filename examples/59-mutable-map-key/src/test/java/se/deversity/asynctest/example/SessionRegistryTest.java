package se.deversity.asynctest.example;

import se.deversity.asynctest.AsyncTest;
import se.deversity.asynctest.FailOn;
import se.deversity.asynctest.AsyncTestContext;
import se.deversity.asynctest.example.service.SessionRegistry;
import se.deversity.asynctest.example.service.UserSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SessionRegistry demonstrating the MutableMapKeyDetector.
 *
 * The concurrent test shows how mutating a map key after insertion is flagged
 * as a potential data-loss hazard.
 */
class SessionRegistryTest {

    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
    }

    @Test
    void test_singleThread_registerAndLookup_works() {
        UserSession session = new UserSession("s1");
        registry.register(session, "data1");
        assertEquals("data1", registry.lookup(session));
    }

    @Test
    void test_singleThread_mutatingKey_makesLookupFail() {
        UserSession session = new UserSession("s2");
        registry.register(session, "data2");
        // Mutate the key after insertion — lookup may now fail
        session.setId("s2-mutated");
        // With identity-based hashCode the entry is still findable by same reference,
        // but value-equality-based consumers would lose the entry
        assertNotNull(registry.lookup(session));
    }

    @Disabled("Remove @Disabled to see bug detected by MutableMapKeyDetector")
    @AsyncTest(threads = 8, invocations = 50, detectAll = false, detectMutableMapKeys = true, failOn = FailOn.LOW)
    void test_concurrent_detectsBug() {
        UserSession session = new UserSession("session-" + Thread.currentThread().getId());
        String oldId = session.getId();

        // Record the key being inserted
        AsyncTestContext.mutableMapKeyMonitor()
                .recordKeyInserted(registry.size() == 0 ? new java.util.HashMap<>() : new java.util.HashMap<>(),
                        session, "SessionRegistry.sessions");

        registry.register(session, "payload");

        // Mutate the key after insertion — this is the bug
        String newId = oldId + "-updated";
        AsyncTestContext.mutableMapKeyMonitor()
                .recordKeyMutation(session, "id", oldId, newId);

        session.setId(newId);

        // Lookup may now silently return null for value-equality consumers
        registry.lookup(session);
    }
}
