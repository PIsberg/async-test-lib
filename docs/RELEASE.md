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

The same tag push independently triggers
[`.github/workflows/javadoc.yml`](../.github/workflows/javadoc.yml), which rebuilds the API
reference at <https://pisberg.github.io/async-test-lib/> and deploys it to GitHub Pages. It does
not wait for `publish.yml`: the version being released is built from the tagged source, and every
earlier release is restored by unpacking its published `-javadoc.jar` from Central. Nothing
generated is committed, so a failed javadoc deploy leaves the repository untouched and can be
re-run from the Actions tab. The site grows by roughly 20 MB per release; `OLDEST_KEPT` in
`.github/scripts/build-javadoc-site.sh` is the knob for dropping old versions if it ever
approaches the 1 GB Pages limit.

## Versioning

Semantic versioning, with `-RCn` for release candidates:

- **MAJOR** (`2.0.0`) — breaking change to public API. That surface is broad: `@AsyncTest`
  attributes, `AsyncTestContext`, the `Detector` / `DetectorFactory` SPI, `Formatter`,
  `Violation`, and `AsyncTestListener` are all consumer-facing (see `CLAUDE.md`).
- **MINOR** (`1.8.0`) — new detectors or backward-compatible features.
- **PATCH** (`1.7.1`) — bug fixes, including detector false-positive fixes.
- **RC** (`1.8.0-RC1`) — pre-release for validation. RCs go to Central like any other version
  and cannot be re-cut once uploaded.

`DetectorType` deserves a specific warning, because it does not look like public API and is.
Removing a constant is **source-breaking for every consumer naming it** in an `excludes`
attribute, and the break lands at compile time in their test sources, not at runtime in ours.
1.7.3 removed `UNCOMMITTED_CHANGES` this way. It was deliberate, documented in the changelog as
`Removed — … Breaking`, and enumerated in the japicmp excludes — but it shipped as a **PATCH**,
which the rule above says is for bug fixes. The 2026-08-08 downstream sweep found the casualty:
`skill3` failed to compile at eight sites, and only a source change let it move off 1.7.0-RC8.

The rule going forward: a public-API removal is MINOR at the very least, and the changelog entry
must show the compile error a consumer will see, not only the rationale for the removal. The
downstream sweep (`.claude/skills/regression-test/`) is what turns this from a guess into a
measurement — run it before calling a release safe to upgrade to.

## Where the version lives

`pom.xml` is **canonical**. The Gradle build reads the version out of it at configuration
time, so `gradle.properties` is not in this list and does not need bumping. These must all move
together:

| File | What |
| --- | --- |
| `pom.xml` | `<version>` — canonical |
| `async-test-lib/pom.xml`, `async-test-agent/pom.xml`, `async-test-analysis/pom.xml` | `<parent><version>` — the reactor modules |
| `consumer-fixture/pom.xml` | own `<version>` + `<async-test.version>` |
| `consumer-fixture/build.gradle.kts` | `asyncTestVersion` |
| `consumer-fixture-langs/pom.xml` and `consumer-fixture-langs/{kotlin,groovy,scala,clojure}/pom.xml` | own `<version>`, `<async-test.version>`, and each module's `<parent><version>` |
| `consumer-fixture-langs/build.gradle.kts` | `asyncTestVersion` |
| `README.md` | Maven + Gradle install snippets |
| `docs/USAGE.md`, `docs/QUICK_REFERENCE.md`, `docs/DISTRIBUTION.md` | The same install snippets; allowlisted since 1.9.1 after all three drifted to 1.6.0 |
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

Then re-pin the japicmp baseline in `async-test-lib/pom.xml` to the version you are releasing
*from* — i.e. the previous release, the one users are upgrading off:

```xml
<oldVersion>
    <dependency>
        ...
        <version>1.9.1</version>   <!-- the release before the one being cut -->
```

**This step is not optional, and skipping it is silent.** The baseline sat at 1.6.0 while 1.7.0
through 1.9.1 shipped, so for six releases the gate compared against an artifact that predated
every API those releases added — it could not have failed on breaking any of them, and the 1.9.1
changelog's claim that japicmp was "green against 1.9.0" described a comparison that never ran.
Bumping it *forward* to the version being cut is the other half of the same trap: the gate then
compares the release against itself, and the coordinate cannot resolve at all because it is not on
Central yet. `bump-version.sh` used to do exactly that — the 1.9.2 bump moved the baseline off the
1.9.1 it had just been re-pinned to — so the script now skips everything between `<oldVersion>` and
`</oldVersion>`, in both its rewrite and its missed-pin check. The `<oldVersion>` line therefore
appears in the script's "prose mentions" report; that is expected, not a missed pin.

A stale baseline does not report anything; it just stops protecting the API your customers pin
against.

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

### 7. Sweep the downstream repos

Publishing proves the library builds. It does not prove the release can be *upgraded to*, which
is a different claim and the one that has actually failed. Once Central has the artifact, run
the downstream sweep — the `regression-test` skill in Claude Code, or by hand:

```bash
bash .claude/skills/regression-test/check-published-artifact.sh 1.7.0 --fix   # do this first
bash .claude/skills/regression-test/find-consumers.sh
```

The preflight is not optional. Because `pom.xml` stays on the version it just released, any
`mvn install` in this working tree leaves a jar in `~/.m2` wearing the release's coordinates,
and every consumer on the machine then resolves uncommitted code while appearing to test the
release. `.claude/skills/regression-test/SKILL.md` has the per-repo commands and the test tiers
that matter.

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
