# Quality Gates

Everything that must stay green, plus the build quirks that trip up newcomers — human or agent.

On Windows/PowerShell, quote every `-D` argument (`"-Dlicense.mock.mode=true"`) or Maven parses it
as a lifecycle phase.

## Topics

Each gate lives in its own file under [`quality-gates/`](quality-gates/). Read the one you need rather than the whole set.

| Document | What it covers |
|----------|----------------|
| [test-suite.md](quality-gates/test-suite.md) | Test conventions and the license guard |
| [platforms.md](quality-gates/platforms.md) | Building on JDK 21, 25 or 26, and which operating systems a change is tested on |
| [static-analysis.md](quality-gates/static-analysis.md) | find-sec-bugs, NullAway, the other promoted checks, and the API gates |
| [ci-coverage.md](quality-gates/ci-coverage.md) | What the E2E check actually covers, and what a docs-only change runs |
| [mutation-fuzzing-benchmarks.md](quality-gates/mutation-fuzzing-benchmarks.md) | The PIT mutation gate and JVM-global versus instance state, the Jazzer fuzzing job, and the benchmarking gate |
| [examples-and-demos.md](quality-gates/examples-and-demos.md) | Running the disabled example demonstrations, and the demo recording |
| [review-lanes.md](quality-gates/review-lanes.md) | The guardrail jobs and the AI review lanes behind the invariants |
