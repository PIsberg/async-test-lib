package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two answers this class has to keep apart: "no cycle" and "this JVM cannot tell".
 *
 * <p><strong>Why this exists.</strong> {@code ThreadMXBean.findDeadlockedThreads()} reports
 * platform threads, and {@code @AsyncTest} workers are virtual by default, so a textbook circular
 * wait between them produced a clean {@code DeadlockDetector} report. The JVM's own JSON thread
 * dump does carry the wait-for graph, on JDKs whose dump names monitors. Issue #367.
 *
 * <p>The two fixtures are real dumps, captured from the same two-thread deadlock on the two JDKs
 * that answer differently: 26 names monitors, 21 does not. They are checked in rather than
 * generated, because the whole point is to exercise a shape this machine may not be able to
 * produce, and because a parser that silently stops matching is exactly the failure this class
 * would otherwise hide.
 */
class VirtualThreadLockGraphTest {

    @Test
    @DisplayName("a real virtual-thread deadlock is read out of a JDK 26 dump")
    void findsTheCycleInACapturedJdk26Dump() {
        Optional<List<VirtualThreadLockGraph.Cycle>> scan =
                VirtualThreadLockGraph.scanDump(fixture("virtual-thread-deadlock-jdk26.json"));

        assertTrue(scan.isPresent(), "this dump names monitors, so the question is answerable");
        List<VirtualThreadLockGraph.Cycle> cycles = scan.get();
        assertEquals(1, cycles.size(), "two threads holding each other's monitor is one cycle: " + cycles);

        VirtualThreadLockGraph.Cycle cycle = cycles.get(0);
        assertEquals(2, cycle.threadNames().size(), "both members are named: " + cycle);
        assertTrue(cycle.threadNames().containsAll(
                        List.of("async-test-worker-0", "async-test-worker-1")),
                "the report has to name the threads a reader can go and look at: " + cycle);
        assertEquals(2, cycle.monitors().size(), "and the two monitors they are stuck on: " + cycle);
        assertEquals(2, cycle.monitors().stream().distinct().count(),
                "two different monitors, or the walk followed the same edge twice: " + cycle);
    }

    @Test
    @DisplayName("a JDK 27 dump, whose thread ids are numbers, still yields both its cycles and its threads")
    void readsTheNumericIdsOfAJdk27Dump() {
        // Captured on JDK 27.0.1.1 (#1410): two virtual threads parked on each other's
        // ReentrantLock, and two blocked on each other's monitor. Format version 2 writes "tid"
        // and parkBlocker's "owner" as JSON numbers where JDK 21 to 26 wrote strings, and a parser
        // that read only strings found no thread at all: no cycle, and an empty sample for
        // StaticInitDeadlockDetector.
        String dump = fixture("virtual-thread-deadlock-jdk27.json");

        Optional<List<VirtualThreadLockGraph.Cycle>> scan = VirtualThreadLockGraph.scanDump(dump);
        assertTrue(scan.isPresent(), "this dump names monitors and lock owners");
        VirtualThreadLockGraph.Cycle locks = cycleNaming(scan.get(), "lock-one");
        assertTrue(locks.threadNames().contains("lock-two"), "both members: " + locks);
        assertTrue(locks.threadIds().containsAll(List.of("44", "46")),
                "the ids, read from numbers, the runner attributes a cycle by: " + locks);
        VirtualThreadLockGraph.Cycle monitors = cycleNaming(scan.get(), "monitor-one");
        assertTrue(monitors.threadNames().contains("monitor-two"), "both members: " + monitors);

        List<VirtualThreadLockGraph.DumpedThread> threads =
                VirtualThreadLockGraph.scanDumpForThreads(dump).orElseThrow();
        assertTrue(threads.stream().anyMatch(t -> "lock-one".equals(t.name()) && t.virtual()),
                "the threads themselves, for the static-initializer sample: " + threads.size());
    }

