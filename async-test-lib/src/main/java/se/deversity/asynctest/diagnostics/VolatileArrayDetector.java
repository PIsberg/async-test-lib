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

    private final Map<ArrayInfo, Set<String>> elementAccesses = new ConcurrentHashMap<>();
    private final Set<ArrayInfo> problematicArrays = ConcurrentHashMap.newKeySet();

    /**
     * Register a volatile array for monitoring.
     *
     * @param array the array being recorded, tracked by identity
     * @param name a label identifying the array in the report
     * @param componentType the component type of the array
     */
    public void registerArray(Object array, String name, Class<?> componentType) {
        ArrayInfo info = new ArrayInfo(name, array, componentType);
        elementAccesses.put(info, ConcurrentHashMap.newKeySet());
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
                String accessKey = Thread.currentThread().getName() + ":write:" + index;
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

    @SuppressWarnings("PMD.CompareObjectsWithEquals") // array identity comparison is intentional
    private @Nullable ArrayInfo findArrayInfo(Object array, String arrayName) {
        for (ArrayInfo info : elementAccesses.keySet()) {
            if (info.array == array || info.name.equals(arrayName)) {
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
        @SuppressWarnings("EqualsGetClass") // subclass-distinct equality is intended for this non-final class
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
