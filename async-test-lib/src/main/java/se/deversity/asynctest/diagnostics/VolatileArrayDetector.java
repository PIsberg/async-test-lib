package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects volatile array element visibility issues.
 * 
 * Problem: Declaring an array as volatile only makes the reference volatile,
 * not the individual elements. Updates to array elements may not be visible
 * across threads.
 * 
 * Example:
 *   volatile int[] array = new int[10];  // Elements are NOT volatile!
 *   array[0] = 42;  // May not be visible to other threads
 */
public class VolatileArrayDetector {

    private static final java.util.regex.Pattern COLON = java.util.regex.Pattern.compile(":");

    /**
     * Access keys, one per (thread, operation, index).
     *
     * <p>The thread part is {@code threadId()}, not {@code getName()}. A name is chosen by
     * whoever created the thread, is not unique, and is the empty string for an unnamed
     * virtual thread - which is what {@code @AsyncTest}'s default workers are. Keying on it
     * collapsed distinct threads into one entry, so the "more than one thread wrote this
     * array" test could not reach two and the detector went silent under exactly the
     * sharing it exists to report.
     */
    private final Map<ArrayInfo, Set<String>> elementAccesses = new ConcurrentHashMap<>();
    private final Set<ArrayInfo> problematicArrays = ConcurrentHashMap.newKeySet();

    /**
     * Guards registration only. The key is an identity-based wrapper and findArrayInfo
     * scans, so check-then-put cannot be one atomic map operation. Registration happens
     * once per array per run and is nowhere near the recording path.
     */
    private final Object registrationLock = new Object();

    /**
     * Register a volatile array for monitoring.
     *
     * @param array the array being recorded, tracked by identity
     * @param name a label identifying the array in the report
     * @param componentType the component type of the array
     */
    public void registerArray(Object array, String name, Class<?> componentType) {
        // First registration wins, like every other registerX here. This one cannot use
        // putIfAbsent: the key is a fresh ArrayInfo with identity semantics, so a second
        // registration adds a second entry rather than colliding with the first. findArrayInfo
        // then resolves an access to whichever of them the key set happens to iterate first,
        // which can differ between two accesses because registering rehashes the map - so the
        // workers' accesses could land in different entries, each seeing a single thread.
        synchronized (registrationLock) {
            // Identity, not name: a second distinct array carrying a label some other array
            // already holds has to get its own entry. Resolving by name here collapsed every
            // per-thread buffer of a ThreadLocal onto the first one registered, and the
            // confined arrays then read as one array written by six threads.
            if (findByIdentity(array) != null) {
                return;
            }
            elementAccesses.put(new ArrayInfo(name, array, componentType),
                                ConcurrentHashMap.newKeySet());
        }
    }

    /**
     * Record a write to an array element.
     *
     * @param array the array being recorded, tracked by identity
     * @param index the array index being accessed
     * @param arrayName a label identifying the array in the report
     */
    public void recordElementWrite(Object array, int index, String arrayName) {
        ArrayInfo info = findArrayInfo(array, arrayName);
        if (info != null) {
            Set<String> accesses = elementAccesses.get(info);
            if (accesses != null) {
                String accessKey = Thread.currentThread().threadId() + ":write:" + index;
                accesses.add(accessKey);
                
                // If multiple threads write to same array, it's problematic
                long uniqueThreads = accesses.stream()
                    .filter(a -> a.contains(":write:"))
                    .map(a -> COLON.split(a, -1)[0])
                    .distinct()
                    .count();
                    
                if (uniqueThreads > 1) {
                    problematicArrays.add(info);
                }
            }
        }
    }

    /**
     * Record a read from an array element.
     *
     * @param array the array being recorded, tracked by identity
     * @param index the array index being accessed
     * @param arrayName a label identifying the array in the report
     */
    public void recordElementRead(Object array, int index, String arrayName) {
        ArrayInfo info = findArrayInfo(array, arrayName);
        if (info != null) {
            Set<String> accesses = elementAccesses.get(info);
            if (accesses != null) {
                accesses.add(Thread.currentThread().getName() + ":read:" + index);
            }
        }
    }

