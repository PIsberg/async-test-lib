# CLAUDE.md

`@AsyncTest` is a JUnit 5 `@TestTemplate` that runs a test body on N threads for M rounds, forcing
them to collide on a `CyclicBarrier`, and reports what 135 detectors saw. This file is the map. It
is deliberately short — it loads on every session, so anything needed only sometimes lives behind a
link.

## Where to look

| If you need | Read |
|---|---|
| To use the library | [docs/INDEX.md](docs/INDEX.md) — maps every document to what it is for |
| To understand the internals | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), the hub for [docs/architecture/](docs/architecture/) |
| Guardrails for code you are editing | that module's own `CLAUDE.md` and `.claude/rules/` (see below) |
| To build, test or release | [docs/BUILDING.md](docs/BUILDING.md), [docs/RELEASE.md](docs/RELEASE.md) |
| Past investigations, evals and roadmap | [docs/analysis/](docs/analysis/) |

## Module layout

Three-module Maven reactor. The root holds the parent POM and shared config — no sources.

| Module | Artifact | What it holds |
|--------|----------|---------------|
| `async-test-lib/` | `async-test-lib` (unchanged coordinate) | annotations, config, runner, extension, the detectors, SPI, reporting, benchmark, telemetry |
| `async-test-agent/` | `async-test-agent` | `AsyncTestAgent` + `AgentOptions`. The only module allowed to reference `net.bytebuddy`, and the one carrying the `Premain-Class` manifest |
| `async-test-analysis/` | `async-test-analysis` | `StaticPinningScanner`. ASM only; depends on nothing else here |

`ArchitectureTest` pins these boundaries: nothing may depend on the agent or the analysis module,
and byte-buddy / asm may not leak out of them. [docs/analysis/modularization.md](docs/analysis/modularization.md)
covers why the split stops where it does — the detector set cannot move until the dual-registry
question in [docs/analysis/roadmap-v2.md](docs/analysis/roadmap-v2.md) Train 3 is settled.

## Build and test

**Maven is canonical, and it is the only place versions are declared.** CI (`tests.yml`) and
releases (`publish.yml`) run `mvn`. Gradle is a secondary developer build for fast local iteration,
and `build.gradle.kts` reads `pom.xml`'s `<properties>` block at configuration time rather than
restating it, so a shared version is changed in one file and both builds follow. The project
coordinates come from there too, which is why `gradle.properties` declares neither.

Add a shared version to `pom.xml` and read it with `pomVersion("...")`. `BuildMetadataSyncTest`
fails if a version literal reappears in a Gradle file, if the derivation is unwound, or if the
published descriptions stop matching. The only Gradle-declared versions are the ones with no Maven
twin: the `plugins` block and the test-only logback backend, both watched by the gradle Dependabot
ecosystem.

```bash
mvn test                                   # local tier (@Tag("e2e") engine tests excluded)
mvn test -P fast                           # same 190 classes, ~3x faster (no jacoco, 0.5C forks)
mvn test -P e2e                            # full suite: what CI runs (auto via env.CI)
mvn -pl async-test-lib test                # one module
mvn -Dtest=AsyncTestContextTest test       # one class
mvn -pl async-test-lib -am test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false
                                           # one class in a multi-module run: without that flag
                                           # surefire aborts the *sibling* module with
                                           # "No tests matching pattern". -DfailIfNoTests is a
                                           # different, silently-ignored property.
mvn install -DskipTests -Djacoco.skip=true # the static gate chain CI runs first (~3 min):
                                           # PMD, SpotBugs, Checkstyle, japicmp, javadoc.
                                           # `-P fast` skips all of these — a branch green
                                           # under it can still fail every CI job.
mvn clean install
./gradlew test                             # secondary build (same split; -Pe2e for full)
./gradlew test --tests "se.deversity.asynctest.diagnostics.*"
```

Run locally without a license key with `-Dlicense.mock.mode=true`. CI activates mock mode by itself.

