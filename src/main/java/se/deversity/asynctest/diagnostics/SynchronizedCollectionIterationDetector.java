package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import se.deversity.vibetags.annotations.AITestDriven;

/**
 * Detects iteration over {@link Collections#synchronizedList},
 * {@link Collections#synchronizedMap}, or {@link Collections#synchronizedSet} wrappers
 * without holding the wrapper's intrinsic lock.
 *
 * <p>The Javadoc for these wrappers explicitly requires:
 * <pre>{@code
 * synchronized (list) {
 *     Iterator i = list.iterator();
 *     while (i.hasNext()) foo(i.next());
 * }
 * }</pre>
 * Iterating without synchronization allows another thread to modify the collection
 * mid-iteration, causing {@link ConcurrentModificationException} or silently skipped elements.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var mon = AsyncTestContext.synchronizedCollectionIterationMonitor();
 * List<String> list = Collections.synchronizedList(new ArrayList<>());
 * mon.recordWrapperCreated(list, "my-list");
 * // later — holdingLock = false means not inside synchronized(list) { }
 * mon.recordIterationStarted(list, Thread.currentThread(), false);
 * }</pre>
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SynchronizedCollectionIterationDetectorTest.java"
)
public class SynchronizedCollectionIterationDetector {

    private static class WrapperInfo {
        final String name;
        final AtomicInteger unsafeIterations = new AtomicInteger();
        final List<String>  details          = new CopyOnWriteArrayList<>();

        WrapperInfo(String name) { this.name = name; }
    }

    private final Map<Integer, WrapperInfo> wrappers = new ConcurrentHashMap<>();

    /** Register a synchronized wrapper created by {@code Collections.synchronized*(collection)}. */
    public void recordWrapperCreated(Object wrapper, String name) {
        if (wrapper == null) return;
        String label = name != null ? name : "collection@" + System.identityHashCode(wrapper);
        wrappers.put(System.identityHashCode(wrapper), new WrapperInfo(label));
    }

    /**
     * Record an iteration starting on a synchronized wrapper.
     *
     * @param wrapper     the synchronized wrapper collection
     * @param thread      the iterating thread
     * @param holdingLock {@code true} if the caller is inside {@code synchronized(wrapper) { }}
     */
    public void recordIterationStarted(Object wrapper, Thread thread, boolean holdingLock) {
        if (wrapper == null || thread == null) return;
        WrapperInfo info = wrappers.get(System.identityHashCode(wrapper));
        if (info == null || holdingLock) return;
        info.unsafeIterations.incrementAndGet();
        info.details.add(String.format(
            "Thread '%s' iterated '%s' without holding synchronized(%s) — "
            + "concurrent modification may cause ConcurrentModificationException or skipped elements",
            thread.getName(), info.name, info.name));
    }

    /** @return report of unsafe iterations */
    public SynchronizedCollectionIterationReport analyze() {
        SynchronizedCollectionIterationReport r = new SynchronizedCollectionIterationReport();
        for (WrapperInfo w : wrappers.values()) {
            if (w.unsafeIterations.get() > 0) {
                r.violations.add(String.format("'%s': %d unsafe iteration(s) detected",
                    w.name, w.unsafeIterations.get()));
                r.details.addAll(w.details);
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SynchronizedCollectionIterationReport {
        final List<String> violations = new ArrayList<>();
        final List<String> details    = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SYNCHRONIZED COLLECTION ITERATION WITHOUT LOCK:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            for (String d : details)    sb.append("    * ").append(d).append("\n");
            sb.append("  Fix: wrap iteration in synchronized(collection) { ... }, "
                    + "or switch to ConcurrentHashMap / CopyOnWriteArrayList");
            return sb.toString();
        }
    }
}
