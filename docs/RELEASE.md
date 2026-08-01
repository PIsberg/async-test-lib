# Release Process

Releases are **tag-driven**. Pushing a `v*` tag triggers
[`.github/workflows/publish.yml`](../.github/workflows/publish.yml), which builds, signs, and
publishes to **Maven Central**, then creates the GitHub Release. Everything before the tag is
preparation; the tag itself is the point of no return.

> Working in Claude Code? Run the `/release` skill (`.claude/skills/release/SKILL.md`) — it
> automates every step below, including the version bump and the preflight checks. This
> document is the manual equivalent, and the explanation of *why* each step exists.

## What the tag actually triggers

On a `v*` tag push, `publish.yml`:

1. Builds and tests on JDK 21 (Temurin).
2. Imports the GPG key and runs `mvn --batch-mode clean deploy -P release`. The `release`
   profile activates `maven-gpg-plugin`, which signs the artifacts at the `verify` phase.
3. Uploads via `central-publishing-maven-plugin`, configured with `autoPublish=true` and
   `waitUntil=published` — **no manual portal action is required**, and nothing stops the
   release once validation passes.
4. Signs each JAR with keyless cosign (Sigstore, via OIDC), producing `.bundle` files.
5. Creates the GitHub Release with the three JARs and their cosign bundles.

Required repository secrets: `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
`MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`.

There is no `<distributionManagement>` in `pom.xml` — the Central plugin handles publication.
The library is **not** published to GitHub Packages.

## Versioning

Semantic versioning, with `-RCn` for release candidates:

- **MAJOR** (`2.0.0`) — breaking change to public API. That surface is broad: `@AsyncTest`
  attributes, `AsyncTestContext`, the `Detector` / `DetectorFactory` SPI, `Formatter`,
  `Violation`, and `AsyncTestListener` are all consumer-facing (see `CLAUDE.md`).
- **MINOR** (`1.8.0`) — new detectors or backward-compatible features.
- **PATCH** (`1.7.1`) — bug fixes, including detector false-positive fixes.
- **RC** (`1.8.0-RC1`) — pre-release for validation. RCs go to Central like any other version
  and cannot be re-cut once uploaded.

## Where the version lives

`pom.xml` is **canonical**. These must all move together:

| File | What |
| --- | --- |
| `pom.xml` | `<version>` — canonical |
| `async-test-lib/pom.xml`, `async-test-agent/pom.xml`, `async-test-analysis/pom.xml` | `<parent><version>` — the reactor modules |
| `gradle.properties` | `version=` — the Gradle build reads it from here |
| `consumer-fixture/pom.xml` | own `<version>` + `<async-test.version>` |
| `consumer-fixture/build.gradle.kts` | `asyncTestVersion` |
| `README.md` | Maven + Gradle install snippets |
| `.claude/SKILL.md` | Maven + Gradle install snippets |
| `examples/*/pom.xml` | `<async-test-lib.version>` (115 files) |
| `examples/*/build.gradle.kts` | `val asyncTestVersion` (85 files) |

`bash .claude/skills/release/bump-version.sh <version>` rewrites all of them.

**The example pins are load-bearing, not cosmetic.** Examples resolve `mavenLocal()` before
`mavenCentral()`, and CI runs `./gradlew publishToMavenLocal` before building them. An example
pinned at anything other than the current version silently resolves that **old release from
Maven Central** and tests code that is not in this repo — the example passes while proving
nothing about the current build. Keep them aligned.

Deliberately **not** rewritten by the bump script:

- `docs/CHANGELOG.md` version headings — that is history.
- Prose stating a *minimum* version ("requires async-test-lib 1.7.0+") — semantically a floor,
  not a pin.

## Releasing

### 1. Preflight

On `main`, clean tree, not behind `origin/main`:

```bash
git switch main && git fetch origin && git status -sb
git status --porcelain     # must be empty
```

A modified `CLAUDE.md` with an empty content diff is VibeTags CRLF noise — restore it, don't
commit it.

### 2. Bump the version

```bash
bash .claude/skills/release/bump-version.sh 1.7.0
```

### 3. Update the changelog

In `docs/CHANGELOG.md`, turn `## [Unreleased]` into `## [<version>] - <YYYY-MM-DD>` and add a
fresh empty `## [Unreleased]` above it. Keep the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) section names.

### 4. Verify locally

The tag publishes, so a red build after tagging means a burned version. Run what CI runs:

```bash
mvn --batch-mode clean verify "-Dlicense.mock.mode=true"
```

That one command is the whole gate. Checkstyle, PMD and SpotBugs are bound to the `verify`
phase (`<goal>check</goal>` in `pom.xml`), so `verify` runs all three across every module —
there is no separate command to run, and a clean `verify` means the static analysis passed.

**Do not** invoke them as bare goals (`mvn checkstyle:check pmd:check spotbugs:check`). Since
the build became a reactor, that fails before it checks anything:

```
Could not find artifact se.deversity.async-test-lib:async-test-lib:jar:<version> in central
```

A bare goal invocation does not build the sibling modules, so `async-test-agent` tries to
resolve the library from Central — where the version being released does not exist yet. The
failure looks like a broken release and is not one.

Local quirks:

- Quote `-D` args in PowerShell, or Maven parses them as lifecycle phases.
- Local runs need `-Dlicense.mock.mode=true`; CI mocks automatically via the `CI` env var.
- The full suite always runs — Surefire ignores `-Dtest=` filters here.

To exercise the examples against the staged library the way CI does:

```bash
./gradlew publishToMavenLocal -x test
CI=true ./gradlew -p examples test --parallel --continue
```

### 5. Tag and push

```bash
git add -A
git commit -m "chore(release): 1.7.0"
git tag -a v1.7.0 -m "Release 1.7.0"
git push origin main
git push origin v1.7.0      # triggers the publish
```

Push the commit **before** the tag, so the workflow builds a commit that exists on `main`.

### 6. Verify the release

```bash
gh run watch "$(gh run list --workflow=publish.yml --limit 1 --json databaseId -q '.[0].databaseId')"
gh release view v1.7.0
```

Maven Central takes ~15–30 minutes to surface the artifact at
<https://repo1.maven.org/maven2/se/deversity/async-test-lib/async-test-lib/>.

Verify a cosign signature:

```bash
cosign verify-blob --bundle async-test-lib-1.7.0.jar.bundle \
  --certificate-identity-regexp 'https://github.com/PIsberg/async-test-lib' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  async-test-lib-1.7.0.jar
```

## When a release fails

Fix forward. Which recovery applies depends on whether the Central upload succeeded.

**Failed before the Central upload** (compile, test, GPG import, egress block) — the version is
untouched and the tag can be moved:

```bash
git tag -d v1.7.0
git push origin :refs/tags/v1.7.0
# commit the fix, then re-tag and re-push
```

**Failed after the Central upload succeeded** — the version is **burned**. Central does not
permit re-releasing a version, and consumers may already have resolved it. Do not retry the
same number; bump to the next patch or RC and release that.

An `ECONNREFUSED` in the workflow is `harden-runner` blocking an egress host, not a network
flake. Add the host to `allowed-endpoints` in `publish.yml`.

## Maintenance

```bash
mvn versions:display-dependency-updates
mvn versions:display-plugin-updates
```

Actions are pinned by commit SHA in the workflows — update those deliberately, not
automatically.
