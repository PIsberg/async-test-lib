package se.deversity.asynctest.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

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
 * <p>A field is one field of one instance. Pass the instance that declares it through
 * {@link #recordNullCheck(Object, String, boolean, boolean)} and
 * {@link #recordInitialization(Object, String)}: a holder created per invocation or per thread
 * then initialises its own field once and is not reported, where the label alone would merge
 * every such holder into one field initialised many times. Within one invocation round the
 * label-only methods are exact, and {@link #markInvocationStart()} closes a round.
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
        /** The open round's initializations. */
        final AtomicInteger initCount        = new AtomicInteger();
        /** The open round's threads that observed the field as null. */
        final Set<Long>     initializingThreads = ConcurrentHashMap.newKeySet();
        volatile boolean    isVolatile       = false;

        /** The closed round with the most initializations, and its null observers. */
        private int closedInits;
        private int closedInitObservers;
        /** The most null observers in a closed round that initialized at most once. */
        private int closedNullObservers;

        FieldState(String fieldId) {
            this.fieldId = fieldId;
        }

        /** Folds the open round into the closed summary and opens a new one. Runs quiescent. */
        synchronized void closeRound() {
            int[] closed = fold(initCount.getAndSet(0), initializingThreads.size());
            initializingThreads.clear();
            closedInits = closed[0];
            closedInitObservers = closed[1];
            closedNullObservers = closed[2];
        }

        /**
         * {@return the worst round so far, the open one included: its initializations, its null
         * observers, and the most null observers of any round that initialized at most once}
         */
        synchronized int[] worst() {
            return fold(initCount.get(), initializingThreads.size());
        }

        private int[] fold(int inits, int observers) {
            boolean worse = inits > closedInits;
            return new int[] {
                worse ? inits : closedInits,
                worse ? observers : closedInitObservers,
                inits <= 1 ? Math.max(observers, closedNullObservers) : closedNullObservers
            };
        }
    }

    /**
     * One field on one owner. The label alone names a field of a class, not of an instance: a
     * holder created afresh for every invocation, or one per thread, initialises its own field
     * once, and keyed by the label those initialisations read as one field initialised many
     * times.
     */
    private record OwnedField(IdentityKey owner, String fieldId) { }

    /** Keyed by the label alone, or by {@link OwnedField} when the caller names the owner. */
    private final Map<Object, FieldState> fields = new ConcurrentHashMap<>();

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
        noteNullCheck(resolve(fieldId), wasNull, isVolatile);
    }

    /**
     * Records a null-guard check on a lazily-initialized field of {@code owner}.
     *
     * <p>Prefer this to the label-only overload whenever the field belongs to an instance. The
     * label names a field of a class; the owner names the one being initialised, so a holder
     * created per invocation or per thread is judged on its own initialisations.
     *
     * @param owner      the instance that declares the field (null-safe; ignored if {@code null})
     * @param fieldId    a stable identifier, e.g. {@code "MyService.instance"}
     * @param wasNull    {@code true} if the field was observed as {@code null}
     * @param isVolatile {@code true} if the field is declared {@code volatile}
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordNullCheck(Object owner, String fieldId, boolean wasNull, boolean isVolatile) {
        if (owner == null || fieldId == null) return;
        noteNullCheck(resolve(owner, fieldId), wasNull, isVolatile);
    }

    private static void noteNullCheck(FieldState state, boolean wasNull, boolean isVolatile) {
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

    /**
     * Records that the calling thread initialized the field of {@code owner}.
     *
     * @param owner   the same instance passed to {@link #recordNullCheck(Object, String, boolean, boolean)}
     * @param fieldId the same identifier passed there
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void recordInitialization(Object owner, String fieldId) {
        if (owner == null || fieldId == null) return;
        resolve(owner, fieldId).initCount.incrementAndGet();
    }

    /**
     * Closes the invocation round in progress, so each round is judged on its own.
     *
     * <p>A field the body builds afresh every round is initialised once per round, and counted
     * across the whole run those initialisations read as one field initialised many times. A
     * duplicate initialisation, or several threads seeing {@code null}, is a finding when it
     * happens within one round; the worst round is what the report describes. Call this after
     * the previous round's workers have all finished, as {@code ConcurrencyRunner} does for the
     * detectors wired into {@code AsyncTestContext.markInvocationStart()}.
     *
     * @since 1.12.3
     */
    @API(status = Status.EXPERIMENTAL)
    public void markInvocationStart() {
        for (FieldState state : fields.values()) {
            state.closeRound();
        }
    }

    // ---- Analysis ----------------------------------------------------------

    /**
     * Analyses recorded initialization data and returns a report of fields
     * where duplicate initialization was detected.
     *
     * @return the findings this detector collected during the run
     */
    public LazyInitRaceReport analyze() {
        LazyInitRaceReport report = new LazyInitRaceReport();

        for (FieldState state : fields.values()) {
            int[] worst = state.worst();
            int inits = worst[0];
            int concurrent = inits > 1 ? worst[1] : worst[2];

            if (inits > 1) {
                String volatileNote = state.isVolatile
                        ? " (field is volatile but initialization is still non-atomic)"
                        : " (field is NOT volatile — memory visibility also at risk)";
                report.races.add(String.format(
                        "%s: initialized %d time(s) by %d concurrent thread(s)%s — DUPLICATE INITIALIZATION RACE!",
                        state.fieldId, inits, concurrent, volatileNote));
            } else if (concurrent > 1) {
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
        return fields.computeIfAbsent(fieldId, id -> new FieldState(fieldId));
    }

    private FieldState resolve(Object owner, String fieldId) {
        OwnedField key = new OwnedField(new IdentityKey(owner), fieldId);
        FieldState state = fields.get(key);
        if (state == null) {
            state = fields.computeIfAbsent(key, k -> new FieldState(fieldId + " on " + key.owner()));
        }
        return state;
    }

    // ---- Report ------------------------------------------------------------

    /**
     * Report produced by {@link #analyze()}.
     */
    public static class LazyInitRaceReport {

        final List<String> races          = new ArrayList<>();
        final List<String> visibilityRisks = new ArrayList<>();

        /**
         * Returns {@code true} when any lazy-init race or visibility risk was detected.
         *
         * @return {@code true} when this detector recorded something worth reporting
         */
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
