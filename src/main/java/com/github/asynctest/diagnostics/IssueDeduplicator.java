package com.github.asynctest.diagnostics;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Deduplicates similar concurrency issues to prevent report flooding.
 *
 * <p>When multiple threads hit the same issue (e.g., race condition on the same field),
 * this class groups them together and reports once with a count of affected threads.
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * IssueDeduplicator<RaceConditionEvent> dedup = new IssueDeduplicator<>();
 *
 * // Record events from multiple threads
 * dedup.record(new RaceConditionEvent("balance", 23, threadId1));
 * dedup.record(new RaceConditionEvent("balance", 23, threadId2));
 * dedup.record(new RaceConditionEvent("balance", 23, threadId3));
 *
 * // Get deduplicated groups
 * List<IssueGroup<RaceConditionEvent>> groups = dedup.getGroups();
 * // Returns 1 group with count=3 instead of 3 separate reports
 * }</pre>
 *
 * @param <T> the event type
 * @since 1.3.0
 */
public class IssueDeduplicator<T extends DeduplicatableEvent> {

    /**
     * Groups of similar issues, keyed by their fingerprint.
     */
    private final Map<String, IssueGroup<T>> groups = new ConcurrentHashMap<>();

    /**
     * Total number of issues recorded (including duplicates).
     */
    private final AtomicInteger totalCount = new AtomicInteger(0);

    /**
     * Maximum number of similar issues to show in detail before summarizing.
     */
    private static final int MAX_DETAILED_THREADS = 5;

    /**
     * Record an issue event.
     *
     * @param event the issue event to record
     */
    public void record(T event) {
        String fingerprint = event.getFingerprint();
        groups.computeIfAbsent(fingerprint, k -> new IssueGroup<>(fingerprint))
              .addEvent(event);
        totalCount.incrementAndGet();
    }

    /**
     * Get all deduplicated issue groups.
     *
     * @return list of issue groups, sorted by occurrence count (descending)
     */
    public List<IssueGroup<T>> getGroups() {
        return groups.values().stream()
                .sorted((a, b) -> Integer.compare(b.getCount(), a.getCount()))
                .collect(Collectors.toList());
    }

    /**
     * Get the total number of issues recorded.
     *
     * @return total count including duplicates
     */
    public int getTotalCount() {
        return totalCount.get();
    }

    /**
     * Get the number of unique issue groups.
     *
     * @return number of distinct issues
     */
    public int getUniqueCount() {
        return groups.size();
    }

    /**
     * Check if any issues were recorded.
     *
     * @return true if at least one issue was recorded
     */
    public boolean hasIssues() {
        return !groups.isEmpty();
    }

    /**
     * Clear all recorded issues.
     */
    public void clear() {
        groups.clear();
        totalCount.set(0);
    }

    /**
     * Format a summary of deduplicated issues.
     *
     * @param issueName the name of the issue type (e.g., "Race Condition")
     * @return formatted summary string
     */
    public String formatSummary(String issueName) {
        if (!hasIssues()) {
            return issueName + ": No issues detected";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(issueName).append(": ")
          .append(getUniqueCount())
          .append(" unique issue(s) found (")
          .append(getTotalCount())
          .append(" total occurrences)\n");

        for (IssueGroup<T> group : getGroups()) {
            sb.append("\n").append(group.formatDetailed());
        }

        return sb.toString();
    }

    /**
     * A group of similar issues.
     *
     * @param <T> the event type
     */
    public static class IssueGroup<T extends DeduplicatableEvent> {
        private final String fingerprint;
        private final List<T> events = Collections.synchronizedList(new ArrayList<>());
        private final Set<Long> threadIds = ConcurrentHashMap.newKeySet();

        IssueGroup(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        /**
         * Add an event to this group.
         *
         * @param event the event to add
         */
        void addEvent(T event) {
            events.add(event);
            threadIds.add(event.getThreadId());
        }

        /**
         * @return the fingerprint identifying this group
         */
        public String getFingerprint() {
            return fingerprint;
        }

        /**
         * @return number of occurrences in this group
         */
        public int getCount() {
            return events.size();
        }

        /**
         * @return number of unique threads affected
         */
        public int getAffectedThreadCount() {
            return threadIds.size();
        }

        /**
         * @return set of affected thread IDs
         */
        public Set<Long> getAffectedThreadIds() {
            return Collections.unmodifiableSet(threadIds);
        }

        /**
         * @return the first event in this group (representative)
         */
        public T getFirstEvent() {
            return events.isEmpty() ? null : events.get(0);
        }

        /**
         * @return all events in this group
         */
        public List<T> getEvents() {
            return Collections.unmodifiableList(events);
        }

        /**
         * Format this group for detailed output.
         *
         * @return formatted string with issue details and thread summary
         */
        public String formatDetailed() {
            StringBuilder sb = new StringBuilder();

            T firstEvent = getFirstEvent();
            if (firstEvent != null) {
                sb.append("  Location: ").append(firstEvent.getLocation())
                  .append(" (line ").append(firstEvent.getLineNumber()).append(")\n");
            }

            sb.append("  Occurrences: ").append(getCount());

            if (getAffectedThreadCount() > 1) {
                sb.append(" (affected threads: ");

                List<Long> sortedThreadIds = getAffectedThreadIds().stream()
                        .sorted()
                        .collect(Collectors.toList());

                if (sortedThreadIds.size() <= MAX_DETAILED_THREADS) {
                    sb.append(sortedThreadIds.stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(", ")));
                } else {
                    // Show first few and summarize the rest
                    List<Long> shown = sortedThreadIds.stream()
                            .limit(MAX_DETAILED_THREADS)
                            .collect(Collectors.toList());
                    int suppressed = sortedThreadIds.size() - MAX_DETAILED_THREADS;

                    sb.append(shown.stream()
                            .map(String::valueOf)
                            .collect(Collectors.joining(", ")))
                      .append(", ... (")
                      .append(suppressed)
                      .append(" more)");
                }

                sb.append(")");
            }

            if (getCount() > 1) {
                sb.append(" ⚠️ ").append(getCount() - 1)
                  .append(" similar issue(s) suppressed");
            }

            return sb.toString();
        }

        /**
         * Format this group for brief output (one-liner).
         *
         * @return brief formatted string
         */
        public String formatBrief() {
            T firstEvent = getFirstEvent();
            if (firstEvent == null) {
                return "";
            }

            return String.format("%s at %s:%d (%d occurrences, %d threads)",
                    firstEvent.getType(),
                    firstEvent.getLocation(),
                    firstEvent.getLineNumber(),
                    getCount(),
                    getAffectedThreadCount());
        }
    }
}
