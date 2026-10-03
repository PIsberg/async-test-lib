package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * var mon = AsyncTestContext.publicLockExposureMonitor();
 * mon.recordSynchronizedOnThis(this, Thread.currentThread(), getClass().getSimpleName());
 * mon.recordObjectPublished(this, "returned from getService()");
 * }</pre>
 */
public class PublicLockExposureDetector {

    /** Labels for objects the test gave no name, numbered per kind within this detector (#860). */
    private final UnnamedLabels unnamedLabels = new UnnamedLabels();

    private final Set<IdentityKey>          synchronizedObjects = ConcurrentHashMap.newKeySet();
    private final Set<IdentityKey>          publishedObjects    = ConcurrentHashMap.newKeySet();
    private final Map<IdentityKey, String>  objectNames         = new ConcurrentHashMap<>();
    private final Map<IdentityKey, String>  publishContexts     = new ConcurrentHashMap<>();

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
        IdentityKey id = new IdentityKey(obj);
        synchronizedObjects.add(id);
        if (className != null) objectNames.put(id, className);
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
        IdentityKey id = new IdentityKey(obj);
        publishedObjects.add(id);
        if (context != null) publishContexts.put(id, context);
    }

    /**
     * {@return report of publicly exposed internal locks}
     */
    public PublicLockExposureReport analyze() {
        PublicLockExposureReport r = new PublicLockExposureReport();
        for (IdentityKey id : synchronizedObjects) {
            if (publishedObjects.contains(id)) {
                String name = objectNames.getOrDefault(id, unnamedLabels.of(id, "object"));
                String ctx  = publishContexts.getOrDefault(id, "external code");
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
