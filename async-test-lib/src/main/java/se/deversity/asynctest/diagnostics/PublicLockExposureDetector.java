package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import java.util.Map;

/**
 * Detects classes that use {@code synchronized(this)} (or {@code synchronized} instance
 * methods) while {@code this} is publicly accessible — exposing the internal lock to
 * external callers.
 *
 * <p>External code can acquire the same lock ({@code synchronized(obj) { ... }}), which:
 * <ul>
 *   <li>Enables deadlock if the external holder waits for something the object also waits for</li>
 *   <li>Causes latency spikes if external code holds the lock for an unpredictable duration</li>
 *   <li>Violates the encapsulation invariant that only the class controls its own synchronization</li>
 * </ul>
 *
 * <p>The standard fix is {@code private final Object lock = new Object()}.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.publicLockExposureDetector();
 * mon.recordSynchronizedOnThis(this, Thread.currentThread(), getClass().getSimpleName());
 * mon.recordObjectPublished(this, "returned from getService()");
 * }</pre>
 */
public class PublicLockExposureDetector extends AbstractInstanceDetector<PublicLockExposureDetector.ObjectState> {

    /** What was recorded about one object: whether it locks on itself, and whether it escaped. */
    static final class ObjectState {
        final String label;
        volatile boolean synchronizesOnThis;
        volatile boolean published;
        volatile @Nullable String className;
        volatile @Nullable String publishContext;

        ObjectState(String label) {
            this.label = label;
        }
    }

    @Override
    ObjectState newState(Object instance, String label) {
        return new ObjectState(label);
    }

    /**
     * Record that {@code obj} is being used as a lock via {@code synchronized(this)}
     * or a {@code synchronized} instance method.
     *
     * @param obj       the object used as the lock (null-safe)
     * @param thread    the locking thread (null-safe)
     * @param className simple class name for reports
     */
    public void recordSynchronizedOnThis(Object obj, Thread thread, String className) {
        if (obj == null) return;
        ObjectState s = stateFor(obj, null, "object");
        if (className != null) s.className = className;
        s.synchronizesOnThis = true;
    }

    /**
     * Record that {@code obj} has been published to external code — stored in a public field,
     * returned from a public method, or passed to an external API.
     *
     * @param obj     the published object (null-safe)
     * @param context describes the publication point, e.g. "returned from getService()"
     */
    public void recordObjectPublished(Object obj, String context) {
        if (obj == null) return;
        ObjectState s = stateFor(obj, null, "object");
        if (context != null) s.publishContext = context;
        s.published = true;
    }

    /**
     * {@return report of publicly exposed internal locks}
     */
    public PublicLockExposureReport analyze() {
        PublicLockExposureReport r = new PublicLockExposureReport();
        for (ObjectState s : states()) {
            if (s.synchronizesOnThis && s.published) {
                String className = s.className;
                String context = s.publishContext;
                String name = className != null ? className : s.label;
                String ctx  = context != null ? context : "external code";
                String finding = String.format(
                    "%s uses synchronized(this) but is publicly exposed via %s — "
                    + "external callers can acquire its lock, causing unintended coupling or deadlock",
                    name, ctx);
                r.violations.add(finding);
                r.structuredViolations.add(new Violation("PublicLockExposure", IssueSeverity.HIGH,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class PublicLockExposureReport {
        final List<String> violations = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("PUBLIC LOCK EXPOSURE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: replace synchronized(this) with a private final Object lock = new Object(); "
                    + "ensure the lock object is never accessible to external callers");
            return sb.toString();
        }
    }
}
