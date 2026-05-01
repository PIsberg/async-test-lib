package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;

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

    private final Set<Integer>          synchronizedObjects = ConcurrentHashMap.newKeySet();
    private final Set<Integer>          publishedObjects    = ConcurrentHashMap.newKeySet();
    private final Map<Integer, String>  objectNames         = new ConcurrentHashMap<>();
    private final Map<Integer, String>  publishContexts     = new ConcurrentHashMap<>();

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
        int id = System.identityHashCode(obj);
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
        int id = System.identityHashCode(obj);
        publishedObjects.add(id);
        if (context != null) publishContexts.put(id, context);
    }

    /** @return report of publicly exposed internal locks */
    public PublicLockExposureReport analyze() {
        PublicLockExposureReport r = new PublicLockExposureReport();
        for (int id : synchronizedObjects) {
            if (publishedObjects.contains(id)) {
                String name = objectNames.getOrDefault(id, "object@" + id);
                String ctx  = publishContexts.getOrDefault(id, "external code");
                r.violations.add(String.format(
                    "%s uses synchronized(this) but is publicly exposed via %s — "
                    + "external callers can acquire its lock, causing unintended coupling or deadlock",
                    name, ctx));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class PublicLockExposureReport {
        final List<String> violations = new ArrayList<>();

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
