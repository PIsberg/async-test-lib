# Downstream Regression Sweep

The library's own CI proves the library builds. It does not prove the library can be *upgraded
to*, which is a different claim and the one that has actually failed here. Every release is
therefore swept through the projects that consume it, against the artifact on Maven Central rather
than a working tree.

This document records what the sweep is, why each rule in it exists, and the result of the most
recent run. The procedure lives in
[`.claude/skills/regression-test/SKILL.md`](../../.claude/skills/regression-test/SKILL.md); this is
the write-up.

## What is swept

Three unrelated projects use `@AsyncTest` for their own reasons. None is a test fixture: they were
written to do a job, and their concurrency is whatever that job required. A fixture written to
exercise a detector will exercise it; a homomorphic-encryption library will not politely arrange
itself around one.

| Project | What it is | Concurrency under `@AsyncTest` | Build |
|---|---|---|---|
| **BlindBean** | Fully homomorphic encryption for Java, over Microsoft SEAL through Project Panama's FFM | 7 classes, 29 methods: ciphertext lifecycle and close races, key rotation under load, the Paillier signed path, and the FHE native bridge | Maven |
| **VibeTags** | AI guardrails for Java: an annotation processor generating agent-facing rule files from `@AI*` annotations | 5 classes: guardrail file writer, module sidecar, write cache, logger - all written concurrently by a processor running inside javac | Maven **and** Gradle |
| **Skill3** | A fully local AI skill relearner | 1 class, 8 methods: shared `ObjectMapper`, process-resource management, a parallel retrieval path, several stateless-under-concurrency claims | Gradle |

Two of them are places where a false positive is expensive: BlindBean's bridge is native code where
a race is a segfault rather than a wrong answer, and VibeTags' processor runs on javac's threading
model, which is somebody else's. In both, the maintainer cannot easily prove the detector wrong.

## The rules, and the failure each one prevents

**Sweep the published artifact, never the working tree.** The repo's pom stays on the version it
just released, so a local `mvn install` writes a jar into `~/.m2` under the *release's*
coordinates. Every consumer on the machine then resolves the working tree instead of the artifact
its users get. On 2026-08-08 that produced a confident, wrong account of which release removed a
detector constant - the sweep's central finding, derived from the wrong bytes. The preflight now
sha1-checks every module against Central and moves impostors aside before anything downstream is
believed.

**Prove which jar was resolved; do not infer it.** Each build is asked what it actually put on the
classpath, and the answer is checked against Central's sha1. "It went green" and "it went green
against the release" are different claims.

**Count the async classes from the result files.** Gradle prints `BUILD SUCCESSFUL` without listing
a single test, so stdout cannot answer whether the concurrency suite ran at all. The counts below
come from `build/test-results/test/*.xml` and from Surefire's output, matched against the classes
found in each repo's sources.

**Use the tag the project's own suite hides behind.** Most of VibeTags' `@AsyncTest` classes carry
`@Tag("e2e")`. A plain `mvn test` reports 957 green tests having run one of its five async classes,
and would sign off on a bump that never touched the other four.

**Bump every build system a project declares, or none.** VibeTags declares the dependency in Maven
and reads it into Gradle through `pomVersion`, so both tiers must move together; leaving one behind
puts them on different detector engines.

**Branch in a worktree, never in the checkout.** All three repos were on unrelated branches and two
had uncommitted work. A sweep that stomps somebody else's checkout has cost more than it found.

## Result: 1.9.8, 2026-08-25

Swept against `se.deversity.async-test-lib:async-test-lib:1.9.8` on Maven Central
(`sha1 429ba0481676705964ffba6aa1a83be57c0ccb35`), verified equal to the resolved jar.

**The preflight was not a formality on this run.** All three modules in `~/.m2` were local builds
sitting under the 1.9.8 coordinate, each with a different sha1 from Central. Without eviction the
sweep would have gone green against a working tree while reporting on the release.

### Per repo

| Repo | From | Tests | Skipped | `@AsyncTest` classes | Build |
|---|---|---:|---:|---|---|
| [BlindBean](https://github.com/PIsberg/blindbean/pull/171) | 1.9.6 | 223 | 0 | **7 of 7**, 29 methods | Maven |
| [VibeTags](https://github.com/PIsberg/vibetags/pull/484) | 1.9.4 | 2345 | 0 | **5 of 5** | Maven `-Pe2e` |
| VibeTags | 1.9.4 | 2345 | 0 | **5 of 5** | Gradle `-Pe2e` |
| [Skill3](https://github.com/PIsberg/skill3/pull/80) | 1.9.4 | 225 | 2 | **1 of 1**, 8 methods | Gradle |

**No failures, no errors, and no change to any consumer beyond the version string.** Two of the
three were four releases behind, so the sweep exercised 1.9.5 through 1.9.8 in one step: eleven new
detectors (135 to 146), the `collections=true` agent option, the lockset model for `synchronized`
methods and for `ReentrantLock`, `StampedLock` and read-write locks, and the dynamic-attach fix.

Skill3's two skips are pre-existing environment assumptions, neither in an `@AsyncTest` class:
`LiveLearnIT` needs `SKILL3_LIVE=1` and `SkillSpectorIntegrationTest` needs SkillSpector installed.

### What this run does and does not prove

1.9.8 changed three detector behaviours in ways that could plausibly have moved a downstream
result, and none of them did:

- `CacheConcurrencyDetector` widened from `instanceof ConcurrentHashMap` to the `ConcurrentMap`
  contract, so any correct concurrent map stops being reported as a non-thread-safe cache;
- `AtomicityValidator` narrowed: a settled single-check cache is excused only when the value it
  published then goes quiet, so a double-submit shaped like a view cache keeps its finding;
- `JdbcConnectionSharedDetector` gained an ownership model, so a pooled connection handed to one
  thread at a time is no longer reported as sharing.

A clean sweep says those changes did not disturb three real suites. It does not say the detectors
found anything new in them - none of these projects had a concurrency defect for 1.9.8 to catch,
and a sweep is a regression check, not a discovery run.

**Numbers are not comparable across runs.** VibeTags reported 2296 tests in a pre-release smoke
test and 2345 here; the difference is three commits that landed in VibeTags' own `main` between the
two runs, not anything the library did. Comparing raw totals across different consumer commits is a
false signal in either direction.

### The pre-release smoke test

A sweep needs a published artifact, so it can only run after the tag. To catch breakage before the
tag instead of after, the same three consumers are also run against the working-tree build, in
worktrees, with nothing committed - explicitly labelled as a smoke test, never as a sweep. For
1.9.8 that ran clean on all three and was recorded on
[PR #335](https://github.com/PIsberg/async-test-lib/pull/335).

It is a weaker claim by construction: the artifact under test is not the artifact users resolve,
and it says nothing about the upgrade path. Its value is timing, not rigour.
