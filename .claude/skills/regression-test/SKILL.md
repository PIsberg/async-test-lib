---
name: regression-test
description: Run the downstream regression sweep — bump every repo that consumes async-test-lib to a released version, run their suites against the published artifact, fix what breaks, and open a PR per repo. Use when the user asks to regression test a release, test downstream repos, bump consumers, or check what a release broke.
---

# Downstream regression sweep

A release is only as good as what it does to the suites that depend on it. This sweep bumps
every consuming repository to a published version, runs its tests against the artifact from
Maven Central, and opens one PR per repo.

The library's own CI proves the library builds. This proves it can be *upgraded to*, which is
a different claim and the one that has actually failed.

## 0. Preflight — the artifact must be the published one

**Do this first, every time. Nothing downstream means anything until it passes.**

```bash
bash .claude/skills/regression-test/check-published-artifact.sh <version> --fix
```

This repo's pom stays on the version it just released, so `mvn install` in the working tree
writes a jar into `~/.m2` under the *release's* coordinates. Every consumer on the machine then
resolves the working tree instead of the artifact its users get, and the sweep goes green
against uncommitted code.

It is worth restating how badly this misleads. On 2026-08-08 all three modules at `1.7.3` were
local builds, and so were the cached `1.6.0`, `1.7.1` and `1.7.2` jars. Reading the
`DetectorType` constant set off those cached jars produced a confident, wrong account of which
release removed a constant — the sweep's central finding, derived from the wrong bytes.

Rules that follow from that:

- **Never** reason about a release from `~/.m2` without a sha1 check against Central.
- `mavenLocal()` sitting above `mavenCentral()` (vibetags does this) means Gradle inherits the
  same poisoning. The preflight fixes both, because both read `~/.m2`.
- The script moves impostors aside rather than deleting them. Restore from
  `$TMPDIR/async-test-lib-m2-impostors` if a local build is wanted back.

## 1. Confirm the target version is actually released

```bash
curl -s https://repo1.maven.org/maven2/se/deversity/async-test-lib/async-test-lib/maven-metadata.xml \
  | grep -E "<release>|<latest>"
```

Sweep against a **release**, not an RC and not the working tree. If the user names a version
that is not on Central, stop and say so.

### Before a release: sweep under a throwaway version

When the consumers are already on the latest release, re-sweeping it proves nothing. The useful
run is the one before the tag, while a consumer break can still change what ships. Do it without
touching any release coordinate in `~/.m2`:

1. Add a detached worktree of this repo at `origin/main`. Set `<version>` in the four reactor poms
   (`pom.xml`, `async-test-lib/`, `async-test-agent/`, `async-test-analysis/`) to a version that
   can never be published, such as `<next>-SWEEP`.
2. Install it: `mvn -B -q install -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true
   -Dpmd.skip=true -Dspotbugs.skip=true -Djapicmp.skip=true -Dcyclonedx.skip=true`.
   Check that the jar carries a class added since the last release (`jar tf`).
3. Pin each consumer worktree (section 4) to that version without committing, and run section 5.
   skill3 needs `mavenLocal()` added.
4. Delete `~/.m2/repository/se/deversity/async-test-lib/*/<next>-SWEEP` afterwards.

After the release publishes, run the normal sweep against Central and open the PRs from that
run. The release's own `mvn clean verify` can leave an `async-test-lib/<next>/*.lastUpdated`
marker in `~/.m2` from a failed lookup; delete it before that run. Used for 1.12.1 on 2026-09-17.

## 2. Discover the consumers

```bash
bash .claude/skills/regression-test/find-consumers.sh
```

One tab-separated line per declaration: `repo`, `file`, `current-version`. It reads whatever
branch each repo happens to have checked out, which is not necessarily `main` — treat the
version it reports as "what that working tree says today", and re-read from `origin/main` once
you are on a clean branch.

Anything it prints that section 3 does not describe is a new consumer. Add it there.

Most of its output is not consumers. It also lists every checkout under `/c/dev/private`,
including this repo's own worktrees (the fixtures, corpus-eval and 148 examples each, repeated
per worktree) and stale worktrees of consumers such as `vibetags-cov`, which still pin old
versions. Filter to the repo names in section 3 before reading versions.

## 3. The known consumers

Everything in this table was measured, and each column exists because getting it wrong produces
a green run that proves nothing.

