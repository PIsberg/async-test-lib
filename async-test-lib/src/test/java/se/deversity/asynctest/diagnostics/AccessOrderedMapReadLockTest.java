package se.deversity.asynctest.diagnostics;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code get} on an access-ordered {@link LinkedHashMap}, the usual LRU cache, relinks the entry,
 * so readers holding one read lock write the map together (#807). Whether a map is access-ordered
 * is a private field of {@code java.util}; the library reads it only when {@code java.util} is
 * already open to it and never opens it itself, so these scenarios run twice: in a child JVM
 * started with {@code --add-opens java.base/java.util=ALL-UNNAMED}, where the order is known, and
 * in this JVM, where it is not.
 */
class AccessOrderedMapReadLockTest {

    /** The same scenarios with the order known: each map is judged by what its gets really do. */
    @Test
    void withJavaUtilOpenOnlyAnAccessOrderedMapsGetsNeedAnExclusiveLock() throws Exception {
        Map<String, Boolean> seen = runInChildJvm("--add-opens", "java.base/java.util=ALL-UNNAMED");

        assertEquals(Boolean.TRUE, seen.get("lruCacheGetsUnderOneReadLock"),
                "every get() relinks an access-ordered map's entry, so one read lock over the "
                        + "gets lets two threads write it at once: " + seen);
        assertEquals(Boolean.FALSE, seen.get("insertionCacheGetsUnderOneReadLock"),
                "an insertion-ordered map's get() only reads, so one read lock guards it: " + seen);
        assertEquals(Boolean.TRUE, seen.get("lruCacheGetsAloneBesideAPut"),
                "two unguarded gets on an access-ordered map in one round write it at once: " + seen);
        assertEquals(Boolean.FALSE, seen.get("insertionCacheGetsAloneBesideAPut"),
                "gets alone in one round on an insertion-ordered map only read, and the put ran "
                        + "alone in another round (#787): " + seen);
        assertEquals(Boolean.TRUE, seen.get("lruCollectionGetsUnderOneReadLock"),
                "the agent-fed path judges a woven get() the same way: " + seen);
        assertEquals(Boolean.FALSE, seen.get("insertionCollectionGetsUnderOneReadLock"),
                "an insertion-ordered map read under the read lock beside a writer under the write "
                        + "lock is the correct read-write idiom: " + seen);
        assertEquals(Boolean.FALSE, seen.get("lruCollectionContainsKeyUnderOneReadLock"),
                "containsKey() does not relink even on an access-ordered map: " + seen);
    }

    /**
     * Without the opening the order is unknown, and every map keeps the verdict it had: gets alone
     * in a round still count as writes for the round rule, and a read lock still guards a get. The
     * second is the part of #807 that stays open.
     */
    @Test
    void withJavaUtilClosedEveryLinkedHashMapKeepsItsEarlierVerdict() throws Exception {
        assertFalse(LinkedHashMap.class.getModule().isOpen("java.util", SelfGuard.class.getModule()),
                "this scenario needs a JVM that does not open java.util to the library");

        Map<String, Boolean> seen = Probe.run();

        assertEquals(Boolean.TRUE, seen.get("lruCacheGetsAloneBesideAPut"), seen.toString());
        assertEquals(Boolean.TRUE, seen.get("insertionCacheGetsAloneBesideAPut"),
                "an unknown order counts as access order for the round rule: " + seen);
        assertEquals(Boolean.FALSE, seen.get("lruCacheGetsUnderOneReadLock"),
                "an unknown order counts as insertion order for the lock check, so a read lock "
                        + "still guards the get: " + seen);
        assertEquals(Boolean.FALSE, seen.get("insertionCacheGetsUnderOneReadLock"), seen.toString());
        assertEquals(Boolean.FALSE, seen.get("lruCollectionGetsUnderOneReadLock"), seen.toString());
        assertEquals(Boolean.FALSE, seen.get("insertionCollectionGetsUnderOneReadLock"),
                seen.toString());
        assertEquals(Boolean.FALSE, seen.get("lruCollectionContainsKeyUnderOneReadLock"),
                seen.toString());
    }

    private static Map<String, Boolean> runInChildJvm(String... jvmFlags) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new java.util.ArrayList<>();
        command.add(java);
        command.addAll(List.of(jvmFlags));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), Probe.class.getName()));

        Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), Charset.defaultCharset());
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child JVM did not exit within 60s:\n" + output);
        assertEquals(0, child.exitValue(), "child JVM failed:\n" + output);

        Map<String, Boolean> seen = new TreeMap<>();
        for (String line : output.split("\\R")) {
            if (line.startsWith(Probe.PREFIX)) {
                String[] pair = line.substring(Probe.PREFIX.length()).split("=", 2);
                seen.put(pair[0], Boolean.valueOf(pair[1]));
            }
        }
        assertEquals(7, seen.size(), "child JVM did not print every scenario:\n" + output);
        return seen;
    }

    /** The scenarios, run in this JVM or as the child JVM's main class. */
    static final class Probe {

        static final String PREFIX = "probe:";

        private Probe() {
        }

        public static void main(String[] args) throws InterruptedException {
            for (Map.Entry<String, Boolean> scenario : run().entrySet()) {
                System.out.println(PREFIX + scenario.getKey() + "=" + scenario.getValue());
            }
            System.out.flush();
        }

        static Map<String, Boolean> run() throws InterruptedException {
            Map<String, Boolean> seen = new TreeMap<>();
            seen.put("lruCacheGetsUnderOneReadLock", cacheGetsUnderOneReadLock(lru()));
            seen.put("insertionCacheGetsUnderOneReadLock", cacheGetsUnderOneReadLock(insertion()));
            seen.put("lruCacheGetsAloneBesideAPut", cacheGetsAloneBesideAPut(lru()));
            seen.put("insertionCacheGetsAloneBesideAPut", cacheGetsAloneBesideAPut(insertion()));
            seen.put("lruCollectionGetsUnderOneReadLock", collectionReadsUnderOneReadLock(lru(), "get"));
            seen.put("insertionCollectionGetsUnderOneReadLock",
                    collectionReadsUnderOneReadLock(insertion(), "get"));
            seen.put("lruCollectionContainsKeyUnderOneReadLock",
                    collectionReadsUnderOneReadLock(lru(), "containsKey"));
            return seen;
        }

        private static Map<String, String> lru() {
            return new LinkedHashMap<>(16, 0.75f, true);
        }

        private static Map<String, String> insertion() {
            return new LinkedHashMap<>();
        }

        /** A put under the write lock in one round, then two gets under the read lock in the next. */
        private static boolean cacheGetsUnderOneReadLock(Map<String, String> cache)
                throws InterruptedException {
            CacheConcurrencyDetector detector = new CacheConcurrencyDetector();
            ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            round(scope, () -> { }, () -> underLock(lock, false,
                    () -> detector.recordPut(cache, "cache", "k", "v")));
            Runnable get = () -> underLock(lock, true, () -> detector.recordGet(cache, "cache", "k"));
            round(scope, () -> { }, get, get);
            return !detector.analyze().concurrentReadWrite.isEmpty();
        }

        /** A put alone in one round, then two unguarded gets alone in the next. */
        private static boolean cacheGetsAloneBesideAPut(Map<String, String> cache)
                throws InterruptedException {
            CacheConcurrencyDetector detector = new CacheConcurrencyDetector();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            round(scope, () -> { }, () -> detector.recordPut(cache, "cache", "k", "v"));
            Runnable get = () -> detector.recordGet(cache, "cache", "k");
            round(scope, () -> { }, get, get);
            return !detector.analyze().concurrentReadWrite.isEmpty();
        }

        /** One round: a put under the write lock and two reads under the read lock. */
        private static boolean collectionReadsUnderOneReadLock(Map<String, String> map, String operation)
                throws InterruptedException {
            SharedCollectionDetector detector = new SharedCollectionDetector();
            ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
            SelfGuard.Scope scope = new SelfGuard.Scope();
            Runnable read = () -> underLock(lock, true, () -> detector.recordRead(map, "map", operation));
            round(scope, detector::markInvocationStart,
                    () -> underLock(lock, false, () -> detector.recordWrite(map, "map", "put")),
                    read, read);
            return detector.analyze().hasIssues();
        }

        private static void underLock(ReentrantReadWriteLock lock, boolean shared, Runnable body) {
            Lock view = shared ? lock.readLock() : lock.writeLock();
            view.lock();
            HeldLocks.acquired(lock, shared);
            try {
                body.run();
            } finally {
                HeldLocks.released(lock, shared);
                view.unlock();
            }
        }

        /**
         * Starts the next round of {@code scope} and runs each body on a fresh thread with the
         * scope bound, released together so their accesses overlap, as a run's workers are.
         */
        private static void round(SelfGuard.Scope scope, Runnable roundStart, Runnable... bodies)
                throws InterruptedException {
            roundStart.run();
            scope.markInvocationStart();
            CyclicBarrier start = new CyclicBarrier(bodies.length);
            Thread[] workers = new Thread[bodies.length];
            for (int i = 0; i < bodies.length; i++) {
                Runnable body = bodies[i];
                workers[i] = new Thread(() -> {
                    SelfGuard.Scope.bind(scope);
                    try {
                        start.await();
                        body.run();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    } finally {
                        SelfGuard.Scope.unbind();
                    }
                }, "round-worker-" + i);
                workers[i].start();
            }
            for (Thread worker : workers) {
                worker.join();
            }
        }
    }
}
