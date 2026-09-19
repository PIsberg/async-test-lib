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

The same switch is on wherever the detectors are measured from outside this module: every
corpus-eval lane, `consumer-fixture` and `consumer-fixture-langs` (Maven and Gradle), the examples
Gradle build, and the `mvn -f examples/pom.xml` commands in `e2e-tests.yml` (the example poms do
not carry it, because they are copy-paste material; Surefire forwards the `-D` to the forked JVM).
Without it #605's crash in corpus lane one read as a silent detector and both corpus jobs stayed
green (#612). One place is lenient on purpose: `example-demos.yml`, where a demonstration that
fails is the expected outcome, so a crash promoted to a failure would look like a demo that fired.
`StrictDetectorsInDownstreamBuildsTest` fails if any of these loses the switch or the demo audit
gains it.

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
