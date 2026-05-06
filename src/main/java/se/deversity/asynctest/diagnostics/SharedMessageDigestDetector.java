package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;

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
public class SharedMessageDigestDetector {

    private static class DigestState {
        final String      name;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        DigestState(String name) { this.name = name; }
    }

    private final Map<Integer, DigestState> digests = new ConcurrentHashMap<>();

    /**
     * Record an access (update/digest/reset/clone) to a MessageDigest instance.
     *
     * @param digest the MessageDigest being accessed (null-safe)
     * @param name   descriptive label for reports (e.g. "sha256", "md5")
     * @param thread the accessing thread
     */
    public void recordAccess(Object digest, String name, Thread thread) {
        if (digest == null || thread == null) return;
        String label = name != null ? name
                : digest.getClass().getSimpleName() + "@" + System.identityHashCode(digest);
        DigestState s = digests.computeIfAbsent(
                System.identityHashCode(digest), id -> new DigestState(label));
        s.accessingThreadIds.add(thread.getId());
        s.accessingThreadNames.add(thread.getName());
    }

    /** @return report of MessageDigest instances accessed from multiple threads */
    public SharedMessageDigestReport analyze() {
        SharedMessageDigestReport r = new SharedMessageDigestReport();
        for (DigestState s : digests.values()) {
            if (s.accessingThreadIds.size() > 1) {
                r.violations.add(String.format(
                        "'%s' accessed from %d threads (%s) — MessageDigest is not thread-safe; "
                                + "concurrent update()/digest() calls silently corrupt the hash state",
                        s.name, s.accessingThreadIds.size(),
                        String.join(", ", s.accessingThreadNames)));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedMessageDigestReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED MESSAGE DIGEST DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: obtain a new MessageDigest.getInstance(...) per thread or "
                    + "use ThreadLocal<MessageDigest>; alternatively use MessageDigest.clone() "
                    + "to snapshot a configured instance per call");
            return sb.toString();
        }
    }
}
