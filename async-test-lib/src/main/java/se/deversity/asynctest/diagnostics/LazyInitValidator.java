package se.deversity.asynctest.diagnostics;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects broken lazy initialization: a field one thread saw as null and another saw as
 * initialized, with neither volatile nor synchronization.
 *
 * <p><strong>The runner never analyzes this class.</strong> It has no
 * {@link se.deversity.asynctest.DetectorType}, no attribute on {@code @AsyncTest} and no
 * {@code DetectorRegistry} entry, so no {@code failOn} gate can trip on what it records: a test
 * drives it and reads {@link #analyze()} itself. Instrumenting it and getting a clean report from
 * an {@code @AsyncTest} means only that nobody asked.
 *
 * <p>The wired detectors for this bug are {@code DoubleCheckedLockingDetector}, which reports the
 * broken DCL structure, and {@code LazyInitRaceDetector}, which reports an observed initialization
 * race. {@code examples/28-lazy-init} promised a detection from this class and lost its
 * demonstration over it. See issues #363 and #374.
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
     * @param fieldName the field involved, as it should appear in the report
     * @param observedNull {@code true} when the reading thread saw {@code null}
     * @param initializedValue {@code true} when the value had already been initialised
     * @param synchronizedAccess {@code true} when the access was made while holding the lock
     * @param volatileField {@code true} when the field is declared {@code volatile}
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
     * @return the findings this detector collected during the run
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
        /** Fields initialised more than once because the guard was not atomic. */
        public final Set<String> multipleInitializations = new HashSet<>();
        /** Fields published without the ordering a reader would need to see them fully. */
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
