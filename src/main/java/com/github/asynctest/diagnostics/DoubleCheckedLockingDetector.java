package com.github.asynctest.diagnostics;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects broken double-checked locking patterns.
 * 
 * Problem: Double-checked locking without volatile can cause partially
 * constructed objects to be visible to other threads.
 * 
 * Broken pattern:
 *   if (instance == null) {              // First check (no lock)
 *       synchronized(lock) {
 *           if (instance == null) {      // Second check (with lock)
 *               instance = new Instance(); // May be partially constructed!
 *           }
 *       }
 *   }
 * 
 * Fix: Make instance volatile
 */
public class DoubleCheckedLockingDetector {

    private final Map<String, DCLInfo> dclRegistry = new ConcurrentHashMap<>();
    private final Set<String> brokenDCLs = ConcurrentHashMap.newKeySet();

    /**
     * Register a double-checked locking pattern for monitoring.
     */
    public void registerDCL(String fieldName, boolean isVolatile, boolean hasFirstCheck, 
                           boolean hasSecondCheck, boolean insideSynchronized) {
        DCLInfo info = new DCLInfo(fieldName, isVolatile, hasFirstCheck, 
                                   hasSecondCheck, insideSynchronized);
        dclRegistry.put(fieldName, info);
        
        // Detect broken pattern: DCL without volatile
        if (hasFirstCheck && hasSecondCheck && insideSynchronized && !isVolatile) {
            brokenDCLs.add(fieldName);
        }
    }

    /**
     * Record access to a field that might use DCL.
     */
    public void recordAccess(String fieldName, boolean isRead, boolean isWrite) {
        DCLInfo info = dclRegistry.get(fieldName);
        if (info != null) {
            if (isRead) info.readCount++;
            if (isWrite) info.writeCount++;
        }
    }

    /**
     * Analyze DCL patterns and return report.
     */
    public DoubleCheckedLockingReport analyze() {
        return new DoubleCheckedLockingReport(dclRegistry, brokenDCLs);
    }

    /**
     * Report class for DCL analysis.
     */
    public static class DoubleCheckedLockingReport {
        private final Map<String, DCLInfo> dclRegistry;
        private final Set<String> brokenDCLs;

        public DoubleCheckedLockingReport(
            Map<String, DCLInfo> dclRegistry,
            Set<String> brokenDCLs
        ) {
            this.dclRegistry = dclRegistry;
            this.brokenDCLs = brokenDCLs;
        }

        public boolean hasIssues() {
            return !brokenDCLs.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("DOUBLE-CHECKED LOCKING ISSUES DETECTED:\n");

            if (!brokenDCLs.isEmpty()) {
                sb.append("  Broken DCL Patterns (missing volatile):\n");
                for (String fieldName : brokenDCLs) {
                    DCLInfo info = dclRegistry.get(fieldName);
                    sb.append("    - ").append(fieldName).append("\n");
                    sb.append("      Problem: Double-checked locking without volatile keyword.\n");
                    sb.append("               Object may be partially constructed when accessed\n");
                    sb.append("               by other threads.\n");
                }
                sb.append("  Fix: Make the field volatile:\n");
                sb.append("    private volatile ").append(getExampleType(brokenDCLs.iterator().next())).append(" ").append(brokenDCLs.iterator().next()).append(";\n");
                sb.append("\n  Or use proper initialization:\n");
                sb.append("    - Holder pattern (Bill Pugh Singleton)\n");
                sb.append("    - Enum singleton\n");
                sb.append("    - Static initializer\n");
            }

            if (!hasIssues()) {
                sb.append("  No double-checked locking issues detected.\n");
            }

            return sb.toString();
        }

        private String getExampleType(String fieldName) {
            DCLInfo info = dclRegistry.get(fieldName);
            if (info != null && info.fieldName.contains("instance")) {
                return "MyClass";
            }
            return "Object";
        }
    }

    /**
     * Internal DCL information.
     */
    static class DCLInfo {
        final String fieldName;
        final boolean isVolatile;
        final boolean hasFirstCheck;
        final boolean hasSecondCheck;
        final boolean insideSynchronized;
        int readCount = 0;
        int writeCount = 0;

        DCLInfo(String fieldName, boolean isVolatile, boolean hasFirstCheck,
                boolean hasSecondCheck, boolean insideSynchronized) {
            this.fieldName = fieldName;
            this.isVolatile = isVolatile;
            this.hasFirstCheck = hasFirstCheck;
            this.hasSecondCheck = hasSecondCheck;
            this.insideSynchronized = insideSynchronized;
        }
    }
}
