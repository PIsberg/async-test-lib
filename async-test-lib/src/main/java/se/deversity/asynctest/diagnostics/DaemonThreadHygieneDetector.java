package se.deversity.asynctest.diagnostics;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.AgentThreadHooks;
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
 * <h2>Without the agent it cannot see a thread the test body constructs</h2>
 * A thread inherits its daemon flag from the thread that created it
 * ({@code Thread(Runnable, String)} copies {@code parent.isDaemon()}), and every worker
 * {@code ConcurrencyRunner} hands a body to is a daemon thread: virtual threads always are,
 * and the platform workers were made daemon so that a deadlocked one could not hold the JVM
 * open (issue #479). So every {@code new Thread(...)} started from a test body is already
 * daemon before the body can get it wrong, in either thread mode, and {@link #analyze()}
 * skips anything recorded by hand that is daemon, because the flag alone cannot say whether anybody
 * decided it. {@code useVirtualThreads = false} was the documented way round this and is not
 * one any more.
 *
 * <p>What it can still see is a thread whose {@code ThreadFactory} sets the flag itself,
 * because that decision does not depend on the caller:
 * {@code Executors.defaultThreadFactory()}, and therefore every JDK thread pool, calls
 * {@code setDaemon(false)} on each thread it hands back. A body that leaks a pool thread is
 * reported. So is a thread created outside the body, on JUnit's own non-daemon thread, and
 * recorded from inside it.
 *
 * <p>The runner announces the limitation once per JVM at INFO as
 * {@code runner.detector.inert}. A clean report from a run that only recorded threads the
 * body constructed means "not observed", not "clean". See issues #352 and #479;
 * {@code DaemonThreadHygieneObservabilityTest} pins both directions.
 *
 * <h2>With the agent it judges the decision instead of the flag</h2>
 * Attached with {@code collections=true}, the agent weaves {@code Thread.start()} and
 * {@code Thread.setDaemon(boolean)} through {@link AgentThreadHooks} (#731). A woven start
 * records the thread through {@link #recordObservedStart(Thread)}, and a thread started that
 * way is reported while alive unless a woven {@code setDaemon(true)} was seen on it, whatever
 * flag it inherited. The runner then does not announce the limitation. A decision made where
 * the agent does not weave, by {@code Thread.Builder.OfPlatform.daemon()} or in a class
 * outside {@code includes=}, is not seen, so such a thread started from woven code is
 * reported as undecided.
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
        final boolean observedAtStart;
        final @Nullable StackTraceElement creationSite;

        ThreadState(long threadId, String label, String threadName,
                    boolean observedAtStart,
                    @Nullable StackTraceElement creationSite) {
            this.threadId = threadId;
            this.label = label;
            this.threadName = threadName;
            this.observedAtStart = observedAtStart;
            this.creationSite = creationSite;
        }
    }

    private final Map<Long, ThreadState> tracked = new ConcurrentHashMap<>();

    /**
     * Record a thread at the moment a woven call site starts it.
     *
     * <p>Unlike {@link #recordThread(Thread, String)}, a daemon flag the thread inherited does
     * not excuse it: only an explicit {@code setDaemon(true)} seen by the agent does. The
     * agent's {@code Thread.start()} hook is the intended caller. A thread already recorded
     * through {@code recordThread} keeps its label and site and is judged the same way.
     *
     * @param thread the thread being started (null-safe; ignored if {@code null})
     * @since 1.12.3
     */
    public void recordObservedStart(Thread thread) {
        if (thread == null) return;
        recordThread(thread, null, true);
        tracked.computeIfPresent(thread.threadId(), (id, s) -> s.observedAtStart ? s
                : new ThreadState(id, s.label, s.threadName, true, s.creationSite));
    }

    /**
     * Record a thread created in user code.
     *
     * @param thread the thread (null-safe; ignored if {@code null})
     * @param label  descriptive name for the report (may be {@code null}; falls
     *               back to {@link Thread#getName()})
     */
    public void recordThread(Thread thread, @Nullable String label) {
        recordThread(thread, label, false);
    }

    private void recordThread(Thread thread, @Nullable String label, boolean observedAtStart) {
        if (thread == null) return;
        long id = thread.threadId();
        if (tracked.containsKey(id)) return; // first-registration wins, like SharedMessageDigestDetector
        String effectiveLabel = (label != null) ? label : thread.getName();
        StackTraceElement site = firstUserFrame(Thread.currentThread().getStackTrace());
        tracked.putIfAbsent(id,
                new ThreadState(id, effectiveLabel, thread.getName(), observedAtStart, site));
    }

    /**
     * Analyze: a thread is flagged when it is still alive at analysis time and (1) is not
     * daemon, or (2) was started by a woven call site with no observed {@code setDaemon}
     * decision. The daemon flag is read here, not when the thread was recorded, because
     * {@code setDaemon} may legally run between {@code recordThread} and {@code start()} (#760).
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (ThreadState s : tracked.values()) {
            Thread t = findLiveThread(s.threadId);
            // Only flag if the thread is CURRENTLY alive. A thread that has cleanly
            // terminated (whether we found it or not in the active set) cannot
            // block JVM exit, so it is not a hygiene issue at analysis time.
            if (t == null || !t.isAlive()) continue;

            if (t.isVirtual()) continue; // always daemon, and never holds the JVM open

            Boolean explicit = AgentThreadHooks.explicitDaemonSetting(t);
            if (Boolean.TRUE.equals(explicit)) continue; // the decision this rule asks for

            // Daemon with no decision seen: inherited from a daemon creator, or set somewhere
            // nothing watched. Only a woven start makes that a finding (#731); a manual recording
            // keeps the old reading, daemon means JVM-exit-friendly. The flag is read now, not at
            // recording: setDaemon may run between recordThread and start() (#760), and after
            // start() it cannot change, so for a woven start this is the flag it started with.
            boolean inheritedDaemon = t.isDaemon() && explicit == null;
            if (inheritedDaemon && !s.observedAtStart) continue;

            String msg = String.format(inheritedDaemon
                    ? "'%s' (thread name='%s', id=%d) was started without an observed "
                            + "setDaemon call and is still alive at analysis time. It is daemon "
                            + "here only because it inherited the flag from the runner's daemon "
                            + "worker; started from a non-daemon thread such as main, the same "
                            + "code creates a thread that blocks JVM exit. Call "
                            + "thread.setDaemon(true) before start(), or ensure the thread "
                            + "terminates before the test ends."
                    : "'%s' (thread name='%s', id=%d) is non-daemon and still alive at "
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

    private static @Nullable Thread findLiveThread(long id) {
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

    private static @Nullable StackTraceElement firstUserFrame(StackTraceElement[] frames) {
        for (StackTraceElement f : frames) {
            String cls = f.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.")) continue;
            if (cls.startsWith("se.deversity.asynctest.Agent")) continue;
            if (cls.startsWith("se.deversity.asynctest.diagnostics.DaemonThreadHygieneDetector")) continue;
            if (cls.endsWith("Detector") || cls.endsWith("Monitor") || cls.endsWith("Validator")) continue;
            return f;
        }
        return null;
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** Threads whose daemon status does not match what the run expects. */
        public final Set<String> flagged = new LinkedHashSet<>();
        /** The same findings as {@link se.deversity.asynctest.report.Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "DAEMON THREAD HYGIENE — clean";
            StringBuilder sb = new StringBuilder("DAEMON THREAD HYGIENE DETECTED (" + IssueSeverity.MEDIUM.getLabel() + "):\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Call thread.setDaemon(true) BEFORE start() if the thread should not block JVM exit.\n")
              .append("    - Prefer Thread.ofVirtual().start(...) — virtual threads are always daemon.\n")
              .append("    - If the thread should live for the JVM's lifetime, register a shutdown hook to join it.\n");
            return sb.toString();
        }
    }
}