    @Test
    @DisplayName("a ReentrantLock deadlock is closed through parkBlocker.owner, alone and mixed with a monitor")
    void closesParkedCyclesThroughTheBlockersOwner() {
        // Captured on JDK 26.0.2 (#1384): two virtual threads each parked on the ReentrantLock the
        // other holds, and a mixed pair, a virtual thread parked on a lock held by a platform
        // thread that is blocked on a monitor the virtual one holds. Neither cycle has a
        // blockedOn edge on both sides, so a graph of monitors alone closes neither.
        Optional<List<VirtualThreadLockGraph.Cycle>> scan =
                VirtualThreadLockGraph.scanDump(fixture("virtual-thread-reentrantlock-deadlock-jdk26.json"));

        assertTrue(scan.isPresent(), "this dump names monitors and lock owners");
        List<VirtualThreadLockGraph.Cycle> cycles = scan.get();
        assertEquals(2, cycles.size(), "the lock pair and the mixed pair: " + cycles);

        VirtualThreadLockGraph.Cycle locks = cycleNaming(cycles, "async-test-worker-0");
        assertTrue(locks.threadNames().contains("async-test-worker-1"), "both members: " + locks);
        assertTrue(locks.threadIds().containsAll(List.of("42", "44")),
                "the ids the runner attributes a cycle by: " + locks);
        assertTrue(locks.monitors().stream().allMatch(m -> m.contains("ReentrantLock")),
                "the two locks are named as what each thread waits for: " + locks);

        VirtualThreadLockGraph.Cycle mixed = cycleNaming(cycles, "mixed-virtual");
        assertTrue(mixed.threadNames().contains("mixed-platform"), "both members: " + mixed);
        assertTrue(mixed.monitors().stream().anyMatch(m -> m.contains("ReentrantLock"))
                        && mixed.monitors().stream().anyMatch(m -> m.startsWith("java.lang.Object@")),
                "one lock edge and one monitor edge: " + mixed);
    }

    @Test
    @DisplayName("a parked thread whose blocker names no owner adds no edge")
    void aParkBlockerWithoutAnOwnerClosesNothing() {
        String withoutOwners = fixture("virtual-thread-reentrantlock-deadlock-jdk26.json")
                .replaceAll(",\\s*\"owner\":\\s*\"\\d+\"", "");
        assertFalse(withoutOwners.contains("\"owner\": \""), "precondition: every lock owner removed");

        Optional<List<VirtualThreadLockGraph.Cycle>> scan = VirtualThreadLockGraph.scanDump(withoutOwners);

        assertTrue(scan.isPresent());
        assertTrue(scan.get().isEmpty(),
                "a parked thread whose lock owner is unknown waits for nobody we can name: " + scan.get());
    }

    @Test
    @DisplayName("a thread parked with a timeout adds no edge, so a tryLock back-off is not a deadlock")
    void aTimedParkClosesNothing() {
        String timed = fixture("virtual-thread-reentrantlock-deadlock-jdk26.json")
                .replace("\"state\": \"WAITING\"", "\"state\": \"TIMED_WAITING\"");
        assertTrue(timed.contains("TIMED_WAITING"), "precondition: the parked threads now wait with a timeout");

        Optional<List<VirtualThreadLockGraph.Cycle>> scan = VirtualThreadLockGraph.scanDump(timed);

        assertTrue(scan.isPresent());
        assertTrue(scan.get().isEmpty(),
                "tryLock(timeout) on each other's lock parks the same way and gives up on its own: " + scan.get());
    }

    /** #1365: a null value is absent, not the next string in the object. */
    @Test
    void aNullFieldReadsAsAbsent() {
        VirtualThreadLockGraph.DumpedThread thread = onlyThread(
                "{\"tid\": \"7\", \"name\": \"w\", \"state\": \"RUNNABLE\", \"blockedOn\": null, "
                        + "\"waitingOn\": \"java.lang.Object@1\", \"stack\": []}");

        assertNull(thread.blockedOn(), "blockedOn is null in the dump: " + thread);
    }

    /** #1365: a key's name inside another field's value is not that key. */
    @Test
    void aKeyNameInsideAValueIsNotTheKey() {
        VirtualThreadLockGraph.DumpedThread thread = onlyThread(
                "{\"tid\": \"7\", \"name\": \"blockedOn\", \"state\": \"RUNNABLE\", \"stack\": []}");

        assertEquals("blockedOn", thread.name());
        assertNull(thread.blockedOn(), "no blockedOn key in this object: " + thread);
        assertEquals("RUNNABLE", thread.state());
    }

    private static VirtualThreadLockGraph.DumpedThread onlyThread(String threadObject) {
        String dump = "{\"threadDump\": {\"threadContainers\": [{\"container\": \"<root>\", \"threads\": ["
                + threadObject + "]}]}}";
        List<VirtualThreadLockGraph.DumpedThread> threads = VirtualThreadLockGraph.scanDumpForThreads(dump)
                .orElseThrow(() -> new AssertionError("the dump has a state, so it parses"));
        assertEquals(1, threads.size(), "one thread: " + threads);
        return threads.get(0);
    }

