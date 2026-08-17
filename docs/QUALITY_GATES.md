# Quality Gates

Everything that must stay green, plus the build quirks that trip up newcomers — human or agent.

On Windows/PowerShell, quote every `-D` argument (`"-Dlicense.mock.mode=true"`) or Maven parses it
as a lifecycle phase.

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
[JVM-global vs instance state](#jvm-global-vs-instance-state)). Integration tests drive the engine
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
> One consequence of the newer engine: `AvoidCatchingThrowable` is no longer a rule in PMD 7.26.0's
> quickstart ruleset, and `AvoidCatchingGenericException` reports the `catch (Throwable)` sites
> instead. `pmd-ruleset.xml` excluded both names for a while; excluding the retired one produced
> only a PMD warning ("Exclude pattern 'AvoidCatchingThrowable' did not match any rule in ruleset
> 'rulesets/java/quickstart.xml'") and it has been removed. The six sites the surviving rule covers,
> five in `ConcurrencyRunner` and one in `AsyncTestAgent.selfAttach`, are argued in the ruleset.

## Static analysis and API gates

`mvn verify` runs Checkstyle, PMD, SpotBugs (with find-sec-bugs), Error Prone, NullAway, JaCoCo
thresholds and japicmp — all of which fail the build.

- **Checkstyle** fails on warnings.
- **PMD** flags `LooseCoupling` (declare `ConcurrentMap`, not `ConcurrentHashMap`) and
  `UnusedPrivateField`.
- **SpotBugs** runs at Max effort / Low threshold and flags repeated `path.getParent()` null paths.
- **find-sec-bugs** adds 121 security detectors inside the SpotBugs run (see below).
- **Error Prone** covers main sources only; nine checks are promoted from advisory warning to
  build-failing `ERROR` (see below) — everything else Error Prone finds still prints as a warning
  but does not fail the build.
- **NullAway** gates nullness on main sources, as an Error Prone check (see below).
- **JaCoCo** requires line ≥ 70% and branch ≥ 65%.
- **japicmp** breaks the build on binary-incompatible API changes against the baseline pinned in
  `async-test-lib/pom.xml` (`<oldVersion>`, currently **1.9.1**). That baseline is only as good as
  the last person who bumped it: it sat at 1.6.0 while 1.7.0 through 1.9.1 shipped, so for six
  releases everything added after 1.6.0 was outside the comparison and could have been broken
  without the gate noticing. Re-pinning it is a step in [RELEASE.md](RELEASE.md#2-bump-the-version).
- **ArchUnit** tests enforce package structure and the module boundaries from within the suite.

### find-sec-bugs

Runs as a SpotBugs plugin rather than a separate gate — one `<plugins>` entry under
`spotbugs-maven-plugin`, mirrored in `build.gradle.kts` via the `spotbugsPlugins` configuration,
sharing the same `spotbugs-exclude.xml`. It adds 121 detectors covering 144 bug patterns (counted
from the plugin jar's own `findbugs.xml`, not from its README). CodeQL already
covers similar ground from a different angle, and two independent security analysers disagreeing is
information rather than duplication.

**It found 41 things and none of them were bugs.** That is the honest result, and the triage is
worth reading before adding an exclusion of your own, because the reasoning is the deliverable:

| Pattern | Count | Verdict |
|---|---|---|
| `CRLF_INJECTION_LOGS` | 24 | Threat model does not apply — the log input is the developer's own test and thread names, written to their own build log. |
| `POTENTIAL_XML_INJECTION` | 11 | 9 are detector `toString()` building plain text with no XML anywhere; 2 are `JUnitXmlReportListener.writeXml`, which does escape (`xmlEscape` for attributes, `cdataEscape` splitting the `]]>` terminator). |
| `PATH_TRAVERSAL_IN` | 2 | The "user input" is the developer's own system property naming where their build writes its report. No privilege boundary. |
| `PREDICTABLE_RANDOM` | 1 | Required, not a defect: the runner's replay seed is printed so a failing run can be reproduced with `@AsyncTest(replaySeed = N)`. |
| `OBJECT_DESERIALIZATION` | 1 | A genuine CWE-502 sink, already hardened — `readStore` installs a strict `ObjectInputFilter` allow-list ending in `!*`. |
| `IMPROPER_UNICODE` | 1 | `toLowerCase(Locale.ROOT)` is already the mitigation the rule asks for. |
| `INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE` | 1 | Byte Buddy's `onError` saying which class it could not instrument is the method's purpose. |

**Exclusions are scoped deliberately.** The deserialization one names a single class *and method*,
the XML ones name the writer method, the random one names one class — so a *new* instance of the
same pattern anywhere else still fails the build. Only `CRLF_INJECTION_LOGS`, where the reasoning
holds for every call site in a test library, is excluded by pattern alone.

The gate was verified live rather than assumed: adding
`new java.util.Random().nextInt()` to a class outside the exclusion scope makes both builds fail
(Maven `PREDICTABLE_RANDOM`, Gradle `SECPR`), which confirms both that the plugin loads and that the
scoping works.

Both builds run these gates in CI, but only since `gradle-tests.yml` gained an explicit
`./gradlew pmdMain spotbugsMain` step. Gradle attaches those tasks to `check`, and the job ran
`test`, `publishToMavenLocal` and `assemble`, none of which reach `check`, so for as long as the
Gradle configuration existed, no workflow had ever executed it. Maven ran the same rules over the
same sources throughout, so this closed a duplicate-coverage hole rather than an unchecked one.

### NullAway

Nullness is the one defect class the other analysers do not check, and this codebase is built out
of nullable references: every one of the 139 detectors is `cfg.detectX ? new XDetector() : null`, so
a `Phase1DetectorSet` field, a `DetectorRegistry` field and every accessor that reaches one is null
whenever its flag is off. Whether each read site guards for that was, until now, enforced by
convention.

NullAway runs as an Error Prone check on main sources, configured in the parent POM:

```xml
<arg>-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=se.deversity.asynctest ...</arg>
```

The same `<arg>` carries the other eight promoted checks (below) — trimmed here since NullAway is the one
with a migration story worth telling; the full line is in the parent POM.

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

### The other eight promoted checks

The README's Error Prone badge used to describe a single check (NullAway), while the other ~500
checks Error Prone ships with ran at their default severity — mostly `WARNING`, which javac prints
but does not fail the build on. A 2026-08 sweep found 65 live warnings across eight checks that had
never been promoted, fixed each, and promoted the checks so the badge's "passing" claim covers them
too:

- **`ReferenceEquality`** (7 sites) — every one was an already-intentional identity comparison
  (a sentinel object, an identity-keyed cache entry) that already carried a `PMD.CompareObjectsWithEquals`
  or `SpotBugs` suppression with a justification comment; Error Prone's own name for the same thing
  just wasn't in the list. Added `"ReferenceEquality"` alongside the existing suppression at each site.
- **`StringConcatToTextBlock`** (52 sites) — cosmetic: multi-line `"a\n" + "b\n" + "c"` concatenations
  in detector `toString()` reports, converted to Java 21 text blocks. Every conversion was verified
  byte-identical to the original via `String.equals()` before landing, since these strings are
  diagnostic report text some tests check with `.contains(...)`.
- **`ExposedPrivateType`** (1) — `ThreadPoolDeadlockDetector.PoolDeadlockRisk`'s constructor took a
  `List<NestedSubmissionEvent>`, and `NestedSubmissionEvent` is `private`. Left package-private and
  suppressed rather than narrowed to `private`: narrowing broke `japicmp` with
  `CLASS_NOW_NOT_EXTENDABLE` (package-private constructors are visible to same-package code,
  `private` isn't), for no real reduction in reachable API surface — `NestedSubmissionEvent` was
  already unnameable outside this file regardless of the constructor's own visibility.
- **`UnusedVariable`** (1) — `LockOrderValidator.LockSequence.threadId` was written in the
  constructor and never read; the outer `Map<Long, LockSequence>` already keys by thread id. Removed
  the field; the constructor reference `LockSequence::new` became a lambda since the field's removal
  left no constructor parameter for it to bind to.
- **`StatementSwitchToExpressionSwitch`**, **`InlineMeSuggester`**, **`PatternMatchingInstanceof`**,
  **`StringSplitter`** (1 each) — mechanical modernizations or suppressions with the same "already
  intentional, just not annotated for this specific tool" shape as `ReferenceEquality` above.

Everything Error Prone finds outside these nine checks (NullAway plus the eight above) still prints
as a warning during `mvn compile` and does not fail the build — the badge measures the promoted set,
not the tool's full catalog.

## What the E2E check actually covers

`E2E Tests` is a summary job, not a test run — it reads the four `needs.*.result` values from
`e2e-tests.yml` and fails if any is `failure` or `cancelled`. It exists so there is one stable
required check whose name does not change with which legs ran. Seeing it pass in three seconds is
it working, not it skipping.

Underneath, which legs run depends on the event:

| Leg | Runs on | Notes |
|---|---|---|
| Consumer Fixture (JDK 21, 25) | every PR and push | Resolves the built artifact from a local repo and drives it through the public API only, one `@AsyncTest` fixture per `DetectorType` |
| Examples Shard (PR) | PRs that change `examples/**` **or** library sources | See below |
| Examples Reactor | push to `main`/`develop`, and nightly | All 127 example projects, four shards |

**The PR filter used to ask the wrong question.** It watched `examples/**` only, so it answered
"did you edit an example?" when what matters is "could you have broken the examples?" — and the 127
examples all consume the built artifact. A library-only PR therefore ran zero of them and went
green, with any breakage surfacing after merge or overnight.

`examples-detect` now also watches `async-test-*/src/main/**` and the root build files. A library
change runs a deterministic every-4th sample (32 of 127) rather than the full reactor: enough to
catch a systemic break at PR time, cheap enough to afford on every library PR. A PR that changes
both gets the union, deduplicated. `gradle-tests.yml` mirrors this exactly.

The sample is a sample, not coverage — the full reactor on push and nightly is still what proves
all 127 build. This only moves discovery of the common failure earlier.

## Mutation testing

PITest gates the mutation score at **≥ 74%** (measured 75.4%; the margin absorbs run-to-run
`TIMED_OUT` jitter). It is never bound to `verify`; `mutation.yml` runs it weekly (Sundays
02:00 UTC) and on demand from the Actions tab, and that job fails below the threshold. Until
2026-08-15 nothing in CI ran it at all, while `CONTRIBUTING.md` said it ran on a schedule.

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

`async-test-lib/src/fuzz/java/se/deversity/asynctest/fuzz/` holds standalone Jazzer harnesses,
excluded from pitest. They exercise parsing and config surfaces rather than the concurrency engine.
`fuzzing.yml` runs them every Monday against the Jazzer CLI.

That schedule first fuzzed anything on 2026-08-10. Every scheduled run from at least 2026-06-29
until then failed without executing the harness, behind three stacked defects, each hidden by the
one in front of it:

1. harden-runner's egress allowlist omitted `release-assets.githubusercontent.com`, the host GitHub
   serves release assets from, so the Jazzer download died on `curl: (7) Failed to connect`.
2. `tar -xzf jazzer_linux.tar.gz jazzer` extracted only the launcher. That binary is a thin driver
   that loads `jazzer_standalone.jar` from its own directory, so it aborted at startup.
3. `-artifact_prefix` accepts only a directory that already exists. libFuzzer will not create one,
   and aborts with `The required directory "fuzzing-findings/" does not exist`.

What let all three survive was `continue-on-error: true` on the fuzz step. It was meant to keep a
fuzzing *finding* from failing the build, but it also swallowed Jazzer failing to *start*. That
blanket tolerance is gone. Jazzer's exit code is still ignored, but the step now requires
libFuzzer's `INITED` line in the log, which appears only after the target class has loaded and the
initial corpus has run. A finding stays an artifact; a toolchain or classpath failure is a red job.
The assertion caught defects 2 and 3 on its first two runs.

The first genuine run executed 7,382,051 inputs in 121 s (61,008 exec/s), grew coverage from 29 to
699 features, and found no defect in `AsyncTestConfig.Builder`. It instruments the config surface
itself — `AsyncTestConfig`, `AsyncTestConfig$Builder`, `FailOn`, `DetectorType` — not merely the
harness.

They sit in `src/fuzz/java` rather than `src/test/java` for one reason: OpenSSF Scorecard's fuzzing
check discards every path containing `/src/test/` before it scans for the
`com.code_intelligence.jazzer.api.FuzzedDataProvider` import, so a harness kept under the test root
is invisible to it and the repo scores 0 on Fuzzing however much fuzzing actually happens. The
filter is in Scorecard's `checks/fileparser/listing.go`, in `isTestdataFile`.

The directory is wired in as an extra test-source root twice, once per build, and the two must stay
in agreement: `build-helper-maven-plugin`'s `add-fuzz-test-source` execution in
`async-test-lib/pom.xml`, and the `sourceSets` block in the root `build.gradle.kts`. Both compile
the harnesses into the ordinary test output, so the Jazzer classpath stays
`async-test-lib/target/test-classes`.

## Guardrail and review lanes

The gates that keep the agent-facing layer honest, and the review lanes that read a PR before a
human does. All added 2026-08-15 after a self-audit against the *Vibe Architecture* health scorecard
([analysis/vibe-architecture-scorecard.md](analysis/vibe-architecture-scorecard.md)); each one turned
a property that held by habit into one that fails a build.

| Lane | Workflow | Runs on | Fails when |
|---|---|---|---|
| Guardrail drift | `guardrails.yml` / `guardrail-drift` | push, PR | a clean `test-compile` regenerates any committed guardrail file (`CLAUDE.md`, `GEMINI.md`, `.claude/rules`, `.gemini/rules`, `.claudeignore`, `.vibetags-*`) differently from what is committed. Fix: build, commit the regenerated files; never edit inside the markers |
| Locked files | `guardrails.yml` / `locked-files` | PR | the diff touches an `@AILocked` element (today `DetectorType`, lines 13–351). A maintainer who has read the wiring applies the `lock-override` label; the guard then reports instead of failing. The label is the escalation path, applied by the person merging, never by the diff |
| Diagram drift | `guardrails.yml` / `diagrams` | push, PR | the code-karta SVGs regenerate with a different set of node titles than the committed ones (`tools/diagram-structure.sh`). Fix: `sh tools/generate-architecture-diagrams.sh`, commit; the failing run attaches the fresh SVGs |
| Core flows (BDD) | `CoreFlowsBddTest` (`-P e2e`, so every CI leg) | every CI build | a scenario in `async-test-lib/src/test/resources/features/core-flows.feature` has no binding, a binding has no scenario, or a scenario's assertions fail against the real engine. Five scenarios: body runs N x M times, a finding fails on `failOn = HIGH`, report-only stays green, an excluded detector reports nothing, `invocations = 0` is refused |
| Docs routing | `DocsIndexCoverageTest` (default `mvn test`) | every build | a document under `docs/` is not linked from `docs/INDEX.md`, or a relative link in the doc set does not resolve |
| Workflow input hygiene | `WorkflowInputHygieneTest` (default `mvn test`) | every build | a workflow interpolates untrusted event text (issue or PR title/body, comment body, commit message, branch name) into a `run:`, `script:` or `prompt:` block. Pass it through `env:` instead |
| Allocation budget | `RunnerAllocationBudgetTest` (`-P e2e`, every CI leg) | every CI build | one all-detector `@AsyncTest` run allocates more than 80,000 bytes per body execution (3.0x the 25,985 to 26,599 measured on 2026-08-15; re-derive the same way, red first) |
| Keygen contract | `KeygenValidateKeyContractTest` (default `mvn test`) | every build | the request to a loopback stand-in stops matching the recorded validate-key contract (path, `POST`, `meta.key`, `meta.scope.user`, `meta.scope.product`), or a `meta.valid=false` answer, or a body without `meta.valid`, admits a run. `LicenseGuardLemonSqueezyTest` is the LemonSqueezy twin |
| Load-test trend | `load-tests.yml` + `load-tests/tools/compare-baseline.sh` | push, PR, nightly 04:00 UTC | never; prints `::warning::` when a fresh sweep row exceeds 1.5x (median ms) or 2.0x (all-detector KB) of the newest committed baseline. A trend line, cross-machine, so warn-only by design |
| Mutation | `mutation.yml` | Sundays, dispatch | the PIT score drops below the pom's `mutationThreshold` (74) |
| Fuzzing | `fuzzing.yml` | Mondays, dispatch, and PRs touching the config surface or the harness | Jazzer finds an input that breaks `AsyncTestConfig.Builder`, or never reaches `INITED` |
| Inquisitor | `inquisitor.yml` | PR | the adversarial reviewer (`.github/INQUISITOR.md`, model pinned in `.github/MODEL-ROSTER.md`) writes a violation against the committed law. Optional lane: skips loudly without `ANTHROPIC_API_KEY`, and the repository does not carry that secret by decision (2026-08-15: Copilot Free is the AI lane; nothing blocks on paid tokens) |
| Copilot review | `copilot-review.yml` | PR opened | never; it requests a GitHub Copilot review, verifies the request was recorded, and reports SKIPPED otherwise (it did on PR #262: no review request was recorded, so Copilot code review is not active for this account yet). Advisory by design |
| Instruction evals | `instruction-evals.yml` | PR touching the instruction files, dispatch | never; runs `evals/` on the Copilot CLI (`COPILOT_GITHUB_TOKEN` secret, the maintainer's Copilot Free quota) and prints the adherence table plus a `::warning::` per rule below its floor. Advisory by decision: each measured rule has an enforcing gate behind it, and those block. Skips loudly without the secret or on exhausted quota. First measured run 2026-08-15, locally: `evals/README.md` |

**Skipped is not passed.** Every lane that can lack credit (Inquisitor, Copilot, evals) says
SKIPPED in its step summary when it does; a green job with a SKIPPED summary is a job that did not
run, and the required-checks list only contains lanes that cannot skip. Since 2026-08-15 the
required checks on `main` are: `Build Maven Project (21)`, `Build Maven Project (25)`,
`Gradle Test Suite (21)`, `Test Suite (21, ubuntu-latest)`, `Guardrail Drift`,
`Locked Files Guard`, `Architecture Diagram Drift`. Branch protection is repository
configuration, not a file here; this sentence is the record of what was set and why.

**AI lanes run on Copilot Free, by decision.** No Anthropic key is required or configured. The
Inquisitor workflow stays in the repository as the law-enforcing lane for anyone who adds
`ANTHROPIC_API_KEY`; the eval bank runs on the Copilot CLI; the Copilot review lane requests a
review from GitHub. All three skip loudly on missing credit and none of them can block a merge.

**Why "propose, do not install" for dependencies.** `dependency-review.yml` fails on high-severity
CVEs and denied licences, Dependabot owns bumps, and `docs/DEPENDENCIES.md` explains every
coordinate's reach into a consumer's classpath. A coordinate that appears in a build file without
that conversation bypasses all three; the PR template asks for it, the Inquisitor reads for it.

**Why untrusted context is a standing rule.** No workflow feeds issue or comment text to an agent
today, so the exposure is zero by absence. The rule in `CLAUDE.md` exists so that stays true by
policy when one is added: text read from outside the repository is data an agent reasons about, not
an instruction it follows.
