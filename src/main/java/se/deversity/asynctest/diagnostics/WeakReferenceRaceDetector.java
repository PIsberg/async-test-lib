package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects race conditions around {@link java.lang.ref.WeakReference} and
 * {@link java.lang.ref.SoftReference} get() calls.
 *
 * <p>Two failure modes are detected:
 * <ol>
 *   <li><strong>Null dereference</strong> — code calls {@code get()} and proceeds to use
 *       the result without null-checking it first. Even if the result was non-null at call
 *       time, a competing GC cycle can collect the referent between the check and the use.</li>
 *   <li><strong>Referent collected mid-test</strong> — the same reference returned non-null
 *       from one thread and null from another, meaning the referent was collected during the
 *       concurrent test run. Code that does not handle null on every code path will fail
 *       intermittently.</li>
 * </ol>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.weakReferenceRaceDetector();
 * Foo val = weakRef.get();
 * d.recordGet(weakRef, "weakRef", val, Thread.currentThread());
 * if (val == null) return; // properly guarded
 * val.use();
 * }</pre>
 *
 * <p>To report a missing null check before use:
 * <pre>{@code
 * Foo val = weakRef.get(); // no null check follows
 * d.recordNullDereference(weakRef, "weakRef", Thread.currentThread());
 * val.use(); // unsafe if val is null
 * }</pre>
 *
 * @since 0.9.0
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/WeakReferenceRaceDetectorTest.java"
)
public class WeakReferenceRaceDetector {

    private static class RefState {
        final String      name;
        final AtomicBoolean sawNonNull    = new AtomicBoolean(false);
        final AtomicBoolean sawNull       = new AtomicBoolean(false);
        final Set<String>  nullThreads    = ConcurrentHashMap.newKeySet();
        final Set<String>  nonNullThreads = ConcurrentHashMap.newKeySet();
        final List<String> nullDerefs     = new CopyOnWriteArrayList<>();

        RefState(String name) { this.name = name; }
    }

    private final Map<Integer, RefState> refs = new ConcurrentHashMap<>();

    /**
     * Record the result of calling {@code ref.get()}.
     *
     * @param ref    the WeakReference or SoftReference instance (used as identity key)
     * @param name   descriptive label for reports
     * @param result the value returned by get() — may be {@code null}
     * @param thread the calling thread
     */
    public void recordGet(Object ref, String name, Object result, Thread thread) {
        if (ref == null || thread == null) return;
        String label = name != null ? name : "ref@" + System.identityHashCode(ref);
        RefState s = refs.computeIfAbsent(System.identityHashCode(ref), id -> new RefState(label));
        if (result != null) {
            s.sawNonNull.set(true);
            s.nonNullThreads.add(thread.getName());
        } else {
            s.sawNull.set(true);
            s.nullThreads.add(thread.getName());
        }
    }

    /**
     * Record that a null result from {@code ref.get()} was used without a null check.
     * Call this when the code proceeds to use the return value of {@code get()} without
     * first asserting it is non-null.
     *
     * @param ref    the WeakReference or SoftReference instance
     * @param name   descriptive label for reports
     * @param thread the calling thread
     */
    public void recordNullDereference(Object ref, String name, Thread thread) {
        if (ref == null || thread == null) return;
        String label = name != null ? name : "ref@" + System.identityHashCode(ref);
        RefState s = refs.computeIfAbsent(System.identityHashCode(ref), id -> new RefState(label));
        s.nullDerefs.add(thread.getName());
    }

    /** @return report of weak-reference race and null-dereference issues */
    public WeakReferenceRaceReport analyze() {
        WeakReferenceRaceReport r = new WeakReferenceRaceReport();
        for (RefState s : refs.values()) {
            if (!s.nullDerefs.isEmpty()) {
                r.violations.add(String.format(
                        "'%s': WeakReference.get() result used without null check on thread(s) (%s) — "
                                + "the referent may be collected at any point",
                        s.name, String.join(", ", s.nullDerefs)));
            } else if (s.sawNonNull.get() && s.sawNull.get()) {
                r.warnings.add(String.format(
                        "'%s': referent was collected during the test — non-null on (%s), null on (%s) — "
                                + "ensure every code path handles null",
                        s.name,
                        String.join(", ", s.nonNullThreads),
                        String.join(", ", s.nullThreads)));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class WeakReferenceRaceReport {
        final List<String> violations = new ArrayList<>();
        final List<String> warnings   = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty() || !warnings.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("WEAK REFERENCE RACE DETECTED:\n");
            for (String v : violations) sb.append("  ERROR - ").append(v).append("\n");
            for (String w : warnings)   sb.append("  WARN  - ").append(w).append("\n");
            sb.append("  Why: The garbage collector may collect a weakly-reachable object at any moment, including between\n"
                    + "       a null-check and the next use of the result. Without a null-check on every get() call,\n"
                    + "       code will throw NullPointerException non-deterministically under GC pressure.\n"
                    + "  Fix: assign get() to a local variable once and null-check that variable:\n"
                    + "       T ref = weakRef.get(); if (ref != null) { use(ref); }  // safe: local variable not GC'd\n"
                    + "       Never call weakRef.get() twice expecting the same result — the GC can collect between the two calls");
            return sb.toString();
        }
    }
}
