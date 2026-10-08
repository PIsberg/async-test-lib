package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detects {@link java.util.TimeZone} instances whose mutable state is modified while
 * being accessed from multiple threads.
 *
 * <p>{@code TimeZone} is a mutable class: {@code setRawOffset()} and {@code setID()}
 * alter the shared instance in place, which is what the javadoc documents; the JDK states no
 * thread-safety contract for {@code TimeZone} either way. This detector reports only recorded
 * mutations, so concurrent reads never reach it - a decision about what is worth reporting
 * rather than a guarantee that reading a zone somebody else is mutating is safe.
 * Unsynchronized concurrent writes (or a write concurrent with a read) produce
 * non-deterministic timezone offsets and IDs — silently wrong date/time arithmetic
 * that is notoriously hard to reproduce.
 *
 * <p>Synchronization awareness is partial. A mutation recorded while the mutating thread holds
 * the instance's own monitor - the {@code synchronized (tz)} idiom - counts as guarded, and an
 * instance whose every recorded mutation was guarded produces no finding. A guard on any other
 * lock object is invisible and still fires; treat such a finding as a prompt to verify the
 * synchronization, or to prefer immutable {@link java.time.ZoneId} or a per-thread copy.
 *
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.SHARED_TIMEZONE)}:
 * <pre>{@code
 * var d = AsyncTestContext.sharedTimeZoneDetector();
 * TimeZone tz = TimeZone.getDefault();
 * // before mutating:
 * d.recordMutation(tz, "setRawOffset", Thread.currentThread());
 * tz.setRawOffset(3600_000);
 * }</pre>
 *
 * @since 0.10.0
 */
public class SharedTimeZoneDetector extends AbstractInstanceDetector<SharedTimeZoneDetector.TzState> {

    /**
     * The mutating threads are the tracked threads: every recorded access is a mutation, so the
     * per-round thread set of the base class is the per-round mutator set, and the report counts
     * the round that raced rather than every mutator of the run (#748).
     */
    static final class TzState extends SelfGuard.ThreadTrackedInstance {
        volatile @Nullable String   firstOperation;
    }

    @Override
    TzState newState(Object instance, String label) {
        return new TzState();
    }

    /**
     * Records a mutating operation on a {@code TimeZone} instance.
     *
     * @param timeZone  the {@code TimeZone} being mutated (null-safe)
     * @param operation the method called, e.g. {@code "setRawOffset"} or {@code "setID"}
     * @param thread    the mutating thread (null-safe)
     */
    public void recordMutation(Object timeZone, String operation, Thread thread) {
        if (timeZone == null || thread == null) return;
        // The report names no instance, so the class name stands in for a label; a null name
        // would have UnnamedLabels number, and keep, the instance.
        TzState s = stateFor(timeZone, "TimeZone");
        s.noteAccess(timeZone, true, thread);
        if (s.firstOperation == null) s.firstOperation = operation != null ? operation : "mutate";
    }

    /**
     * {@return report of TimeZone instances mutated from multiple threads}
     */
    public SharedTimeZoneReport analyze() {
        SharedTimeZoneReport r = new SharedTimeZoneReport();
        for (TzState s : states()) {
            if (s.sharedAndUnguarded()) {
                String finding = String.format(
                        "TimeZone instance mutated from %d threads (%s) via '%s' — "
                                + "unsynchronized concurrent mutations corrupt date/time arithmetic"
                                + SelfGuard.REPORT_NOTE,
                        s.threadCount(),
                        String.join(", ", s.threadNames()),
                        s.firstOperation);
                r.violations.add(finding);
                r.structuredViolations.add(new Violation("SharedTimeZone", IssueSeverity.HIGH,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedTimeZoneReport {
        final List<String> violations = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED TIMEZONE MUTATION DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("""
  Why: java.util.TimeZone is mutable. A call to setID() or setRawOffset() on a shared TimeZone instance
       changes the global timezone state, affecting every thread that uses that instance — including date
       formatting in unrelated parts of the application, producing silently wrong times.
  Fix: Use java.time.ZoneId (immutable) instead of java.util.TimeZone. If TimeZone is unavoidable,
       never mutate a shared instance — call TimeZone.getTimeZone(id) to get a fresh copy and treat it as read-only.\
""");
            return sb.toString();
        }
    }
}
