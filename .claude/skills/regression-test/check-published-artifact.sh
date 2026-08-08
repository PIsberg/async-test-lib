#!/usr/bin/env bash
#
# Guarantees that ~/.m2 serves the PUBLISHED async-test-lib artifacts for a version, not a
# local build wearing the same coordinates.
#
#   check-published-artifact.sh <version> [--fix]
#
# Without --fix it reports and exits 1 on any mismatch. With --fix it moves the impostor
# aside (never deletes) and refetches from Central, then re-verifies.
#
# Why this exists, and why it is step one of the regression sweep:
#
# This repository's pom stays on the version it just released, so a plain `mvn install` in
# the working tree writes a jar into ~/.m2 under the coordinates of the RELEASE. Every
# downstream repo on the machine then silently resolves the working tree instead of the
# artifact its users get. A sweep run in that state proves nothing: it is green against
# uncommitted code.
#
# This is not hypothetical. On 2026-08-08 all three modules at 1.7.3 were local builds
# (async-test-lib local sha1 c16b9349 against Central's 56e7d477), and so were the cached
# 1.6.0, 1.7.1 and 1.7.2 jars. Reading the DetectorType constant set off those cached jars
# produced a confident and wrong account of which release removed UNCOMMITTED_CHANGES.
#
# Anything that reasons about what a release contains must read a sha1-verified jar.

set -euo pipefail

VERSION="${1:?usage: check-published-artifact.sh <version> [--fix]}"
FIX="${2:-}"

MODULES=(async-test-lib async-test-agent async-test-analysis)
CENTRAL="https://repo1.maven.org/maven2/se/deversity/async-test-lib"
M2="${HOME}/.m2/repository/se/deversity/async-test-lib"
BACKUP="${TMPDIR:-/tmp}/async-test-lib-m2-impostors"

fail=0

for module in "${MODULES[@]}"; do
    dir="${M2}/${module}/${VERSION}"
    jar="${dir}/${module}-${VERSION}.jar"

    want=$(curl -fsL "${CENTRAL}/${module}/${VERSION}/${module}-${VERSION}.jar.sha1" 2>/dev/null \
        | tr -d '[:space:]' || true)
    if [ -z "$want" ]; then
        echo "  ?  ${module}:${VERSION} — not published to Central; skipping"
        continue
    fi

    if [ ! -f "$jar" ]; then
        echo "  ·  ${module}:${VERSION} — not cached locally; Maven will fetch the real one"
        continue
    fi

    got=$(sha1sum "$jar" | cut -d' ' -f1)
    if [ "$got" = "$want" ]; then
        echo "  ok ${module}:${VERSION} — matches Central (${want:0:8})"
        continue
    fi

    echo "  !! ${module}:${VERSION} — LOCAL BUILD masquerading as the release"
    echo "        local:   ${got}"
    echo "        Central: ${want}"

    if [ "$FIX" != "--fix" ]; then
        fail=1
        continue
    fi

    mkdir -p "$BACKUP"
    mv "$dir" "${BACKUP}/${module}-${VERSION}"
    echo "        moved aside -> ${BACKUP}/${module}-${VERSION}"

    mkdir -p "$dir"
    curl -fsL -o "$jar" "${CENTRAL}/${module}/${VERSION}/${module}-${VERSION}.jar"
    curl -fsL -o "${dir}/${module}-${VERSION}.pom" \
        "${CENTRAL}/${module}/${VERSION}/${module}-${VERSION}.pom"

    got=$(sha1sum "$jar" | cut -d' ' -f1)
    if [ "$got" != "$want" ]; then
        echo "        FATAL: refetch still does not match Central" >&2
        fail=1
        continue
    fi
    echo "        refetched from Central, sha1 verified"
done

if [ "$fail" -ne 0 ]; then
    echo
    echo "~/.m2 is serving at least one local build as ${VERSION}. Re-run with --fix," >&2
    echo "or the sweep will test uncommitted code and call it a released artifact." >&2
    exit 1
fi
