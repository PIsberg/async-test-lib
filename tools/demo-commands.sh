#!/usr/bin/env bash
# Demo script recorded by asciinema to generate docs/diagrams/demo.gif.
# Mirrors the approach used in PIsberg/vibetags.

RESET='\033[0m'
BOLD='\033[1m'
DIM='\033[2m'
GREEN='\033[32m'

step() { echo -e "\n${DIM}# $*${RESET}"; sleep 0.7; }
cmd()  {
    echo -e "${GREEN}\$${RESET} ${BOLD}$*${RESET}"
    sleep 0.5
    eval "$*"
    echo ""
    sleep 1.2
}

clear
echo -e "${BOLD}async-test${RESET} — catch concurrency bugs before they reach production"
echo ""
sleep 1.0

step "1. A shared ArrayList accessed from 6 concurrent threads"
cmd "cat tools/demo/src/test/java/se/deversity/asynctest/demo/SharedListTest.java"

step "2. Run the stress test — async-test hammers it with 6 threads × 3 rounds"
cmd "mvn test -f tools/demo/pom.xml -q 2>&1 || true"

exit 0
