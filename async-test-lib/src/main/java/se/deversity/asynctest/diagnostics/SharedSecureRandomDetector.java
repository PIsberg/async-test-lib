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
 * <p><strong>Why it matters.</strong> Most JDK {@code SecureRandom}
 * implementations are <em>not</em> guaranteed to be thread-safe. The default
 * {@code SHA1PRNG} provider serializes via internal synchronization (so it
 * works correctly but at significant contention cost), while
 * {@code NativePRNG} on Linux is internally synchronized too but blocks all
 * callers on a shared {@code /dev/urandom} file handle. Other providers
 * (Bouncy Castle, custom SPI implementations) may not synchronize at all,
 * producing biased, predictable, or duplicate "random" output under
 * concurrent access — a security bug.
 *
 * <p>The safe pattern is one of:
 * <ul>
 *   <li>{@code ThreadLocal<SecureRandom>} — each thread gets its own instance.</li>
 *   <li>Use {@code SecureRandom.getInstanceStrong()} per call (slow but always safe).</li>
 *   <li>Use {@link java.util.concurrent.ThreadLocalRandom} if you don't need
 *       cryptographic-grade randomness.</li>
 * </ul>
 *
 * <p>This detector flags any {@code SecureRandom} accessed by more than one
 * thread during the test, regardless of which provider it uses. Distinct from
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

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
            String msg = String.format(
                    "'%s' (algorithm=%s, provider=%s) accessed from %d threads (%s) — "
                            + "SecureRandom is provider-dependent for thread safety; concurrent "
                            + "access can produce biased, duplicate, or predictable output.",
                    s.label,
                    s.algorithm,
                    s.provider,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedSecureRandom",
                    IssueSeverity.HIGH,
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
        /** The violations. */
        public final List<String> violations = new ArrayList<>();
        /** The structured violations. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** {@return whether there are issues} */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            if (violations.isEmpty()) return "SHARED SECURE RANDOM — clean";
            StringBuilder sb = new StringBuilder("SHARED SECURE RANDOM DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use ThreadLocal<SecureRandom> to give each thread its own instance.\n")
              .append("    - Or call SecureRandom.getInstanceStrong() once per consumer call (safe but slow).\n")
              .append("    - For non-cryptographic use, prefer ThreadLocalRandom.current().\n");
            return sb.toString();
        }
    }
}
