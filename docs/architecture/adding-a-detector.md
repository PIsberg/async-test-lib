# Adding a Detector

> Part of the [architecture documentation](../ARCHITECTURE.md).

The most common change in this repo, and the easiest to get silently wrong: a new detector only
works when *every* wiring point is updated together. A field without construction, or construction
without analysis, compiles fine and simply never detects anything. Nothing fails loudly — the
detector just doesn't run.

## The synchronized-change contract

One new `DetectorType` constant requires simultaneous changes in five files, all under
`async-test-lib/src/main/java/se/deversity/asynctest/`. Land them as one change, never piecemeal.

1. **`DetectorType.java`** — the new enum constant. This file is `@AILocked`; edit only with
   explicit owner sign-off.
2. **`AsyncTest.java`** — the matching `detectXxx()` annotation attribute. Its name and default
   become stable public API.
3. **`AsyncTestConfig.java`** — public final flag field derived in the constructor
   (`flag = enabled.contains(DetectorType.TYPE);`), the same-named `Builder` setter
   (`return flag(DetectorType.TYPE, v);`) and the `from(AsyncTest)` call chain. Resolution itself
   is one `EnumSet` and needs no per-detector line. See
   [configuration-resolution.md](configuration-resolution.md).
4. **`DetectorRegistry.java`** — three steps that must land together: (a) the final field,
   (b) its factory-table row in the constructor, `field = create(DetectorType.TYPE, Xxx::new);`,
   keyed on the type rather than a config flag (#916), (c) an `analyzeAll()` call in the correct
   phase block. `DetectorRegistryFactoryTableTest` fails on a type with no row, or two.
5. **`AsyncTestContext.java`** — the field copied from the registry plus the static accessor used by
   instrumented code, keeping ThreadLocal install/uninstall symmetric. See
   [execution-flow.md](execution-flow.md).

## The detector class itself

New detectors live in `diagnostics/` and follow the house thread-safety idiom. A detector that
keeps state per object it is told about extends `AbstractInstanceDetector<S>` (#918), which owns
the weakly identity-keyed map, the get-then-`computeIfAbsent` lookup and the label of an unnamed
object: implement `newState(instance, label)`, call `stateFor(instance, name)` on the record path
and iterate `states()` in `analyze()`. Otherwise: per-key state in a
`ConcurrentHashMap` with a **get-then-`computeIfAbsent`** hot path, thread-id/name sets as
`ConcurrentHashMap.newKeySet()`, counters as `LongAdder`. Violation lists are `CopyOnWrite` or
synchronized lists; first-registration-wins uses `putIfAbsent`.

The hot path has two allocation traps (#812). Look the state up with
`map.get(IdentityKey.lookup(x))`, which reuses the calling thread's key while it names the same
instance, and make the stored key on a miss: a `new IdentityKey(x)` per call costs 24 bytes,
which the compiler removes in some JVMs and not in others. And add a thread id with
`SelfGuard.addThreadId(set, id)`, not `set.add(id)`, which boxes 24 bytes on every call.
The recording body of `RunnerAllocationBudgetTest` is the gate, through `SharedCollectionDetector`.

Declare parameters and fields as `ConcurrentMap`, not `ConcurrentHashMap` — PMD's `LooseCoupling`
rule fails the build otherwise.

`analyze()` must be idempotent: same observed state → same violations, no side effects.
`DetectorRegistry.analyzeAll()` relies on it.

**Hot-path constraints.** `recordAccess`-style methods run inside every invocation round under full
contention: never introduce O(n²) work, allocation, autoboxing, or lock acquisition there.
`SiteCapture` must not allocate when a site is already captured for a key.

**Locale.** Message formatting that renders floats must pass `Locale.ROOT`. The maintainer's machine
locale is `sv-SE`, where `%f` produces comma decimals and breaks assertions.

## Severity

The `failOn` gate resolves a finding's severity in `DetectorDefaultSeverity.of(name, report,
structured)`, in this order: per-finding grades (`GradedFindings`), the most severe severity in the
report's public `structuredViolations` list, a `diagnostics/IssueSeverity` marker in the report
text, the detector's entry in `DetectorDefaultSeverity`, and last **HIGH for anything else**. A
detector that keeps `Violation`s states its severity there; one that does not must tag its text (a
deadlock report says CRITICAL in its own text) or declare a default. Grades do not replace either:
listeners, and the JSON and SARIF output, read the list, the text and the table, never the grades,
so a graded report states its severity in its list too. Since #801 every built-in detector keeps
the list and the `DetectorDefaultSeverity` table is empty; a new detector does the same.
`DetectorSeverityMarkerTest` fails the build for a detector that reaches the HIGH fallback.

Keep the list, and fill it wherever the text gains a line. `StructuredViolationCoverageTest` fails
on a detector whose report has no `structuredViolations` field unless it is pinned in that test's
text-only allow-list, which only shrinks and has been empty since #801, so a new detector needs
the field. Return the report
through `DetectorFailurePolicy.checkedReport(this, r)`, as the template does; the same test fails a
structured detector whose source does not. Under `async-test.strict-detectors`, which this build's
tests set, that call throws for a report with issues and an empty list, so every test that obtains
the report is a driver: the detector's own unit tests that call `analyze()`, and any test that fires
it through the runner or the registry, where `DetectorRegistry.ifIssue` checks it again (#802,
#829). Give the detector one entry in the test's `PATHS`, which drives it even if its unit tests
change. The check only sees the paths some test reaches, so write a firing unit test for each place
the report writes a finding.

## Tests are part of the change

Every detector has a mandated JUnit 5 test at
`async-test-lib/src/test/java/se/deversity/asynctest/diagnostics/<Name>DetectorTest.java`, with an
80% coverage goal. An implementation change without the matching test update is incomplete.
Integration-style coverage typically uses the `EngineTestKit` dummy pattern — see
[../QUALITY_GATES.md](../QUALITY_GATES.md).

## Also register the factory

`spi/adapters/LegacyDetectorFactories.java` exposes each detector through the `DetectorFactory`
`ServiceLoader` path via `LegacyDetectorAdapter`. `AllDetectorsSpiCoverageTest` fails loudly if the
SPI side is incomplete.

The adapter's structure is deliberately legacy-shaped — do not modernize it; touch its business
logic only when explicitly asked.
