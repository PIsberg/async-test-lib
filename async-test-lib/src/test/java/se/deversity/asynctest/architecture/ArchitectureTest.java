package se.deversity.asynctest.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * ArchUnit architectural constraint tests for async-test-lib.
 *
 * Rules are verified against the actual source before being written:
 * - Package dependency directions are confirmed by import-level grep
 * - Naming conventions are confirmed against all classes in each package
 * - Immutability is confirmed by inspecting AsyncTestConfig field declarations
 */
@AnalyzeClasses(
        packages = "se.deversity.asynctest",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
class ArchitectureTest {

    // =========================================================================
    // Package dependency rules
    // =========================================================================

    /**
     * Detectors are pure logic components. They must not reference JUnit
     * extension or runner infrastructure, keeping them framework-independent
     * and reusable.
     */
    @ArchTest
    static final ArchRule diagnostics_does_not_depend_on_extension =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.diagnostics..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.extension..")
                    .because("diagnostics are pure detection logic; they must not know about JUnit extension infrastructure");

    @ArchTest
    static final ArchRule diagnostics_does_not_depend_on_runner =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.diagnostics..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.runner..")
                    .because("diagnostics are pure detection logic; they must not know about the concurrency runner");

    /**
     * The SPI core interfaces (Detector, DetectorFactory, DetectorRegistry) must not
     * depend on concrete implementations in diagnostics, runner, or extension — that
     * would invert the dependency direction and prevent third-party detector authors
     * from implementing the SPI without pulling in the full library internals.
     *
     * Note: spi.adapters is intentionally excluded from this rule. The adapters
     * sub-package is the designated glue layer that bridges the legacy diagnostics
     * detectors into the SPI, so its dependency on diagnostics is by design.
     */
    @ArchTest
    static final ArchRule spi_core_does_not_depend_on_diagnostics =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.spi")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.diagnostics..")
                    .because("spi core interfaces are the stable contract layer; only spi.adapters (the glue layer) may reference concrete diagnostics");

    @ArchTest
    static final ArchRule spi_does_not_depend_on_runner =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.spi..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.runner..")
                    .because("spi and its adapters must not depend on the concurrency runner — runner is a consumer of spi, not the reverse");

    @ArchTest
    static final ArchRule spi_does_not_depend_on_extension =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.spi..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.extension..")
                    .because("spi and its adapters must not depend on JUnit extension infrastructure");

    /**
     * The benchmark package records timing telemetry. It must remain
     * independent of detector logic, runner, and extension so it can be
     * used in isolation and kept on the hot path without pulling in
     * unrelated transitive dependencies.
     */
    @ArchTest
    static final ArchRule benchmark_does_not_depend_on_diagnostics =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.benchmark..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.diagnostics..")
                    .because("benchmark is a lightweight telemetry layer; it must not pull in detector logic");

    @ArchTest
    static final ArchRule benchmark_does_not_depend_on_runner =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.benchmark..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.runner..")
                    .because("benchmark is a lightweight telemetry layer; it must not depend on the concurrency runner");

    @ArchTest
    static final ArchRule benchmark_does_not_depend_on_extension =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.benchmark..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.extension..")
                    .because("benchmark is a lightweight telemetry layer; it must not depend on JUnit extension infrastructure");

    /**
     * The report package formats violations for output. It must not reference
     * runner or extension packages, keeping report generation decoupled from
     * execution machinery.
     */
    @ArchTest
    static final ArchRule report_does_not_depend_on_runner =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.report..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.runner..")
                    .because("report formatting is presentation-only; it must not depend on the concurrency runner");

    @ArchTest
    static final ArchRule report_does_not_depend_on_extension =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.report..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.extension..")
                    .because("report formatting is presentation-only; it must not depend on JUnit extension infrastructure");

    /**
     * The extension package (JUnit 5 SPI) drives test setup and delegates
     * execution to ConcurrencyRunner. It must not call back into runner in a
     * way that creates a circular dependency — the runner is the terminal
     * execution layer and must not know about extension wiring.
     */
    @ArchTest
    static final ArchRule runner_does_not_depend_on_extension =
            noClasses()
                    .that().resideInAPackage("se.deversity.asynctest.runner..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("se.deversity.asynctest.extension..")
                    .because("runner is the execution engine; it must not reference the JUnit extension that drives it");

    // =========================================================================
    // Naming conventions
    // =========================================================================

    /**
     * Public annotation types form the user-facing API of the library.
     * Keeping them in the root package gives users a single, stable import
     * prefix and avoids churn if internal sub-packages are restructured.
     */
    @ArchTest
    static final ArchRule annotation_types_reside_in_root_package =
            classes()
                    .that().areAnnotations()
                    .and().arePublic()
                    .should().resideInAPackage("se.deversity.asynctest")
                    .because("public annotation types are stable API and belong in the root package");

    /**
     * Every concrete (non-interface) class ending in "Formatter" in the report
     * package must implement the Formatter SPI contract so consumers can discover
     * and use them uniformly via the Formatter interface.
     *
     * The Formatter interface itself is excluded because an interface cannot
     * implement itself.
     */
    @ArchTest
    static final ArchRule formatters_implement_formatter_interface =
            classes()
                    .that().resideInAPackage("se.deversity.asynctest.report..")
                    .and().haveSimpleNameEndingWith("Formatter")
                    .and().arePublic()
                    .and().areNotInterfaces()
                    .should().implement(se.deversity.asynctest.report.Formatter.class)
                    .because("concrete classes named *Formatter in the report package must implement the Formatter contract");

    /**
     * Exception classes must extend RuntimeException or Exception — never
     * Throwable directly — so they integrate cleanly with standard Java
     * exception handling idioms.
     */
    @ArchTest
    static final ArchRule exceptions_extend_runtime_or_checked_exception =
            classes()
                    .that().haveSimpleNameEndingWith("Exception")
                    .should().beAssignableTo(Exception.class)
                    .because("exception types must extend Exception or RuntimeException, not raw Throwable");

    // =========================================================================
    // Immutability
    // =========================================================================

    /**
     * AsyncTestConfig is documented as an immutable snapshot passed across
     * thread boundaries. All its fields must be final to enforce that
     * guarantee at the language level.
     */
    @ArchTest
    static final ArchRule async_test_config_has_only_final_fields =
            classes()
                    .that().haveFullyQualifiedName("se.deversity.asynctest.AsyncTestConfig")
                    .should().haveOnlyFinalFields()
                    .because("AsyncTestConfig is an immutable value object; mutable fields would break thread-safety guarantees");

    // =========================================================================
    // Cycle detection
    // =========================================================================

    /**
     * No dependency cycles between the major architectural layers: runner,
     * extension, benchmark, and spi.
     *
     * The diagnostics and report packages intentionally share two support types:
     * - report.Violation (detectors return it; report formats it)
     * - diagnostics.IssueSeverity and diagnostics.SiteCapture (Violation records these)
     * This cross-dependency predates the SPI and is tracked as a known coupling.
     * The slice rule below covers all other sub-packages via a custom assignment
     * that groups diagnostics+report into one slice for cycle analysis, so their
     * mutual dependency does not trigger a false positive.
     */
    @ArchTest
    static final ArchRule no_package_cycles =
            slices()
                    .assignedFrom(new SliceAssignment() {
                        @Override
                        public SliceIdentifier getIdentifierOf(com.tngtech.archunit.core.domain.JavaClass javaClass) {
                            String pkg = javaClass.getPackageName();
                            // diagnostics and report share Violation/IssueSeverity/SiteCapture;
                            // treat them as one "detection-model" slice for cycle analysis.
                            if (pkg.startsWith("se.deversity.asynctest.diagnostics")
                                    || pkg.startsWith("se.deversity.asynctest.report")) {
                                return SliceIdentifier.of("detection-model");
                            }
                            // spi and spi.adapters are one slice (adapters is the glue layer).
                            if (pkg.startsWith("se.deversity.asynctest.spi")) {
                                return SliceIdentifier.of("spi");
                            }
                            // All other sub-packages get their own slice.
                            if (pkg.startsWith("se.deversity.asynctest.")) {
                                String remainder = pkg.substring("se.deversity.asynctest.".length());
                                String topSegment = remainder.contains(".")
                                        ? remainder.substring(0, remainder.indexOf('.'))
                                        : remainder;
                                return SliceIdentifier.of(topSegment);
                            }
                            return SliceIdentifier.ignore();
                        }

                        @Override
                        public String getDescription() {
                            return "architectural layer slices (detection-model, spi, runner, extension, benchmark)";
                        }
                    })
                    .should().beFreeOfCycles()
                    .because("architectural layers (runner, extension, benchmark, spi, detection-model) must not form dependency cycles");

    // =========================================================================
    // Module boundaries (see docs/analysis/modularization.md)
    // =========================================================================
    //
    // These rules pin the boundaries an extraction into Maven submodules depends on, so the
    // boundary is enforced before the directory move rather than discovered during it. Both
    // `agent` and `analysis` are leaves today: nothing in src/main imports them, and each is
    // the sole home of a heavyweight dependency the rest of the library should not pay for.

    /**
     * Nothing may depend on the agent. It is the Byte Buddy instrumentation entry point,
     * reached through {@code -javaagent:} or {@code selfAttach()}, never by a compile-time
     * reference. Keeping it a leaf is what allows it to become its own artifact, taking
     * byte-buddy and byte-buddy-agent off every consumer's default test classpath.
     */
    @ArchTest
    static final ArchRule nothing_depends_on_agent =
            noClasses()
                    .that().resideOutsideOfPackage("se.deversity.asynctest.agent..")
                    .should().dependOnClassesThat().resideInAPackage("se.deversity.asynctest.agent..")
                    .because("agent is an instrumentation leaf; a compile-time reference to it would "
                            + "drag byte-buddy into the core artifact");

    /**
     * Nothing may depend on the static pinning scanner, and it may not depend on anything
     * else in the library. It is a standalone ASM-based pre-scanner with no runtime coupling
     * to the execution engine.
     */
    @ArchTest
    static final ArchRule nothing_depends_on_analysis =
            noClasses()
                    .that().resideOutsideOfPackage("se.deversity.asynctest.analysis..")
                    .should().dependOnClassesThat().resideInAPackage("se.deversity.asynctest.analysis..")
                    .because("analysis is a standalone pre-scanner; nothing in the run path may reference it");

    // The other direction — analysis depending on nothing in the library — is no longer asserted
    // here, and does not need to be. async-test-analysis declares no dependency on async-test-lib,
    // so its classes cannot see them at compile time; the reactor enforces what an ArchUnit rule
    // could only describe. (Asserting it from this module would also be vacuous: the analysis
    // classes are not on this module's classpath, and ArchUnit fails a rule whose selection is
    // empty.)

    /** Byte Buddy is the agent's dependency alone. */
    @ArchTest
    static final ArchRule bytebuddy_is_confined_to_the_agent =
            noClasses()
                    .that().resideOutsideOfPackage("se.deversity.asynctest.agent..")
                    .should().dependOnClassesThat().resideInAnyPackage("net.bytebuddy..")
                    .because("byte-buddy belongs to the agent artifact, not the core one");

    /** ASM is the pinning scanner's dependency alone. */
    @ArchTest
    static final ArchRule asm_is_confined_to_analysis =
            noClasses()
                    .that().resideOutsideOfPackage("se.deversity.asynctest.analysis..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.objectweb.asm..")
                    .because("asm belongs to the analysis artifact, not the core one");
}
