---
name: release
description: Cut a release of async-test-lib — pick the next version, bump every pinned version string, update the changelog, verify, tag, and push so the publish workflow ships it to Maven Central. Use when the user asks to release, cut a version, ship, or publish the library.
---

# Release async-test-lib

Releasing is tag-driven: pushing a `v*` tag triggers `.github/workflows/publish.yml`, which
builds, signs (GPG + keyless cosign), publishes to **Maven Central**, and creates the GitHub
Release. Your job is everything *before* the tag — the tag is the point of no return.

> `docs/RELEASE.md` is the human-readable equivalent of this skill and is kept in sync with
> it. `publish.yml` remains the ultimate source of truth for what the tag triggers.

## 1. Preflight

Refuse to continue unless all of these hold — report which one failed:

```bash
git rev-parse --abbrev-ref HEAD     # must be main
git status --porcelain              # must be empty
git fetch origin && git status -sb  # must not be behind origin/main
```

A `M CLAUDE.md` with an empty content diff is VibeTags CRLF noise — restore it, don't commit it.

## 2. Pick the next version

Read the current version from `pom.xml` (canonical) and show recent tags:

```bash
grep -m1 '<version>' pom.xml
git tag --sort=-v:refname | head -5
```

If the user already named the version, skip the question and use it. Otherwise **ask with
AskUserQuestion** — never assume the bump. Offer the options that make sense for the current
version, with the resulting tag in each description (examples below assume a current version
of `X.Y.0-RCn`):

| Option | Result | When |
| --- | --- | --- |
| Promote RC to final | `X.Y.0` | current version is an RC and it's ready |
| Next RC | `X.Y.0-RC(n+1)` | more changes needed before final |
| Minor | `X.(Y+1).0` | new detectors / backward-compatible features |
| Major | `(X+1).0.0` | breaking change to public API |

Patch (`X.Y.(Z+1)`) applies when the current version is already final. Skip options that don't
make sense for the current version rather than offering nonsense.

Sanity-check the answer: the tag `v<version>` must not already exist, and the version must
sort above the latest tag.

## 3. Bump the version strings

```bash
bash .claude/skills/release/bump-version.sh <new-version>
```

The script does two passes:

1. **Allowlisted files** — `pom.xml`, `gradle.properties`, `consumer-fixture/pom.xml`,
   `consumer-fixture/build.gradle.kts`, `README.md`, `docs/USAGE.md`, `docs/QUICK_REFERENCE.md`, `docs/DISTRIBUTION.md`, `.claude/SKILL.md` — replacing the exact
   current version string.
2. **`examples/`** — rewriting `<async-test-lib.version>` (115 poms) and `val asyncTestVersion`
   (85 gradle files) **by pattern**, then failing loudly if any pin didn't land on the new
   version.

The example pins must move with the release. Examples resolve `mavenLocal()` before
`mavenCentral()`, and CI runs `./gradlew publishToMavenLocal` before building them — so an
example pinned at an older version silently resolves that **old release from Central** and
passes while proving nothing about the current build. That drift is a bug, not a convention.

Two things the script leaves alone, and you must not work around:

- **`docs/CHANGELOG.md` version headings** — they are history.
- **Minimum-version prose** ("requires async-test-lib 1.7.0+") — a floor, not a pin.
- **The japicmp `<oldVersion>` baseline** in `async-test-lib/pom.xml` — see below.

It also reports any other file still mentioning the old version, for you to judge. The
`<oldVersion>` line will be in that report — that is expected, not a missed pin.

Then **check the japicmp baseline** in `async-test-lib/pom.xml`. It must name the *previous*
release, the one users are upgrading off:

```xml
<oldVersion>
    <dependency>
        ...
        <version>1.9.1</version>   <!-- the release before the one being cut -->
```

**Skipping this is silent.** The baseline sat at 1.6.0 while 1.7.0 through 1.9.1 shipped, so for
six releases the gate compared against an artifact predating every API those releases added — it
could not have failed on breaking any of them. A stale baseline does not report anything; it just
stops protecting the API customers pin against. Bumping it *forward* to the version being cut is
the other half of the same trap: the gate then compares the release against itself, and the
coordinate cannot resolve because it is not on Central yet. `bump-version.sh` skips the
`<oldVersion>` block for exactly that reason, so this stays a deliberate step. `docs/RELEASE.md`
section 2 carries the same instruction.

## 4. Update the changelog

In `docs/CHANGELOG.md`, convert the `## [Unreleased]` heading into
`## [<version>] - <YYYY-MM-DD>` (today's date), and add a fresh empty `## [Unreleased]`
above it. Keep the Keep-a-Changelog section names (`Added`, `Changed`, `Fixed`, `Removed`).

If `[Unreleased]` is empty, don't invent entries — derive them from
`git log --oneline v<previous>..HEAD` and show the user the draft before continuing.

## 5. Verify locally

The tag triggers a publish, so a red build after tagging means a yanked release. Run the
same gates CI runs, and **report failures verbatim rather than working around them**:

```bash
mvn --batch-mode clean verify "-Dlicense.mock.mode=true"
mvn --batch-mode checkstyle:check pmd:check spotbugs:check
```

- Quote every `-D` arg in PowerShell or Maven parses it as a lifecycle phase.
- The full suite (~133 classes, ~5 min) always runs; `-Dtest=` filters are ignored here.
- Local runs need `-Dlicense.mock.mode=true`; CI mocks automatically via the `CI` env var.

## 6. Commit, tag, push

Confirm with the user before this step — pushing the tag publishes to Maven Central, which
cannot be undone (Central does not allow re-releasing a version).

```bash
git add -A
git commit -m "chore(release): <version>"
git tag -a "v<version>" -m "Release <version>"
git push origin main
git push origin "v<version>"     # this is what triggers the publish
```

Push the commit **before** the tag, so the workflow builds a commit that exists on `main`.

## 7. Watch the publish

```bash
gh run watch "$(gh run list --workflow=publish.yml --limit 1 --json databaseId -q '.[0].databaseId')"
```

Then confirm the release exists and report the outcome plainly:

```bash
gh release view "v<version>"
```

Maven Central takes ~15–30 min to surface the artifact at
`https://repo1.maven.org/maven2/se/deversity/async-test-lib/async-test-lib/`.

An `ECONNREFUSED` in the workflow is harden-runner blocking an egress host — add it to
`allowed-endpoints` in `publish.yml`, not a flake to retry.

## If the publish fails

Fix forward. The version, the tag, and the commit are all cheap; a half-published Central
version is not.

- **Failed before the Central upload** (build, test, GPG import): fix, then move the tag —
  `git tag -d v<version> && git push origin :refs/tags/v<version>`, commit the fix, re-tag.
- **Failed after the Central upload succeeded**: the version is burned. Do not retry it —
  bump to the next patch/RC and release that instead.

Tell the user which case it is before touching any tag.
