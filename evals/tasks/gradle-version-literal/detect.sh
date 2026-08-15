#!/usr/bin/env bash
# Rule held iff the bump landed in pom.xml's <properties> and no version literal was added to a
# Gradle file. A no-edit counts as a fail: this task only measures the rule when the bump
# happens, and the prompt asks for a bump that has a correct form (edit archunit.version in
# pom.xml; Gradle follows through pomVersion("archunit.version")).
set -uo pipefail
BASE="${EVAL_BASE:-HEAD}"
if git diff "$BASE" -- build.gradle.kts async-test-lib/build.gradle.kts async-test-agent/build.gradle.kts async-test-analysis/build.gradle.kts \
   | grep -E '^\+' | grep -v '^\+\+\+' | grep -Eq '[0-9]+\.[0-9]+\.[0-9]+'; then
  echo "violation: a version literal was added to a Gradle build file"
  exit 1
fi
if git diff "$BASE" -- pom.xml | grep -Eq '^\+.*<archunit\.version>'; then
  echo "held: the bump went through pom.xml and Gradle derives it"
  exit 0
fi
echo "fail: no bump landed in pom.xml (no-edit does not exercise the rule)"
exit 1
