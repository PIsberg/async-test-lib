#!/usr/bin/env bash
# Bump every dependency and build-tool version this repository pins to the latest release.
#
#   bash .claude/skills/bumpdeps/bump-deps.sh [--dry-run]
#
# Two layers, because Dependabot only watches the reactor root:
#
#   1. Reactor (pom.xml + module poms). versions-maven-plugin rewrites the <properties> pins to
#      the latest release, pre-releases excluded. build.gradle.kts reads those properties, so
#      Gradle follows for free (BuildMetadataSyncTest pins that derivation).
#   2. Satellites: consumer-fixture/, consumer-fixture-langs/, examples/*/, load-tests/ and the
#      Gradle-only pins in build.gradle.kts. Each is a file pattern plus the Maven coordinate
#      whose latest release belongs there; the RULES table below is the whole list, so a new
#      pin is one line here.
#
# What it deliberately leaves alone (reported, never rewritten):
#   - the async-test-lib version pins: that is the release skill's job (bump-version.sh)
#   - the japicmp <oldVersion> baseline in async-test-lib/pom.xml (docs/RELEASE.md)
#   - the Gradle wrapper: `gradle wrapper --gradle-version X` must regenerate the jar so the
#     wrapper-validation step keeps passing; run it by hand when the report says so
#   - intellij-plugin/: no workflow builds it, so a bump there cannot be verified in CI
#   - GitHub Actions SHAs: Dependabot's github-actions ecosystem owns those
#
# Exit 0 with a report either way; a fetch failure for one coordinate is printed and skipped,
# never a silent "already latest".
set -euo pipefail

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
HERE="$REPO_ROOT/.claude/skills/bumpdeps"
LATEST="$HERE/latest-version.sh"
CENTRAL="https://repo1.maven.org/maven2"
PORTAL="https://plugins.gradle.org/m2"

# Pre-release markers the reactor pass must skip: the same list latest-version.sh applies to
# the satellites, spelled the way the versions plugin wants it (comma-separated regexes).
IGNORE='.*-(alpha|beta|rc|RC|M|m|ea|preview|dev|SNAPSHOT|jdk[0-9]).*,.*-alpha[0-9]*,.*-beta-?[0-9]*,.*-vt-.*'

echo "== 1. Reactor properties (versions-maven-plugin)"
if [[ $DRY_RUN -eq 1 ]]; then
  mvn -q -N versions:display-property-updates -DgenerateBackupPoms=false \
      "-Dmaven.version.ignore=$IGNORE" 2>&1 | grep -E '\$\{.*\} .* -> ' || echo "  (no reactor property updates)"
else
  mvn -q -N versions:update-properties -DgenerateBackupPoms=false \
      "-Dmaven.version.ignore=$IGNORE" 2>&1 | grep -E 'Updat|ERROR' || echo "  (no reactor property updates)"
