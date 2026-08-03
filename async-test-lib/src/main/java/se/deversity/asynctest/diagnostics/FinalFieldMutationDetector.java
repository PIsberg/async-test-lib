package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import se.deversity.vibetags.annotations.AITestDriven;
import se.deversity.vibetags.annotations.AIThreadSafe;

/**
 * Detects reflective mutation of {@code final} fields
 * ({@code Field.setAccessible(true)} + {@code Field.set(...)}), which JDK 26
 * warns about and future JDK releases will deny by default (JEP 500 — Warnings
 * About Uses of Deep Reflection to Mutate Final Fields).
 *
 * <p><strong>Why it matters — twice over:</strong>
 * <ul>
 *   <li><b>Memory model.</b> The JMM's final-field semantics guarantee that any
 *       thread that sees a reference to an object sees its {@code final} fields
 *       fully initialized — <em>without synchronization</em>. That guarantee holds
 *       only for values written in the constructor. A reflective write after
 *       construction has no such fence: other threads may never observe the new
 *       value (the JIT is free to constant-fold {@code final} reads), observe it
 *       arbitrarily late, or observe a mix of old and new state. This is a
 *       silent, unfixable data race.</li>
 *   <li><b>Forward compatibility.</b> JDK 26 runs with
 *       {@code --illegal-final-field-mutation=warn} by default and prints
 *       "Mutating final fields will be blocked in a future release". Code relying
 *       on this pattern (hand-rolled DI, test fixture injection, serialization
 *       hacks) will start throwing once the default flips to {@code deny}.</li>
 * </ul>
 *
 * <p><strong>Issues detected:</strong>
 * <ul>
 *   <li><b>Final-field mutation</b> — any reflective write to a {@code final}
 *       field observed during the test (HIGH). One issue per field, with mutation
 *       count and the mutating threads.</li>
 *   <li><b>Mutation racing readers</b> — the field was also read by at least one
 *       thread other than the mutators (CRITICAL). Those readers have no
 *       happens-before edge to the write and may see the stale value forever.</li>
 *   <li><b>Concurrent mutators</b> — two or more distinct threads reflectively
 *       wrote the same final field (CRITICAL): last-write-wins with no ordering
 *       guarantee at all.</li>
 * </ul>
 *
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * @AsyncTest(threads = 8)
 * void testConfigOverride() {
 *     var detector = AsyncTestContext.finalFieldMutationDetector();
 *     String field = "Config.MAX_RETRIES";
 *
 *     detector.recordMutation(field, Thread.currentThread());
 *     reflectivelyOverride(config, "MAX_RETRIES", 5);   // Field.set on a final field
 *
 *     detector.recordRead(field, Thread.currentThread());
 *     int retries = config.maxRetries();
 * }
 * }</pre>
 *
 * @since 1.7.0
 */
@AIThreadSafe(strategy = AIThreadSafe.Strategy.OTHER, note = "Per-field state in ConcurrentHashMap with get-then-computeIfAbsent hot path; thread-id/name sets are ConcurrentHashMap.newKeySet().")
@AITestDriven(
    framework = {AITestDriven.Framework.JUNIT_5},
    coverageGoal = 80,
    testLocation = "src/test/java/se/deversity/asynctest/diagnostics/FinalFieldMutationDetectorTest.java"
)
public class FinalFieldMutationDetector {

    private static final class State {
        final AtomicInteger mutations = new AtomicInteger(0);
        final Set<Long>   mutatingThreadIds   = ConcurrentHashMap.newKeySet();
        final Set<String> mutatingThreadNames = ConcurrentHashMap.newKeySet();
        final Set<Long>   readingThreadIds    = ConcurrentHashMap.newKeySet();
        final Set<String> readingThreadNames  = ConcurrentHashMap.newKeySet();
    }

    // Per-field state, keyed by the caller-supplied descriptive name
    // (e.g. "Config.MAX_RETRIES").
    private final Map<String, State> fields = new ConcurrentHashMap<>();

    private State stateFor(String field) {
        State s = fields.get(field);
        if (s == null) {
            s = fields.computeIfAbsent(field, k -> new State());
        }
        return s;
    }

    /**
     * Record a reflective mutation of a {@code final} field
     * ({@code Field.set}, {@code Unsafe.putObject}, VarHandle hacks, ...).
     *
     * @param field  a descriptive name for the field (e.g. {@code "Config.MAX_RETRIES"})
     * @param thread the mutating thread
     */
    public void recordMutation(String field, Thread thread) {
        if (field == null || thread == null) return;
        State s = stateFor(field);
        s.mutations.incrementAndGet();
        s.mutatingThreadIds.add(thread.threadId());
        s.mutatingThreadNames.add(thread.getName());
    }

    /**
     * Record an ordinary read of the same {@code final} field. Reads from threads
     * other than the mutators escalate the finding: those readers have no
     * happens-before edge to the reflective write.
     *
     * @param field  a descriptive name for the field
     * @param thread the reading thread
     */
    public void recordRead(String field, Thread thread) {
        if (field == null || thread == null) return;
        State s = stateFor(field);
        s.readingThreadIds.add(thread.threadId());
        s.readingThreadNames.add(thread.getName());
    }

