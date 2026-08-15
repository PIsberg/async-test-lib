#!/bin/sh
# Compare a fresh load-test result set against the newest committed baseline, row by row.
#
# Usage: sh load-tests/tools/compare-baseline.sh <fresh-results-dir> [<baseline-dir>]
#
# Rows are joined on their key columns (throughput.csv: threads,invocations,detectAll;
# memory.csv: threads,invocations) and the measured column is compared as a ratio. The
# bands are wide on purpose: baselines are recorded on whichever machine cut the release,
# and the nightly ring runs on a shared runner, so this is a trend detector, not a gate.
# Above the band it prints a GitHub ::warning:: line and exits 0. The inner-loop gate that
# does fail a build is RunnerAllocationBudgetTest, which asserts an allocation ceiling that
# is stable across machines. Wall-clock is never asserted anywhere.
#
# Missing rows (fast mode records a subset) are reported and skipped, not counted.
set -eu

fresh="$1"
if [ $# -ge 2 ]; then
  baseline="$2"
else
  results="$(dirname "$fresh")"
  fresh_name="$(basename "$fresh")"
  # newest committed version directory that is not the fresh one; _plots is not a result set
  baseline="$(ls -d "$results"/*/ 2>/dev/null | sed 's:/$::' \
    | grep -v '/_plots$' | grep -v "/$fresh_name\$" | sort -V | tail -1 || true)"
fi
if [ -z "${baseline:-}" ] || [ ! -d "$baseline" ]; then
  echo "::notice::compare-baseline: no committed baseline to compare against; nothing compared."
  exit 0
fi
echo "compare-baseline: fresh=$fresh baseline=$baseline"

# compare <file> <key-column-count> <measured-column-index> <band-multiplier> <label>
compare() {
  file="$1"; keys="$2"; col="$3"; band="$4"; label="$5"
  if [ ! -f "$fresh/$file" ] || [ ! -f "$baseline/$file" ]; then
    echo "  $file: missing on one side, skipped"
    return 0
  fi
  awk -F',' -v keys="$keys" -v col="$col" -v band="$band" -v label="$label" -v file="$file" '
    function key(   k, i) { k = $1; for (i = 2; i <= keys; i++) k = k "," $i; return k }
    /^#/ || /^[a-zA-Z]/ || NF < col { next }
    FNR == NR { base[key()] = $col; next }
    {
      k = key(); if (!(k in base)) { printf "  %s [%s]: no baseline row, skipped\n", file, k; next }
      b = base[k] + 0; f = $col + 0
      if (b <= 0) { next }
      ratio = f / b
      printf "  %s [%s] %s: baseline=%s fresh=%s ratio=%.2f\n", file, k, label, b, f, ratio
      if (ratio > band) {
        printf "::warning::%s [%s]: %s is %.2fx the committed baseline (%s -> %s); band is %sx. A trend, not a gate: check RunnerAllocationBudgetTest and load-tests/README.md before reading it as a regression.\n", file, k, label, ratio, b, f, band
      }
    }' "$baseline/$file" "$fresh/$file"
}

compare throughput.csv 3 4 1.5 medianMs
compare memory.csv 2 4 2.0 allDetectorAllocKB
exit 0
