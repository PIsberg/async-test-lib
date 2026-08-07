package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects {@link java.util.regex.Matcher} instances shared across multiple threads.
 *
 * <p>{@link java.util.regex.Pattern} is thread-safe, but {@link java.util.regex.Matcher}
 * is not — it holds per-match state (position, groups, last-append offset). Unsynchronized concurrent
 * use of the same {@code Matcher} instance produces incorrect matches or
 * {@link java.lang.StringIndexOutOfBoundsException}.
 *
 * <p>The detector observes sharing — which threads touched the instance — not
 * locks: a shared matcher guarded by correct external synchronization is
 * flagged all the same. Treat a finding as a prompt to verify that
 * synchronization exists, or to obtain a fresh matcher per thread from the
 * shared {@code Pattern}.
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
        s.accessingThreadIds.add(thread.threadId());
        s.accessingThreadNames.add(thread.getName());
    }

    /**
     * {@return report of Matchers accessed from multiple threads}
     */
    public SharedMatcherReport analyze() {
        SharedMatcherReport r = new SharedMatcherReport();
        for (MatcherState s : matchers.values()) {
            if (s.accessingThreadIds.size() > 1) {
                r.violations.add(String.format(
                        "'%s' accessed from %d threads (%s) — Matcher is not thread-safe; "
                                + "Pattern is safe but each Matcher holds mutable match state"
                                + " (the detector observes sharing, not locks — verify external"
                                + " synchronization or use a per-thread instance)",
                        s.name, s.accessingThreadIds.size(),
                        String.join(", ", s.accessingThreadNames)));
            }
        }
        return r;
    }

    /** Report produced by {@link #analyze()}. */
    public static class SharedMatcherReport {
        final List<String> violations = new ArrayList<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() { return !violations.isEmpty(); }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("SHARED REGEX MATCHER DETECTED:\n");
            for (String v : violations) sb.append("  - ").append(v).append("\n");
            sb.append("""
  Why: A Matcher holds the current match position and region as mutable internal state.
       Concurrent find()/group() calls on the same Matcher corrupt that state, producing wrong
       match results, missed matches, or ArrayIndexOutOfBoundsException inside the regex engine.
  Fix:
    - Call pattern.matcher(input) inside each thread to obtain a fresh, independent Matcher
    - Pattern.compile() is safe to share — it is immutable after construction
    - For Java 11+: Pattern.asMatchPredicate() or Pattern.asPredicate() also thread-safe\
""");
            return sb.toString();
        }
    }
}
