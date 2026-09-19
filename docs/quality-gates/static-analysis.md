# Static analysis and API gates

Part of the [Quality Gates guide](../QUALITY_GATES.md).

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
- **japicmp** breaks the build when the version number understates the API change, against the
  baseline pinned in `async-test-lib/pom.xml` (`<oldVersion>`). A removal passes at 2.0.0 and
  fails at 1.10.0 or 1.9.9; an addition passes at any of them. That baseline used to be only as
  good as the last person who bumped it: it sat at 1.6.0 while 1.7.0 through 1.9.1 shipped, so
  for six releases everything added after 1.6.0 was outside the comparison and could have been
  broken without the gate noticing, and it went stale again at four consecutive releases after
  that. Re-pinning it is
  still a step in [RELEASE.md](../RELEASE.md#2-bump-the-version), but **JapicmpBaselineFreshnessTest**
  now fails the build when the pinned version is not the newest release in the changelog below the
  version being built - stale, or bumped forward onto the release being cut. This document
  deliberately does not name the current baseline: that number belongs in one place, and the test
  is what keeps it honest.
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
of nullable references: every one of the 146 detectors is `cfg.detectX ? new XDetector() : null`, so
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
