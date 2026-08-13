#!/usr/bin/env bash
# Bump the async-test-lib version across every file that pins it.
#
#   bash .claude/skills/release/bump-version.sh 1.7.0
#
# Two passes:
#   1. Allowlisted files  — rewrite the current version only where it is a PIN
#                           (<version>, <*.version>, an async-test-lib coordinate, or
#                           asyncTestVersion). Prose that merely mentions the version is
#                           reported, never rewritten: this pass used to be a blind
#                           s/CURRENT/NEW/g, which moved "since 1.7.0" notes onto the new
#                           release and reattributed the broken 1.7.0-RC jars to RCs that
#                           never existed. A surviving PIN is a hard error; surviving prose
#                           is printed for a human to judge.
#   2. examples/          — replace the pin by PATTERN, whatever it currently says. Examples
#                           resolve mavenLocal() before mavenCentral(), and CI publishes the
#                           current version to mavenLocal before building them; an example
#                           pinned at an older version silently tests that old release from
#                           Central instead of this repo's code. Pattern-matching means drift
#                           cannot quietly reappear.
#
# docs/CHANGELOG.md is never touched — its version headings are history. Prose stating a
# minimum version ("requires 1.7.0+") is a floor, not a pin, and is also left alone.

set -euo pipefail

NEW="${1:-}"
if [[ -z "$NEW" ]]; then
  echo "usage: bump-version.sh <new-version>" >&2
  exit 2
fi

