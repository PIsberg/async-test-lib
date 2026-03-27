package com.github.asynctest.diagnostics;

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

    private final Map<ArrayInfo, Set<String>> elementAccesses = new ConcurrentHashMap<>();
    private final Set<ArrayInfo> problematicArrays = ConcurrentHashMap.newKeySet();

    /**
     * Register a volatile array for monitoring.
     */
    public void registerArray(Object array, String name, Class<?> componentType) {
        ArrayInfo info = new ArrayInfo(name, array, componentType);
        elementAccesses.put(info, ConcurrentHashMap.newKeySet());
    }

    /**
     * Record a write to an array element.
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
                    .map(a -> a.split(":")[0])
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

    private ArrayInfo findArrayInfo(Object array, String arrayName) {
        for (ArrayInfo info : elementAccesses.keySet()) {
            if (info.array == array || info.name.equals(arrayName)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Analyze array access patterns and return report.
     */
    public VolatileArrayReport analyze() {
        return new VolatileArrayReport(
            elementAccesses,
            problematicArrays
        );
    }

    /**
     * Report class for volatile array analysis.
     */
    public static class VolatileArrayReport {
        private final Map<ArrayInfo, Set<String>> elementAccesses;
        private final Set<ArrayInfo> problematicArrays;

        public VolatileArrayReport(
            Map<ArrayInfo, Set<String>> elementAccesses,
            Set<ArrayInfo> problematicArrays
        ) {
            this.elementAccesses = elementAccesses;
            this.problematicArrays = problematicArrays;
        }

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
