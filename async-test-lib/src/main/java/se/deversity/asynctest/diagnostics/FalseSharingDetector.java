package se.deversity.asynctest.diagnostics;

import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.report.Violation;
import java.time.Instant;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Heuristic for False Sharing - multiple threads accessing adjacent memory locations
 * that fall within the same CPU cache line (typically 64 bytes).
 *
 * <p><strong>Experimental, findings off by default.</strong> Cache-line effects are not
 * observable from pure Java: this detector estimates field offsets by summing nominal
 * type sizes in declaration order, while the JVM reorders fields, compresses references,
 * and honors {@code @Contended} padding, so the estimated offsets do not correspond to
 * real memory layout. Keying is per class rather than per object, so thread-confined
 * instances of one class are indistinguishable from a genuinely shared instance. Thread
 * sets are compared within one invocation round, so accesses from different rounds, which
 * never overlapped, are not read as concurrent, and the high-contention threshold counts
 * the field's writes made in a round another thread also spent on the field, over the run and
 * whichever threads made them, so pooled platform workers and one-round virtual threads give
 * the same workload the same verdict. Only writes make coherence traffic: a read leaves the
 * line Shared, which any number of cores may hold at once, so a field that is only read is
 * never high-contention, and a pair needs a write to one of its fields in the round that put
 * them on different threads. The
 * findings are therefore not evidence of false sharing, and {@link #analyze()} returns
 * an empty report unless {@link #EXPERIMENTAL_PROPERTY} is set.
 * 
 * False sharing causes cache coherency traffic and performance degradation.
 * This detector identifies fields accessed by different threads with adjacent memory offsets.
 */
public class FalseSharingDetector {
    
    /**
     * System property that opts in to this detector's findings
     * ({@code -Dasync-test.experimental.false-sharing=true}). Without it, {@link #analyze()}
     * returns an empty report: the offset model behind the findings is declaration-order
     * arithmetic that real JVM field layout (reordering, compressed oops, {@code @Contended}
     * padding) does not follow, so the pairs it names are not evidence of actual cache-line
     * sharing. Recording is unaffected by the property.
     */
    public static final String EXPERIMENTAL_PROPERTY = "async-test.experimental.false-sharing";

    private static final int CACHE_LINE_SIZE = 64; // Common cache line size
    private static final int FIELD_ACCESS_THRESHOLD = 100; // Accesses to trigger analysis
    
    private final Map<String, FieldAccessInfo> fieldAccess = new ConcurrentHashMap<>();
    private final Map<String, List<AccessEvent>> accessHistory = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    
    private static class FieldAccessInfo {
        final String fieldName;
        final long memoryOffset;
        final AtomicLong accessCount = new AtomicLong(0);

        FieldAccessInfo(String name, long offset) {
            this.fieldName = name;
            this.memoryOffset = offset;
        }
    }

    /**
     * One access, with the round it was made in. Thread sets are compared within a round: the
     * runner finishes one round before it starts the next, so two threads that each touched a
     * field in a different round never contended, and with virtual threads every body execution
     * is a fresh thread (#765). The round comes from the same {@link SelfGuard.Scope} clock the
     * sharing verdicts read; with none bound the whole run is one round, as before. Whether it
     * was a write decides whether it can have cost another core its copy of the line (#825).
     */
    private static class AccessEvent {
        final long threadId;
        final int round;
        final boolean write;

        AccessEvent(long threadId, int round, boolean write) {
            this.threadId = threadId;
            this.round = round;
            this.write = write;
        }
    }
    
    /**
     * Record a field access, counted as a write. Call this when a field is accessed in your test;
     * {@link #recordFieldAccess(Object, String, Class, boolean)} tells a read from a write, and a
     * field that is only read then stays out of the findings.
     *
     * @param object the object the access is on, tracked by identity
     * @param fieldName the field involved, as it should appear in the report
     * @param fieldType the declared type of the field
     */
    public void recordFieldAccess(Object object, String fieldName, Class<?> fieldType) {
        recordFieldAccess(object, fieldName, fieldType, true);
    }

    /**
     * Record a field access, saying whether it wrote the field.
     *
     * @param object the object the access is on, tracked by identity
     * @param fieldName the field involved, as it should appear in the report
     * @param fieldType the declared type of the field
     * @param write {@code true} for a store to the field, {@code false} for a load; only stores
     *              count toward the high-contention threshold, and a pair needs a store to one of
     *              its two fields in the round that put them on different threads
     * @since 1.12.3
     */
    public void recordFieldAccess(Object object, String fieldName, Class<?> fieldType, boolean write) {
        if (!enabled || object == null) return;
        
        String key = object.getClass().getName() + "." + fieldName;

        // The offset estimate is approximate, and reflective, so it runs on the first access only.
        FieldAccessInfo info = fieldAccess.computeIfAbsent(key,
            k -> new FieldAccessInfo(fieldName, estimateMemoryOffset(object.getClass(), fieldName))
        );

        info.accessCount.incrementAndGet();

        // Record detailed access history for analysis; the thread sets are derived from it per round
        accessHistory.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(new AccessEvent(Thread.currentThread().threadId(), SelfGuard.RoundThreads.roundNow(), write));
    }
    
    /**
     * Analyze for false sharing patterns.
     *
     * @return the findings this detector collected during the run
     */
    public FalseSharingReport analyzeFalseSharing() {
        FalseSharingReport report = new FalseSharingReport();
        
        // Findings are opt-in (see EXPERIMENTAL_PROPERTY): the offsets below are estimates
        // the JVM's real field layout does not follow, so without explicit opt-in the
        // detector must stay silent rather than report pairs it cannot substantiate.
        // Recording still ran, so setting the property and re-analyzing needs no re-run.
        if (!Boolean.getBoolean(EXPERIMENTAL_PROPERTY)) {
            report.fillStructuredViolations();
            return DetectorFailurePolicy.checkedReport(this, report);
        }

        Map<String, Set<Integer>> writtenRounds = new HashMap<>();
        Map<String, Map<Integer, Set<Long>>> threadsByRound = threadsByRound(writtenRounds);
        // Sorted, so a pair names its fields in the same order on every run and every JDK
        List<Map.Entry<String, FieldAccessInfo>> fields = new ArrayList<>(fieldAccess.entrySet());
        fields.sort(Map.Entry.comparingByKey());

        // Find fields in same cache line accessed by different threads
        for (int i = 0; i < fields.size(); i++) {
            FieldAccessInfo field1 = fields.get(i).getValue();
            Map<Integer, Set<Long>> rounds1 = threadsByRound.getOrDefault(fields.get(i).getKey(), Map.of());
            Set<Integer> written1 = writtenRounds.getOrDefault(fields.get(i).getKey(), Set.of());

            for (int j = i + 1; j < fields.size(); j++) {
                FieldAccessInfo field2 = fields.get(j).getValue();
                Map<Integer, Set<Long>> rounds2 = threadsByRound.getOrDefault(fields.get(j).getKey(), Map.of());
                Set<Integer> written2 = writtenRounds.getOrDefault(fields.get(j).getKey(), Set.of());

                // Check if fields are in same cache line
                long offset1 = field1.memoryOffset;
                long offset2 = field2.memoryOffset;
                
                if (offset1 >= 0 && offset2 >= 0) {
                    long distance = Math.abs(offset1 - offset2);
                    
                    if (distance < CACHE_LINE_SIZE && distance > 0) {
                        // Different threads accessing adjacent fields, within one round, one of them
                        // written. Each pair is visited once, so it is tried with either field as the
                        // one with two or more threads: which of the two comes first is the order of
                        // the keys, and the verdict must not depend on it (#839).
                        if (differentThreadsInOneRound(rounds1, written1, rounds2, written2)
                                || differentThreadsInOneRound(rounds2, written2, rounds1, written1)) {
                            FalseSharingReport.ContentionPair pair = new FalseSharingReport.ContentionPair(
                                field1.fieldName, field2.fieldName, distance,
                                field1.accessCount.get(), field2.accessCount.get()
                            );
                            report.falseSharedPairs.add(pair);
                        }
                    }
                }
            }
        }
        
        // Analyze contention patterns from history
        analyzeContentionPatterns(report, threadsByRound);

        report.fillStructuredViolations();
        return DetectorFailurePolicy.checkedReport(this, report);
    }

    /**
     * {@return whether some round saw at least two threads on the first field, a non-empty,
     * different set of threads on the second, and a write to either}
     *
     * <p>The pair predicate the detector has always used (two or more threads on the first field,
     * unequal thread sets), taken within one round rather than over the run. A round in which the
     * second field was not accessed at all is no contention for the line, however the sets compare,
     * and neither is one in which both were only read: the line then stays Shared on every core that
     * holds it, and no core's copy is invalidated (#825).
     */
    private static boolean differentThreadsInOneRound(Map<Integer, Set<Long>> first, Set<Integer> firstWritten,
                                                      Map<Integer, Set<Long>> second, Set<Integer> secondWritten) {
        for (Map.Entry<Integer, Set<Long>> round : first.entrySet()) {
            Set<Long> other = second.get(round.getKey());
            if (round.getValue().size() >= 2 && other != null && !round.getValue().equals(other)
                    && (firstWritten.contains(round.getKey()) || secondWritten.contains(round.getKey()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return each field's accessing threads, per round, from a snapshot of the access history}
     *
     * @param writtenRounds filled with the rounds in which each field was written, keyed like the
     *                      result; a field never written gets no entry
     */
    private Map<String, Map<Integer, Set<Long>>> threadsByRound(Map<String, Set<Integer>> writtenRounds) {
        Map<String, Map<Integer, Set<Long>>> byField = new HashMap<>();
        for (Map.Entry<String, List<AccessEvent>> entry : accessHistory.entrySet()) {
            Map<Integer, Set<Long>> rounds = new HashMap<>();
            for (AccessEvent event : snapshot(entry.getValue())) {
                rounds.computeIfAbsent(event.round, r -> new HashSet<>()).add(event.threadId);
                if (event.write) {
                    writtenRounds.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).add(event.round);
                }
            }
            byField.put(entry.getKey(), rounds);
        }
        return byField;
    }

    private static List<AccessEvent> snapshot(List<AccessEvent> history) {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /**
     * Standardized alias for {@link #analyzeFalseSharing()}.
     *
     * @return the findings this detector collected during the run
     */
    public FalseSharingReport analyze() {
        return analyzeFalseSharing();
    }

    private void analyzeContentionPatterns(FalseSharingReport report,
                                           Map<String, Map<Integer, Set<Long>>> threadsByRound) {
        for (Map.Entry<String, List<AccessEvent>> entry : accessHistory.entrySet()) {
            List<AccessEvent> history = entry.getValue();
            if (history.size() < FIELD_ACCESS_THRESHOLD) continue;

            // Only accesses made in a round with more than one thread on the field count. Threads in
            // different rounds never contended (#765), and a platform thread outlives its round, so a
            // count kept over the run added up the rounds it spent alone on the field after racing
            // once (#794).
            //
            // The threshold is the field's contended traffic, not any one thread's share of it
            // (#811). Cache-line traffic is set by how often the line is touched while more than one
            // core holds it, and a Java thread is not a core: a platform worker migrates, and a
            // virtual thread lives one body execution. A per-thread share summed over the run is one
            // a pooled platform worker reaches across many rounds and a virtual thread only inside
            // one body, so the same workload reported on one thread model and not the other.
            //
            // Only writes count (#825). A read leaves the line Shared, and any number of cores hold
            // a Shared line at once, so reads alone move nothing. A write takes the line Exclusive
            // and invalidates every other copy, and each other core's next access misses once, then
            // hits its fresh copy until the next write. Every coherence miss follows a write, so the
            // writes made while another thread was on the field are the traffic, and a field nobody
            // writes is never high-contention however many threads read it. Counting the reads of a
            // round that also had a writer would let one write among many reads cross the threshold.
            Map<Integer, Set<Long>> rounds = threadsByRound.getOrDefault(entry.getKey(), Map.of());
            int contendedWrites = 0;
            for (AccessEvent event : snapshot(history)) {
                Set<Long> threads = rounds.get(event.round);
                if (event.write && threads != null && threads.size() > 1) {
                    contendedWrites++;
                }
            }

            if (contendedWrites >= FIELD_ACCESS_THRESHOLD) {
                report.highContentionFields.add(entry.getKey());
            }
        }
    }
    
    private long estimateMemoryOffset(Class<?> clazz, String fieldName) {
        try {
            Field target = clazz.getDeclaredField(fieldName);
            // Approximate offset based on field declaration order
            Field[] fields = clazz.getDeclaredFields();
            long offset = 16; // Object header
            for (Field f : fields) {
                if (f.equals(target)) {
                    return offset;
                }
                offset += getFieldSize(f.getType());
            }
            return -1;
        } catch (NoSuchFieldException e) {
            return -1;
        }
    }
    
    private long getFieldSize(Class<?> type) {
        if (type == long.class || type == double.class) return 8;
        if (type == int.class || type == float.class) return 4;
        if (type == short.class || type == char.class) return 2;
        if (type == byte.class || type == boolean.class) return 1;
        return 8; // References
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */
    public void reset() {
        fieldAccess.clear();
        accessHistory.clear();
    }
    /**
     * Disable.
     */
    public void disable() {
        enabled = false;
    }
    /**
     * Enable.
     */
    public void enable() {
        enabled = true;
    }
    
    public static class FalseSharingReport {
        public static class ContentionPair {
            /** First field of the contending pair. */
            public final String field1;
            /** Second field of the contending pair. */
            public final String field2;
            /** Distance between the two fields; under a cache line means they share one. */
            public final long distanceInBytes;
            /** How many times the first field of the pair was accessed. */
            public final long accesses1;
            /** How many times the second field of the pair was accessed. */
            public final long accesses2;
            /**
             * Creates a ContentionPair.
             *
             * @param f1 the first field of the contending pair
             * @param f2 the second field of the contending pair
             * @param dist the distance between the two fields in bytes; under a cache line means they share one
             * @param acc1 how many times the first field was accessed
             * @param acc2 how many times the second field was accessed
             */
            public ContentionPair(String f1, String f2, long dist, long acc1, long acc2) {
                this.field1 = f1;
                this.field2 = f2;
                this.distanceInBytes = dist;
                this.accesses1 = acc1;
                this.accesses2 = acc2;
            }
        }
        
        /**
         * Field pairs close enough to share a cache line, accessed by different threads in one round
         * in which one of the two was written.
         */
        public final Set<ContentionPair> falseSharedPairs = new HashSet<>();
        /**
         * Fields written often enough for cache-line sharing to matter: 100 or more writes made in
         * rounds another thread was also on the field. Reads do not count.
         */
        public final Set<String> highContentionFields = new HashSet<>();
        
        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !falseSharedPairs.isEmpty() || !highContentionFields.isEmpty();
        }
        
        /** The findings as Violations, at the severity the text resolved to (#801). */
        public final List<Violation> structuredViolations = new ArrayList<>();

        /** Adds a Violation per finding, worded as its text line (#801); called once before the report is returned. */
        void fillStructuredViolations() {
            if (!hasIssues()) {
                return;
            }
            // The severity the failOn gate read from this text before #801: a marker in it,
            // else the value DetectorDefaultSeverity declared for the detector.
            IssueSeverity severity = IssueSeverity.markedIn(toString()).orElse(IssueSeverity.HIGH);
            for (ContentionPair pair : falseSharedPairs) {
                structuredViolations.add(new Violation("FalseSharing", severity,
                        pair.field1 + " <-> " + pair.field2 + ": same cache line, " + pair.distanceInBytes + " bytes apart",
                        List.of(), Map.of(), Instant.now()));
            }
            for (String field : highContentionFields) {
                structuredViolations.add(new Violation("FalseSharing", severity,
                        field + ": written while other threads were on it", List.of(), Map.of(), Instant.now()));
            }
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No false sharing detected.";
            }
            
            StringBuilder sb = new StringBuilder();
            // The severity marker is load-bearing, not decoration. IssueSeverity.fromReport
            // recovers a finding's severity from this text and defaults to HIGH when it finds no
            // marker, so an unmarked advisory about cache-line adjacency reached the failOn gate
            // ranked alongside a lost update. This detector is experimental, off unless
            // -Dasync-test.experimental.false-sharing=true, and its findings are documented as
            // uncorrelated with the phenomenon, so LOW is the only defensible ranking.
            sb.append(IssueSeverity.LOW.getLabel()).append(" ");
            sb.append("POTENTIAL FALSE SHARING DETECTED:\n");
            
            if (!falseSharedPairs.isEmpty()) {
                sb.append("\nFields in same cache line accessed by different threads, one of them written:\n");
                for (ContentionPair pair : falseSharedPairs) {
                    sb.append(String.format(
                        "  - %s (accesses: %d) <-> %s (accesses: %d) [distance: %d bytes]%n",
                        pair.field1, pair.accesses1, pair.field2, pair.accesses2, pair.distanceInBytes
                    ));
                }
            }

            if (!highContentionFields.isEmpty()) {
                sb.append("\nHigh-contention fields written while other threads were on them:\n");
                for (String field : highContentionFields) {
                    sb.append("  - ").append(field).append("\n");
                }
            }

            sb.append("\nWhy: CPUs transfer memory in 64-byte cache lines. When Thread A writes fieldA and Thread B writes fieldB and both fields occupy the same cache line, every write forces the entire line to be invalidated and re-fetched across all cores — \"cache ping-pong\" that can reduce throughput by 10x even though the threads are touching entirely different fields.\n");
            sb.append("Fix:\n");
            sb.append("  - Annotate each hot field with @Contended (sun.misc.Contended / jdk.internal.vm.annotation.Contended); add -XX:+EnableContended on Java 8-10 (default from Java 11 onward)\n");
            sb.append("  - Pad manually: place 7 long dummy fields between the hot fields to force them onto separate cache lines\n");
            sb.append("  - Redesign: group read-only fields together and isolate write-heavy fields in their own inner class annotated @Contended");
            
            return sb.toString();
        }
    }
}