    private static VirtualThreadLockGraph.Cycle cycleNaming(List<VirtualThreadLockGraph.Cycle> cycles, String name) {
        return cycles.stream().filter(c -> c.threadNames().contains(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no cycle names " + name + ": " + cycles));
    }

    @Test
    @DisplayName("the same deadlock on a JDK 21 dump reports 'cannot tell', not 'no deadlock'")
    void reportsUnknownWhenTheDumpDoesNotNameMonitors() {
        String jdk21 = fixture("virtual-thread-deadlock-jdk21.json");
        assertFalse(jdk21.contains("monitorsOwned"),
                "the fixture is the pre-monitor dump shape; if this fails the fixture was replaced");

        Optional<List<VirtualThreadLockGraph.Cycle>> scan =
                VirtualThreadLockGraph.scanDump(jdk21);

        assertTrue(scan.isEmpty(),
                "the threads really are deadlocked in this dump and the dump cannot say so. "
                        + "Returning an empty list here would report a clean bill of health for a "
                        + "JVM that was never asked the question, which is the failure this whole "
                        + "class exists to stop. Got: " + scan);
    }


    @Test
    @DisplayName("on this JVM, a live virtual-thread deadlock is either found or declared unseeable")

    void liveDeadlockIsFoundWhereverTheJvmCanSaySo() throws Exception {
        // Both directions in one method, in this order, on purpose. Surefire runs with
        // reuseForks=false so the deadlock below dies with this class's JVM and cannot reach
        // another test class, but it outlives every later test in this one: a monitor deadlock
        // cannot be broken, and virtual threads cannot be killed. An earlier draft asserted the
        // healthy case in a separate method and that method failed, because it ran second and
        // found this deadlock. That was the code working and the test being wrong.
        // Scoped to this test's own probe names, not "no cycles at all": in a shared-JVM run
        // (pitest's coverage stage) earlier suites leak their own deadlocked workers, and those
        // cycles are real, just not ours to assert about.
        Optional<List<VirtualThreadLockGraph.Cycle>> before = VirtualThreadLockGraph.scan();
        before.ifPresent(cycles -> assertTrue(
                cycles.stream().noneMatch(c -> c.threadNames().contains("probe-a")
                        || c.threadNames().contains("probe-b")),
                "this test's probes are not deadlocked yet, so a cycle naming them here is a "
                        + "false positive: " + cycles));

        Object first = new Object();
        Object second = new Object();
        CountDownLatch both = new CountDownLatch(2);

        Thread.ofVirtual().name("probe-a").start(() -> {
            synchronized (first) {
                arrive(both);
                synchronized (second) {
                    throw new IllegalStateException("unreachable: this is the deadlock");
                }
            }
        });
        Thread.ofVirtual().name("probe-b").start(() -> {
            synchronized (second) {
                arrive(both);
                synchronized (first) {
                    throw new IllegalStateException("unreachable: this is the deadlock");
                }
            }
        });
        assertTrue(both.await(5, TimeUnit.SECONDS), "both probes must reach their first monitor");
        Thread.sleep(300);      // and then block on the second

        Optional<List<VirtualThreadLockGraph.Cycle>> after = VirtualThreadLockGraph.scan();

        // The invariant holds on every JDK, which is what makes it worth asserting: where the
        // dump names monitors the cycle must be found, and where it does not the answer must be
        // "cannot tell" rather than "clean".
        assertEquals(before.isPresent(), after.isPresent(),
                "whether this JVM can answer the question does not change while it runs");
        if (after.isEmpty()) {
            assertFalse(VirtualThreadLockGraph
                            .scanDump(fixture("virtual-thread-deadlock-jdk26.json")).isEmpty(),
                    "the parser itself still has to work even where this JVM's dump is thin");
            return;
        }
        List<String> names = after.get().stream().flatMap(c -> c.threadNames().stream()).toList();
        assertTrue(names.containsAll(List.of("probe-a", "probe-b")),
                "this JVM's dump names monitors, so the live deadlock must be in the result. "
                        + "Found: " + after.get());
    }

    private static void arrive(CountDownLatch latch) {
        latch.countDown();
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String fixture(String name) {
        try (InputStream in = VirtualThreadLockGraphTest.class
                .getResourceAsStream("/threaddumps/" + name)) {
            assertNotNull(in, "missing test fixture /threaddumps/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read /threaddumps/" + name, e);
        }
    }
}
