package se.deversity.asynctest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.NonAtomicConcurrentMapUpdateDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Check-then-act on a thread-safe map, reported through the woven map hooks alone.
 *
 * <p>{@code CONCURRENT_MAP_CHECK_THEN_ACT} is the detector for the most common misuse of the most
 * used concurrent class, {@code if (!map.containsKey(k)) map.put(k, v)} on a
 * {@code ConcurrentHashMap}, and until this feed it fired only when the code under test called
 * {@code recordCheckThenAct} about itself. Code that misuses the map does not do that. The agent
 * already substitutes every {@code Map.get}, {@code containsKey}, {@code put} and {@code remove}
 * call site, and the hooks dropped a {@code ConcurrentMap} receiver because each call alone is
 * atomic. The pair is what is not, so these cases go through the hooks exactly as woven code
 * would, record nothing by hand, and ask the detector for its report.
 *
 * <p>The racing cases line both threads up between the check and the act, which is the
 * interleaving that loses an update. Run one after the other, the second thread's check sees the
 * first thread's put, and that execution lost nothing.
 */
class AgentMapCheckThenActHooksTest {

    private static AsyncTestContext newContext() {
        return new AsyncTestContext(AsyncTestConfig.builder().detectAll(true).build());
    }

    /**
     * Runs {@code body} on two threads at once, each with {@code context} installed. The body gets
     * a {@code meet} action that returns once both threads have reached it.
     */
    private static void together(AsyncTestContext context, Consumer<Runnable> body)
            throws InterruptedException {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Runnable meet = () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (BrokenBarrierException | TimeoutException e) {
                throw new IllegalStateException(e);
            }
        };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                AsyncTestContext.install(context);
                try {
                    body.accept(meet);
                } catch (Throwable e) { // NOPMD AvoidCatchingThrowable - rethrown on the test thread
                    failure.set(e);
                } finally {
                    AsyncTestContext.uninstall();
                }
            }, "check-then-act-" + i);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static NonAtomicConcurrentMapUpdateDetector.Report reportOf(AsyncTestContext context) {
        AsyncTestContext.install(context);
        try {
            return AsyncTestContext.nonAtomicConcurrentMapUpdateDetector().analyze();
        } finally {
            AsyncTestContext.uninstall();
        }
    }

    @Test
    @DisplayName("containsKey then put on a ConcurrentHashMap from two threads is reported")
    void absentCheckThenPutIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> cache = new ConcurrentHashMap<>();

        together(context, meet -> {
            boolean present = AgentCollectionHooks.mapContainsKey(cache, "user-1");
            meet.run();
            if (!present) {
                AgentCollectionHooks.mapPut(cache, "user-1", new Object());
            }
        });

        NonAtomicConcurrentMapUpdateDetector.Report report = reportOf(context);
        assertTrue(report.hasIssues(),
                "both threads checked the key absent and then put their own value, which is the "
                        + "lost update putIfAbsent exists to prevent; the woven hooks are the only "
                        + "feed here, so silence means the pair was never recognised. " + report);
        assertTrue(report.violations.get(0).contains("user-1"), report.violations.toString());
        assertTrue(report.violations.get(0).contains("containsKey-then-put"),
                "the report names the shape it saw: " + report.violations);
    }

    @Test
    @DisplayName("get then put of a derived value on a ConcurrentHashMap from two threads is reported")
    void readModifyWriteIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> counts = new ConcurrentHashMap<>();
        // Above the Integer cache, so each thread's increment is its own instance; the cached
        // boxes are the documented false-negative boundary, pinned below.
        counts.put("hits", 1_000);

        together(context, meet -> {
            Integer seen = (Integer) AgentCollectionHooks.mapGet(counts, "hits");
            meet.run();
            AgentCollectionHooks.mapPut(counts, "hits", seen + 1);
        });

        assertTrue(reportOf(context).hasIssues(),
                "both threads read 1000 and wrote 1001, so one increment is gone; compute or "
                        + "merge makes the read-modify-write atomic");
        assertEquals(1_001, counts.get("hits"),
                "the lost update the finding describes really happened");
    }

    @Test
    @DisplayName("get then put of one shared instance by every racer stays silent")
    void installingOneSharedInstanceAfterAGetIsExcused() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> current = new ConcurrentHashMap<>();
        current.put("generation", new Object());
        Object generation = new Object();

        together(context, meet -> {
            boolean stale = AgentCollectionHooks.mapGet(current, "generation") != generation;
            meet.run();
            if (stale) {
                AgentCollectionHooks.mapPut(current, "generation", generation);
            }
        });

        assertFalse(reportOf(context).hasIssues(),
                "every caller installed the same generation object, so whichever put lands last "
                        + "the map holds what putIfAbsent would have left; the hook cannot tell "
                        + "this from a derived value, and a false positive on the idiom is the "
                        + "worse of the two mistakes");
    }

    @Test
    @DisplayName("racers that all put one cached box are reported: an increment was lost (#996)")
    void cachedBoxIncrementIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> counts = new ConcurrentHashMap<>();
        counts.put("hits", 0);

        together(context, meet -> {
            Integer seen = (Integer) AgentCollectionHooks.mapGet(counts, "hits");
            meet.run();
            AgentCollectionHooks.mapPut(counts, "hits", seen + 1);
        });

        assertEquals(1, counts.get("hits"), "an increment was lost");
        assertTrue(reportOf(context).hasIssues(),
                "both racers put Integer.valueOf(1), which is one cached instance only because the "
                        + "JVM caches small boxes: the value was derived from the read, and an "
                        + "increment was lost: " + reportOf(context));
    }

    /** States a check-then-act moves a key through. */
    private enum Phase { IDLE, STARTED }

    @Test
    @DisplayName("racers that all put one enum constant after a check are reported (#996)")
    void enumConstantTransitionIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> phases = new ConcurrentHashMap<>();
        phases.put("job", Phase.IDLE);

        together(context, meet -> {
            boolean idle = AgentCollectionHooks.mapGet(phases, "job") == Phase.IDLE;
            meet.run();
            if (idle) {
                AgentCollectionHooks.mapPut(phases, "job", Phase.STARTED);
            }
        });

        assertTrue(reportOf(context).hasIssues(),
                "both racers saw IDLE and both moved the job to STARTED, so both believe they "
                        + "started it; the constant is shared by the enum, not chosen as a token: "
                        + reportOf(context));
    }

    @Test
    @DisplayName("get then remove on a ConcurrentHashMap from two threads is reported")
    void readThenRemoveIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> sessions = new ConcurrentHashMap<>();
        Object token = new Object();
        sessions.put("s", token);

        together(context, meet -> {
            boolean mine = AgentCollectionHooks.mapGet(sessions, "s") == token;
            meet.run();
            if (mine) {
                AgentCollectionHooks.mapRemove(sessions, "s");
            }
        });

        assertTrue(reportOf(context).hasIssues(),
                "remove(k) after a get that decided it is the check-then-act remove(k, v) exists for");
    }

    @Test
    @DisplayName("containsKey then put on a Collections.synchronizedMap from two threads is reported")
    void synchronizedWrapperCheckThenPutIsReported() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> registry = Collections.synchronizedMap(new HashMap<>());

        together(context, meet -> {
            boolean present = AgentCollectionHooks.mapContainsKey(registry, "k");
            meet.run();
            if (!present) {
                AgentCollectionHooks.mapPut(registry, "k", new Object());
            }
        });

        assertTrue(reportOf(context).hasIssues(),
                "each call on the wrapper takes its monitor and releases it, so the pair is as "
                        + "non-atomic as it is on a ConcurrentHashMap");
    }

    @Test
    @DisplayName("the same read-modify-write under the map's own monitor stays silent")
    void pairUnderTheMapsMonitorIsSilent() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> counts = Collections.synchronizedMap(new HashMap<>());
        counts.put("hits", 0);

        together(context, meet -> {
            meet.run();
            synchronized (counts) {
                Integer seen = (Integer) AgentCollectionHooks.mapGet(counts, "hits");
                AgentCollectionHooks.mapPut(counts, "hits", seen + 1);
            }
        });

        NonAtomicConcurrentMapUpdateDetector.Report report = reportOf(context);
        assertFalse(report.hasIssues(),
                "holding the wrapper's monitor across both calls is the documented way to make "
                        + "the pair atomic; reporting it would be a false positive. " + report);
        assertEquals(2, counts.get("hits"), "and indeed nothing was lost");
    }

    @Test
    @DisplayName("racing absent checks that all put one shared instance stay silent")
    void racersPuttingOneInstanceAreExcused() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> cache = new ConcurrentHashMap<>();
        Object canonical = new Object();

        together(context, meet -> {
            boolean present = AgentCollectionHooks.mapContainsKey(cache, "k");
            meet.run();
            if (!present) {
                AgentCollectionHooks.mapPut(cache, "k", canonical);
            }
        });

        assertFalse(reportOf(context).hasIssues(),
                "both callers put the same instance, so the map ends as putIfAbsent would leave "
                        + "it and no caller holds a value the map dropped");
    }

    @Test
    @DisplayName("a pair made by one thread only stays silent")
    void oneThreadIsSilent() {
        AsyncTestContext context = newContext();
        Map<Object, Object> cache = new ConcurrentHashMap<>();
        AsyncTestContext.install(context);
        try {
            for (int i = 0; i < 3; i++) {
                if (!AgentCollectionHooks.mapContainsKey(cache, "k" + i)) {
                    AgentCollectionHooks.mapPut(cache, "k" + i, new Object());
                }
            }
        } finally {
            AsyncTestContext.uninstall();
        }
        assertFalse(reportOf(context).hasIssues(), "nothing raced, so nothing was lost");
    }

    @Test
    @DisplayName("a check on one key and a put on another is not a pair")
    void differentKeysAreNotAPair() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> cache = new ConcurrentHashMap<>();

        together(context, meet -> {
            AgentCollectionHooks.mapContainsKey(cache, "checked");
            meet.run();
            AgentCollectionHooks.mapPut(cache, Thread.currentThread().getName(), new Object());
        });

        assertFalse(reportOf(context).hasIssues(),
                "the put names a key the check never read, so it acted on nothing it checked");
    }

    @Test
    @DisplayName("a present-key check followed by a put is not reported")
    void presentCheckThenPutIsNotAPair() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> flags = new ConcurrentHashMap<>();
        flags.put("k", Boolean.TRUE);

        together(context, meet -> {
            boolean present = AgentCollectionHooks.mapContainsKey(flags, "k");
            meet.run();
            if (present) {
                AgentCollectionHooks.mapPut(flags, "k", new Object());
            }
        });

        assertFalse(reportOf(context).hasIssues(),
                "containsKey answering true decides an overwrite, which loses nothing unless "
                        + "someone removes the key in between; the feed reports only the shapes "
                        + "that lose an update on their own");
    }

    @Test
    @DisplayName("a plain HashMap is left to the shared-collection detector")
    void plainMapIsNotThisDetectorsConcern() throws InterruptedException {
        AsyncTestContext context = newContext();
        Map<Object, Object> plain = new HashMap<>();

        together(context, meet -> {
            boolean present = AgentCollectionHooks.mapContainsKey(plain, "k");
            meet.run();
            if (!present) {
                synchronized (plain) { // keeps the HashMap itself intact; the pair is still racy
                    AgentCollectionHooks.mapPut(plain, "k", new Object());
                }
            }
        });

        assertFalse(reportOf(context).hasIssues(),
                "an unsynchronized HashMap is a data race on every call, which SHARED_COLLECTIONS "
                        + "reports; calling it a check-then-act too would report one bug twice");
    }

    @Test
    @DisplayName("the hooks still return what the map returned")
    void hooksReturnTheMapsAnswers() {
        AsyncTestContext context = newContext();
        Map<Object, Object> cache = new ConcurrentHashMap<>();
        AsyncTestContext.install(context);
        try {
            assertFalse(AgentCollectionHooks.mapContainsKey(cache, "k"));
            assertNull(AgentCollectionHooks.mapPut(cache, "k", "v"));
            assertTrue(AgentCollectionHooks.mapContainsKey(cache, "k"));
            assertEquals("v", AgentCollectionHooks.mapGet(cache, "k"));
            assertEquals("v", AgentCollectionHooks.mapRemove(cache, "k"));
            assertNull(AgentCollectionHooks.mapGet(cache, "k"));
        } finally {
            AsyncTestContext.uninstall();
        }
    }
}
