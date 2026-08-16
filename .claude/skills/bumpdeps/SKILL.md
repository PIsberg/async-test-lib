---
name: bumpdeps
description: Bump every dependency, plugin and toolchain version this repository pins to the latest release, everywhere Dependabot does not look (fixtures, examples, load-tests, Gradle-only pins), then verify with the real gates and open a PR. Use when the user asks to bump, update or upgrade dependencies, or to bring the build up to date.
---

# Bump dependencies

Dependabot watches the reactor root (`pom.xml`, `build.gradle.kts`, GitHub Actions) and nothing
else. The rest of the repository pins versions by hand and drifts: on 2026-08-16 the 137
examples were on JUnit 5.10.2 while the library shipped 6.1.3, `consumer-fixture` was on 6.0.3,
and the language fixtures on toolchains one to five releases old. This skill closes that gap in
one pass and proves the result with the gates that would catch a bad bump.

Two scripts, both under `.claude/skills/bumpdeps/`:

- `latest-version.sh <groupId> <artifactId> [repo]` prints the latest release of one artifact
  from Maven Central (default) or the Gradle plugin portal, ignoring pre-releases. It reads
  `<versions>` and picks the highest non-pre-release rather than trusting `<latest>`, because
  some projects publish an `-M1` or `-alpha` there.
- `bump-deps.sh [--dry-run]` does the whole pass and prints a report. Always dry-run first.

## 1. Preflight

```bash
git rev-parse --abbrev-ref HEAD     # branch off main; never bump on main
git status --porcelain              # must be empty: the diff will touch ~280 files
```

Branch: `chore/bump-dependencies`. If the tree is dirty, stop and ask; the bump must be the only
thing in the diff or nobody can review it.

## 2. Dry-run, read the report

```bash
bash .claude/skills/bumpdeps/bump-deps.sh --dry-run
```

Three sections:

1. **Reactor properties**: what `versions-maven-plugin` would rewrite in `pom.xml`
   `<properties>`. Pre-releases are filtered (`-alpha`, `-beta`, `-RC`, `-M1`, `-jdk5`
   classifiers, `-vt-` test tags). Gradle follows automatically: `build.gradle.kts` reads these
   properties with `pomVersion(...)`, and `BuildMetadataSyncTest` fails if that derivation is
   unwound.
2. **Satellite pins**: every `file  current -> latest  (coordinate)` line the `RULES` table in
   the script knows about: `consumer-fixture/`, `consumer-fixture-langs/`, `examples/*/`,
   `load-tests/`, and the Gradle-only pins in `build.gradle.kts` (logback, the plugin ids in
   `plugins {}`). Adding a pin to the repository means adding one line to `RULES`; a pin the
   table does not know about is invisible to this skill, so when you add a new fixture or
   example toolchain, add its rule in the same PR.
3. **Manual, reported only**: the Gradle wrapper (regenerating it needs
   `gradle wrapper --gradle-version X` so `wrapper-validation` keeps passing), `intellij-plugin/`
   (no workflow builds it, so a bump there is unverifiable in CI), the japicmp `<oldVersion>`
   baseline (must stay on the previous release, `docs/RELEASE.md`), the async-test-lib version
   pins (the release skill's job), and GitHub Actions SHAs (Dependabot).

Read the report for major jumps and decide before applying. Ones that have bitten or nearly
bitten:

- **PMD engine (`pmd.version`)**: pinned independently of the plugin because the plugin's
  default engine cannot resolve JDK types on a newer JDK than the build targets
  (`docs/QUALITY_GATES.md`). A newer PMD is fine; a red PMD gate after the bump is more likely the
  JDK than the code. Re-run on the JDK CI uses before reporting a defect.
- **vibetags**: a new processor version regenerates the guardrail files (`CLAUDE.md`,
  `GEMINI.md`, `.claude/rules`, `.gemini/rules`). Delete `.vibetags-cache` and
  `async-test-lib/.vibetags-cache` before the build, then commit what it regenerates; the
  `guardrail-drift` job compares a clean regeneration with what is committed.
- **Scala** in `consumer-fixture-langs`: the rule takes the latest `scala3-library_3`, which is
  the "Next" line, not the LTS. If the Scala fixture stops compiling on the new line and the fix
  is not obvious, pin the LTS and say so in the PR; the fixture proves the language, not the
  compiler release.
- **gmavenplus** major bumps: check `<targetBytecode>` still applies; Groovy 5 refuses the
  plugin's 1.8 default.
- **JUnit in examples**: 137 poms and 137 Gradle files move together. The examples job on the PR
  builds every changed example (`examples-changed`), which is the check that matters; do not
  skip it by editing only a few.

## 3. Apply

```bash
rm -rf .vibetags-cache async-test-lib/.vibetags-cache
bash .claude/skills/bumpdeps/bump-deps.sh
git --no-pager diff --stat | tail -3
```

Look at the non-example part of the diff by hand:

```bash
git --no-pager diff -- pom.xml build.gradle.kts consumer-fixture consumer-fixture-langs load-tests | grep '^[-+] '
```

## 4. Verify, and report what actually ran

Green is not proof; report each gate distinctly, with counts. `mvn`'s exit code has read 0 on
a red run through a pipe before, so read the surefire `Tests run:` lines, not `$?`.

```bash
mvn install -DskipTests -Djacoco.skip=true            # static gates: PMD, SpotBugs, Checkstyle, Error Prone, japicmp
git status --short | grep -v examples/                # regenerated guardrails to commit
mvn -pl async-test-lib -am test -P fast               # module suite (BuildMetadataSyncTest sits here)
./gradlew test && ./gradlew publishToMavenLocal       # the derived build, then the artifact fixtures resolve
mvn -f consumer-fixture/pom.xml test && ./gradlew -p consumer-fixture test
mvn -f consumer-fixture-langs/pom.xml test && ./gradlew -p consumer-fixture-langs test
mvn -f examples/01-completablefuture-exception-handling/pom.xml test
mvn -f examples/128-kotlin-lost-update/pom.xml test    # the one example with a language toolchain
./gradlew -p load-tests compileJava compileTestJava
```

`bash .claude/skills/bumpdeps/verify.sh` runs exactly this sequence and writes one verdict line per gate to `/tmp/verify-bump.log`. If a gate is
red, fix forward or pin that one dependency back with a comment naming the failure; never drop
the gate. A pin held back on purpose belongs in the PR body.

## 5. Docs, commit, PR

- `docs/CHANGELOG.md` `## [Unreleased]`: one entry, "Changed: dependencies bumped", naming the
  notable jumps (major versions, toolchains) rather than every patch.
- `docs/DEPENDENCIES.md` does not carry versions and needs no edit unless a dependency was
  added or removed.
- Commit with the trailer, open the PR, and **watch the pipeline until every check is green**.
  The examples shard and the Gradle examples shard are the long ones on a JUnit bump.
- The PR body lists what was held back and why, and what section 3 of the report said needs a
  human (wrapper, intellij-plugin).

Do not merge; that is the user's call.
