package se.deversity.asynctest.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@link IdentityHashKeyScanner} follows and what it leaves alone (#803), on fixture
 * classes compiled with this module's tests.
 */
class IdentityHashKeyScannerTest {

    // --- the two shapes #763 found in the library, as they were before their fixes ---

    /** {@code ABAProblemDetector} before 5f3bd4cf: the attempt map and its one put, verbatim. */
    static final class PreFixAbaProblemDetector {
        final Map<Long, Object> casAttempts = new ConcurrentHashMap<>();

        void recordCompareAndSet(Object attempt) {
            casAttempts.put((long) System.identityHashCode(attempt), attempt);
        }
    }

    /** {@code OptimisticReadValidationDetector} before 1eef9c4f: the helper and its four map calls. */
    static final class PreFixOptimisticReadValidationDetector {
        private final Map<String, String> pendingReads = new ConcurrentHashMap<>();

        private static String key(Object lock, Thread thread) {
            return System.identityHashCode(lock) + ":" + thread.threadId();
        }

        void onRead(Object lock, Thread thread) {
            pendingReads.put(key(lock, thread), thread.getName());
        }

        boolean onValidate(Object lock, Thread thread) {
            String read = pendingReads.get(key(lock, thread));
            String k = key(lock, thread);
            String again = pendingReads.get(k);
            pendingReads.remove(k);
            return read != null && again != null;
        }
    }

    // --- shapes the source-text gate does not follow ---

    /** The hash stored in a field by the constructor and used as a key by another method. */
    static final class HashInAField {
        private static final Map<Integer, HashInAField> INDEX = new ConcurrentHashMap<>();
        private final int id;

        HashInAField(Object subject) {
            this.id = System.identityHashCode(subject);
        }

        void register() {
            INDEX.put(id, this);
        }
    }

    /** A helper longer than one {@code return}. */
    static final class LongerHelper {
        private final Set<Long> seen = new HashSet<>();

        private static long keyOf(Object o, long salt) {
            long h = System.identityHashCode(o);
            if (salt != 0) {
                h = h << 32 | salt;
            }
            return h;
        }

        boolean first(Object o) {
            return seen.add(keyOf(o, 1));
        }
    }

    /** A chain of helpers, one of them in another class. */
    static final class ChainedHelpers {
        private final Map<Integer, String> names = new HashMap<>();

        static int outer(Object o) {
            return Helpers.middle(o) + 1;
        }

        String name(Object o) {
            return names.get(outer(o));
        }
    }

    static final class Helpers {
        static int middle(Object o) {
            return inner(o);
        }

        static int inner(Object o) {
            return System.identityHashCode(o);
        }
    }

    /** {@code Thread} inherits {@code Object.hashCode()}, so its hash is an identity hash. */
    static final class ThreadHashCodeKey {
        private final Map<Integer, String> byThread = new ConcurrentHashMap<>();

        void enter(Thread thread) {
            byThread.put(thread.hashCode(), thread.getName());
        }
    }

    /** The hash becomes a key inside the helper it is passed to. */
    static final class ParameterBecomesAKey {
        private final Set<Integer> seen = new HashSet<>();

        private void remember(int hash) {
            seen.add(hash);
        }

        void onAccess(Object o) {
            remember(System.identityHashCode(o));
        }
    }

    /** The hash captured by a lambda that keys by it. */
    static final class CapturedByALambda {
        private final Set<Integer> seen = ConcurrentHashMap.newKeySet();

        void onAccess(Object o, List<Runnable> tasks) {
            int h = System.identityHashCode(o);
            tasks.add(() -> seen.add(h));
        }
    }

    /** A record key with the hash as a component: its equals compares the hash. */
    static final class RecordComponentKey {
        record Key(int hash, String field) { }

        private final Map<Key, Object> locks = new ConcurrentHashMap<>();

        Object lockFor(Object subject, String field) {
            return locks.get(new Key(System.identityHashCode(subject), field));
        }
    }

    /** A key built with a {@code StringBuilder} held in a local. */
    static final class BuiltKey {
        private final Map<String, Object> names = new HashMap<>();

        boolean known(Object o) {
            StringBuilder sb = new StringBuilder("lock@");
            sb.append(System.identityHashCode(o));
            return names.containsKey(sb.toString());
        }
    }

    /** A key formatted through varargs, and one chosen by a conditional. */
    static final class FormattedAndConditionalKeys {
        private final Map<String, Integer> states = new HashMap<>();
        private final Map<Integer, Integer> counts = new HashMap<>();

        void f(Object o, long id, boolean byIdentity) {
            states.put(String.format("%x:%d", System.identityHashCode(o), id), 1);
            counts.get(byIdentity ? System.identityHashCode(o) : 0);
        }
    }

    /** The hash reaches the key around a loop's back edge. */
    static final class AcrossALoop {
        private final Set<Integer> seen = new HashSet<>();

        int f(List<Object> objects) {
            int h = 0;
            int hits = 0;
            for (Object o : objects) {
                if (seen.contains(h)) {
                    hits++;
                }
                h = System.identityHashCode(o);
            }
            return hits;
        }
    }

    // --- correct idioms that must stay silent ---

    /** A wrapper that compares referents with {@code ==}, like the library's {@code IdentityKey}. */
    static final class IdentityKeyLike {
        private final Object referent;
        private final int hash;

        IdentityKeyLike(Object referent) {
            this.referent = referent;
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKeyLike that && that.referent == referent;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return "IdentityKeyLike@" + Integer.toHexString(hash);
        }
    }

    static final class SilentIdioms {
        private final Map<Object, String> labels = new HashMap<>();
        private final Map<IdentityKeyLike, Integer> states = new HashMap<>();
        private final Map<Integer, String> byContent = new HashMap<>();
        private final Map<Long, String> byThreadId = new HashMap<>();
        private final List<Integer> order = new ArrayList<>();
        private final Set<Integer> seen = new HashSet<>();

        private static String label(Object o) {
            return "pool@" + System.identityHashCode(o);
        }

        private void remember(int value) {
            seen.add(value);
        }

        void f(Object o, Object unknown, String s, Thread thread, PrintStream log) {
            labels.put(o, "pool@" + System.identityHashCode(o));
            labels.put(o, label(o));
            log.println(label(o));
            states.put(new IdentityKeyLike(o), 1);
            labels.put(o, new IdentityKeyLike(o).toString());
            byContent.put(s.hashCode(), s);
            byContent.put(unknown.hashCode(), s);
            byThreadId.put(thread.threadId(), thread.getName());
            order.add(System.identityHashCode(o));
            remember(42);
            int h = System.identityHashCode(o);
            log.println(h);
            h = s.length();
            seen.add(h);
        }
    }

    @Test
    @DisplayName("the shapes #763 fixed in two detectors are red")
    void theHistoricalShapesAreRed() {
        List<IdentityHashKeyScanner.Finding> aba = scan(PreFixAbaProblemDetector.class);
        assertEquals(1, aba.size(), aba::toString);
        assertTrue(aba.get(0).sink().equals("Map.put")
                && aba.get(0).origin().startsWith("System.identityHashCode"), aba::toString);

        List<IdentityHashKeyScanner.Finding> optimistic = scan(PreFixOptimisticReadValidationDetector.class);
        assertEquals(4, optimistic.size(), "every map call the helper's key reaches: " + optimistic);
    }

    @Test
    @DisplayName("fields, longer helpers, chains, parameters, lambdas and Object.hashCode are followed")
    void theShapesTheTextGateMisses() {
        assertSinks(List.of("Map.put"), scan(HashInAField.class));
        assertSinks(List.of("Set.add"), scan(LongerHelper.class));
        assertSinks(List.of("Map.get"), scan(ChainedHelpers.class, Helpers.class));
        assertSinks(List.of("the key of Set.add inside IdentityHashKeyScannerTest$ParameterBecomesAKey.remember"),
                scan(ParameterBecomesAKey.class));
        assertSinks(List.of("Map.get"), scan(RecordComponentKey.class));
        assertSinks(List.of("Map.containsKey"), scan(BuiltKey.class));
        assertSinks(List.of("Map.put", "Map.get"), scan(FormattedAndConditionalKeys.class));
        assertSinks(List.of("Set.contains"), scan(AcrossALoop.class));

        List<IdentityHashKeyScanner.Finding> thread = scan(ThreadHashCodeKey.class);
        assertSinks(List.of("Map.put"), thread);
        assertTrue(thread.get(0).origin().startsWith("Thread.hashCode()"), thread::toString);

        List<IdentityHashKeyScanner.Finding> lambda = scan(CapturedByALambda.class);
        assertEquals(1, lambda.size(), lambda::toString);
        assertTrue(lambda.get(0).sink().contains("inside a lambda"), lambda::toString);
    }

    @Test
    @DisplayName("labels, identity wrappers, content hashes and non-key uses stay silent")
    void correctIdiomsStaySilent() {
        List<IdentityHashKeyScanner.Finding> found = scan(SilentIdioms.class, IdentityKeyLike.class);
        assertEquals(List.of(), found);
    }

    private static void assertSinks(List<String> expected, List<IdentityHashKeyScanner.Finding> found) {
        assertEquals(expected, found.stream().map(IdentityHashKeyScanner.Finding::sink).toList(), found::toString);
    }

    private static List<IdentityHashKeyScanner.Finding> scan(Class<?>... classes) {
        List<byte[]> bytes = new ArrayList<>();
        for (Class<?> c : classes) {
            bytes.add(bytesOf(c));
        }
        return IdentityHashKeyScanner.scan(bytes, IdentityHashKeyScannerTest.class.getClassLoader());
    }

    private static byte[] bytesOf(Class<?> c) {
        String resource = c.getName().replace('.', '/') + ".class";
        try (InputStream in = c.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("no class file for " + c.getName());
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
