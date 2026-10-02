#!/bin/sh
# Regenerates the guardrail files from the @AI* annotations and fails when they differ from the
# committed ones (CI) or the staged ones (the pre-commit hook).
#
# One script for both callers, so they cannot disagree on which files count (#869).
# GuardrailDriftWiringTest pins that .github/workflows/guardrails.yml and .pre-commit-config.yaml
# both call it, and that the path list lives only here.
#
# Usage:  sh tools/guardrail-drift.sh [--ignore-whitespace]
#
# --ignore-whitespace is for the local hook. A regeneration on another OS or JDK can differ from
# CI's in line endings or a trailing blank line alone, and a hook that fails on those is a hook
# people switch off. CI runs without it and stays byte-exact.
#
# Never fix a failure by editing inside VIBETAGS-START/END: change the annotation and rerun.
set -eu

paths="CLAUDE.md GEMINI.md AGENTS.md .claudeignore .vibetags-roles .vibetags-root-index .gemini
       .vibetags-mod-async-test-lib .vibetags-mod-async-test-agent .vibetags-mod-async-test-analysis
       async-test-lib/CLAUDE.md async-test-lib/.claudeignore async-test-lib/.vibetags-roles async-test-lib/.claude/rules
       async-test-agent/CLAUDE.md async-test-agent/.claude/rules
       async-test-analysis/CLAUDE.md async-test-analysis/.claude/rules"

diff_opts=""
if [ "${1:-}" = "--ignore-whitespace" ]; then
    diff_opts="--ignore-space-at-eol --ignore-blank-lines"
fi

# test-compile, not compile: the processor also runs over the test sources, and the generated
# files describe both. A check that only compiled main would pass a tree whose test-source
# annotations had drifted. clean, because a partial or up-to-date build regenerates only some
# module regions of the root CLAUDE.md.
mvn -B -q clean test-compile -Djacoco.skip=true

# Against the index, not HEAD: in CI the two are the same, and locally a regenerated file the
# commit leaves unstaged is exactly the drift this exists to catch. A newly generated file is
# untracked, which git diff cannot see, so it is listed separately.
# shellcheck disable=SC2086
untracked=$(git status --porcelain --untracked-files=all -- $paths | grep '^??' || true)
# shellcheck disable=SC2086
if [ -n "$untracked" ] || ! git diff --quiet $diff_opts -- $paths; then
    echo "::error::Guardrail files are out of date with the annotations. Run a clean build (mvn clean test-compile) and commit the regenerated files; never edit inside VIBETAGS-START/END by hand."
    if [ -n "$untracked" ]; then
        echo "$untracked"
    fi
    # shellcheck disable=SC2086
    git --no-pager diff $diff_opts -- $paths
    exit 1
fi
echo "Guardrail files regenerate to the committed files."
