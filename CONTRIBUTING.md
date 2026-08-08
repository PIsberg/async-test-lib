# Contributing

Thanks for looking. This document covers what a change has to satisfy before it can be merged,
and the small number of places where this repository does things differently from the default.

## Before you start

- **Open an issue first for anything that changes behaviour.** A detector's firing rule, a report
  format, a public signature: these have downstream consumers, and it is cheaper to disagree about
  the design in an issue than in a review.
- **Typos, doc fixes and test additions need no issue.** Send the pull request.
- **Do not commit to `main`.** Branch, then open a pull request.

## Build and test

Maven is canonical. Gradle is a secondary developer build for faster local iteration.

```bash
mvn test                     # local tier, e2e engine tests excluded
mvn test -P fast             # same classes, roughly 3x faster: no jacoco, 0.5C forks
mvn test -P e2e              # the full suite, which is what CI runs
mvn -Dtest=SomeTest test     # one class
```

Run without a licence key using `-Dlicense.mock.mode=true`. CI activates mock mode by itself when
no key is configured. See [docs/BUILDING.md](docs/BUILDING.md) for the rest.

## What a change has to satisfy

**Every behaviour change ships with a test.** A fix without a regression test is a bug waiting for
its second visit. Write the failing test first where you can, and say so in the pull request; where
you cannot, break the fix deliberately once and confirm the test goes red.

**Test what a caller can observe.** A test pinned to internals fails on every refactor and passes
through real defects.

**A detector needs both directions.** It must fire on genuinely buggy code *and* stay silent on the
correctly synchronized twin of that same code. A detector that only ever fires is noise, and
[docs/analysis/detector-accuracy-eval.md](docs/analysis/detector-accuracy-eval.md) tracks which
detectors currently manage both. If yours cannot stay silent on correct code, say so in its javadoc
and report at MEDIUM with wording that tells the reader what is unverified. Do not claim a verdict
the detector cannot support.

**Adding a detector is a synchronized change across nine files.** The enum constant alone compiles
and detects nothing. `AllDetectorsSpiCoverageTest` and `AsyncTestConfigBuildResolutionTest` fail if
you miss a step, and [docs/architecture/adding-a-detector.md](docs/architecture/adding-a-detector.md)
lists them in order.

**Never hand-edit between `VIBETAGS-START` and `VIBETAGS-END` markers.** That region is generated
from annotations in the source and the next compile overwrites it. Change the annotation instead.
Text outside the markers survives.

**Update the docs the change makes wrong, in the same change.** A comment justifying a workaround
that no longer exists is a defect. Counts, commands and version numbers in the docs are checked by
`BuildMetadataSyncTest`, so a stale number is a red build rather than a cosmetic problem.

**Log events asserted in a test are a contract.** `ConcurrencyRunnerLogContractTest` pins
`runner.config` and its fields; renaming one is a breaking change, not a cleanup.

## Quality gates

CI runs Checkstyle, PMD, SpotBugs with find-sec-bugs, Error Prone with NullAway, ArchUnit,
JaCoCo, CodeQL, dependency review and an OpenSSF Scorecard, on JDK 21 and 25. Mutation testing
runs on a schedule with a 75% threshold. A pull request is expected to be green before review, not
after it. If a gate fails for a reason you believe is the toolchain rather than your change, say
which gate and on which JDK — see
[docs/QUALITY_GATES.md](docs/QUALITY_GATES.md) for the cases where that has been true before.

## Commit messages and pull requests

State the failure the change prevents and how you verified it. The diff already shows what changed;
the message is for why. If tests failed on the way, say so.

## Licence

This project is published under the PolyForm Noncommercial License 1.0.0. By contributing you agree
that your contribution is licensed on the same terms, and that the maintainer may also distribute it
under a commercial licence. If that is a problem for your employer, raise it in the issue before
writing the code rather than after.
