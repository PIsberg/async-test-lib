# Guardrail and review lanes

Part of the [Quality Gates guide](../QUALITY_GATES.md).

## Guardrail and review lanes

The gates that keep the agent-facing layer honest, and the review lanes that read a PR before a
human does. All added 2026-08-15 after a self-audit against the *Vibe Architecture* health scorecard
([analysis/vibe-architecture-scorecard.md](../analysis/vibe-architecture-scorecard.md)); each one turned
a property that held by habit into one that fails a build.

| Lane | Workflow | Runs on | Fails when |
|---|---|---|---|
| Guardrail drift | `guardrails.yml` / `guardrail-drift` | push, PR | a clean `test-compile` regenerates any committed guardrail file (`CLAUDE.md`, `GEMINI.md`, `.claude/rules`, `.gemini/rules`, `.claudeignore`, `.vibetags-*`) differently from what is committed. Fix: build, commit the regenerated files; never edit inside the markers |
| Locked files | `guardrails.yml` / `locked-files` | PR | the diff touches an `@AILocked` element (today `DetectorType`, lines 13–351). A maintainer who has read the wiring applies the `lock-override` label; the guard then reports instead of failing. The label is the escalation path, applied by the person merging, never by the diff |
| Diagram drift | `guardrails.yml` / `diagrams` | push, PR | the code-karta SVGs regenerate with a different set of node titles than the committed ones (`tools/diagram-structure.sh`). Fix: `sh tools/generate-architecture-diagrams.sh`, commit; the failing run attaches the fresh SVGs |
| Core flows (BDD) | `CoreFlowsBddTest` (`-P e2e`, so every CI leg) | every CI build | a scenario in `async-test-lib/src/test/resources/features/core-flows.feature` has no binding, a binding has no scenario, or a scenario's assertions fail against the real engine. Five scenarios: body runs N x M times, a finding fails on `failOn = HIGH`, report-only stays green, an excluded detector reports nothing, `invocations = 0` is refused |
| Docs routing | `DocsIndexCoverageTest` (default `mvn test`) | every build | a document under `docs/` is not linked from `docs/INDEX.md`, or a relative link in the doc set does not resolve |
| Workflow input hygiene | `WorkflowInputHygieneTest` (default `mvn test`) | every build | a workflow interpolates untrusted event text (issue or PR title/body, comment body, commit message, branch name) into a `run:`, `script:` or `prompt:` block. Pass it through `env:` instead |
| Maven transfer retry | `MavenTransferRetryTest` (default `mvn test`) | every build | `.mvn/maven.config` stops carrying `retryHandler.requestSentEnabled=true` for both the `aether.connector.http.*` and `maven.wagon.http.*` families, gains a line Maven 3.8 cannot parse, gains a CR that would turn `true` into `true\r`, or a workflow step invokes Maven after a `cd` or under `working-directory:`, where the config no longer applies |
| Allocation budget | `RunnerAllocationBudgetTest` (`-P e2e`, every CI leg) | every CI build | one all-detector `@AsyncTest` run allocates more than 80,000 bytes per body execution (3.0x the 25,985 to 26,599 measured on 2026-08-15; re-derive the same way, red first) |
| Keygen contract | `KeygenValidateKeyContractTest` (default `mvn test`) | every build | the request to a loopback stand-in stops matching the recorded validate-key contract (path, `POST`, `meta.key`, `meta.scope.user`, `meta.scope.product`), or a `meta.valid=false` answer, or a body without `meta.valid`, admits a run. `LicenseGuardLemonSqueezyTest` is the LemonSqueezy twin |
| Load-test trend | `load-tests.yml` + `load-tests/tools/compare-baseline.sh` | push, PR, nightly 04:00 UTC | never; prints `::warning::` when a fresh sweep row exceeds 1.5x (median ms) or 2.0x (all-detector KB) of the newest committed baseline. A trend line, cross-machine, so warn-only by design |
| Mutation | `mutation.yml` | Sundays, dispatch | the PIT score drops below the pom's `mutationThreshold` (76) |
| Fuzzing | `fuzzing.yml` | Mondays, dispatch, and PRs touching the config surface or the harness | Jazzer finds an input that breaks `AsyncTestConfig.Builder`, or never reaches `INITED` |
| Example demonstrations | `example-demos.yml` | Tuesdays, dispatch | a `@Disabled` example demonstration **passes** in every run once enabled. The inversion is the design, which is why it cannot live in the examples pipeline. See [examples-and-demos.md](examples-and-demos.md) |
| Inquisitor | `inquisitor.yml` | PR | the adversarial reviewer (`.github/INQUISITOR.md`, model pinned in `.github/MODEL-ROSTER.md`) writes a violation against the committed law. Optional lane: skips loudly without `ANTHROPIC_API_KEY`, and the repository does not carry that secret by decision (2026-08-15: Copilot Free is the AI lane; nothing blocks on paid tokens) |
| Copilot review | `copilot-review.yml` | PR opened | never; it requests a GitHub Copilot review, verifies the request was recorded, and reports SKIPPED otherwise (it did on PR #262: no review request was recorded, so Copilot code review is not active for this account yet). Advisory by design |
| Instruction evals | `instruction-evals.yml` | PR touching the instruction files, dispatch | never; runs `evals/` on the Copilot CLI (`COPILOT_GITHUB_TOKEN` secret, the maintainer's Copilot Free quota) and prints the adherence table plus a `::warning::` per rule below its floor. Advisory by decision: each measured rule has an enforcing gate behind it, and those block. Skips loudly without the secret or on exhausted quota. First measured run 2026-08-15, locally: `evals/README.md` |
