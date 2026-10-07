package se.deversity.asynctest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.Events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

/**
 * A rendezvous that a peer cannot reach fails the round promptly and says why.
 *
 * <p>The hand-rolled barrier this replaces left peers waiting for the whole round timeout when one
 * worker threw before reaching it, and then reported a timeout that hid the exception that caused
 * it. Here a worker that throws breaks the round's rendezvous, so its peers fail at once and the
 * original exception stays in the report; a worker that never arrives fails its peers when the
 * rendezvous timeout passes, with a message naming the rendezvous.
 */
@E2E
class RendezvousFailureE2eTest {

    @Test
    void aPeerThatThrowsReleasesTheOthersAtOnce() {
        long start = System.nanoTime();
        Events events = run(ThrowingPeerDummy.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, events.failed().count(), "the round must fail");
        String failure = failureText(events);
        assertTrue(failure.contains("peer failed before the rendezvous"),
                "the peer that threw must be reported as the original failure: " + failure);
        assertTrue(failure.contains("rendezvous was broken"),
                "the waiting workers must say the rendezvous broke, not that they timed out: " + failure);
        // The round timeout is 60 s; without the break every peer would wait for all of it.
        assertTrue(elapsedMs < 30_000, "peers waited " + elapsedMs + " ms instead of failing at once");
    }

    @Test
    void aPeerThatNeverArrivesFailsTheOthersAtTheRendezvousTimeout() {
        Events events = run(AbsentPeerDummy.class);

        assertEquals(1, events.failed().count(), "the round must fail");
        String failure = failureText(events);
        assertTrue(failure.contains("rendezvous"), "the failure must name the rendezvous: " + failure);
        assertTrue(failure.contains("of 3 workers"),
                "the failure must say how many workers the rendezvous waited for: " + failure);
    }

    private static Events run(Class<?> dummy) {
        return EngineTestKit.engine("junit-jupiter").selectors(selectClass(dummy)).execute().testEvents();
    }

    private static String failureText(Events events) {
        Event failed = events.failed().list().get(0);
        Throwable cause = failed.getPayload(org.junit.platform.engine.TestExecutionResult.class)
                .flatMap(org.junit.platform.engine.TestExecutionResult::getThrowable).orElseThrow();
        StringBuilder text = new StringBuilder(String.valueOf(cause));
        for (Throwable s : cause.getSuppressed()) {
            text.append(" | ").append(s);
        }
        return text.toString();
    }

    static class ThrowingPeerDummy {
        private final AtomicInteger role = new AtomicInteger();

        @AsyncTest(threads = 3, invocations = 1, timeoutMs = 60_000, useVirtualThreads = false, detectAll = true)
        void oneWorkerThrowsBeforeTheRendezvous() {
            if (role.getAndIncrement() == 0) {
                throw new IllegalStateException("peer failed before the rendezvous");
            }
            AsyncTestContext.rendezvous();
        }
    }

    static class AbsentPeerDummy {
        private final AtomicInteger role = new AtomicInteger();

        @AsyncTest(threads = 3, invocations = 1, timeoutMs = 60_000, useVirtualThreads = false, detectAll = true)
        void oneWorkerSkipsTheRendezvous() {
            if (role.getAndIncrement() == 0) {
                return;
            }
            AsyncTestContext.rendezvous(Duration.ofMillis(300));
        }
    }
}
