# Vibe Architecture health scorecard: async-test-lib self-audit

- **Date:** 2026-08-15. Audit in the morning against `main` at 4e127b1c; the closing pass is the
  pull request this file arrives in.
- **Scope:** this repository, audited against the 33-row scorecard in *Vibe Architecture*,
  Appendix A (first edition, 2026-08-01 build), plus the twelve-item documentation checklist in
  Chapter 6c.10.
- **Status:** point-in-time analysis, not current reference. The enforcing artifacts named in
  each row are the reference; when this file and a gate disagree, the gate wins.
- **Method:** each row scored 0 (absent) / 1 (partial, manual, or team-dependent) / 2
  (automated, consistent, merge-blocking where applicable), with the evidence named. Scores are
  claims about mechanisms that exist and run, not about intentions. Where a lane can lack credit
  (an API key, a Copilot quota) it reports SKIPPED, and SKIPPED is scored as what it is: not run.

**Before: 42 of 66. After this pass: 56 of 66.** Book banding for both: 34–49 "mature", 50–66
"complete stack, maintain". The ten points not taken are listed at the end with the reason each
one is the maintainer's decision, a measurement not yet made, or a piece of work too large to
ride in a closing pass.

Two facts the audit surfaced that were not scores: the committed code-karta diagrams were 27 node
titles behind the code, and `CONTRIBUTING.md` said mutation testing ran on a schedule when no
workflow ran it. Both are fixed by gates, not by edits.

## Part I: Foundation

| # | Row | Before | After | Evidence / reason |
|---|---|---|---|---|
| 1 | Tier-1 file ≤ 20 lines, non-contradictory | 1 | 1 | Root `CLAUDE.md` is about 230 lines: a link table, module layout, build commands, a generated guardrail index and now a five-line standing-rules list. It has no ≤ 20-line invariant tier of its own, and two annotation notes count the add-a-detector change differently (the `DetectorType` lock says five places, the `AsyncTestConfig` note says six; the sixth is the `from(AsyncTest)` chain, so both are true of different scopes, but they read as a contradiction). The diet is a maintainer's edit of their own map; deferred, see below. |
| 2 | Tier-2 scoping | 2 | 2 | Generated `.claude/rules/*.md` per module with `paths:` frontmatter (5 in `async-test-lib`, 2 in `async-test-agent`, 1 in `async-test-analysis`); `.gemini/rules/` mirrors them. |
| 3 | Build/test commands in root file | 2 | 2 | `CLAUDE.md` "Build and test": the tier split, the one-class-in-a-reactor trap, the static chain, both builds. |
| 4 | No AI in prohibited categories | 2 | 2 | No cryptographic primitive is implemented here; `OfflineLicense` verifies Ed25519 through the JDK's `java.security` API and sits, with `LicenseGuard` and the three crypto-misuse detectors, in `<security_elements>`, whose rule requires explicit security review of every change. |

## Part II: Engineering the Guardrails

