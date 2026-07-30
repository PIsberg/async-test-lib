package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link Thread} instances created by user code without
 * {@link Thread#setDaemon(boolean) setDaemon(true)} that remain alive at
 * detector tear-down.
 *
 * <p><strong>Why it matters.</strong> A non-daemon {@code Thread} is part of
 * the JVM's "keep alive" set: as long as one is running (or even just started
 * but not yet returned from {@code run()}), the JVM will not exit. Tests that
 * spin up workers and forget to mark them daemon, or that leak threads through
 * un-shut-down executors, can hang the entire test process — and the resulting
 * timeout is usually attributed to whatever test happens to be running when CI
 * gives up, not to the leaking test.
 *
 * <p>This detector complements {@link ThreadLeakDetector} (which counts live
 * worker threads regardless of daemon state) by specifically flagging the
 * <em>hygiene</em> issue: a non-daemon flag on a thread that should clearly
 * have been daemon (started inside a test, no shutdown path, still alive at
 * analysis time).
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new DaemonThreadHygieneDetector();
 * Thread t = new Thread(() -> { ... });
 * d.recordThread(t, "background-worker");
 * t.start();
 * // ... test body ...
 * var report = d.analyze();
 * assertFalse(report.hasIssues(), report.toString());
 * }</pre>
 *
 * <p>Standalone — not auto-wired into {@code @AsyncTest}. Instantiate and call
 * directly, or register through the
 * {@link se.deversity.asynctest.spi.DetectorFactory} SPI if you want it picked
 * up by {@link se.deversity.asynctest.spi.DetectorRegistry}.
 *
 * @since 1.6.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-thread access map is a ConcurrentHashMap; first-registration-wins via putIfAbsent.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/DaemonThreadHygieneDetectorTest.java"
)
public final class DaemonThreadHygieneDetector {

    private static final class ThreadState {
        final long   threadId;
        final String label;
        final String threadName;
        final boolean wasDaemonAtRegistration;
        final StackTraceElement creationSite;

        ThreadState(long threadId, String label, String threadName,
                    boolean wasDaemon, StackTraceElement creationSite) {
            this.threadId = threadId;
            this.label = label;
            this.threadName = threadName;
            this.wasDaemonAtRegistration = wasDaemon;
            this.creationSite = creationSite;
        }
    }

    private final Map<Long, ThreadState> tracked = new ConcurrentHashMap<>();

    /**
     * Record a thread created in user code.
     *
     * @param thread the thread (null-safe; ignored if {@code null})
     * @param label  descriptive name for the report (may be {@code null}; falls
     *               back to {@link Thread#getName()})
     */
    public void recordThread(Thread thread, String label) {
        if (thread == null) return;
        long id = thread.threadId();
        if (tracked.containsKey(id)) return; // first-registration wins, like SharedMessageDigestDetector
        String effectiveLabel = (label != null) ? label : thread.getName();
        StackTraceElement site = firstUserFrame(Thread.currentThread().getStackTrace());
        tracked.putIfAbsent(id,
                new ThreadState(id, effectiveLabel, thread.getName(),
                        thread.isDaemon(), site));
    }

    /**
     * Analyze: a thread is flagged when it (1) was not marked daemon at
     * registration time, and (2) is still alive (or never started) at analysis
     * time — i.e. has not cleanly terminated.
     */
    public Report analyze() {
        Report r = new Report();
        for (ThreadState s : tracked.values()) {
            if (s.wasDaemonAtRegistration) continue; // daemon → JVM-exit-friendly, OK
            Thread t = findLiveThread(s.threadId);
            // Only flag if the thread is CURRENTLY alive. A thread that has cleanly
            // terminated (whether we found it or not in the active set) cannot
            // block JVM exit, so it is not a hygiene issue at analysis time.
            if (t == null || !t.isAlive()) continue;

            String msg = String.format(
                    "'%s' (thread name='%s', id=%d) is non-daemon and still alive at "
                            + "analysis time — non-daemon threads block JVM exit. Call "
                            + "thread.setDaemon(true) before start(), or ensure the thread "
                            + "terminates before the test ends.",
                    s.label,
                    s.threadName,
                    s.threadId);
            if (s.creationSite != null) {
                msg = msg + "\n    First recorded at: "
                        + s.creationSite.getClassName() + "." + s.creationSite.getMethodName()
                        + "(" + s.creationSite.getFileName() + ":" + s.creationSite.getLineNumber() + ")";
            }
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "DaemonThreadHygiene",
                    IssueSeverity.MEDIUM,
                    msg,
                    List.of(),
                    Map.of(
                            "threadId", s.threadId,
                            "threadName", s.threadName,
                            "label", s.label,
                            "stillAlive", t.isAlive()),
                    Instant.now()));
        }
        return r;
    }

    private static Thread findLiveThread(long id) {
        // Thread.getId is unique while the thread is alive but may be reused
        // after termination — so we only return a match for ALIVE threads.
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        Thread[] all = new Thread[root.activeCount() + 32];
        int n = root.enumerate(all, true);
        for (int i = 0; i < n; i++) {
            Thread t = all[i];
            if (t != null && t.threadId() == id && t.isAlive()) return t;
        }
        return null;
    }

    private static StackTraceElement firstUserFrame(StackTraceElement[] frames) {
        for (StackTraceElement f : frames) {
            String cls = f.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.")) continue;
            if (cls.startsWith("se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector")) continue;
            if (cls.endsWith("Detector") || cls.endsWith("Monitor") || cls.endsWith("Validator")) continue;
            return f;
        }
        return null;
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        public final List<String> violations = new ArrayList<>();
        public final Set<String> flagged = new LinkedHashSet<>();
        public final List<Violation> structuredViolations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "DAEMON THREAD HYGIENE — clean";
            StringBuilder sb = new StringBuilder("DAEMON THREAD HYGIENE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Call thread.setDaemon(true) BEFORE start() if the thread should not block JVM exit.\n")
              .append("    - Prefer Thread.ofVirtual().start(...) — virtual threads are always daemon.\n")
              .append("    - If the thread should live for the JVM's lifetime, register a shutdown hook to join it.\n");
            return sb.toString();
        }
    }
}
