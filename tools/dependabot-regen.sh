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
#       Applies PATCH to the index of the checkout in DIR, then checks what was staged, which is
#       exactly what gets committed, and unstages everything and fails if any entry is not a
#       generated guardrail file. Runs in the job that holds the push token, from main's copy of
#       this script, so the only thing that job takes from the PR is the patch.
#
# What "a generated guardrail file" means: a path tools/guardrail-drift.sh --list-paths names, or a
# .md file under a directory it names (the .claude/rules and .gemini trees hold nothing else),
# with mode 100644 on both sides (no symlink, no executable, no submodule), its name made of plain
# characters only (git quotes anything else, which then fails the check), and no rename: renames
# are turned off on both sides, so a rename shows up as a deletion and an addition, each checked.
# The list is read from the drift script, never copied: the files a regeneration may push are the
# files the Guardrail Drift check compares.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
paths=$(sh "$here/guardrail-drift.sh" --list-paths)

# Prints yes when $1 is a guardrail file in the checkout $2, no otherwise.
is_guardrail() {
    for p in $paths; do
        if [ "$1" = "$p" ]; then
            echo yes
            return
        fi
        if [ -d "$2/$p" ]; then
            case "$1" in
                "$p"/*.md) echo yes; return ;;
            esac
        fi
    done
    echo no
}

case "${1:-}" in
    patch)
        out=${2:?usage: dependabot-regen.sh patch OUT}
        # -A so a newly generated file (untracked) is in the patch too; the drift check counts it.
        # shellcheck disable=SC2086
        git add -A -- $paths
        # shellcheck disable=SC2086
        git diff --cached --binary --no-renames -- $paths > "$out"
        if [ ! -s "$out" ]; then
            echo "::error::guardrail-drift.sh reported drift, but the regenerated files diff as empty." >&2
            exit 1
        fi
        echo "Regenerated guardrail files:"
        # shellcheck disable=SC2086
        git diff --cached --stat --no-renames -- $paths
        ;;
    apply)
        dir=${2:?usage: dependabot-regen.sh apply DIR PATCH}
        patch=${3:?usage: dependabot-regen.sh apply DIR PATCH}
        (cd "$dir" && git apply --index "$patch")
        staged=$(cd "$dir" && git diff --cached --raw --no-renames)
        if [ -z "$staged" ]; then
            echo "::error::$patch changes no file." >&2
            exit 1
        fi
        # One "--raw" line per entry: ":oldmode newmode oldsha newsha status<TAB>path".
        refused=$(printf '%s\n' "$staged" | while IFS= read -r line; do
            meta=$(printf '%s' "$line" | cut -f1)
            path=$(printf '%s' "$line" | cut -f2-)
            old_mode=$(printf '%s' "$meta" | cut -d' ' -f1 | tr -d ':')
            new_mode=$(printf '%s' "$meta" | cut -d' ' -f2)
            reason=""
            case "$old_mode" in 100644 | 000000) ;; *) reason="mode $old_mode" ;; esac
            case "$new_mode" in 100644 | 000000) ;; *) reason="mode $new_mode" ;; esac
            case "$path" in
                "" | *[!A-Za-z0-9._/-]*) reason="unusual path" ;;
            esac
            if [ -z "$reason" ] && [ "$(is_guardrail "$path" "$dir")" = no ]; then
                reason="not a guardrail file"
            fi
            if [ -n "$reason" ]; then
                echo "  $path ($reason)"
            fi
        done)
        if [ -n "$refused" ]; then
            (cd "$dir" && git reset -q --hard HEAD)
            echo "::error::Refusing the regeneration patch; nothing was committed. Entries outside the generated guardrail files:" >&2
            printf '%s\n' "$refused" >&2
            exit 1
        fi
        echo "Applied and staged:"
        (cd "$dir" && git diff --cached --name-status --no-renames)
        ;;
    *)
        echo "usage: dependabot-regen.sh patch OUT | apply DIR PATCH" >&2
        exit 2
        ;;
esac
