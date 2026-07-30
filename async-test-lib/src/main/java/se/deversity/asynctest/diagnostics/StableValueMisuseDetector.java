package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects misuse of Java 25+ {@code StableValue} (JEP 502 — Stable Values,
 * Preview in JDK 25, continuing in JDK 26).
 *
 * <p>{@code StableValue<T>} is a <em>deferred-immutable</em> holder: it is
 * unset at construction and may be set <strong>at most once</strong>. Once set,
 * its content is treated by the JVM as a true constant (it can be constant-folded
 * like a {@code final} field), while still allowing the assignment to happen
 * lazily. This makes it the modern, thread-safe replacement for the
 * double-checked-locking and holder-class lazy-initialization idioms.
 *
 * <p>The safety guarantees only hold if the access pattern respects the
 * at-most-once contract. This detector flags the patterns that break it:
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Read before set</b> — {@code orElseThrow()} / {@code get()} invoked
 *       before any value has been set; this throws {@code NoSuchElementException}
 *       at runtime. Under concurrency this is a publication race: a reader can
 *       observe the holder before the writer's {@code trySet} completes.</li>
 *   <li><b>Double set</b> — a second {@code setOrThrow(...)} (or a {@code trySet}
 *       with a conflicting value) after the holder is already set. {@code setOrThrow}
 *       throws {@code IllegalStateException}; {@code trySet} silently drops the
 *       second value — a lost update when callers assume their write won.</li>
 *   <li><b>Reentrant computation</b> — the supplier passed to {@code orElseSet(...)}
 *       reads the <em>same</em> {@code StableValue} (directly or transitively) while
 *       it is still computing. This is a self-deadlock / {@code IllegalStateException}
 *       on the JDK implementation and an infinite recursion in hand-rolled holders.</li>
 *   <li><b>Set contention</b> — many distinct threads race to set the same holder.
 *       Correct but wasteful: all-but-one supplier invocation is discarded, so a
 *       costly supplier is computed N times for one stored result (design smell).</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * private static final StableValue<Config> CONFIG = StableValue.of();
 *
 * @AsyncTest(threads = 16)
 * void testLazyConfig() {
 *     var detector = AsyncTestContext.stableValueMisuseDetector();
 *     String name = "CONFIG";
 *
 *     detector.recordSupplierStart(name, Thread.currentThread());
 *     Config c = CONFIG.orElseSet(() -> loadConfig()); // at-most-once
 *     detector.recordSupplierEnd(name, Thread.currentThread());
 *
 *     detector.recordRead(name, Thread.currentThread());
 *     use(c);
 * }
 * }</pre>
 *
 * @since 1.7.0
 */
public class StableValueMisuseDetector {

    /** Above this many distinct threads racing one holder, we warn about wasted supplier work. */
    private static final int SET_CONTENTION_THRESHOLD = 3;

    private static final class State {
        final AtomicBoolean set = new AtomicBoolean(false);
        final AtomicInteger setAttempts = new AtomicInteger(0);
        final Set<Long> settingThreadIds = ConcurrentHashMap.newKeySet();
        final AtomicBoolean contentionReported = new AtomicBoolean(false);
    }

    // Per-holder state, keyed by the caller-supplied descriptive name.
    private final Map<String, State> states = new ConcurrentHashMap<>();

    // Per-thread set of holder names whose orElseSet supplier is currently running (reentrancy).
    private final Map<Long, Set<String>> activeSuppliers = new ConcurrentHashMap<>();

    private final List<String> readBeforeSetReports  = Collections.synchronizedList(new ArrayList<>());
    private final List<String> doubleSetReports       = Collections.synchronizedList(new ArrayList<>());
    private final List<String> reentrantReports       = Collections.synchronizedList(new ArrayList<>());
    private final List<String> contentionWarnings     = Collections.synchronizedList(new ArrayList<>());

    private final AtomicInteger totalReads = new AtomicInteger(0);
    private final AtomicInteger totalSets  = new AtomicInteger(0);

    private State stateFor(String name) {
        State s = states.get(name);
        if (s == null) {
            s = states.computeIfAbsent(name, k -> new State());
        }
        return s;
    }

