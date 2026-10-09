# Test suite conventions

Part of the [Quality Gates guide](../QUALITY_GATES.md).

## Test suite conventions

`mvn test` runs the local tier: the plain-JUnit tests, with the `@Tag("e2e")` classes excluded via
the `surefire.excludedGroups` property. The e2e tier is the 22 `EngineTestKit` classes plus the two
agent end-to-end classes; measured on 2026-08-05 they were 48.5% of in-test time for 4.9% of the
tests. The `e2e` profile clears the exclusion and auto-activates on the `CI` env var, so every
workflow still runs the full suite (~1700 tests); locally, `mvn test -P e2e`. `E2eTagGuardTest`
pins the tag set in both drift directions.

The jacoco `check` gate rides the same switch: without the e2e tier the coverage floor would fail
over `runner/` and `extension/` code that is covered in CI, so the gate is skipped (jacoco logs the
skip) unless the `e2e` profile runs. A skipped gate is not a passed gate; CI always runs it.

`-Dtest=<Class>` does filter normally — add `-DfailIfNoSpecifiedTests=false` when scoping to a
module that has no match, and `-P e2e` when the class is tagged `@E2E`.

Surefire forks a fresh JVM per class (`reuseForks=false`), which masks cross-test JVM contamination;
pitest's shared JVM surfaces it, so tests must not assume a pristine JVM (see
[JVM-global vs instance state](mutation-fuzzing-benchmarks.md#jvm-global-vs-instance-state)). Integration tests drive the engine
with JUnit `EngineTestKit` against nested dummy classes annotated with `@AsyncTest` — the pattern
throughout `src/test/java`.

**There is deliberately no flaky-test rerun.** The parent POM omits `<rerunFailingTestsCount>` on
purpose: in this library a test that fails intermittently is reporting a real detector finding, not
infrastructure noise. Auto-rerunning would mask the exact signal the project exists to catch.

**Strict detector mode is on for our own build.** Both analysis sweeps catch around each detector
so that one failure cannot discard the findings already collected — right for a consumer, wrong
here, because a detector that throws reports nothing and *nothing reported is indistinguishable
from a clean run*. Five detectors shipped for several releases dereferencing a registry miss inside
`toString()`, and the only trace was a stderr line nobody read.

`async-test.strict-detectors=true` (set in the surefire `systemPropertyVariables` and in
`build.gradle.kts`) promotes that swallowed failure to an `AssertionError`. Consumers keep the
containment. Verified by breaking a detector on purpose: the same `IllegalStateException` in
`SharedRandomDetector.analyze()` gives `BUILD SUCCESS` with the flag off and `BUILD FAILURE` with
it on. `DetectorSweepResilienceTest` pins both halves — the containment (with the flag cleared for
the duration) and the promotion. Mechanics in `se.deversity.asynctest.DetectorFailurePolicy`.

The flag also fails a built-in report that has issues and an empty `structuredViolations` list,
checked where each structured detector returns its report (`DetectorFailurePolicy.checkedReport`,
#829) and again once per report in `DetectorRegistry.ifIssue` (#802). Without it the `failOn` gate
reads that finding's severity from its text, and only a hand-written driver in
`StructuredViolationCoverageTest` could notice. Because the check sits where the report is built, a
detector's own unit tests that call `analyze()` drive it, not only the tests that fire the detector
through the registry. The no-context `Phase1DetectorSet.printReports()` reads the list for the
severity it hands listeners, and is covered by the same call. Until 1.13.0 the SPI bridge
`LegacyDetectorAdapter` made the same check on its own path (#841); 1.13.0 removed that path (#922).
With the flag off the check returns before looking at the report and writes nothing.
`StructuredFindingsStrictModeTest` pins both halves.

The same switch is on wherever the detectors are measured from outside this module: every
corpus-eval lane, `consumer-fixture` and `consumer-fixture-langs` (Maven and Gradle), the examples
Gradle build, and the `mvn -f examples/pom.xml` commands in `e2e-tests.yml` (the example poms do
not carry it, because they are copy-paste material; Surefire forwards the `-D` to the forked JVM).
Without it #605's crash in corpus lane one read as a silent detector and both corpus jobs stayed
green (#612). One place is lenient on purpose: `example-demos.yml`, where a demonstration that
fails is the expected outcome, so a crash promoted to a failure would look like a demo that fired.
`StrictDetectorsInDownstreamBuildsTest` fails if any of these loses the switch or the demo audit
gains it.

## Skipped tests are a baseline, not a count

A skip is a pass to Maven and Gradle, and the only trace is a count in a log line. The real-licence
E2E tests skipped on every CI leg, and on the operator machine, for about two months while guarding
the only real-grant path (#901). So every job that runs a suite now checks its skips against a
committed list: `.github/scripts/skipped_tests_gate.py` reads the job's JUnit XML after the tests
and fails when a test skips that `.github/skipped-tests.txt` does not list, or a listed one ran or
never appeared (#905). Both directions matter: a new skip hides a test, and a stale line hides a
reason that no longer holds.

Each line is `<class>#<method> <contexts> # <reason>`. A context is a pattern over the job context
the workflow passes (`tests/jdk21/ubuntu-latest`, `gradle/jdk21`, `corpus/jdk25`, `license-e2e`,
...) joined to the report directory, so the Gatherer skips are allowed on JDK 21 legs only and lane
five's disabled JDK rows only in lane five's reports. A job that runs on an OS matrix names the OS in
its context, so a Windows-only test can be required to skip on Linux and macOS and to run on
Windows; `SkippedTestsGateWiringTest` requires it, since the context without the OS kept all three
Windows legs red from #927 on, unseen behind `continue-on-error` (#950). A method ending in `?` may skip but need not:
that is for a test whose own assumption depends on timing, such as the Gatherer test that skips
when the JDK keeps a parallel stream's integration on one thread, which a loaded JDK 26 leg did on
this gate's first CI run. A job with no line in scope tolerates no skip, which
is how `license-e2e.yml` and `OS-Sensitive Tests` are checked. A job that finds no report fails: an
empty run must not read as a clean one. The baseline was measured from main's CI on 2026-10-05.

`SkippedTestsGateWiringTest` pins the wiring: the listed jobs in `tests.yml`, `corpus.yml`,
`e2e-tests.yml`, `license-e2e.yml`, `gradle-tests.yml` and `load-tests.yml` must call the gate, and any other job
whose commands run a suite must be listed as exempt with a reason. The examples reactors are
exempt because their skips are the `@Disabled` demonstrations, which
[examples-and-demos.md](examples-and-demos.md) gates. `load-tests.yml` is gated with no line: nothing
in `load-tests/` can skip, so any skip there is new (#908). The script's `--self-test` covers both directions on synthetic reports and
parses the real baseline; it was also run against main's real corpus reports, where it passes with
the baseline and names all 46 lane-five skips without it.

## Thread-safety claims are tested concurrently

An `@AIThreadSafe` note is a specific claim ("at-most-once gate execution under contention"), so a
class that makes one needs a test that runs it on several threads at once and can fail when the
claim breaks. `ThreadSafetyClaimsAreTestedConcurrentlyTest` reads every main source file in the
three modules (the annotation is source-retained) and fails when a class carrying `@AIThreadSafe`
is not named by a test marked `@ConcurrencyTestFor(TheClass.class)`, when a marker names a class
that no longer makes the claim, or when a marked test runs no threads through `@AsyncTest` or a
`CyclicBarrier` (#906). The marker is explicit so that a test which merely mentions a class does
not count. The detectors are exempt as a package, because `@AsyncTest` feeds them by design and
`DetectorAccuracyEvalTest` and the corpus lanes run each one in both directions; any other
exemption goes in the test's `EXEMPT` map with its reason.

Why it exists: `LicenseGuard`'s only concurrency test asserted a `ConcurrentHashMap`'s size, which
holds even when the gate runs on every thread, and `LicenseValidationCache` had no concurrent test
until the first one found a shipped Windows defect (#904). Verified by deleting
`LicenseGuardGateOnceDogfoodTest`: the gate names `LicenseGuard`. `ConcurrencyRunnerCollisionDogfoodTest`
covers the runner's own claim, and goes red when the runner serializes its workers.

Naming a class and running it concurrently is not yet a test that fails when the class breaks, and
source cannot tell the difference. The weekly mutation run can: `.github/scripts/concurrency_test_kills.py`
runs PIT once per marked class, with the class as the only target and the tests marked for it as
the only tests (all of them together, so a second marker cannot silently replace the first, #925),
and fails when the share of mutants that test detects falls below the class's floor in
`.github/concurrency-kill-floors.txt` (#909). A full PIT run cannot answer it, because without the
full mutation matrix `mutations.xml` names only the first test to kill a mutant, and the slow
`@AsyncTest` tests are rarely first. Measured on 2026-10-05, the marked tests alone detect 61% of
`LicenseGuard`'s mutants, 42% of `LicenseValidationCache`'s, 38% of `ConcurrencyRunner`'s and 6% of
`AsyncTestContext`'s and `DetectorRegistry`'s, whose mutants sit mostly in per-detector accessors a
ThreadLocal test never reaches. Adding `RendezvousTest` beside `AsyncTestContextTest` on 2026-10-06
raised `AsyncTestContext` to 21% (109/512, 78 of the kills its own). Floors sit 5 to 10 points under those numbers. Verified by
weakening `LicenseGuardGateOnceDogfoodTest` so it no longer asserts that the provider was asked
once: `LicenseGuard` falls to 22% and the check names it. A scoped run scores below the pom's 76%
suite threshold by design, and that threshold is a POM literal that `-DmutationThreshold` cannot
lower, so the script judges the fresh report rather than Maven's exit code.

## License guard

`runner/LicenseGuard.check(config)` runs once per config fingerprint per JVM and throws
`SecurityException` on denial. Security-critical: never weaken the check.
`ConcurrentHashMap.computeIfAbsent` gives at-most-once gate execution per fingerprint; denied
fingerprints consistently throw.

Mock mode — `license.mock.mode=true` (the POM default for local tests) or auto-mock in CI (`CI` /
`GITHUB_ACTIONS` env) — grants without a key. Mechanics in
[architecture/runtime-guarantees.md](../architecture/runtime-guarantees.md).

Related hardening: `benchmark/BenchmarkComparator.readStore` deserializes with a strict
`ObjectInputFilter` allow-list ending in `!*` — never widen it (CWE-502).