    /**
     * {@return the entry holding exactly {@code array}, or {@code null}}
     *
     * <p>Identity only, with no name fallback, which is what registration has to ask: two
     * distinct arrays may legitimately carry the same label - a {@code ThreadLocal<int[]>}
     * registered as {@code "buffer"} by every worker is confined, correct code - and a lookup
     * that answered by name would drop all but the first of them and then resolve every
     * worker's writes to that one entry.
     *
     * @param array the array to look for
     */
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "ReferenceEquality"}) // array identity comparison is intentional
    private @Nullable ArrayInfo findByIdentity(Object array) {
        for (ArrayInfo info : elementAccesses.keySet()) {
            if (info.array == array) {
                return info;
            }
        }
        return null;
    }

    /**
     * {@return the entry for {@code array}, preferring identity and falling back to the label}
     *
     * <p>The name fallback is kept for a caller that records against an array it never
     * registered, which is the lenient behaviour this detector has always had. It is only
     * consulted when no entry holds the array itself, so arrays that share a label no longer
     * collapse into one another.
     *
     * @param array     the array being accessed
     * @param arrayName the label it was recorded under
     */
    private @Nullable ArrayInfo findArrayInfo(Object array, String arrayName) {
        ArrayInfo byIdentity = findByIdentity(array);
        if (byIdentity != null) {
            return byIdentity;
        }
        for (ArrayInfo info : elementAccesses.keySet()) {
            if (info.name.equals(arrayName)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Analyze array access patterns and return report.
     *
     * @return the findings this detector collected during the run
     */
    public VolatileArrayReport analyze() {
        return new VolatileArrayReport(
            problematicArrays
        );
    }

    /**
     * Report class for volatile array analysis.
     */
    public static class VolatileArrayReport {
        private final Set<ArrayInfo> problematicArrays;
        /**
         * Creates a VolatileArrayReport.
         *
         * @param problematicArrays the arrays whose elements were accessed without the ordering the code assumes
         */
        public VolatileArrayReport(
            Set<ArrayInfo> problematicArrays
        ) {
            this.problematicArrays = Collections.unmodifiableSet(new HashSet<>(problematicArrays));
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !problematicArrays.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("VOLATILE ARRAY ISSUES DETECTED:\n");

            if (!problematicArrays.isEmpty()) {
                sb.append("  Volatile Arrays with Multi-Thread Access:\n");
                for (ArrayInfo info : problematicArrays) {
                    sb.append("    - ").append(info.name)
                      .append(" (").append(info.componentType.getSimpleName())
                      .append("[])\n");
                    sb.append("      Problem: volatile keyword only applies to array reference,\n");
                    sb.append("               not individual elements. Element updates may not\n");
                    sb.append("               be visible across threads.\n");
                }
                sb.append("  Why: The volatile keyword guarantees visibility of the array reference (the pointer to the array object),\n");
                sb.append("       NOT the individual array elements. A write to array[i] in Thread A may remain invisible to Thread B\n");
                sb.append("       indefinitely — producing stale reads, lost updates, and non-deterministic results.\n");
                sb.append("  Fix: Use one of these alternatives:\n");
                sb.append("    - AtomicReferenceArray<T>\n");
                sb.append("    - AtomicIntegerArray / AtomicLongArray\n");
                sb.append("    - ConcurrentHashMap<Integer, T>\n");
                sb.append("    - Make the array itself non-volatile and use proper synchronization\n");
            }

            if (!hasIssues()) {
                sb.append("  No volatile array issues detected.\n");
            }

            return sb.toString();
        }
    }

    /**
     * Internal array information.
     */
    static class ArrayInfo {
        final String name;
        final Object array;
        final Class<?> componentType;

        ArrayInfo(String name, Object array, Class<?> componentType) {
            this.name = name;
            this.array = array;
            this.componentType = componentType;
        }

        @Override
        @SuppressWarnings({"EqualsGetClass", "ReferenceEquality"}) // subclass-distinct equality is intended for this non-final class; array is tracked by identity, not value
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ArrayInfo arrayInfo = (ArrayInfo) o;
            return array == arrayInfo.array;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(array);
        }
    }
}
