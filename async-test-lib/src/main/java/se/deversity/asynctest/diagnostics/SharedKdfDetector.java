package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@code javax.crypto.KDF} (Key Derivation Function, JEP 510 — final in
 * JDK 25) instances shared across threads.
 *
 * <p><strong>Why it matters.</strong> The {@code KDF} javadoc states: "Unless
 * otherwise documented by an implementation, the methods defined in this class
 * are not thread-safe. Multiple threads that need to access a single object
 * concurrently should synchronize amongst themselves." A KDF derivation
 * ({@code deriveKey}/{@code deriveData}) threads algorithm parameters and
 * provider state through the underlying SPI; concurrent derivations on one
 * instance can interleave that state and produce wrong keys — a silent
 * cryptographic-integrity failure (the derived key simply doesn't match what the
 * peer derives) with no exception at the point of corruption.
 *
 * <p>The safe pattern is one {@code KDF} instance per thread (KDF construction
 * via {@code KDF.getInstance(...)} is cheap), or full external synchronization
 * around every {@code deriveKey}/{@code deriveData} call on a shared instance.
 *
 * <p>The parameter type is {@link Object} rather than {@code javax.crypto.KDF}
 * because the library targets Java 21 and {@code KDF} only exists in JDK 24+;
 * pass the KDF instance directly.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = AsyncTestContext.sharedKdfDetector();
 * d.recordAccess(kdf, "HKDF-SHA256", "deriveKey", Thread.currentThread());
 * SecretKey key = kdf.deriveKey("AES", params);
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedKdfDetectorTest.java"
)
public final class SharedKdfDetector {

    private static final class State {
        final String label;
        final String algorithm;
        final Set<String> operations           = ConcurrentHashMap.newKeySet();
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        State(String label, String algorithm) {
            this.label = label;
            this.algorithm = algorithm;
        }
    }

    private final Map<Integer, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access to a KDF instance.
     *
     * @param kdf       the {@code javax.crypto.KDF} instance (typed {@link Object}
     *                  because the library targets Java 21; null-safe)
     * @param algorithm the KDF algorithm, e.g. {@code "HKDF-SHA256"} (may be {@code null})
     * @param operation descriptive operation label, e.g. {@code "deriveKey"},
     *                  {@code "deriveData"} (may be {@code null})
     * @param thread    accessing thread
     */
    public void recordAccess(Object kdf, String algorithm, String operation, Thread thread) {
        if (kdf == null || thread == null) return;
        int id = System.identityHashCode(kdf);
        State s = instances.get(id);
        if (s == null) {
            final String label = kdf.getClass().getSimpleName() + "@" + id;
            final String algo = algorithm != null ? algorithm : "unknown";
            s = instances.computeIfAbsent(id, k -> new State(label, algo));
        }
        if (operation != null) s.operations.add(operation);
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
    }

    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (s.accessingThreadIds.size() <= 1) continue;
            String msg = String.format(
                    "KDF '%s' (algorithm %s) accessed from %d threads (%s) via %s — "
                            + "javax.crypto.KDF is documented as not thread-safe unless the "
                            + "provider says otherwise; concurrent deriveKey()/deriveData() "
                            + "calls can interleave provider state and silently derive wrong "
                            + "keys that fail to match the peer's.",
                    s.label,
                    s.algorithm,
                    s.accessingThreadIds.size(),
                    String.join(", ", s.accessingThreadNames),
                    String.join(", ", s.operations));
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedKdf",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "algorithm", s.algorithm,
                            "threadCount", s.accessingThreadIds.size()),
                    Instant.now()));
        }
        return r;
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
            if (violations.isEmpty()) return "SHARED KDF — clean";
            StringBuilder sb = new StringBuilder("SHARED KDF DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use one KDF instance per thread — KDF.getInstance(...) is cheap; "
                      + "a ThreadLocal works well.\n")
              .append("    - Or synchronize every deriveKey()/deriveData() call on the shared "
                      + "instance externally.\n")
              .append("    - Only share freely if the provider explicitly documents its KDF "
                      + "implementation as thread-safe.\n");
            return sb.toString();
        }
    }
}
