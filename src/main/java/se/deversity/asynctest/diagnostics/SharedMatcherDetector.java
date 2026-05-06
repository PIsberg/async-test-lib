package se.deversity.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.*;

/**
 * Detects {@link java.util.regex.Matcher} instances shared across multiple threads.
 *
 * <p>{@link java.util.regex.Pattern} is thread-safe, but {@link java.util.regex.Matcher}
 * is not — it holds per-match state (position, groups, last-append offset). Concurrent
 * use of the same {@code Matcher} instance produces incorrect matches or
 * {@link java.lang.StringIndexOutOfBoundsException}.
 *
 * <p>Usage inside {@code @AsyncTest}:
 * <pre>{@code
 * var d = AsyncTestContext.sharedMatcherDetector();
 * d.recordAccess(sharedMatcher, "emailMatcher", Thread.currentThread());
 * }</pre>
 *
 * @since 0.9.0
 */
public class SharedMatcherDetector {

    private static class MatcherState {
        final String      name;
        final Set<Long>   accessingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> accessingThreadNames = ConcurrentHashMap.newKeySet();

        MatcherState(String name) { this.name = name; }
    }

    private final Map<Integer, MatcherState> matchers = new ConcurrentHashMap<>();

    /**
     * Record an access (find/matches/group/reset) to a Matcher instance.
     *
     * @param matcher the Matcher being accessed (null-safe)
     * @param name    descriptive label for reports
     * @param thread  the accessing thread
     */
    public void recordAccess(Object matcher, String name, Thread thread) {
        if (matcher == null || thread == null) return;
        String label = name != null ? name
                : matcher.getClass().getSimpleName() + "@" + System.identityHashCode(matcher);
        MatcherState s = matchers.computeIfAbsent(
                System.identityHashCode(matcher), id -> new MatcherState(label));
        s.accessingThreadIds.add(thread.getId());
        s.accessingThreadNames.add(thread.getName());
    }

    /** @return report of Matchers accessed from multiple threads */
    public SharedMatcherReport analyze() {
        SharedMatcherReport r = new SharedMatcherReport();
        for (MatcherState s : matchers.values()) {
            if (s.accessingThreadIds.size() > 1) {
                r.violations.add(String.format(
                        "'%s' accessed from %d threads (%s) — Matcher is not thread-safe; "
                                + "Pattern is safe but each Matcher holds mutable match state",
                        s.name, s.accessingThreadIds.size(),
                        String.join(", ", s.accessingThreadNames)));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedMatcherReport {
        final List<String> violations = new ArrayList<>();

        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED REGEX MATCHER DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("  Fix: call pattern.matcher(input) inside each thread rather than "
                    + "sharing a single Matcher instance; Pattern.compile() results are safe to share");
            return sb.toString();
        }
    }
}
