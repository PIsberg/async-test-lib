# Support, versioning and end-of-life policy

What a team adopting this library can rely on, and for how long. Written to be answerable in a
dependency review rather than to be reassuring.

## Versioning

The project follows [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html) for the
`async-test-lib` artifact's public API.

| Change | Version bump |
|---|---|
| Removing or narrowing a public signature | major |
| Removing a `DetectorType` constant | major |
| Changing a detector's default from on to off | major |
| Renaming a log event asserted in `ConcurrencyRunnerLogContractTest` | major |
| Adding a detector, a config option, an overload | minor |
| A detector firing on a case it previously missed | minor |
| Fixing a detector that fired on correct code | patch |
| Report wording, log wording at DEBUG, docs | patch |

Binary compatibility against the previous release is enforced by
[japicmp](https://siom79.github.io/japicmp/) in the `async-test-lib` module's build, not by
review. A breaking change that has not had its major bump fails the build.

**What is not covered.** Anything in a package containing `internal`, the `spi.adapters` package,
the exact text of a report or an assertion message, and the `async-test-agent` and
`async-test-analysis` artifacts' internals. The agent's `-javaagent` contract and the analysis
module's `StaticPinningScanner` entry point *are* covered.

**Detector counts are not an API.** The number of detectors goes up. Code that depends on
`DetectorType.values().length` will break, by design.

## Java baseline

| | Supported |
|---|---|
| Compiles and runs on | JDK 21 and later |
| Verified in CI on | JDK 21, JDK 25 |
| Bytecode target | 21 |

Raising the baseline is a major-version change. Detectors for features newer than the baseline
(the FFM API, JDK 25/26 concurrency types) are written against `Object` and reflection so they
compile on 21 and light up on the JDK that has the feature; they report nothing rather than failing
where it is absent.

## JUnit

See the compatibility matrix in [BUILDING.md](BUILDING.md#junit-compatibility). The supported range
is verified in CI against the `consumer-fixture` module rather than asserted here.

## Release cadence and support window

There is no fixed cadence. Releases happen when there is something worth releasing.

| Line | Status |
|---|---|
| Latest minor of the current major | Supported: bug fixes, security fixes |
| Previous minor of the current major | Security fixes for 6 months after the next minor ships |
| Previous major | Security fixes for 12 months after the next major ships |
| Anything older | Unsupported |

"Supported" means a fix ships in a new patch release. It does not mean a fix within a fixed number
of days; this is a small project and that promise would not be credible.

## Security

Report vulnerabilities as described in [SECURITY.md](../SECURITY.md). Do not open a public issue for
a vulnerability.

## Deprecation

A public element is deprecated for at least one minor release before removal, carrying
`@Deprecated` and a javadoc `@deprecated` line naming the replacement. Removal happens in the next
major. The per-detector `detectXxx()` booleans on `@AsyncTest` are deprecated in favour of
`preset()` / `includes()` / `excludes()` and are on that path.

## Commercial licensing

The library is published under the PolyForm Noncommercial License 1.0.0. Commercial use requires a
licence key; see [LICENSING.md](LICENSING.md) for how the gate behaves and what to configure. The
licensing model is not covered by the versioning policy above, and changes to it are announced in
the changelog.
