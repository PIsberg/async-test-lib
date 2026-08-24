package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects misuse of the {@code StructuredTaskScope.Configuration} lambda introduced by JEP 525
 * (Structured Concurrency, sixth preview in JDK 26).
 *
 * <p>JDK 26 moved scope settings out of constructor overloads and into a
 * {@code UnaryOperator<Configuration>} passed to {@code open}:
 *
 * <pre>{@code
 * try (var scope = StructuredTaskScope.open(
 *         Joiner.<Order>allSuccessfulOrThrow(),
 *         cfg -> cfg.withTimeout(Duration.ofSeconds(3))
 *                   .withName("order-fetcher"))) {
 *     ...
 * }
 * }</pre>
 *
 * <p>{@code Configuration} is immutable and its {@code withX} methods return a new instance, which
 * is the whole trap. A lambda that calls {@code cfg.withTimeout(...)} and then returns something
 * other than that return value compiles, runs, and silently applies no timeout at all - the scope
 * waits forever on the one subtask that hangs. The old constructor form could not fail this way,
 * because there was nothing to drop on the floor.
 *
 * <p>This detector is about the settings themselves and how they interact across scopes;
 * {@link StructuredTaskScopeMisuseDetector} owns the fork/join/close lifecycle and
 * {@link ScopeJoinerMisuseDetector} owns the joiner. Nothing here duplicates either.
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Configuration discarded</b> - what the lambda asked for and what the scope ended up
 *       with differ. The usual cause is a lambda that ignores its parameter and returns a
 *       {@code Configuration} it built elsewhere, so {@code withTimeout} / {@code withName} apply
 *       to an object nobody uses.</li>
 *   <li><b>Non-positive timeout</b> - a timeout of zero or less. {@code join()} then expires
 *       before any subtask can finish, so the timeout path is the only path the scope has.</li>
 *   <li><b>Unbounded fan-out with no timeout</b> - at least
 *       {@value #DEFAULT_UNBOUNDED_FORK_THRESHOLD} subtasks forked into a scope with no deadline.
 *       Structured concurrency guarantees the scope waits for every subtask; with no timeout, one
 *       subtask that never returns is a test that never returns.</li>
 *   <li><b>Timeout shorter than the work</b> - every {@code join()} on the scope expired, across at
 *       least two joins. The configured deadline is not a safety net, it is the normal path, and
 *       the results the test asserts on are whatever the fallback produced.</li>
 *   <li><b>Thread factory shared by overlapping scopes</b> - one {@code ThreadFactory} instance
 *       configured on two scopes whose lifetimes overlap. A factory carrying per-scope state -
 *       a name counter, a thread group, an uncaught-exception handler - then serves both.</li>
 *   <li><b>Duplicate scope name</b> (warning) - two scopes alive at once under the same
 *       {@code withName}, which makes every diagnostic that quotes the name ambiguous.</li>
 * </ul>
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.scopeConfigurationMisuseDetector();
 * d.recordScopeOpened("scope-1", "order-fetcher", 3_000L, threadFactory, Thread.currentThread());
 * d.recordEffectiveConfiguration("scope-1", scope.toString(), effectiveTimeoutMillis);
 * d.recordFork("scope-1");
 * d.recordJoinOutcome("scope-1", timedOut);
 * d.recordScopeClosed("scope-1");
 * }</pre>
 *
 * @since 1.9.7
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER,
        note = "One immutable-after-open state object per scope id in a ConcurrentHashMap; counters inside it "
             + "are atomics. Scope lifetimes are ordered by a single AtomicLong sequence rather than the wall "
             + "clock, so overlap is decided by program order and never by clock granularity or drift.")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/ScopeConfigurationMisuseDetectorTest.java"
)
public final class ScopeConfigurationMisuseDetector {

    /** Pass as the timeout when the configuration lambda never called {@code withTimeout}. */
    public static final long NO_TIMEOUT = 0L;

    /**
     * Number of subtasks in one deadline-less scope at which the fan-out counts as unbounded.
     *
     * <p>A handful of subtasks with no timeout is a judgement call. Sixteen is a fan-out, and a
     * fan-out with no deadline hangs on its slowest member.
     */
    public static final int DEFAULT_UNBOUNDED_FORK_THRESHOLD = 16;

    private static final class ScopeState {
        final String        scopeId;
        final @Nullable String requestedName;
        final long          requestedTimeoutMillis;
        final int           threadFactoryId;      // 0 = none configured
        final long          openSeq;

        volatile @Nullable String effectiveName;
        volatile long       effectiveTimeoutMillis;
        volatile boolean    effectiveRecorded;
        volatile long       closeSeq = Long.MAX_VALUE;