if [[ ! "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+|-SNAPSHOT)?$ ]]; then
  echo "error: '$NEW' is not a valid version (expected X.Y.Z, X.Y.Z-RCn, or X.Y.Z-SNAPSHOT)" >&2
  exit 2
fi

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

CURRENT="$(sed -n 's|^[[:space:]]*<version>\(.*\)</version>.*|\1|p' pom.xml | head -1)"
if [[ -z "$CURRENT" ]]; then
  echo "error: could not read current version from pom.xml" >&2
  exit 1
fi

if [[ "$CURRENT" == "$NEW" ]]; then
  echo "error: pom.xml is already at $NEW — nothing to bump" >&2
  exit 1
fi

if git rev-parse -q --verify "refs/tags/v$NEW" >/dev/null; then
  echo "error: tag v$NEW already exists" >&2
  exit 1
fi

ALLOWLIST=(
  pom.xml
  # The reactor modules pin the parent by version; miss one and Maven resolves the
  # PREVIOUS parent from Central instead of the one being released.
  async-test-lib/pom.xml
  async-test-agent/pom.xml
  async-test-analysis/pom.xml
  # gradle.properties is deliberately absent: build.gradle.kts reads the version out of
  # pom.xml, so there is nothing to bump there.
  consumer-fixture/pom.xml
  consumer-fixture/build.gradle.kts
  README.md
  # These docs carry install snippets like README.md; they sat outside the
  # allowlist until 1.9.1 and drifted three releases (pins said 1.6.0).
  docs/USAGE.md
  docs/QUICK_REFERENCE.md
  docs/DISTRIBUTION.md
  .claude/SKILL.md
)

echo "Bumping $CURRENT -> $NEW"
echo

changed=0
for f in "${ALLOWLIST[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "  ! $f (missing — skipped)"
    continue
  fi
  hits="$(grep -c -F "$CURRENT" "$f" || true)"
  if [[ "$hits" -eq 0 ]]; then
    echo "  · $f (no occurrence of $CURRENT)"
    continue
  fi
  # \Q..\E quotes the dotted version so '.' can't match arbitrary characters. The version is
  # passed through the environment rather than interpolated by the shell, so the perl source
  # below reads as perl rather than as escaped shell.
  #
  # Everything between <oldVersion> and </oldVersion> is skipped. That block is the japicmp
  # API-compatibility baseline, and it must stay pinned to the PREVIOUS release: bumping it to
  # the version being cut makes the gate compare the release against itself, and the coordinate
  # cannot resolve at all because that version is not on Central yet. Re-pinning the baseline is
  # a separate, deliberate step (docs/RELEASE.md section 2), not a side effect of bumping.
  # Without this guard the 1.9.2 bump moved the baseline off the 1.9.1 it had just been
  # re-pinned to, silently undoing the fix that release shipped.
  CURRENT="$CURRENT" NEW="$NEW" perl -pi -e '
    $in_old = 1 if m{<oldVersion>};
    unless ($in_old) {
      s{(<version>)\Q$ENV{CURRENT}\E(</version>)}{$1$ENV{NEW}$2}g;
      s{(<[A-Za-z0-9._-]+\.version>)\Q$ENV{CURRENT}\E(</[A-Za-z0-9._-]+\.version>)}{$1$ENV{NEW}$2}g;
      s{(async-test-lib:)\Q$ENV{CURRENT}\E}{$1$ENV{NEW}}g;
      s{(asyncTestVersion = ")\Q$ENV{CURRENT}\E(")}{$1$ENV{NEW}$2}g;
    }
    $in_old = 0 if m{</oldVersion>};
  ' "$f"
  echo "  ✓ $f ($hits occurrence(s))"
  changed=$((changed + hits))
done

echo
remaining="$(grep -c -F "$CURRENT" "${ALLOWLIST[@]}" 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')" || true
pins=$((changed - remaining))
echo "Rewrote $pins pin(s) across ${#ALLOWLIST[@]} allowlisted file(s)."

# The rewrite above is deliberately pin-shaped, so prose that merely mentions the version
# survives. That is the point: a "since 1.7.0" floor or a historical note about which jars
# were broken must NOT move with the release. Report them so a human judges each one.
if [[ "$remaining" -gt 0 ]]; then
  echo
  echo "$remaining prose mention(s) of $CURRENT left alone — check each one:"
  grep -n -F "$CURRENT" "${ALLOWLIST[@]}" 2>/dev/null | sed 's/^/  - /'
fi

# A pin left behind is the dangerous case: the build would resolve the PREVIOUS release.
# Skips the <oldVersion> block for the same reason the rewrite does: a baseline still on
# $CURRENT is correct, not a missed pin.
missed=""
for f in "${ALLOWLIST[@]}"; do
  [[ -f "$f" ]] || continue
  hit="$(CURRENT="$CURRENT" FILE="$f" perl -ne '
    $in_old = 1 if m{<oldVersion>};
    print "$ENV{FILE}:$.:$_"
      if !$in_old
      && /(?:<version>|\.version>|async-test-lib:|asyncTestVersion = ")\Q$ENV{CURRENT}\E/;
    $in_old = 0 if m{</oldVersion>};
  ' "$f")"
  # Not `[[ ... ]] && missed=...`: under `set -e` a false test as the last command in the loop
  # body would abort the script on the common case of finding nothing.
  if [[ -n "$hit" ]]; then
    missed="${missed}${hit}"
  fi
done
if [[ -n "$missed" ]]; then
  echo
  echo "error: pin-shaped occurrences of $CURRENT survived the rewrite:" >&2
  echo "$missed" | sed 's/^/  - /' >&2
  exit 1
fi

# Pass 2 — examples pin by pattern, so a stale pin is corrected even if it wasn't at $CURRENT.
echo
echo "Realigning examples/ pins (by pattern):"

ex_poms=0
for f in examples/*/pom.xml; do
  [[ -f "$f" ]] || continue
  if perl -pi -e "BEGIN{\$c=0} \$c += s|<async-test-lib\.version>[^<]*</async-test-lib\.version>|<async-test-lib.version>$NEW</async-test-lib.version>|g; END{exit 0}" "$f"; then
    ex_poms=$((ex_poms + 1))
  fi
done

ex_gradle=0
for f in examples/*/build.gradle.kts; do
  [[ -f "$f" ]] || continue
  perl -pi -e "s|^val asyncTestVersion = \"[^\"]*\"|val asyncTestVersion = \"$NEW\"|" "$f"
  ex_gradle=$((ex_gradle + 1))
done

echo "  ✓ examples/*/pom.xml           ($ex_poms file(s))"
echo "  ✓ examples/*/build.gradle.kts  ($ex_gradle file(s))"

# Fail loudly if any example pin did not land on $NEW — a silent miss means that example
# would test an old release from Central rather than this build.
stray="$( { grep -rh -o "<async-test-lib\.version>[^<]*" examples/*/pom.xml 2>/dev/null | sed 's|.*>||'
           grep -rh -oE '^val asyncTestVersion = "[^"]*"' examples/*/build.gradle.kts 2>/dev/null | sed -E 's|.*"([^"]*)"|\1|'
         } | sort -u | grep -v -x -F "$NEW" || true )"
if [[ -n "$stray" ]]; then
  echo
  echo "error: some example pins are not at $NEW after the rewrite:" >&2
  echo "$stray" | sed 's/^/  - /' >&2
  exit 1
fi

# Surface anything else still referencing the old version, so a human can judge it.
echo
echo "Other tracked files still mentioning $CURRENT (review manually — NOT auto-bumped):"
others="$(git grep -l -F "$CURRENT" -- . \
  ':(exclude)docs/CHANGELOG.md' \
  ':(exclude)examples' \
  "${ALLOWLIST[@]/#/:(exclude)}" 2>/dev/null || true)"
if [[ -z "$others" ]]; then
  echo "  (none)"
else
  echo "$others" | sed 's/^/  - /'
fi

echo
echo "Excluded by design: docs/CHANGELOG.md (history) and minimum-version prose ('requires X+')."
echo "Next: update docs/CHANGELOG.md, then run the verify gates."