    /**
     * Analyze all recorded final-field events.
     *
     * @return a report describing detected issues
     */
    public FinalFieldMutationReport analyze() {
        List<String> mutationIssues        = new ArrayList<>();
        List<String> racingReaderIssues    = new ArrayList<>();
        List<String> concurrentWriteIssues = new ArrayList<>();

        for (Map.Entry<String, State> e : fields.entrySet()) {
            String field = e.getKey();
            State s = e.getValue();
            if (s.mutatingThreadIds.isEmpty()) continue;

            mutationIssues.add(
                "Final field '" + field + "' reflectively mutated " + s.mutations.get()
                + " time(s) by thread(s) " + String.join(", ", s.mutatingThreadNames)
                + ". JDK 26 warns on this (--illegal-final-field-mutation=warn) and a "
                + "future release will deny it; it also voids the JMM final-field "
                + "publication guarantee for every reader."
            );

            boolean foreignReader = s.readingThreadIds.stream()
                    .anyMatch(id -> !s.mutatingThreadIds.contains(id));
            if (foreignReader) {
                racingReaderIssues.add(
                    "Final field '" + field + "' was read by thread(s) "
                    + String.join(", ", s.readingThreadNames)
                    + " while being reflectively mutated by "
                    + String.join(", ", s.mutatingThreadNames)
                    + ". Readers of a final field have no happens-before edge to a "
                    + "post-construction write — they may see the stale value forever "
                    + "(final reads can be constant-folded)."
                );
            }

            if (s.mutatingThreadIds.size() > 1) {
                concurrentWriteIssues.add(
                    "Final field '" + field + "' reflectively mutated by "
                    + s.mutatingThreadIds.size() + " distinct threads ("
                    + String.join(", ", s.mutatingThreadNames)
                    + ") — last-write-wins with no ordering guarantee."
                );
            }
        }

        return new FinalFieldMutationReport(mutationIssues, racingReaderIssues, concurrentWriteIssues);
    }

    /**
     * Report of final-field mutation analysis.
     */
    public static class FinalFieldMutationReport {
        private final List<String> mutationIssues;
        private final List<String> racingReaderIssues;
        private final List<String> concurrentWriteIssues;

        FinalFieldMutationReport(
                List<String> mutationIssues,
                List<String> racingReaderIssues,
                List<String> concurrentWriteIssues) {
            this.mutationIssues = mutationIssues;
            this.racingReaderIssues = racingReaderIssues;
            this.concurrentWriteIssues = concurrentWriteIssues;
        }

        /** {@return true if any reflective final-field mutation was detected} */
        public boolean hasIssues() {
            return !mutationIssues.isEmpty();
        }

        /** {@return the mutation issues} */
        public List<String> getMutationIssues()        { return Collections.unmodifiableList(mutationIssues); }
        /** {@return the racing reader issues} */
        public List<String> getRacingReaderIssues()    { return Collections.unmodifiableList(racingReaderIssues); }
        /** {@return the concurrent write issues} */
        public List<String> getConcurrentWriteIssues() { return Collections.unmodifiableList(concurrentWriteIssues); }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "FinalFieldMutationReport: No final-field mutation detected";
            }

            StringBuilder sb = new StringBuilder();
            if (!racingReaderIssues.isEmpty() || !concurrentWriteIssues.isEmpty()) {
                sb.append(IssueSeverity.CRITICAL.format())
                  .append(": final field mutated while other threads depend on it\n");
            } else {
                sb.append(IssueSeverity.HIGH.format())
                  .append(": reflective final-field mutation (deprecated by JEP 500, breaks JMM guarantees)\n");
            }

            appendSection(sb, "Final-field mutations", mutationIssues);
            appendSection(sb, "Mutation racing readers (stale value forever)", racingReaderIssues);
            appendSection(sb, "Concurrent mutators (unordered last-write-wins)", concurrentWriteIssues);

            sb.append("\n\n").append("=".repeat(60));
            sb.append("\n").append(getLearningContent());
            sb.append("=".repeat(60));

            return sb.toString();
        }

        private static void appendSection(StringBuilder sb, String title, List<String> items) {
            if (items.isEmpty()) return;
            sb.append("\n  ").append(title).append(":\n");
            for (String item : items) {
                sb.append("    - ").append(item).append("\n");
            }
        }

        private static String getLearningContent() {
            return """
                📚 LEARNING: Reflective final-field mutation (JEP 500, JDK 26)

                The JMM guarantees that a thread which sees a reference to an object
                sees its final fields fully initialized — with no synchronization.
                That only covers writes made in the constructor. Field.set(...) on a
                final field after construction has no fence: readers may never see
                the new value (final reads can be constant-folded by the JIT).

                JDK 26 default: --illegal-final-field-mutation=warn
                  "Mutating final fields will be blocked in a future release"
                A future JDK flips the default to deny → IllegalAccessException.

                Common offenders:
                  ✗ Test fixtures injecting mocks into final fields
                  ✗ Hand-rolled dependency injection / configuration override
                  ✗ Serialization frameworks writing final fields outside readObject

                Fixes:
                  • Make the field non-final and volatile if it must change.
                  • Inject via constructor (redesign for testability).
                  • For frameworks: use sun.reflect.ReflectionFactory serialization
                    channels, or record the need for --enable-final-field-mutation.
                """;
        }
    }
}
