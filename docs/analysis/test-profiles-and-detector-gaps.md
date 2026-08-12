# Test profiles and detector gaps

Investigated 2026-08-06 on `investigate/detectors-and-test-profiles`. Two questions: can the
suite be split into a fast local default and a CI-only end-to-end tier, and which concurrency
bug classes do the 135 detectors not yet cover. Timing numbers come from the surefire XML
reports of a local run on 2026-08-05 (JDK as configured, in-test time, excluding fork
startup); everything else was read from the sources cited inline. Update 2026-08-06: the
Part 1 profile split and Part 2's top candidate (`FLOW_PUBLISHER_CONCURRENCY`) have been
implemented on this branch; candidates 2-6 and the item 0 recording enrichment remain open.

## Part 1: a fast local profile and a CI-only e2e profile

### Measured shape of the suite

| Module | Classes | Tests | In-test time |
|---|---|---|---|
| `async-test-lib` | 210 | 1723 | 143.1 s |
| `async-test-agent` (surefire) | 4 | 43 | 5.3 s |
| `async-test-agent` (failsafe) | 1 (`AgentJarPremainIT`) | 2 | 2.2 s |
| `async-test-analysis` | 1 | 5 | 0.3 s |

`docs/QUALITY_GATES.md` puts the wall time of `mvn test` at about 5 minutes. The gap between
143 s in-test and ~300 s wall is fork startup: `reuseForks=false` gives every class its own
JVM, roughly 0.75 s each across 210 classes. That isolation is deliberate (it stops cross-test
contamination, QUALITY_GATES.md) and this investigation does not propose touching it.

The skew that makes a split worthwhile: 22 classes drive the whole engine through JUnit
`EngineTestKit` (spinning up the JUnit Platform against nested `@AsyncTest` dummy classes, so
each runs `ConcurrencyRunner` plus the full detector sweep). Those 22 classes are 69.5 s of
the 143.1 s in-test time (48.5%) but only 85 of the 1723 tests (4.9%). The slowest single
class, `Phase2AsyncIntegrationTest`, is 16.2 s on its own. By contrast the 147
`diagnostics/*Test` classes, the plain-JUnit bulk that a detector change actually needs, total
38.5 s.

In the agent module, `SelfAttachTest` (1.9 s, needs `-Djdk.attach.allowAttachSelf=true`) and
`AgentFeedsDetectorEndToEndTest` (1.7 s) are end-to-end by nature; `AgentJarPremainIT` already
lives behind failsafe and only runs at `verify`.

### Current state

- No `@Tag` exists anywhere in any source set (verified by grep across all modules,
  `consumer-fixture`, `load-tests`, `examples`).
- No surefire `groups`/`excludedGroups`/`includes`/`excludes` in any pom.
- Exactly one Maven profile exists: `release` (GPG signing).
- CI entry points: `tests.yml` runs `mvn clean install` (matrix JDK 21/25 across three OSes),
  `publish.yml` runs `mvn clean deploy -P release`. A separate `e2e-tests.yml` already exists
  but covers the out-of-reactor `consumer-fixture` and `examples` builds, not this split.
- `BuildMetadataSyncTest` asserts Maven-Gradle agreement on versions, coordinates and
  descriptions only; it does not touch test configuration, so a Maven-side profile does not
  trip it. (Verified against its five test methods.)

### Proposed design

Tag-based, not name-based: renaming 22 classes to `*IT` would churn history and docs, and
several of the 22 are pinned by name elsewhere (`ConcurrencyRunnerLogContractTest`,
`AsyncTestLibraryMetaTest`).

1. Add a meta-annotation in the library's test sources, `@E2E` = `@Tag("e2e")`, and apply it
   to the 22 `EngineTestKit` classes plus `SelfAttachTest` and `AgentFeedsDetectorEndToEndTest`.
   A meta-annotation rather than raw string tags, so a typo cannot silently reclassify a
   class. The name overlaps with `e2e-tests.yml` conceptually but not mechanically; if that
   reads as confusing, `engine-e2e` is the fallback tag id.
2. Root pom: `<surefire.excludedGroups>e2e</surefire.excludedGroups>` as a property, wired
   into the surefire config. Default (local) `mvn test` then runs the plain-JUnit tier only.
3. A Maven profile `e2e` that clears the property, auto-activated on the `CI` environment
   variable, which GitHub Actions always sets. CI keeps running the full suite with zero
   workflow edits, releases included (`publish.yml` inherits the same activation), and a
   developer can opt in locally with `mvn test -P e2e`.
4. Gradle mirror: `excludeTags("e2e")` in the shared test block unless `CI` is set or
   `-Pe2e` is passed. `gradle-tests.yml` runs in Actions, so it also keeps full coverage
   automatically.
5. A guard test in `architecture/` asserting that every test class importing
   `org.junit.platform.testkit` carries the tag. Without it the tag set and the EngineTestKit
   set drift apart silently; with it, the next meta-test cannot land untagged.

Estimated local effect (estimate, not measured): dropping 85 tests, ~69.5 s in-test and ~22
forked JVMs takes `mvn test` from roughly 5 minutes to roughly 3.5. The architecture gates
(9.2 s, including `ArchitectureTest`) stay in the local default on purpose: they are the
guardrails a developer needs before pushing, and they are the tests most likely to catch a
boundary violation early.

### The one real interaction: the coverage gate

`async-test-lib` binds a jacoco check at `verify` (LINE >= 0.70, BRANCH >= 0.65). The e2e tier
carries most of the coverage of `runner/`, `extension/` and `report/`, so a local
`mvn clean install` without the profile may fail the gate over code that is actually covered
in CI. Options:

