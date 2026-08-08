#!/usr/bin/env bash
#
# Assembles the versioned javadoc site published by .github/workflows/javadoc.yml.
#
#   build-javadoc-site.sh <version> <output-dir>
#
# Layout produced:
#
#   <output-dir>/index.html                              redirect to api/latest/
#   <output-dir>/api/index.html                          version list
#   <output-dir>/api/latest/                             copy of the newest version
#   <output-dir>/api/<version>/index.html                module list for that version
#   <output-dir>/api/<version>/<module>/                 that module's javadoc
#
# The version being released comes from <module>/target/reports/apidocs, which the
# workflow generated from the checked-out source. Earlier releases are restored by
# unpacking their published -javadoc.jar from Maven Central, so the whole site is
# reproducible from the tag plus Central and nothing has to be stored in git.
#
# A module that did not exist in an older release simply has no javadoc jar for that
# version; that is expected (async-test-agent and async-test-analysis were split out
# later) and is skipped rather than failing the build.

set -euo pipefail

VERSION="${1:?usage: build-javadoc-site.sh <version> <output-dir>}"
OUT="${2:?usage: build-javadoc-site.sh <version> <output-dir>}"

GROUP_PATH="se/deversity/async-test-lib"
CENTRAL="https://repo1.maven.org/maven2/${GROUP_PATH}"
MODULES=(async-test-lib async-test-agent async-test-analysis)

# Releases older than 1.3.0 predate the current package layout and are not worth
# restoring; raising this floor is the supported way to prune the site.
OLDEST_KEPT="1.3.0"

rm -rf "$OUT"
mkdir -p "$OUT/api"

# --- the version being released, from the working tree -----------------------------

for module in "${MODULES[@]}"; do
    src="${module}/target/reports/apidocs"
    if [ ! -f "${src}/index.html" ]; then
        echo "FATAL: ${src}/index.html is missing — did 'mvn javadoc:javadoc' run?" >&2
        exit 1
    fi
    mkdir -p "${OUT}/api/${VERSION}"
    cp -r "$src" "${OUT}/api/${VERSION}/${module}"
    echo "built    ${VERSION}/${module}"
done

# --- earlier releases, from Maven Central ------------------------------------------

# Only final releases: an RC's javadoc is noise once the release exists.
released=$(
    curl -fsSL "${CENTRAL}/async-test-lib/maven-metadata.xml" \
        | grep -o '<version>[^<]*</version>' \
        | sed -e 's/<[^>]*>//g' \
        | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' \
        | sort -V
)

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for v in $released; do
    if [ "$v" = "$VERSION" ]; then
        continue
    fi
    # sort -V puts the lower of the two first, so v sorting first while differing from
    # the floor means v predates it. Written as an `if` rather than an `&&` chain: a
    # false `&&` chain is a failed command, and this script runs under `set -e`.
    oldest_of_pair=$(printf '%s\n%s\n' "$v" "$OLDEST_KEPT" | sort -V | head -1)
    if [ "$oldest_of_pair" = "$v" ] && [ "$v" != "$OLDEST_KEPT" ]; then
        continue
    fi

    for module in "${MODULES[@]}"; do
        jar="${work}/${module}-${v}.jar"
        url="${CENTRAL}/${module}/${v}/${module}-${v}-javadoc.jar"
        # -fsL, not -fsSL: a 404 here is the expected answer for a module that did not
        # exist yet, and curl's own error line would just duplicate the skip message.
        if ! curl -fsL -o "$jar" "$url"; then
            echo "skip     ${v}/${module} (no javadoc jar on Central)"
            continue
        fi
        dest="${OUT}/api/${v}/${module}"
        mkdir -p "$dest"
        unzip -q "$jar" -d "$dest"
        rm -rf "${dest}/META-INF"
        rm -f "$jar"
        echo "restored ${v}/${module}"
    done
done

# --- latest ------------------------------------------------------------------------

versions=$(ls -1 "${OUT}/api" | sort -V)
latest=$(echo "$versions" | tail -1)
cp -r "${OUT}/api/${latest}" "${OUT}/api/latest"
echo "latest   -> ${latest}"

# --- generated index pages ---------------------------------------------------------

page_head() {
    cat <<'HTML'
<!doctype html>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root { color-scheme: light dark; }
  body { font: 16px/1.6 system-ui, sans-serif; max-width: 46rem; margin: 4rem auto; padding: 0 1.5rem; }
  h1 { font-size: 1.5rem; }
  ul { padding-left: 1.2rem; }
  li { margin: .35rem 0; }
  .note { opacity: .7; font-size: .9rem; }
</style>
HTML
}

# api/<version>/index.html — the modules present in that release
for v in $versions; do
    {
        page_head
        echo "<title>async-test-lib ${v} API</title>"
        echo "<h1>async-test-lib ${v}</h1>"
        echo "<ul>"
        for module in "${MODULES[@]}"; do
            if [ -f "${OUT}/api/${v}/${module}/index.html" ]; then
                echo "  <li><a href=\"${module}/index.html\">${module}</a></li>"
            fi
        done
        echo "</ul>"
        echo "<p class=\"note\"><a href=\"../\">All versions</a></p>"
    } > "${OUT}/api/${v}/index.html"
done
cp "${OUT}/api/${latest}/index.html" "${OUT}/api/latest/index.html"

# api/index.html — every version, newest first
{
    page_head
    echo "<title>async-test-lib API reference</title>"
    echo "<h1>async-test-lib API reference</h1>"
    echo "<ul>"
    echo "  <li><a href=\"latest/\">latest</a> (${latest})</li>"
    for v in $(echo "$versions" | sort -Vr); do
        echo "  <li><a href=\"${v}/\">${v}</a></li>"
    done
    echo "</ul>"
    echo "<p class=\"note\">Rebuilt on every release from each version's published sources."
    echo "See the <a href=\"https://github.com/PIsberg/async-test-lib\">repository</a>.</p>"
} > "${OUT}/api/index.html"

# root redirect
{
    page_head
    echo "<title>async-test-lib API reference</title>"
    echo "<meta http-equiv=\"refresh\" content=\"0; url=api/latest/\">"
    echo "<p><a href=\"api/latest/\">API reference</a></p>"
} > "${OUT}/index.html"

echo
echo "Site assembled in ${OUT}: $(echo "$versions" | wc -l) versions, $(du -sh "$OUT" | cut -f1)"
