# Production-Readiness Evaluation

_Branch: `chore/production-readiness-eval` · base: `main` @ 5040f98 · date: 2026-06-29_

## Verdict

The library is **functionally mature** — 171 main source files, 165 test files,
~110 wired detectors, 121 example projects, 10 CI workflows, OpenSSF Scorecard,
CodeQL, fuzzing, SBOM, dual Maven+Gradle builds. Main + test sources **compile
cleanly on JDK 26** (baseline 21; 100 Error Prone *style* warnings, zero errors).
No `TODO`/`FIXME`/`HACK` markers in `src/main`.

It is **not yet cut as a final release** (`v1.7.0-RC1` is the latest tag — a release
candidate) and has **two structural blockers to being usable by others**: a
license gate and licensing model. Below is what's left, by priority.

---

## P0 — Blockers for "usable by others"

1. **License gate on test execution.**
   `runner/LicenseGuard.check()` throws `SecurityException("LICENSE DENIED")` for an
   external user who is *not* in CI, has no key, and hasn't set `-Dlicense.mock.mode=true`.
   A test-scoped library that can refuse to run unless an external licensing backend
   says yes is a hard adoption blocker. Decide one of:
   - default to permissive/mock when no key is configured (gate becomes opt-in), or
   - document the no-key local path as a first-class, supported mode (it currently
     reads as a fallback), and guarantee the backend is reachable/optional.

2. **PolyForm Noncommercial license.**
   Free for non-commercial use only. That is a deliberate product choice, but it
   means "production ready for others" excludes commercial users by license. If broad
   adoption is the goal, this is the single biggest gate; if monetization is the goal,
   it's fine but should be stated up front in the README's first paragraph.

3. **Cut a final release.** `1.7.0-RC1` → `1.7.0`. No GA tag exists. Publishing flow
   (`publish.yml` → `mvn deploy -P release`) appears ready but unproven for a final tag.

## P1 — Correctness / consistency

4. **Dual build system, unclear source of truth.**
   `publish.yml` and `tests.yml` use **Maven** (`mvn deploy`, `mvn install`); a separate
   `gradle-tests.yml` and `docs/CLAUDE.md` say publishing is **Gradle/vanniktech**.
   Two parallel build definitions (`pom.xml` + `build.gradle.kts`) must be kept in lockstep
   by hand — version, deps, plugins. Pick one canonical build (or document explicitly why
   both exist and which one releases), or they will drift.

5. **Documentation version/count drift.** Sources disagree:
   - Detector count: README "111", `docs/CLAUDE.md` "100 detectors / 13 phases",
     `DetectorType` enum ~110 constants, 128 files in `diagnostics/`.
   - Version: README & build files "1.7.0-RC1", `docs/PRE_RELEASE_CHECKLIST.md` still
     "1.6.0" and reads as written for the *first ever* release ("READY FOR DISTRIBUTION",
     v1.6.0 tag instructions) — it is stale.
   - Pick one authoritative detector count and version, regenerate the rest.

6. **JDK 25/26 detectors are not wired into `detectAll`.** ✅ **RESOLVED.**
   `StableValueMisuseDetector`, `StructuredTaskScopeMisuseDetector`, and
   `GathererConcurrencyMisuseDetector` are now wired into the pipeline as **Phase 16**
   (count 111 → 114) — `DetectorType` constants, `@AsyncTest` flags, full `AsyncTestConfig`
   plumbing, legacy `DetectorRegistry` wiring, `AsyncTestContext` accessors, and SPI
   factories — with the `DetectorType` edit done under explicit owner authorization.
   Covered by `AllDetectorsSpiCoverageTest` and a new `Jdk2526DetectorWiringTest`.

## P2 — Polish before GA

7. **Error Prone warnings (100).** Mostly `MissingSummary`, `UnusedVariable`,
   `PatternMatchingInstanceof`. None block, but a library publishing Javadoc artifacts
   should clear `MissingSummary` and dead fields/params (`BoxedPrimitiveLockDetector.lockObject`,
   `UncaughtExceptionHandlerDetector` unused `tid`/param).

8. **Stale/duplicative docs.** `docs/` has `DISTRIBUTION.md`, `DISTRIBUTION_COMPLETE.md`,
   `DISTRIBUTION_SETUP.md`, `SUMMARY.md`, `PRE_RELEASE_CHECKLIST.md` — overlapping and
   partly stale. Consolidate to one release/distribution guide.

9. **Repo hygiene.** Working tree carries build/log artifacts (`vibetags.log` ~213 KB,
   `.vibetags-mod-_root_` ~69 KB, `build/`, `target/`, `.gradle/`, `.pytest_cache/`).
   Confirm `.gitignore` covers these so they aren't shipped or committed.

## Not blockers (already strong)

- Test coverage: 165 test files, JaCoCo + codecov wired, 80% per-detector coverage goals.
- Supply chain: CodeQL, OpenSSF Scorecard, dependency-review, fuzzing, SBOM, Dependabot.
- Public-API discipline: `consumer-fixture/` exercises only the published surface;
  guardrails in `CLAUDE.md` lock signatures and the `DetectorType` enum.
- Docs breadth: USAGE, ARCHITECTURE, CI_INTEGRATION, DETECTOR_CATALOG, CHANGELOG all present;
  all README links resolve. IntelliJ companion plugin present.

---

## Shortest path to GA

1. Resolve the **license-gate default** (P0-1) and **state the licensing model** clearly (P0-2).
2. Choose **one canonical build** (P1-4); make the other clearly secondary or remove it.
3. Reconcile **detector count + version** across all docs (P1-5); delete/refresh stale
   distribution docs (P2-8).
4. Either **wire the 3 JDK 25/26 detectors** into the enum, or soften the "111, all on"
   claim (P1-6).
5. Clear **Error Prone `MissingSummary`/unused** warnings (P2-7), confirm `.gitignore` (P2-9).
6. Tag **`1.7.0`** and dry-run `publish.yml` against a staging repo before GA (P0-3).
