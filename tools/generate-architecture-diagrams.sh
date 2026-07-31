#!/bin/sh
# Regenerate the code-karta architecture diagrams under docs/diagrams/codekarta/.
#
# These complement the PlantUML diagrams in docs/diagrams/ rather than replacing them.
# The PlantUML sources are hand-drawn and say what the design intends; these are parsed
# from the source and say what it currently is. When the two disagree, that gap is the
# interesting part.
#
# code-karta is resolved from Maven Central rather than vendored, so this needs a network
# on first run and nothing afterwards.
#
# Usage:  sh tools/generate-architecture-diagrams.sh
#
# The diagrams are committed. Regenerate them when the package structure changes, not on
# every commit: a diff here means the architecture moved.
set -eu

CK_VERSION="0.1.0"
CK_COORDS="se.deversity.codekarta:code-karta-cli:${CK_VERSION}:jar:all"
OUT_DIR="docs/diagrams/codekarta"
SRC="async-test-lib/src/main/java/se/deversity/asynctest"

# --layout elk is deliberate. The default 'simple' engine lays every node of one BFS depth
# into a single unbounded row; diagnostics/ alone has ~138 types, most of them unconnected
# to each other, which renders tens of thousands of pixels wide. ELK's layered algorithm
# keeps the same graph near 2300px.
LAYOUT="elk"

repo_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_root"

command -v mvn  >/dev/null 2>&1 || { echo "error: mvn is not on PATH"  >&2; exit 1; }
command -v java >/dev/null 2>&1 || { echo "error: java is not on PATH" >&2; exit 1; }

echo "Resolving ${CK_COORDS} ..."
mvn -B -q dependency:get -Dartifact="${CK_COORDS}" \
  || { echo "error: could not resolve code-karta ${CK_VERSION} from Maven Central" >&2; exit 1; }

M2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
CK_JAR="$M2/se/deversity/codekarta/code-karta-cli/${CK_VERSION}/code-karta-cli-${CK_VERSION}-all.jar"
[ -f "$CK_JAR" ] || { echo "error: code-karta CLI jar not found at $CK_JAR" >&2; exit 1; }

mkdir -p "$OUT_DIR"

# Each diagram is scoped to a package that answers one question. A whole-tree run is
# deliberately not attempted: breadth, not depth, is what makes these unreadable, so
# scoping the input beats tuning --max-depth.

echo "Class diagram: the public surface and wiring ..."
java -jar "$CK_JAR" --input "$SRC" --output "$OUT_DIR" --layout "$LAYOUT"

echo "Class diagram: the runner ..."
java -jar "$CK_JAR" --input "$SRC/runner" --output "$OUT_DIR/runner" --layout "$LAYOUT"

echo "Class diagram: the detector SPI ..."
java -jar "$CK_JAR" --input "$SRC/spi" --output "$OUT_DIR/spi" --layout "$LAYOUT"

echo "Class diagram: the reporting pipeline ..."
java -jar "$CK_JAR" --input "$SRC/report" --output "$OUT_DIR/report" --layout "$LAYOUT"

echo "Sequence diagram: one @AsyncTest invocation through ConcurrencyRunner ..."
java -jar "$CK_JAR" --input "$SRC/runner/ConcurrencyRunner.java" \
                    --output "$OUT_DIR/runner" --layout "$LAYOUT"

echo
echo "Done. Generated under $OUT_DIR:"
find "$OUT_DIR" -name '*.svg' -print | sort
