# Quality Gates

Everything that must stay green, plus the build quirks that trip up newcomers — human or agent.

On Windows/PowerShell, quote every `-D` argument (`"-Dlicense.mock.mode=true"`) or Maven parses it
as a lifecycle phase.

## Test suite conventions

`mvn test` runs the full suite (~1600 tests, ~5 min). `-Dtest=<Class>` does filter normally — add
`-DfailIfNoSpecifiedTests=false` when scoping to a module that has no match.

Surefire forks a fresh JVM per class (`reuseForks=false`), which masks cross-test JVM contamination;
pitest's shared JVM surfaces it, so tests must not assume a pristine JVM (see
[JVM-global vs instance state](#jvm-global-vs-instance-state)). Integration tests drive the engine
with JUnit `EngineTestKit` against nested dummy classes annotated with `@AsyncTest` — the pattern
throughout `src/test/java`.

**There is deliberately no flaky-test rerun.** The parent POM omits `<rerunFailingTestsCount>` on
purpose: in this library a test that fails intermittently is reporting a real detector finding, not
infrastructure noise. Auto-rerunning would mask the exact signal the project exists to catch.

## Build with JDK 21 or 25, not 26

> **Resolved: the PMD engine is now pinned, and JDK 26 no longer trips the gate.**
>
> PMD 7.17 could not resolve types from JDK 26 class files. It fell back to a name-based heuristic
> and reported bogus `LooseCoupling` violations — including, memorably, flagging
> `Map<String, String>` as *"an implementation type; use the interface instead"*, which is the
> interface it is asking for.
>
> maven-pmd-plugin 3.28.0 still ships 7.17.0 as its default, so the parent POM now pins
> `<pmd.version>7.26.0</pmd.version>` and overrides `pmd-core` / `pmd-java` in the plugin's
> `<dependencies>`. `build.gradle.kts` sets the same number via `extra["pmdVersion"]`.
>
> Same commit, same plugin, same JDK 26 — only the engine differs:
>
> ```
> mvn -pl async-test-lib pmd:check -Dpmd.version=7.17.0  →  243 violations, BUILD FAILURE
> mvn -pl async-test-lib pmd:check -Dpmd.version=7.26.0  →    0 violations, BUILD SUCCESS
> ```
>
> `mvn verify -DskipTests` (PMD, SpotBugs, Checkstyle and Error Prone) also passes on JDK 26 with
> the pin in place. **The test suite has not been exercised on JDK 26**, so CI stays on 21 and 25
> and that remains the supported pair — but a red PMD gate is no longer explained by the toolchain,
> and a failure here should be read as a real finding.
>
> Worth keeping the history because the old failure was convincing: it looked exactly like a
> repository-wide code-quality problem, and the obvious remedy — a mechanical sweep replacing
> `ConcurrentHashMap` declarations with `ConcurrentMap` across ~120 files — was both large and
> completely wrong, since the flagged declarations were already the interface. The tell was that the
> reported line did not contain the implementation type the message named.
>
> One consequence of the newer engine: PMD 7.26.0 widened `AvoidCatchingGenericException` to also
> report `catch (Throwable)`, duplicating `AvoidCatchingThrowable`, which `pmd-ruleset.xml` already
> excludes for the `ConcurrencyRunner` sites. Both rules are now excluded there, with the reasoning
> recorded in the ruleset.

## Static analysis and API gates

`mvn verify` runs Checkstyle, PMD, SpotBugs, Error Prone, NullAway, JaCoCo thresholds and japicmp —
all of which fail the build.

- **Checkstyle** fails on warnings.
- **PMD** flags `LooseCoupling` (declare `ConcurrentMap`, not `ConcurrentHashMap`) and
  `UnusedPrivateField`.
- **SpotBugs** runs at Max effort / Low threshold and flags repeated `path.getParent()` null paths.
- **Error Prone** covers main sources only.
- **NullAway** gates nullness on main sources, as an Error Prone check (see below).
- **JaCoCo** requires line ≥ 70% and branch ≥ 65%.
- **japicmp** breaks the build on binary-incompatible API changes against the last release.
- **ArchUnit** tests enforce package structure and the module boundaries from within the suite.

### NullAway

Nullness is the one defect class the other analysers do not check, and this codebase is built out
of nullable references: every one of the 127 detectors is `cfg.detectX ? new XDetector() : null`, so
a `Phase1DetectorSet` field, a `DetectorRegistry` field and every accessor that reaches one is null
whenever its flag is off. Whether each read site guards for that was, until now, enforced by
convention.

NullAway runs as an Error Prone check on main sources, configured in the parent POM:

```xml
<arg>-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=se.deversity.asynctest</arg>
```

`build.gradle.kts` sets the same two options through `options.errorprone`. `@Nullable` comes from
JSpecify (`org.jspecify:jspecify`), `provided` scope: the annotation has CLASS retention, so it
never reaches a consumer's runtime classpath, and adding one is binary-compatible — japicmp agrees.

**Placement matters.** JSpecify's `@Nullable` is `TYPE_USE`, so it binds to the type immediately to
its right. For an array that is the difference between two different claims:

```java
StackTraceElement @Nullable [] stack;   // the array may be absent      ← what these APIs mean
@Nullable StackTraceElement[] stack;    // the elements may be null
```

**What the first clean run found.** 119 findings across 51 files. Most were contracts that were
already true and simply unwritten — a `@Nullable` field, a nullable return, a parameter that
callers already passed `null` to. Eleven were `dereferenced expression is @Nullable`, and five of
those were live NPEs: `CountDownLatchDetector`, `CyclicBarrierDetector`, `ExchangerDetector`,
`PhaserDetector` and `ReentrantLockDetector` all looked a subject up in a registry that a
`record*`-without-`register*` call never populated, then dereferenced the result inside
`toString()`. The NPE never reached anyone, which is what made it survive: `DetectorRegistry.ifIssue`
catches `RuntimeException` around `report.toString()` so one bad detector cannot discard the sweep,
so the detector silently reported nothing and the concurrency bug the user instrumented for went
unreported. `UnregisteredSubjectReportTest` pins the fix.

**When NullAway is wrong.** It reasons per method and cannot see an invariant that holds across
one. Two shapes recur here, and both are cheap to state explicitly rather than suppress:

- A value is non-null because an earlier branch guaranteed it (`AsyncTestConfig` reaching
  `preset.enabled()` only on the non-`isAll()` path). Use `Objects.requireNonNull` with a message
  that says *why* — it documents the invariant and fails loudly if it ever stops holding.
- A framework callback initialises a field before any other callback runs (ASM calls
  `ClassVisitor.visit` before `visitMethod`). Annotate the field `@Nullable` and handle the absent
  case; the handler is unreachable, and saying so in a comment is more honest than asserting the
  contract in a suppression.

Neither `@SuppressWarnings("NullAway")` nor a widened `AnnotatedPackages` exclusion appears in the
tree, and adding one should be argued for rather than assumed.

## Mutation testing

PITest gates the mutation score at **≥ 74%** (measured 75.4%; the margin absorbs run-to-run
`TIMED_OUT` jitter). Run on demand — it is never bound to `verify`.

```bash
mvn org.pitest:pitest-maven:mutationCoverage                 # full run, ~1h20m
mvn org.pitest:pitest-maven:mutationCoverage -DtargetClasses=se.deversity.asynctest.diagnostics.Shared*
```

Quirks: `parseSurefireArgLine=false` is required because JaCoCo's late-bound `@{argLine}` crashes
pitest, so the needed JVM flags are duplicated in the plugin's `jvmArgs`. Reports are
non-timestamped, so each run overwrites `target/pit-reports/`.

Surviving mutants are dominated by diagnostic output and timing-heuristic detectors — killing them
would require flaky timing-forced tests, so they are deliberately tolerated. Mutation analysis has
caught real wiring bugs: see the excludes-branch gap in
[configuration-resolution.md](architecture/configuration-resolution.md) and the deadlock baselining
below.

### JVM-global vs instance state

Detectors that query JVM-wide facilities (`ThreadMXBean`, thread dumps) must baseline pre-existing
state at construction so they only report what the monitored test caused. `DeadlockDetector.analyze()`
excludes thread ids already deadlocked when the detector was created — otherwise leaked deadlocked
threads from earlier tests in a shared JVM cause false positives. Found by mutation testing. Its
static `hasDeadlock()` stays JVM-wide by design.

The same section of the map holds one deliberate piece of JVM-global state going the other way:
`UncommittedChangesDetector` caches its `git status` result statically, because forking a process
per test method cost 99% of the analysis sweep. Any test that changes the working tree and then
expects the detector to notice must call the package-private `invalidateCache()` first, the way
`UncommittedChangesDetectorTest` does in its `@BeforeEach`.

## License guard

`runner/LicenseGuard.check(config)` runs once per config fingerprint per JVM and throws
`SecurityException` on denial. Security-critical: never weaken the check.
`ConcurrentHashMap.computeIfAbsent` gives at-most-once gate execution per fingerprint; denied
fingerprints consistently throw.

Mock mode — `license.mock.mode=true` (the POM default for local tests) or auto-mock in CI (`CI` /
`GITHUB_ACTIONS` env) — grants without a key. Mechanics in
[architecture/runtime-guarantees.md](architecture/runtime-guarantees.md).

Related hardening: `benchmark/BenchmarkComparator.readStore` deserializes with a strict
`ObjectInputFilter` allow-list ending in `!*` — never widen it (CWE-502).

## Benchmarking

Opt-in invocation timing with baseline regression classification (STABLE / REGRESSION /
IMPROVEMENT), gated by the `enableBenchmarking` flag.

`benchmark/BenchmarkRecorder` times every round — its hot path is allocation-free, no autoboxing,
lock-free in the common case — and persists baselines compared by `BenchmarkComparator`. The
`[BENCHMARK]` log lines and the `benchmark.invocation.times` metric are consumed by dashboards;
never rename or remove them without flagging the dashboard change. Full guide:
[BENCHMARKING.md](BENCHMARKING.md).

## Fuzzing

`async-test-lib/src/test/java/se/deversity/asynctest/fuzz/` holds standalone Jazzer harnesses,
excluded from pitest. They exercise parsing and config surfaces rather than the concurrency engine.
