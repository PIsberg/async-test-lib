package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a single {@link Iterator}, {@link ListIterator}, or {@link Spliterator}
 * instance being driven from more than one thread.
 *
 * <p><strong>Why it matters.</strong> An iterator carries mutable cursor state
 * (its position, and — for fail-fast iterators — a captured {@code modCount})
 * that is advanced by every call. It is confined to the thread that obtained it
 * by contract, regardless of whether the backing collection is itself
 * thread-safe: {@code ConcurrentHashMap}, {@code CopyOnWriteArrayList}, and
 * friends guarantee safe <em>concurrent iteration by different threads each
 * using their own iterator</em>, not safe <em>concurrent use of one shared
 * iterator instance</em>. Two threads racing {@code hasNext()}/{@code next()}
 * on the same instance can each observe a stale cursor and step on the other's
 * progress, causing skipped or duplicated elements, a spurious
 * {@code NoSuchElementException}, or (for a non-concurrent backing collection)
 * a corrupted internal structure. Racing {@code remove()} is worse still: two
 * threads can attempt to remove the same logical slot, one succeeding and the
 * other throwing or silently doing nothing useful. {@link Spliterator} has the
 * identical hazard for {@code tryAdvance()}/{@code forEachRemaining()} — it is
 * designed to be handed to <em>one</em> thread at a time, with {@code trySplit()}
 * as the sanctioned way to fan work out across threads.
 *
 * <p>Distinct from {@link ConcurrentModificationDetector}, which flags a
 * collection being structurally <em>modified</em> while some thread iterates
 * it. This detector's concern is orthogonal and narrower: the very same
 * iterator/spliterator <em>object</em> being driven from two or more threads,
 * independent of whether any modification of the backing collection ever
 * occurs.
 *
 * <p>Synchronization awareness is partial. An access recorded while the accessing thread holds
 * the iterator's own monitor - the {@code synchronized (it)} idiom - counts as guarded, and an
 * iterator whose every access was guarded produces no finding. A guard on any other lock object,
 * and a handoff coordinated some other way, is invisible and still fires; treat such a finding
 * as a prompt to verify that coordination exists, or to give each thread its own iterator.
 *
 * <p>Cooperative API: call {@link #recordAccess} at each
 * {@code hasNext}/{@code next}/{@code remove}/{@code tryAdvance}/
 * {@code forEachRemaining} call site, passing the iterator instance and the
 * operation name. A violation is flagged once two or more distinct threads
 * have touched the same instance.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new SharedIteratorDetector();
 * Iterator<String> it = list.iterator();
 * d.recordAccess(it, "hasNext");
 * d.recordAccess(it, "next");
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedIteratorDetectorTest.java"
)
public final class SharedIteratorDetector {

    private static final class State extends SelfGuard.TrackedInstance {
        final String label;
        final String kind;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();
        final Set<String> operations           = ConcurrentHashMap.newKeySet();

        State(String label, String kind) {
            this.label = label;
            this.kind = kind;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access to an iterator-like instance from the calling thread.
     *
     * @param iterator  the {@link Iterator}, {@link ListIterator}, or
     *                  {@link Spliterator} instance (null-safe)
     * @param operation the operation performed, e.g. {@code "hasNext"},
     *                  {@code "next"}, {@code "remove"}, {@code "tryAdvance"},
     *                  {@code "forEachRemaining"} (may be {@code null})
     */
    public void recordAccess(Object iterator, String operation) {
        if (iterator == null) return;
        Thread thread = Thread.currentThread();
        int id = System.identityHashCode(iterator);
        State s = instances.get(id);
        if (s == null) {
            final String kind = kindOf(iterator);
            final String label = kind + "@" + id;
            s = instances.computeIfAbsent(id, k -> new State(label, kind));
        }
        s.noteAccess(iterator);
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
        if (operation != null) s.operations.add(operation);
    }

    private static String kindOf(Object iterator) {
        if (iterator instanceof Spliterator) return "Spliterator";
        if (iterator instanceof ListIterator) return "ListIterator";
        if (iterator instanceof Iterator) return "Iterator";
        return iterator.getClass().getSimpleName();
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1 || !s.sawUnguardedAccess()) continue;
            String msg = String.format(
                    "%s '%s' accessed from %d threads (%s) via %s — iterators carry mutable cursor "
                            + "state and are confined to a single thread; unsynchronized concurrent hasNext()/next()/remove()/"
                            + "tryAdvance() calls on the same instance skip or duplicate elements, throw "
                            + "NoSuchElementException, or corrupt the underlying collection, even when that "
                            + "collection is itself a concurrent collection"
                            + SelfGuard.REPORT_NOTE + ".",
                    s.kind,
                    s.label,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames),
                    String.join("/", s.operations));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedIterator",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "kind", s.kind,
                            "threadCount", s.accessingThreadIds.size()),
                    Instant.now()));
        }
        return r;
    }

    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link se.deversity.asynctest.report.Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SHARED ITERATOR — clean";
            StringBuilder sb = new StringBuilder("SHARED ITERATOR DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Give each thread its own Iterator/ListIterator via collection.iterator().\n")
              .append("    - For parallel traversal, split with Spliterator.trySplit() and hand each\n")
              .append("      resulting spliterator to exactly one thread, or use a parallel stream.\n")
              .append("    - When iterating a concurrent collection from multiple threads, have each\n")
              .append("      thread obtain and drive its own iterator rather than sharing one.\n");
            return sb.toString();
        }
    }
}
