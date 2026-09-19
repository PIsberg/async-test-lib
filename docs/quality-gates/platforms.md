# JDKs and operating systems

Part of the [Quality Gates guide](../QUALITY_GATES.md).

## Build with JDK 21, 25 or 26

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
> the pin in place. The test suite now runs there too: `tests.yml` and the e2e consumer fixture
> matrix 21, 25 and 26, so all three are supported rather than two plus a static-analysis note. A
> red PMD gate is no longer explained by the toolchain, and a failure here should be read as a real
> finding.
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

## Which operating systems a change is tested on

The `Test Suite` matrix is three JDKs on ubuntu for a pull request, and the same three JDKs
on ubuntu, windows and macos for a push to `main` and for the nightly schedule.

The windows and macos legs are `continue-on-error` and always have been, which means they
could never fail a pull request: they were six jobs of about eighteen minutes that nobody
could act on before merging, and `Build Maven Project` waited for all nine legs before it
could start. Running them where their result is read instead - on `main`, within the hour,
against a commit rather than a diff - took the median `Tests & Build` wall time from about
29 minutes back down, with no job made faster and nothing dropped from the gate (#484).

What this costs: an OS-specific break is now found after merge rather than before. That is
the same exposure the `continue-on-error` flag already accepted, made explicit and cheaper.
When a change is OS-sensitive, ask for the full matrix on the branch:
`gh workflow run tests.yml --ref <branch>`. A dispatch is not a pull request, so it runs
all nine legs.

The other half of #484 was that nine of the nineteen workflows had no `concurrency:` group, so
a force-push left the previous run alive to compete for runners with the one replacing it.
`WorkflowConcurrencyTest` holds that closed from both directions a new workflow can open it:
one check requires a top-level `concurrency:` block on every workflow triggered by a pull
request, the other requires its group to vary per branch and to cancel what it supersedes. A
group shared across branches serialises unrelated pull requests instead, which looks like a
fix and is not. `javadoc.yml` is the one documented exemption: GitHub allows a single Pages
deployment at a time, and a half-applied site is worse than a queued one. Neither check can be
replaced by watching the pipeline, because queueing fails nothing - every check still goes
green, just later.

**Skipped is not passed.** Every lane that can lack credit (Inquisitor, Copilot, evals) says
SKIPPED in its step summary when it does; a green job with a SKIPPED summary is a job that did not
run, and the required-checks list only contains lanes that cannot skip. Since 2026-09-17 the
required checks on `main` are: `Build Maven Project (21)`, `Build Maven Project (25)`,
`Gradle Test Suite (21)`, `Test Suite (21, ubuntu-latest)`, `Guardrail Drift`,
`Locked Files Guard`, `Architecture Diagram Drift`, `E2E Tests` and `Corpus Eval`. Branch
protection is repository configuration, not a file here; this sentence is the record of what was
set and why.

**The two summary checks were required by their own comments before they were required by GitHub.**
`e2e-tests.yml` and `corpus.yml` each end in an `if: always()` summary job whose stated purpose, in
both files, is to be "one stable required check" for its workflow, and the paragraph above on the
E2E check says the same. Neither context was in the list, which was read back from
`required_status_checks` on 2026-09-17 and held seven entries: both workflows ran on every pull
request to `main`, both went red when a leg failed, and neither could block a merge. The 59
`VERDICT` tiers that rest on corpus evidence, and the 148 example projects, were gated by
nothing but someone noticing a red tick. Both contexts were added the same day. A summary job is
the right thing to require precisely because it cannot skip: it runs on `always()` and reads its
legs' results, so a lane that did not run is reported through it instead of dropping out of the
list.

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