fi
echo
# ---- satellite rules -------------------------------------------------------------------
# glob | perl regex: (pre)VERSION(post), VERSION written as [^<]+ or [^"]+ | groupId | artifactId | C=Central P=plugin portal
RULES=(
  # JUnit everywhere the reactor's property does not reach
  'consumer-fixture/pom.xml|(<junit\.jupiter\.version>)[^<]+(</junit\.jupiter\.version>)|org.junit.jupiter|junit-jupiter|C'
  'consumer-fixture/build.gradle.kts|(val junitVersion = ")[^"]+(")|org.junit.jupiter|junit-jupiter|C'
  'consumer-fixture-langs/pom.xml|(<junit\.jupiter\.version>)[^<]+(</junit\.jupiter\.version>)|org.junit.jupiter|junit-jupiter|C'
  'consumer-fixture-langs/build.gradle.kts|(val junitVersion = ")[^"]+(")|org.junit.jupiter|junit-jupiter|C'
  'examples/*/pom.xml|(<junit\.version>)[^<]+(</junit\.version>)|org.junit.jupiter|junit-jupiter|C'
  'examples/*/build.gradle.kts|(val junitVersion = ")[^"]+(")|org.junit.jupiter|junit-jupiter|C'
  'examples/*/build.gradle.kts|(val junitPlatformVersion = ")[^"]+(")|org.junit.platform|junit-platform-launcher|C'
  'load-tests/build.gradle.kts|(val junitVersion = ")[^"]+(")|org.junit.jupiter|junit-jupiter|C'
  # Language toolchains of consumer-fixture-langs (Maven properties + Gradle literals)
  'consumer-fixture-langs/pom.xml|(<kotlin\.version>)[^<]+(</kotlin\.version>)|org.jetbrains.kotlin|kotlin-stdlib|C'
  'consumer-fixture-langs/kotlin/build.gradle.kts|(kotlin\("jvm"\) version ")[^"]+(")|org.jetbrains.kotlin.jvm|org.jetbrains.kotlin.jvm.gradle.plugin|P'
  'consumer-fixture-langs/pom.xml|(<groovy\.version>)[^<]+(</groovy\.version>)|org.apache.groovy|groovy|C'
  'consumer-fixture-langs/groovy/build.gradle.kts|(org\.apache\.groovy:groovy:)[^"]+(")|org.apache.groovy|groovy|C'
  'consumer-fixture-langs/pom.xml|(<gmavenplus-plugin\.version>)[^<]+(</gmavenplus-plugin\.version>)|org.codehaus.gmavenplus|gmavenplus-plugin|C'
  'consumer-fixture-langs/pom.xml|(<scala\.version>)[^<]+(</scala\.version>)|org.scala-lang|scala3-library_3|C'
  'consumer-fixture-langs/scala/build.gradle.kts|(org\.scala-lang:scala3-library_3:)[^"]+(")|org.scala-lang|scala3-library_3|C'
  'consumer-fixture-langs/pom.xml|(<scala-maven-plugin\.version>)[^<]+(</scala-maven-plugin\.version>)|net.alchim31.maven|scala-maven-plugin|C'
  'consumer-fixture-langs/pom.xml|(<clojure\.version>)[^<]+(</clojure\.version>)|org.clojure|clojure|C'
  'consumer-fixture-langs/pom.xml|(<clojure-maven-plugin\.version>)[^<]+(</clojure-maven-plugin\.version>)|com.theoryinpractise|clojure-maven-plugin|C'
  'consumer-fixture-langs/pom.xml|(<maven-surefire-plugin\.version>)[^<]+(</maven-surefire-plugin\.version>)|org.apache.maven.plugins|maven-surefire-plugin|C'
  'consumer-fixture/pom.xml|(<maven-surefire-plugin\.version>)[^<]+(</maven-surefire-plugin\.version>)|org.apache.maven.plugins|maven-surefire-plugin|C'
  'consumer-fixture/pom.xml|(<maven-compiler-plugin\.version>)[^<]+(</maven-compiler-plugin\.version>)|org.apache.maven.plugins|maven-compiler-plugin|C'
  # The Kotlin example
  'examples/128-kotlin-lost-update/pom.xml|(<kotlin\.version>)[^<]+(</kotlin\.version>)|org.jetbrains.kotlin|kotlin-stdlib|C'
  'examples/128-kotlin-lost-update/build.gradle.kts|(kotlin\("jvm"\) version ")[^"]+(")|org.jetbrains.kotlin.jvm|org.jetbrains.kotlin.jvm.gradle.plugin|P'
  # Gradle-only pins in the root build (everything else there is pomVersion(...))
  'build.gradle.kts|(extra\["logbackVersion"\] = ")[^"]+(")|ch.qos.logback|logback-classic|C'
  'build.gradle.kts|(id\("com\.vanniktech\.maven\.publish"\) version ")[^"]+(")|com.vanniktech.maven.publish|com.vanniktech.maven.publish.gradle.plugin|P'
  'build.gradle.kts|(id\("net\.ltgt\.errorprone"\) version ")[^"]+(")|net.ltgt.errorprone|net.ltgt.errorprone.gradle.plugin|P'
  'build.gradle.kts|(id\("com\.github\.spotbugs"\) version ")[^"]+(")|com.github.spotbugs|com.github.spotbugs.gradle.plugin|P'
  'build.gradle.kts|(id\("org\.cyclonedx\.bom"\) version ")[^"]+(")|org.cyclonedx.bom|org.cyclonedx.bom.gradle.plugin|P'
  'load-tests/build.gradle.kts|(id\("me\.champeau\.jmh"\) version ")[^"]+(")|me.champeau.jmh|me.champeau.jmh.gradle.plugin|P'
)

