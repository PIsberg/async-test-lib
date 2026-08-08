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

## 2. Discover the consumers

```bash
bash .claude/skills/regression-test/find-consumers.sh
```

One tab-separated line per declaration: `repo`, `file`, `current-version`. It reads whatever
branch each repo happens to have checked out, which is not necessarily `main` — treat the
version it reports as "what that working tree says today", and re-read from `origin/main` once
you are on a clean branch.

Anything it prints that section 3 does not describe is a new consumer. Add it there.

## 3. The known consumers

Everything in this table was measured, and each column exists because getting it wrong produces
a green run that proves nothing.

| Repo | Declares it in | Build command | Async classes |
|---|---|---|---|
| `blindbean` | `blindbean-tests/pom.xml` (bare `<version>`) | `./mvnw -B -pl blindbean-tests -am test` | 7 |
| `vibetags` | `vibetags-parent/pom.xml` (property) **and** `vibetags/build.gradle` (literal) | see below | 4 |
| `skill3` | `build.gradle` (literal) | `./gradlew --no-daemon test` | 1 (8 methods) |

`codekarta` is **not** a consumer — checked 2026-08-08, no dependency and no `@AsyncTest`, only
a passing mention in a `vibetags-usage` skill doc. Do not go looking again unless
`find-consumers.sh` says otherwise.

### blindbean

Use `./mvnw`, not `mvn`. Its enforcer requires Maven `[3.9.0,)` and the `mvn` on PATH is 3.8.6,
so a plain `mvn` fails in the parent before it ever compiles a test.

### vibetags

Two build systems declare the dependency separately. **Bump both or neither** — the comment
beside each already says so, and bumping one leaves the tiers on different detector engines.

Build order, and the tier that matters:

```bash
cd vibetags-annotations && mvn -B -q install -DskipTests   # published to ~/.m2 for both builds
cd ../vibetags          && mvn -B test -Pe2e               # 1537 tests
cd ../vibetags          && ./gradlew --no-daemon test -Pe2e
```

**`-Pe2e` is not optional here.** Three of the four `@AsyncTest` classes carry `@Tag("e2e")`, so
a plain `mvn test` runs only `WriteCacheAsyncTest`, reports 957 green tests, and would sign off
on a bump having exercised a quarter of the async surface.

### skill3

Plain `./gradlew --no-daemon test`. No tag split.

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

Open the PRs. **Do not merge them** — that is the user's call, in their repos.

## 8. Report

Per repo: previous version, new version, test counts, async classes run, PR link, and anything
skipped and why. State plainly whether the release is safe to upgrade to. If a consumer needed
a source change, that is a finding about the release, not a footnote.
