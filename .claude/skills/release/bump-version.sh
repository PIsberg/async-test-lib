#!/usr/bin/env bash
# Bump the async-test-lib version across every file that pins it.
#
#   bash .claude/skills/release/bump-version.sh 1.7.0
#
# Two passes:
#   1. Allowlisted files  — replace the exact current version string.
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
  gradle.properties
  consumer-fixture/pom.xml
  consumer-fixture/build.gradle.kts
  README.md
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
  # \Q..\E quotes the dotted version so '.' can't match arbitrary characters.
  perl -pi -e "s/\Q$CURRENT\E/$NEW/g" "$f"
  echo "  ✓ $f ($hits occurrence(s))"
  changed=$((changed + hits))
done

echo
echo "Rewrote $changed occurrence(s) across ${#ALLOWLIST[@]} allowlisted file(s)."

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