| Repo | Declares it in | Build command | Async classes |
|---|---|---|---|
| `blindbean` | `blindbean-tests/pom.xml` (bare `<version>`) | `./mvnw -B -pl blindbean-tests -am test` | 7 (29 methods) |
| `vibetags` | `vibetags-parent/pom.xml` (property) **only** | see below | 6 |
| `skill3` | `build.gradle` (literal) | `./gradlew --no-daemon test` | 1 (8 methods) |
| `nanometer` | `pom.xml` (`asynctest.version` property, consumed via `dependencyManagement`) **and** `nanometer-api/build.gradle.kts` **and** `nanometer-example/build.gradle.kts` (literals) | `mvn -B -U -pl nanometer-api -am test` | 1 (2 methods) |

Measured 2026-09-17 on 1.12.1. Whole-suite totals from that sweep, for spotting a run that
quietly shrank: blindbean 223, vibetags 3122 + 6 (Maven) and 3128 (Gradle), skill3 8,
nanometer-api 11.

**Count the async classes from the source, not from this table.** The table drifts: blindbean
sat at 11 here for several releases while the source had 7, because the old count included
FHE-named tests that carry no `@AsyncTest`. Before treating a lower count as a regression:

```bash
git -C <repo> grep -l "@AsyncTest" origin/main -- '*src/test*'
```

`codekarta` is **not** a consumer — checked 2026-08-08, no dependency and no `@AsyncTest`, only
a passing mention in a `vibetags-usage` skill doc. Do not go looking again unless
`find-consumers.sh` says otherwise.

### blindbean

A fresh worktree has no `build-native/Release`, and every FHE test then errors with "Cannot
load blindbean_fhe native library" (52 errors on 2026-08-17). Point the run at the main checkout's
DLL if its native source is at the same commit: `-Dblindbean.native.path=C:/dev/private/blindbean/build-native/Release`.
Run blindbean alone: `FheAsyncConcurrencyTest` needs ~78 s for 4 tests against a 60 s per-test
budget, and it timed out once while vibetags was running beside it.

Use `./mvnw`, not `mvn`. Its enforcer requires Maven `[3.9.0,)` and the `mvn` on PATH is 3.8.6,
so a plain `mvn` fails in the parent before it ever compiles a test.

### vibetags

**One declaration, two build systems.** `vibetags-parent/pom.xml` holds the property and
`vibetags/build.gradle` reads it back with `pomVersion('async-test-lib.version')`, so the pom
is the only place to edit. This used to say "bump both or neither" because the Gradle file
carried a literal; it does not any more, and following the old instruction means hunting for a
version string that is not there. Still run **both** tiers, to confirm the Gradle side picked
the new value up.

`mvn` on PATH is 3.8.6 and vibetags' spotbugs plugin requires 3.8.9+, so a plain `mvn` fails in
`vibetags-annotations` before it compiles anything. `-Dspotbugs.skip=true` does not help: the
check is a plugin prerequisite, not an execution. vibetags has no `mvnw`, so use the Maven the
blindbean wrapper has already downloaded:

```bash
ls ~/.m2/wrapper/dists/            # apache-maven-3.9.11 was there on 2026-09-17
M39=$(echo ~/.m2/wrapper/dists/apache-maven-3.9.11/*/bin/mvn)
```

Expand the glob with `echo` as above. A bare `M39=~/.../*/bin/mvn` assignment keeps the `*`
literally, and `"$M39"` then fails with exit 127 in under a second. Building it from `ls -d` has
the same result when an `ls -F` style alias appends `*` to the executable's name. Either way no
test runs, so read the log for a test count, not just the exit code.

Build order, and the tier that matters:

```bash
cd vibetags-annotations && "$M39" -B -q install -DskipTests   # published to ~/.m2 for both builds
cd ../vibetags          && "$M39" -B test -Pe2e               # 3122 + 6 tests (2026-09-17)
cd ../vibetags          && ./gradlew --no-daemon test -Pe2e   # 3128 tests (2026-09-17)
```

The Maven tier runs two surefire executions, so the **last** `Tests run:` summary in the log reads
`6`. That is the second execution, not the total; take both summaries before calling the run
hollow. Gradle prints no count at all: sum `tests="..."` across `build/test-results/test/*.xml`.

**`-Pe2e` is not optional here.** Five of the six `@AsyncTest` classes carry `@Tag("e2e")`:
EnforcementBaselineAsyncTest, GuardrailFileWriterAsyncTest, LazyFileAppenderAsyncTest,
ModuleSidecarAsyncTest and VibeTagsLoggerAsyncTest. Only WriteCacheAsyncTest is untagged
(checked against `origin/main` on 2026-09-17), so a plain `mvn test` exercises one of the six and
still goes green. `VibeTagsLoggerConcurrencyTest` also appears in the results; it has no
`@AsyncTest` and does not count toward the six.

### skill3

