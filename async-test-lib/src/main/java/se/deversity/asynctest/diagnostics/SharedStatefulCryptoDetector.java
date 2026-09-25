package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * Detects stateful JCA cryptographic primitives — {@link Cipher}, {@link Mac},
 * and {@link Signature} — shared across multiple threads.
 *
 * <p><strong>Why it matters.</strong> Unlike {@link java.security.MessageDigest}
 * (covered by {@link SharedMessageDigestDetector}), these three primitives carry
 * <em>mutable per-operation state</em> across an {@code init → update* → doFinal}
 * (or {@code sign}/{@code verify}) call sequence. None of them is thread-safe.
 * When two threads interleave operations on the same instance without synchronization:
 *
 * <ul>
 *   <li>{@code Cipher} mixes plaintext/ciphertext blocks from different streams,
 *       producing corrupt output or {@code IllegalStateException} ("Cipher not
 *       initialized").</li>
 *   <li>{@code Mac} and {@code Signature} fold bytes from both callers into one
 *       running digest, yielding MACs/signatures that verify for neither input —
 *       silently breaking message integrity and authenticity.</li>
 * </ul>
 *
 * <p>The safe pattern is one instance per thread (a {@code ThreadLocal}), a fresh
 * instance per operation, or an object pool with exclusive checkout.
 *
 * <p>This detector flags any instance accessed by more than one thread during the
 * test, regardless of provider. It is the stateful-primitive sibling of
 * {@link SharedMessageDigestDetector} and {@link SharedSecureRandomDetector}.
 *
 * <p>Synchronization awareness is partial: an access recorded while the accessing thread holds the
 * instance's <em>own</em> monitor counts as guarded, and an instance whose every access was guarded
 * produces no finding. A guard on any other lock counts once the test declares it with
 * {@code AsyncTestContext.holdingLock(...)} or the agent sees it taken. A lock that was never
 * declared is invisible and still fires; treat such a finding as a prompt to verify the
 * synchronization, or to move to a per-thread instance.
 *
 * <p>Usage:
 * <pre>{@code
 * var d = new SharedStatefulCryptoDetector();
 * d.recordAccess(cipher, "payload-cipher", Thread.currentThread());
 * d.recordAccess(mac, "hmac", Thread.currentThread());
 * d.recordAccess(signature, "jwt-signer", Thread.currentThread());
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with double-check (get-then-computeIfAbsent) hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedStatefulCryptoDetectorTest.java"
)
@AISecure(aspect = "cryptography (confidentiality / integrity / authenticity state)")
public final class SharedStatefulCryptoDetector {

    private static final class State extends SelfGuard.ThreadTrackedInstance {
        final String label;
        final String kind;
        final String algorithm;

        State(String label, String kind, String algorithm) {
            this.label = label;
            this.kind = kind;
            this.algorithm = algorithm;
        }
    }

    private final Map<IdentityKey, State> instances = new ConcurrentHashMap<>();

    /**
     * Record an access to a {@link Cipher} instance (init/update/doFinal/wrap/unwrap).
     *
     * @param cipher the Cipher (null-safe)
     * @param name   descriptive label for reports (may be {@code null})
     * @param thread accessing thread
     */
    public void recordAccess(Cipher cipher, String name, Thread thread) {
        if (cipher == null) return;
        record(cipher, name, "Cipher",
                cipher.getClass(), safeString(cipher::getAlgorithm), thread);
    }

    /**
     * Record an access to a {@link Mac} instance (init/update/doFinal).
     *
     * @param mac    the Mac (null-safe)
     * @param name   descriptive label for reports (may be {@code null})
     * @param thread accessing thread
     */
    public void recordAccess(Mac mac, String name, Thread thread) {
        if (mac == null) return;
        record(mac, name, "Mac",
                mac.getClass(), safeString(mac::getAlgorithm), thread);
    }

    /**
     * Record an access to a {@link Signature} instance (initSign/initVerify/update/sign/verify).
     *
     * @param signature the Signature (null-safe)
     * @param name      descriptive label for reports (may be {@code null})
     * @param thread    accessing thread
     */
    public void recordAccess(Signature signature, String name, Thread thread) {
        if (signature == null) return;
        record(signature, name, "Signature",
                signature.getClass(), safeString(signature::getAlgorithm), thread);
    }

    private void record(Object instance, String name, String kind, Class<?> type,
                        String algorithm, Thread thread) {
        if (thread == null) return;
        IdentityKey key = new IdentityKey(instance);
        State s = instances.get(key);
        if (s == null) {
            // Cold path — first observation of this instance.
            final String label = (name != null)
                    ? name : type.getSimpleName() + "@" + key.hashCode();
            s = instances.computeIfAbsent(key, k -> new State(label, kind, algorithm));
        }
        s.noteAccess(instance, thread);
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the findings this detector collected during the run
     */
    public Report analyze() {
        Report r = new Report();
        for (State s : instances.values()) {
            if (!s.sharedAndUnguarded()) continue;
            String msg = String.format(
                    "%s '%s' (algorithm=%s) accessed from %d threads (%s) — %s is stateful "
                            + "and not thread-safe; unsynchronized concurrent init/update/doFinal interleaving "
                            + "corrupts output or breaks integrity"
                            + SelfGuard.REPORT_NOTE + ".",
                    s.kind,
                    s.label,
                    s.algorithm,
                    s.threadCount(),
                    String.join(", ", s.threadNames()),
                    s.kind);
            r.violations.add(msg);
            r.structuredViolations.add(new Violation(
                    "SharedStatefulCrypto",
                    IssueSeverity.HIGH,
                    msg,
                    List.of(),
                    Map.of(
                            "label", s.label,
                            "kind", s.kind,
                            "algorithm", s.algorithm,
                            "threadCount", s.threadCount()),
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
            if (violations.isEmpty()) return "SHARED STATEFUL CRYPTO — clean";
            StringBuilder sb = new StringBuilder("SHARED STATEFUL CRYPTO DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append('\n');
            sb.append("  Fix:\n")
              .append("    - Use a ThreadLocal<Cipher/Mac/Signature> so each thread owns its instance.\n")
              .append("    - Or construct a fresh instance per operation (getInstance is cheap relative to the bug).\n")
              .append("    - Or guard the full init→doFinal sequence with exclusive checkout from a pool.\n");
            return sb.toString();
        }
    }
}
