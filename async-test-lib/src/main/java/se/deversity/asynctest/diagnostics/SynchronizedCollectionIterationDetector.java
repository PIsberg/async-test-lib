package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Usage inside {@code @AsyncTest(includes = DetectorType.SYNCHRONIZED_COLLECTION_ITERATION)}:
 * <pre>{@code
 * var mon = AsyncTestContext.synchronizedCollectionIterationDetector();
 * List<String> list = Collections.synchronizedList(new ArrayList<>());
 * mon.recordWrapperCreated(list, "my-list");
 * // later — holdingLock = false means not inside synchronized(list) { }
 * mon.recordIterationStarted(list, Thread.currentThread(), false);
 * }</pre>
 */
public class SynchronizedCollectionIterationDetector extends AbstractInstanceDetector<SynchronizedCollectionIterationDetector.WrapperInfo> {

    static final class WrapperInfo {
        final String name;
        final AtomicInteger unsafeIterations = new AtomicInteger();
        final List<String>  details          = new CopyOnWriteArrayList<>();

        WrapperInfo(String name) { this.name = name; }
    }

    @Override
    WrapperInfo newState(Object instance, String label) {
        return new WrapperInfo(label);
    }

    /**
     * Register a synchronized wrapper created by {@code Collections.synchronized*(collection)}.
     *
     * @param wrapper the wrapper object being recorded, tracked by identity
     * @param name a label identifying the wrapper in the report
     */
    public void recordWrapperCreated(Object wrapper, String name) {
        if (wrapper == null) return;
        // computeIfAbsent, not put: an @AsyncTest body runs once per worker, so this is called
        // again for a wrapper already being tracked. Installing fresh state there discarded
        // every unsafe iteration counted so far. The first label wins, which is the right way
        // round - a name is cosmetic and the observations are the finding.
        stateFor(wrapper, name, "collection");
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
        WrapperInfo info = trackedState(wrapper);
        if (info == null || holdingLock) return;
        // Counted every time, described the first few: an iteration in a loop would otherwise grow
        // the copy-on-write list, and copy it, once per pass on the threads being watched.
        if (info.unsafeIterations.incrementAndGet() > MAX_DETAILS) return;
        info.details.add(String.format(
            "Thread '%s' iterated '%s' without holding synchronized(%s) — "
            + "concurrent modification may cause ConcurrentModificationException or skipped elements",
            thread.getName(), info.name, info.name));
    }

    /** How many unsafe iterations of one wrapper are described; the rest are counted. */
    static final int MAX_DETAILS = 10;

    /** Map views the agent saw handed out, to the map whose monitor guards them. */
    private final Map<IdentityKey, Object> viewOwners = new ConcurrentHashMap<>();

    /**
     * Records that {@code view} is a {@code keySet}, {@code values} or {@code entrySet} view of
     * the synchronized map {@code owner}, which the agent saw returned by a woven call. Such a view
     * iterates under the map's monitor, not its own.
     *
     * @param view  the view the map returned
     * @param owner the synchronized map
     * @since 1.13.2
     */
    public void recordView(Object view, Object owner) {
        if (view == null || owner == null) return;
        viewOwners.putIfAbsent(new IdentityKey(view), owner);
    }

    /**
     * Records an iteration the agent saw start: a woven {@code iterator()} or {@code stream()} on a
     * {@code Collections.synchronized*} wrapper. The wrapper is judged against the monitor it
     * really synchronizes on: a map view the agent saw handed out against its map; otherwise the
     * wrapper's private {@code mutex}, which the agent's {@code java.util} opening lets the library
     * read (#996), so a set or a view the run did not see being made is judged against its real
     * lock too. Without that opening a synchronized list is judged against itself, and a
     * synchronized set or collection is not judged, because it may be a map's view and judging it
     * against its own monitor would report the documented {@code synchronized (map)} idiom.
     *
     * @param collection the wrapper being iterated
     * @param thread     the iterating thread
     * @since 1.13.2
     */
    public void agentIteration(Object collection, Thread thread) {
        if (collection == null || thread == null) return;
        Object owner = viewOwners.get(IdentityKey.lookup(collection));
        Object mutex = owner == null ? SynchronizedMutexes.mutexOf(collection) : null;
        Object monitor;
        String label;
        if (owner != null) {
            monitor = owner;
            label = owner.getClass().getSimpleName() + " view";
        } else if (mutex != null) {
            // The wrapper's own lock, read with java.util opened by the agent (#996): itself for a
            // plain wrapper, the map for a view, the parent list for a subList.
            monitor = mutex;
            label = collection.getClass().getSimpleName();
        } else {
            String type = collection.getClass().getName();
            if (!"java.util.Collections$SynchronizedList".equals(type)
                    && !"java.util.Collections$SynchronizedRandomAccessList".equals(type)) {
                return;
            }
            monitor = collection;
            label = "SynchronizedList";
        }
        stateFor(collection, null, label);
        recordIterationStarted(collection, thread, Thread.holdsLock(monitor));
    }

    /**
     * {@return report of unsafe iterations}
     */
    public SynchronizedCollectionIterationReport analyze() {
        SynchronizedCollectionIterationReport r = new SynchronizedCollectionIterationReport();
        for (WrapperInfo w : states()) {
            if (w.unsafeIterations.get() > 0) {
                r.violations.add(String.format("'%s': %d unsafe iteration(s) detected",
                    w.name, w.unsafeIterations.get()));
                r.details.addAll(w.details);
            }
        }
        if (r.hasIssues()) {
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(r.toString())
                    .orElse(IssueSeverity.HIGH);
            for (String finding : r.violations) {
                r.structuredViolations.add(new Violation("SynchronizedCollectionIteration", severity,
                        finding, List.of(), Map.of(), Instant.now()));
            }
        }
        return DetectorFailurePolicy.checkedReport(this, r);
    }

    /** Report produced by {@link #analyze()}. */
    public static class SynchronizedCollectionIterationReport {
        final List<String> violations = new ArrayList<>();
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();
        final List<String> details    = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
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
