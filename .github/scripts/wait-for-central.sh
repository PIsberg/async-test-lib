#!/usr/bin/env bash
# Wait until Maven Central serves this release's jars, byte for byte, and fail if it does not.
#
# central-publishing-maven-plugin runs with waitUntil=uploaded (pom.xml): waiting for
# "published" inside `mvn deploy` got the job killed mid-poll in 1.7.0 and 1.9.1. The cost was
# that a green Publish Release meant only "the bundle was uploaded". v1.13.0's run went green
# while nothing reached Central, and nobody was told (#949, #952). This step is that telling.
#
# Usage:  wait-for-central.sh <version> [<reactor-root>]
#
# For each module it polls <module>-<version>.jar.sha1 on repo1.maven.org and compares it with
# the sha1 of <reactor-root>/<module>/target/<module>-<version>.jar. Exit 0 once all three match;
# exit 1 on a mismatch, on a jar this run did not build, or when the bound runs out.
#   CENTRAL_WAIT_SECONDS  the bound, default 3600 (the release skill expects 15 to 30 minutes)
#   CENTRAL_POLL_SECONDS  between polls, default 60
set -euo pipefail

if [ $# -lt 1 ]; then
  sed -n '2,15p' "$0"
  exit 2
fi
VERSION=$1
ROOT=${2:-.}
TIMEOUT=${CENTRAL_WAIT_SECONDS:-3600}
INTERVAL=${CENTRAL_POLL_SECONDS:-60}
BASE=https://repo1.maven.org/maven2/se/deversity/async-test-lib

pending=(async-test-lib async-test-agent async-test-analysis)
deadline=$((SECONDS + TIMEOUT))
while :; do
  still=()
  for module in "${pending[@]}"; do
    jar="$ROOT/$module/target/$module-$VERSION.jar"
    if [ ! -f "$jar" ]; then
      echo "::error::$jar was not built by this run, so there is nothing to compare Central with"
      exit 1
    fi
    remote=$(curl -fsS "$BASE/$module/$VERSION/$module-$VERSION.jar.sha1" 2>/dev/null | awk '{print $1}') || remote=""
    if [ -z "$remote" ]; then
      still+=("$module")
      continue
    fi
    built=$(sha1sum "$jar" | awk '{print $1}')
    if [ "$remote" != "$built" ]; then
      echo "::error::Central serves $module $VERSION with sha1 $remote, but this run built $built"
      exit 1
    fi
    echo "$module $VERSION is on Maven Central (sha1 $built)"
  done
  if [ ${#still[@]} -eq 0 ]; then
    echo "Every module of $VERSION is on Maven Central and matches what this run built."
    exit 0
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "::error::After ${TIMEOUT}s Maven Central still does not serve $VERSION of: ${still[*]}." \
      "The upload succeeded, so read the deployment's state at" \
      "https://central.sonatype.com/publishing/deployments"
    exit 1
  fi
  pending=("${still[@]}")
  echo "Waiting for ${still[*]} $VERSION on Maven Central ($((deadline - SECONDS))s left)"
  sleep "$INTERVAL"
done
