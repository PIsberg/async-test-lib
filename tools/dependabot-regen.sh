#!/bin/sh
# Carries a guardrail regeneration onto a Dependabot pull request
# (.github/workflows/dependabot-regen.yml). Two halves, for two jobs that must not meet:
#
#   sh tools/dependabot-regen.sh patch OUT
#       In the PR's checkout, after tools/guardrail-drift.sh has regenerated and found drift:
#       writes the regenerated guardrail files as a binary git patch to OUT. Runs in the job that
#       builds the PR's code, which holds no secret.
#
#   sh tools/dependabot-regen.sh apply DIR PATCH
#       Applies PATCH to the checkout in DIR and stages it, refusing the whole patch if any file
#       in it lies outside tools/guardrail-drift.sh's path list. Runs in the job that holds the
#       push token, from main's copy of this script, so the only thing that job takes from the PR
#       is the patch, and the patch can only touch generated guardrail files.
#
# The path list is tools/guardrail-drift.sh --list-paths, never a copy: the files a regeneration
# may push are exactly the files the Guardrail Drift check compares.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
paths=$(sh "$here/guardrail-drift.sh" --list-paths)

case "${1:-}" in
    patch)
        out=${2:?usage: dependabot-regen.sh patch OUT}
        # -A so a newly generated file (untracked) is in the patch too; the drift check counts it.
        # shellcheck disable=SC2086
        git add -A -- $paths
        # shellcheck disable=SC2086
        git diff --cached --binary -- $paths > "$out"
        if [ ! -s "$out" ]; then
            echo "::error::guardrail-drift.sh reported drift, but the regenerated files diff as empty." >&2
            exit 1
        fi
        echo "Regenerated guardrail files:"
        # shellcheck disable=SC2086
        git diff --cached --stat -- $paths
        ;;
    apply)
        dir=${2:?usage: dependabot-regen.sh apply DIR PATCH}
        patch=${3:?usage: dependabot-regen.sh apply DIR PATCH}
        files=$(cd "$dir" && git apply --numstat "$patch" | cut -f3-)
        if [ -z "$files" ]; then
            echo "::error::$patch changes no file." >&2
            exit 1
        fi
        refused=""
        # One file name per line; a guardrail path never contains a space or a newline.
        for f in $files; do
            allowed=no
            for p in $paths; do
                case "$f" in
                    "$p" | "$p"/*) allowed=yes ;;
                esac
            done
            if [ "$allowed" = no ]; then
                refused="$refused $f"
            fi
        done
        if [ -n "$refused" ]; then
            echo "::error::Refusing the regeneration patch: it touches files outside the guardrail path list:$refused" >&2
            exit 1
        fi
        (cd "$dir" && git apply --index "$patch")
        echo "Applied and staged:"
        printf '  %s\n' $files
        ;;
    *)
        echo "usage: dependabot-regen.sh patch OUT | apply DIR PATCH" >&2
        exit 2
        ;;
esac