    /**
     * Record a value-set attempt ({@code trySet} / {@code setOrThrow}) on the holder.
     * A second attempt after the holder is already set is reported as a double-set.
     *
     * @param name   a descriptive name for the StableValue (e.g. field name)
     * @param thread the current thread
     */
    public void recordSet(String name, Thread thread) {
        if (name == null || thread == null) return;
        totalSets.incrementAndGet();
        State s = stateFor(name);
        s.settingThreadIds.add(thread.threadId());
        s.setAttempts.incrementAndGet();

        boolean wasAlreadySet = !s.set.compareAndSet(false, true);
        if (wasAlreadySet) {
            doubleSetReports.add(
                "Thread " + thread.getName() + " (id=" + thread.threadId() + "): "
                + "StableValue '" + name + "' set again after it was already set. "
                + "setOrThrow() throws IllegalStateException here; trySet() silently drops "
                + "this value — a lost update if this writer assumed it won."
            );
        }

        // Record once per holder, the first time the distinct-thread count crosses
        // the threshold. The one-shot flag avoids both duplicates and the missed-edge
        // race of comparing size() to an exact value under concurrent adds.
        if (s.settingThreadIds.size() >= SET_CONTENTION_THRESHOLD
                && s.contentionReported.compareAndSet(false, true)) {
            contentionWarnings.add(
                "StableValue '" + name + "': " + s.settingThreadIds.size()
                + " distinct threads raced to set it. Only one value is stored; the "
                + "supplier work of the losers is discarded. Prefer a single orElseSet(...) "
                + "with an idempotent, side-effect-free supplier."
            );
        }
    }

    /**
     * Record a read ({@code orElseThrow()} / {@code get()}) on the holder.
     * A read before any set is reported as a read-before-set (NoSuchElementException risk).
     *
     * @param name   a descriptive name for the StableValue
     * @param thread the current thread
     */
    public void recordRead(String name, Thread thread) {
        if (name == null || thread == null) return;
        totalReads.incrementAndGet();
        State s = stateFor(name);
        if (!s.set.get()) {
            readBeforeSetReports.add(
                "Thread " + thread.getName() + " (id=" + thread.threadId() + "): "
                + "StableValue '" + name + "' read via orElseThrow()/get() before it was set — "
                + "this throws NoSuchElementException. Use orElseSet(supplier) for lazy init, "
                + "or guard the read after confirming the value is present."
            );
        }
    }

    /**
     * Record the start of an {@code orElseSet(supplier)} computation. If the same
     * holder's supplier is already running on this thread, the supplier is reading
     * the value it is meant to produce (reentrant computation).
     *
     * @param name   a descriptive name for the StableValue
     * @param thread the current thread
     */
    public void recordSupplierStart(String name, Thread thread) {
        if (name == null || thread == null) return;
        long tid = thread.threadId();
        Set<String> running = activeSuppliers.computeIfAbsent(tid, k -> ConcurrentHashMap.newKeySet());
        if (!running.add(name)) {
            reentrantReports.add(
                "Thread " + thread.getName() + " (id=" + tid + "): "
                + "StableValue '" + name + "' orElseSet() supplier re-entered the same holder "
                + "while it was still computing. The JDK throws IllegalStateException to break "
                + "the cycle; a hand-rolled holder would recurse infinitely. Remove the "
                + "self-reference from the supplier."
            );
        }
    }

    /**
     * Record the successful completion of an {@code orElseSet(supplier)} computation.
     * Marks the holder as set.
     *
     * @param name   a descriptive name for the StableValue
     * @param thread the current thread
     */
    public void recordSupplierEnd(String name, Thread thread) {
        if (name == null || thread == null) return;
        long tid = thread.threadId();
        Set<String> running = activeSuppliers.get(tid);
        if (running != null) {
            running.remove(name);
        }
        State s = stateFor(name);
        s.settingThreadIds.add(tid);
        s.set.set(true);
    }

    /**
     * Analyze all recorded StableValue events for misuse patterns.
     *
     * @return a report describing detected issues
     */
    public StableValueMisuseReport analyze() {
        return new StableValueMisuseReport(
            new ArrayList<>(readBeforeSetReports),
            new ArrayList<>(doubleSetReports),
            new ArrayList<>(reentrantReports),
            new ArrayList<>(contentionWarnings),
            totalReads.get(),
            totalSets.get()
        );
    }

