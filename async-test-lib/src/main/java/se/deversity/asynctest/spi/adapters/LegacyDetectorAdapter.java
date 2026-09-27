package se.deversity.asynctest.spi.adapters;

import org.jspecify.annotations.Nullable;
import se.deversity.asynctest.DetectorFailurePolicy;
import se.deversity.asynctest.DetectorType;
import se.deversity.asynctest.diagnostics.DetectorDefaultSeverity;
import se.deversity.asynctest.report.Violation;
import se.deversity.asynctest.spi.Detector;
import se.deversity.vibetags.annotations.AILegacyBridge;
import se.deversity.vibetags.annotations.AIPerformance;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic SPI {@link Detector} that wraps a legacy detector instance and projects
 * its {@code analyze()} output into structured {@link Violation}s.
 *
 * <p>Legacy detectors do not share a common base interface — each one has its own
 * {@code analyze()} returning a bespoke {@code XxxReport} inner class. To avoid
 * writing 95 hand-tailored adapters, this class uses reflection to invoke
 * {@code delegate.analyze()} and the resulting report's {@code hasIssues()} /
 * {@code toString()}.
 *
 * <p>Detectors whose report does not follow the canonical shape
 * ({@code analyze() → Report{hasIssues(), toString()}}) silently return an empty
 * list — they continue to work via the legacy {@code DetectorRegistry} path; the
 * SPI registry simply has no structured view of them. The detector class and its report
 * type need not be public: the adapter makes their methods accessible wherever their
 * package is open to this library, as the class path always is (#851). A detector that
 * has the shape but whose report method or {@code hasIssues()} stays out of reach, in a
 * named module that does not open its package, is not shapeless: it is reported as a
 * failure below, since it would otherwise look clean whatever it recorded (#847).
 *
 * <p>A report with issues that keeps a public {@code structuredViolations} list hands
 * over those {@link Violation}s as they are, at the severities the detector chose, which
 * are the ones the {@code failOn} gate reads. A report without one becomes a single
 * {@link Violation} carrying its {@code toString()}, at the severity
 * {@link DetectorDefaultSeverity#of(String, String)} gives that text. Before 1.12.3 every
 * finding came out {@code HIGH} (#841). A report whose list this library may not read is
 * treated as one without a list, and strict mode does not call that list empty (#851).
 *
 * <p>A detector that throws from its report method, its report's {@code hasIssues()} or
 * {@code toString()}, or whose report cannot be reached, is contained the way the registry
 * path contains a throwing one, through
 * {@link DetectorFailurePolicy#detectorFailed}: its finding is lost and a line is written,
 * and under strict mode the build fails. Any other {@link Error} it throws, strict mode's own
 * {@link AssertionError} included, reaches the caller.
 *
 * @param <D> legacy detector type
 *
 * @since 1.6.0
 */
@AIPerformance(constraint = "analyze() does Method.getMethod + invoke each call; only invoked once per round per detector, not on the hot recordAccess path. If profiling shows reflection overhead, cache the Method handles in the constructor.")
@AILegacyBridge
public final class LegacyDetectorAdapter<D> implements Detector {

    private final D delegate;
    private final DetectorType type;
    private final String detectorName;
    private final @Nullable Method analyzeMethod;
    private final @Nullable NoSuchMethodException analyzeMethodLookupFailure;
    private final @Nullable Method hasIssuesMethod;
    /**
     * Creates a LegacyDetectorAdapter.
     *
     * @param delegate the legacy detector to expose through the SPI; findings are read from this instance
     * @param type the constant this detector answers to
     * @param detectorName the name this detector reports under
     */
    public LegacyDetectorAdapter(D delegate, DetectorType type, String detectorName) {
        this.delegate = delegate;
        this.type = type;
        this.detectorName = detectorName;

        Method resolvedAnalyze;
        NoSuchMethodException resolvedFailure;
        try {
            resolvedAnalyze = delegate.getClass().getMethod("analyze");
            resolvedFailure = null;
        } catch (NoSuchMethodException e) {
            // Not every detector names its report method "analyze". The set uses
            // analyzeAtomicity(), analyzeWakeups(), analyzeFairness(), validateLockOrder(),
            // validateConstructorSafety() and more. Before this fallback, a detector whose
            // method was named anything else resolved to nothing and analyze() returned an
            // empty list forever — registered, addressable, unit-tested, and permanently unable
            // to emit a Violation. LOCK_ORDER and CONSTRUCTOR_SAFETY were both in that state.
            // The failure was invisible because every path out of analyze() returns List.of().
            resolvedAnalyze = findReportMethod(delegate.getClass());
            resolvedFailure = resolvedAnalyze == null ? e : null;
        }
        this.analyzeMethod = resolvedAnalyze;
        this.analyzeMethodLookupFailure = resolvedFailure;
        this.hasIssuesMethod = (resolvedAnalyze == null) ? null : findHasIssues(resolvedAnalyze.getReturnType());
        // A third-party detector class or report type need not be public: one nested in a test
        // class usually is not. Where its package is open to this library, as the class path is,
        // this lets the calls through, as JUnit does for the test methods themselves (#851). Where
        // it is not, it answers false and changes nothing, so analyze() still reports the
        // IllegalAccessException (#847). It cannot open anything the detector's module keeps closed.
        if (resolvedAnalyze != null) resolvedAnalyze.trySetAccessible();
        if (hasIssuesMethod != null) hasIssuesMethod.trySetAccessible();
    }

    /**
     * Finds a detector's report method when it is not called {@code analyze}.
     *
     * <p>A report method is public, takes no arguments, and returns something carrying a
     * {@code boolean hasIssues()} — that last part is the real test, and it is what keeps this
     * from binding to an unrelated getter. Candidates are considered in name order so the choice
     * is deterministic across JVMs, since {@link Class#getMethods()} has no defined order and a
     * detector with two report methods must not bind to a different one on different runs.
     *
     * @param detectorClass the legacy detector's class
     * @return the report method, or {@code null} if the detector has no canonical shape
     */
    private static @Nullable Method findReportMethod(Class<?> detectorClass) {
        Method best = null;
        for (Method m : detectorClass.getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                continue;
            }
            String name = m.getName();
            if (!name.startsWith("analyze") && !name.startsWith("validate")) {
                continue;
            }
            if (findHasIssues(m.getReturnType()) == null) {
                continue;
            }
            if (best == null || name.compareTo(best.getName()) < 0) {
                best = m;
            }
        }
        return best;
    }

    @Override
    public DetectorType type() {
        return type;
    }

    @Override
    public List<Violation> analyze() {
        try {
            if (analyzeMethodLookupFailure != null) throw analyzeMethodLookupFailure;
            // Non-null whenever the lookup failure above is null — the constructor sets
            // exactly one of the two.
            if (analyzeMethod == null) return List.of();
            Object report = analyzeMethod.invoke(delegate);
            if (report == null) return List.of();

            if (hasIssuesMethod == null) return List.of();
            boolean has = (boolean) hasIssuesMethod.invoke(report);
            if (!has) return List.of();

            // The detector's own findings, at the severities it chose, are what the failOn gate
            // reads from the same report (DetectorDefaultSeverity.of). Grading the text HIGH here
            // instead made this path disagree with the gate for every detector that rates below
            // HIGH (#841).
            Optional<List<Violation>> structured = structuredFindings(report);
            if (structured.isPresent() && !structured.get().isEmpty()) return structured.get();

            String text = String.valueOf(report);
            // As DetectorRegistry.ifIssue: a report type that keeps the list and left it empty
            // fails this build's tests, and changes nothing anywhere else. A list this library may
            // not read is not an empty one, so strict mode would name the wrong fault; the finding
            // still comes out, graded by its text (#851).
            if (structured.isPresent()) DetectorFailurePolicy.structuredFindingsMissing(delegateName(), report);
            return List.of(new Violation(
                    detectorName,
                    DetectorDefaultSeverity.of(detectorName, text),
                    text,
                    List.of(),
                    Map.of(),
                    Instant.now()));
        } catch (InvocationTargetException e) {
            // analyze() or hasIssues() threw. Before #841 this was caught below as a shape
            // mismatch, so a broken detector, even one failing strict mode's own AssertionError,
            // reported nothing and failed nothing on this path.
            Throwable thrown = e.getCause();
            return detectorFailed(thrown != null ? thrown : e);
        } catch (IllegalAccessException | RuntimeException | StackOverflowError e) {
            // The report's toString() threw, or its structured list did. Or the detector has the
            // canonical shape but its report method or the report's hasIssues() is declared on a
            // class this package may not call into even after trySetAccessible, in a module that
            // does not open its package (IllegalAccessException), so whatever it
            // records never reaches this path: returned silently before #847, which read exactly
            // like a clean detector.
            return detectorFailed(e);
        } catch (ReflectiveOperationException e) {
            // The detector doesn't follow the canonical shape (NoSuchMethodException): it has no
            // structured view on this path, by design. Built-ins are held to the shape at build
            // time by DetectorFiringContractTest instead.
            return List.of();
        }
    }

    /**
     * Contains a failure the delegate threw, the way {@code DetectorRegistry.ifIssue} does on the
     * registry path: an exception or a {@link StackOverflowError} goes through
     * {@link DetectorFailurePolicy#detectorFailed}, which writes its line and, under strict mode,
     * fails the build; any other {@link Error}, such as strict mode's own {@link AssertionError}
     * raised inside {@code analyze()}, propagates unchanged.
     *
     * @param failure what the delegate threw, unwrapped from the reflection call, or the
     *                {@link IllegalAccessException} that refused the call into its report
     * @return an empty list, since a broken detector costs its own finding and nothing else
     */
    private List<Violation> detectorFailed(Throwable failure) {
        if (failure instanceof Error error && !(failure instanceof StackOverflowError)) {
            throw error;
        }
        DetectorFailurePolicy.detectorFailed(delegateName(), failure);
        return List.of();
    }

    /** {@return the delegate's simple class name, which the registry path's diagnostics use too} */
    private String delegateName() {
        return delegate.getClass().getSimpleName();
    }

    /**
     * {@return the {@link Violation}s in {@code report}'s public {@code structuredViolations} list,
     * an empty list when its type keeps none or the list is empty, and no list at all when its
     * type keeps one this library may not read}
     *
     * <p>Read by name, as {@link DetectorDefaultSeverity#structuredIn} reads it for the gate, since
     * the built-in reports share no interface. The list may sit on a report type that is not
     * public; it is read wherever that type's package is open to this library, and nowhere else.
     *
     * @param report a report that has issues
     */
    private static Optional<List<Violation>> structuredFindings(Object report) throws IllegalAccessException {
        Field field;
        try {
            field = report.getClass().getField(DetectorDefaultSeverity.STRUCTURED_FIELD);
        } catch (NoSuchFieldException textOnly) {
            return Optional.of(List.of());
        }
        if (!(field.canAccess(report) || field.trySetAccessible())) {
            return Optional.empty();
        }
        if (!(field.get(report) instanceof List<?> findings)) {
            return Optional.of(List.of());
        }
        List<Violation> out = new ArrayList<>(findings.size());
        for (Object finding : findings) {
            if (finding instanceof Violation v) out.add(v);
        }
        return Optional.of(List.copyOf(out));
    }

    /**
     * Exposed for callers that need direct access to the wrapped legacy detector.
     *
     * @return the wrapped legacy detector
     */
    public D delegate() {
        return delegate;
    }

    /**
     * Walks the report class hierarchy looking for a {@code boolean hasIssues()}
     * method. Some reports declare it on a base type / interface.
     */
    private static @Nullable Method findHasIssues(Class<?> reportClass) {
        for (Class<?> c = reportClass; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getMethod("hasIssues");
                if (m.getReturnType() == boolean.class) return m;
            } catch (NoSuchMethodException ignored) {
                // try parent
            }
        }
        return null;
    }
}
