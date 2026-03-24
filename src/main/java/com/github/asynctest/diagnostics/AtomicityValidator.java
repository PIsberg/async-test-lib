package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks compound operations that should behave atomically.
 */
public class AtomicityValidator {

    private static class CompoundOperation {
        final String operationName;
        final long threadId;
        final Map<String, Object> firstReads = new ConcurrentHashMap<>();

        CompoundOperation(String operationName, long threadId) {
            this.operationName = operationName;
            this.threadId = threadId;
        }
    }

    private static class FieldAccessRecord {
        final long threadId;
        final boolean write;

        FieldAccessRecord(long threadId, boolean write) {
            this.threadId = threadId;
            this.write = write;
        }
    }

    private final Map<String, CompoundOperation> activeOperations = new ConcurrentHashMap<>();
    private final Map<String, List<FieldAccessRecord>> fieldHistory = new ConcurrentHashMap<>();
    private final List<String> atomicityViolations = new ArrayList<>();
    private volatile boolean enabled = true;

    public void recordCompoundOperationStart(String operationName) {
        if (!enabled || operationName == null || operationName.isBlank()) {
            return;
        }

        activeOperations.put(operationKey(operationName),
            new CompoundOperation(operationName, Thread.currentThread().threadId()));
    }

    public void recordCompoundOperationEnd(String operationName) {
        if (!enabled || operationName == null || operationName.isBlank()) {
            return;
        }

        activeOperations.remove(operationKey(operationName));
    }

    public void recordFieldAccess(String fieldName, Object value, boolean isWrite) {
        if (!enabled || fieldName == null || fieldName.isBlank()) {
            return;
        }

        long threadId = Thread.currentThread().threadId();
        List<FieldAccessRecord> history = fieldHistory.computeIfAbsent(fieldName, ignored -> new ArrayList<>());
        synchronized (history) {
            history.add(new FieldAccessRecord(threadId, isWrite));
        }

        for (CompoundOperation operation : activeOperations.values()) {
            if (operation.threadId != threadId) {
                continue;
            }

            if (isWrite) {
                Object initialRead = operation.firstReads.get(fieldName);
                if (initialRead != null && !initialRead.equals(value)) {
                    atomicityViolations.add(String.format(
                        "%s on %s: read %s and later wrote %s",
                        operation.operationName, fieldName, initialRead, value
                    ));
                }
            } else {
                operation.firstReads.putIfAbsent(fieldName, value);
            }
        }
    }

    public boolean detectCheckThenActViolation(String fieldName, Object checkValue,
                                               Object expectedValue, boolean wouldAct) {
        if (!enabled || !wouldAct) {
            return false;
        }

        boolean violation = checkValue != null ? !checkValue.equals(expectedValue) : expectedValue != null;
        if (violation) {
            atomicityViolations.add(String.format(
                "Check-then-act violation on %s: checked %s but observed %s",
                fieldName, checkValue, expectedValue
            ));
        }
        return violation;
    }

    public AtomicityReport analyzeAtomicity() {
        AtomicityReport report = new AtomicityReport();
        report.checkThenActViolations.addAll(atomicityViolations);

        for (Map.Entry<String, List<FieldAccessRecord>> entry : fieldHistory.entrySet()) {
            Set<Long> threads = new HashSet<>();
            boolean hasRead = false;
            boolean hasWrite = false;

            synchronized (entry.getValue()) {
                for (FieldAccessRecord access : entry.getValue()) {
                    threads.add(access.threadId);
                    hasRead |= !access.write;
                    hasWrite |= access.write;
                }
            }

            if (threads.size() > 1 && hasRead && hasWrite) {
                report.unsafeFieldAccesses.add(String.format(
                    "%s: mixed read/write compound access across %d threads",
                    entry.getKey(),
                    threads.size()
                ));
            }
            if (threads.size() > 1 && hasWrite) {
                report.totcouRaces.add(String.format(
                    "%s: state changed between check/use windows on %d threads",
                    entry.getKey(),
                    threads.size()
                ));
            }
        }

        return report;
    }

    private String operationKey(String operationName) {
        return Thread.currentThread().threadId() + ":" + operationName;
    }

    public void reset() {
        activeOperations.clear();
        fieldHistory.clear();
        atomicityViolations.clear();
    }

    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
    }

    public static class AtomicityReport {
        public final Set<String> checkThenActViolations = new HashSet<>();
        public final Set<String> unsafeFieldAccesses = new HashSet<>();
        public final Set<String> totcouRaces = new HashSet<>();

        public boolean hasIssues() {
            return !checkThenActViolations.isEmpty()
                || !unsafeFieldAccesses.isEmpty()
                || !totcouRaces.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No atomicity violations detected.";
            }

            StringBuilder sb = new StringBuilder("ATOMICITY VIOLATIONS DETECTED:\n");
            if (!checkThenActViolations.isEmpty()) {
                sb.append("\nCheck-then-act issues:\n");
                for (String violation : checkThenActViolations) {
                    sb.append("  - ").append(violation).append('\n');
                }
            }
            if (!unsafeFieldAccesses.isEmpty()) {
                sb.append("\nUnsafe compound field accesses:\n");
                for (String access : unsafeFieldAccesses) {
                    sb.append("  - ").append(access).append('\n');
                }
            }
            if (!totcouRaces.isEmpty()) {
                sb.append("\nTOCTOU windows:\n");
                for (String race : totcouRaces) {
                    sb.append("  - ").append(race).append('\n');
                }
            }
            sb.append("\nFix: synchronize the full compound operation or use CAS-based primitives");
            return sb.toString();
        }
    }
}