    /**
     * Report of StableValue misuse analysis.
     */
    public static class StableValueMisuseReport {
        private final List<String> readBeforeSetIssues;
        private final List<String> doubleSetIssues;
        private final List<String> reentrantIssues;
        private final List<String> contentionWarnings;
        private final int totalReads;
        private final int totalSets;

        StableValueMisuseReport(
                List<String> readBeforeSetIssues,
                List<String> doubleSetIssues,
                List<String> reentrantIssues,
                List<String> contentionWarnings,
                int totalReads,
                int totalSets) {
            this.readBeforeSetIssues = readBeforeSetIssues;
            this.doubleSetIssues = doubleSetIssues;
            this.reentrantIssues = reentrantIssues;
            this.contentionWarnings = contentionWarnings;
            this.totalReads = totalReads;
            this.totalSets = totalSets;
        }

        /** {@return true if any correctness-affecting StableValue misuse was detected} */
        public boolean hasIssues() {
            return !readBeforeSetIssues.isEmpty()
                || !doubleSetIssues.isEmpty()
                || !reentrantIssues.isEmpty();
        }

        public List<String> getReadBeforeSetIssues() { return Collections.unmodifiableList(readBeforeSetIssues); }
        public List<String> getDoubleSetIssues()      { return Collections.unmodifiableList(doubleSetIssues); }
        public List<String> getReentrantIssues()      { return Collections.unmodifiableList(reentrantIssues); }
        public List<String> getContentionWarnings()   { return Collections.unmodifiableList(contentionWarnings); }
        public int          getTotalReads()           { return totalReads; }
        public int          getTotalSets()            { return totalSets; }

        @Override
        public String toString() {
            if (!hasIssues() && contentionWarnings.isEmpty()) {
                return "StableValueMisuseReport: No StableValue misuse detected";
            }

            StringBuilder sb = new StringBuilder();

            if (!reentrantIssues.isEmpty() || !readBeforeSetIssues.isEmpty()) {
                sb.append(IssueSeverity.CRITICAL.format())
                  .append(": StableValue accessed before/while being set (will throw at runtime)\n");
            } else if (!doubleSetIssues.isEmpty()) {
                sb.append(IssueSeverity.HIGH.format())
                  .append(": StableValue set more than once (lost update / IllegalStateException)\n");
            } else {
                sb.append(IssueSeverity.LOW.format())
                  .append(": StableValue usage warnings\n");
            }

            sb.append("  Reads=").append(totalReads)
              .append(", Sets=").append(totalSets).append("\n");

            appendSection(sb, "Read before set (NoSuchElementException risk)", readBeforeSetIssues);
            appendSection(sb, "Double set (lost update / IllegalStateException)", doubleSetIssues);
            appendSection(sb, "Reentrant orElseSet() computation", reentrantIssues);
            appendSection(sb, "Set contention (wasted supplier work)", contentionWarnings);

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
        }

        private static void appendSection(StringBuilder sb, String title, List<String> items) {
            if (items.isEmpty()) return;
            sb.append("\n  ").append(title).append(":\n");
            for (String item : items) {
                sb.append("    - ").append(item).append("\n");
            }
        }

        private static String getLearningContent() {
            return """
                📚 LEARNING: StableValue (Java 25+, JEP 502)

                StableValue<T> is a deferred-immutable holder: unset at first, settable
                at most once, then treated as a true constant by the JVM. It replaces the
                double-checked-locking and holder-class idioms for thread-safe lazy init.

                Correct usage:
                  static final StableValue<Config> CONFIG = StableValue.of();

                  // Lazy, at-most-once, thread-safe:
                  Config c = CONFIG.orElseSet(() -> loadConfig());

                Common mistakes:
                  ✗ orElseThrow()/get() before the value is set → NoSuchElementException
                  ✗ setOrThrow() twice → IllegalStateException (or trySet drops the 2nd value)
                  ✗ orElseSet supplier that reads the same StableValue → reentrant deadlock
                  ✗ Many threads each computing a costly supplier to set one holder (only
                    one result is kept — make the supplier idempotent and side-effect-free)

                Rule of thumb:
                  • Set exactly once, via orElseSet(supplier), with a pure supplier.
                  • Never read before the set is guaranteed to have happened-before the read.
                """;
        }
    }
}
