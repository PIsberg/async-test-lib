package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;

/**
 * Detects {@code synchronized} blocks that lock on cached boxed primitives.
 *
 * <p>The JVM caches commonly-used boxed values:
 * <ul>
 *   <li>{@link Integer} and {@link Long} in the range {@code -128} to {@code 127}.</li>
 *   <li>{@link Boolean#TRUE} and {@link Boolean#FALSE}.</li>
 *   <li>Interned {@link String} literals (e.g. {@code "lock"}).</li>
 * </ul>
 * Because these are <em>identity-shared</em> instances, any code anywhere in the JVM
 * that synchronizes on the same value shares the lock — even across unrelated classes.
 * This causes surprising contention, deadlocks, or over-broad mutual exclusion.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.boxedPrimitiveLockDetector();
 * Integer lockObj = 42; // cached — dangerous!
 * d.recordLockAcquire(lockObj, Thread.currentThread(), "MyService.process:30");
 * synchronized (lockObj) { ... }
 * }</pre>
 *
 * @since 0.10.0
 */
public class BoxedPrimitiveLockDetector {

    private static class LockEvent {
        final Object lockObject;
        final long   threadId;
        final String threadName;
        final String location;
        final String reason;

        LockEvent(Object lockObject, long tid, String tname, String location, String reason) {
            this.lockObject = lockObject;
            this.threadId   = tid;
            this.threadName = tname;
            this.location   = location;
            this.reason     = reason;
        }
    }

    private final List<LockEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Records a {@code synchronized} lock acquisition attempt.
     *
     * <p>If the lock object is a cached boxed primitive this event will appear in
     * the analysis report.
     *
     * @param lockObject the monitor object (null-safe)
     * @param thread     the locking thread (null-safe)
     * @param location   human-readable location, e.g. {@code "ClassName.method:lineNum"}
     */
    public void recordLockAcquire(Object lockObject, Thread thread, String location) {
        if (lockObject == null || thread == null) return;
        String reason = detectCachedPrimitive(lockObject);
        if (reason != null) {
            events.add(new LockEvent(lockObject, thread.getId(), thread.getName(),
                    location != null ? location : "unknown", reason));
        }
    }

    private static String detectCachedPrimitive(Object obj) {
        if (obj instanceof Boolean) {
            return "Boolean cached instance (" + obj + ")";
        }
        if (obj instanceof Integer) {
            int v = (Integer) obj;
            if (v >= -128 && v <= 127) return "cached Integer(" + v + ")";
        }
        if (obj instanceof Long) {
            long v = (Long) obj;
            if (v >= -128 && v <= 127) return "cached Long(" + v + ")";
        }
        if (obj instanceof String && obj == ((String) obj).intern()) {
            return "interned String(\"" + obj + "\")";
        }
        return null;
    }

    /** @return report of synchronizations on cached boxed primitives */
    public BoxedPrimitiveLockReport analyze() {
        BoxedPrimitiveLockReport r = new BoxedPrimitiveLockReport();
        for (LockEvent e : events) {
            r.violations.add(String.format(
                    "Thread '%s' synchronized on %s at [%s] — "
                            + "this is a JVM-global shared instance; any code using the same "
                            + "value as a lock will accidentally share your monitor",
                    e.threadName, e.reason, e.location));
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class BoxedPrimitiveLockReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("BOXED PRIMITIVE LOCK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Why: Synchronizing on a boxed primitive (Integer, Long, Boolean) is dangerous because the JVM caches\n" +
                       "       commonly-used values (Integers -128 to 127, Boolean.TRUE/FALSE). Two completely unrelated code paths\n" +
                       "       that synchronize on 'Integer.valueOf(42)' acquire the same monitor object — causing accidental\n" +
                       "       coupling and potential deadlocks with code that has nothing to do with your class.\n" +
                       "  Fix: Always synchronize on a dedicated private final Object lock = new Object(); — never on a boxed\n" +
                       "       primitive, String literal, or any other object that might be shared or interned by the JVM.");
            return sb.toString();
        }
    }
}