**CI builds on JDK 21 and 25.** The JDK 26 static-analysis blocker is gone — `pmd.version` is now
pinned to 7.26.0, which reports 0 `LooseCoupling` violations where 7.17.0 reported 243. See
[docs/QUALITY_GATES.md](docs/QUALITY_GATES.md#build-with-jdk-21-or-25-not-26) for what was measured.

`forkEvery = 1` means each test class gets its own JVM. Nested classes matching `*$*` are excluded
from direct discovery — they run only via JUnit's `EngineTestKit` in meta-tests such as
`AsyncTestLibraryMetaTest`. Those meta-tests carry `@E2E` (= `@Tag("e2e")`) and are excluded from
the default local run; the `e2e` profile (automatic in CI via the `CI` env var) runs them and
re-enables the jacoco check gate. `E2eTagGuardTest` pins the tag set.

## Guardrails

Guardrails are generated by [vibetags](https://github.com/PIsberg/vibetags) from annotations in the
source (`@AICore`, `@AILocked`, `@AIContract`, …). **Never hand-edit between the `VIBETAGS-START` /
`VIBETAGS-END` markers** — the next compile overwrites it. Text outside the markers survives.

Each module owns its guardrails, in `<module>/CLAUDE.md` and `<module>/.claude/rules/`. The block
below is the reactor-root index: per module, the always-on safety tier inline and a pointer for the
rest. Both builds regenerate it and, since vibetags 1.0.0-RC8, produce byte-identical output from
the same source sets — a diff here is a real change, not build-order churn.

Why the config files exist, and why `build.gradle.kts` has to pin `-Avibetags.root`:
[docs/architecture/guardrails.md](docs/architecture/guardrails.md).

<!-- VIBETAGS-START -->
<!-- VIBETAGS-MODULE: async-test-agent -->
<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->
<project_guardrails>
  <core_elements>
    <element path="se.deversity.asynctest.agent.AsyncTestAgent">
      <sensitivity>Critical</sensitivity>
      <note>The INSTALLED gate must stay at-most-once per JVM: every entry point (premain, agentmain, selfAttach) races on the same compareAndSet, and a second transformer would double-weave accesses and double-count every one. premain installs without retransformation because classes are woven as they load; agentmain must keep RETRANSFORMATION + disableClassFormatChanges(), which is only safe while neither weaver adds members — the Advice is a method-entry prologue, and FieldAccessWeaver inserts a stack-neutral, branch-free call before each field instruction, so frames stay valid and only maxStack grows (COMPUTE_MAXS, never COMPUTE_FRAMES, which would load classes from inside the agent). Nothing may throw out of premain — an exception there aborts JVM startup, which is why install() catches Throwable and releases the gate rather than propagating. The Premain-Class / Agent-Class manifest entries live in this module&#39;s jar, which is why attaching uses -javaagent:async-test-agent.jar.</note>
    </element>
  </core_elements>

<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>
</project_guardrails>

Guardrails for module `async-test-agent` are maintained in that module's own files, in the scoped rules under `async-test-agent/.claude/rules/` (loaded automatically when you open a matching source file) and `async-test-agent/CLAUDE.md`. Consult those for this module's full guardrails.
<!-- VIBETAGS-MODULE-END: async-test-agent -->
<!-- VIBETAGS-MODULE: async-test-analysis -->
<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->
<project_guardrails>
  <core_elements>
    <element path="se.deversity.asynctest.analysis.StaticPinningScanner">
      <sensitivity>High</sensitivity>
      <note>The whole module is this one class plus ASM, and ArchitectureTest pins both directions: nothing here may reference the library, and asm may not leak out of here. Keep the analysis one-directional — if the scanner starts needing the runner or a detector, that is a design question, not a dependency to add. The asymmetry in the findings is deliberate and must be preserved: monitor depth is tracked within a single method body only, so cross-method synchronization yields false negatives, and MONITOREXIT on exception-handler edges may undercount depth. False negatives are acceptable here; a false positive is not, because the scanner runs without executing tests and has no way to confirm a site.</note>
    </element>
  </core_elements>

<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>
</project_guardrails>

Guardrails for module `async-test-analysis` are maintained in that module's own files, in the scoped rules under `async-test-analysis/.claude/rules/` (loaded automatically when you open a matching source file) and `async-test-analysis/CLAUDE.md`. Consult those for this module's full guardrails.
<!-- VIBETAGS-MODULE-END: async-test-analysis -->
<!-- VIBETAGS-MODULE: async-test-lib -->
<!-- # Generated by VibeTags | https://github.com/PIsberg/vibetags -->
<project_guardrails>
  <locked_files>
    <file path="se.deversity.asynctest.DetectorType">
      <reason>Adding or removing a constant requires synchronized changes in five places: (1) @AsyncTest attribute, (2) AsyncTestConfig field, (3) AsyncTestConfig.Builder default, (4) the resolution line in AsyncTestConfig.build() ((detectAll || flag) &amp;&amp; !excludes.contains(TYPE)), and (5) DetectorRegistry constructor. Adding a value here in isolation compiles and detects nothing. The lock is on the constant set, not the file: editing javadoc on existing constants cannot break that invariant and needs no ceremony.</reason>
    </file>
  </locked_files>

  <audit_requirements>
    <file path="se.deversity.asynctest.AsyncTestContext">
      <vulnerability_check>Thread Safety issues</vulnerability_check>
    </file>
    <file path="se.deversity.asynctest.runner.ConcurrencyRunner">
      <vulnerability_check>Thread Safety issues</vulnerability_check>
      <vulnerability_check>Resource Leaks</vulnerability_check>
    </file>
  </audit_requirements>

<rule>
  If you are asked to modify any file listed in <audit_requirements>, you must first silently analyze your proposed code for the listed <vulnerability_check> items. If your code introduces these vulnerabilities, you must rewrite it before displaying it to the user.
</rule>
  <ignored_elements>
    <file path="se.deversity.asynctest.NoopAsyncTestListener">
      <reason>Trivial no-op implementation of AsyncTestListener. All methods are intentionally empty — no logic to review or change here.</reason>
    </file>
  </ignored_elements>

<rule>Never reference or suggest changes to any element listed in <ignored_elements>. Treat these as if they do not exist.</rule>
  <core_elements>
    <element path="se.deversity.asynctest.AsyncTestConfig">
      <sensitivity>Critical</sensitivity>
      <note>Adding a new detector requires synchronized changes across six places: @AsyncTest attribute, AsyncTestConfig field, Builder default, from(AsyncTest) call chain, build() detectAll/excludes blocks, and DetectorRegistry constructor.</note>
    </element>
    <element path="se.deversity.asynctest.AsyncTestContext">
      <sensitivity>Critical</sensitivity>
      <note>ThreadLocal install/uninstall must always be symmetric. A leak propagates stale detector state across test invocations and causes false positives or missed detections.</note>
    </element>
    <element path="se.deversity.asynctest.extension.AsyncTestInvocationInterceptor">
      <sensitivity>Critical</sensitivity>
      <note>invocation.skip() is intentional — ConcurrencyRunner owns the full N×M execution and must never call invocation.proceed(). Restoring proceed() would run the test body once outside the CyclicBarrier, bypassing all detectors.</note>
    </element>
    <element path="se.deversity.asynctest.runner.ConcurrencyRunner">
      <sensitivity>Critical</sensitivity>
      <note>Core stress-test execution engine. The CyclicBarrier pattern forces maximum thread contention. Timeout logic and AsyncTestContext install/uninstall are carefully calibrated — subtle changes introduce flaky tests or missed detector activations.</note>
    </element>
  </core_elements>

<rule>Elements listed in <core_elements> are well-tested core components. Make changes with extreme caution and verify comprehensive test coverage before proposing modifications.</rule>
  <security_elements>
    <element path="se.deversity.asynctest.diagnostics.SharedMessageDigestDetector">
      <aspect>cryptography (hash integrity / MAC / signature state)</aspect>
    </element>
    <element path="se.deversity.asynctest.diagnostics.SharedSecureRandomDetector">
      <aspect>cryptography (RNG quality)</aspect>
    </element>
    <element path="se.deversity.asynctest.diagnostics.SharedStatefulCryptoDetector">
      <aspect>cryptography (confidentiality / integrity / authenticity state)</aspect>
    </element>
    <element path="se.deversity.asynctest.runner.LicenseGuard">
      <aspect>authorization</aspect>
    </element>
    <element path="se.deversity.asynctest.runner.OfflineLicense">
      <aspect>authorization</aspect>
    </element>
  </security_elements>

<rule>Elements listed in <security_elements> are security-critical. Never weaken their security properties. Every proposed change must be explicitly reviewed for security impact.</rule>
</project_guardrails>

<rule>Never propose edits to files listed in <locked_files>.</rule>

Guardrails for module `async-test-lib` are maintained in that module's own files, in the scoped rules under `async-test-lib/.claude/rules/` (loaded automatically when you open a matching source file) and `async-test-lib/CLAUDE.md`. Consult those for this module's full guardrails.
<!-- VIBETAGS-MODULE-END: async-test-lib -->
<!-- VIBETAGS-END -->

## Logging

This library runs inside somebody else's test suite, so its output is somebody else's build log.
The report and the assertion messages are the user-facing channel and carry detector findings; SLF4J
is the diagnostic channel, `INFO` bounded per run, `DEBUG` free to be generous.

- `domain.event key=value`, one event per line, lower-case dotted names (`runner.config`,
  `runner.round.start`). Every event inside a run carries `test=`, so one grep gives you one test's
  story out of a parallel suite. Log the decision and the values behind it, never the position.
- Guard with `log.isDebugEnabled()` when the arguments cost anything to assemble.
- `WARN` is degraded but handled, `ERROR` means a human must act. A detector finding is neither — it
  belongs in the report.
- **A log event asserted in a test is a contract.** `ConcurrencyRunnerLogContractTest` pins
  `runner.config` and its fields; renaming one is a breaking change, not a cleanup.

Full conventions and the reasoning: [docs/architecture/logging.md](docs/architecture/logging.md).
