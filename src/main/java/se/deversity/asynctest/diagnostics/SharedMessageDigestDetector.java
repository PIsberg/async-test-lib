package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import se.deversity.asynctest.report.Violation;
import se.deversity.vibetags.annotations.AISecure;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

/**
 * Detects {@link java.security.MessageDigest} instances shared across multiple threads.
 *
 * <p>{@code MessageDigest} is <strong>not thread-safe</strong>. Its internal digest state
 * (the running hash buffer, byte count, and padding) is mutated by every {@code update()}
 * and {@code digest()} call. Concurrent access from multiple threads silently corrupts
 * the hash, producing wrong digests without any exception — one of the harder bugs to
 * diagnose in production.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.sharedMessageDigestDetector();
 * d.recordAccess(sharedDigest, "sha256", Thread.currentThread());
 * sharedDigest.update(data);
 * }</pre>
 *
 * @since 0.9.0
 */
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/SharedMessageDigestDetectorTest.java"
)
@AISecure(aspect = "cryptography (hash integrity / MAC / signature state)")
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-instance state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
public class SharedMessageDigestDetector {

    private static class DigestState {
        final String      name;
        final String      type;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();
        final Set<SiteCapture.Site> accessSites = ConcurrentHashMap.newKeySet();

        DigestState(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private final Map<Integer, DigestState> digests = new ConcurrentHashMap<>();

    /**
     * Record an access (update/digest/reset/clone/encrypt/decrypt/sign/verify) to a MessageDigest or cryptographic instance.
     *
     * @param digest the JCA instance being accessed (null-safe)
     * @param name   descriptive label for reports (e.g. "sha256", "aes-cbc")
     * @param thread the accessing thread
     */
    public void recordAccess(Object digest, String name, Thread thread) {
        if (digest == null || thread == null) return;

        // Hot path: lookup-only. The vast majority of calls hit an instance the
        // detector has already seen at least once, so we avoid all classification
        // work (instanceof chain, label string construction, lambda allocation)
        // until we know the entry is missing.
        int id = System.identityHashCode(digest);
        DigestState s = digests.get(id);
        if (s == null) {
            // Cold path: first encounter of this instance. computeIfAbsent
            // guarantees the factory runs at most once even under contention.
            s = digests.computeIfAbsent(id, k -> {
                String label = (name != null)
                        ? name
                        : digest.getClass().getSimpleName() + "@" + k;
                String type;
                if (digest instanceof javax.crypto.Cipher) {
                    type = "Cipher";
                } else if (digest instanceof javax.crypto.Mac) {
                    type = "Mac";
                } else if (digest instanceof java.security.Signature) {
                    type = "Signature";
                } else {
                    type = "MessageDigest";
                }
                return new DigestState(label, type);
            });
        }
        s.accessingThreadIds.add(thread.getId());
        s.accessingThreadNames.add(thread.getName());
        // Capture the user-code site once per distinct call site. The Set's hashing
        // gives us per-(class, line) dedupe so a tight loop doesn't accumulate frames.
        SiteCapture.capture().ifPresent(s.accessSites::add);
    }

    /** {@return report of JCA instances accessed from multiple threads} */
    public SharedMessageDigestReport analyze() {
        SharedMessageDigestReport r = new SharedMessageDigestReport();
        for (DigestState s : digests.values()) {
            if (s.accessingThreadIds.size() > 1) {
                r.violatedTypes.add(s.type);
                String msg;
                if ("Cipher".equals(s.type)) {
                    msg = String.format(
                            "'%s' accessed from %d threads (%s) — Cipher is not thread-safe; "
                                    + "concurrent encrypt/decrypt updates silently corrupt the block cipher states (e.g. IV, chaining blocks)",
                            s.name, s.accessingThreadIds.size(),
                            String.join(", ", s.accessingThreadNames));
                } else if ("Mac".equals(s.type)) {
                    msg = String.format(
                            "'%s' accessed from %d threads (%s) — Mac is not thread-safe; "
                                    + "concurrent update()/doFinal() calls silently corrupt the running MAC byte calculations",
                            s.name, s.accessingThreadIds.size(),
                            String.join(", ", s.accessingThreadNames));
                } else if ("Signature".equals(s.type)) {
                    msg = String.format(
                            "'%s' accessed from %d threads (%s) — Signature is not thread-safe; "
                                    + "concurrent update()/sign()/verify() calls silently corrupt stateful signing or verification operations",
                            s.name, s.accessingThreadIds.size(),
                            String.join(", ", s.accessingThreadNames));
                } else {
                    msg = String.format(
                            "'%s' accessed from %d threads (%s) — MessageDigest is not thread-safe; "
                                    + "concurrent update()/digest() calls silently corrupt the hash state",
                            s.name, s.accessingThreadIds.size(),
                            String.join(", ", s.accessingThreadNames));
                }
                // Append source-line attribution if we captured at least one user-code frame.
                // The set is already deduped by (class, line); a tight loop on one site
                // contributes a single entry, multiple distinct sites all show.
                if (!s.accessSites.isEmpty()) {
                    StringBuilder sites = new StringBuilder("\n    Access sites:");
                    for (SiteCapture.Site site : s.accessSites) {
                        sites.append("\n      - ").append(site.render());
                    }
                    msg = msg + sites;
                }
                r.violations.add(msg);

                // Mirror as a structured Violation for downstream formatters.
                Map<String, Object> attrs = Map.of(
                        "type", s.type,
                        "threads", s.accessingThreadIds.size(),
                        "threadNames", String.join(",", s.accessingThreadNames),
                        "name", s.name);
                r.structuredViolations.add(new Violation(
                        "SharedMessageDigest",
                        IssueSeverity.HIGH,
                        msg,
                        new ArrayList<>(s.accessSites),
                        attrs,
                        java.time.Instant.now()));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedMessageDigestReport {
        public final List<String> violations = new ArrayList<>();
        public final Set<String> violatedTypes = new LinkedHashSet<>();
        /** Structured mirror of {@link #violations} for {@link se.deversity.asynctest.report.Formatter}s. */
        public final List<Violation> structuredViolations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED MESSAGE DIGEST / CRYPTOGRAPHY DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            
            sb.append("  Why & Fix Guide:\n");
            for (String type : violatedTypes) {
                if ("MessageDigest".equals(type)) {
                    sb.append("  [MessageDigest]\n")
                      .append("    Why: MessageDigest accumulates bytes in an internal buffer as you call update(). Concurrent calls\n")
                      .append("         mix bytes from different threads into the same digest, producing a hash of interleaved data\n")
                      .append("         rather than the intended input — a silent data-integrity failure.\n")
                      .append("    Fix:\n")
                      .append("      - Create a new MessageDigest.getInstance(\"SHA-256\") per call or per thread (cheap, non-blocking)\n")
                      .append("      - Or use ThreadLocal<MessageDigest> and call reset() at the start of each use\n")
                      .append("      - MessageDigest.clone() works if a pre-configured instance must be reused (requires the algorithm to support cloning)\n");
                } else if ("Cipher".equals(type)) {
                    sb.append("  [Cipher]\n")
                      .append("    Why: Cipher maintains stateful block-mode execution context (including IVs, intermediate feedback blocks,\n")
                      .append("         and partial buffers). Sharing a Cipher across threads corrupts these states, leading to decryption failures\n")
                      .append("         (BadPaddingException) or, worse, silent output corruption during encryption.\n")
                      .append("    Fix:\n")
                      .append("      - Instantiate a new Cipher instance via Cipher.getInstance(transformation) dynamically for each encryption/decryption task.\n")
                      .append("      - Or use a ThreadLocal<Cipher> to safely isolate instances to separate threads.\n");
                } else if ("Mac".equals(type)) {
                    sb.append("  [Mac]\n")
                      .append("    Why: Mac is stateful and computes a running message authentication code across multiple update() updates.\n")
                      .append("         Concurrent access interleaves input bytes from different threads, producing corrupted or invalid MAC tags.\n")
                      .append("    Fix:\n")
                      .append("      - Create a new Mac instance dynamically per validation or signature generation.\n")
                      .append("      - Or thread-confine Mac instances using a ThreadLocal.\n");
                } else if ("Signature".equals(type)) {
                    sb.append("  [Signature]\n")
                      .append("    Why: Signature tracks sequential cryptographic signing or verification state. Simultaneous thread updates\n")
                      .append("         scramble the message buffer, producing invalid digital signatures or incorrect verification outcomes.\n")
                      .append("    Fix:\n")
                      .append("      - Obtain a local Signature instance via Signature.getInstance(algorithm) on demand.\n")
                      .append("      - Or confine Signature instances to ThreadLocal structures.\n");
                }
            }
            return sb.toString();
        }
    }
}
