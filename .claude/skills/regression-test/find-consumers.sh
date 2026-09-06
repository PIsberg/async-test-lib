#!/usr/bin/env bash
#
# Lists every repository beside this one that pins async-test-lib, and what it pins.
#
#   find-consumers.sh [root]        # root defaults to the directory holding this repo
#
# Output is one tab-separated line per declaration:
#
#   <repo>  <file>  <current-version>
#
# Discovery matters more than the hardcoded list in SKILL.md: consumers get added, and a
# sweep that only visits the repos someone remembered has unknown coverage. Anything this
# prints that SKILL.md does not describe is a gap to close, not noise.
#
# Three declaration shapes are recognised, because all three are in use and an earlier
# version of this script that handled only the first two silently missed blindbean:
#
#   1. <async-test-lib.version>1.7.3</async-test-lib.version>     Maven property
#   2. se.deversity.async-test-lib:async-test-lib:1.7.3           Gradle / TOML coordinate
#   3. <artifactId>async-test-lib</artifactId>                    Maven dependency block,
#      ... <version>1.7.3</version>                               version on a later line
#   4. ... <version>${asynctest.version}</version>                same, but through a
#      <asynctest.version>1.9.7</asynctest.version>               property of any name,
#                                                                 defined in the same file
#
# Rule 4 exists because rule 3 used to skip a ${...} version, assuming rule 1 would find
# the property elsewhere. That only holds when the property is named async-test-lib.version.
# nanometer names its <asynctest.version> and consumes it through dependencyManagement, so
# its Maven build - the one that actually runs its @AsyncTest class - was invisible here
# while the two Gradle files it also declares were reported. A sweep that bumped only what
# this printed would have left the tested build on the old version and reported a pass.
#
# Only tracked files are searched (git grep), so build output and vendored jars stay invisible.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$(cd "${HERE}/../../.." && pwd)"
ROOT="${1:-$(dirname "$SELF")}"

for repo in "$ROOT"/*/; do
    repo="${repo%/}"
    name="$(basename "$repo")"

    # `.wt-*` is where step 4 of SKILL.md parks its throwaway worktrees. Skipping them by
    # name, not by ".git is a file", keeps a repo whose main checkout happens to be a
    # worktree visible instead of silently dropping it.
    case "$name" in .wt-*) continue ;; esac

    git -C "$repo" rev-parse --git-dir >/dev/null 2>&1 || continue
    [ "$(cd "$repo" && pwd)" = "$SELF" ] && continue

    files=$(git -C "$repo" grep -l -I "async-test-lib" \
        -- '*.xml' '*.gradle' '*.gradle.kts' '*.toml' '*.properties' 2>/dev/null || true)
    [ -z "$files" ] && continue

    while IFS= read -r file; do
        [ -n "$file" ] || continue
        # The file is passed twice: pass 1 collects every <property>value</property> so a
        # ${reference} in pass 2 resolves, whatever the property happens to be named.
        awk -v repo="$(basename "$repo")" -v file="$file" '
            FNR == NR {
                if (match($0, /<[A-Za-z0-9_.-]+>[0-9][^<]*</)) {
                    kv = substr($0, RSTART, RLENGTH)
                    k = kv; sub(/^</, "", k); sub(/>.*/, "", k)
                    val = kv; sub(/^[^>]*>/, "", val); sub(/<.*/, "", val)
                    prop[k] = val
                }
                next
            }
            # 1. Maven property
            match($0, /<async-test-lib\.version>[^<]+</) {
                v = substr($0, RSTART, RLENGTH)
                sub(/.*>/, "", v); sub(/<.*/, "", v)
                print repo "\t" file "\t" v
                next
            }
            # 2. Gradle / TOML coordinate
            match($0, /async-test-lib:async-test-lib:[0-9][^"'"'"'[:space:]]*/) {
                v = substr($0, RSTART, RLENGTH)
                sub(/.*:/, "", v)
                print repo "\t" file "\t" v
                next
            }
            # 3. Maven dependency block: remember the artifactId, take the next <version>
            /<artifactId>[[:space:]]*async-test-lib[[:space:]]*<\/artifactId>/ { pending = 1; next }
            pending && match($0, /<version>[^<]+</) {
                v = substr($0, RSTART, RLENGTH)
                sub(/.*>/, "", v); sub(/<.*/, "", v)
                # A ${property} reference resolves from what pass 1 collected, so a
                # property of any name is reported rather than silently dropped.
                if (v ~ /^[$]/) {
                    k = v; sub(/^[$][{]/, "", k); sub(/[}]$/, "", k)
                    if (k in prop) v = prop[k]
                }
                if (v !~ /^\$/) print repo "\t" file "\t" v
                pending = 0
                next
            }
            # Give up after a few lines so an unrelated <version> is never attributed here.
            pending { if (++gap > 4) { pending = 0; gap = 0 } }
        ' "${repo}/${file}" "${repo}/${file}" | sort -u
    done <<< "$files"
done
