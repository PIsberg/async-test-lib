# What CI runs for a change

Part of the [Quality Gates guide](../QUALITY_GATES.md).

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
| Examples Reactor | push to `main`/`develop`, and nightly | All 148 example projects, four shards |

**The PR filter used to ask the wrong question.** It watched `examples/**` only, so it answered
"did you edit an example?" when what matters is "could you have broken the examples?", and the
148 examples all consume the built artifact. A library-only PR therefore ran zero of them and went
green, with any breakage surfacing after merge or overnight.

`examples-detect` now also watches `async-test-*/src/main/**` and the root build files. A library
change runs a deterministic every-4th sample, 37 of the 148 examples, rather than the full
reactor: enough to catch a systemic break at PR time, cheap enough to afford on every library PR.
A PR that changes both gets the union, deduplicated. `gradle-tests.yml` mirrors this exactly.

The sample is a sample, not coverage. The full reactor on push and nightly is still what proves
all 148 examples build; this only moves discovery of the common failure earlier.

## What a docs-only change runs

Six workflows carry no required status context and cannot be affected by prose, pictures or an
issue template, so since #484 they skip a diff that touches only `docs/**`, `**/*.md`,
`.github/ISSUE_TEMPLATE/**` or `LICENSE`: `e2e-tests.yml` (the largest, at roughly fifteen jobs),
`load-tests.yml`, `codeql.yml`, `inquisitor.yml`, `copilot-review.yml` and
`dependency-review.yml`.

`tests.yml`, `gradle-tests.yml` and `guardrails.yml` are deliberately **not** filtered. They
report the seven contexts `main` requires, and GitHub does not read "never ran" as "passed": a
pull request whose required check never reported sits at BLOCKED with every visible check green
and no way forward but an admin merge. That is worse than the fan-out. `corpus.yml` is not
filtered either, because `CorpusClaimsInDocsTest` reads `README.md` and
`docs/analysis/corpus-eval.md` - a prose change there is exactly what it checks.

`RequiredCheckIsNeverPathFilteredTest` holds that line: it fails if a workflow declaring a
required job name also declares a `paths` or `paths-ignore` filter on its triggers.