- (a) The check execution rides the same property and is skipped when the e2e tier is
  excluded, with an explicit log line saying the gate was skipped, not passed.
- (b) Accept that local `install` can go red on coverage and document
  `mvn clean install -P e2e` as the pre-push command.
- (c) Move the check into the `e2e` profile permanently.

Recommendation: (a), because it keeps the fast path usable and keeps the skip visible. This is
the main open question for review.

Docs that the change makes wrong and must be updated in the same PR: `QUALITY_GATES.md:10`
(the "runs the full suite" claim), `BUILDING.md`, the root `CLAUDE.md` build section, and
`WORKFLOW.md`.

## Part 2: detector gap analysis

Method: the full 127-constant inventory of `DetectorType` was grouped by category, a candidate
list of known concurrency bug classes was checked against it, and each surviving candidate was
verified absent by grepping the sources and `docs/DETECTOR_CATALOG.md` for the relevant JDK
types. Candidates ruled out because coverage already exists: task rejection
(`ThreadPoolMonitor.recordTaskRejected`), semaphore permit leaks and over-release
(`SemaphoreMisuseDetector`), wait-outside-loop (`SPURIOUS_WAKEUP_HAZARD`), read-lock upgrade
(`LOCK_UPGRADE_DEADLOCK`), silent submit() exception loss (`FUTURE_IGNORED`).

### 0. Highest value: synchronization-aware recording (an improvement, not a new detector)

`docs/analysis/detector-accuracy-eval.md` measured that only 3 of 6 detectors stay silent on
the correctly synchronized twin of buggy code, and names the root cause: detector input is
(thread, access) tuples with no representation of synchronization, so a lock-protected event
stream is indistinguishable from a racy one. Before adding detector 128, the largest accuracy
win available is enriching the recording path with lock context:

- Cheap first step, no weaving required: at `recordUse`/`recordAccess` time in the 17
  `Shared*` detectors, capture `Thread.holdsLock(instance)` (and optionally a held
  `ReentrantLock` registered via the existing context). If every observed access to an
  instance happened under one consistent monitor, downgrade or suppress the finding.
- The miss direction is safe by the project's own rule: a false negative is acceptable, a
  false positive on correct code is what the RC8 wording rewrite and the
  `SharedSecureRandomDetector` severity drop were both paying for.
- This also raises the ceiling for `failOn = HIGH`, which the eval currently warns will fail
  builds over correct-but-shared code.

### New detector candidates, ranked

Verified absent means a grep for the listed JDK types returns nothing in `src/main`,
`DETECTOR_CATALOG.md` or the fixtures, checked 2026-08-06.

1. **`FLOW_PUBLISHER_CONCURRENCY`** - `java.util.concurrent.Flow` and `SubmissionPublisher`
   have zero coverage (verified absent); this is the only JDK concurrency API with no
   detector at all. Record `onSubscribe`/`onNext`/`onError`/`onComplete` with thread and
   sequence; fire on overlapping `onNext` calls (the reactive-streams serialization rule),
   signals after a terminal signal, and `onNext` without a preceding `request`. Spec
   violations are facts, so the false-positive posture is strong. Severity HIGH for
   overlapping `onNext`.
2. **`JVM_DEFAULT_MUTATION`** - `Locale.setDefault` / `TimeZone.setDefault` during a run.
   Sibling of `SYSTEM_PROPERTY_MUTATION`, same snapshot-before/compare-after model, so a
   finding is a fact, not an inference. The Phase 12 fixture already tiptoes around exactly
   this ("`TimeZone.setDefault()` would change process-wide state and leak",
   `Phase12OperationalHygieneDetectorsFixtureTest:140`), which is good evidence the bug class
   is real and unhandled. Cheap to build.
3. **`PIPED_STREAM_DEADLOCK`** - `PipedInputStream`/`PipedOutputStream` used from a single
   thread, which the JDK javadoc itself warns may deadlock, plus writes with no connected
   reader. Record reader and writer thread ids per pipe pair; same-thread read and write is a
   factual finding.
4. **`PROCESS_STREAM_STARVATION`** - `Process.waitFor()` without draining stdout/stderr, the
   classic child-process buffer deadlock. Mildly heuristic (small outputs never fill the
   buffer), so it takes the RC8 conditional wording and MEDIUM severity.
5. **`CLASS_INIT_DEADLOCK`** - threads blocked in `<clinit>` frames, detected from the same
   ThreadMXBean dump the deadlock detector already takes. Extends the family the accuracy
   eval rates trustworthy in both directions. Rarer in the wild, hence rank 5.
6. **`SHARED_SERVICE_LOADER`** - `ServiceLoader` is not thread-safe and nothing covers it;
   observed-sharing family, MEDIUM with conditional wording. Niche, lowest priority.

### Cost of adding any of these

Today a detector costs ~9 synchronized edit sites (`DetectorType` is `@AILocked`; config,
registry, factories, docs), scaffolded by the `/adddetector` skill and enforced by
`AllDetectorsSpiCoverageTest`. Roadmap Train 1 (the registry factory table) would cut that to
~4 sites, and Train 2 gives the SPI its own detector identities. If more than two of the
candidates above go ahead, landing Train 1 first is cheaper than paying the 9-site cost three
times. The synchronization-aware recording in item 0 is also where Train 2's
`AbstractInstanceDetector` base class would naturally host lock-context capture, one reason to
build it once in a base class rather than 17 times.