        final AtomicInteger forks       = new AtomicInteger();
        final AtomicInteger joins       = new AtomicInteger();
        final AtomicInteger joinTimeouts = new AtomicInteger();

        ScopeState(String scopeId, @Nullable String requestedName, long requestedTimeoutMillis,
                   int threadFactoryId, long openSeq) {
            this.scopeId                = scopeId;
            this.requestedName          = requestedName;
            this.requestedTimeoutMillis = requestedTimeoutMillis;
            this.threadFactoryId        = threadFactoryId;
            this.openSeq                = openSeq;
        }

        boolean overlaps(ScopeState other) {
            return openSeq < other.closeSeq && other.openSeq < closeSeq;
        }

        String label() {
            String name = requestedName != null ? requestedName : effectiveName;
            return name != null ? name + " (" + scopeId + ")" : scopeId;
        }
    }

    private final Map<String, ScopeState> scopes    = new ConcurrentHashMap<>();
    private final AtomicLong              sequence  = new AtomicLong();
    private final int                     unboundedForkThreshold;
    private volatile boolean              enabled   = true;

    /** Creates a detector with the default fan-out threshold. */
    public ScopeConfigurationMisuseDetector() {
        this(DEFAULT_UNBOUNDED_FORK_THRESHOLD);
    }

    /**
     * Creates a detector with an explicit fan-out threshold.
     *
     * @param unboundedForkThreshold subtasks in one deadline-less scope at which the fan-out is
     *                               reported; values below 2 are raised to 2, since a single
     *                               subtask is not a fan-out
     */
    public ScopeConfigurationMisuseDetector(int unboundedForkThreshold) {
        this.unboundedForkThreshold = Math.max(unboundedForkThreshold, 2);
    }

    /**
     * Record a scope opening, with what the configuration lambda asked for.
     *
     * @param scopeId                the scope's identifier, unique per open
     * @param requestedName          the name passed to {@code withName}, or {@code null} for none
     * @param requestedTimeoutMillis the timeout passed to {@code withTimeout} in milliseconds, or
     *                               {@link #NO_TIMEOUT} when the lambda set none
     * @param threadFactory          the factory passed to {@code withThreadFactory}, or {@code null}
     * @param owner                  the thread opening the scope
     */
    public void recordScopeOpened(String scopeId, @Nullable String requestedName,
                                  long requestedTimeoutMillis, @Nullable Object threadFactory,
                                  Thread owner) {
        if (!enabled || scopeId == null || owner == null) return;
        int factoryId = threadFactory != null ? System.identityHashCode(threadFactory) : 0;
        scopes.computeIfAbsent(scopeId, k -> new ScopeState(
                scopeId, requestedName, requestedTimeoutMillis, factoryId, sequence.incrementAndGet()));
    }

    /**
     * Record the configuration the scope actually ended up with.
     *
     * <p>This is the half that catches a lambda which built a {@code Configuration} and then threw
     * it away: the requested settings are known from {@link #recordScopeOpened}, and these are the
     * ones the scope reports about itself. Skip the call and no discarded-configuration finding is
     * possible - the other five still are.
     *
     * @param scopeId                the scope's identifier
     * @param effectiveName          the name the scope reports, or {@code null} for none
     * @param effectiveTimeoutMillis the deadline the scope actually enforces, or {@link #NO_TIMEOUT}
     */
    public void recordEffectiveConfiguration(String scopeId, @Nullable String effectiveName,
                                             long effectiveTimeoutMillis) {
        ScopeState s = state(scopeId);
        if (s == null) return;
        s.effectiveName          = effectiveName;
        s.effectiveTimeoutMillis = effectiveTimeoutMillis;
        s.effectiveRecorded      = true;
    }

    /**
     * Record a {@code fork(...)} against a scope.
     *
     * @param scopeId the scope's identifier
     */
    public void recordFork(String scopeId) {
        ScopeState s = state(scopeId);
        if (s != null) s.forks.incrementAndGet();
    }

    /**
     * Record how a {@code join()} ended.
     *
     * @param scopeId  the scope's identifier
     * @param timedOut {@code true} if the configured deadline expired rather than the joiner being
     *                 satisfied
     */
    public void recordJoinOutcome(String scopeId, boolean timedOut) {
        ScopeState s = state(scopeId);
        if (s == null) return;
        s.joins.incrementAndGet();
        if (timedOut) s.joinTimeouts.incrementAndGet();
    }

    /**
     * Record a scope closing, which ends its lifetime for the overlap findings.
     *
     * @param scopeId the scope's identifier
     */
    public void recordScopeClosed(String scopeId) {
        ScopeState s = state(scopeId);
        if (s != null) s.closeSeq = sequence.incrementAndGet();
    }

