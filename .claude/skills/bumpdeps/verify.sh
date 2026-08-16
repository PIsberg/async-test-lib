#!/usr/bin/env bash
# Runs every gate a dependency bump can break and writes a one-line verdict per gate to $log
# (default /tmp/verify-bump.log). Read the log, not the exit code: it reports counts per suite.
#   bash .claude/skills/bumpdeps/verify.sh [logfile]
cd "$(git rev-parse --show-toplevel)"
log="${1:-/tmp/verify-bump.log}"; : > "$log"
step() { echo "### $1" >> $log; }
step "fast suite"; mvn -q -pl async-test-lib -am test -P fast >> $log 2>&1; echo "fast EXIT=$?" >> $log
cat async-test-lib/target/surefire-reports/*.txt | grep -h "^Tests run" | awk -F'[:,]' '{r+=$2; f+=$4; e+=$6; s+=$8} END {print "fast run="r" fail="f" err="e" skip="s}' >> $log
step "gradle root test"; ./gradlew -q test >> $log 2>&1; echo "gradle EXIT=$?" >> $log
step "gradle publishToMavenLocal"; ./gradlew -q publishToMavenLocal >> $log 2>&1; echo "publishLocal EXIT=$?" >> $log
step "consumer-fixture mvn"; mvn -q -f consumer-fixture/pom.xml test >> $log 2>&1; echo "cf-mvn EXIT=$?" >> $log
cat consumer-fixture/target/surefire-reports/*.txt | grep -h "^Tests run" | awk -F'[:,]' '{r+=$2; f+=$4; e+=$6} END {print "cf run="r" fail="f" err="e}' >> $log
step "consumer-fixture gradle"; ./gradlew -q -p consumer-fixture test >> $log 2>&1; echo "cf-gradle EXIT=$?" >> $log
step "consumer-fixture-langs mvn"; mvn -q -f consumer-fixture-langs/pom.xml test >> $log 2>&1; echo "cfl-mvn EXIT=$?" >> $log
cat consumer-fixture-langs/*/target/surefire-reports/*.txt | grep -h "^Tests run" | awk -F'[:,]' '{r+=$2; f+=$4; e+=$6} END {print "cfl run="r" fail="f" err="e}' >> $log
grep -h "Ran [0-9]* tests containing" "$log" | tail -1 | sed "s/^/clojure.test: /" >> "$log"
step "consumer-fixture-langs gradle"; ./gradlew -q -p consumer-fixture-langs test >> $log 2>&1; echo "cfl-gradle EXIT=$?" >> $log
step "examples 01, 128 (mvn)"; mvn -q -f examples/01-completablefuture-exception-handling/pom.xml test >> $log 2>&1; echo "ex01 EXIT=$?" >> $log; mvn -q -f examples/128-kotlin-lost-update/pom.xml test >> $log 2>&1; echo "ex128 EXIT=$?" >> $log
step "examples 01 (gradle)"; ./gradlew -q -p examples/01-completablefuture-exception-handling test >> $log 2>&1; echo "ex01-gradle EXIT=$?" >> $log
step "load-tests compile"; ./gradlew -q -p load-tests compileJava compileTestJava >> $log 2>&1; echo "load EXIT=$?" >> $log
echo DONE >> $log
