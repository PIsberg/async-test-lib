package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
     *
     * @param fieldName the field involved, as it should appear in the report
     * @param isVolatile the {@code isVolatile} flag
     * @param hasFirstCheck the {@code hasFirstCheck} flag
     * @param hasSecondCheck the {@code hasSecondCheck} flag
     * @param insideSynchronized the {@code insideSynchronized} flag
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
     *
     * @param fieldName the field involved, as it should appear in the report
     * @param isRead the {@code isRead} flag
     * @param isWrite the {@code isWrite} flag
     */
    public void recordAccess(String fieldName, boolean isRead, boolean isWrite) {
        DCLInfo info = dclRegistry.get(fieldName);
        if (info != null) {
            if (isRead) info.readCount.incrementAndGet();
            if (isWrite) info.writeCount.incrementAndGet();
        }
    }

    /**
     * Analyze DCL patterns and return report.
     *
     * @return the findings this detector collected during the run
     */
    public DoubleCheckedLockingReport analyze() {
        DoubleCheckedLockingReport report801 = new DoubleCheckedLockingReport(dclRegistry, brokenDCLs);
        report801.fillStructuredViolations();
        return DetectorFailurePolicy.checkedReport(this, report801);
    }

    /**
     * Report class for DCL analysis.
     */
    public static class DoubleCheckedLockingReport {
        private final Map<String, DCLInfo> dclRegistry;
        private final Set<String> brokenDCLs;
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a Violation per finding (#801); called once by the detector before it returns the report. */
        void fillStructuredViolations() {
            if (!hasIssues()) {
                return;
            }
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(toString()).orElse(IssueSeverity.HIGH);
                for (String finding : brokenDCLs) {
                    structuredViolations.add(new Violation("DoubleCheckedLocking", severity,
                            finding, List.of(), Map.of(), Instant.now()));
                }
        }

        /**
         * Creates a DoubleCheckedLockingReport.
         *
         * @param dclRegistry every registered double-checked-locking site and what was observed on it
         * @param brokenDCLs the double-checked-locking sites whose guard was not safe
         */
        public DoubleCheckedLockingReport(
            Map<String, DCLInfo> dclRegistry,
            Set<String> brokenDCLs
        ) {
            this.dclRegistry = Collections.unmodifiableMap(new HashMap<>(dclRegistry));
            this.brokenDCLs = Collections.unmodifiableSet(new HashSet<>(brokenDCLs));
        }

        /**
         * {@return whether there are issues}
         */
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
        // A double-checked-locking test races threads through the field by design, so these
        // counters were being incremented concurrently in every run this detector exists for.
        final AtomicInteger readCount = new AtomicInteger();
        final AtomicInteger writeCount = new AtomicInteger();

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
