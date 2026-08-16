#!/usr/bin/env bash
# Print the latest release version of a Maven artifact, ignoring pre-releases.
#
#   latest-version.sh <groupId> <artifactId> [repo-base-url]
#
# Reads maven-metadata.xml from Maven Central by default (or the Gradle plugin portal, whose
# plugin markers are Maven artifacts named <id>:<id>.gradle.plugin). "Latest release" means the
# highest version in <versions> that carries no pre-release marker; the <latest>/<release>
# elements are not trusted because some projects publish an -M1 or -alpha there.
#
# Pre-release filter: anything with -alpha, -beta, -rc, -m<n>, -ea, -snapshot, -preview, -dev,
# a +classifier suffix such as -jdk5, or a project-specific test tag. Case-insensitive.
set -euo pipefail

group="${1:?groupId}"
artifact="${2:?artifactId}"
repo="${3:-https://repo1.maven.org/maven2}"

path="${repo}/${group//.//}/${artifact}/maven-metadata.xml"
xml="$(curl -fsSL "$path")" || { echo "error: cannot fetch $path" >&2; exit 1; }

printf '%s\n' "$xml" \
  | grep -o '<version>[^<]*</version>' \
  | sed 's|<version>\(.*\)</version>|\1|' \
  | grep -viE -- '-(alpha|beta|rc|m[0-9]|ea|snapshot|preview|dev|jdk[0-9]|vt-)' \
  | grep -viE 'transitive-test' \
  | sort -V \
  | tail -1