echo "== 2. Satellite pins"
declare -A LATEST_CACHE
changed_files=0
for rule in "${RULES[@]}"; do
  IFS='|' read -r glob regex group artifact repoflag <<<"$rule"
  repo="$CENTRAL"; [[ "$repoflag" == "P" ]] && repo="$PORTAL"
  key="$group:$artifact"
  if [[ -z "${LATEST_CACHE[$key]:-}" ]]; then
    if ! LATEST_CACHE[$key]="$(bash "$LATEST" "$group" "$artifact" "$repo")"; then
      echo "  ! $key: could not resolve latest (skipped)"; LATEST_CACHE[$key]="?"; continue
    fi
  fi
  latest="${LATEST_CACHE[$key]}"
  [[ "$latest" == "?" ]] && continue
  # Same regex with the version part promoted to a capture group, to read the current value.
  read_regex="${regex//\[^<\]+/([^<]+)}"
  read_regex="${read_regex//\[^\"\]+/([^\"]+)}"
  # shellcheck disable=SC2086
  for f in $glob; do
    [[ -f "$f" ]] || continue
    current="$(READ_RE="$read_regex" perl -ne 'if (/$ENV{READ_RE}/) { print "$2\n"; exit }' "$f")"
    [[ -z "$current" || "$current" == "$latest" ]] && continue
    printf '  %-55s %s -> %s  (%s)\n' "$f" "$current" "$latest" "$key"
    if [[ $DRY_RUN -eq 0 ]]; then
      RE="$regex" LATEST_V="$latest" perl -pi -e 's{$ENV{RE}}{$1$ENV{LATEST_V}$2}g' "$f"
      changed_files=$((changed_files + 1))
    fi
  done
done
[[ $changed_files -eq 0 && $DRY_RUN -eq 0 ]] && echo "  (no satellite pin changed)"
echo

echo "== 3. Manual, reported only"
wrapper_current="$(sed -n 's|.*gradle-\([0-9][0-9.]*\)-bin.zip|\1|p' gradle/wrapper/gradle-wrapper.properties)"
wrapper_latest="$(curl -fsSL https://services.gradle.org/versions/current | sed -n 's|.*"version" *: *"\([^"]*\)".*|\1|p')" || wrapper_latest="?"
if [[ "$wrapper_current" == "$wrapper_latest" ]]; then
  echo "  gradle wrapper: $wrapper_current (current)"
else
  echo "  gradle wrapper: $wrapper_current -> $wrapper_latest  run: gradle wrapper --gradle-version $wrapper_latest  (regenerates the jar; wrapper-validation checks it)"
fi
ij="$(sed -n 's|.*id("org.jetbrains.intellij.platform") version "\([^"]*\)".*|\1|p' intellij-plugin/build.gradle.kts 2>/dev/null || true)"
[[ -n "$ij" ]] && echo "  intellij-plugin: org.jetbrains.intellij.platform $ij (no CI builds this module; bump and verify by hand: ./gradlew -p intellij-plugin build)"
echo "  japicmp <oldVersion> in async-test-lib/pom.xml: untouched by design (previous release; docs/RELEASE.md)"
echo "  async-test-lib version pins: untouched; that is .claude/skills/release/bump-version.sh"
echo "  GitHub Actions SHAs: Dependabot (github-actions ecosystem)"
echo

if [[ $DRY_RUN -eq 0 ]]; then
  echo "== Changed files"
  git --no-pager diff --stat | tail -40
  echo
  echo "Next: verify (SKILL.md): mvn install -DskipTests -Djacoco.skip=true; mvn test -P fast; ./gradlew test;"
  echo "      the fixtures; a few examples. A vibetags bump regenerates guardrails: commit them."
fi
