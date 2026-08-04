package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link SecureRandom} instances accessed from multiple threads.
 *
 * <p><strong>Why it matters.</strong> {@link SecureRandom} documents its instances as
 * safe for use by multiple concurrent threads, and the JDK providers honor that:
 * {@code SHA1PRNG} serializes via internal synchronization, and {@code NativePRNG} on
 * Linux additionally blocks all callers on a shared {@code /dev/urandom} file handle.
 * A shared instance is therefore the documented-safe idiom, and what it costs is
 * contention, because every caller queues on the same internal lock. The residual
 * correctness risk is provider-dependent: a non-JDK SPI implementation that skips
 * synchronization can produce biased or duplicate output under concurrent access.
 *
 * <p>To avoid the contention (or any non-JDK provider risk) use one of:
 * <ul>
 *   <li>{@code ThreadLocal<SecureRandom>} — each thread gets its own instance.</li>
 *   <li>Use {@code SecureRandom.getInstanceStrong()} per call (slow but always safe).</li>
 *   <li>Use {@link java.util.concurrent.ThreadLocalRandom} if you don't need
 *       cryptographic-grade randomness.</li>
 * </ul>
 *
 * <p>This detector reports any {@code SecureRandom} accessed by more than one thread,
 * regardless of provider, as a medium-severity observation: on JDK providers it is a
 * contention note rather than a bug, and a defect signal only when the provider is a
 * custom SPI that does not synchronize. Distinct from
 * {@link SharedRandomDetector} which covers {@code java.util.Random}.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new SharedSecureRandomDetector();
 * d.recordAccess(secureRandom, "session-id-source", Thread.currentThread());
 * }</pre>
 *
 * @since 1.6.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with double-check (get-then-computeIfAbsent) hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedSecureRandomDetectorTest.java"
)
@AISecure(aspect = "cryptography (RNG quality)")
public final class SharedSecureRandomDetector {

    private static final class State {
        final String label;
        final String algorithm;
        final String provider;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        State(String label, String algorithm, String provider) {
            this.label = label;
            this.algorithm = algorithm;
            this.provider = provider;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access (nextBytes/nextInt/nextLong/setSeed/etc.) to a
     * {@link SecureRandom} instance.
     *
     * @param random the SecureRandom (null-safe)
     * @param name   descriptive label for reports (may be {@code null})
     * @param thread accessing thread
     */
    public void recordAccess(SecureRandom random, String name, Thread thread) {
        if (random == null || thread == null) return;
        int id = System.identityHashCode(random);
        State s = instances.get(id);
        if (s == null) {
            // Cold path — first observation of this instance.
            s = instances.computeIfAbsent(id, k -> {
                String label = (name != null)
                        ? name
                        : random.getClass().getSimpleName() + "@" + k;
                String algorithm = safeString(random::getAlgorithm);
                String provider  = safeString(() -> random.getProvider() != null
                        ? random.getProvider().getName() : "unknown");
                return new State(label, algorithm, provider);
            });
        }
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
            String msg = String.format(
                    "'%s' (algorithm=%s, provider=%s) accessed from %d threads (%s) — "
                            + "SecureRandom thread safety is provider-dependent: JDK providers "
                            + "synchronize internally (documented-safe, callers serialize on one "
                            + "lock), while a non-JDK SPI that skips synchronization can produce "
                            + "biased or duplicate output. Shared use is a contention cost first, "
                            + "a correctness risk only off the JDK providers.",
                    s.label,
                    s.algorithm,
                    s.provider,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedSecureRandom",
                    IssueSeverity.MEDIUM,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "algorithm", s.algorithm,
                            "provider", s.provider,
                            "threadCount", s.accessingThreadIds.size()),
                    Instant.now()));
        }
        return r;
    }

    private static String safeString(java.util.concurrent.Callable<String> c) {
        try { return c.call(); } catch (Exception e) { return "unknown"; }
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
            if (violations.isEmpty()) return "SHARED SECURE RANDOM — clean";
            StringBuilder sb = new StringBuilder("SHARED SECURE RANDOM DETECTED:\n");
            // The explicit marker below is what IssueSeverity.fromReport reads; without
            // it an untagged report defaults to HIGH and the failOn gate treats the
            // documented-safe shared-SecureRandom idiom as a build-breaking finding.
            sb.append("  Severity: MEDIUM — documented-safe on JDK providers; a contention and\n")
              .append("  provider-portability observation, not corruption.\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use ThreadLocal<SecureRandom> to give each thread its own instance.\n")
              .append("    - Or call SecureRandom.getInstanceStrong() once per consumer call (safe but slow).\n")
              .append("    - For non-cryptographic use, prefer ThreadLocalRandom.current().\n");
            return sb.toString();
        }
    }
}
