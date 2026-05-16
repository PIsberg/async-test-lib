#!/usr/bin/env bash
# Simulates async-test output for the demo GIF.
# Matches the real output format from RaceConditionDetector.

RESET='\033[0m'
RED='\033[31m'
YELLOW='\033[33m'
BLUE='\033[34m'
BOLD='\033[1m'
DIM='\033[2m'

sleep 0.3
echo -e "${BLUE}[INFO]${RESET} --- maven-surefire-plugin:3.5.5:test ---"
echo -e "${BLUE}[INFO]${RESET} Running CounterTest"
sleep 1.4

echo ""
echo -e "${YELLOW}${BOLD}🟠 HIGH${RESET}: Potential race conditions detected — unsynchronized writes to shared"
echo "fields allow threads to overwrite each other's changes, producing lost updates,"
echo "stale reads, and silently wrong results"
echo ""
echo "Concurrent write hotspots:"
echo -e "  - ${BOLD}CounterTest@6b8f3c1.counter${RESET}: 8 writes observed across 8 threads"
echo ""
echo "Unsynchronized access sequences:"
echo "  - counter: thread 47 write followed immediately by thread 48 write"
echo "  - counter: thread 50 read followed immediately by thread 51 write"
echo ""
printf "${DIM}"; printf '─%.0s' {1..62}; printf "${RESET}\n"
echo -e "${BOLD}Auto-Fix${RESET}: replace plain \`int\` with AtomicInteger"
echo ""
echo -e "  ${DIM}// Before (not thread-safe)${RESET}"
echo -e "  ${RED}private int counter = 0;${RESET}"
echo -e "  ${RED}counter = value + 1;${RESET}"
echo ""
echo -e "  ${DIM}// After (lock-free, thread-safe)${RESET}"
echo -e "  private final AtomicInteger counter = new AtomicInteger();"
echo -e "  counter.incrementAndGet();"
printf "${DIM}"; printf '─%.0s' {1..62}; printf "${RESET}\n"
sleep 0.5

echo ""
echo -e "${RED}[ERROR]${RESET} Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 1.83 s"
echo -e "${RED}[ERROR]${RESET} CounterTest#counter_mustBeThreadSafe  <<<  FAILURE!"
echo -e "${RED}[INFO]${RESET}  BUILD FAILURE"