    private @Nullable ScopeState state(String scopeId) {
        if (!enabled || scopeId == null) return null;
        return scopes.get(scopeId);
    }

    /** Turn recording off; already-recorded state is kept. */
    public void disable() { enabled = false; }

    /** Turn recording back on. */
    public void enable() { enabled = true; }

    /**
     * Analyses the recorded scope configurations and builds the report.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        List<ScopeState> all = new ArrayList<>(scopes.values());
        all.sort((a, b) -> Long.compare(a.openSeq, b.openSeq));

        for (ScopeState s : all) {
            discardedConfiguration(r, s);
            nonPositiveTimeout(r, s);
            unboundedFanOut(r, s, unboundedForkThreshold);
            timeoutIsTheNormalPath(r, s);
        }
        sharedThreadFactory(r, all);
        duplicateNames(r, all);
        return r;
    }

    private static void discardedConfiguration(Report r, ScopeState s) {
        if (!s.effectiveRecorded) return;
        List<String> dropped = new ArrayList<>();
        if (s.requestedTimeoutMillis != s.effectiveTimeoutMillis) {
            dropped.add("timeout " + describeTimeout(s.requestedTimeoutMillis)
                        + " -> " + describeTimeout(s.effectiveTimeoutMillis));
        }
        if (s.requestedName != null && !s.requestedName.equals(s.effectiveName)) {
            dropped.add("name '" + s.requestedName + "' -> "
                        + (s.effectiveName == null ? "none" : "'" + s.effectiveName + "'"));
        }
        if (dropped.isEmpty()) return;

        String msg = String.format(
                "Scope %s was configured with settings it did not end up with: %s. Configuration is immutable "
                + "and every withX returns a new instance, so a lambda that does not return the value it "
                + "derived from its own parameter applies nothing.",
                s.label(), String.join("; ", dropped));
        r.add("ScopeConfigurationMisuse", IssueSeverity.HIGH, msg,
                Map.of("scope", s.scopeId, "issue", "configurationDiscarded",
                       "requestedTimeoutMillis", s.requestedTimeoutMillis,
                       "effectiveTimeoutMillis", s.effectiveTimeoutMillis,
                       "dropped", String.join("; ", dropped)));
    }

    private static void nonPositiveTimeout(Report r, ScopeState s) {
        long timeout = s.effectiveRecorded ? s.effectiveTimeoutMillis : s.requestedTimeoutMillis;
        if (timeout >= NO_TIMEOUT) return;
        String msg = String.format(
                "Scope %s was given a timeout of %d ms. A deadline that is already in the past expires before "
                + "any subtask can finish, so join() always takes the timeout path and the scope never returns "
                + "a real result.",
                s.label(), timeout);
        r.add("ScopeConfigurationMisuse", IssueSeverity.CRITICAL, msg,
                Map.of("scope", s.scopeId, "issue", "nonPositiveTimeout", "timeoutMillis", timeout));
    }

    private static void unboundedFanOut(Report r, ScopeState s, int threshold) {
        long timeout = s.effectiveRecorded ? s.effectiveTimeoutMillis : s.requestedTimeoutMillis;
        int forks = s.forks.get();
        if (timeout != NO_TIMEOUT || forks < threshold) return;
        String msg = String.format(
                "Scope %s forked %d subtasks with no timeout configured. A structured scope does not return "
                + "until every subtask has, so the slowest of the %d decides when the test finishes and one "
                + "that never returns means the test never does either.",
                s.label(), forks, forks);
        r.add("ScopeConfigurationMisuse", IssueSeverity.MEDIUM, msg,
                Map.of("scope", s.scopeId, "issue", "unboundedFanOutWithoutTimeout",
                       "forks", forks, "threshold", threshold));
    }

    private static void timeoutIsTheNormalPath(Report r, ScopeState s) {
        int joins = s.joins.get();
        int timeouts = s.joinTimeouts.get();
        if (joins < 2 || timeouts < joins) return;
        long timeout = s.effectiveRecorded ? s.effectiveTimeoutMillis : s.requestedTimeoutMillis;
        String msg = String.format(
                "Scope %s timed out on all %d of its joins with a %s deadline. The timeout is not a safety net "
                + "here, it is the path every run takes, so what the test asserts on is whatever the fallback "
                + "produced rather than the subtasks' results.",
                s.label(), joins, describeTimeout(timeout));
        r.add("ScopeConfigurationMisuse", IssueSeverity.HIGH, msg,
                Map.of("scope", s.scopeId, "issue", "timeoutIsTheNormalPath",
                       "joins", joins, "joinTimeouts", timeouts, "timeoutMillis", timeout));
    }

    private static void sharedThreadFactory(Report r, List<ScopeState> all) {
        Map<Integer, List<ScopeState>> byFactory = new java.util.LinkedHashMap<>();
        for (ScopeState s : all) {
            if (s.threadFactoryId != 0) {
                byFactory.computeIfAbsent(s.threadFactoryId, k -> new ArrayList<>()).add(s);
            }
        }
        for (Map.Entry<Integer, List<ScopeState>> e : byFactory.entrySet()) {
            List<ScopeState> sharing = e.getValue();
            if (sharing.size() < 2) continue;
            TreeSet<String> overlapping = new TreeSet<>();
            for (int i = 0; i < sharing.size(); i++) {
                for (int j = i + 1; j < sharing.size(); j++) {
                    if (sharing.get(i).overlaps(sharing.get(j))) {
                        overlapping.add(sharing.get(i).label());
                        overlapping.add(sharing.get(j).label());
                    }
                }
            }
            if (overlapping.size() < 2) continue;
            String scopeList = String.join(", ", overlapping);
            String msg = String.format(
                    "One ThreadFactory was configured on %d scopes alive at the same time: %s. Whatever the "
                    + "factory carries per scope - a name counter, a thread group, an uncaught-exception "
                    + "handler - is shared between them, so the threads of one scope are numbered and handled "
                    + "by state the other is also advancing.",
                    overlapping.size(), scopeList);
            r.add("ScopeConfigurationMisuse", IssueSeverity.HIGH, msg,
                    Map.of("issue", "threadFactorySharedByOverlappingScopes",
                           "scopes", scopeList, "scopeCount", overlapping.size()));
        }
    }

    private static void duplicateNames(Report r, List<ScopeState> all) {
        Map<String, List<ScopeState>> byName = new java.util.LinkedHashMap<>();
        for (ScopeState s : all) {
            String name = s.requestedName != null ? s.requestedName : s.effectiveName;
            if (name != null) byName.computeIfAbsent(name, k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<ScopeState>> e : byName.entrySet()) {
            List<ScopeState> sharing = e.getValue();
            if (sharing.size() < 2) continue;
            int overlaps = 0;
            for (int i = 0; i < sharing.size(); i++) {
                for (int j = i + 1; j < sharing.size(); j++) {
                    if (sharing.get(i).overlaps(sharing.get(j))) overlaps++;
                }
            }
            if (overlaps == 0) continue;
            String msg = String.format(
                    "The name '%s' was on %d scopes alive at the same time. Every diagnostic that quotes a "
                    + "scope name - a timeout message, a cancellation trace, this report - then points at more "
                    + "than one scope.",
                    e.getKey(), sharing.size());
            r.add("ScopeConfigurationMisuse", IssueSeverity.LOW, msg,
                    Map.of("issue", "duplicateScopeName", "name", e.getKey(), "scopes", sharing.size()));
        }
    }

    private static String describeTimeout(long millis) {
        return millis == NO_TIMEOUT ? "none" : millis + " ms";
    }

    /** Report produced by {@link #analyze()}. */
    public static final class Report {
        /** Findings as human-readable lines, for the text report. */
        public final List<String> violations = new ArrayList<>();
        /** The same findings as {@link Violation} objects, for machine-readable reports. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        void add(String detector, IssueSeverity severity, String message, Map<String, Object> context) {
            violations.add(message);
            structuredViolations.add(
                    new Violation(detector, severity, message, List.of(), context, Instant.now()));
        }

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SCOPE CONFIGURATION MISUSE - clean";
            StringBuilder sb = new StringBuilder("SCOPE CONFIGURATION MISUSE DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Why: JEP 525 replaced the scope constructors with a UnaryOperator<Configuration>.\n")
              .append("       Configuration is immutable, so every withTimeout/withName/withThreadFactory call\n")
              .append("       returns a new instance and the lambda has to hand that instance back. Dropping it\n")
              .append("       compiles and runs, and the scope quietly has no deadline at all.\n")
              .append("  Fix:\n")
              .append("    - Return the value derived from the lambda's own parameter: cfg -> cfg.withTimeout(d)\n")
              .append("    - Give any scope that forks a fan-out a timeout, so one hung subtask cannot hang the\n")
              .append("      whole scope\n")
              .append("    - Size the timeout above the work's real duration; if every join expires, the fallback\n")
              .append("      is the only path the test ever exercises\n")
              .append("    - Build a ThreadFactory per scope, and give concurrently open scopes distinct names\n");
            return sb.toString();
        }
    }
}
