package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Detects deadlocks between class initializers, where the lock each thread waits on is the JVM's
 * per-class initialization lock rather than a monitor any Java code can name.
 *
 * <p>Two threads are enough. Thread A touches class {@code X} first and enters {@code X.<clinit>};
 * thread B touches {@code Y} and enters {@code Y.<clinit>}. If {@code X.<clinit>} then references
 * {@code Y} and {@code Y.<clinit>} references {@code X}, both threads block forever: the JVM
 * requires initialization of a class to complete before any thread may use it, and each holds the
 * lock the other is waiting for (JLS 12.4.2).
 *
 * <p><strong>Why the ordinary deadlock detector cannot see this.</strong>
 * {@code ThreadMXBean.findDeadlockedThreads()} walks monitor and ownable-synchronizer ownership.
 * A class initialization lock is neither: it lives in the JVM's instance-class metadata, is not a
 * Java object monitor, and does not appear in any {@code LockInfo}. So the platform's own deadlock
 * finder returns {@code null} for a textbook static-initializer deadlock, which is precisely why
 * this class exists alongside {@link DeadlockDetector} rather than inside it. The failure mode in
 * production is a service that hangs on startup under load and starts fine every time you try to
 * reproduce it single-threaded, because the interleaving only exists when two threads race to
 * touch the two classes.
 *
 * <p><strong>Two evidence paths.</strong>
 * <ul>
 *   <li><strong>Recorded wait-for graph (CRITICAL, a verdict).</strong> Instrument the static
 *       initializers with {@link #recordInitStart}, {@link #recordInitRequest} and
 *       {@link #recordInitEnd}. A cycle in the resulting thread-waits-for-thread graph is a
 *       deadlock, not a slow start.</li>
 *   <li><strong>Live thread sample (HIGH, corroborating).</strong> With no instrumentation at
 *       all, {@link #analyze()} samples every live thread once and looks for {@code <clinit>}
 *       frames in threads that are blocked or waiting. Two threads parked inside two different
 *       class initializers is the signature, and the sample names the classes.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * class Config {
 *     static {
 *         var d = AsyncTestContext.staticInitDeadlockDetector();
 *         d.recordInitStart(Config.class, Thread.currentThread());
 *         d.recordInitRequest(Registry.class, Thread.currentThread());
 *         DEFAULTS = Registry.defaults();          // may block on Registry.<clinit>
 *         d.recordInitEnd(Config.class, Thread.currentThread());
 *     }
 * }
 * }</pre>
 *
 * @since 1.8.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
    note = "Holder and wait maps are ConcurrentHashMap keyed on class name / thread id. The "
        + "live-thread sample is taken at most once and cached in an AtomicReference so "
        + "analyze() stays idempotent even though the threads it observes are not.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/StaticInitDeadlockDetectorTest.java"
)
public final class StaticInitDeadlockDetector {

    private static final Logger log = LoggerFactory.getLogger(StaticInitDeadlockDetector.class);

    /** The JVM's own name for a static initializer frame. */
    private static final String CLINIT = "<clinit>";

    private record Waiter(long threadId, String threadName, String requestedClass) { }

    private record Holder(long threadId, String threadName) { }

    /** A thread observed sitting inside a class initializer, from the live sample. */
    private record Parked(String threadName, Thread.State state, String initializingClass) { }

    /** Class name to the thread currently running its {@code <clinit>}. */
    private final Map<String, Holder> holders = new ConcurrentHashMap<>();

    /** Thread id to what that thread is waiting to have initialized. */
    private final Map<Long, Waiter> waits = new ConcurrentHashMap<>();

    /** Cached live-thread sample, taken once so repeated analyze() calls agree. */
    private final AtomicReference<@Nullable List<Parked>> sample = new AtomicReference<>();

    /**
     * Record entry into a class's static initializer. From this moment the calling thread holds
     * that class's initialization lock, and every other thread that touches the class blocks.
     *
     * @param type   the class whose {@code <clinit>} is running (null-safe)
     * @param thread the initializing thread
     */
    public void recordInitStart(@Nullable Class<?> type, @Nullable Thread thread) {
        if (type == null || thread == null) return;
        holders.put(type.getName(), new Holder(thread.threadId(), thread.getName()));
    }

    /**
     * Record that the calling thread is about to touch a class that may need initializing. Call
     * this immediately before the reference that can block.
     *
     * @param requested the class about to be touched (null-safe)
     * @param thread    the requesting thread
     */
    public void recordInitRequest(@Nullable Class<?> requested, @Nullable Thread thread) {
        if (requested == null || thread == null) return;
        waits.put(thread.threadId(),
                  new Waiter(thread.threadId(), thread.getName(), requested.getName()));
    }

    /**
     * Record completion of a class's static initializer. Releases the class's initialization lock
     * and clears every wait that lock was blocking, so a run that merely serialised on class
     * loading leaves no outstanding edges behind.
     *
     * @param type   the class whose {@code <clinit>} finished (null-safe)
     * @param thread the initializing thread
     */
    public void recordInitEnd(@Nullable Class<?> type, @Nullable Thread thread) {
        if (type == null) return;
        holders.remove(type.getName());
        if (thread != null) waits.remove(thread.threadId());
        waits.values().removeIf(w -> w.requestedClass().equals(type.getName()));
    }

    /**
     * Evaluate the observed state and produce a report. Idempotent: the live-thread sample is
     * taken at most once per detector instance and reused, so calling this N times on quiescent
     * state yields N identical reports.
     *
     * @return the report of initialization deadlock cycles and parked initializer threads
     */
    public Report analyze() {
        Report r = new Report();

        for (List<Waiter> cycle : findCycles()) {
            StringBuilder chain = new StringBuilder();
            for (Waiter w : cycle) {
                if (chain.length() > 0) chain.append(" → ");
                chain.append(String.format("'%s' is initializing and waits for %s",
                        w.threadName(), simple(w.requestedClass())));
            }
            String msg = String.format(
                "CRITICAL: class-initialization deadlock across %d threads: %s → (back to the "
                + "start). Each thread holds the JVM initialization lock of the class it is "
                + "inside and needs the one the next thread holds, so none can finish. "
                + "ThreadMXBean.findDeadlockedThreads() cannot see this: a class init lock is not "
                + "a monitor and not an ownable synchronizer, so the platform's deadlock finder "
                + "reports nothing while the JVM is fully wedged.",
                cycle.size(), chain);
            add(r, IssueSeverity.CRITICAL, msg, cycle.size());
        }

        List<Parked> parked = liveSample();
        Set<String> classes = new LinkedHashSet<>();
        for (Parked p : parked) classes.add(simple(p.initializingClass()));
        if (parked.size() >= 2 && classes.size() >= 2 && r.violations.isEmpty()) {
            StringBuilder who = new StringBuilder();
            for (Parked p : parked) {
                if (who.length() > 0) who.append("; ");
                who.append(String.format("'%s' (%s) inside %s",
                        p.threadName(), p.state(), simple(p.initializingClass())));
            }
            add(r, IssueSeverity.HIGH, String.format(
                "HIGH: %d threads are parked inside %d different class initializers — %s. That is "
                + "the shape of a static-initialization deadlock. It is reported from a live "
                + "stack sample rather than a recorded wait-for graph, so it could also be one "
                + "slow initializer that several threads are queued behind; instrument the "
                + "initializers with recordInitStart/recordInitRequest to get a definite answer.",
                parked.size(), classes.size(), who), parked.size());
        }
        return r;
    }

    /**
     * Walk the thread-waits-for-thread graph and return every distinct cycle. An edge exists when
     * a waiting thread's requested class is currently held by a different thread.
     */
    private List<List<Waiter>> findCycles() {
        Map<Long, Waiter> snapshot = new LinkedHashMap<>(waits);
        List<List<Waiter>> cycles = new ArrayList<>();
        Set<Long> alreadyReported = new HashSet<>();

        for (Waiter start : snapshot.values()) {
            if (alreadyReported.contains(start.threadId())) continue;

            List<Waiter> path = new ArrayList<>();
            Set<Long> onPath = new LinkedHashSet<>();
            Waiter current = start;

            while (current != null && onPath.add(current.threadId())) {
                path.add(current);
                Holder h = holders.get(current.requestedClass());
                if (h == null || h.threadId() == current.threadId()) { current = null; break; }
                current = snapshot.get(h.threadId());
            }

            if (current != null && current.threadId() == start.threadId() && path.size() >= 2) {
                cycles.add(path);
                for (Waiter w : path) alreadyReported.add(w.threadId());
            }
        }
        return cycles;
    }

    /**
     * Sample every live thread once, looking for threads parked inside a static initializer.
     * The result is cached: a detector must not report a different answer each time it is asked.
     */
    private List<Parked> liveSample() {
        List<Parked> cached = sample.get();
        if (cached != null) return cached;

        List<Parked> found = new ArrayList<>();
        try {
            for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                Thread t = e.getKey();
                if (t.threadId() == Thread.currentThread().threadId()) continue;
                Thread.State state = t.getState();
                if (state != Thread.State.BLOCKED
                        && state != Thread.State.WAITING
                        && state != Thread.State.TIMED_WAITING) continue;
                for (StackTraceElement f : e.getValue()) {
                    if (CLINIT.equals(f.getMethodName())) {
                        found.add(new Parked(t.getName(), state, f.getClassName()));
                        break;
                    }
                }
            }
        } catch (RuntimeException ex) {
            // A security manager, or a thread dying mid-walk, must not fail the test run: this
            // sample is corroborating evidence, and losing it is strictly better than turning a
            // detector into the reason the suite went red. Recorded rather than swallowed so a
            // permanently empty sample is diagnosable.
            log.debug("staticinit.sample.failed reason={}", ex.toString());
        }
        sample.compareAndSet(null, List.copyOf(found));
        return sample.get() == null ? List.of() : sample.get();
    }

    /**
     * {@return whether the platform's own deadlock finder sees anything}
     * Exposed so a test can pin the premise of this detector: for a class-initialization
     * deadlock the answer is {@code false} while the JVM is genuinely wedged.
     */
    public static boolean platformDeadlockDetectorSeesAnything() {
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            long[] ids = mx.findDeadlockedThreads();
            return ids != null && ids.length > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String simple(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static void add(Report r, IssueSeverity severity, String msg, int threadCount) {
        r.violations.add(msg);
        r.structuredViolations.add(new Violation(
                "StaticInitDeadlock",
                severity,
                msg,
                List.of(),
                Map.of("threadCount", threadCount),
                Instant.now()));
    }

    /** Report produced by {@link #analyze()}. {@code hasIssues()} drives the SPI sweep. */
    public static final class Report implements GradedFindings {
        /** Human-readable findings, one per violation. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as machine-readable {@link Violation} records. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * Checks if any issues were detected.
         *
         * @return true if there are violations, false otherwise
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        /**
         * One grade per finding, so a verdict-grade finding is not held back by a weaker one from
         * the same detector.
         *
         * <p>The recorded cycle is a verdict: a closed chain of classes each waiting on the next is a
         * deadlock, not a suspicion. The corroborating sample states which threads were seen inside
         * which initializer, which is true as far as it goes and leaves the conclusion to the
         * reader, so it is a {@link TrustTier#FACT}.
         */
        @Override
        public List<GradedFindings.Grade> grades() {
            return structuredViolations.stream()
                    .map(v -> new GradedFindings.Grade(v.severity(), tierOf(v.severity()), v.message()))
                    .toList();
        }

        private static TrustTier tierOf(IssueSeverity severity) {
            return switch (severity) {
            case CRITICAL -> TrustTier.VERDICT;
            case HIGH -> TrustTier.FACT;
            default -> TrustTier.PROMPT;
            };
        }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "StaticInitDeadlock — clean";
            StringBuilder sb = new StringBuilder("STATIC INITIALIZATION DEADLOCK DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Break the cycle between the two <clinit> bodies. Whichever direction ")
              .append("is easier to remove, remove it: a static initializer that references ")
              .append("another class it does not strictly need is the usual culprit.\n")
              .append("    - Move the cross-class work out of <clinit> and behind a lazy holder ")
              .append("class, so initialization happens on first use rather than on class load:\n")
              .append("        private static final class Holder { static final Registry V = ")
              .append("Registry.build(); }\n")
              .append("    - Do not start threads from a static initializer and then wait for ")
              .append("them: the new thread cannot use the half-initialized class, so it blocks ")
              .append("on the lock the starting thread still holds.\n")
              .append("    - Keep <clinit> to assigning constants. Anything that can block, do ")
              .append("elsewhere.\n");
            return sb.toString();
        }
    }
}
