package se.deversity.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects unsafe lazy initialization and broken double-checked locking patterns.
 */
public class LazyInitValidator {

    private static class LazyFieldState {
        final String fieldName;
        final Set<Long> accessingThreads = ConcurrentHashMap.newKeySet();
        final AtomicInteger initializationAttempts = new AtomicInteger();
        volatile boolean volatileField;
        volatile boolean synchronizedAccess;
        volatile boolean observedNull;
        volatile boolean initialized;

        LazyFieldState(String fieldName) {
            this.fieldName = fieldName;
        }
    }

    private final Map<String, LazyFieldState> fields = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    /**
     * Records access so it can be analysed at the end of the run.
     *
     * @param fieldName the field name
     * @param observedNull the observed null
     * @param initializedValue the initialized value
     * @param synchronizedAccess the synchronized access
     * @param volatileField the volatile field
     */

    public void recordAccess(String fieldName, boolean observedNull, boolean initializedValue,
                             boolean synchronizedAccess, boolean volatileField) {
        if (!enabled || fieldName == null || fieldName.isBlank()) {
            return;
        }

        LazyFieldState state = fields.computeIfAbsent(fieldName, LazyFieldState::new);
        state.accessingThreads.add(Thread.currentThread().threadId());
        // These four are monotone latches, and `|=` on a volatile is read-modify-write:
        // a thread OR-ing false can read before another's true and write false back over it,
        // losing the observation. Only ever writing true removes the lost update entirely,
        // and a plain volatile write is enough because the value never goes back down.
        if (observedNull) {
            state.observedNull = true;
        }
        if (initializedValue) {
            state.initialized = true;
        }
        if (synchronizedAccess) {
            state.synchronizedAccess = true;
        }
        if (volatileField) {
            state.volatileField = true;
        }
        if (observedNull && initializedValue) {
            state.initializationAttempts.incrementAndGet();
        }
    }
    /**
     * Analyses what has been recorded about the observation and builds the report for it.
     *
     * @return the analyze
     */

    public LazyInitReport analyze() {
        LazyInitReport report = new LazyInitReport();

        for (LazyFieldState state : fields.values()) {
            if (state.initializationAttempts.get() > 1 && !state.synchronizedAccess && !state.volatileField) {
                report.multipleInitializations.add(String.format(
                    "%s: %d unsynchronized initialization attempts across %d threads",
                    state.fieldName,
                    state.initializationAttempts.get(),
                    state.accessingThreads.size()
                ));
            }

            if (state.accessingThreads.size() > 1 && state.observedNull && state.initialized
                && !state.synchronizedAccess && !state.volatileField) {
                report.unsafePublication.add(String.format(
                    "%s: lazy init observed from %d threads without volatile/synchronization",
                    state.fieldName,
                    state.accessingThreads.size()
                ));
            }
        }

        return report;
    }
    /**
     * Clears recorded the observation so this instance can be reused for the next run.
     */

    public void reset() {
        fields.clear();
    }

    public static class LazyInitReport {
        /** The multiple initializations. */
        public final Set<String> multipleInitializations = new HashSet<>();
        /** The unsafe publication. */
        public final Set<String> unsafePublication = new HashSet<>();

        /**
         * {@return whether there are issues}
         */
        public boolean hasIssues() {
            return !multipleInitializations.isEmpty() || !unsafePublication.isEmpty();
        }

        @Override
        public String toString() {
            if (!hasIssues()) {
                return "No lazy initialization issues detected.";
            }

            StringBuilder sb = new StringBuilder("LAZY INITIALIZATION ISSUES DETECTED:\n");
            for (String issue : multipleInitializations) {
                sb.append("  - ").append(issue).append('\n');
            }
            for (String issue : unsafePublication) {
                sb.append("  - ").append(issue).append('\n');
            }
            sb.append("  Fix: guard initialization with synchronization, holder class, or volatile DCL");
            return sb.toString();
        }
    }
}
