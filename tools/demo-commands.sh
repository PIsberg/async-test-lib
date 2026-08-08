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
echo -e "${BOLD}@AsyncTest${RESET} — catch concurrency bugs before they reach production"
echo ""
sleep 1.0

step "1. A counter incremented by 6 concurrent threads — read, add one, write back"
cmd "cat tools/demo/src/test/java/se/deversity/asynctest/demo/CounterTest.java"

step "2. Run the stress test — @AsyncTest hammers it with 6 threads × 3 rounds"
# The `| head` is deliberately visible in the recording rather than hidden: the finding is
# followed by ~70 lines of LEARNING and AUTO-FIX guidance, which is genuinely useful at a
# terminal and would scroll the actual result off a 26-row GIF. Showing the pipe means the
# viewer knows the output was cut, instead of being told this is all the tool prints.
cmd "mvn test -f tools/demo/pom.xml -q 2>&1 | head -n 20 || true"

exit 0
