package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;

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
    /**
     * Both writers — {@code recordFieldAccess} and {@code detectCheckThenActViolation} — are
     * called straight from the user's concurrently running test body, so this collection is
     * mutated by N threads at once. A plain ArrayList loses elements under concurrent add (two
     * threads write the same index), silently dropping real violations before analysis reads
     * them, and can throw ArrayIndexOutOfBoundsException into the user's test body.
     */
    private final Queue<String> atomicityViolations = new ConcurrentLinkedQueue<>();
    private volatile boolean enabled = true;
    /**
     * Records compound operation start so it can be analysed at the end of the run.
     *
     * @param operationName the operation name
     */

    public void recordCompoundOperationStart(String operationName) {
        if (!enabled || operationName == null || operationName.isBlank()) {
            return;
        }

        activeOperations.put(operationKey(operationName),
            new CompoundOperation(operationName, Thread.currentThread().threadId()));
    }
    /**
     * Records compound operation end so it can be analysed at the end of the run.
     *
     * @param operationName the operation name
     */

    public void recordCompoundOperationEnd(String operationName) {
        if (!enabled || operationName == null || operationName.isBlank()) {
            return;
        }

        activeOperations.remove(operationKey(operationName));
    }
    /**
     * Records field access so it can be analysed at the end of the run.
     *
     * @param fieldName the field name
     * @param value the value
     * @param isWrite the is write
     */

    public void recordFieldAccess(String fieldName, @Nullable Object value, boolean isWrite) {
        recordFieldAccess(fieldName, value, isWrite, Thread.currentThread().threadId());
    }

    /**
     * Records a field access attributed to an explicit thread id, rather than
     * {@code Thread.currentThread()}.
     *
     * <p>This overload exists for callers that observe an access from a thread other than
     * the one performing the recording — most notably the telemetry drain thread, which
     * replays field-access events captured on the stress-test worker threads (see
     * {@code se.deversity.asynctest.telemetry.TelemetryBridge}). Passing the originating
     * {@code threadId} keeps cross-thread atomicity analysis correct; the three-argument
     * {@link #recordFieldAccess(String, Object, boolean)} overload simply forwards
     * {@code Thread.currentThread().threadId()} here.
     *
     * <p>The {@code value} may be {@code null}: {@code null} values are tolerated (the
     * cross-thread mixed read/write analysis relies only on {@code threadId} and
     * {@code isWrite}, and the compound-operation first-read tracking skips {@code null}
     * reads), so access-pattern-only sources such as the agent — which has method-name
     * granularity but no field value — can feed this detector meaningfully.
     *
     * @param fieldName the qualified field/accessor identifier; {@code null}/blank is ignored
     * @param value     the observed value, or {@code null} when unavailable
     * @param isWrite   {@code true} for a write access, {@code false} for a read
     * @param threadId  the id of the thread the access is attributed to
     * @since 1.7.0
     */
    public void recordFieldAccess(String fieldName, @Nullable Object value, boolean isWrite,
                                  long threadId) {
        if (!enabled || fieldName == null || fieldName.isBlank()) {
            return;
        }

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
                operation.firstReads.computeIfAbsent(fieldName, k -> value);
            }
        }
    }
    /**
     * Detect check then act violation.
     *
     * @param fieldName the field name
     * @param checkValue the check value
     * @param expectedValue the expected value
     * @param wouldAct the would act
     * @return the detect check then act violation
     */

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
    /**
     * Analyses what has been recorded about atomicity and builds the report for it.
     *
     * @return the analyze atomicity
     */

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

    /**
     * Standardized alias for {@link #analyzeAtomicity()}.
     *
     * @return the analyze
     */
    public AtomicityReport analyze() {
        return analyzeAtomicity();
    }

    private String operationKey(String operationName) {
        return Thread.currentThread().threadId() + ":" + operationName;
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */

    public void reset() {
        activeOperations.clear();
        fieldHistory.clear();
        atomicityViolations.clear();
    }
    /**
     * Disable.
     */

    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */

    public void enable() {
        enabled = true;
    }

    public static class AtomicityReport {
        /** The check then act violations. */
        public final Set<String> checkThenActViolations = new HashSet<>();
        /** The unsafe field accesses. */
        public final Set<String> unsafeFieldAccesses = new HashSet<>();
        /** The totcou races. */
        public final Set<String> totcouRaces = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
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
