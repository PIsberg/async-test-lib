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
# The diagrams are committed, and CI regenerates them on every push and pull request and
# fails on structural drift (the `diagrams` job in .github/workflows/guardrails.yml,
# comparing tools/diagram-structure.sh fingerprints). Structure, not bytes: regeneration is
# idempotent on one machine, but code-karta's directory walk follows filesystem order, so
# node positions differ between the OS that committed and the OS that checks. A structural
# diff means the architecture moved, which is exactly when someone should look, and the
# gate makes sure someone does. Regenerate locally and commit the SVGs, or let the failing
# job hand you the fresh ones as an artifact.
set -eu

CK_VERSION="0.2.0"
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

# Each class diagram is scoped to a package that answers one question. A whole-tree run is
# deliberately not attempted: breadth, not depth, is what makes these unreadable, so
# scoping the input beats tuning --max-depth.
#
# --output-name (code-karta 0.2.0) is what lets these share one directory. Before it every
# class diagram was called class-diagram.svg, so each scope needed a subdirectory of its
# own -- a layout that said nothing about the diagrams and everything about a limitation
# of the generator.

echo "Module diagram: the Maven reactor ..."
# Needs code-karta 0.2.0: before it, --modules-only understood module-info.java only and
# returned "Graph is empty" here, because this project declares no JPMS modules.
java -jar "$CK_JAR" --input . --output "$OUT_DIR" --modules-only \
                    --output-name modules-diagram.svg --layout "$LAYOUT"

echo "Class diagram: the public surface and wiring ..."
java -jar "$CK_JAR" --input "$SRC" --output "$OUT_DIR" \
                    --output-name class-diagram.svg --layout "$LAYOUT"

echo "Class diagram: the runner ..."
java -jar "$CK_JAR" --input "$SRC/runner" --output "$OUT_DIR" \
                    --output-name runner-class-diagram.svg --layout "$LAYOUT"

echo "Class diagram: the detector SPI ..."
# --max-members all: the SPI is a handful of types, and what each one declares is the whole
# point of the diagram. The six-member default is sized for a large package, not for this.
java -jar "$CK_JAR" --input "$SRC/spi" --output "$OUT_DIR" --max-members all \
                    --output-name spi-class-diagram.svg --layout "$LAYOUT"

echo "Class diagram: the reporting pipeline ..."
java -jar "$CK_JAR" --input "$SRC/report" --output "$OUT_DIR" \
                    --output-name report-class-diagram.svg --layout "$LAYOUT"

echo "Sequence diagram: one @AsyncTest invocation through ConcurrencyRunner ..."
java -jar "$CK_JAR" --input "$SRC/runner/ConcurrencyRunner.java" --output "$OUT_DIR" \
                    --output-name concurrencyrunner-sequence-diagram.svg --layout "$LAYOUT"

echo
echo "Done. Generated under $OUT_DIR:"
find "$OUT_DIR" -name '*.svg' -print | sort
