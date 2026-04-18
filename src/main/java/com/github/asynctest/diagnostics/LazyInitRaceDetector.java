package com.github.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detects lazy-initialization races — situations where multiple threads
 * simultaneously observe a field as {@code null} and each proceeds to
 * initialize it, causing the initialization to execute more than once.
 *
 * <p>The classic broken pattern:
 *
 * <pre>{@code
 * private ExpensiveObject instance;  // NOT volatile
 *
 * ExpensiveObject getInstance() {
 *     if (instance == null) {         // ← Thread A and B both see null
 *         instance = new ExpensiveObject();  // ← both initialize!
 *     }
 *     return instance;
 * }
 * }</pre>
 *
 * <p>Even if the initialization is "idempotent", this pattern can cause:
 * <ul>
 *   <li>Visible-state inconsistency when {@code instance} is not {@code volatile}</li>
 *   <li>Wasteful duplicate initialization of expensive resources</li>
 *   <li>Race conditions if initialization has side-effects (database connections,
 *       file handles, etc.)</li>
 * </ul>
 *
 * <p>This detector tracks how many threads simultaneously report a {@code null}
 * check before performing initialization.  If more than one thread calls
 * {@link #recordInitialization} for the same field, a race is recorded.
 *
 * <p>Usage:
 * <pre>{@code
 * @AsyncTest(threads = 8, detectLazyInitRace = true)
 * void testLazyInit() {
 *     LazyInitRaceDetector d = AsyncTestContext.lazyInitRaceDetector();
 *
 *     if (instance == null) {
 *         d.recordNullCheck("MyService.instance", true, false);
 *         instance = createInstance();
 *         d.recordInitialization("MyService.instance");
 *     } else {
 *         d.recordNullCheck("MyService.instance", false, false);
 *     }
 * }
 * }</pre>
 */
public class LazyInitRaceDetector {

    private static final class FieldState {
        final String fieldId;
        final AtomicInteger initCount        = new AtomicInteger();
        final AtomicInteger nullCheckCount   = new AtomicInteger();
        final Set<Long>     initializingThreads = ConcurrentHashMap.newKeySet();
        volatile boolean    isVolatile       = false;

        FieldState(String fieldId) {
            this.fieldId = fieldId;
        }
    }

    private final Map<String, FieldState> fields = new ConcurrentHashMap<>();

    // ---- Public API --------------------------------------------------------

    /**
     * Records a null-guard check on a lazily-initialized field.
     *
     * @param fieldId    a stable identifier, e.g. {@code "MyService.instance"}
     * @param wasNull    {@code true} if the field was observed as {@code null}
     *                   (i.e., the thread intends to initialize it)
     * @param isVolatile {@code true} if the field is declared {@code volatile}
     */
    public void recordNullCheck(String fieldId, boolean wasNull, boolean isVolatile) {
        if (fieldId == null) return;
        FieldState state = resolve(fieldId);
        state.nullCheckCount.incrementAndGet();
        if (isVolatile) state.isVolatile = true;
        if (wasNull) {
            state.initializingThreads.add(Thread.currentThread().threadId());
        }
    }

    /**
     * Records that the calling thread performed the initialization (i.e., it
     * set the previously-null field to a new value).
     *
     * <p>If multiple threads call this for the same {@code fieldId}, a race is
     * detected.
     *
     * @param fieldId the same identifier passed to {@link #recordNullCheck}
     */
    public void recordInitialization(String fieldId) {
        if (fieldId == null) return;
        resolve(fieldId).initCount.incrementAndGet();
    }

    // ---- Analysis ----------------------------------------------------------

    /**
     * Analyses recorded initialization data and returns a report of fields
     * where duplicate initialization was detected.
     */
    public LazyInitRaceReport analyze() {
        LazyInitRaceReport report = new LazyInitRaceReport();

        for (FieldState state : fields.values()) {
            int inits = state.initCount.get();
            int concurrent = state.initializingThreads.size();

            if (inits > 1) {
                String volatileNote = state.isVolatile
                        ? " (field is volatile but initialization is still non-atomic)"
                        : " (field is NOT volatile — memory visibility also at risk)";
                report.races.add(String.format(
                        "%s: initialized %d time(s) by %d concurrent thread(s)%s — DUPLICATE INITIALIZATION RACE!",
                        state.fieldId, inits, concurrent, volatileNote));
            } else if (concurrent > 1 && inits <= 1) {
                // Multiple threads saw null simultaneously but only one initialized —
                // still risky without visibility guarantee
                if (!state.isVolatile) {
                    report.visibilityRisks.add(String.format(
                            "%s: %d thread(s) simultaneously observed null but field is not volatile — VISIBILITY RISK",
                            state.fieldId, concurrent));
                }
            }
        }

        return report;
    }

    // ---- Internal ----------------------------------------------------------

    private FieldState resolve(String fieldId) {
        return fields.computeIfAbsent(fieldId, FieldState::new);
    }

    // ---- Report ------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class LazyInitRaceReport {

        final List<String> races          = new ArrayList<>();
        final List<String> visibilityRisks = new ArrayList<>();

        /** Returns {@code true} when any lazy-init race or visibility risk was detected. */
        public boolean hasIssues() {
            return !races.isEmpty() || !visibilityRisks.isEmpty();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("LAZY INITIALIZATION RACE ISSUES DETECTED:\n");

            if (!races.isEmpty()) {
                sb.append("  Duplicate Initialization Races:\n");
                for (String r : races) {
                    sb.append("    - ").append(r).append("\n");
                }
            }

            if (!visibilityRisks.isEmpty()) {
                sb.append("  Non-volatile Lazy Fields (visibility risk):\n");
                for (String r : visibilityRisks) {
                    sb.append("    - ").append(r).append("\n");
                }
            }

            if (!hasIssues()) {
                sb.append("  No lazy-init races detected.\n");
            }

            sb.append("  Fix: use 'volatile' + double-checked locking, or the")
              .append(" initialization-on-demand holder idiom:")
              .append(" 'private static class Holder { static final T INSTANCE = new T(); }'");
            return sb.toString();
        }
    }
}
