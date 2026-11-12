package se.deversity.asynctest.agent;

import com.example.agentfixture.ComputeTallyBean;
import com.example.agentfixture.ComputeTallyBean.Tally;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import se.deversity.asynctest.diagnostics.AtomicityValidator;
import se.deversity.asynctest.telemetry.TelemetryBridge;
import se.deversity.asynctest.telemetry.TelemetryRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins that a value mutated inside {@code ConcurrentHashMap}'s atomic per-key updates reads as
 * guarded, and that the shapes that are real races keep reporting (#1245).
 *
 * <p>The map's exclusion comes from a lock inside the JDK, which the lockset cannot see, so before
 * #1245 the field accesses in the function were recorded with an empty lockset on every thread and
 * the correct idiom drew the same finding as its broken twin. The woven call now holds a token per
 * (map, key) for the length of the call.
 */
@Tag("e2e")
class AtomicMapUpdateWeavingTest {

    private static final int WORKERS = 4;
    private static final int BUMPS = 200;
    private static final String KEY = "k";

    @BeforeAll
    static void attachWithFieldAndCollectionWeaving() {
        boolean supported;
        try {
            ByteBuddyAgent.install();
            supported = true;
        } catch (Throwable t) { // NOPMD - broad by design: any attach failure means "unsupported"
            supported = false;
        }
        assumeTrue(supported,
                "self-attach not permitted (run with -Djdk.attach.allowAttachSelf=true)");

        AsyncTestAgent.selfAttach("includes=com.example.agentfixture,fields=true,collections=true");
    }

    @AfterEach
    void stopRegistry() {
        TelemetryRegistry.stop();
    }

    /** Runs {@code bump} {@link #BUMPS} times on each of {@link #WORKERS} threads at once. */
    private static List<String> findings(Consumer<Integer> bump) throws InterruptedException {
        AtomicityValidator validator = new AtomicityValidator();
        Set<Long> workers = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        try (TelemetryBridge bridge = TelemetryBridge.activateWithFilter(validator, workers::contains)) {
            CountDownLatch registered = new CountDownLatch(WORKERS);
            CountDownLatch done = new CountDownLatch(WORKERS);
            for (int w = 0; w < WORKERS; w++) {
                int worker = w;
                new Thread(() -> {
                    workers.add(Thread.currentThread().threadId());
                    registered.countDown();
                    try {
                        assertTrue(registered.await(10, TimeUnit.SECONDS));
                        for (int i = 0; i < BUMPS; i++) {
                            bump.accept(worker);
                        }
                    } catch (Throwable t) { // NOPMD - reported to the test thread below
                        failed.compareAndSet(null, t);
                    } finally {
                        done.countDown();
                    }
                }, "map-update-worker").start();
            }
            assertTrue(done.await(20, TimeUnit.SECONDS), "worker threads did not finish");
            TelemetryRegistry.flush();
        }
        if (failed.get() != null) {
            throw new AssertionError("a worker failed, so nothing was measured", failed.get());
        }
        return validator.analyzeAtomicity().unsafeFieldAccesses.stream().toList();
    }

    private static boolean mentionsHits(List<String> findings) {
        return findings.stream().anyMatch(f -> f.contains("Tally.hits"));
    }

    private static Map<String, Tally> seeded(Map<String, Tally> map) {
        map.put(KEY, new Tally());
        return map;
    }

    @Test
    @DisplayName("a value bumped inside ConcurrentHashMap.compute is guarded")
    void computeOnAConcurrentHashMapIsSilent() throws InterruptedException {
        Map<String, Tally> map = seeded(new ConcurrentHashMap<>());
        List<String> findings = findings(w -> ComputeTallyBean.compute(map, KEY));
        assertFalse(mentionsHits(findings),
                "ConcurrentHashMap.compute is atomic for the key, so the bump inside it is one "
                        + "step; a finding means the woven call no longer holds its per-key "
                        + "token. Findings: " + findings);
    }

    @Test
    @DisplayName("compute called on the concrete class is guarded the same way")
    void computeOnTheConcreteClassIsSilent() throws InterruptedException {
        ConcurrentHashMap<String, Tally> map = new ConcurrentHashMap<>();
        map.put(KEY, new Tally());
        List<String> findings = findings(w -> ComputeTallyBean.computeOnTheClass(map, KEY));
        assertFalse(mentionsHits(findings), "an invokevirtual call site: " + findings);
    }

    @Test
    @DisplayName("computeIfPresent and merge are guarded too")
    void computeIfPresentAndMergeAreSilent() throws InterruptedException {
        Map<String, Tally> map = seeded(new ConcurrentHashMap<>());
        List<String> findings = findings(w -> {
            if (w % 2 == 0) {
                ComputeTallyBean.computeIfPresent(map, KEY);
            } else {
                ComputeTallyBean.merge(map, KEY);
            }
        });
        assertFalse(mentionsHits(findings), "both are atomic for the key: " + findings);
    }

    @Test
    @DisplayName("the same bump after a get is a race")
    void getThenBumpFires() throws InterruptedException {
        Map<String, Tally> map = seeded(new ConcurrentHashMap<>());
        List<String> findings = findings(w -> ComputeTallyBean.getThenBump(map, KEY));
        assertTrue(mentionsHits(findings),
                "the read and the bump are two steps on four threads: " + findings);
    }

    @Test
    @DisplayName("compute on a ConcurrentSkipListMap, which may run the function concurrently, is a race")
    void computeOnASkipListMapFires() throws InterruptedException {
        Map<String, Tally> map = seeded(new ConcurrentSkipListMap<>());
        List<String> findings = findings(w -> ComputeTallyBean.compute(map, KEY));
        assertTrue(mentionsHits(findings),
                "ConcurrentSkipListMap.compute is not atomic, so it must not be given the token: "
                        + findings);
    }

    @Test
    @DisplayName("one value reachable under two keys is a race even inside compute")
    void oneValueUnderTwoKeysFires() throws InterruptedException {
        Tally shared = new Tally();
        Map<String, Tally> map = new ConcurrentHashMap<>();
        map.put("a", shared);
        map.put("b", shared);
        List<String> findings = findings(w -> ComputeTallyBean.compute(map, w % 2 == 0 ? "a" : "b"));
        assertTrue(mentionsHits(findings),
                "compute on two keys runs concurrently, so the token is per key: " + findings);
    }

    /** A subclass that changes nothing about the per-key updates. */
    static class PlainSubclass<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;
    }

    /** One more level down, still overriding nothing. */
    static final class PlainGrandchild<K, V> extends PlainSubclass<K, V> {
        private static final long serialVersionUID = 1L;
    }

    /** A subclass whose {@code compute} runs the function between a get and a put, under no lock. */
    static class NonAtomicCompute<K, V> extends ConcurrentHashMap<K, V> {
        private static final long serialVersionUID = 1L;

        @Override
        public V compute(K key, java.util.function.BiFunction<? super K, ? super V, ? extends V> remapping) {
            V updated = remapping.apply(key, get(key));
            if (updated == null) {
                remove(key);
            } else {
                put(key, updated);
            }
            return updated;
        }
    }

    /** Overrides nothing itself, but inherits the non-atomic {@code compute}. */
    static final class BelowNonAtomicCompute<K, V> extends NonAtomicCompute<K, V> {
        private static final long serialVersionUID = 1L;
    }

    @Test
    @DisplayName("a subclass that overrides none of the updates is guarded like ConcurrentHashMap (#1259)")
    void computeOnANonOverridingSubclassIsSilent() throws InterruptedException {
        Map<String, Tally> plain = seeded(new PlainSubclass<>());
        Map<String, Tally> grandchild = seeded(new PlainGrandchild<>());
        List<String> findings = findings(w -> ComputeTallyBean.compute(w % 2 == 0 ? plain : grandchild, KEY));
        assertFalse(mentionsHits(findings),
                "neither class overrides compute, so ConcurrentHashMap's own atomic compute runs: "
                        + findings);
    }

    @Test
    @DisplayName("a subclass whose compute is not atomic is a race, at any depth (#1259)")
    void computeOnAnOverridingSubclassFires() throws InterruptedException {
        Map<String, Tally> overriding = seeded(new NonAtomicCompute<>());
        assertTrue(mentionsHits(findings(w -> ComputeTallyBean.compute(overriding, KEY))),
                "NonAtomicCompute runs the function outside any lock, so it must not get the token");
        Map<String, Tally> below = seeded(new BelowNonAtomicCompute<>());
        assertTrue(mentionsHits(findings(w -> ComputeTallyBean.compute(below, KEY))),
                "BelowNonAtomicCompute inherits the non-atomic compute from a level above it");
    }
}
