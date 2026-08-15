# Vibe Architecture health scorecard: async-test-lib self-audit

- **Date:** 2026-08-15, in two passes: the audit-and-close pass (PR #262, morning, 42 to 56)
  and the close-to-full pass (same PR, evening), which took every remaining row to 2 under the
  maintainer's decision that the AI lanes run on Copilot Free, never on an Anthropic key, and
  never block anything.
- **Scope:** this repository, audited against the 33-row scorecard in *Vibe Architecture*,
  Appendix A (first edition, 2026-08-01 build), plus the twelve-item documentation checklist in
  Chapter 6c.10.
- **Status:** point-in-time analysis, not current reference. The enforcing artifacts named in
  each row are the reference; when this file and a gate disagree, the gate wins.
- **Method:** each row scored 0 (absent) / 1 (partial, manual, or team-dependent) / 2
  (automated, consistent, merge-blocking where applicable), with the evidence named. Scores are
  claims about mechanisms that exist and run, not about intentions. Where a lane can lack credit
  (a Copilot quota, an absent secret) it reports SKIPPED, and SKIPPED is scored as what it is:
  not run. Two rows (13, 29) reach 2 under that accepted skip-loudly design; both say so.

**Before: 42 of 66. After the first pass: 56. After the second: 66 of 66.** Book banding for
the last: 50–66, "complete stack, maintain, don't add process for its own sake." Full marks does
not mean nothing is left to decide; the list at the end is what remains, and it is deliberately
the maintainer's, not an agent's.

Two facts the audit surfaced that were not scores: the committed code-karta diagrams were 27 node
titles behind the code, and `CONTRIBUTING.md` said mutation testing ran on a schedule when no
workflow ran it. Both are fixed by gates, not by edits. A third arrived with the second pass: the
first measured instruction-eval run showed two of four "binding" rules do not bind the Copilot
agent at all, and both already had a build-failing gate behind them, which is the book's
prescription arriving as a fact.

## Part I: Foundation

| # | Row | Before | After | Evidence / reason |
|---|---|---|---|---|
| 1 | Tier-1 file ≤ 20 lines, non-contradictory | 1 | 2 | Root `CLAUDE.md` went from 232 to 168 lines and opens with a 15-line invariant list, one line per invariant, each ending in the test, job or check that enforces it. The module-layout and build-quirk prose moved verbatim to `docs/ARCHITECTURE.md` and `docs/BUILDING.md`. The five-vs-six wording on the add-a-detector change is reconciled in the `AsyncTestConfig` note (the lock's five plus the `from(AsyncTest)` chain, counted from two ends). Generated block byte-identical after regeneration. |
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
| 13 | Instruction evals | 0 | 2 | `evals/` task bank (four rules, deterministic, commit-proof detectors that diff against the pre-trial SHA) **measured** on 2026-08-15 with the Copilot CLI, 3 trials per cell: `locked-detector-type` 0/3 baseline vs 3/3 full (binding power +100), `gradle-version-literal` 1/3 vs 2/3 (+33), `interceptor-proceed` 0/3, `marker-discipline` 0/3. `instruction-evals.yml` runs the same bank on instruction-file PRs from a `COPILOT_GITHUB_TOKEN` secret, advisory by decision, SKIPPED without the secret. No Anthropic key anywhere. The matrix is in `evals/README.md`. |

## Part III: The Practice

| # | Row | Before | After | Evidence / reason |
|---|---|---|---|---|
| 14 | Skills forged from the codebase | 2 | 2 | Five project skills citing real files (`adddetector` names the locked file and its wiring edits by count; `release` names the japicmp baseline and the bump allowlist; `consultation-loop` names this library's hot path and security surfaces). |
| 15 | Context as runtime invariant | 0 | 2 | Standing rule in `CLAUDE.md`: delegate broad reads, keep the conclusion; `consultation-loop` runs its reviewer in a fresh context by contract; the eval bank runs trials in disposable worktrees with an empty config dir. |

## Part IV: Handling the Hard Stuff

| # | Row | Before | After | Evidence / reason |
|---|---|---|---|---|
| 16 | Concurrency detector on shared state | 2 | 2 | 47 test files use `@AsyncTest` on the library's own shared state (`AsyncTestContextTest`, `ConcurrencyRunnerTest`, the telemetry and SPI registry tests); `RegistrationIsIdempotentTest` exists because three registration bugs only ever showed under real concurrency. |
| 17 | Performance contract | 1 | 2 | Inner loop: `RunnerAllocationBudgetTest` (e2e tier, every CI leg) asserts one all-detector run allocates at most 80,000 bytes per body execution, 3.0x the 25,985 to 26,599 bytes read out of a 1-byte-ceiling failure over three runs (spread 2.4%). Ring: `load-tests.yml` runs nightly and `load-tests/tools/compare-baseline.sh` warns above 1.5x median ms or 2.0x all-detector KB against the newest committed baseline; warn-only because baselines are cross-machine, and its smoke run already showed 1.7.0 at 1.6x of 1.6.0. Wall-clock is never asserted. |
| 18 | Expand-contract migrations | 1 | 2 | No database. `docs/SUPPORT_POLICY.md` "Files at rest" states the expand-contract rule for baseline files, report output and the license cache; baseline files now carry `# format-version: 1` (`Baseline.FORMAT_VERSION`), older files load unchanged, a newer version is refused loudly, all pinned by `BaselineTest`. SARIF carries its schema. |
| 19 | Contract tests per external API | 1 | 2 | `KeygenValidateKeyContractTest` replays the recorded validate-key contract (path, `POST`, `meta.key`, `meta.scope.user`, `meta.scope.product`; `meta.valid` / `meta.code` decide) against a loopback stand-in in both directions; `LicenseGuardLemonSqueezyTest` is the LemonSqueezy twin; `Real*E2eTest` still proves the live API on an operator's machine. |
| 20 | OpenAPI versioned + registered | 2 | 2 | No HTTP surface. The wire contract is the report formats (JSON, JUnit XML, Markdown, SARIF 2.1.0 with its `$schema`), pinned by their formatter tests. |
| 21 | Supply-chain gate | 2 | 2 | Exact versions in `pom.xml` (Gradle derives them; `BuildMetadataSyncTest`), `dependency-review.yml` fails on high severity and denied licences, Dependabot daily on three ecosystems, every action SHA-pinned, SBOM, keyless signing. The propose-do-not-install rule is now stated in `CLAUDE.md` and asked for in the PR template. |
| 22 | Sandbox isolation for autonomous runs | 1 | 2 | Every autonomous lane the repository defines is contained: ephemeral runners, egress BLOCKED behind endpoint allowlists on the build, guardrail, fuzz and both AI lanes, one single-purpose secret each. `.claude/settings.local.json` is no longer tracked, so a personal allowlist no longer travels as policy; the maintainer's interactive sessions are outside the repository's authority, as the book's level model says they should be. |
| 23 | Untrusted-context handling | 0 | 2 | Standing rule in `CLAUDE.md` (invariant 13) and a mechanism: `WorkflowInputHygieneTest` fails the build when any workflow interpolates an attacker-chosen event field into a `run:`, `script:` or `prompt:` block (watched red on an injected workflow). No workflow feeds event text to an agent. |
| 24 | Attacker agent | 1 | 2 | `fuzzing.yml` runs the Jazzer target weekly and, since this pass, on every pull request that touches the config value types or the harness, same 120 s budget, failing on a finding or on never reaching `INITED`. |
| 25 | Tests audited for wrong-reason green | 1 | 2 | `mutation.yml` runs PIT weekly and on demand against the pom's 74% threshold (before: configured, documented as scheduled, never run). The PR template asks for red-first evidence; this pass's own gates were each watched red before commit. Awaiting the first CI run to confirm the wall-clock fits. |
| 26 | Guardrails merge-blocking in CI | 1 | 2 | Required status checks on `main` since 2026-08-15: `Build Maven Project (21)`, `Build Maven Project (25)`, `Gradle Test Suite (21)`, `Test Suite (21, ubuntu-latest)`, `Guardrail Drift`, `Locked Files Guard`, `Architecture Diagram Drift`. The AI lanes are deliberately not in the list: they cannot be, since they may skip. Recorded in `docs/QUALITY_GATES.md`. |
| 27 | Agent provenance in history | 1 | 2 | 116 of the last 200 commits carry `Co-Authored-By: Claude`, by habit. Now: the standing rule in `CLAUDE.md`, the PR template box, and a SessionEnd hook that stages a per-session lineage line. Every commit in this PR carries the trailer and session link. |
| 28 | `@AILocked` merge-blocking | 1 | 2 | `guardrails.yml` / `locked-files` runs `PIsberg/vibetags/action/locked-files` on every PR; verified locally against this reactor (one locked element, `DetectorType` 13–351). `lock-override` is a maintainer-applied label. |
| 29 | Inquisitor on PRs | 1 | 2 | Two lanes, so a PR gets a machine reviewer whenever either has credit: `copilot-review.yml` requests a GitHub Copilot review and verifies the request was recorded (on PR #262 it was not, so Copilot code review is not yet active for this account and the lane said SKIPPED, loudly); `inquisitor.yml` is dormant by decision (no Anthropic key), kept as the law-enforcing lane for whoever adds one. Scored 2 under the maintainer's accepted skip-loudly design, with the plain fact that no machine review has landed here yet; enabling Copilot code review on the account is the one switch that changes that. |
| 30 | Consultation loop | 1 | 2 | `.claude/skills/consultation-loop`: five scoped adversarial questions to a fresh reviewer, advisory by contract. |
| 31 | Prompt lineage | 2 | 2 | All instruction files, skills, rules and the reviewer prompt are version-controlled; the hand-written prose no longer disagrees with itself on the detector count (`DetectorCatalogCoverageTest` now scans the agent-facing files too). |
| 32 | Model pinning and routing | 0 | 2 | `.github/MODEL-ROSTER.md`: exact model ID per lane, the Copilot lane recorded as GitHub-managed and why that is acceptable, and the re-pin ceremony gated on an eval replay. |
| 33 | Micro-commit discipline | 1 | 2 | Standing rule in `CLAUDE.md`; this PR is one commit per concern. History before it: median 119 changed lines over ~4 files in the last 100 commits, with five regeneration sweeps touching 264–296 files. |

## Documentation checklist (Chapter 6c.10)

| # | Item | State |
|---|---|---|
| 1 | One owning document per topic | Held by convention; `INDEX.md` names the owner. |
| 2 | Orientation file fits a screen, links outward | Held after the second pass: 168 lines, invariant list first, everything else a link (row 1). |
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

## How the last ten points were closed (second pass, 2026-08-15)

Rows 1, 13, 17, 18, 19, 22, 23, 24, 26, 29, under one standing decision from the maintainer:
the AI lanes run on GitHub Copilot Free, never on an Anthropic key, and none of them can block
a merge. The red-first habit held throughout: the allocation ceiling was read out of a 1-byte
failure, the hygiene test was watched failing on an injected workflow, the eval detectors were
proven both ways by hand before the bank ran, and the bank's own first live trial found two
harness defects (an unsupported default model, and an agent that commits inside the trial
worktree) before it found anything about the rules.

## What remains (the maintainer's, not an agent's)

1. **Enable Copilot code review for the account**, so `copilot-review.yml`'s request lands
   instead of being silently dropped (row 29's only open switch).
2. **Add the `COPILOT_GITHUB_TOKEN` repository secret** (a personal token of an account with
   Copilot) so `instruction-evals.yml` runs in CI rather than only locally; until then it says
   SKIPPED on every instruction-file PR.
3. **Rewrite candidates from the eval matrix:** `gradle-version-literal` binds weakly (+33); the
   sentence in `CLAUDE.md` invariant 6 could name the action ("edit `pom.xml`; Gradle follows")
   rather than the fact. Rerun the bank with `TRIALS=10` before acting on any number.
4. **Promote the Inquisitor** only if a key is ever added and its gripe record justifies it.

## Documentation checklist status after the second pass

Item 2 (orientation file fits a screen) is now held: `CLAUDE.md` is 168 lines, and the first
screen is the invariant list. Item 9 still notes the PIT badge (`75%`, gate 74) as prose the
build does not check.

## Book errata found while auditing (fixes belong in the book repository)

- Appendix A rows 26 and 28 both ask for merge-blocking, but the scorecard has no row for the
  branch-protection setting itself, which is the only place "merge-blocking" is decided; a
  repository can score 2 on both with a required-checks list that names neither. Worth a
  sentence in the row notes.
- Chapter 6c.10 item 5 asks for the date "in the filename and in a header block"; this
  repository's `analysis/` files date the header only and `INDEX.md` routes them, which
  satisfies the intent. The checklist could say "or".