| # | Row | Before | After | Evidence / reason |
|---|---|---|---|---|
| 5 | Annotations on frozen APIs | 2 | 2 | 69 of 198 main files carry `@AI*`: `@AIThreadSafe` 39, `@AITestDriven` 35, `@AIPublicAPI` 19, `@AIContract` 10, `@AICore` 6, `@AILocked` 1 (`DetectorType`), `@AISecure` 4. Every public-API class is covered. |
| 6 | Regeneration enforced, drift fails build | 1 | 2 | Both builds regenerated the files; nothing checked the result. `guardrails.yml` / `guardrail-drift` now runs a clean `test-compile` and fails on any diff over the generated paths. Verified green on the current tree before it was added. |
| 7 | ≥ 3 ArchUnit boundary rules | 2 | 2 | `ArchitectureTest`: 20 `@ArchTest` rules, including the two module-boundary rules and the byte-buddy / asm confinement rules; `ArchUnitBadgeSyncTest` pins the README count. |
| 8 | Arch tests in default run | 2 | 2 | Untagged, so plain `mvn test` and every CI leg. |
| 9 | Living diagram regenerates every build | 1 | 2 | `guardrails.yml` / `diagrams` regenerates the code-karta SVGs and fails on structural drift (`tools/diagram-structure.sh` fingerprints). Its first local run found 27 node titles moved since the last manual regeneration; the regenerated SVGs are in this PR. The PlantUML diagrams remain hand-drawn by design (they say what is intended, the parsed ones say what is). |
| 10 | Spec directory | 2 | 2 | `docs/` with `INDEX.md` as the routing table, `ARCHITECTURE.md` as the hub, one topic per file, `analysis/` for point-in-time work. Seven docs-vs-code gates before this pass; `DocsIndexCoverageTest` makes eight. |
| 11 | BDD feature files | 0 | 2 | `async-test-lib/src/test/resources/features/core-flows.feature`, five scenarios, executed by `CoreFlowsBddTest` through the real engine with a two-way scenario/binding match. Dependency-free (JUnit Platform 6 vs Cucumber's 1.x engine). |
| 12 | `@AITestDriven` on critical classes | 2 | 2 | 35 usages; the detectors carry the requirement once at package level in the detectors rule file rather than 135 times in the always-loaded index, deliberately. |
| 13 | Instruction evals | 0 | 1 | `evals/` task bank (four rules, deterministic detectors, floors) wired to PRs that edit the instruction files. Every detector proven red and green by hand. The bank has not been run against a model; without `ANTHROPIC_API_KEY` the workflow says SKIPPED. Score 2 requires a run and a floor result. |

## Part III: The Practice

| # | Row | Before | After | Evidence / reason |
|---|---|---|---|---|
| 14 | Skills forged from the codebase | 2 | 2 | Five project skills citing real files (`adddetector` names the locked file and its wiring edits by count; `release` names the japicmp baseline and the bump allowlist; `consultation-loop` names this library's hot path and security surfaces). |
| 15 | Context as runtime invariant | 0 | 2 | Standing rule in `CLAUDE.md`: delegate broad reads, keep the conclusion; `consultation-loop` runs its reviewer in a fresh context by contract; the eval bank runs trials in disposable worktrees with an empty config dir. |

## Part IV: Handling the Hard Stuff

| # | Row | Before | After | Evidence / reason |
|---|---|---|---|---|
| 16 | Concurrency detector on shared state | 2 | 2 | 47 test files use `@AsyncTest` on the library's own shared state (`AsyncTestContextTest`, `ConcurrencyRunnerTest`, the telemetry and SPI registry tests); `RegistrationIsIdempotentTest` exists because three registration bugs only ever showed under real concurrency. |
| 17 | Performance contract | 1 | 1 | `load-tests/` (JMH, throughput and memory sweeps) runs on every PR and asserts nothing; the memory sweep samples peak heap, which is too noisy for a ceiling. A real inner-loop budget needs a per-thread allocation counter on the runner path, calibrated red-first; deferred as its own piece of work. |
| 18 | Expand-contract migrations | 1 | 1 | No database. Data at rest: report `Baseline` files (line-based, unversioned) and the license validation cache (a timestamp). SARIF output carries its schema. No written data-at-rest rule; deferred. |
| 19 | Contract tests per external API | 1 | 1 | Two license servers behind `common-license-lib`; `Real*E2eTest` classes hit the live services with a key, the rest use mock mode. No recorded consumer contract (WireMock/Pact); deferred, the honest gate today is the live e2e. |
| 20 | OpenAPI versioned + registered | 2 | 2 | No HTTP surface. The wire contract is the report formats (JSON, JUnit XML, Markdown, SARIF 2.1.0 with its `$schema`), pinned by their formatter tests. |
| 21 | Supply-chain gate | 2 | 2 | Exact versions in `pom.xml` (Gradle derives them; `BuildMetadataSyncTest`), `dependency-review.yml` fails on high severity and denied licences, Dependabot daily on three ecosystems, every action SHA-pinned, SBOM, keyless signing. The propose-do-not-install rule is now stated in `CLAUDE.md` and asked for in the PR template. |
| 22 | Sandbox isolation for autonomous runs | 1 | 1 | Every CI lane, including the two agent lanes, runs egress-blocked behind an endpoint allowlist on ephemeral runners. The committed `.claude/settings.local.json` pre-approves `git push`, `curl`, `python3 -c` and `node -e` for local sessions; tightening it is the maintainer's call. |
| 23 | Untrusted-context handling | 0 | 1 | Standing rule in `CLAUDE.md`; no workflow feeds issue or comment text to an agent, so exposure is zero by absence. No test pins it, so 1. |
| 24 | Attacker agent | 1 | 1 | `fuzzing.yml` runs one Jazzer target weekly and fails correctly (the three stacked defects that hid behind `continue-on-error` until 2026-08-10 are documented in `QUALITY_GATES.md`). Not on PRs, one target. |
| 25 | Tests audited for wrong-reason green | 1 | 2 | `mutation.yml` runs PIT weekly and on demand against the pom's 74% threshold (before: configured, documented as scheduled, never run). The PR template asks for red-first evidence; this pass's own gates were each watched red before commit. Awaiting the first CI run to confirm the wall-clock fits. |
| 26 | Guardrails merge-blocking in CI | 1 | 1 | The gates run on every PR; which are *required* is branch protection, not readable from the repository. Deferred decision: add Guardrail Drift, Locked Files Guard, Architecture Diagram Drift and the ubuntu test leg to the required-checks list. |
| 27 | Agent provenance in history | 1 | 2 | 116 of the last 200 commits carry `Co-Authored-By: Claude`, by habit. Now: the standing rule in `CLAUDE.md`, the PR template box, and a SessionEnd hook that stages a per-session lineage line. Every commit in this PR carries the trailer and session link. |
| 28 | `@AILocked` merge-blocking | 1 | 2 | `guardrails.yml` / `locked-files` runs `PIsberg/vibetags/action/locked-files` on every PR; verified locally against this reactor (one locked element, `DetectorType` 13–351). `lock-override` is a maintainer-applied label. |
| 29 | Inquisitor on PRs | 1 | 1 | `inquisitor.yml` (skips loudly without `ANTHROPIC_API_KEY`) and `copilot-review.yml` (verifies the request landed, SKIPPED otherwise). Neither has produced a review here yet; the vibetags repository scores the same design 2 under the maintainer's accepted skip-on-quota policy, so this row is the maintainer's to promote once either lane has run. |
| 30 | Consultation loop | 1 | 2 | `.claude/skills/consultation-loop`: five scoped adversarial questions to a fresh reviewer, advisory by contract. |
| 31 | Prompt lineage | 2 | 2 | All instruction files, skills, rules and the reviewer prompt are version-controlled; the hand-written prose no longer disagrees with itself on the detector count (`DetectorCatalogCoverageTest` now scans the agent-facing files too). |
| 32 | Model pinning and routing | 0 | 2 | `.github/MODEL-ROSTER.md`: exact model ID per lane, the Copilot lane recorded as GitHub-managed and why that is acceptable, and the re-pin ceremony gated on an eval replay. |
| 33 | Micro-commit discipline | 1 | 2 | Standing rule in `CLAUDE.md`; this PR is one commit per concern. History before it: median 119 changed lines over ~4 files in the last 100 commits, with five regeneration sweeps touching 264–296 files. |

## Documentation checklist (Chapter 6c.10)

| # | Item | State |
|---|---|---|
| 1 | One owning document per topic | Held by convention; `INDEX.md` names the owner. |
| 2 | Orientation file fits a screen, links outward | Partial: `CLAUDE.md` links outward but is ~230 lines (row 1). |
| 3 | `INDEX.md` routes to every document, tested | **Now enforced** by `DocsIndexCoverageTest`; five documents were unrouted. |
| 4 | Architecture docs one topic each, rule first | Held (`docs/architecture/`). |
| 5 | Point-in-time work in `analysis/`, dated | Held; dates are in header blocks, not filenames. |
| 6 | Decisions dated, immutable, superseded not edited | Held for `analysis/`. |
| 7 | Every relative link resolves, checked in CI | **Now enforced** by `DocsIndexCoverageTest` (0 broken at introduction). |
| 8 | README quick start executed in CI | Held: the consumer fixture and examples reactor in `e2e-tests.yml`. |
| 9 | Numbers in prose asserted against the artifact | Held for detector counts (`DetectorCatalogCoverageTest`, now including agent-facing files), the ArchUnit badge, pom/Gradle metadata. Not held for the PIT badge (`75%`, measured 75.4%; the gate is 74). |
| 10 | Generated content between markers, regenerates in CI, drift fails | **Now enforced** by `guardrail-drift`. |
| 11 | Doc changes ship in the causing commit | Held by convention and PR template. |
| 12 | Agent guessed wrong ends with a doc fix | Held by convention; this pass fixed three stale counts it found. |

## Deferred decisions (the maintainer's, not an agent's)

1. **Required checks.** Add `Guardrail Drift`, `Locked Files Guard`, `Architecture Diagram
   Drift` and the ubuntu `Test Suite` legs to the branch protection of `main`; leave the
   Inquisitor, Copilot and eval lanes out until their record justifies it (rows 26, 28, 29).
2. **`ANTHROPIC_API_KEY` repository secret**, optional: turns on the Inquisitor lane and lets
   the eval bank run in CI (rows 13, 29). Until then both say SKIPPED.
3. **The `lock-override` label** has to exist on the repository for the guard's override to be
   applicable; create it once, with a description that says who applies it.
4. **Context diet of `CLAUDE.md`** (row 1): move the module-layout and build-quirk prose into
   the docs that own it and leave a ≤ 20-line invariant list, each line naming its enforcing
   test. A maintainer's edit of their own map, roughly the vibetags 220-to-120 pass.
5. **`.claude/settings.local.json`** (row 22): decide whether the local allowlist should
   pre-approve `git push`, `curl`, `python3 -c` and `node -e`, and whether a tracked file
   named `.local.json` should stay tracked (it is in `.gitignore` and tracked at once).
6. **A performance budget on the runner path** (row 17): a per-thread allocation counter and a
   ceiling calibrated red-first, plus a nightly comparison against a committed baseline. Its
   own PR.
7. **Contract tests for the license servers** (row 19), and a written data-at-rest rule for
   the `Baseline` file format (row 18).

## Book errata found while auditing (fixes belong in the book repository)

- Appendix A rows 26 and 28 both ask for merge-blocking, but the scorecard has no row for the
  branch-protection setting itself, which is the only place "merge-blocking" is decided; a
  repository can score 2 on both with a required-checks list that names neither. Worth a
  sentence in the row notes.
- Chapter 6c.10 item 5 asks for the date "in the filename and in a header block"; this
  repository's `analysis/` files date the header only and `INDEX.md` routes them, which
  satisfies the intent. The checklist could say "or".