Plain `./gradlew --no-daemon test`. No tag split. Its only repository is `mavenCentral()`, so a
pre-release sweep (section 1) must add `mavenLocal()` in the worktree or Gradle cannot resolve the
throwaway version. Pass `--refresh-dependencies` on the run against Central.

### nanometer

Run the Maven build. The two `build.gradle.kts` files carry their own literals: bump them in the
same commit so both builds stay on one version, and say in the PR that only the Maven build ran
locally. Confirm what resolved with
`mvn -B -pl nanometer-api dependency:tree -Dincludes=se.deversity.async-test-lib`.

## 4. Branch gently

Other agents work in these repos. Before touching a checkout:

```bash
git -C <repo> status --porcelain     # dirty? leave it alone and say so
git -C <repo> branch --show-current  # not main? do not switch it
```

If the repo is on anything other than a clean `main`, **use a worktree** so the other agent's
checkout is untouched:

```bash
git -C <repo> fetch -q origin
git -C <repo> worktree add -b chore/async-test-lib-<version> /c/dev/private/.wt-<repo>-atl origin/main
```

Always branch from `origin/main`, never from whatever is checked out, or unrelated commits ride
into the PR. Clean up with `git -C <repo> worktree remove <path>` once the PR is open.

A vibetags worktree fails that removal with `Filename too long`, and leaves the directory behind.
Delete it with a long-path-aware call, then prune:

```powershell
Remove-Item -LiteralPath '\\?\C:\dev\private\<worktree>' -Recurse -Force
git -C vibetags worktree prune
```

Do not assume a checkout stays put: during the 2026-08-08 sweep blindbean was switched to
another branch mid-run by a different agent. The pushed branch and PR were unaffected, which is
the reason to push early.

## 5. Bump, run, and check what actually ran

Bump the pin, then run the command from section 3. When it goes green, **verify the async
classes executed** — a bump that silently stops running them is the failure mode this sweep
exists to catch:

```bash
# Maven
grep -oE "in [a-z.]+\.[A-Za-z]*(Async|Concurrency)[A-Za-z]*Test" <log> | sort -u
# Gradle
ls build/test-results/test/ | grep -iE "async|concurrency"
```

Compare the count against section 3. Fewer classes than expected is a failure even if the build
is green.

## 6. When a bump breaks something

Find out whether the library or the consumer is wrong, and do not guess:

1. Read the error. A `cannot find symbol` on a `DetectorType` constant means the detector set
   changed.
2. Diff the enum across the **sha1-verified** published jars — never `~/.m2` unchecked. The
   preflight script is the pattern; download each version, verify, then
   `javap -cp <jar> se.deversity.asynctest.DetectorType`.
3. Check `docs/CHANGELOG.md` for that version. A removal recorded under **Removed — … Breaking**
   is intentional; fix the consumer. A removal nobody documented is a library defect; fix it
   here and say so.

Worked example, 2026-08-08: skill3 failed to compile at eight sites on
`excludes = DetectorType.UNCOMMITTED_CHANGES`. The verified jars showed the constant present at
1.7.0/1.7.1, still present at 1.7.2, gone at 1.7.3 — matching the CHANGELOG's 1.7.3 "Removed"
entry exactly. Intentional, so the consumer dropped the exclusion; nothing to fix here.

Note the shape of that break for the future: **removing a public `DetectorType` constant is
source-breaking for any consumer naming it in `excludes`**, and 1.7.3 shipped it as a patch
while `docs/RELEASE.md` defines a breaking public-API change as MAJOR. Flag that tension to the
user rather than quietly re-deciding the policy.

## 7. Commit, push, PR — one per repo

Branch `chore/async-test-lib-<version>`. The PR body must carry the evidence, not a claim:

- the exact test counts and the command that produced them
- which async classes ran, listed
- for a break: the compiler error, what changed upstream, why the fix is right
- that the artifact was sha1-verified against Central

Write the body to a file with a quoted heredoc (`<<'EOF'`) and pass `--body-file`. An inline
`--body "..."` lets bash run every backticked span as a command, and the PR opens with a
mangled description.

Open the PRs. **Do not merge them** — that is the user's call, in their repos.

Then follow each PR's checks to the end. `gh pr checks --watch` exits 0 when no check ran at all,
so count what passed rather than trusting the exit code:

```bash
gh pr checks <n> -R PIsberg/<repo> --json bucket --jq '.[].bucket' | sort | uniq -c
```

On 2026-09-17: blindbean 20 pass, vibetags 21 pass and 1 skipping, skill3 5 pass, nanometer 9 pass.

## 8. Report

Per repo: previous version, new version, test counts, async classes run, PR link, and anything
skipped and why. State plainly whether the release is safe to upgrade to. If a consumer needed
a source change, that is a finding about the release, not a footnote.
